package io.github.mangi.eta.ui.app

import android.content.Context
import java.io.File
import androidx.room.withTransaction
import io.github.mangi.eta.ui.model.CloudUsageReceiptCodec
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.media.AgentToolMediaPreviews
import io.github.mangi.eta.agent.model.AgentTurnIdentity
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.agent.model.ConversationCheckpointTooLargeException
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationFolderEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationDao
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AgentConversationHistoryFiles
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement
import io.github.mangi.eta.ui.model.attachUserImageSources
import io.github.mangi.eta.ui.model.decodeUserMessageImages
import io.github.mangi.eta.ui.model.encodeUserMessageImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray

internal object AgentConversationStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class Snapshot(
        val selectedConversationId: String?,
        val conversationsById: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val updatedAt: Map<String, Long>,
        val createdAt: Map<String, Long> = emptyMap(),
        val folderIds: Map<String, String> = emptyMap(),
        val pinnedIds: Set<String> = emptySet(),
        val completionMarkerIds: Set<String> = emptySet(),
        val folders: List<ConversationFolderUi> = emptyList(),
        /** 回收站：会话 id -> 软删除时间。仅包含 deleted_at 非空的行。 */
        val deletedAt: Map<String, Long> = emptyMap(),
    )

    private val saveMutex = Mutex()

    fun load(context: Context, selectedOnly: Boolean = false): Snapshot =
        runBlocking(Dispatchers.IO) {
            EtaDatabase.get(context.applicationContext).withTransaction {
                loadSnapshot(context.applicationContext, selectedOnly)
            }
        }

    /**
     * The selected conversation id alone, without loading any conversation content.
     *
     * Construction-time callers (sub-agent draft seeding) need the selected id before the
     * asynchronous hydrate can finish. This is two primary-key/cheap row reads, not the full
     * snapshot transaction: no conversation metadata, no paged messages, no history files.
     *
     * Mirrors [loadSnapshot]'s resolution exactly — a stored id that no longer exists falls
     * back to the most recently updated conversation, and an empty table yields null — so
     * the properties seeded from this value match what the full load would have produced.
     */
    fun persistedSelectedConversationId(context: Context): String? =
        runBlocking(Dispatchers.IO) {
            val dao = EtaDatabase.get(context.applicationContext).conversationDao()
            // 回收站里的会话不能被恢复为启动选中项；stored 存在但已软删除时走最近会话兜底。
            val stored = dao.state()?.selectedConversationId
            val exists = stored != null && dao.conversationMetadata(stored)?.let { it.deletedAt == null } == true
            if (exists) stored else dao.conversationsPage(limit = 1, offset = 0).firstOrNull()?.id
        }

    /**
     * Seed for the pre-hydration window: no conversation content is assumed to exist.
     *
     * [selectedConversationId] must be the persisted selection so that property
     * initializers running after `init` still see the real owner. Content arrives later.
     */
    fun emptySnapshot(selectedConversationId: String? = null): Snapshot = Snapshot(
        selectedConversationId = selectedConversationId,
        conversationsById = emptyMap(),
        titles = emptyMap(),
        updatedAt = emptyMap(),
        createdAt = emptyMap(),
        folderIds = emptyMap(),
        pinnedIds = emptySet(),
        completionMarkerIds = emptySet(),
        folders = emptyList(),
    )

    /**
     * Cold-start counterpart of [load] for the application scope.
     *
     * [load] blocks its caller, which was the main thread while [io.github.mangi.eta.ui.app.AgentAppState]
     * built its initial snapshot from a ViewModel constructor. Keeping the identical
     * projection semantics here lets startup read Room without occupying the UI thread.
     */
    suspend fun loadSuspending(context: Context, selectedOnly: Boolean = false): Snapshot =
        EtaDatabase.get(context.applicationContext).withTransaction {
            loadSnapshot(context.applicationContext, selectedOnly)
        }

    suspend fun save(
        context: Context,
        selectedConversationId: String?,
        conversationsById: Map<String, AgentChatHomeUiState>,
        titles: Map<String, String>,
        updatedAt: Map<String, Long>,
        folderIds: Map<String, String> = emptyMap(),
        pinnedIds: Set<String> = emptySet(),
        completionMarkerIds: Set<String> = emptySet(),
        folders: List<ConversationFolderUi> = emptyList(),
        deletedAt: Map<String, Long> = emptyMap(),
        /**
         * When non-null, only these conversations rewrite message pages, history files and
         * checkpoints. Metadata is still written for the full snapshot. The send path uses this
         * to persist the active conversation without re-encoding every other loaded transcript.
         */
        contentConversationIds: Set<String>? = null,
    ) {
        val appContext = context.applicationContext
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                val sorted = conversationsById.entries
                    .sortedByDescending { (id, _) -> updatedAt[id] ?: 0L }

                val storedIds = sorted.mapTo(mutableSetOf()) { it.key }
                val selected = selectedConversationId
                    ?.takeIf { it in storedIds }
                    ?: sorted.firstOrNull()?.key
                val now = System.currentTimeMillis()
                val conversations = sorted.map { (id, state) ->
                    ConversationEntity(
                        id = id,
                        title = titles[id].orEmpty(),
                        thinkingEnabled = state.reasoningEffort.enablesReasoning,
                        reasoningEffort = state.reasoningEffort.wireValue,
                        appliedRuntimeRunIdsJson = json.encodeToString(state.appliedRuntimeRunIds),
                        createdAt = updatedAt[id] ?: now,
                        updatedAt = updatedAt[id] ?: now,
                        folderId = folderIds[id].orEmpty(),
                        isPinned = id in pinnedIds,
                        hasCompletionMarker = id in completionMarkerIds,
                        providerId = state.providerId,
                        modelId = state.modelId,
                        assistantId = state.assistantId,
                        deletedAt = deletedAt[id],
                    )
                }
                val database = EtaDatabase.get(appContext)
                val dao = database.conversationDao()
                // Keep the existing all-or-nothing snapshot contract, but encode/write one
                // conversation and one message page at a time instead of materializing
                // every entity and every checkpoint JSON before starting the transaction.
                database.withTransaction {
                    val existing = dao.conversations().associateBy { it.id }
                    require(sorted.all { (id, state) -> state.conversationContentLoaded || id in existing }) {
                        "Cannot save a new conversation without loaded content"
                    }
                    existing.keys.filterNot { it in storedIds }.forEach { id ->
                        dao.deleteConversation(id)
                        AgentConversationHistoryFiles.file(appContext, id).delete()
                    }
                    val metadata = conversations.map { row ->
                        row.copy(createdAt = existing[row.id]?.createdAt ?: row.createdAt)
                    }
                    dao.insertMissingConversations(metadata)
                    dao.updateConversationMetadata(metadata.map { row ->
                        ConversationMetadata(row.id, row.title, row.thinkingEnabled, row.reasoningEffort,
                            row.appliedRuntimeRunIdsJson, row.createdAt, row.updatedAt, row.folderId,
                            row.isPinned, row.hasCompletionMarker, row.providerId, row.modelId, row.assistantId,
                            row.deletedAt)
                    })
                    dao.deleteState()
                    for ((conversationId, state) in sorted) {
                        if (!state.conversationContentLoaded) continue
                        if (contentConversationIds != null && conversationId !in contentConversationIds) continue
                        dao.deleteMessagesForConversation(conversationId)
                        dao.deleteContextCheckpoint(conversationId)
                        val pages = state.messages.asSequence()
                            .mapIndexedNotNull { index, message -> message.toEntityOrNull(conversationId, index) }
                            .chunked(MESSAGE_LOAD_PAGE_SIZE)
                        for (page in pages) dao.insertMessages(page)
                        writeConversationHistory(appContext, conversationId, state.history)
                        val encodedHistory = encodeCheckpoint(state.history)
                        dao.insertContextCheckpoints(listOf(ConversationContextCheckpointEntity(
                            conversationId = conversationId,
                            historyJson = encodedHistory,
                            cloudUsageJson = CloudUsageReceiptCodec.encode(
                                conversationId, state.providerId, state.modelId, encodedHistory,
                                state.livePromptTokens.takeUnless { state.livePromptIsProjected },
                                state.cloudHistoryTokens, state.cloudRequestOverheadTokens,
                                state.contextHasStarted, state.contextAwaitingReceipt, state.cloudRouteSignature,
                                state.cloudReceiptRequestId,
                            ),
                        )))
                    }
                    selected?.let { dao.insertState(ConversationStateEntity(selectedConversationId = it)) }
                    dao.replaceFolders(
                        folders.mapIndexed { index, folder ->
                            ConversationFolderEntity(
                                id = folder.id,
                                name = folder.name,
                                sortIndex = folder.sortIndex.takeIf { it > 0 } ?: index,
                                createdAt = now,
                            )
                        },
                    )
                }
            }
        }
    }

    fun searchStoredConversation(
        context: Context, id: String, title: String, updatedAt: Long, query: String,
        unnamedTitle: String, roles: io.github.mangi.eta.ui.model.MessageSearchRoleLabels,
    ): List<io.github.mangi.eta.ui.model.MessageSearchHit> = runBlocking(Dispatchers.IO) {
        if (query.isBlank()) return@runBlocking emptyList()
        val database = EtaDatabase.get(context.applicationContext)
        database.withTransaction {
        val dao = database.conversationDao()
        buildList {
            var offset = 0
            while (true) {
                val page = dao.searchablePage(id, MESSAGE_LOAD_PAGE_SIZE, offset)
                val state = AgentChatHomeUiState(messages = page.mapNotNull { it.asMessageEntity().toMessageOrNull() },
                    input = "", isStreaming = false, thinkingEnabled = false)
                addAll(io.github.mangi.eta.ui.model.searchConversationMessages(
                    mapOf(id to state), mapOf(id to title), mapOf(id to updatedAt), query, unnamedTitle, roles))
                if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                offset += page.size
            }
        }
        }
    }

    fun unloadedPreview(state: AgentChatHomeUiState): AgentChatHomeUiState {
        val preview = when (val last = state.messages.lastOrNull()) {
            is UserMessageUi -> last.copy(content = last.content.take(2048), images = emptyList(),
                imageSources = emptyList(), imageIsVideo = emptyList(), imageDurationsMs = emptyList())
            is AgentMessageUi -> last.copy(content = last.content.take(2048))
            is ThinkingMessageUi -> last.copy(content = "")
            is ToolActivityMessageUi -> last.copy(
                command = null,
                resultSummary = null,
                argumentsSummary = "",
                argumentsDetail = null,
                resultDetail = null,
                mediaPreviews = emptyList(),
            )
            is SystemNoticeMessageUi -> last.copy(detail = null)
            else -> null
        }
        return state.copy(messages = listOfNotNull(preview), history = emptyList(), conversationContentLoaded = false,
            childContexts = emptyList(), childContextRunId = "", selectedContextTaskId = null)
    }


    private fun conversationHistoryFile(context: Context, id: String): File =
        AgentConversationHistoryFiles.file(context, id)

    /** Room 列仍受游标窗口限制。模型下一次要发的历史写在文件里，避免重新打开后又被截短。 */
    private fun writeConversationHistory(
        context: Context,
        id: String,
        history: List<AgentModelClient.ConversationMessage>,
    ) {
        if (id.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) return
        val file = conversationHistoryFile(context, id)
        file.parentFile?.mkdirs()
        val encoded = AgentConversationCodec.encodeTranscriptForTransfer(history)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encoded, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(encoded, Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun readConversationHistory(
        context: Context,
        id: String,
    ): List<AgentModelClient.ConversationMessage>? {
        val file = conversationHistoryFile(context, id)
        if (!file.isFile) return null
        val decoded = AgentConversationCodec.decodeTranscript(file.readText(Charsets.UTF_8))
        return decoded.takeIf { it.isNotEmpty() }
    }

    /**
     * 只有"受保护回合本身超过容量上限"才允许放弃保护重编：那时保留保护只会让整轮写不进去。
     * OOM、序列化故障等其它异常必须上抛——过去一律 catch 会把它们也当成容量问题，
     * 于是清掉全部 turnId 再编一次，保护静默失效、前缀消息被悄悄丢掉。
     */
    private fun encodeCheckpoint(history: List<AgentModelClient.ConversationMessage>): String =
        try {
            AgentConversationCodec.encodeConversationCheckpoint(history)
        } catch (tooLarge: ConversationCheckpointTooLargeException) {
            AndroidAgentLogger.warn(
                "Conversation checkpoint exceeded its cap with ${history.size} messages; " +
                    "retrying without turn protection"
            )
            // Do not retry OOM by allocating another full list: only the capacity case lands here.
            AgentConversationCodec.encodeConversationCheckpoint(history.map { it.copy(turnId = "") })
        }

    suspend fun questionConversationIds(context: Context): List<String> =
        EtaDatabase.get(context.applicationContext).conversationDao().questionConversationIds()

    fun loadConversation(context: Context, id: String): AgentChatHomeUiState? = runBlocking(Dispatchers.IO) {
        val database = EtaDatabase.get(context.applicationContext)
        database.withTransaction {
            val dao = database.conversationDao()
            dao.conversationMetadata(id)?.let { loadConversationState(context, dao, it, true) }
        }
    }

    private suspend fun loadConversationState(
        context: Context, dao: ConversationDao, conversation: ConversationMetadata, withContent: Boolean,
    ): AgentChatHomeUiState {
        val (fallbackProviderId, fallbackModelId) = defaultSelection()
        val checkpoint = if (withContent) dao.contextCheckpoint(conversation.id) else null
        val persistedHistory = if (!withContent) emptyList() else {
            readConversationHistory(context, conversation.id)
                ?: AgentConversationCodec.decodeTranscript(checkpoint?.historyJson)
        }
        val legacyHistory = mutableListOf<AgentModelClient.ConversationMessage>()
        val storedRows = buildList {
            if (!withContent) {
                dao.conversationPreview(conversation.id)?.asMessageEntity()?.let(::add)
            } else {
                var offset = 0
                while (true) {
                    val page = dao.messagesPage(conversation.id, MESSAGE_LOAD_PAGE_SIZE, offset)
                    // Keep rows briefly so a one-time identity repair can update their primary keys.
                    addAll(page)
                    if (persistedHistory.isEmpty()) legacyHistory.addAll(page.toLegacyHistory())
                    if (page.size < MESSAGE_LOAD_PAGE_SIZE) break
                    offset += page.size
                }
            }
        }
        val sourceHistory = persistedHistory.ifEmpty { legacyHistory }
        val history = AgentTurnIdentity.migrate(sourceHistory)
        val rawMessages = storedRows.mapNotNull { it.toMessageOrNull() }
        val identityMigration = if (withContent) {
            migrateStableMessageIdentities(rawMessages, history)
        } else {
            StableIdentityMigration(rawMessages, emptyList())
        }
        // Both full-load entry points hold a Room transaction. Validate every target
        // against the whole table before the first UPDATE, then publish the UI IDs only
        // after the entire batch succeeds. A conflicting conversation remains readable.
        val identityMessages = if (withContent) {
            ConversationMessageIdMigration.apply(
                original = rawMessages,
                migrated = identityMigration.messages,
                storedRowIds = storedRows.mapTo(mutableSetOf()) { it.id },
                renames = identityMigration.renames,
                targetExists = { dao.messageIdExists(it) },
                rename = { oldId, newId -> dao.renameMessageId(conversation.id, oldId, newId) },
            )
        } else rawMessages
        if (identityMigration.renames.isNotEmpty() && identityMessages === rawMessages) {
            // Do not log message IDs, conversation IDs, or contents.
            AndroidAgentLogger.warn("Message identity migration skipped: occupied target or invalid batch; original messages retained")
        }
        if (withContent && history.isNotEmpty() && history != sourceHistory) {
            writeConversationHistory(context, conversation.id, history)
        }
        val messages = attachUserImageSources(
            messages = identityMessages,
            history = history,
        )
        // Invalid receipts must not resurrect bills from a different history/model.
        val receipt = checkpoint?.takeIf { persistedHistory.isNotEmpty() }?.let {
            CloudUsageReceiptCodec.decodeReceipt(it.cloudUsageJson, conversation.id,
                conversation.providerId, conversation.modelId, it.historyJson)
        }
        val contextDisplay = checkpoint?.let {
            CloudUsageReceiptCodec.decodeDisplayState(it.cloudUsageJson, conversation.id, it.historyJson)
        }
        return AgentChatHomeUiState(
            contextHasStarted = contextDisplay?.hasStarted ?: (history.isNotEmpty() || messages.isNotEmpty()),
            // Legacy checkpoints without state metadata are conservative, even if markers aren't loaded.
            contextAwaitingReceipt = contextDisplay?.awaitingReceipt ?: (receipt == null && history.isNotEmpty()),
            cloudRouteSignature = receipt?.routeSignature,
            conversationContentLoaded = withContent,
            messages = messages,
            isWaitingForAnswer = AgentQuestionProjection.hasWaiting(messages),
            history = history,
            appliedRuntimeRunIds = conversation.appliedRuntimeRunIdsJson.toStringList(),
            input = "",
            isStreaming = false,
            thinkingEnabled = conversation.reasoningEffortValue.enablesReasoning,
            reasoningEffort = conversation.reasoningEffortValue,
            providerId = if (conversation.providerId.isBlank() && conversation.modelId.isBlank()) fallbackProviderId else conversation.providerId,
            modelId = if (conversation.providerId.isBlank() && conversation.modelId.isBlank()) fallbackModelId else conversation.modelId,
            assistantId = conversation.assistantId,
            livePromptTokens = receipt?.inputTokens,
            contextBudgetReceiptTokens = receipt?.takeIf {
                io.github.mangi.eta.ui.model.RequestOverheadCalibration.hasUsableBaseline(
                    it.historyTokens, it.overheadTokens)
            }?.inputTokens,
            cloudReceiptRequestId = receipt?.requestId,
            contextReceiptEvidence = receipt?.let { actual -> actual.requestId?.let { requestId ->
                io.github.mangi.eta.ui.model.ContextReceiptEvidence(requestId, actual.inputTokens,
                    actual.historyTokens, actual.overheadTokens)
            } },
            cloudHistoryTokens = receipt?.historyTokens,
            cloudRequestOverheadTokens = receipt?.overheadTokens,
        )
    }

    private data class StableIdentityMigration(
        val messages: List<AgentChatMessageUi>,
        val renames: List<Pair<String, String>>,
    )

    /**
     * Repair the old split identity scheme only when the visible user sequence and the
     * persisted history sequence prove the same turns. Repeated or incomplete payloads
     * stay untouched and continue through the reducer's fail-closed compatibility path.
     */
    private fun migrateStableMessageIdentities(
        messages: List<AgentChatMessageUi>,
        history: List<AgentModelClient.ConversationMessage>,
    ): StableIdentityMigration {
        val uiUsers = messages.filterIsInstance<UserMessageUi>()
        val ordinaryUiUsers = uiUsers.filterNot { it.isSteerSupplement() }
        val ordinaryHistory = history.withIndex().filter { (_, message) ->
            message.role.equals("user", ignoreCase = true) &&
                !AgentContextCompactor.isCompressionSummary(message) &&
                !AgentContextCompactor.isSteeringUserMessage(message)
        }
        if (ordinaryUiUsers.size != ordinaryHistory.size) {
            return StableIdentityMigration(messages, emptyList())
        }
        val uiTexts = ordinaryUiUsers.map { it.content.trim() }
        val historyTexts = ordinaryHistory.map { (_, message) ->
            AgentConversationCodec.userVisibleText(message).trim()
        }
        // Repeated visible text is not enough evidence to identify the corresponding turn.
        if (uiTexts.distinct().size != uiTexts.size ||
            historyTexts.distinct().size != historyTexts.size ||
            uiTexts != historyTexts) {
            return StableIdentityMigration(messages, emptyList())
        }
        val turnIds = ordinaryHistory.map { it.value.turnId }
        if (turnIds.any(String::isBlank) || turnIds.distinct().size != turnIds.size) {
            return StableIdentityMigration(messages, emptyList())
        }

        val updated = messages.toMutableList()
        val renames = mutableListOf<Pair<String, String>>()
        val stableByUiIndex = mutableMapOf<Int, String>()
        var ordinaryIndex = 0
        messages.forEachIndexed { index, message ->
            val user = message as? UserMessageUi ?: return@forEachIndexed
            if (!user.isSteerSupplement()) {
                val turnId = turnIds[ordinaryIndex++]
                stableByUiIndex[index] = turnId
                val newId = "user-$turnId"
                if (user.id != newId) {
                    updated[index] = user.copy(id = newId)
                    renames += user.id to newId
                }
            }
        }

        var currentTurnId: String? = null
        messages.forEachIndexed { index, message ->
            val user = message as? UserMessageUi ?: return@forEachIndexed
            stableByUiIndex[index]?.let { currentTurnId = it }
            if (!user.isSteerSupplement()) return@forEachIndexed
            val owner = currentTurnId ?: return@forEachIndexed
            val suffix = user.id.substringAfter("-supplement-", "message-$index")
            val newId = "user-$owner-supplement-$suffix"
            if (user.id != newId) {
                updated[index] = user.copy(id = newId)
                renames += user.id to newId
            }
        }
        val targets = renames.map { it.second }
        val sourceIds = messages.map { it.id }.toSet()
        if (targets.size != targets.distinct().size ||
            renames.any { (oldId, newId) -> newId in sourceIds && newId != oldId }) {
            return StableIdentityMigration(messages, emptyList())
        }
        return StableIdentityMigration(updated, renames)
    }

    private suspend fun loadSnapshot(context: Context, selectedOnly: Boolean): Snapshot {
        val dao = EtaDatabase.get(context).conversationDao()
        val conversations = dao.conversations()
        if (conversations.isEmpty()) {
            return Snapshot(
                selectedConversationId = null,
                conversationsById = emptyMap(),
                titles = emptyMap(),
                updatedAt = emptyMap(),
                createdAt = emptyMap(),
                folders = dao.folders().toUiFolders(),
            )
        }

        val states = linkedMapOf<String, AgentChatHomeUiState>()
        val titles = mutableMapOf<String, String>()
        val updatedAt = mutableMapOf<String, Long>()
        val createdAt = mutableMapOf<String, Long>()

        // 回收站会话照常进内存快照（软删除靠 deletedAt 标记），但启动选中项与兜底都只能落在未删除会话上。
        val selected = dao.state()?.selectedConversationId
            ?.takeIf { id -> conversations.any { it.id == id && it.deletedAt == null } }
            ?: conversations.firstOrNull { it.deletedAt == null }?.id
        conversations.forEach { conversation ->
            states[conversation.id] = loadConversationState(context, dao, conversation, !selectedOnly || conversation.id == selected)
            titles[conversation.id] = conversation.title.takeUnless { it == LEGACY_UNNAMED_TITLE }.orEmpty()
            updatedAt[conversation.id] = conversation.updatedAt
            createdAt[conversation.id] = conversation.createdAt
        }

        return Snapshot(
            selectedConversationId = selected,
            conversationsById = states,
            titles = titles,
            updatedAt = updatedAt,
            createdAt = createdAt,
            folderIds = conversations
                .mapNotNull { conversation ->
                    conversation.folderId.takeIf { it.isNotBlank() }?.let { conversation.id to it }
                }
                .toMap(),
            pinnedIds = conversations.filter { it.isPinned }.map { it.id }.toSet(),
            completionMarkerIds = conversations.filter { it.hasCompletionMarker }.map { it.id }.toSet(),
            folders = dao.folders().toUiFolders(),
            deletedAt = conversations.mapNotNull { conversation ->
                conversation.deletedAt?.let { conversation.id to it }
            }.toMap(),
        )
    }


    private fun List<ConversationFolderEntity>.toUiFolders(): List<ConversationFolderUi> =
        map { folder ->
            ConversationFolderUi(
                id = folder.id,
                name = folder.name,
                sortIndex = folder.sortIndex,
            )
        }

    private val ConversationMetadata.reasoningEffortValue: ReasoningEffort
        get() = ReasoningEffort.fromWireValue(reasoningEffort) ?: ReasoningEffort.DEFAULT

    private suspend fun defaultSelection(): Pair<String, String> {
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        return settings?.selectedProviderId.orEmpty() to settings?.selectedModelId.orEmpty()
    }

    private fun AgentChatMessageUi.toEntityOrNull(
        conversationId: String,
        sortIndex: Int,
    ): ConversationMessageEntity? =
        when (this) {
            is UserMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_USER,
                content = content,
                imagesJson = encodeUserMessageImages(images, imageSources, imageIsVideo, imageDurationsMs),
                isEdited = isEdited,
            )

            is AgentMessageUi -> {
                if (content.isBlank() && isStreaming) {
                    null
                } else {
                    ConversationMessageEntity(
                        id = id,
                        conversationId = conversationId,
                        sortIndex = sortIndex,
                        type = TYPE_ASSISTANT,
                        content = content,
                        renderMarkdown = renderMarkdown,
                        contextTokens = usage?.contextTokens,
                        inputTokens = usage?.inputTokens,
                        outputTokens = usage?.outputTokens,
                        reasoningTokens = usage?.reasoningTokens,
                        cachedTokens = usage?.cachedTokens,
                        generatedAtMillis = generatedAtMillis,
                    )
                }
            }

            is SystemNoticeMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_SYSTEM_NOTICE,
                content = code.wireValue,
                resultSummary = detail,
                renderMarkdown = false,
            )

            is ErrorReconnectMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_ERROR_RECONNECT,
                content = AgentErrorReconnectCodec.encode(this),
                renderMarkdown = false,
            )

            is ThinkingMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_THINKING,
                content = content,
                elapsedSeconds = elapsedSeconds,
            )

            is AgentQuestionMessageUi -> ConversationMessageEntity(
                id = id, conversationId = conversationId, sortIndex = sortIndex,
                type = AgentQuestionPersistence.TYPE, content = AgentQuestionPersistence.encode(this), renderMarkdown = false,
            )

            is ToolActivityMessageUi -> {
                // 敏感工具的原始参数/结果按策略不落库，即便运行时事件已经过滤过一次，
                // 这里再按工具名兜底，避免旧数据或新增工具漏过。
                val sensitive = AgentSensitiveToolPolicy.isSensitive(toolName)
                ConversationMessageEntity(
                    id = id,
                    conversationId = conversationId,
                    sortIndex = sortIndex,
                    type = TYPE_TOOL,
                    content = command.orEmpty(),
                    toolName = toolName,
                    toolStatus = status.name,
                    argumentsSummary = argumentsSummary,
                    resultSummary = resultSummary,
                    argumentsDetail = argumentsDetail.takeUnless { sensitive },
                    resultDetail = resultDetail.takeUnless { sensitive },
                    mediaJson = if (sensitive) AgentToolMediaPreviews.EMPTY
                    else AgentToolMediaPreviews.encodePreviews(mediaPreviews),
                    imageCount = imageCount,
                )
            }

            is ToolSummaryMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_TOOL_SUMMARY,
                content = "",
                toolsJson = tools.toJsonArrayString(),
            )

            is ContextCompactedMessageUi -> ConversationMessageEntity(
                id = id,
                conversationId = conversationId,
                sortIndex = sortIndex,
                type = TYPE_CONTEXT_COMPACTED,
                content = summary,
                elapsedSeconds = compactedCount,
                argumentsSummary = compressorLabel,
                contextTokens = baselineTokens.takeIf { it > 0 },
                inputTokens = preservedUsage.inputTokens.toTokenColumn().takeIf { it > 0 },
                outputTokens = preservedUsage.outputTokens.toTokenColumn(),
                cachedTokens = preservedUsage.cachedTokens.toTokenColumn().takeIf { it > 0 },
                imageCount = resumeRound.coerceAtLeast(0),
            )

            else -> null
        }

    private fun ConversationMessageEntity.toMessageOrNull(): AgentChatMessageUi? =
        when (type) {
            TYPE_USER -> {
                val decoded = decodeUserMessageImages(imagesJson)
                UserMessageUi(
                    id = id,
                    content = content,
                    images = decoded.previews,
                    isEdited = isEdited,
                    imageSources = decoded.sources,
                    imageIsVideo = decoded.videoFlags,
                    imageDurationsMs = decoded.durationsMs,
                )
            }

            TYPE_ASSISTANT -> AgentMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                renderMarkdown = renderMarkdown ?: true,
                generatedAtMillis = generatedAtMillis,
                usage = TokenUsageUi(
                    contextTokens = contextTokens,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    reasoningTokens = reasoningTokens,
                    cachedTokens = cachedTokens,
                ).takeUnless { it.isEmpty },
            )

            TYPE_SYSTEM_NOTICE -> SystemNoticeCode.fromWireValue(content)?.let { code ->
                if (code == SystemNoticeCode.RuntimeFailed) ErrorReconnectMessageUi(
                    id = id,
                    runId = "",
                    reconnectId = "legacy:$id",
                    round = 0,
                    status = ErrorReconnectStatus.Failed,
                    reasonDetail = resultSummary.orEmpty(),
                    isReconnect = false,
                ) else SystemNoticeMessageUi(id = id, code = code, detail = resultSummary)
            }

            TYPE_ERROR_RECONNECT -> AgentErrorReconnectCodec.decode(id, content)

            TYPE_THINKING -> ThinkingMessageUi(
                id = id,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds,
                collapsed = true,
            )

            AgentQuestionPersistence.TYPE -> AgentQuestionPersistence.decode(id, conversationId, content)

            TYPE_TOOL -> ToolActivityMessageUi(
                id = id,
                toolName = toolName.orEmpty(),
                status = toolStatus.orEmpty().toToolStatus(),
                argumentsSummary = argumentsSummary.orEmpty(),
                command = content.takeIf(String::isNotBlank),
                resultSummary = resultSummary,
                imageCount = imageCount,
                argumentsDetail = argumentsDetail,
                resultDetail = resultDetail,
                mediaPreviews = AgentToolMediaPreviews.decode(mediaJson),
                detailsWithheld = AgentSensitiveToolPolicy.isSensitive(toolName.orEmpty()) &&
                    argumentsDetail.isNullOrBlank() && resultDetail.isNullOrBlank(),
            )

            TYPE_TOOL_SUMMARY -> ToolSummaryMessageUi(
                id = id,
                tools = toolsJson.toStringList(),
            )

            TYPE_CONTEXT_COMPACTED -> {
                val legacyResumeRound = outputTokens == null
                ContextCompactedMessageUi(
                    id = id,
                    compactedCount = elapsedSeconds ?: 0,
                    summary = content,
                    compressorLabel = argumentsSummary.orEmpty(),
                    baselineTokens = contextTokens ?: 0,
                    resumeRound = if (legacyResumeRound) inputTokens ?: 0 else imageCount,
                    preservedUsage = if (legacyResumeRound) {
                        ConversationTokenUsageUi()
                    } else {
                        ConversationTokenUsageUi(
                            inputTokens = (inputTokens ?: 0).toLong(),
                            outputTokens = (outputTokens ?: 0).toLong(),
                            cachedTokens = (cachedTokens ?: 0).toLong(),
                        )
                    },
                )
            }

            else -> null
        }

    private fun String.toToolStatus(): ToolActivityStatusUi =
        runCatching { ToolActivityStatusUi.valueOf(this) }.getOrNull()
            ?.let { status ->
                if (status == ToolActivityStatusUi.Running) ToolActivityStatusUi.Unknown else status
            }
            ?: ToolActivityStatusUi.Unknown

    private fun List<String>.toJsonArrayString(): String =
        JSONArray().also { array ->
            forEach { array.put(it) }
        }.toString()

    private fun String.toStringList(): List<String> =
        runCatching {
            val array = JSONArray(this)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())

    private fun List<ConversationMessageEntity>.toLegacyHistory(): List<AgentModelClient.ConversationMessage> =
        mapNotNull { message ->
            when (message.type) {
                TYPE_USER -> AgentModelClient.ConversationMessage(
                    role = "user",
                    content = message.content,
                )
                TYPE_ASSISTANT -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { content ->
                        AgentModelClient.ConversationMessage(
                            role = "assistant",
                            content = content,
                        )
                    }
                else -> null
            }
        }


    private fun Long.toTokenColumn(): Int =
        coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private const val TYPE_USER = "user"
    private const val TYPE_ASSISTANT = "assistant"
    private const val TYPE_SYSTEM_NOTICE = "system_notice"
    private const val TYPE_ERROR_RECONNECT = "error_reconnect"
    private const val TYPE_THINKING = "thinking"
    private const val TYPE_TOOL = "tool"
    private const val TYPE_TOOL_SUMMARY = "tool_summary"
    private const val TYPE_CONTEXT_COMPACTED = "context_compacted"
    private const val MESSAGE_LOAD_PAGE_SIZE = 128
    private const val LEGACY_UNNAMED_TITLE = "新对话"
}
