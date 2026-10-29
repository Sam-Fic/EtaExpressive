package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.media.AgentToolMediaPreviews
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ConversationCheckpointTooLargeException
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeRunDao
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.decodeUserMessageImages
import io.github.mangi.eta.ui.model.encodeUserMessageImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.json.JSONArray
import java.util.UUID

/**
 * 会话分析导出 / 导入。
 *
 * 导出走 JSON 单文件：除了消息，还带每回合的 token 账单、工具调用明细，
 * 以及可选的原始模型 I/O 与运行时事件日志。
 * 导入把同一份文件还原成一条普通新会话——可以接着聊。
 */
internal object EtaConversationAnalysisRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** 应用认识的会话消息类型；其余类型在导入时直接丢弃。 */
    private val IMPORTABLE_MESSAGE_TYPES = setOf(
        "user",
        "assistant",
        "system_notice",
        "error_reconnect",
        "thinking",
        "tool",
        "tool_summary",
        "context_compacted",
        "question",
    )

    suspend fun export(
        context: Context,
        conversationId: String,
        options: EtaConversationAnalysisOptions = EtaConversationAnalysisOptions(),
    ): EtaConversationAnalysisDocument = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val database = EtaDatabase.get(appContext)
        val dao = database.conversationDao()
        val conversation = dao.conversationEntity(conversationId) ?: error("会话不存在")
        val rows = dao.messagesForConversation(conversationId)
        val checkpoint = dao.contextCheckpoint(conversationId)
        val runIds = decodeStringList(conversation.appliedRuntimeRunIdsJson)

        val messages = rows.map { row -> row.toAnalysisMessage(options.includeImages) }
        val rounds = EtaConversationAnalysisRounds.segment(messages, runIds)
        val summary = EtaConversationAnalysisRounds.summarize(messages, rounds)
        val history = readHistory(appContext, conversationId, checkpoint)
        val runtimeRuns = if (options.includeRawRuntimeIo) {
            loadRuntimeRuns(database.runtimeRunDao(), runIds)
        } else {
            emptyList()
        }

        EtaConversationAnalysisDocument(
            exportedAt = System.currentTimeMillis(),
            conversation = EtaConversationAnalysisConversation(
                id = conversation.id,
                title = conversation.title,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt,
                thinkingEnabled = conversation.thinkingEnabled,
                reasoningEffort = conversation.reasoningEffort,
                providerId = conversation.providerId,
                modelId = conversation.modelId,
                assistantId = conversation.assistantId,
                folderId = conversation.folderId,
            ),
            options = options,
            summary = summary,
            rounds = rounds,
            messages = messages,
            history = history,
            runtimeRuns = runtimeRuns,
        )
    }

    /** 把导出文件还原成一条新会话，返回新会话 id。 */
    suspend fun import(
        context: Context,
        document: EtaConversationAnalysisDocument,
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val id = "conv-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val reasoningEffort = ReasoningEffort.entries
            .firstOrNull { it.wireValue == document.conversation.reasoningEffort }
            ?.wireValue
            ?: ReasoningEffort.DEFAULT.wireValue
        val conversation = ConversationEntity(
            id = id,
            title = document.conversation.title,
            thinkingEnabled = document.conversation.thinkingEnabled,
            reasoningEffort = reasoningEffort,
            historyJson = "[]",
            appliedRuntimeRunIdsJson = "[]",
            createdAt = now,
            updatedAt = now,
            folderId = "",
            isPinned = false,
            hasCompletionMarker = false,
            // 提供商与模型是本机的东西，导入方未必有；留空让会话回落到本机默认模型。
            providerId = "",
            modelId = "",
            assistantId = "",
        )
        val messages = document.messages
            .filter { it.type in IMPORTABLE_MESSAGE_TYPES }
            .mapIndexed { index, message -> message.toEntity(id, index) }
        val checkpoint = document.history.takeIf { it.isNotEmpty() }?.let { history ->
            ConversationContextCheckpointEntity(
                conversationId = id,
                historyJson = encodeCheckpoint(history),
                cloudUsageJson = "",
            )
        }
        val database = EtaDatabase.get(appContext)
        database.withTransaction {
            database.conversationDao().importAsNewConversation(conversation, messages, checkpoint)
        }
        id
    }

    /**
     * 模型历史优先读文件，其次读检查点——与应用自己的加载顺序一致。
     *
     * 少了这一步，导入方拿不到工具角色的历史，新会话只能靠消息行反推，工具上下文会丢。
     */
    private fun readHistory(
        context: Context,
        conversationId: String,
        checkpoint: ConversationContextCheckpointEntity?,
    ): List<AgentModelClient.ConversationMessage> {
        val file = AgentConversationHistoryFiles.file(context, conversationId)
        if (file.isFile) {
            val fromFile = runCatching {
                AgentConversationCodec.decodeTranscript(file.readText(Charsets.UTF_8))
            }.getOrDefault(emptyList())
            if (fromFile.isNotEmpty()) return fromFile
        }
        return AgentConversationCodec.decodeTranscript(checkpoint?.historyJson)
    }

    private fun ConversationMessageEntity.toAnalysisMessage(includeImages: Boolean): EtaConversationAnalysisMessage {
        val decoded = decodeUserMessageImages(imagesJson)
        val images = if (includeImages) {
            decoded.previews.mapIndexed { index, preview ->
                EtaConversationAnalysisImage(
                    preview = preview,
                    source = decoded.sources.getOrNull(index),
                    isVideo = decoded.videoFlags.getOrNull(index) == true,
                    durationMs = decoded.durationsMs.getOrNull(index),
                )
            }
        } else {
            emptyList()
        }
        return EtaConversationAnalysisMessage(
            id = id,
            sortIndex = sortIndex,
            type = type,
            content = content,
            isEdited = isEdited,
            renderMarkdown = renderMarkdown,
            contextTokens = contextTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
            elapsedSeconds = elapsedSeconds,
            toolName = toolName,
            toolStatus = toolStatus,
            argumentsSummary = argumentsSummary,
            resultSummary = resultSummary,
            argumentsDetail = argumentsDetail,
            resultDetail = resultDetail,
            imageCount = imageCount,
            tools = decodeStringList(toolsJson),
            media = if (includeImages) AgentToolMediaPreviews.decode(mediaJson) else emptyList(),
            images = images,
            generatedAtMillis = generatedAtMillis,
        )
    }

    private fun EtaConversationAnalysisMessage.toEntity(conversationId: String, sortIndex: Int) =
        ConversationMessageEntity(
            id = "import-${UUID.randomUUID()}",
            conversationId = conversationId,
            sortIndex = sortIndex,
            type = type,
            content = content,
            imagesJson = encodeImages(images),
            isEdited = isEdited,
            renderMarkdown = renderMarkdown,
            contextTokens = contextTokens,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
            elapsedSeconds = elapsedSeconds,
            toolName = toolName,
            toolStatus = toolStatus,
            argumentsSummary = argumentsSummary,
            resultSummary = resultSummary,
            argumentsDetail = argumentsDetail,
            resultDetail = resultDetail,
            mediaJson = AgentToolMediaPreviews.encodePreviews(media),
            imageCount = imageCount,
            toolsJson = encodeStringList(tools),
            generatedAtMillis = generatedAtMillis,
        )

    private fun encodeImages(images: List<EtaConversationAnalysisImage>): String {
        if (images.isEmpty()) return "[]"
        val distinctSource = images.any { it.source != null && it.source != it.preview }
        val hasVideo = images.any { it.isVideo }
        val hasDuration = images.any { it.durationMs != null }
        return encodeUserMessageImages(
            previews = images.map { it.preview },
            sources = if (distinctSource) images.map { it.source ?: it.preview } else emptyList(),
            videoFlags = if (hasVideo) images.map { it.isVideo } else emptyList(),
            durationsMs = if (hasDuration) images.map { it.durationMs } else emptyList(),
        )
    }

    private suspend fun loadRuntimeRuns(
        dao: RuntimeRunDao,
        runIds: List<String>,
    ): List<EtaConversationAnalysisRuntimeRun> {
        if (runIds.isEmpty()) return emptyList()
        val results = dao.runtimeResults().associateBy { it.runId }
        val archives = dao.archivedRunHeaders().associateBy { it.runId }
        return runIds.mapNotNull { runId ->
            val result = results[runId]
            val archive = archives[runId]
            if (result == null && archive == null) return@mapNotNull null
            val transcriptJson = result?.transcriptJson ?: archive?.transcriptJson.orEmpty()
            EtaConversationAnalysisRuntimeRun(
                runId = runId,
                createdAt = result?.createdAt ?: archive?.createdAt ?: 0L,
                ok = result?.ok ?: archive?.ok ?: false,
                error = result?.error ?: archive?.error,
                transcript = AgentConversationCodec.decodeTranscript(transcriptJson),
                events = archive?.let { loadArchiveEvents(dao, it.archiveRunId) } ?: emptyList(),
            )
        }
    }

    private suspend fun loadArchiveEvents(dao: RuntimeRunDao, archiveRunId: String): List<JsonElement> {
        val elements = mutableListOf<JsonElement>()
        var offset = 0
        while (true) {
            val page = dao.archivedEvents(archiveRunId, ARCHIVE_EVENT_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            page.forEach { row ->
                runCatching { json.parseToJsonElement(row.eventJson) }.getOrNull()?.let(elements::add)
            }
            offset += page.size
            if (page.size < ARCHIVE_EVENT_PAGE_SIZE) break
        }
        return elements
    }

    private fun decodeStringList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeStringList(values: List<String>): String =
        JSONArray().also { array -> values.forEach(array::put) }.toString()

    /** 检查点有容量上限；放不下时按应用自身的做法去掉回合保护再编一次。 */
    private fun encodeCheckpoint(history: List<AgentModelClient.ConversationMessage>): String =
        try {
            AgentConversationCodec.encodeConversationCheckpoint(history)
        } catch (tooLarge: ConversationCheckpointTooLargeException) {
            AgentConversationCodec.encodeConversationCheckpoint(history.map { it.copy(turnId = "") })
        }

    private const val ARCHIVE_EVENT_PAGE_SIZE = 64
}
