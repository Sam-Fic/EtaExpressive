package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.agent.media.AgentToolMediaPreviews
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.model.ConversationCheckpointTooLargeException
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.RuntimeRunDao
import io.github.mangi.eta.data.model.UsageDiagnosticsJson
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.model.DISPATCH_CONSOLE_CONVERSATION_ID
import io.github.mangi.eta.ui.model.decodeUserMessageImages
import io.github.mangi.eta.ui.model.encodeUserMessageImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.json.JSONArray
import java.util.Locale
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

    /** 只用于凭据脱敏与截断判断，不参与写库。 */
    private val traceFormatter = AgentTraceFormatter()

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

        // 先读原始 history：图片的宽高 / MIME 只存在它的 image_file 部件里，
        // messages 的 imagesJson 只有预览与引用，需要按同一 path 做一次 join。
        val rawHistory = readHistory(appContext, conversationId, checkpoint)
        val imageMetadata = imageMetadataFromHistory(rawHistory)
        val messages = rows.map { row -> row.toAnalysisMessage(options.includeImages, imageMetadata) }
        // history 是模型侧原文，不能整段清空（会破坏导入回放），只替换凭据形态。
        val history = rawHistory.map(::redactHistoryCredentials)
        val rounds = EtaConversationAnalysisRounds.segment(messages, runIds)
        val summary = EtaConversationAnalysisRounds.summarize(messages, rounds)
        val runtimeRuns = if (options.includeRawRuntimeIo) {
            loadRuntimeRuns(database.runtimeRunDao(), runIds)
        } else {
            emptyList()
        }
        val withheldToolCallIds = messages
            .filter { it.type == "tool" && it.detailsWithheld == true }
            .mapNotNull { it.toolCallId ?: it.id }
            .distinct()
        val truncatedItems = truncationItems(messages, history)
        val config = readConfigSnapshot(conversationId)
        val deletedAt = conversation.deletedAt

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
                isPinned = conversation.isPinned,
                deletedAt = deletedAt?.takeIf { it > 0 },
                hasCompletionMarker = conversation.hasCompletionMarker,
                isDispatchConsole = conversation.id == DISPATCH_CONSOLE_CONVERSATION_ID,
                dispatchParentId = conversation.dispatchParentId?.takeIf { it.isNotBlank() },
                dispatchManagedAt = conversation.dispatchManagedAt?.takeIf { it > 0 },
                appliedRuntimeRunIds = runIds.takeIf { it.isNotEmpty() },
            ),
            options = options,
            summary = summary,
            rounds = rounds,
            messages = messages,
            history = history,
            runtimeRuns = runtimeRuns,
            config = config,
            withheldToolCallIds = withheldToolCallIds.takeIf { it.isNotEmpty() },
            truncated = truncatedItems.isNotEmpty().takeIf { it },
            truncatedIn = truncatedItems.takeIf { it.isNotEmpty() },
        )
    }

    /**
     * history 只做凭据形态替换，与卡片展示共用同一份正则；不按敏感工具整段清空，
     * 否则模型侧原文回放会失真。
     */
    private fun redactHistoryCredentials(
        message: AgentModelClient.ConversationMessage,
    ): AgentModelClient.ConversationMessage = message.copy(
        content = traceFormatter.redactCredentials(message.content),
        contentJson = traceFormatter.redactCredentials(message.contentJson),
        reasoningContent = traceFormatter.redactCredentials(message.reasoningContent),
        toolCallsJson = traceFormatter.redactCredentials(message.toolCallsJson),
        responsesReasoningJson = traceFormatter.redactCredentials(message.responsesReasoningJson),
    )

    /**
     * 从 history 的 image_file 部件收集宽高 / MIME，按 `path` 建立索引。
     *
     * messages 的 imagesJson 只有预览与引用，没有这些元数据；这里只读已落库的 JSON，
     * 不重新解码图片文件。
     */
    private fun imageMetadataFromHistory(
        history: List<AgentModelClient.ConversationMessage>,
    ): Map<String, HistoryImageMeta> {
        val metadata = mutableMapOf<String, HistoryImageMeta>()
        history.forEach { message ->
            if (message.role != "user" || message.contentJson.isBlank()) return@forEach
            val parts = runCatching { JSONArray(message.contentJson) }.getOrNull() ?: return@forEach
            for (index in 0 until parts.length()) {
                val part = parts.optJSONObject(index) ?: continue
                if (part.optString("type") != AgentConversationCodec.IMAGE_FILE_TYPE) continue
                val path = part.optString("path").takeIf { it.isNotBlank() } ?: continue
                metadata[path] = HistoryImageMeta(
                    width = part.optInt("width", 0).takeIf { it > 0 },
                    height = part.optInt("height", 0).takeIf { it > 0 },
                    mime = part.optString("mime").takeIf { it.isNotBlank() },
                )
            }
        }
        return metadata
    }

    /**
     * 列出本次导出中已知被截断的位置。
     *
     * 只认存储层留下的确定性信号：卡片明细超过写入上限、history 工具参数变成
     * [_eta_truncated] 标记、history 正文达到内容上限。不按消息条数或体积猜测。
     */
    private fun truncationItems(
        messages: List<EtaConversationAnalysisMessage>,
        history: List<AgentModelClient.ConversationMessage>,
    ): List<String> {
        val items = mutableListOf<String>()
        messages.forEach { message ->
            if (traceFormatter.isArgumentsDetailTruncated(message.argumentsDetail)) {
                items += "messages[${message.id}].argumentsDetail"
            }
            if (traceFormatter.isResultDetailTruncated(message.resultDetail)) {
                items += "messages[${message.id}].resultDetail"
            }
        }
        history.forEachIndexed { index, message ->
            if (message.toolCallsJson.contains(AgentConversationCodec.TRUNCATED_MARKER)) {
                items += "history[$index].toolCallsJson"
            }
            if (AgentConversationCodec.isContentAtStorageLimit(message.content)) {
                items += "history[$index].content"
            }
        }
        return items
    }

    /**
     * 只读配置快照：全部是值与枚举，不含密钥、端点或 provider 凭据。
     *
     * 工具加载档取「本会话实际生效值」：会话有锁定值就用锁定值，否则用当前全局值；
     * 这里刻意不调用会写锁的解析函数，导出不应该改变会话状态。锁定槽里残留的旧值
     * 「suffix」按历史原样保留（该会话当时确实以该语义运行）；新 run 重锁后自然
     * 写入迁移后的三档值，全局路径则经 [Prefs.toolLoadingMode] 的读时迁移归一。
     */
    private suspend fun readConfigSnapshot(conversationId: String): EtaConversationAnalysisConfig {
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        return EtaConversationAnalysisConfig(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            locale = Locale.getDefault().toLanguageTag(),
            toolLoadingMode = resolvedToolLoadingMode(conversationId),
            mcpToolExposureDeferred = Prefs.mcpToolExposureDeferred(),
            toolReviewMode = Prefs.toolReviewMode(),
            errorReconnectPolicy = settings?.errorReconnectPolicy?.persistedValue.orEmpty(),
        )
    }

    private fun resolvedToolLoadingMode(conversationId: String): String =
        if (Prefs.getString(Prefs.Keys.TOOL_LOADING_MODE_CONVERSATION) == conversationId) {
            Prefs.getString(Prefs.Keys.TOOL_LOADING_MODE_ACTIVE).ifBlank { Prefs.toolLoadingMode() }
        } else {
            Prefs.toolLoadingMode()
        }

    private data class HistoryImageMeta(
        val width: Int?,
        val height: Int?,
        val mime: String?,
    )

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
        // 还原应用过的运行 id：回合切分靠它把消息 id 解析成真实模型回合；
        // 旧版文件没有这一项时为空，导入后按顺序分段兜底。
        // 同一文件可能被重复导入（或导入设备就是导出设备），原始 runId 与消息 id
        // 都可能撞库内主键，所以整体重映射成新 runId，同步改写消息 id 前缀。
        val appliedRunIds = document.conversation.appliedRuntimeRunIds.orEmpty()
        val runIdRemap = appliedRunIds.associateWith { "run-${UUID.randomUUID()}" }
        val remappedRunIds = appliedRunIds.map { runIdRemap.getValue(it) }
        val conversation = ConversationEntity(
            id = id,
            title = document.conversation.title,
            thinkingEnabled = document.conversation.thinkingEnabled,
            reasoningEffort = reasoningEffort,
            historyJson = "[]",
            appliedRuntimeRunIdsJson = encodeStringList(remappedRunIds),
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
        val database = EtaDatabase.get(appContext)
        val dao = database.conversationDao()
        val messages = document.messages
            .filter { it.type in IMPORTABLE_MESSAGE_TYPES }
            .mapIndexed { index, message -> message.toEntity(id, index, runIdRemap) }
        val checkpoint = document.history.takeIf { it.isNotEmpty() }?.let { history ->
            ConversationContextCheckpointEntity(
                conversationId = id,
                historyJson = encodeCheckpoint(history),
                cloudUsageJson = "",
            )
        }
        database.withTransaction {
            dao.importAsNewConversation(conversation, messages, checkpoint)
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

    private fun ConversationMessageEntity.toAnalysisMessage(
        includeImages: Boolean,
        imageMetadata: Map<String, HistoryImageMeta>,
    ): EtaConversationAnalysisMessage {
        val decoded = decodeUserMessageImages(imagesJson)
        val images = if (includeImages) {
            decoded.previews.mapIndexed { index, preview ->
                val source = decoded.sources.getOrNull(index)
                val meta = source?.let(imageMetadata::get)
                val metaWidth = meta?.width
                val metaHeight = meta?.height
                EtaConversationAnalysisImage(
                    preview = preview,
                    source = source,
                    isVideo = decoded.videoFlags.getOrNull(index) == true,
                    durationMs = decoded.durationsMs.getOrNull(index),
                    width = metaWidth,
                    height = metaHeight,
                    mime = meta?.mime,
                    estimatedTokens = if (metaWidth != null && metaHeight != null) {
                        AgentContextBudget.countImageTokens(metaWidth, metaHeight)
                    } else {
                        null
                    },
                )
            }
        } else {
            emptyList()
        }
        val safeToolName = toolName.orEmpty()
        val withheld = type == "tool" && AgentSensitiveToolPolicy.isSensitive(safeToolName)
        // 只解一次：消息级 token 列与回合级 cache 同源，避免两处口径漂移。
        val diagnostics = UsageDiagnosticsJson.decode(usageDiagnosticsJson)
        val cache = diagnostics?.let { diagnostic ->
            EtaConversationAnalysisCacheDiagnostics(
                prefixState = diagnostic.prefixState,
                toolsState = diagnostic.toolsState,
                systemState = diagnostic.systemState,
                historyState = diagnostic.historyState,
                cacheCreationTokens = diagnostic.cacheCreationTokens?.toLong()?.coerceAtLeast(0L) ?: 0L,
            )
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
            cacheCreationTokens = diagnostics?.cacheCreationTokens,
            cache = cache,
            elapsedSeconds = elapsedSeconds,
            toolName = toolName,
            toolStatus = toolStatus,
            toolCallId = if (type == "tool") EtaConversationAnalysisRounds.toolCallIdOf(id) else null,
            argumentsSummary = argumentsSummary,
            resultSummary = resultSummary,
            argumentsDetail = argumentsDetail,
            resultDetail = resultDetail,
            detailsWithheld = withheld.takeIf { it },
            imageCount = imageCount,
            tools = decodeStringList(toolsJson),
            media = if (includeImages) AgentToolMediaPreviews.decode(mediaJson) else emptyList(),
            images = images,
            generatedAtMillis = generatedAtMillis,
        )
    }

    /**
     * 把消息 id 里的 runId 段重映射成新值，回合结构与 toolCallId 派生保持不变。
     * 覆盖五类带运行标识的 id：`assistant-<run>-<round>-<idx>`、`<run>-tool-<round>-<call>`、
     * `<run>-thinking-<round>-<idx>`、`user-<run>` 与其补充行 `user-<run>-supplement-*`；
     * 其余（自由命名 / 老消息）返回 null，由调用方换随机 id。
     */
    private fun remapMessageId(messageId: String, runIdRemap: Map<String, String>): String? {
        for ((oldRunId, newRunId) in runIdRemap) {
            // 三个带尾部界符的前缀：`-tool-` / `-thinking-` / `assistant-…-` 自带分隔，
            // 不会把 run-1 误配到 run-12；剩余部分是「回合号-序号」直接拼接。
            val prefixed = listOf(
                "assistant-$oldRunId-" to "assistant-$newRunId-",
                "$oldRunId-tool-" to "$newRunId-tool-",
                "$oldRunId-thinking-" to "$newRunId-thinking-",
            )
            for ((oldPrefix, newPrefix) in prefixed) {
                if (messageId.startsWith(oldPrefix)) return newPrefix + messageId.removePrefix(oldPrefix)
            }
            // user 前缀没有尾部界符（id 形如 `user-<run>` 或 `user-<run>-supplement-*`），
            // 需按整词匹配：剩余部分为空或以 `-` 开头才算命中。
            val userOld = "user-$oldRunId"
            if (messageId.startsWith(userOld)) {
                val rest = messageId.removePrefix(userOld)
                if (rest.isEmpty() || rest.startsWith("-")) return "user-$newRunId$rest"
            }
        }
        return null
    }

    private fun EtaConversationAnalysisMessage.toEntity(
        conversationId: String,
        sortIndex: Int,
        runIdRemap: Map<String, String>,
    ): ConversationMessageEntity {
        val candidate = remapMessageId(id, runIdRemap) ?: "import-${UUID.randomUUID()}"
        // 导入后再次导出仍要带上缓存归因：优先回合结构的完整字段，旧文件只有 creation 数时
        // 也别丢；两种都没有就不写列，保持与无诊断消息一致。
        val diagnostics = cache?.let {
            io.github.mangi.eta.data.model.UsageDiagnostics(
                prefixState = it.prefixState,
                toolsState = it.toolsState,
                systemState = it.systemState,
                historyState = it.historyState,
                cacheCreationTokens = it.cacheCreationTokens.takeIf { value -> value > 0 }?.toInt()
                    ?: cacheCreationTokens,
            )
        } ?: cacheCreationTokens?.let {
            io.github.mangi.eta.data.model.UsageDiagnostics(cacheCreationTokens = it)
        }
        return ConversationMessageEntity(
            id = candidate,
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
            usageDiagnosticsJson = UsageDiagnosticsJson.encode(diagnostics),
        )
    }

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
                // 与 history 同一套凭据脱敏：运行时原文里同样可能有 `API_KEY=...` 这类终端回显。
                transcript = AgentConversationCodec.decodeTranscript(transcriptJson)
                    .map(::redactHistoryCredentials),
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
