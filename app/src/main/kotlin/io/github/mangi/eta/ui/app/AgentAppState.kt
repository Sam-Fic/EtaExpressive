package io.github.mangi.eta.ui.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.IntentFilter
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.runtime.AgentChildTaskGroups
import io.github.mangi.eta.agent.runtime.AgentChildControlPolicy
import io.github.mangi.eta.agent.runtime.localizedText
import io.github.mangi.eta.ui.components.AgentStopSelection
import io.github.mangi.eta.ui.components.ConversationSubAgentEditor
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import kotlin.jvm.Volatile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.dispatch.AgentDispatchPayload
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_VIDEO_BYTES
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentCompressionEndpoint
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentRequestOverhead
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentImageGenerationClient
import io.github.mangi.eta.agent.model.AgentImageGenerationParser
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTurnIdentity
import io.github.mangi.eta.agent.model.AgentVideoGenerationClient
import io.github.mangi.eta.agent.model.AgentVideoGenerationParser
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.DispatchWaitingQuestionUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentConversationIdentity
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentLiveRunHandoff
import io.github.mangi.eta.agent.runtime.AgentRunArchiveStore
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.AgentRuntimeResultStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.model.RequestOverheadCalibration
import io.github.mangi.eta.ui.model.ContextEstimateDiagnostics
import io.github.mangi.eta.config.AutoCompressPreference
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AppFileLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.supportsGptSpeedBinding
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.EtaBackupExportOptions
import io.github.mangi.eta.data.repository.AgentConversationHistoryFiles
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.EtaConversationAnalysisCodec
import io.github.mangi.eta.data.repository.EtaConversationAnalysisOptions
import io.github.mangi.eta.data.repository.EtaConversationAnalysisRepository
import io.github.mangi.eta.data.repository.EtaModelBackupSummary
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ModelRepository
import io.github.mangi.eta.data.repository.MainAgentSpeedDefaultsRepository
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement
import io.github.mangi.eta.ui.model.hasPartialAssistantAfterLastUser
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import io.github.mangi.eta.ui.model.MessageSearchHit
import io.github.mangi.eta.ui.model.MessageSearchRoleLabels
import io.github.mangi.eta.ui.model.searchConversationMessages
import io.github.mangi.eta.ui.model.searchConversationTitle
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentOwnerContextState
import io.github.mangi.eta.ui.model.normalizeTerminalRunMessages
import io.github.mangi.eta.ui.model.withTerminalBodiesInOrder
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.AgentContextCompactionUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationTokenUsageUi
import io.github.mangi.eta.ui.model.conversationTokenUsage
import io.github.mangi.eta.ui.model.latestBilledContextTokens
import io.github.mangi.eta.ui.model.liveContextUsage
import io.github.mangi.eta.ui.model.compressionContextUsage
import io.github.mangi.eta.ui.model.cacheDisplayName
import io.github.mangi.eta.ui.model.toOutboundModelImage
import io.github.mangi.eta.ui.model.toRequestModelImage
import io.github.mangi.eta.ui.model.visibleFileReferences
import io.github.mangi.eta.ui.model.shouldBlockSendForContextWindow
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.DISPATCH_CONSOLE_CONVERSATION_ID
import io.github.mangi.eta.ui.model.filterForFolder
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingConversationMentionUi
import io.github.mangi.eta.ui.model.ConversationMention
import io.github.mangi.eta.ui.model.toMentionedConversations
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillNoticeUi
import io.github.mangi.eta.ui.model.SkillReplacementUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolCapabilities
import io.github.mangi.eta.ui.model.ToolCapabilityUi
import io.github.mangi.eta.ui.model.ToolGroupSwitch
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.withMessageId
import io.github.mangi.eta.ui.model.fullImageSourceAt
import io.github.mangi.eta.ui.model.isVideoAt
import io.github.mangi.eta.ui.model.durationMsAt
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
) {
    private val appContext = context.applicationContext
    private val conversationDrafts = ConversationDrafts(
        appContext.getSharedPreferences("conversation_input_drafts", Context.MODE_PRIVATE), scope,
    )

    // Separate main-agent namespace; the existing full preferences backup includes this scalar.
    private val mainAgentSpeedDefaults by lazy {
        MainAgentSpeedDefaultsRepository(requireNotNull(Prefs.localAgentPreferences()) {
            "Agent preferences 未初始化"
        })
    }

    fun currentDraftField() = conversationDrafts.field(selectedConversationId)

    /**
     * 当前聊天视图的数据源：本机（Local）或某个远程服务器上的会话（Remote）。
     * 选中本机会话即置回 Local；侧栏点击远程会话行置为 Remote，由 AgentAppRoot 据此切换 stateProvider/onAction。
     */
    var selectedTarget: io.github.mangi.eta.ui.remote.ChatTarget by
        mutableStateOf(io.github.mangi.eta.ui.remote.ChatTarget.Local)
        private set

    fun selectRemoteTarget(target: io.github.mangi.eta.ui.remote.ChatTarget) {
        selectedTarget = target
    }

    fun selectLocalTarget() {
        selectedTarget = io.github.mangi.eta.ui.remote.ChatTarget.Local
    }

    /**
     * 流式期间短按发送暂存的待发消息，按会话隔离。
     * 当前运行正常结束后按顺序续发队首；用户主动停止时保留队列不自动发出。
     */
    private val queuedMessagesByConversation = androidx.compose.runtime.mutableStateMapOf<String, List<QueuedMessageUi>>()

    /** 当前会话的排队消息；运行结束后按顺序自动发出。 */
    val queuedMessages: List<QueuedMessageUi>
        get() = selectedConversationId?.let { queuedMessagesByConversation[it] }.orEmpty()

    /**
     * 最近一次本机发送的用户消息 id：气泡入场只播一次，播过由界面消费清空。
     * 新会话首条发送会重建聊天舞台的组合边界，靠列表比对判断「刚追加」会漏掉这条。
     */
    var sentUserMessageEntranceId by mutableStateOf<String?>(null)
        private set

    fun consumeSentUserMessageEntrance(id: String) {
        if (sentUserMessageEntranceId == id) sentUserMessageEntranceId = null
    }

    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private val runConversationIds = mutableMapOf<String, String>()
    private val runUsageResumeRounds = mutableMapOf<String, Int>()
    private val runUsageOwners = mutableMapOf<String, Pair<String, String>>()
    private val runRequestRounds = mutableMapOf<String, Int>()
    private val runUsageRoutes = mutableMapOf<String, String>()
    private val usageRunByConversation = mutableMapOf<String, String>()
    private val contextEstimateDiagnostics = ContextEstimateDiagnostics()
    private val invalidatedUsageRuns = mutableSetOf<String>()
    private val runGeneratedAtMillis = mutableMapOf<String, Long>()
    // A stopped worker still owns its transcript until its terminal result is committed.
    private val stoppingRuns = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    // The UI must be able to unlock itself: a stop that never gets a RunResult cannot hold the screen.
    private val stopSealTimeout = RunStopSealTimeout()
    private val stopSealTerminalTimeout =
        RunStopSealTimeout(timeoutMillis = RunStopSealTimeout.TERMINAL_GRACE_MS)
    private val stopSealWatchdogJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val mainStopReasons = java.util.concurrent.ConcurrentHashMap<String, AgentChildControlPolicy.Reason>()
    private val modelRetryState = AgentRunRetryState()
    private val runOverheadTokens = mutableMapOf<String, Int>()

    /**
     * Window the run was actually launched with.
     *
     * A run keeps the config it snapshotted at send time, so changing the maximum
     * context mid-run does not affect the request already in flight. The picker,
     * however, immediately reports the new window, and judging an in-flight run
     * against it made the percentage jump for reasons the run never saw.
     */
    private val runContextWindows = mutableMapOf<String, Int>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runReplayBatch = AgentRunReplayBatch()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val conversationSummaryCache = ConversationSummaryCache()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private val runEventBudgets = mutableMapOf<String, AgentRunEventBudget<AgentEvent>>()
    private val runEventBudgetCallbacks = linkedSetOf<String>()
    private val runEventDeferredFlushes = mutableMapOf<String, () -> Unit>()
    private val runFrameEventBudget = AgentFrameEventBudget(STREAM_EVENT_FRAME_BUDGET_NS)
    private var runEventBudgetFrameCallback: Choreographer.FrameCallback? = null
    private val drainingRunEventBudgets = mutableSetOf<String>()
    private var runEventFrameWorkDepth = 0
    private val runJobs = androidx.compose.runtime.mutableStateMapOf<String, Job>()
    private val imageGenerationRunIds = mutableSetOf<String>()
    private val directMediaRuns = DirectMediaRunControl()
    private val ownerContexts = mutableMapOf<String, AgentOwnerContextState>()
    private val contextHideJobs = mutableMapOf<AgentOwnerContextState.HideToken, Job>()
    private data class PendingSteerDraft(
        val conversationId: String?, val imageIds: Set<String>, val fileIds: Set<String>,
        val mentionIds: Set<String> = emptySet(),
        val submittedText: String = "",
    )
    private val pendingSteerDrafts = mutableMapOf<String, PendingSteerDraft>()
    private var compressionJob: Job? = null
    /** Conversation the active [compressionJob] belongs to; null for none/unknown. */
    private var compressionJobConversationId: String? = null
    private var pendingManualCompress: PendingManualCompress? = null
    private val pendingInRunCompactConversationIds = mutableSetOf<String>()
    private var pendingRetiredUsage = ConversationTokenUsageUi()
    private var pendingRetiredConversations = 0
    private var pendingRetiredMessages = 0
    private var pendingRetiredHeatmap = emptyMap<java.time.LocalDate, Int>()
    private val runCompressedDuringRun = mutableSetOf<String>()
    data class ContextBudgetPrompt(val runId: String, val conversationId: String, val reason: String)
    var contextBudgetPrompt by mutableStateOf<ContextBudgetPrompt?>(null)
        private set
    private val contextBudgetBlockedRuns = mutableMapOf<String, ContextBudgetPrompt>()

    fun dismissContextBudgetPrompt() { contextBudgetPrompt = null }

    fun allowCurrentRunCompaction(runId: String) {
        val prompt = contextBudgetBlockedRuns[runId] ?: return
        if (prompt.conversationId != selectedConversationId) return
        scope.launch {
            val sent = withContext(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).compactRun(
                    runId,
                    AgentContextCompactor.keepRecentFor(),
                )
            }
            if (sent) contextBudgetPrompt = null
            else Toast.makeText(appContext, appContext.getString(R.string.appst_runtime_unreachable_pause), Toast.LENGTH_LONG).show()
        }
    }

    fun stopBudgetBlockedRun(runId: String) {
        val prompt = contextBudgetBlockedRuns[runId] ?: return
        if (prompt.conversationId != selectedConversationId) return
        contextBudgetPrompt = null
        abandonPausedRun()
    }

    private val persistenceLock = Any()
    private val persistenceQueue by lazy {
        LatestConversationSaveQueue<ConversationSaveSnapshot>(
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.IO),
            merge = { previous, latest -> latest.mergeRetiredFrom(previous) },
            write = { snapshot -> writeConversationSnapshot(snapshot) },
        )
    }
    private val conversationPersistenceMutex = Mutex()
    @Volatile private var lastConversationPersistenceError: String? = null
    private var conversationArchiveBusy = false
    private var runtimeRefreshAfterArchive = false
    private var bindingRefreshAfterArchive = false
    private val deferredArchiveSaves = mutableListOf<Pair<kotlinx.coroutines.CompletableDeferred<Boolean>, (() -> Unit)?>>()
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    /** 恢复进行中又来了新请求：不丢弃、合并成「跑完再来一遍」。 */
    private val runtimeRefreshPending = AtomicBoolean(false)
    // Main-thread cursor: bounded recovery passes rotate rather than dropping older owners.
    private var questionRecoveryQueryOffset = 0
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null
    @Volatile private var selectionProviders: List<io.github.mangi.eta.data.model.ProviderSetting> = emptyList()

    /** In-memory memo keyed by the provider snapshot identity plus binding ids.
     * Not a data class so no generated toString can expose provider credentials. */
    private class ContextRouteSignatureEntry(
        val providers: List<io.github.mangi.eta.data.model.ProviderSetting>,
        val providerId: String,
        val modelId: String,
        val signature: String?,
    )

    @Volatile private var contextRouteSignatureEntry: ContextRouteSignatureEntry? = null
    private var defaultProviderId: String? = null
    private var defaultModelId: String? = null
    private var modelBindingGeneration = 0L
    private var memoryEditGeneration = 0L
    private val overheadSelection = RequestOverheadSelection()
    private var overheadConfigurationGeneration = 0L

    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var fileAttachmentOwnerVersion = 0L
    private var conversationSelectionVersion = 0L
    private var conversationSelectionJob: Job? = null
    private var deleteAllConversationsJob: Job? = null
    private val preparingConversationMentions = mutableSetOf<String>()
    private val chatImageCache = AgentChatImageCache(appContext)


    private var selectedConversationId: String?
    private var conversationsById: Map<String, AgentChatHomeUiState>
    private var conversationTitles: Map<String, String>
    private var conversationUpdatedAt: Map<String, Long>
    private var conversationCreatedAt: Map<String, Long>
    private var conversationFolderIds: Map<String, String>
    private var conversationPinned: Set<String>
    private var conversationCompletionMarkers: Set<String>
    /** 回收站：会话 id -> 软删除时间；键集合即回收站成员，删除时间用于回收站列表排序。 */
    private var conversationDeletedAt: Map<String, Long>
    private var conversationFolders: List<ConversationFolderUi>
    /** Startup hydration and backup reload both publish the same maps; only one wins. */
    private var conversationsHydrated = false
    /**
     * Completed once [conversationsHydrated] becomes true, so construction-time background
     * work can wait for the real conversation map instead of observing the empty seed.
     * Deliberately not snapshot state: it signals a one-shot lifecycle transition, and
     * making it observable would recompose every reader on the commit frame.
     */
    private val conversationsHydratedSignal = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        // A constructor-local snapshot must not pin the original transcript after
        // compression, deletion or backup reload replaces the live conversation map.
        //
        // Startup used to read Room here synchronously, loading every conversation's
        // content (paged messages plus history files). AgentSessionHost is a process-wide
        // singleton built from a ViewModel, so that runBlocking ran on the main thread and
        // held the first frame behind the whole transaction.
        //
        // The selected id still has to be known NOW, because property initializers below
        // (sub-agent draft seeding) read it as they run. That is one primary-key row read
        // on the single-row conversation_state table, not the full snapshot. Content itself
        // is hydrated off the main thread by hydrateConversations().
        val initialConversations = AgentConversationStore.emptySnapshot(
            selectedConversationId = AgentConversationStore.persistedSelectedConversationId(appContext),
        )
        selectedConversationId = initialConversations.selectedConversationId
        conversationsById = initialConversations.conversationsById
        conversationTitles = initialConversations.titles
        conversationUpdatedAt = initialConversations.updatedAt
        conversationCreatedAt = initialConversations.createdAt
        conversationFolderIds = initialConversations.folderIds
        conversationPinned = initialConversations.pinnedIds
        conversationCompletionMarkers = initialConversations.completionMarkerIds
        conversationDeletedAt = initialConversations.deletedAt
        conversationFolders = initialConversations.folders
        hydrateConversations()
        // 派发结算通知：Runtime 侧只发布，注入（steer / 普通一轮）由 UI 自己的通路完成。
        scope.launch {
            io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.notices.collect { notice ->
                handleDispatchNotice(notice)
            }
        }
        // 子会话提问同步：只读展示 + 跳转作答，不注入调度台模型（模型无法代答）。
        scope.launch {
            io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.questionNotices.collect { notice ->
                handleDispatchQuestionNotice(notice)
            }
        }
        // 子会话手机操作权等待：并发 GUI 时无限期排队的可见性（transient，仅卡片副标题）。
        scope.launch {
            io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.controlNotices.collect { notice ->
                handleDispatchControlNotice(notice)
            }
        }
        // 排队单排空/重启恢复开跑：DispatchCreated 发不出时的补位（绑卡标题 + 挂接子 run）。
        scope.launch {
            io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.startedNotices.collect { notice ->
                handleDispatchStartedNotice(notice)
            }
        }
    }

    /**
     * Load the persisted conversation map off the main thread and publish it once.
     *
     * Guarded by [conversationsHydrated] so a second construction (or a backup reload
     * landing first) cannot rewind the live maps with an older snapshot.
     */
    private fun hydrateConversations() {
        scope.launch {
            val snapshot = runCatching {
                withContext(Dispatchers.IO) {
                    AgentConversationStore.loadSuspending(appContext, selectedOnly = true)
                }
            }.getOrElse { failure ->
                AndroidAgentLogger.error(
                    "Startup conversation load failed: type=${failure.safeLogType()}"
                )
                // Release the waiters so construction-time recovery is not parked forever.
                // conversationsHydrated stays false, so saves remain refused and the
                // destructive image sweep is skipped: the map is still the empty seed.
                conversationsHydratedSignal.complete(Unit)
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                // A backup reload owns the maps once it has run; do not overwrite it.
                if (conversationsHydrated) return@withContext
                conversationsHydrated = true
                conversationsHydratedSignal.complete(Unit)
                selectedConversationId = snapshot.selectedConversationId
                conversationsById = snapshot.conversationsById.mapValues { (_, state) -> orderedTerminalState(state) }
                conversationTitles = snapshot.titles
                conversationUpdatedAt = snapshot.updatedAt
                conversationCreatedAt = snapshot.createdAt
                conversationFolderIds = snapshot.folderIds
                conversationPinned = snapshot.pinnedIds
                conversationCompletionMarkers = snapshot.completionMarkerIds - listOfNotNull(snapshot.selectedConversationId)
                conversationDeletedAt = snapshot.deletedAt
                conversationFolders = snapshot.folders
                conversationDispatchMarks = snapshot.dispatchMarkedIds
                homeState = selectedConversationId?.let(::conversationState) ?: newDraftChatState()
                conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
                refreshConversationSummaries()
                // 先把调度台从回收站恢复/补建，再清过期回收站，避免老版本删掉的调度台被误清理。
                ensureDispatchConsoleSelected()
                // 启动水合后先清一次过期回收站：App 长期不开回收站页也能按留存期落盘清理。
                purgeExpiredTrash()
            }
        }
    }

    // ============================================================ 调度台（Session Dispatch）

    private val handledDispatchNotices =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** 派发子会话正在等待用户回答提问的会话 id 集：抽屉行据此显示等待标记，回答/终态即清。 */
    private var conversationWaitingQuestionIds by mutableStateOf(setOf<String>())

    /** 提问通知恰一次去重（replay 兜底 Activity 重建）；key = dispatchId:questionId:phase。 */
    private val handledDispatchQuestionNotices =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /**
     * DispatchCreated 早于工具卡片落定（或队列稍后才开跑）时暂存子会话标题，
     * 供 ToolFinished 在同一张卡上补齐身份；消费即删，避免无界增长。
     */
    private val pendingDispatchTitles = mutableMapOf<String, String>()

    /**
     * 调度台暂停/停止期间到达的子任务汇报。此时只落卡片、不碰模型输入
     * （不新起一轮、不 steer）；等用户恢复或下次主动发言时，由
     * [drainPendingConsoleReceipts] / [takePendingConsoleReceiptsForSteer] 并入那一轮。
     * pending 只活在本进程：进程重建后旧汇报仍可在卡片的终态里看到。
     */
    private val pendingConsoleReceipts = mutableListOf<String>()

    /**
     * 调度台被用户显式停止后，回报只更新卡片，不再自动拉起新一轮——否则「停止」形同虚设。
     * 用户再次在调度台发言或恢复运行时解除。
     */
    private var consoleDispatchAutoRunBlocked = false

    /** 派生会话标记（调度台所建或接续中）；内存投影，真相在 conversations 两列。 */
    private var conversationDispatchMarks by mutableStateOf(setOf<String>())

    /**
     * 冷启动 / 回到主页 / 备份恢复后：确保调度台存在，软删除自动恢复，置顶强制成立。
     *
     * 已存在的条目只修复状态，不刷新时间戳，避免每次冷启动把它顶到最前；
     * 只有真正缺失时才用固定 id 重建（幂等，不会产生重复行）。
     */
    fun ensureDispatchConsoleSelected() {
        if (!conversationsHydrated) return
        val consoleId = dispatchConsoleId() ?: createDispatchConsole()
        var repaired = false
        if (consoleId in conversationDeletedAt) {
            // 老版本允许把调度台删进回收站：这里自动恢复，内容和配置原样保留。
            conversationDeletedAt = conversationDeletedAt - consoleId
            repaired = true
        }
        if (consoleId !in conversationPinned) {
            conversationPinned = conversationPinned + consoleId
            repaired = true
        }
        // 调度台不参与分类：藏「移动到分类」入口前的版本允许把它移进文件夹，
        // 这里清掉历史遗留归属，避免它出现在某个文件夹的筛选视图里。
        if (consoleId in conversationFolderIds) {
            conversationFolderIds = conversationFolderIds - consoleId
            repaired = true
        }
        // 标题是真实会话属性：这里只修身份与保护状态，绝不改标题，也不动 updatedAt。
        if (repaired) {
            refreshConversationSummaries()
            persistConversations()
        }
        if (repaired || selectedConversationId != consoleId) selectConversation(consoleId)
    }

    /**
     * 调度台侧栏固定显示名：复用内置调度台助手的既有常量，不新增资源键。
     * 只用于显示层与新建时的初始真实标题，不参与已有标题的持续纠正。
     */
    private fun dispatchConsoleTitle(): String =
        io.github.mangi.eta.data.model.AssistantPrompt.DISPATCHER_NAME

    /**
     * 调度台的稳定身份：固定 id 是权威来源；只有老档案里没有这行时，才退回内置调度台助手身份。
     * 两种情况都不读标题，用户改名不会改变保护。
     */
    private fun dispatchConsoleId(): String? =
        if (DISPATCH_CONSOLE_CONVERSATION_ID in conversationsById) {
            DISPATCH_CONSOLE_CONVERSATION_ID
        } else {
            conversationsById.entries
                // 归档快照沿用调度台助手的 assistantId，必须排除，否则固定 id 缺失时
                // 回收站里的一段快照会被当成调度台本体，绕过「不可删除」保护。
                .firstOrNull { (id, state) ->
                    !isDispatchConsoleArchiveSnapshot(id) &&
                        state.assistantId == io.github.mangi.eta.data.model.AssistantPrompt.DISPATCHER_ID
                }
                ?.key
        }

    /** 调度台在侧栏是系统级条目：不可删除、不可取消置顶。 */
    private fun isDispatchConsoleConversation(conversationId: String): Boolean =
        conversationId == dispatchConsoleId()

    /** 当前选中会话是否为调度台本身；顶栏「回到调度台」按钮以此为显隐依据。 */
    val currentIsDispatchConsole: Boolean
        get() = selectedConversationId != null && selectedConversationId == dispatchConsoleId()

    private fun createDispatchConsole(): String {
        val conversationId = DISPATCH_CONSOLE_CONVERSATION_ID
        // 幂等兜底：并发或重入时绝不生成第二行。
        if (conversationId in conversationsById) return conversationId
        val draft = newDraftChatState().copy(
            assistantId = io.github.mangi.eta.data.model.AssistantPrompt.DISPATCHER_ID,
        )
        conversationSelectionVersion += 1
        conversationSelectionJob?.cancel()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        homeState = draft
        updateConversation(conversationId, draft)
        conversationTitles = conversationTitles + (conversationId to dispatchConsoleTitle())
        conversationPinned = conversationPinned + conversationId
        conversationDeletedAt = conversationDeletedAt - conversationId
        conversationCompletionMarkers = conversationCompletionMarkers - conversationId
        // 时间戳只在真正重建时写一次；后续冷启动沿用数据库里的原值，不会每次顶到最前。
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        persistConversations()
        return conversationId
    }

    // ---------------------------------------------------------------- 调度台清空主会话（归档 + 开新篇）

    /**
     * 调度台「清空主会话」：把当前全部消息复制成一段快照移入回收站，然后清空会话本体。
     *
     * 语义是归档而不是删除：
     * - 快照是独立 id 的普通会话行（软删除），回收站里与普通会话一致，可恢复、可永久删除；
     * - 调度台本体只清消息与上下文，并把真实标题复位成新建时的初始值（顶栏随之复原）；
     *   置顶与 updatedAt 不动，侧栏仍固定显示「调度台」；
     * - 快照消息 id 统一加 `archive-<seq>-` 前缀，多次清空的块据此排序，恢复时可精确去重。
     */
    fun clearDispatchConsoleContext() {
        if (rejectConversationArchiveMutation()) return
        val consoleId = dispatchConsoleId() ?: return
        val console = conversationState(consoleId)?.takeIf { it.conversationContentLoaded } ?: return
        // 空会话与生成中：按钮已禁用，这里再兜底一次，保证幂等且不会切走正在跑的一轮。
        if (console.messages.isEmpty() || console.isStreaming || console.activeRunId != null) return

        val now = System.currentTimeMillis()
        val seq = archiveSequence(now)
        val snapshotId = "$DISPATCH_CONSOLE_ARCHIVE_PREFIX$seq-${UUID.randomUUID().toString().take(8)}"
        // 必须在下面「复位本体标题」之前读取：快照标题要用被清空那一刻的真实标题，
        // 复位后的「调度台」不算旧标题。
        val consoleTitleBeforeClear = conversationTitles[consoleId]
        val snapshotMessages = console.messages.map { message ->
            message.withMessageId("$ARCHIVE_MESSAGE_ID_PREFIX$seq-${message.id}")
        }
        val snapshot = emptyChatState(console.thinkingEnabled).copy(
            messages = snapshotMessages,
            // 另存模型历史，并给每一轮的 turnId 打上块序号：恢复时靠它把这段 history
            // 按 A → B → 当前 插回正确位置，并避免同一轮重复写入。
            history = tagArchiveHistory(console.history, seq),
            providerId = console.providerId,
            modelId = console.modelId,
            assistantId = console.assistantId,
            reasoningEffort = console.reasoningEffort,
            availableReasoningEfforts = console.availableReasoningEfforts,
            gptSpeedMode = console.gptSpeedMode,
        )
        conversationsById = conversationsById + (snapshotId to snapshot)
        conversationTitles = conversationTitles + (snapshotId to archiveSnapshotTitle(now, consoleTitleBeforeClear))
        conversationCreatedAt = conversationCreatedAt + (snapshotId to now)
        conversationUpdatedAt = conversationUpdatedAt + (snapshotId to now)
        conversationDeletedAt = conversationDeletedAt + (snapshotId to now)
        conversationPinned = conversationPinned - snapshotId
        conversationCompletionMarkers = conversationCompletionMarkers - snapshotId

        // 清空本体：只保留模型/助手绑定，其余上下文、草稿、子代理统计全部丢弃。
        ownerContexts.remove(consoleId)
        conversationDrafts.remove(consoleId)
        // 排队待发消息与旧上下文绑定，不清的话新一轮会把它们当作后续追问补发。
        queuedMessagesByConversation.remove(consoleId)
        pendingConversationTitles.remove(consoleId)
        titleRequestSeq.remove(consoleId)
        manuallyRenamedDuringTitleRequest -= consoleId
        // 真实标题复位成「新建调度台」时的初始值（顶栏读的就是这个字段，否则会停在
        // 上一轮自动生成的标题里）；侧栏展示名由 displayTitle 固定为「调度台」，
        // 不读这里。快照标题已在上面单独写入，不受影响。上一行已解除重命名守卫，
        // 且 history 清空后下一轮会重新登记标题生成，复位不会阻塞后续自动标题。
        conversationTitles = conversationTitles + (consoleId to dispatchConsoleTitle())
        val cleared = emptyChatState(console.thinkingEnabled).copy(
            providerId = console.providerId,
            modelId = console.modelId,
            assistantId = console.assistantId,
            reasoningEffort = console.reasoningEffort,
            availableReasoningEfforts = console.availableReasoningEfforts,
            gptSpeedMode = console.gptSpeedMode,
            conversationContentLoaded = true,
        )
        updateConversation(consoleId, cleared, updateTimestamp = false)
        refreshConversationSummaries()

        // 快照与本体重写放在同一次持久化里：要么都落库，要么都保持原状。
        val persisted = persistConversations()
        scope.launch(Dispatchers.IO) {
            val saved = persisted.await()
            if (!saved) AndroidAgentLogger.warn("dispatch_console_clear_persist_failed")
            // 兜底清掉运行态副作用：run history 文件、checkpoint、outbox、压缩原文。
            discardRuntimeTracesForConversation(consoleId)
            if (saved) AgentConversationHistoryFiles.file(appContext, consoleId).delete()
        }
    }

    /**
     * 回收站恢复调度台归档：把快照消息插到「当前消息之前」，按归档序号保持 A → B → 当前，
     * 完成后删除回收站记录，避免重复恢复产生重复内容。
     */
    private fun restoreDispatchConsoleArchive(snapshotId: String) {
        val consoleId = dispatchConsoleId() ?: return
        if (consoleId == snapshotId) return
        val snapshotSeq = archiveSequenceOfSnapshotId(snapshotId) ?: return
        scope.launch(Dispatchers.Main.immediate) {
            // 优先用内存里的快照：刚落库尚未完成时也能立刻接回，不必等持久化队列。
            val snapshot = conversationState(snapshotId)?.takeIf { it.conversationContentLoaded }
                ?: withContext(Dispatchers.IO) {
                    AgentConversationStore.loadConversation(appContext, snapshotId)
                }
            if (snapshot == null) return@launch
            val console = conversationState(consoleId)?.takeIf { it.conversationContentLoaded }
                ?: withContext(Dispatchers.IO) {
                    AgentConversationStore.loadConversation(appContext, consoleId)
                }
                ?: return@launch
            if (console.isStreaming || console.activeRunId != null) {
                Toast.makeText(appContext, appContext.getString(R.string.appst_archive_resume_busy), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val seqTag = snapshotSeq.toString()
            val seen = console.messages.mapTo(mutableSetOf()) { it.id }
            // 恢复消息 id 统一成 `user-archive-<块序号>-<轮标识>` / `archive-<块序号>-<块内标识>`：
            // 既与 history 的 turnId 对齐（编辑/分支按 `user-<turnId>` 找回轮次），
            // 也保证同一块重复恢复时 id 稳定、可直接去重。
            val restored = snapshot.messages.mapNotNull { message ->
                val restoredId = canonicalArchiveMessageId(message, seqTag)
                if (seen.add(restoredId)) message.withMessageId(restoredId) else null
            }
            // 模型上下文同步接回：优先用快照里另存的 history；文件缺失或为空时，
            // 按普通会话的 legacy 路径由完整消息集合重建，保证下一轮 run 真的能读到。
            val blockHistory = snapshot.history
                .takeIf { it.isNotEmpty() }
                ?.let { tagArchiveHistory(it, seqTag) }
                ?: rebuildArchiveHistory(snapshot.messages, seqTag)
            val historyAlreadyMerged = console.history.any { message ->
                archiveSequenceOfBlockTag(message.turnId) == snapshotSeq
            }
            val mergedHistory = when {
                blockHistory.isEmpty() || historyAlreadyMerged -> console.history
                else -> {
                    // 与消息同一规则：插到所有序号更大的归档轮之前，当前轮永远在最后。
                    val insertAt = console.history.indexOfFirst { message ->
                        val blockSeq = archiveSequenceOfBlockTag(message.turnId)
                        blockSeq == null || blockSeq > snapshotSeq
                    }.let { if (it < 0) console.history.size else it }
                    buildList {
                        addAll(console.history.subList(0, insertAt))
                        addAll(blockHistory)
                        addAll(console.history.subList(insertAt, console.history.size))
                    }
                }
            }
            val mergedMessages = if (restored.isEmpty()) {
                console.messages
            } else {
                // 插到所有序号更大的归档块之前；当前消息（无归档序号）永远在最后。
                val insertAt = console.messages.indexOfFirst { message ->
                    val blockSeq = archiveSequenceOfBlockTag(message.id)
                    blockSeq == null || blockSeq > snapshotSeq
                }.let { if (it < 0) console.messages.size else it }
                buildList {
                    addAll(console.messages.subList(0, insertAt))
                    addAll(restored)
                    addAll(console.messages.subList(insertAt, console.messages.size))
                }
            }
            if (mergedMessages !== console.messages || mergedHistory !== console.history) {
                updateConversation(
                    consoleId,
                    console.copy(messages = mergedMessages, history = mergedHistory),
                    updateTimestamp = false,
                )
            }
            discardConversationRecord(snapshotId)
            refreshConversationSummaries()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
            persistConversations()
        }
    }

    /** 归档序号：固定 16 位，便于按字符串比较，也便于从 id 反解。 */
    private fun archiveSequence(now: Long): String = now.toString().padStart(16, '0')

    /**
     * 归档快照标题：优先沿用被清空那一刻的真实标题，回收站里能认出是哪一段；
     * 没有可用标题时回退到时间戳，保证快照标题始终可读、可用于检索。
     *
     * 保留「调度台归档 · 」前缀而不是直接用原标题：回收站里要能一眼区分归档快照与
     * 普通会话，多段归档也不至于和原本地会话标题混淆。空白或仍是新建初值
     * [dispatchConsoleTitle]（说明这一段还没生成过标题）都视为「没有可用标题」。
     */
    private fun archiveSnapshotTitle(now: Long, sourceTitle: String?): String {
        val original = sourceTitle?.trim().orEmpty()
        val suffix = original
            .takeUnless { it.isEmpty() || it == dispatchConsoleTitle() }
            ?: DateFormat.format("yyyy-MM-dd HH:mm", now)
        return "调度台归档 · $suffix"
    }

    private fun isDispatchConsoleArchiveSnapshot(conversationId: String): Boolean =
        conversationId.startsWith(DISPATCH_CONSOLE_ARCHIVE_PREFIX)

    private fun archiveSequenceOfSnapshotId(conversationId: String): Long? {
        if (!isDispatchConsoleArchiveSnapshot(conversationId)) return null
        return conversationId.removePrefix(DISPATCH_CONSOLE_ARCHIVE_PREFIX)
            .substringBefore('-').toLongOrNull()
    }

    /** 从 `archive-<块序号>-` 前缀（消息 id 与 history turnId 共用，user 消息带 `user-` 壳）反解块序号。 */
    private fun archiveSequenceOfBlockTag(value: String): Long? {
        val tag = when {
            value.startsWith(ARCHIVE_MESSAGE_ID_PREFIX) -> value
            value.startsWith("user-$ARCHIVE_MESSAGE_ID_PREFIX") -> value.removePrefix("user-")
            else -> return null
        }
        return tag.removePrefix(ARCHIVE_MESSAGE_ID_PREFIX)
            .substringBefore('-').toLongOrNull()
    }

    /**
     * 把快照消息 id 归一成块标记形态。user 消息还原成 `user-archive-<seq>-<轮标识>`，
     * 与 history 的 `turnId = archive-<seq>-<轮标识>` 对应；其余消息为 `archive-<seq>-<块内标识>`。
     * 兼容三种来源：清空时刚打的 `archive-<seq>-user-<turnId>`、库里身份迁移后的
     * `user-archive-<seq>-<turnId>`，以及嵌套块（`archive-<seqB>-archive-<seqA>-…`）。
     */
    private fun canonicalArchiveMessageId(message: AgentChatMessageUi, seq: String): String {
        var raw = message.id
        if (raw.startsWith("user-$ARCHIVE_MESSAGE_ID_PREFIX")) {
            raw = raw.removePrefix("user-")
        }
        if (raw.startsWith(ARCHIVE_MESSAGE_ID_PREFIX)) {
            raw = raw.removePrefix(ARCHIVE_MESSAGE_ID_PREFIX)
        }
        raw = raw.removePrefix("$seq-")
        if (raw.startsWith("user-")) raw = raw.removePrefix("user-")
        val prefix = if (message is UserMessageUi) {
            "user-$ARCHIVE_MESSAGE_ID_PREFIX$seq-"
        } else {
            "$ARCHIVE_MESSAGE_ID_PREFIX$seq-"
        }
        return prefix + raw
    }

    /**
     * 给一段模型历史的每一轮 turnId 打上块序号；已有同块标记的保持原样。
     * turnId 只是本地元数据（不发给 Provider），加前缀不影响请求内容。
     */
    private fun tagArchiveHistory(
        history: List<AgentModelClient.ConversationMessage>,
        seq: String,
    ): List<AgentModelClient.ConversationMessage> =
        AgentTurnIdentity.migrate(history).map { message ->
            val turnId = message.turnId
            when {
                turnId.isBlank() -> message
                turnId.startsWith("$ARCHIVE_MESSAGE_ID_PREFIX$seq-") -> message
                else -> message.copy(turnId = "$ARCHIVE_MESSAGE_ID_PREFIX$seq-$turnId")
            }
        }

    /**
     * 快照的 history 不可用（为空）时，按普通会话的 legacy 重建路径从消息还原上下文：
     * 只有 user / assistant 文本轮进入模型输入；工具卡、状态注记、思考、工具汇总、
     * 上下文压缩行等只用于展示，不重建进 history。
     */
    private fun rebuildArchiveHistory(
        messages: List<AgentChatMessageUi>,
        seq: String,
    ): List<AgentModelClient.ConversationMessage> {
        val rebuilt = messages.mapNotNull { message ->
            when (message) {
                is UserMessageUi -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { AgentModelClient.ConversationMessage(role = "user", content = it) }
                is AgentMessageUi -> message.content
                    .takeIf { it.isNotBlank() }
                    ?.let { AgentModelClient.ConversationMessage(role = "assistant", content = it) }
                else -> null
            }
        }
        return tagArchiveHistory(rebuilt, seq)
    }

    /** 仅移除会话记录本身，不走用量退休：归档快照是原会话的副本，退休会重复计费。 */
    private fun discardConversationRecord(conversationId: String) {
        conversationDrafts.remove(conversationId)
        pendingSelectionByKey.remove(conversationId)
        pendingConversationTitles.remove(conversationId)
        titleRequestSeq.remove(conversationId)
        manuallyRenamedDuringTitleRequest -= conversationId
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        conversationCreatedAt = conversationCreatedAt - conversationId
        conversationFolderIds = conversationFolderIds - conversationId
        conversationPinned = conversationPinned - conversationId
        conversationCompletionMarkers = conversationCompletionMarkers - conversationId
        conversationDeletedAt = conversationDeletedAt - conversationId
    }

    /**
     * 清空后让模型上下文真正失效：删除该会话的 checkpoint、归档终态、outbox 结果与压缩原文。
     * Room 消息与上下文 checkpoint 由清空后的持久化覆盖；历史文件在持久化成功后再删一次。
     */
    private suspend fun discardRuntimeTracesForConversation(conversationId: String) {
        runCatching {
            AgentRunCheckpointStore.list(appContext).forEach { checkpoint ->
                val owner = runCatching {
                    AgentUiHandoffPayload.from(checkpoint.handoff.payload).conversationId
                }.getOrNull()
                if (owner == conversationId) {
                    AgentRunCheckpointStore.remove(appContext, checkpoint.runId)
                }
            }
            AgentRunArchiveStore.list(appContext).forEach { archived ->
                val runId = archived.result.runId.ifBlank { archived.handoff.id }
                val owner = runCatching {
                    AgentConversationIdentity.fromHandoff(archived.handoff, runId)
                }.getOrNull()
                if (owner == conversationId) {
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
            // outbox 双保险：先直接删本地待领取结果表，再尽力向 Runtime 确认（服务未起时前者仍然生效）。
            AgentRuntimeResultStore.list(appContext).forEach { completed ->
                if (completedRunConversationId(completed) == conversationId) {
                    AgentRuntimeResultStore.remove(
                        appContext,
                        completed.result.runId.ifBlank { completed.handoff.id },
                    )
                }
            }
            val completedRuns =
                (AgentRuntimeClient(appContext, AndroidAgentLogger).queryCompletedRuns() as?
                    AgentRuntimeClient.CompletedRunsQuery.Known)?.runs.orEmpty()
            completedRuns.forEach { run ->
                if (completedRunConversationId(run) == conversationId) {
                    AgentRuntimeClient(appContext, AndroidAgentLogger)
                        .ackResult(run.result.runId.ifBlank { run.handoff.id })
                }
            }
            io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, conversationId).delete()
        }.onFailure {
            AndroidAgentLogger.warn("调度台上下文清理失败：${it.javaClass.simpleName}")
        }
    }

    private fun handleDispatchNotice(notice: io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.Notice) {
        // 恰一次：replay 兜底 Activity 重建，这里按 dispatchId+结果去重。
        if (!handledDispatchNotices.add("${notice.dispatchId}:${notice.outcome.name}")) return
        scope.launch(Dispatchers.Main.immediate) {
            markDispatchManaged(notice.conversationId)
            pendingDispatchTitles.remove(notice.dispatchId)
            // run 已终态：等待提问标记必然随之结束（提问中被打断/停止的路径不经过 RESOLVED 通知）。
            conversationWaitingQuestionIds = conversationWaitingQuestionIds - notice.conversationId
            // 先把终态、正文与跳转身份写进调度台的 `task_dispatch` 卡片，再走既有注入路径。
            // 顺序反了会让模型的一轮复述先于卡片更新出现，看起来像凭空多一条回复。
            updateDispatchReportCard(notice)
            // 结算回报到达说明该 run 已经终态；物化完成后再用 outbox 兜底折叠结果，
            // 避免会话尚未进内存时回收把结果丢掉。
            materializeDispatchConversation(notice.conversationId, notice.title) {
                refreshRuntimeResults()
            }
            deliverDispatchNotice(notice)
        }
    }

    /**
     * 子会话提问同步到调度台（只读展示 + 跳转作答，不注入调度台模型——模型无法代答）。
     * - ASKED：`task_dispatch` 卡写入「等待回答」快照 + 抽屉行等待标记
     *   （后台系统横幅由服务侧在发布点直接发，前台静默）；
     * - RESOLVED：卡片恢复「执行中」、标记清除（横幅由服务侧撤销）。
     * 找不到派发卡时不硬造：提问必晚于派发，卡已存在；旧数据/被清理时跳过展示，角标照常。
     */
    private fun handleDispatchQuestionNotice(
        notice: io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.QuestionNotice,
    ) {
        // 去重键取 notice.questionId（RESOLVED 阶段 request 为 null，运行时侧显式带 id），
        // 同一 run 连环提问时每问各有一键，不会互相吞并。
        if (!handledDispatchQuestionNotices.add("${notice.dispatchId}:${notice.questionId}:${notice.phase}")) return
        scope.launch(Dispatchers.Main.immediate) {
            markDispatchManaged(notice.conversationId)
            when (notice.phase) {
                io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.QuestionNotice.Phase.ASKED -> {
                    // 防御性物化：子会话行不在内存时补建，抽屉标记才有落点（与结算通知同路径）。
                    materializeDispatchConversation(notice.conversationId, notice.conversationTitle)
                    conversationWaitingQuestionIds = conversationWaitingQuestionIds + notice.conversationId
                    notice.request?.let { request ->
                        updateDispatchCardWaitingQuestion(
                            dispatchId = notice.dispatchId,
                            waiting = DispatchWaitingQuestionUi(
                                questionTitle = request.title,
                                questionText = request.question,
                            ),
                        )
                    }
                }
                io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.QuestionNotice.Phase.RESOLVED -> {
                    conversationWaitingQuestionIds = conversationWaitingQuestionIds - notice.conversationId
                    updateDispatchCardWaitingQuestion(dispatchId = notice.dispatchId, waiting = null)
                }
            }
            refreshConversationSummaries()
        }
    }

    /**
     * 写/清派发卡的「等待回答」快照：只动 [ToolActivityMessageUi.waitingQuestion]，
     * 不碰状态与正文（Running 态的终态更新仍由结算回报负责）。仅内存投影，不落库。
     * 卡不存在/已终态时跳过；写入与清除都以等值比较幂等。
     */
    private fun updateDispatchCardWaitingQuestion(
        dispatchId: String,
        waiting: DispatchWaitingQuestionUi?,
    ) {
        val consoleId = dispatchConsoleId() ?: return
        val state = conversationState(consoleId) ?: return
        if (!state.conversationContentLoaded) return
        val index = dispatchCardIndex(state.messages, dispatchId)
        if (index < 0) return
        val card = state.messages[index] as ToolActivityMessageUi
        if (card.waitingQuestion == waiting) return
        if (waiting != null && card.status != ToolActivityStatusUi.Running) return
        updateConversation(
            consoleId,
            state.copy(
                messages = state.messages.incrementalSnapshot().replacing(
                    index,
                    card.copy(waitingQuestion = waiting),
                ),
            ),
        )
    }

    /**
     * 子会话手机操作权等待同步（transient）：卡片副标题「等待手机操作权」，
     * 获得/释放/终态即清。协调器已按翻转去重，这里等值比较兜底 replay 重投。
     */
    private fun handleDispatchControlNotice(
        notice: io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.ControlNotice,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            val consoleId = dispatchConsoleId() ?: return@launch
            val state = conversationState(consoleId) ?: return@launch
            if (!state.conversationContentLoaded) return@launch
            val index = dispatchCardIndex(state.messages, notice.dispatchId)
            if (index < 0) return@launch
            val card = state.messages[index] as ToolActivityMessageUi
            if (card.waitingControl == notice.waiting) return@launch
            updateConversation(
                consoleId,
                state.copy(
                    messages = state.messages.incrementalSnapshot().replacing(
                        index,
                        card.copy(waitingControl = notice.waiting),
                    ),
                ),
            )
        }
    }

    /**
     * 排队单排空/重启恢复开跑的补位（等价于 DispatchCreated 的 UI 侧处理，只是没有
     * 活跃的调度台 run 可反查 consoleId）：绑卡标题/runId（顺带清「排队中」快照）+
     * 物化子会话 + 挂接子 run 订阅。找不到调度台/卡片时其余处理照常，只有绑卡跳过。
     */
    private fun handleDispatchStartedNotice(
        notice: io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.StartedNotice,
    ) {
        // 恰一次：replay 兜底 Activity 重建与恢复路径的早发布。
        if (!handledDispatchNotices.add("${notice.dispatchId}:STARTED")) return
        scope.launch(Dispatchers.Main.immediate) {
            markDispatchManaged(notice.conversationId)
            dispatchConsoleId()?.let { consoleId ->
                bindDispatchCardTitleForConsole(consoleId, notice.dispatchId, notice.conversationTitle, notice.runId)
            }
            materializeDispatchConversation(notice.conversationId, notice.conversationTitle) {
                attachDispatchedRun(notice.conversationId, notice.runId, notice.brief)
                refreshRuntimeResults()
            }
        }
    }

    /**
     * 子任务回报落到调度台的同一张 `task_dispatch` 卡片：一个 dispatchId 一张卡，
     * 只更新状态/正文/身份，不追加新消息，因此多个子任务先后完成也不会互相覆盖。
     * 找不到卡片（旧数据、卡片被清理、调度台未加载）时才补建一张，保证汇报不丢。
     */
    private fun updateDispatchReportCard(
        notice: io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.Notice,
    ) {
        val consoleId = dispatchConsoleId() ?: return
        val state = conversationState(consoleId) ?: return
        if (!state.conversationContentLoaded) return
        val status = when (notice.outcome) {
            io.github.mangi.eta.agent.dispatch.AgentDispatchLedger.Outcome.COMPLETED ->
                ToolActivityStatusUi.Success
            io.github.mangi.eta.agent.dispatch.AgentDispatchLedger.Outcome.FAILED,
            io.github.mangi.eta.agent.dispatch.AgentDispatchLedger.Outcome.STOPPED ->
                ToolActivityStatusUi.Failed
        }
        val report = notice.summary.ifBlank { notice.detail }.trim()
        val existingIndex = dispatchCardIndex(state.messages, notice.dispatchId)
        val existingRunId = if (existingIndex >= 0) {
            io.github.mangi.eta.agent.dispatch.AgentDispatchReport
                .parse((state.messages[existingIndex] as ToolActivityMessageUi).resultDetail)
                ?.runId.orEmpty()
        } else {
            ""
        }
        val meta = io.github.mangi.eta.agent.dispatch.AgentDispatchReport.Meta(
            dispatchId = notice.dispatchId,
            conversationId = notice.conversationId,
            runId = existingRunId,
        )
        val messages = if (existingIndex >= 0) {
            val card = state.messages[existingIndex] as ToolActivityMessageUi
            state.messages.incrementalSnapshot().replacing(
                existingIndex,
                card.copy(
                    status = status,
                    argumentsSummary = notice.title.ifBlank { card.argumentsSummary },
                    resultSummary = report.takeIf { it.isNotBlank() } ?: card.resultSummary,
                    resultDetail = io.github.mangi.eta.agent.dispatch.AgentDispatchReport.encode(meta),
                    detailsWithheld = false,
                    // 终态覆盖一切在途等待：提问中/操作权排队中被打断的 run 由这里收尾。
                    waitingQuestion = null,
                    waitingControl = false,
                ),
            )
        } else {
            state.messages + ToolActivityMessageUi(
                id = "dispatch-report-${notice.dispatchId}",
                toolName = io.github.mangi.eta.agent.dispatch.AgentDispatchReport.TOOL_NAME,
                status = status,
                argumentsSummary = notice.title.ifBlank { "子任务" },
                resultSummary = report.takeIf { it.isNotBlank() },
                resultDetail = io.github.mangi.eta.agent.dispatch.AgentDispatchReport.encode(meta),
            )
        }
        updateConversation(consoleId, state.copy(messages = messages))
        persistConversations()
    }

    /** 定位某个 dispatchId 对应的 `task_dispatch` 卡片；没有则 -1。 */
    private fun dispatchCardIndex(messages: List<AgentChatMessageUi>, dispatchId: String): Int =
        messages.indexOfLast { message ->
            message is ToolActivityMessageUi &&
                message.toolName == io.github.mangi.eta.agent.dispatch.AgentDispatchReport.TOOL_NAME &&
                io.github.mangi.eta.agent.dispatch.AgentDispatchReport
                    .parse(message.resultDetail)?.dispatchId == dispatchId
        }

    /**
     * DispatchCreated 落定后把子会话标题写进那张卡（卡片在 ToolFinished 时才建好，
     * 两个事件顺序不固定，先到的一方只暂存标题）。同时补齐 runId 供后续跳转/排查。
     */
    private fun bindDispatchCardTitle(
        consoleRunId: String,
        dispatchId: String,
        title: String,
        subRunId: String,
    ) {
        val consoleId = conversationIdForRun(consoleRunId) ?: return
        bindDispatchCardTitleForConsole(consoleId, dispatchId, title, subRunId)
    }

    /**
     * 通知路径用：调度台会话 id 直接给定——StartedNotice（排空/恢复路径）发出时
     * 没有活跃的调度台 run，无法按 runId 反查 consoleId。重编码 meta 时新建 Meta
     * 默认 queued=false，顺带清掉卡片上的「排队中」快照。
     */
    private fun bindDispatchCardTitleForConsole(
        consoleId: String,
        dispatchId: String,
        title: String,
        subRunId: String,
    ) {
        val state = conversationState(consoleId) ?: return
        if (!state.conversationContentLoaded) return
        val index = dispatchCardIndex(state.messages, dispatchId)
        if (index < 0) return
        val card = state.messages[index] as ToolActivityMessageUi
        val existing = io.github.mangi.eta.agent.dispatch.AgentDispatchReport.parse(card.resultDetail)
        val meta = io.github.mangi.eta.agent.dispatch.AgentDispatchReport.Meta(
            dispatchId = dispatchId,
            conversationId = existing?.conversationId.orEmpty(),
            runId = subRunId,
        )
        updateConversation(
            consoleId,
            state.copy(
                messages = state.messages.incrementalSnapshot().replacing(
                    index,
                    card.copy(
                        argumentsSummary = title.ifBlank { card.argumentsSummary },
                        resultDetail = io.github.mangi.eta.agent.dispatch.AgentDispatchReport.encode(meta),
                    ),
                ),
            ),
        )
        persistConversations()
    }

    /**
     * 进程重建/错过通知时，用 outbox 里的终态补一次卡片（live 通知可能已经随进程消失）。
     * 只按子会话 id 找回既有卡片；找不到就不硬造，避免与 live 通知产生重复卡片。
     */
    private fun updateDispatchReportCardFromRecovery(
        conversationId: String,
        ok: Boolean,
        content: String,
        error: String?,
    ): Boolean {
        val consoleId = dispatchConsoleId() ?: return false
        val state = conversationState(consoleId) ?: return false
        if (!state.conversationContentLoaded) return false
        val index = state.messages.indexOfLast { message ->
            message is ToolActivityMessageUi &&
                message.toolName == io.github.mangi.eta.agent.dispatch.AgentDispatchReport.TOOL_NAME &&
                io.github.mangi.eta.agent.dispatch.AgentDispatchReport
                    .parse(message.resultDetail)?.conversationId == conversationId
        }
        if (index < 0) return false
        val card = state.messages[index] as ToolActivityMessageUi
        if (card.status != ToolActivityStatusUi.Running) return false
        if (io.github.mangi.eta.agent.dispatch.AgentDispatchReport.parse(card.resultDetail) == null) {
            return false
        }
        val report = content.trim().ifBlank { error.orEmpty().trim() }
        updateConversation(
            consoleId,
            state.copy(
                messages = state.messages.incrementalSnapshot().replacing(
                    index,
                    card.copy(
                        status = if (ok) ToolActivityStatusUi.Success else ToolActivityStatusUi.Failed,
                        resultSummary = report.takeIf { it.isNotBlank() } ?: card.resultSummary,
                        detailsWithheld = false,
                        // outbox 回补也是终态路径：同样清掉在途等待快照。
                        waitingQuestion = null,
                        waitingControl = false,
                    ),
                ),
            ),
        )
        return true
    }

    private fun markDispatchManaged(conversationId: String) {
        if (conversationId.isBlank() || conversationId in conversationDispatchMarks) return
        conversationDispatchMarks = conversationDispatchMarks + conversationId
        refreshConversationSummaries()
    }

    /** 派生会话不在内存时从 Room 物化（通知与 DispatchCreated 事件共用）。 */
    private fun materializeDispatchConversation(
        conversationId: String,
        title: String,
        onReady: (() -> Unit)? = null,
    ) {
        if (conversationId.isBlank()) return
        if (conversationsById.containsKey(conversationId)) {
            onReady?.invoke()
            return
        }
        scope.launch(Dispatchers.Main.immediate) {
            val state = runCatching {
                withContext(Dispatchers.IO) {
                    AgentConversationStore.loadConversation(appContext, conversationId)
                }
            }.getOrNull() ?: return@launch
            if (conversationsById.containsKey(conversationId)) {
                onReady?.invoke()
                return@launch
            }
            conversationsById = conversationsById + (conversationId to state)
            if (title.isNotBlank() && conversationTitles[conversationId].isNullOrBlank()) {
                conversationTitles = conversationTitles + (conversationId to title)
            }
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
            refreshConversationSummaries()
            onReady?.invoke()
        }
    }

    /**
     * 结算回报注入调度台：在跑→steer 插话；空闲→发起一轮（现有 run 模型下「主智能体不停止」
     * 靠完成即触发新一轮实现）。
     *
     * 暂停 / 用户显式停止期间**绝不自动起轮**，否则「停止」形同虚设：只把终态写进
     * `task_dispatch` 卡、追加一条「派发回报」回执卡，并把文本积压到
     * [pendingConsoleReceipts]，等用户恢复（[continuePausedGeneration] → steer）或下次
     * 主动发言（[drainPendingConsoleReceipts] → 并入那一轮 history）时再让模型读到。
     */
    private fun deliverDispatchNotice(
        notice: io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.Notice,
    ) {
        val consoleId = dispatchConsoleId() ?: return
        val state = conversationState(consoleId) ?: return
        if (!state.conversationContentLoaded) return
        val text = notice.toConsoleText()
        // 用户停止（含 run 已自然结束、随后又按了停止的窗口）或暂停：只落卡片与回执。
        if (consoleDispatchAutoRunBlocked || state.isPaused) {
            appendConsoleReceipt(consoleId, notice.dispatchId, text)
            enqueuePendingConsoleReceipt(text)
            return
        }
        val activeRunId = state.activeRunId
        when {
            activeRunId != null && state.isStreaming -> {
                // 不在这里手写回执卡：steer 成功后 runtime 会发 supplement 事件，
                // UI 侧按前缀把它渲染成「派发回报」卡，手写会显示成两张。
                runCatching {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).steerRun(activeRunId, text)
                }
            }
            activeRunId == null && !state.isStreaming ->
                dispatchNoticeAsConsoleTurn(consoleId, state, text)
            else -> {
                // 停止过渡窗口（已请求停止、尚未拿到终态）：同样不拉起新一轮。
                appendConsoleReceipt(consoleId, notice.dispatchId, text)
                enqueuePendingConsoleReceipt(text)
            }
        }
    }

    /** 暂停/停止期间积压一条回报文本；上限与 notifier 的 replay 深度同量级，防无限增长。 */
    private fun enqueuePendingConsoleReceipt(text: String) {
        if (pendingConsoleReceipts.size >= 32) pendingConsoleReceipts.removeAt(0)
        pendingConsoleReceipts.add(text)
    }

    /**
     * 「派发回报」回执卡：身份仍是 [UserMessageUi]（模型输入、历史对齐与编辑/分支的轮边界
     * 都依赖 user 轮），展示层按 `CONSOLE_TEXT_PREFIX` 识别后画成工具卡（DispatchReceiptCard），
     * 不套用户气泡、不给操作区。id 用 dispatchId 派生，replay / 重启不会重复追加。
     */
    private fun appendConsoleReceipt(
        consoleId: String,
        dispatchId: String,
        text: String,
    ) {
        val state = conversationState(consoleId) ?: return
        if (!state.conversationContentLoaded) return
        val id = "dispatch-receipt-$dispatchId"
        if (state.messages.any { it.id == id }) return
        updateConversation(
            consoleId,
            state.copy(messages = state.messages + UserMessageUi(id = id, content = text)),
        )
        persistConversations()
    }

    /**
     * 用户重新在调度台发言：把积压回报并入这一轮的 history（因此会持久化在历史里），
     * 并解除停止后的自动起轮封锁。非调度台会话返回空。
     */
    private fun drainPendingConsoleReceipts(
        conversationId: String?,
    ): List<AgentModelClient.ConversationMessage> {
        if (conversationId == null || !isDispatchConsoleConversation(conversationId)) return emptyList()
        consoleDispatchAutoRunBlocked = false
        if (pendingConsoleReceipts.isEmpty()) return emptyList()
        val receipts = pendingConsoleReceipts.toList()
        pendingConsoleReceipts.clear()
        return receipts.map { AgentModelClient.buildUserHistoryMessage(it, emptyList()) }
    }

    /**
     * 用户恢复（resume）时把积压回报 steer 进当前 run：恢复是显式动作，不算「偷偷拉起」。
     *
     * 暂停期间已经手动落过硬回执卡；steer 成功后 runtime 的 supplement 事件会再落一张同文卡片，
     * 所以这里先把手动卡摘掉，由 supplement 那张接管，避免同一段汇报显示两遍。
     * 返回合并文本；非调度台或没有积压时为 null。
     */
    private fun takePendingConsoleReceiptsForSteer(conversationId: String?): String? {
        if (conversationId == null || !isDispatchConsoleConversation(conversationId)) return null
        consoleDispatchAutoRunBlocked = false
        if (pendingConsoleReceipts.isEmpty()) return null
        val pendingText = pendingConsoleReceipts.joinToString("\n\n")
        val pendingSet = pendingConsoleReceipts.toSet()
        pendingConsoleReceipts.clear()
        val state = conversationState(conversationId)
        if (state != null) {
            val staleIds = state.messages.filterIsInstance<UserMessageUi>()
                .filter { it.id.startsWith("dispatch-receipt-") && it.content in pendingSet }
                .mapTo(mutableSetOf()) { it.id }
            if (staleIds.isNotEmpty()) {
                updateConversation(
                    conversationId,
                    state.copy(messages = state.messages.filterNot { it.id in staleIds }),
                )
                persistConversations()
            }
        }
        return pendingText
    }

    /**
     * 调度台被显式停止：回报只更新卡片，不再自动起轮，直到用户再次发言或恢复。
     * 只对调度台生效，普通会话「主智能体停止后也不该被回报拉起」没有对应场景。
     */
    private fun blockConsoleDispatchAutoRun() {
        if (selectedConversationId?.let(::isDispatchConsoleConversation) == true) {
            consoleDispatchAutoRunBlocked = true
        }
    }

    /**
     * 回执以 [UserMessageUi] 承载一轮：模型输入、历史对齐（AgentConversationRevisionReducer）
     * 与编辑/分支语义都依赖 user 轮，换新类型会破坏边界判定。展示层按
     * [io.github.mangi.eta.agent.dispatch.AgentDispatchNotifier.CONSOLE_TEXT_PREFIX] 识别后
     * 画成「派发回报」工具卡（不套用户气泡），因此用户能看见这条回执，但不会误以为是自己的发言。
     */
    private fun dispatchNoticeAsConsoleTurn(
        consoleId: String,
        state: AgentChatHomeUiState,
        text: String,
    ) {
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(id = "user-$runId", content = text)
        val messages = state.messages + userMessage
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(text, emptyList())
        launchConversationRun(
            conversationId = consoleId,
            runId = runId,
            prompt = text,
            images = emptyList(),
            // 暂停期积压的回报可能还没被读过（暂停结束、用户既没恢复也没发言就又有一单结算）：
            // 既然是合法的自动起轮，就一并并入 history，避免模型漏读上一段汇报。
            history = state.history + drainPendingConsoleReceipts(consoleId),
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = state,
            reasoningEffort = state.reasoningEffort,
        )
    }

    // UI and Runtime use explicit owners. A selected UI owner is never a Runtime lookup key.
    val conversationSubAgentPreferences = ConversationSubAgentPreferences(canEdit = ::canEditSubAgentSettings)
    private val pendingSubAgentDraftBindings = mutableMapOf<String, SubAgentConfigKey.Draft>()
    private var subAgentDraftReady by mutableStateOf(true)
    private var subAgentDraftPointerReloadPending = false
    private var subAgentRestoreBlocked by mutableStateOf(false)
    private var subAgentConfigFailure by mutableStateOf<String?>(null)
    private var pendingSubAgentDraftSource: SubAgentConfigKey? = selectedConversationId?.let { SubAgentConfigKey.Conversation(it) }
    private var draftSubAgentOwner by mutableStateOf(initializeSubAgentDraft())

    private fun readSubAgentDraftPointer(source: SubAgentConfigKey?): SubAgentConfigKey.Draft {
        val pointer = Prefs.getString("agent_conversation_child_ui_draft_v1")
        if (pointer.isNotBlank()) {
            val existing = SubAgentConfigKey.Draft(pointer)
            if (conversationSubAgentPreferences.existingDraftOrNull(existing) != null) return existing
            // A promoted draft may have been retired, but its selected conversation is authoritative.
            check(source is SubAgentConfigKey.Conversation) { "原草稿配置缺失，不能替换为默认配置" }
        }
        val created = conversationSubAgentPreferences.createDraft(source)
        Prefs.putString("agent_conversation_child_ui_draft_v1", created.value)
        return created
    }

    private fun initializeSubAgentDraft(): SubAgentConfigKey.Draft = try {
        readSubAgentDraftPointer(pendingSubAgentDraftSource)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        subAgentDraftReady = false
        subAgentDraftPointerReloadPending = true
        subAgentConfigFailure = appContext.getString(R.string.appst_subagent_draft_read_failed)
        SubAgentConfigKey.Draft(java.util.UUID.randomUUID().toString())
    }

    val subAgentConfigOwner: SubAgentConfigKey
        get() = selectedConversationId?.let { SubAgentConfigKey.Conversation(it) } ?: draftSubAgentOwner

    fun canEditSubAgentSettings(owner: SubAgentConfigKey): Boolean {
        if (owner != subAgentConfigOwner || conversationArchiveBusy || subAgentRestoreBlocked) return false
        if (owner is SubAgentConfigKey.Draft && !subAgentDraftReady) return false
        // Paused/stopping parents still own their run. Children and other conversations do not lock this owner.
        if (homeState.isStreaming || homeState.isPaused) return false
        val id = selectedConversationId
        return runConversationIds.none { (runId, conversation) ->
            conversation == id && (runJobs[runId]?.isActive == true || stoppingRuns.containsKey(runId))
        }
    }

    fun subAgentEditor(owner: SubAgentConfigKey): ConversationSubAgentEditor =
        ConversationSubAgentEditor(owner, conversationSubAgentPreferences) { canEditSubAgentSettings(owner) }.also { editor ->
            editor.bindLifecycleState(
                reason = {
                    if (subAgentRestoreBlocked || (owner is SubAgentConfigKey.Draft && !subAgentDraftReady))
                        subAgentConfigFailure ?: appContext.getString(R.string.appst_subagent_config_unrestored)
                    else null
                },
                recovery = { owner == subAgentConfigOwner && ensureSubAgentConfigurationReady() },
            )
        }

    private fun subAgentConfigFailed(failure: Exception) {
        if (failure is CancellationException) throw failure
        subAgentConfigFailure = appContext.getString(R.string.appst_subagent_config_read_failed)
        AndroidAgentLogger.warn("Sub-agent configuration unavailable: type=${failure.safeLogType()}")
        Toast.makeText(appContext, subAgentConfigFailure, Toast.LENGTH_LONG).show()
    }

    private fun beginNewSubAgentDraft(
        source: SubAgentConfigKey? = subAgentConfigOwner,
        recoveringPending: Boolean = false,
    ): Boolean {
        // Never clone an unresolved placeholder or discard its original-pointer recovery intent.
        if (recoveringPending && source == null) return false
        if (source is SubAgentConfigKey.Draft && !subAgentDraftReady &&
            (!recoveringPending || subAgentDraftPointerReloadPending)) {
            Toast.makeText(appContext, appContext.getString(R.string.appst_subagent_retry_restore), Toast.LENGTH_LONG).show()
            return false
        }
        return try {
            if (source is SubAgentConfigKey.Draft) {
                check(conversationSubAgentPreferences.existingDraftOrNull(source) != null) {
                    "原草稿配置不可读取，不能替换为默认配置"
                }
            }
            val next = conversationSubAgentPreferences.createDraft(source)
            Prefs.putString("agent_conversation_child_ui_draft_v1", next.value)
            draftSubAgentOwner = next
            subAgentDraftReady = true
            subAgentDraftPointerReloadPending = false
            pendingSubAgentDraftSource = null
            subAgentConfigFailure = null
            true
        } catch (failure: Exception) {
            pendingSubAgentDraftSource = source
            subAgentDraftReady = false
            subAgentConfigFailed(failure)
            false
        }
    }

    private fun ensureSubAgentConfigurationReady(): Boolean = try {
        if (subAgentRestoreBlocked) {
            conversationSubAgentPreferences.refreshAfterRestore()
            val next = readSubAgentDraftPointer(selectedConversationId?.let { SubAgentConfigKey.Conversation(it) })
            draftSubAgentOwner = next
            subAgentDraftReady = true
            subAgentRestoreBlocked = false
            Prefs.putString("agent_conversation_child_ui_draft_v1", next.value)
        }
        if (selectedConversationId == null && !subAgentDraftReady && subAgentDraftPointerReloadPending) {
            val recovered = readSubAgentDraftPointer(pendingSubAgentDraftSource)
            draftSubAgentOwner = recovered
            subAgentDraftPointerReloadPending = false
            subAgentDraftReady = true
            subAgentConfigFailure = null
        }
        // Only recovery of the same unselected draft resumes its saved source.
        if (selectedConversationId == null && !subAgentDraftReady && !beginNewSubAgentDraft(pendingSubAgentDraftSource, recoveringPending = true)) false
        else {
            conversationSubAgentPreferences.snapshot(subAgentConfigOwner)
            true
        }
    } catch (failure: Exception) { subAgentConfigFailed(failure); false }

    private fun bindSubAgentDraft(conversationId: String): Boolean = try {
        val draft = draftSubAgentOwner
        conversationSubAgentPreferences.bindDraft(draft, SubAgentConfigKey.Conversation(conversationId))
        pendingSubAgentDraftBindings[conversationId] = draft
        true
    } catch (failure: Exception) { subAgentConfigFailed(failure); false }

    data class StopRequest(
        val selection: AgentStopSelection<AgentChildTaskGroups.StopTarget>,
        val mainRunning: Boolean,
        val childrenRunning: Boolean,
    )

    fun captureStopRequest(): StopRequest? {
        val owner = selectedConversationId ?: return null
        val runId = activeRunIdForSelectedConversation()
        val children = AgentChildTaskGroups.captureStopTarget(owner)
        if (runId == null && children == null) return null
        return StopRequest(AgentStopSelection(owner, runId, children), runId != null, AgentChildTaskGroups.hasActive(owner))
    }

    private fun stopSelectionMatches(target: StopRequest, checkChildren: Boolean): Boolean {
        val saved = target.selection
        if (saved.ownerId != selectedConversationId || saved.runId != activeRunIdForSelectedConversation()) return false
        return !checkChildren || saved.groupTarget == AgentChildTaskGroups.captureStopTarget(saved.ownerId)
    }

    fun stopMainReply(target: StopRequest): Boolean {
        // 用户显式按了停止：即使此刻 run 刚好自然结束（没有可停的 runId），也要封锁
        // 调度台的「回报自动起轮」，否则下一个子任务完成就会把它偷偷拉起来。
        blockConsoleDispatchAutoRun()
        if (!stopSelectionMatches(target, checkChildren = false)) return false
        val runId = target.selection.runId ?: return false
        contextBudgetBlockedRuns.remove(runId)
        if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
        stopRun(runId, keepChildren = true)
        return true
    }

    fun stopEntireTask(target: StopRequest): Boolean {
        blockConsoleDispatchAutoRun()
        if (!stopSelectionMatches(target, checkChildren = true)) return false
        val selection = target.selection
        selection.groupTarget?.let { if (!AgentChildTaskGroups.stop(it)) return false }
        // Stop the captured group above, then only the captured parent. A late control message
        // must not cancel a new generation of children created after this confirmation.
        selection.runId?.let { runId ->
            contextBudgetBlockedRuns.remove(runId)
            if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
            stopRun(runId, keepChildren = true)
        }
        return true
    }

    fun canPauseStopRequest(target: StopRequest): Boolean =
        stopSelectionMatches(target, checkChildren = false) && target.selection.runId != null &&
            target.selection.runId !in imageGenerationRunIds && !homeState.isPaused

    fun pauseStopRequest(target: StopRequest): Boolean {
        if (!canPauseStopRequest(target)) return false
        pauseCurrentRun()
        return true
    }

    private var selectedFolderId: String? = null
    private var pendingNewConversationFolderId: String? = null

    /**
     * 侧栏列表过滤器：选中的远程服务器胶囊；null = 显示本机列表。
     * 与 [selectedTarget]（聊天视图数据源）解耦——胶囊和文件夹一样只过滤列表，
     * 不决定当前聊天渲染谁。与 [selectedFolderId] 互斥：选远程胶囊即退出本机作用域。
     */
    var selectedRemoteServerId: String? by mutableStateOf(null)
        private set

    var homeState by mutableStateOf(
        selectedConversationId?.let(::conversationState) ?: emptyChatState(false)
    )
        private set

    private val autoCompressPreference = AutoCompressPreference()
    var autoCompressEnabled by mutableStateOf(autoCompressPreference.enabled)
        private set

    var requestOverheadTokens by mutableStateOf(0)
        private set

    /** 有效回执判定：未在等待新回执 + 实际路由签名仍然一致。
     * 学习签名已随显示学习一起退场：custom gateway/headers 取不到 learning 签名，但
     * actual-local-v1 实际路由摘要能验证“同一 provider/模型/配置”，因此同样算已测。
     * 未测豁免、压缩锚点与预算 seed 必须共用这一个判定（否则 UI 认为已测而 runtime 没有 seed）。 */
    private fun hasEffectiveContextReceipt(state: AgentChatHomeUiState): Boolean =
        !state.contextAwaitingReceipt && state.cloudRouteSignature != null &&
            state.cloudRouteSignature == contextRouteSignature(state)

    /** 有效实测 helper：正数真实回执 + 有效实际路由/历史证据。 */
    private fun validMeasuredContextTokens(state: AgentChatHomeUiState): Int? =
        billedPromptTokens(state)?.takeIf { tokens -> tokens > 0 && hasEffectiveContextReceipt(state) }

    /** Runtime 未实测豁免接线：只有“摘要真正提交过”（history 已被摘要替换）或本次请求确实
     * 没有有效实测时才允许越界发送；known 仍由既有校准锚点/本地硬限约束。
     * prune-only 或摘要失败都不算已压缩，也不能靠“看起来清了历史”来解锁。 */
    private fun unmeasuredContextSendAllowed(
        contextState: AgentChatHomeUiState,
        summaryCommitted: Boolean,
    ): Boolean = summaryCommitted || validMeasuredContextTokens(contextState) == null

    /** 严格路由签名：customHeaders/customBody/sessionGatewayJson 一律 fail closed，
     * 凭据内容从不参与任何持久化比较。 */
    private fun strictRouteSignature(
        provider: io.github.mangi.eta.data.model.ProviderSetting,
        model: io.github.mangi.eta.data.model.Model,
    ): String? = RequestOverheadCalibration.routeSignature(provider, model).takeIf { it.isNotBlank() }

    /** Actual receipts need a verifiable local scope even when custom routing forbids learning.
     * Only a digest is checkpointed; custom configuration is never a calibration scope.
     * Uncached: computed from one captured provider snapshot. */
    private fun contextRouteSignature(
        providers: List<io.github.mangi.eta.data.model.ProviderSetting>,
        providerId: String,
        modelId: String,
    ): String? {
        val provider = providers.firstOrNull { it.id == providerId && it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled } ?: return null
        strictRouteSignature(provider, model)?.let { return it }
        val endpoint = when (provider) {
            is io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting -> provider.endpointMode
            is io.github.mangi.eta.data.model.CustomProviderSetting -> provider.endpointMode
            is io.github.mangi.eta.data.model.AnthropicProviderSetting -> provider.anthropicVersion
        }
        val fields = org.json.JSONArray().put(provider.id).put(provider.baseUrl).put(provider.sourceType)
            .put(endpoint).put(provider.systemPrompt).put(provider.authMode).put(provider.apiKey)
            .put(provider.responsesStripReasoningStatus).put(provider.hostedWebSearchEnabled)
            .put(provider.sessionGatewayJson)
            .put(org.json.JSONArray(provider.customHeaders.map { listOf(it.name, it.value) }))
            .put(org.json.JSONArray(provider.customBody.map { listOf(it.key, it.value.toString()) }))
            .put(kotlinx.serialization.json.Json.encodeToString(io.github.mangi.eta.data.model.Model.serializer(),
                model.copy(createdAt = 0, displayName = "", sortOrder = 0)))
        return "actual-local-v1:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(fields.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Memoized [contextRouteSignature] for the current snapshot and binding. */
    private fun contextRouteSignature(state: AgentChatHomeUiState): String? {
        // Streaming deltas re-check the route on every publish; the inputs are only the
        // provider snapshot (replaced, never mutated) and the two binding ids.
        val providers = selectionProviders
        contextRouteSignatureEntry?.let { cached ->
            if (cached.providers === providers && cached.providerId == state.providerId &&
                cached.modelId == state.modelId) return cached.signature
        }
        val signature = contextRouteSignature(providers, state.providerId, state.modelId)
        contextRouteSignatureEntry = ContextRouteSignatureEntry(providers, state.providerId, state.modelId, signature)
        return signature
    }

    /** UI 侧同一个“有效实测”：InputBar 只有在它为 null（真未知）时才因为本地估算解除发送禁用，
     * 已有实测的 known 状态保持原有 99% 拦截与 autoCompress 短路。 */
    val measuredContextTokens: Int?
        get() = if (homeState.messageEdit == null) validMeasuredContextTokens(homeState) else null

    var billedOverheadTokens by mutableStateOf<Int?>(null)
        private set

    private var billedOverheadConversationId: String? = null

    var modelPickerState by mutableStateOf(AgentModelPickerUiState())
        private set

    /**
     * 生成中改选的「待生效」绑定，按会话隔离。运行配置在发起时已冻结成本轮专属快照，
     * 所以这里只登记选择、不动会话绑定：本轮继续用原模型/助手，终态提交后下一轮生效。
     * key 是选中会话 id，首页未落库的草稿用空串。
     */
    private val pendingSelectionByKey = mutableMapOf<String, PendingSelection>()

    private data class PendingSelection(
        val providerId: String = "",
        val modelId: String = "",
        val assistantId: String? = null,
    )

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    var pendingScrollToMessageId by mutableStateOf<String?>(null)
        private set

    /**
     * 外部入口把**在跑**的 run 交接过来时的目标会话：主页下一次进入要落在这里
     * （一次性，由 `AgentAppRoot` 取走并转成 `homeEntryPinnedConversationId`）。
     *
     * 只有值，不在这里做导航：路由由界面层决定，状态层只负责说「这一单该被看见」。
     */
    var liveExternalConversationRequest by mutableStateOf<String?>(null)
        private set

    var toolsState by mutableStateOf(buildToolsState(appContext))
        private set

    /** 最近一次工具页语言标签：重建快照时用它从 appContext 派生带显式语言的配置上下文，
     *  只存字符串不存 Activity，避免单例持有 Activity 导致泄漏。 */
    private var toolsLocales: String? = null

    /**
     * 工具页内联开关改动后重建卡片状态（分组开关状态取自 Prefs）。
     * 必须传 Activity 级上下文取语言：applicationContext 在部分 OEM 上不吃 per-app
     * 语言覆盖，用它预解析的字符串快照会停回系统语言，工具列表与设置页语言不一致。
     * 上下文本身不保存（单例防泄漏），重建时按语言标签从 appContext 派生配置上下文。
     */
    fun refreshToolsState(context: Context) {
        toolsLocales = context.resources.configuration.locales.toLanguageTags()
        rebuildToolsState()
    }

    /** 偏好监听等无 Activity 上下文的路径：沿用最近一次工具页语言重建快照。 */
    fun refreshToolsState() {
        rebuildToolsState()
    }

    private fun rebuildToolsState() {
        toolsState = buildToolsState(localizedToolsContext())
    }

    private fun localizedToolsContext(): Context {
        val tags = toolsLocales ?: return appContext
        val configuration = android.content.res.Configuration(appContext.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(tags))
        }
        return appContext.createConfigurationContext(configuration)
    }

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    var permissionHealthState by mutableStateOf(PermissionHealthUiState(emptyList()))
        private set

    var memoryState by mutableStateOf(AgentMemoryUiState())
        private set

    init {
        refreshConversationSummaries()
        observeConversationSummaryDates()
        observeRuntimeSelection()
        observeRuntimeArchiveCompletions()
        observeLiveRunHandoffs()
        observeAutoCompressEnabled()
        scope.launch(Dispatchers.Main.immediate) {
            conversationSubAgentPreferences.revision.collectLatest { refreshRequestOverhead() }
        }
        observeOwnerContexts()
        refreshRequestOverhead()
        ProviderBalanceStore.start(scope)
        scope.launch {
            RootAccess.state.collectLatest {
                refreshPermissionHealth()
                refreshRequestOverhead()
            }
        }
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                // This runs at construction, when conversationsById is still the
                // intentionally empty seed. Wait for hydration so recovery sees the real
                // map instead of concluding that nothing is owned.
                awaitConversationsHydrated()
                // Sweep only when the persisted map actually arrived. After a failed
                // hydration the map is still the empty seed, where every real conversation
                // looks like an orphan and the sweep would delete the whole cache.
                if (conversationsHydrated) {
                    chatImageCache.deleteOrphans(conversationsById.keys)
                }
                // 与 [refreshRuntimeResults] 同一口径：归档（完整事件流）先于 outbox（只有
                // 最终 result）导入，否则语音浮窗等外部入口的思考 / 工具卡会被折叠结果顶掉。
                importArchivedExternalRuns()
                recoverRuntimeRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
                // 构造期恢复进行中落库的终态：同 [pumpRuntimeRecovery] 的关闸补泵口径。
                if (runtimeRefreshPending.get()) pumpRuntimeRecovery()
            }
        }
    }

    /**
     * Suspend until startup hydration has published the persisted conversation map.
     *
     * Construction-time background work reads [conversationsById] and would otherwise act
     * on the empty seed: cache sweeps treat every conversation as an orphan and recovery
     * lookups miss. Never blocks the main thread — [hydrateConversations] publishes from
     * the application scope.
     */
    private suspend fun awaitConversationsHydrated() {
        conversationsHydratedSignal.await()
    }

    private fun ownerContext(ownerId: String): AgentOwnerContextState =
        ownerContexts.getOrPut(ownerId) { AgentOwnerContextState(ownerId) }

    private fun observeOwnerContexts() {
        scope.launch(Dispatchers.Main.immediate) {
            AgentChildTaskGroups.revision.collect {
                val owners = ownerContexts.keys.toSet() + listOfNotNull(selectedConversationId)
                owners.filter { it in conversationsById }.forEach { refreshOwnerContext(it) }
            }
        }
    }

    private fun requestOwnerContext(ownerId: String) {
        scope.launch(Dispatchers.Main.immediate) { refreshOwnerContext(ownerId) }
    }

    private suspend fun refreshOwnerContext(ownerId: String) {
        if (ownerId !in conversationsById) return
        // The registry and UI share the application process. Reads have no control/handoff effects.
        val snapshot = withContext(Dispatchers.IO) {
            var result: Pair<Long, List<io.github.mangi.eta.agent.delegation.SubAgentContextStats>>? = null
            for (attempt in 0 until 3) {
                val before = AgentChildTaskGroups.revision.value
                val contexts = AgentChildTaskGroups.contextStats(ownerId)
                if (before == AgentChildTaskGroups.revision.value) {
                    result = before to contexts
                    break
                }
            }
            result
        } ?: return
        if (ownerId !in conversationsById) return
        val reducer = ownerContext(ownerId)
        val previous = reducer.projection().children.associateBy { it.taskId }
        reducer.refresh(ownerId, snapshot.second.map { stats ->
            AgentOwnerContextState.TaskSnapshot(
                stats, snapshot.first, stats.statusVersion,
                stats.statusChangedAtMs?.takeIf { it > 0L },
            )
        })
        if (ownerId == selectedConversationId && snapshot.second.any {
            it.manualCompactionState == "ended" && previous[it.taskId]?.manualCompactionState == "pending"
        }) Toast.makeText(appContext, appContext.getString(R.string.appst_compact_subtask_ended), Toast.LENGTH_LONG).show()
        publishOwnerContext(ownerId)
        scheduleOwnerContextHides(ownerId)
    }

    private fun publishOwnerContext(ownerId: String) {
        val view = ownerContexts[ownerId]?.projection() ?: return
        val current = conversationsById[ownerId]?.takeIf { it.conversationContentLoaded } ?: return
        updateConversation(ownerId, current.copy(childContexts = view.children,
            selectedContextTaskId = view.selectedTaskId), updateTimestamp = false)
    }

    private fun scheduleOwnerContextHides(ownerId: String) {
        val state = ownerContexts[ownerId] ?: return
        val wanted = state.pendingHides().toSet()
        contextHideJobs.keys.filter { it.ownerId == ownerId && it !in wanted }.toList().forEach {
            contextHideJobs.remove(it)?.cancel()
        }
        wanted.forEach { token ->
            if (token !in contextHideJobs) {
                val job = scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
                    try {
                        delay(state.remainingMs(token))
                        if (state.expire(token)) publishOwnerContext(ownerId)
                    } finally {
                        contextHideJobs.remove(token)
                    }
                }
                contextHideJobs[token] = job
                job.start()
            }
        }
    }

    private fun observeAutoCompressEnabled() {
        scope.launch(Dispatchers.Main.immediate) {
            val compression = autoCompressPreference.observe { autoCompressEnabled = it }
            val prefs = Prefs.localAgentPreferences()
            // 会影响常驻开销的开关都从界面能力定义派生（含外部闸门）：新增/改名一个
            // 开关时这里自动跟上，否则用户改了开关而界面上的估算不动。
            // 单工具禁用集也直接改变下发的 schema，一并监听。
            val overheadKeys = ToolCapabilities.all.mapTo(mutableSetOf()) { it.switchKey } +
                ToolCapabilities.externalGates.mapTo(mutableSetOf()) { it.switchKey } +
                Prefs.Keys.AGENT_DISABLED_TOOLS
            val overheadListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == null || key in overheadKeys) {
                    scope.launch(Dispatchers.Main.immediate) { refreshRequestOverhead() }
                    // 同一批键也是工具页唯一的状态来源。工具页可能还留在返回栈里
                    // （设置页改完再返回），这里重建一次，避免它拿着一份旧快照。
                    scope.launch(Dispatchers.Main.immediate) { refreshToolsState() }
                }
            }
            prefs?.registerOnSharedPreferenceChangeListener(overheadListener)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                prefs?.unregisterOnSharedPreferenceChangeListener(overheadListener)
                compression.close()
            }
        }
    }

    private fun requestOverheadAssistant(state: AgentChatHomeUiState = homeState) =
        AssistantRepository.profile(resolvedAssistantId(state)) ?: AssistantRepository.active()

    private fun currentOverheadBinding() = RequestOverheadSelection.Binding(
        owner = when (val owner = subAgentConfigOwner) {
            is SubAgentConfigKey.Conversation -> "conversation:${owner.value}"
            is SubAgentConfigKey.Draft -> "draft:${owner.value}"
            is SubAgentConfigKey.Preset -> error("预设不能作为会话运行配置")
        },
        providerId = homeState.providerId,
        modelId = homeState.modelId,
        assistantId = requestOverheadAssistant().id,
        modelGeneration = modelBindingGeneration,
        configurationGeneration = overheadConfigurationGeneration,
    )

    fun refreshRequestOverhead(configurationChanged: Boolean = true) {
        // Skills, MCP schemas and capability snapshots lack one common revision. Existing
        // refresh callers signal a possible change, so invalidate conservatively before IO.
        // Only an explicitly known-same-configuration retry may retain a successful estimate.
        if (configurationChanged) overheadConfigurationGeneration++
        val binding = currentOverheadBinding()
        val overheadRequest = overheadSelection.begin(binding)
        // A previous binding's positive value is not a fallback for an unknown new binding.
        requestOverheadTokens = overheadSelection.tokensFor(binding) ?: 0
        val state = homeState
        val owner = subAgentConfigOwner
        val assistant = requestOverheadAssistant()
        val providers = selectionProviders.toList()
        scope.launch(Dispatchers.IO) {
            val tokens = try {
                val provider = providers.firstOrNull { it.id == state.providerId && it.isEnabled }
                val model = provider?.models?.firstOrNull { it.id == state.modelId && it.isEnabled }
                if (provider == null || model == null) null else {
                    // Preview must not call configForProviderAndModel: that can refresh OAuth.
                    val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant).copy(
                        terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                        browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                        screenTools = agentBooleanForUi(Prefs.Keys.AGENT_SCREEN_TOOLS),
                        deviceTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_TOOLS),
                        personalTools = agentBooleanForUi(Prefs.Keys.AGENT_PERSONAL_TOOLS),
                    )
                    estimateRequestOverhead(config, assistant, owner, providers)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null // Unknown, not a successfully measured zero. Keep this binding's valid value.
            }
            withContext(Dispatchers.Main) {
                if (requestOverheadAssistant() != assistant ||
                    !overheadSelection.complete(overheadRequest, currentOverheadBinding(), tokens)) return@withContext
                requestOverheadTokens = requireNotNull(tokens)
                syncBilledOverhead(selectedConversationId, homeState.messages)
            }
        }
    }

    private suspend fun estimateRequestOverhead(
        config: AgentModelClient.ModelConfig,
        assistant: io.github.mangi.eta.data.model.AssistantProfile,
        owner: SubAgentConfigKey,
        providers: List<io.github.mangi.eta.data.model.ProviderSetting>,
    ): Int {
        val enabledSkillIds = assistant.enabledSkillIds.toSet()
        val skillContext = SkillContext(
            installedSkills = SkillRuntime.createIndexService(appContext)
                .listSkillsForManagement()
                .filter { it.installed && it.id in enabledSkillIds }
                .filter { SkillCompatibilityChecker.evaluate(it).available },
        )
        val memoryContext = if (AgentMemoryRepository.isEnabled(assistant.id)) {
            AgentMemoryContextBuilder.build(
                snapshot = AgentMemoryRepository.snapshot(assistant.id),
                contextWindow = config.contextWindow,
            )
        } else {
            AgentMemoryContext.DISABLED
        }
        val additionalTools = JSONArray()
        McpRunSnapshot.appendCachedModelTools(additionalTools, McpServerRepository.enabledServers())
        val childPrompt = io.github.mangi.eta.agent.delegation.SubAgentRequestPreview.appendTo(
            tools = additionalTools,
            owner = owner,
            config = conversationSubAgentPreferences.previewSnapshot(owner),
            providers = providers,
            workspaceEnabled = config.terminalTools,
        )
        return AgentRequestOverhead.estimate(
            config = config,
            skillContext = skillContext,
            memoryContext = memoryContext,
            capabilities = AgentToolCapabilities.capture(appContext),
            additionalTools = additionalTools,
        ) + AgentContextBudget.countTokens(childPrompt)
    }

    private fun syncBilledOverhead(
        conversationId: String?,
        messages: List<AgentChatMessageUi>,
    ) {
        val hasBilled = latestBilledContextTokens(messages) != null
        if (!hasBilled) {
            if (billedOverheadConversationId == conversationId) {
                billedOverheadTokens = null
            }
            return
        }
        if (billedOverheadConversationId != conversationId || billedOverheadTokens == null) {
            billedOverheadConversationId = conversationId
            billedOverheadTokens = overheadSelection.tokensFor(currentOverheadBinding())
        }
    }

    private fun updateSelectionProviders(providers: List<io.github.mangi.eta.data.model.ProviderSetting>) {
        if (selectionProviders != providers) modelBindingGeneration++
        selectionProviders = providers
        // Invalidate the old run permanently, including if settings are later changed back.
        runUsageRoutes.forEach { (runId, route) ->
            val state = conversationIdForRun(runId)?.let(::conversationState)
            if (state != null && route != contextRouteSignature(state)) invalidatedUsageRuns.add(runId)
        }
        // Project every loaded binding, including background conversations without a usage receipt.
        // Ineligible bindings display NORMAL without erasing their independent GPT memory.
        conversationsById.toList().forEach { (id, state) ->
            val next = state.withCurrentGptSpeedBinding()
            if (state.conversationContentLoaded && (next != state ||
                (state.cloudRouteSignature != null && state.cloudRouteSignature != contextRouteSignature(state)))) {
                updateConversation(id, next, updateTimestamp = false)
            }
        }
        if (selectedConversationId == null) homeState = homeState.withCurrentGptSpeedBinding()
    }

    private fun AgentChatHomeUiState.withCurrentGptSpeedBinding(): AgentChatHomeUiState = copy(
        gptSpeedMode = rememberedGptSpeedMode(providerId, modelId),
    )

    private fun rememberedGptSpeedMode(providerId: String, modelId: String): GptSpeedMode {
        val provider = selectionProviders.singleOrNull { it.id == providerId }?.takeIf { it.isEnabled }
        val model = provider?.models?.singleOrNull { it.id == modelId }?.takeIf { it.isEnabled }
        // Do not read/write memory for non-GPT or missing selections, including startup placeholders.
        if (!supportsGptSpeedBinding(provider, model)) return GptSpeedMode.NORMAL
        return mainAgentSpeedDefaults.modeFor(providerId, modelId)
    }

    private fun observeRuntimeSelection() {
        scope.launch {
            combine(SettingsDataStore.settingsFlow(), ProviderRepository.providersFlow()) { settings, providers ->
                Triple(settings.selectedProviderId, settings.selectedModelId, providers)
            }.distinctUntilChanged(::runtimeSelectionUnchanged)
                .collectLatest { (providerId, modelId, providers) ->
                    updateSelectionProviders(providers)
                    defaultProviderId = providerId
                    defaultModelId = modelId
                    refreshBoundModelPicker()
                }
        }
    }

    /** Global defaults are used only for an unbound draft; never write them over an existing binding. */
    private fun refreshBoundModelPicker() {
        if (conversationArchiveBusy) {
            bindingRefreshAfterArchive = true
            return
        }
        val initializingDraftBinding = selectedConversationId == null && homeState.modelId.isBlank() && homeState.providerId.isBlank()
        if (homeState.modelId.isBlank() && homeState.providerId.isBlank() && selectionProviders.isNotEmpty()) {
            updateCurrentConversation(homeState.copy(providerId = defaultProviderId.orEmpty(), modelId = defaultModelId.orEmpty()))
            if (selectedConversationId != null) persistConversations()
        }
        val provider = selectionProviders.singleOrNull { it.id == homeState.providerId }?.takeIf { it.isEnabled }
        val model = provider?.models?.singleOrNull { it.id == homeState.modelId }?.takeIf { it.isEnabled }
        val projected = AgentModelPickerProjector.project(selectionProviders, homeState.providerId, homeState.modelId)
        // The shared picker deduplicates API aliases for listing. An already bound selection must
        // still project ITS exact model ID, not lose its speed action to another entry's alias.
        val boundOption = if (provider != null && model != null) {
            AgentModelPickerProjector.project(listOf(provider.withModels(listOf(model))), provider.id, model.id).selectedModel
        } else null
        modelPickerState = projected.copy(selectedModel = boundOption?.takeIf {
            provider != null && model != null && it.providerId == provider.id && it.id == model.id
        }, isChanging = false)
        currentReasoningCapabilities = if (provider != null && model != null)
            RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant = null).reasoningCapabilities else null
        // Display effective choices, but retain the user's saved preference until they explicitly change it.
        val next = if (initializingDraftBinding && model != null) {
            homeState.withPreferredReasoningEffort()
        } else homeState.copy(availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty())
        val scopedNext = if (provider != null && model != null && next.cloudRouteSignature != null &&
            next.cloudRouteSignature != contextRouteSignature(next)) next.copy(
                livePromptTokens = null, livePromptIsProjected = false, cloudHistoryTokens = null,
                contextBudgetReceiptTokens = null,
                cloudRequestOverheadTokens = null, cloudRouteSignature = null, cloudReceiptRequestId = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null, contextAwaitingReceipt = true,
                contextHasStarted = true) else next
        // Restore by provider ID + model selection ID, never copy the previous model's tier.
        // Unavailable/non-GPT projections are NORMAL and never overwrite remembered GPT choices.
        val speedNext = scopedNext.withCurrentGptSpeedBinding()
        if (speedNext != homeState) {
            val conversationId = selectedConversationId
            if (conversationId == null) homeState = speedNext else updateConversation(conversationId, speedNext, updateTimestamp = false)
        }
        refreshRequestOverhead()
        // 换会话 / 换服务商都会重跑这里：待生效投影必须跟着选中会话重新解析。
        publishPendingSelection()
    }

    /**
     * 把选中会话的待生效绑定投影给弹窗：与已绑定选择一样按精确 modelId 投影，
     * 目标已被删除 / 禁用时直接不落影，等终态提交时静默丢弃。
     */
    private fun publishPendingSelection() {
        val pending = pendingSelectionByKey[selectedConversationId.orEmpty()]
        val providerId = pending?.providerId.orEmpty()
        val modelId = pending?.modelId.orEmpty()
        val provider = selectionProviders.singleOrNull { it.id == providerId }?.takeIf { it.isEnabled }
        val model = provider?.models?.singleOrNull { it.id == modelId }?.takeIf { it.isEnabled }
        val option = if (provider != null && model != null) {
            AgentModelPickerProjector.project(listOf(provider.withModels(listOf(model))), provider.id, model.id).selectedModel
        } else null
        val assistantId = pending?.assistantId?.takeIf { AssistantRepository.profile(it) != null }
        if (modelPickerState.pendingModel == option && modelPickerState.pendingAssistantId == assistantId) return
        modelPickerState = modelPickerState.copy(pendingModel = option, pendingAssistantId = assistantId)
    }

    private fun rejectSendIfModelUnavailable(): Boolean {
        val selected = modelPickerState.selectedModel
        if (!modelPickerState.isChanging && selected != null && selected.providerId == homeState.providerId && selected.id == homeState.modelId) return false
        Toast.makeText(appContext, appContext.getString(R.string.appst_model_unavailable_send), Toast.LENGTH_LONG).show()
        return true
    }

    private val modelReasoningMemory = HashMap<String, ReasoningEffort>()

    private fun modelReasoningKey(providerId: String, modelId: String): String =
        providerId + "\u0000" + modelId

    private fun rememberedModelReasoningEffort(
        providerId: String,
        modelId: String,
        stored: ReasoningEffort?,
    ): ReasoningEffort = modelReasoningMemory[modelReasoningKey(providerId, modelId)] ?: stored ?: ReasoningEffort.OFF

    private fun rememberModelReasoningEffort(providerId: String, modelId: String, effort: ReasoningEffort) {
        if (providerId.isBlank() || modelId.isBlank()) return
        modelReasoningMemory[modelReasoningKey(providerId, modelId)] = effort
        scope.launch(Dispatchers.IO) {
            val model = ModelRepository.modelsByProvider(providerId).firstOrNull { it.id == modelId } ?: return@launch
            if (model.preferredReasoningEffort == effort) return@launch
            ModelRepository.saveModel(providerId, model.copy(preferredReasoningEffort = effort))
        }
    }

    private fun preferredReasoningEffortForCurrentModel(): ReasoningEffort {
        val model = modelPickerState.selectedModel
        val preferred = if (model == null) {
            ReasoningEffort.OFF
        } else {
            rememberedModelReasoningEffort(model.providerId, model.id, model.preferredReasoningEffort)
        }
        return ConversationReasoningPolicy.resolve(preferred, currentReasoningCapabilities)
    }

    private fun AgentChatHomeUiState.withPreferredReasoningEffort(): AgentChatHomeUiState {
        val normalized = preferredReasoningEffortForCurrentModel()
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
        )
    }

    fun refreshRuntimeResults() {
        if (conversationArchiveBusy) {
            runtimeRefreshAfterArchive = true
            return
        }
        runtimeRefreshPending.set(true)
        pumpRuntimeRecovery()
    }

    /**
     * 单飞 + 合并的恢复泵：恢复不并发，但进行中的请求不会被丢。
     *
     * 原来「已在恢复中就直接 return」会让紧跟在 `ON_RESUME` 恢复之后的终态落库被吞掉——
     * 归档刚写完、恢复刚好还在跑，这一次通告就白发了，内容要等到下一次切前台才出现。
     */
    private fun pumpRuntimeRecovery() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                while (runtimeRefreshPending.compareAndSet(true, false)) {
                    // 归档必须先于 outbox 折叠：外部入口（系统语音浮窗、小爱、Breeno）的 run 同时留下
                    // 两份记录——归档带完整事件流（思考 / 工具卡），outbox 只有最终 result。先跑 recover
                    // 会用 result 折出一条「只有最终答复、连用户消息都没有」的助手消息，随后归档导入
                    // 因 alreadyImported（assistant-$runId-1 且非 streaming）直接早退并被删掉，
                    // 中间过程永久丢失。反过来先导入归档，recover 的 history reducer 会判 alreadyApplied
                    // 而只做收尾，不重复追加。
                    importArchivedExternalRuns()
                    recoverRuntimeRuns()
                }
            } finally {
                runtimeRecoveryInProgress.set(false)
                // 关闸那一刻进来并撞上 CAS 的请求：再泵一次，否则它要等下一次外部触发。
                if (runtimeRefreshPending.get()) pumpRuntimeRecovery()
            }
        }
    }

    /**
     * 外部入口的 run 到终态就会在 [AgentRunArchiveStore.archived] 上通告。
     *
     * 订阅方只负责触发一次恢复，是否真有内容由读库决定。`awaitConversationsHydrated()` 是
     * 为了构造期的早到通告：此时会话表还是空种子，导入会误判成「新会话」并把持久化写歪。
     */
    private fun observeRuntimeArchiveCompletions() {
        scope.launch {
            AgentRunArchiveStore.archived.collect {
                awaitConversationsHydrated()
                refreshRuntimeResults()
            }
        }
    }

    /**
     * 浮窗关闭时把**还在跑**的 run 交接过来（见 [AgentLiveRunHandoff]）。
     *
     * 只做「接管 + 挂接」：首轮用户消息按归档同一口径补进会话，之后的事件全部走
     * [subscribeAttachedRun] 的 attach → 投影 → 终态落库通路，与派发子 run 完全同一套，
     * 不新开第二条渲染路径。
     */
    private fun observeLiveRunHandoffs() {
        scope.launch {
            AgentLiveRunHandoff.notices.collect { notice -> adoptLiveExternalRun(notice) }
        }
    }

    private fun adoptLiveExternalRun(notice: AgentLiveRunHandoff.Notice) {
        scope.launch(Dispatchers.IO) {
            awaitConversationsHydrated()
            // 通告带 replay：用户可能先关浮窗、过一阵才打开应用。只有 run 仍活跃才接管；
            // 已终态的单交给归档导入（那里有完整事件流），不拿半截状态占位。
            if (!awaitRunActive(notice.runId)) return@launch
            withContext(Dispatchers.Main) { bindLiveExternalRun(notice) }
        }
    }

    /**
     * 探询该 run 是否仍在跑。
     *
     * 两个方向都要挡：通告可能早于 Runtime 建好 session（提交后立刻关浮窗），也可能晚到
     * （replay 给新构造的状态）。三次探询覆盖前者；三次都不在就判终态，交给归档路径——
     * 那时接管只会拿一条没有事件流的半截消息占位。Runtime 不可达同样不算活跃。
     */
    private suspend fun awaitRunActive(runId: String): Boolean {
        if (runId.isBlank()) return false
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        repeat(3) { attempt ->
            val active = client.queryActiveRun()
            if (active is AgentRuntimeClient.ActiveRunQuery.Known && runId in active.runIds) return true
            if (attempt < 2) delay(250)
        }
        return false
    }

    private fun bindLiveExternalRun(notice: AgentLiveRunHandoff.Notice) {
        val runId = notice.runId
        if (runId.isBlank() || runId in runJobs) return
        val payload = notice.payload
        val conversationId = archiveConversationId(notice.source, payload.conversationKey)
        val effort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationState(conversationId)
            ?: emptyChatState(effort.enablesReasoning).copy(reasoningEffort = effort)
        if (AgentRuntimeHistoryReducer.wasApplied(existingState, runId)) return
        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        if (conversationId !in conversationCreatedAt) {
            conversationCreatedAt = conversationCreatedAt + (conversationId to notice.createdAt)
        }
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                thinkingEnabled = effort.enablesReasoning,
                reasoningEffort = effort,
                // 同一单被交接两次（浮窗重开又关）不能叠两条同 id 的用户消息：列表 key 会冲突。
                messages = existingState.messages.filterNot { it.id == "user-$runId" } +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = notice.userImagePreviews,
                    ),
            ),
        )
        // 与「派发子 run 挂到工作会话」同一套：置 streaming / 绑用量归属 / attach 订阅。
        startAttachedRun(runId, conversationId)
        refreshConversationSummaries()
        persistConversations()
        // 用户此刻多半不在聊天页：还在调度台或空会话时就顺手选中，并留一次性的主页钉子
        // （[liveExternalConversationRequest]），下次进主页直接落在这一单上。
        if (selectedConversationId == null || selectedConversationId == dispatchConsoleId()) {
            selectConversation(conversationId)
        }
        liveExternalConversationRequest = conversationId
    }

    fun consumeLiveExternalConversationRequest(conversationId: String) {
        if (liveExternalConversationRequest == conversationId) liveExternalConversationRequest = null
    }

    fun refreshMemory(assistantId: String = AssistantRepository.active().id) {
        val owner = assistantId
        val name = AssistantRepository.currentProfile(owner)?.name.orEmpty()
        val generation = ++memoryEditGeneration
        memoryState = AgentMemoryUiState(assistantId = owner, assistantName = name, isLoading = true)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AgentMemoryRepository.snapshot(owner) }
            withContext(Dispatchers.Main) {
                if (generation != memoryEditGeneration || memoryState.assistantId != owner) return@withContext
                result.fold(onSuccess = { snapshot ->
                    memoryState = AgentMemoryUiState(assistantId = owner, assistantName = name, baseRevision = snapshot.revision,
                        enabled = AssistantRepository.currentProfile(owner)?.memoryEnabled == true, isLoading = false,
                        draft = snapshot.content, savedContent = snapshot.content, draftBytes = snapshot.byteSize)
                    refreshRequestOverhead()
                }, onFailure = { error ->
                    memoryState = memoryState.copy(isLoading = false, notice = error.message ?: appContext.getString(R.string.appst_memory_read_failed))
                })
            }
        }
    }

    fun updateMemoryDraft(content: String) {
        memoryState = memoryState.copy(draft = content, draftBytes = content.toByteArray(Charsets.UTF_8).size, notice = null)
    }

    fun setMemoryEnabled(enabled: Boolean) {
        val owner = memoryState.assistantId.takeIf { it.isNotBlank() } ?: return
        val generation = memoryEditGeneration
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AgentMemoryRepository.setEnabled(enabled, owner) }
            withContext(Dispatchers.Main) {
                if (generation != memoryEditGeneration || memoryState.assistantId != owner) return@withContext
                memoryState = if (result.isSuccess) memoryState.copy(enabled = enabled, notice = null)
                    else memoryState.copy(notice = result.exceptionOrNull()?.message ?: appContext.getString(R.string.appst_memory_toggle_save_failed))
                refreshRequestOverhead()
            }
        }
    }

    fun saveMemory() {
        if (memoryState.canSave) writeMemoryDraft(memoryState.draft)
    }

    fun clearMemory() {
        if (!memoryState.isLoading && !memoryState.isSaving) writeMemoryDraft("")
    }

    private fun writeMemoryDraft(target: String) {
        val state = memoryState
        if (state.assistantId.isBlank() || state.baseRevision.isBlank() || state.isSaving) return
        if (AssistantRepository.currentProfile(state.assistantId) == null) {
            memoryState = memoryState.copy(notice = appContext.getString(R.string.appst_memory_foreign_draft))
            return
        }
        val generation = memoryEditGeneration
        memoryState = state.copy(isSaving = true, notice = null)
        scope.launch(Dispatchers.IO) {
            val result = runCatching { AgentMemoryRepository.replaceAll(target, state.assistantId, state.baseRevision) }
            withContext(Dispatchers.Main) {
                if (generation != memoryEditGeneration || memoryState.assistantId != state.assistantId) return@withContext
                result.fold(onSuccess = { snapshot ->
                    val draft = if (memoryState.draft == state.draft) snapshot.content else memoryState.draft
                    memoryState = memoryState.copy(isSaving = false, baseRevision = snapshot.revision,
                        savedContent = snapshot.content, draft = draft, draftBytes = draft.toByteArray(Charsets.UTF_8).size,
                        notice = appContext.getString(R.string.appst_memory_saved))
                    refreshRequestOverhead()
                }, onFailure = { error ->
                    memoryState = memoryState.copy(isSaving = false, notice = error.message ?: appContext.getString(R.string.appst_memory_save_failed))
                })
            }
        }
    }

    fun dismissMemoryNotice() {
        memoryState = memoryState.copy(notice = null)
    }

    private data class BranchRequestBoundary(
        val runId: String,
        val round: Int,
        val snapshotId: String,
        val textBaseline: Map<String, String>,
    )
    private val branchRequestBoundaries = mutableMapOf<String, BranchRequestBoundary>()
    private var branchHistorySnapshotLoader: (String, String, String) -> io.github.mangi.eta.agent.runtime.AgentRuntimeSession.HistorySnapshot? =
        { owner, run, snapshot -> AgentRuntimeClient(appContext, AndroidAgentLogger).queryHistory(owner, run, snapshot) }

    private var conversationRevisionBusy = false

    private fun rejectConversationArchiveMutation(protectStoppingRun: Boolean = true): Boolean {
        if (conversationRevisionBusy) {
            Toast.makeText(appContext, appContext.getString(R.string.appst_restoring_revisions), Toast.LENGTH_SHORT).show()
            return true
        }
        // Stop settlement owns the current transcript, not navigation, metadata or model choice.
        // Those safe operations still obey the independent archive/backup maintenance lock.
        if (protectStoppingRun && stoppingRuns.keys.any { runConversationIds[it] == selectedConversationId }) {
            Toast.makeText(appContext, StopSealNotices.pending(), Toast.LENGTH_SHORT).show()
            return true
        }
        if (!conversationArchiveBusy && !io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) return false
        Toast.makeText(appContext, appContext.getString(R.string.appst_import_export_busy), Toast.LENGTH_SHORT).show()
        return true
    }

    private suspend fun <T> withConversationArchive(
        requireIdleRuns: Boolean = true,
        block: suspend () -> T,
    ): T = withContext(Dispatchers.Main.immediate) {
        check(!conversationArchiveBusy && !conversationRevisionBusy) { "已有对话归档或修订任务正在执行" }
        if (requireIdleRuns) {
            check(
                !runtimeRecoveryInProgress.get() &&
                    !modelPickerState.isChanging &&
                    pendingManualCompress == null &&
                    runJobs.isEmpty() &&
                    compressionJob?.isActive != true &&
                    conversationsById.values.none { it.isStreaming || it.isCompressingContext },
            ) { "请先等待对话任务或恢复完成" }
        }
        conversationArchiveBusy = true
        try {
            // Force a fresh save, and inspect its Boolean result instead of only joining a Job.
            val saved = withContext(Dispatchers.Main.immediate) { persistConversations(allowArchive = true) }
            check(saved.await()) { "当前对话保存失败，归档任务未开始，请重试" }
            conversationPersistenceMutex.withLock { block() }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                conversationArchiveBusy = false
                if (runtimeRefreshAfterArchive) {
                    runtimeRefreshAfterArchive = false
                    refreshRuntimeResults()
                }
                if (bindingRefreshAfterArchive) {
                    bindingRefreshAfterArchive = false
                    refreshBoundModelPicker()
                }
                val deferred = deferredArchiveSaves.toList()
                deferredArchiveSaves.clear()
                if (deferred.isNotEmpty()) scope.launch {
                    try {
                        val saved = persistConversations().await()
                        withContext(Dispatchers.IO) {
                            deferred.forEach { (completion, callback) ->
                                try { if (saved) callback?.invoke() }
                                catch (_: Exception) { /* A callback cannot undo the committed save. */ }
                                finally { completion.complete(saved) }
                            }
                        }
                    } finally {
                        deferred.forEach { (completion, _) -> completion.complete(false) }
                    }
                }
            }
        }
    }

    suspend fun exportBackup(
        output: OutputStream,
        options: EtaBackupExportOptions = EtaBackupExportOptions(),
    ): EtaBackupSummary = withConversationArchive(requireIdleRuns = false) {
        EtaBackupRepository.export(appContext, output, options)
    }

    suspend fun exportConversation(conversationId: String, output: OutputStream): EtaBackupSummary =
        withConversationArchive(requireIdleRuns = false) {
            EtaBackupRepository.exportConversation(appContext, conversationId, output)
        }

    /**
     * 导出会话分析 JSON：消息、每回合 token 账单与工具调用明细。
     *
     * 可选带上原始模型 I/O 与运行时事件日志，以及图片。
     */
    suspend fun exportConversationAnalysis(
        conversationId: String,
        options: EtaConversationAnalysisOptions = EtaConversationAnalysisOptions(),
    ): String = withConversationArchive(requireIdleRuns = false) {
        EtaConversationAnalysisCodec.encode(
            EtaConversationAnalysisRepository.export(appContext, conversationId, options),
        )
    }

    /** 导入会话分析 JSON，还原成一条普通新会话，返回新会话 id。 */
    suspend fun importConversationAnalysis(raw: String): String = withConversationArchive {
        val document = EtaConversationAnalysisCodec.decode(raw)
        val id = EtaConversationAnalysisRepository.import(appContext, document)
        reloadConversationsAfterBackup()
        id
    }

    /** 只恢复模型文件，不改动对话、设置与附件。 */
    suspend fun importModels(input: InputStream): EtaModelBackupSummary =
        withContext(Dispatchers.IO) {
            EtaBackupRepository.importModels(appContext, input)
        }

    suspend fun importBackup(input: InputStream): EtaBackupSummary {
        val summary = withConversationArchive {
            val activeRunQuery = withContext(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRun()
            }
            when (val active = activeRunQuery) {
                is AgentRuntimeClient.ActiveRunQuery.Known -> check(active.runId == null) { "请先停止正在运行的 Agent 任务" }
                AgentRuntimeClient.ActiveRunQuery.Unavailable -> error("无法确认 Agent Runtime 状态，请稍后重试")
            }
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    EtaBackupRepository.import(appContext, input)
                } finally {
                    // Even a failed commit-marker fsync may have committed metadata already.
                    // Never unlock with stale in-memory data capable of overwriting the database.
                    reloadConversationsAfterBackup()
                }
            }
        }
        // 归档锁释放后再补齐：备份可能不含调度台，或把它留在回收站里。
        withContext(Dispatchers.Main.immediate) { ensureDispatchConsoleSelected() }
        return summary
    }

    private suspend fun reloadConversationsAfterBackup() {
        val snapshot = withContext(Dispatchers.IO) {
            AgentConversationStore.load(appContext, selectedOnly = true)
        }
        withContext(Dispatchers.Main.immediate) {
            try {
                conversationSubAgentPreferences.refreshAfterRestore()
                val next = readSubAgentDraftPointer(snapshot.selectedConversationId?.let { SubAgentConfigKey.Conversation(it) })
                Prefs.putString("agent_conversation_child_ui_draft_v1", next.value)
                draftSubAgentOwner = next
                subAgentDraftReady = true
                subAgentRestoreBlocked = false
            } catch (failure: Exception) {
                subAgentRestoreBlocked = true
                subAgentConfigFailed(failure)
            }
            pendingSubAgentDraftBindings.clear()
            // Always reload committed Room history, even when preferences cannot yet be read.
            // Claim hydration so a late startup load cannot rewind this restored snapshot.
            conversationsHydrated = true
            conversationsHydratedSignal.complete(Unit)
            selectedConversationId = snapshot.selectedConversationId
            conversationsById = snapshot.conversationsById.mapValues { (_, state) -> orderedTerminalState(state) }
            conversationTitles = snapshot.titles
            conversationUpdatedAt = snapshot.updatedAt
            conversationCreatedAt = snapshot.createdAt
            conversationFolderIds = snapshot.folderIds
            conversationPinned = snapshot.pinnedIds
            conversationCompletionMarkers = snapshot.completionMarkerIds - listOfNotNull(snapshot.selectedConversationId)
            conversationDeletedAt = snapshot.deletedAt
            conversationDispatchMarks = snapshot.dispatchMarkedIds
            conversationFolders = snapshot.folders
            selectedFolderId = null
            selectedRemoteServerId = null
            pendingNewConversationFolderId = null
            fileAttachmentOwnerVersion += 1
            homeState = selectedConversationId
                ?.let(::conversationState)
                ?: newDraftChatState()
            conversationPaneState = conversationPaneState.copy(
                selectedConversationId = selectedConversationId,
                searchQuery = "",
            )
            refreshConversationSummaries()
            restoreConversationRuntimeModel()
        }
    }

    /** 用 checkpoint、终态 outbox 与 active session 一次性对账，避免用进程存活推断 run 状态。 */
    private suspend fun recoverRuntimeRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val checkpoints = withContext(Dispatchers.IO) {
            AgentRunCheckpointStore.list(appContext)
        }
        val initialCompletedQuery = client.queryCompletedRuns()
        if (initialCompletedQuery is AgentRuntimeClient.CompletedRunsQuery.Unavailable) {
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed"
            }
        }
        val initialCompletedRuns =
            (initialCompletedQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val activeRunQuery = client.queryActiveRun()
        val terminalRaceQuery = if (
            activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known && checkpoints.isNotEmpty()
        ) {
            client.queryCompletedRuns()
        } else {
            initialCompletedQuery
        }
        val terminalRaceCompletedRuns =
            (terminalRaceQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val completedRuns = (initialCompletedRuns + terminalRaceCompletedRuns)
            .associateBy { completed ->
                completed.result.runId.ifBlank { completed.handoff.id }
            }
            .values
            .toList()
        val activeStateKnown = activeRunQuery is AgentRuntimeClient.ActiveRunQuery.Known
        val terminalStateKnown = terminalRaceQuery is AgentRuntimeClient.CompletedRunsQuery.Known
        val activeRunIds = (activeRunQuery as? AgentRuntimeClient.ActiveRunQuery.Known)
            ?.runIds
            .orEmpty()
        val locallyObservedRunIds = withContext(Dispatchers.Main) {
            // Conversation bindings route events; only subscriber jobs prove local observation.
            runJobs.keys.toSet()
        }
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = checkpoints,
            completedRuns = completedRuns,
            activeStateKnown = activeStateKnown,
            terminalStateKnown = terminalStateKnown,
            activeRunIds = activeRunIds,
            locallyObservedRunIds = locallyObservedRunIds,
        )
        // Room-only question recovery must run even with no checkpoint/outbox or unavailable
        // run indexes. Run absence is not proof that a question's answer was never consumed.
        val questionConversationIds = withContext(Dispatchers.IO) {
            AgentConversationStore.questionConversationIds(appContext)
        }

        // Resolve every conversation this pass will touch while still off the main thread.
        // The projection below is synchronous and would otherwise read Room once per run
        // on the UI thread; AgentUiHandoffPayload.from is a pure parse.
        preloadConversationsForRecovery(
            buildList {
                plan.completed.forEach { add(completedRunConversationId(it.result)) }
                plan.interrupted.forEach { add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId) }
                plan.reattach.forEach { add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId) }
            },
        )

        val acknowledgeAfterSave = mutableListOf<String>()
        val removeAfterSave = mutableListOf<String>()
        val changed = withContext(Dispatchers.Main) {
            var stateChanged = false
            plan.completed.forEach { recoveryPlan ->
                val completedRun = recoveryPlan.result
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val isDispatch = completedRun.handoff.source == AgentRuntimeWire.ETA_DISPATCH_HANDOFF_SOURCE
                // 派发来源没有 UI 补充轮，载荷也不是 AgentUiHandoffPayload 的 JSON，
                // 必须按 handoff source 解析，否则会话 id 会退化成整段 JSON。
                val payload = if (isDispatch) null else AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val dispatchBrief = if (isDispatch) {
                    AgentDispatchPayload.from(completedRun.handoff.payload)?.brief.orEmpty()
                } else {
                    ""
                }
                val conversationId = completedRunConversationId(completedRun)
                val state = conversationState(conversationId)
                if (state == null) {
                    // 会话尚未进内存（或已被删除）：不 ACK，让结果留在 outbox，
                    // 等物化/下次 recovery 再把终态折叠进会话，避免结果被丢弃。
                    return@forEach
                }
                // A degraded checkpoint may omit an event boundary; apply the authoritative
                // result but do not replay an incomplete trace into conversation history.
                recoveryPlan.checkpoint
                    ?.takeUnless { it.recoveryIncomplete }
                    ?.let { checkpoint ->
                        stateChanged = restoreCheckpointTrace(
                            checkpoint = checkpoint,
                            interrupted = false,
                        ) || stateChanged
                    }
                val result = completedRun.result
                if (isDispatch) {
                    // 通知可能随进程消失；用 outbox 终态把调度台那张卡从 Running 收到终态，
                    // 否则重启后卡片会一直停在「执行中」。
                    stateChanged = updateDispatchReportCardFromRecovery(
                        conversationId = conversationId,
                        ok = result.ok,
                        content = result.content,
                        error = result.error,
                    ) || stateChanged
                }
                val beforeRecovery = ensureDispatchBrief(
                    conversationState(conversationId) ?: state,
                    runId,
                    dispatchBrief,
                )
                val recovery = AgentPendingResultRecovery.apply(
                    state = beforeRecovery,
                    runId = runId,
                    result = result,
                    promptSupplement = payload?.promptSupplement,
                    supplements = payload?.supplements.orEmpty(),
                    generatedAtMillis = recoveryPlan.checkpoint?.events
                        ?.filterIsInstance<AgentEvent.RunFinished>()?.lastOrNull()?.generatedAtMillis,
                )
                val ordered = normalizeTerminalRunMessages(runId, recovery.state.messages)
                val next = AgentPendingResultRecovery.stateToPublish(beforeRecovery, recovery, ordered)
                if (next != null) {
                    updateConversation(
                        conversationId, next,
                        updateTimestamp = !recovery.alreadyApplied,
                    )
                    stateChanged = true
                    if (ConversationCompletionMarker.shouldMark(
                            result, isSelected = conversationId == selectedConversationId,
                            alreadyApplied = recovery.alreadyApplied,
                        )) {
                        markConversationCompleted(conversationId)
                    }
                    if (!recovery.alreadyApplied && VirtualCompletionNotice.confirmed(result)) {
                        Toast.makeText(appContext, appContext.getString(R.string.appst_task_completed), Toast.LENGTH_SHORT).show()
                    }
                }
                acknowledgeAfterSave += runId
            }

            plan.interrupted.forEach { checkpoint ->
                removeAfterSave += checkpoint.runId
                // An interrupted run with an incomplete checkpoint is intentionally
                // abandoned rather than replayed from an unsafe partial boundary.
                if (!checkpoint.recoveryIncomplete) {
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = true,
                    ) || stateChanged
                }
            }
            if (stateChanged) refreshConversationSummaries()
            stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
        }

        // Query after local terminal projection, before the durable save/ACK boundary, so a
        // missed QuestionResolved can correct an Interrupted card even on alreadyApplied runs.
        val questionChanges = reconcileRecoveredQuestions(client, questionConversationIds,
            completedRuns.map(::completedRunConversationId))
        // 消息无变化也可能有待 ACK 的终态：外部入口的 run 由归档先一步完整导入后，这里的
        // 折叠会被 history reducer 判为 alreadyApplied（不产生新消息），但 outbox 条目仍要清掉，
        // 否则每次启动都会重新读一遍同一份结果。
        if (changed || questionChanges || acknowledgeAfterSave.isNotEmpty()) {
            val saved = withContext(Dispatchers.Main) { persistConversations() }.await()
            if (saved) {
                acknowledgeAfterSave.forEach(client::ackResult)
                removeAfterSave.forEach { runId ->
                    AgentRunCheckpointStore.remove(appContext, runId)
                }
            }
        }

        plan.reattach.forEach { checkpoint ->
            withContext(Dispatchers.Main) { startReattachedRun(checkpoint) }
        }
    }

    /** One finite authoritative question pass shared by outbox and Room-only recovery. */
    private suspend fun reconcileRecoveredQuestions(
        client: AgentRuntimeClient,
        storedConversationIds: List<String>,
        completedConversationIds: List<String>,
    ): Boolean {
        val conversationIds = withContext(Dispatchers.Main) {
            (completedConversationIds + storedConversationIds + conversationsById.filterValues {
                it.conversationContentLoaded && it.messages.any { m ->
                    m is AgentQuestionMessageUi && m.status != AgentQuestionStatus.Answered
                }
            }.keys).distinct()
        }
        val owners = mutableListOf<AgentQuestionRequest>()
        conversationIds.forEach { id ->
            val cached = withContext(Dispatchers.Main) { conversationsById[id] } ?: return@forEach
            if (!cached.conversationContentLoaded) {
                val loaded = withContext(Dispatchers.IO) {
                    AgentConversationStore.loadConversation(appContext, id)?.let(::orderedTerminalState)
                } ?: return@forEach
                withContext(Dispatchers.Main) {
                    // Do not overwrite a concurrent edit/delete while Room was being read.
                    if (conversationsById[id] === cached) {
                        conversationsById = conversationsById + (id to loaded.withCurrentGptSpeedBinding())
                    }
                }
            }
            withContext(Dispatchers.Main) {
                conversationsById[id]?.takeIf { it.conversationContentLoaded }?.let { state ->
                    owners += AgentQuestionProjection.recoveryOwners(id, state.messages)
                }
            }
        }
        val batch = withContext(Dispatchers.Main) {
            AgentQuestionProjection.recoveryQueryBatch(owners, questionRecoveryQueryOffset).also {
                questionRecoveryQueryOffset = it.nextOffset
            }
        }
        // At most 8 reads, in groups of 4, each interrupted after 5s. There is no polling,
        // and neither a truncated batch nor null/timeout is used as evidence of death.
        val observations = batch.owners.chunked(AgentQuestionProjection.RECOVERY_QUERY_CONCURRENCY)
            .flatMap { group ->
                withContext(Dispatchers.IO) {
                    group.map { owner -> async {
                        owner to withTimeoutOrNull(AgentQuestionProjection.RECOVERY_QUERY_TIMEOUT_MILLIS) {
                            runInterruptible {
                                client.queryQuestion(owner.conversationId, owner.runId, owner.questionId, owner.toolCallId)
                            }
                        }
                    } }.map { it.await() }
                }
            }
        return withContext(Dispatchers.Main) {
            var changed = false
            observations.forEach { (owner, snapshot) ->
                // Persisted content may have been evicted/deleted while IPC was pending; never
                // reinsert a stale conversation. A subsequent bounded pass can retry it.
                val state = conversationsById[owner.conversationId]
                    ?.takeIf { it.conversationContentLoaded } ?: return@forEach
                val messages = AgentQuestionProjection.reconcileMessages(state.messages, owner, snapshot)
                if (messages != state.messages) {
                    updateConversation(owner.conversationId, state.copy(messages = messages), updateTimestamp = false)
                    changed = true
                }
            }
            if (changed) refreshConversationSummaries()
            changed
        }
    }

    /** 把安全事件恢复为 UI 轨迹；半截回复不进入模型 history，设备工具也不会重放。 */
    private fun restoreCheckpointTrace(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        interrupted: Boolean,
    ): Boolean {
        val runId = checkpoint.runId
        if (runId.isBlank()) return false
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationState(conversationId) ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false

        bindUsageRun(runId, conversationId)
        updateConversation(conversationId, existing.copy(isStreaming = true, activeRunId = runId))
        restoreRunEvents(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = AgentQuestionProjection.interruptWaiting(runId, runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = finalizedText,
                ))
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    interruptedTools + SystemNoticeMessageUi(
                        id = noticeId,
                        code = SystemNoticeCode.Interrupted,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runGeneratedAtMillis.remove(runId)
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    /**
     * 终态 outbox 条目所属会话。UI 来源从 AgentUiHandoffPayload 取，派发来源从
     * AgentDispatchPayload 取；两者都用同一个 identity 解析，避免把整段载荷当会话 id。
     */
    private fun completedRunConversationId(completedRun: AgentRuntimeWire.CompletedRun): String {
        val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
        return AgentConversationIdentity.fromHandoff(completedRun.handoff, runId)
    }

    private fun startReattachedRun(checkpoint: AgentRunCheckpointStore.Checkpoint) {
        val runId = checkpoint.runId
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        startAttachedRun(runId, conversationId)
    }

    /**
     * 把调度台派发的子 run 挂到工作会话上。
     *
     * 派发 run 由 Runtime 自己 startRun，UI 既不写检查点也不订阅事件；若不主动 attach，
     * 打开派发会话就只能看到调度端预先落下的那条简讯。这里补齐下发指令并复用与
     * 前端 run 完全相同的 attach → 事件投影 → 终态持久化通路。
     */
    private fun attachDispatchedRun(conversationId: String, runId: String, brief: String) {
        if (conversationId.isBlank() || runId.isBlank()) return
        val current = conversationState(conversationId) ?: return
        if (runId in runJobs || AgentRuntimeHistoryReducer.wasApplied(current, runId)) return
        val prepared = ensureDispatchBrief(current, runId, brief)
        bindUsageRun(runId, conversationId)
        updateConversation(conversationId, prepared.copy(isStreaming = true, activeRunId = runId))
        refreshConversationSummaries()
        subscribeAttachedRun(runId)
    }

    /**
     * 派发指令不一定由 UI 写入会话：新建工作会话由调度端落了 user 简讯，
     * 接续的用户会话则没有。挂接/回收前把缺失的指令补进展示与模型历史，
     * 保证打开会话能看到完整一轮；已有同文末轮时不重复插入。
     */
    private fun ensureDispatchBrief(
        state: AgentChatHomeUiState,
        runId: String,
        brief: String,
    ): AgentChatHomeUiState {
        val text = brief.trim()
        if (text.isEmpty()) return state
        val historyHasBrief = state.history.lastOrNull()?.let { last ->
            last.role.equals("user", ignoreCase = true) && last.content.trim() == text
        } == true
        val messagesHasBrief = (state.messages.lastOrNull() as? UserMessageUi)
            ?.content?.trim() == text
        if (historyHasBrief && messagesHasBrief) return state
        val history = if (historyHasBrief) {
            state.history
        } else {
            state.history + AgentModelClient.buildUserHistoryMessage(text, emptyList()).copy(turnId = runId)
        }
        val messages = if (messagesHasBrief) state.messages else {
            state.messages + UserMessageUi(id = "user-$runId", content = text)
        }
        return state.copy(messages = messages, history = history)
    }

    private fun startAttachedRun(runId: String, conversationId: String) {
        val existing = conversationState(conversationId) ?: return
        if (runId in runJobs || AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return

        bindUsageRun(runId, conversationId)
        updateConversation(conversationId, existing.copy(isStreaming = true, activeRunId = runId))
        refreshConversationSummaries()
        subscribeAttachedRun(runId)
    }

    private fun subscribeAttachedRun(runId: String) {
        runJobs[runId] = scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val outcome = client.attachRun(
                runId = runId,
                onReplay = { events -> restoreRunEvents(runId, events) },
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            when (outcome) {
                is AgentRuntimeClient.AttachOutcome.Completed -> withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId = runId,
                        result = outcome.result,
                        acknowledgeRuntimeResult = true,
                    )
                }
                AgentRuntimeClient.AttachOutcome.NotActive -> {
                    withContext(Dispatchers.Main) {
                        if (runJobs.remove(runId) != null) {
                            setConversationStreaming(runId, false)
                        }
                    }
                    recoverRuntimeRuns()
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    // Losing the subscriber is not evidence that Runtime stopped the run.
                    // Keep its trace and binding until foreground recovery can query Runtime.
                    runJobs.remove(runId)
                }
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return

        // 归档是往已有会话里追加内容：先把目标会话从 Room 读进内存。否则 conversationState()
        // 只能拿到「有预览、内容未加载」的条目或直接退化到 emptyChatState，随后的持久化会用
        // 空历史把该会话覆盖掉（语音浮窗的归档会话尤其容易撞上）。
        preloadArchivedConversations(
            archivedRuns.mapNotNull { archived ->
                AgentExternalArchivePayload.from(archived.handoff.payload)?.let { payload ->
                    archiveConversationId(archived.handoff.source, payload.conversationKey)
                }
            },
        )

        withContext(Dispatchers.Main) {
            if (conversationArchiveBusy) return@withContext // Keep archive files for the next replay.
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations(
                onSaved = {
                    importedRunIds.forEach { runId ->
                        AgentRunArchiveStore.remove(appContext, runId)
                    }
                },
            )
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationState(conversationId) ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort)
        // 归档带完整事件流（思考 / 工具卡），outbox 只带最终 result。恢复路径先折叠过 result 时，
        // 会话里会留下一条 assistant-$runId-1 的纯文本，若只凭「已有完成的助手消息」判定已导入，
        // 中间过程就再也补不回来。因此再加一条：归档里有可见工作轨迹、而会话里该 run 一条都没有，
        // 就必须重放——下面的 restoreRunEvents 会先 reset 该 run 的旧投影，不会重复堆消息。
        val archivedHasWorkTrace = archivedRun.events.any { it.isVisibleWorkTrace() }
        val stateHasWorkTrace = existingState.messages.any { message ->
            message.id.startsWith("$runId-thinking-") || message.id.startsWith("$runId-tool-")
        }
        val alreadyImported = (AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }) && !(archivedHasWorkTrace && !stateHasWorkTrace)
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        bindUsageRun(runId, conversationId)
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                // 重导路径下 user-$runId 可能已存在（上一次折叠留下的）：同 id 两条会让列表 key 冲突。
                messages = existingState.messages.filterNot { it.id == "user-$runId" } +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        // 批量回放：单次可变快照 + resetForReplay（清掉该 run 已有投影）+ replaying 事件，
        // 末尾只刷一次摘要；逐事件 apply 会让每个增量都走一遍列表投影与摘要。
        restoreRunEvents(runId, archivedRun.events)
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    /**
     * 归档事件里是否存在「用户可见的工作过程」：工具调用或思考块。纯文本轮流
     * （只有文本事件）不算——那种 run 折叠 result 与回放事件的结果一致，没有补导的价值。
     */
    private fun AgentEvent.isVisibleWorkTrace(): Boolean = when (this) {
        is AgentEvent.ToolStarted, is AgentEvent.ToolFinished,
        is AgentEvent.HostedToolStarted, is AgentEvent.HostedToolFinished -> true
        is AgentEvent.AssistantBlockStart -> kind == AgentEvent.AssistantBlockKind.THINKING
        is AgentEvent.AssistantBlockEnd -> kind == AgentEvent.AssistantBlockKind.THINKING
        else -> false
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        if (rejectConversationArchiveMutation()) return
        if (homeState.isStreaming && !homeState.isPaused) return
        val normalized = ConversationReasoningPolicy.resolve(effort, currentReasoningCapabilities)
        if (normalized == homeState.reasoningEffort) return
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        rememberModelReasoningEffort(homeState.providerId, homeState.modelId, normalized)
        if (selectedConversationId != null) persistConversations()
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
    }

    fun cycleGptSpeedMode() {
        if (rejectConversationArchiveMutation()) return
        if (homeState.isStreaming && !homeState.isPaused) return
        if (modelPickerState.isChanging) return
        val selected = modelPickerState.selectedModel ?: return
        if (selected.id != homeState.modelId || selected.providerId != homeState.providerId) return
        val provider = selectionProviders.singleOrNull { it.id == selected.providerId }?.takeIf { it.isEnabled } ?: return
        val model = provider.models.singleOrNull { it.id == selected.id }?.takeIf { it.isEnabled } ?: return
        val eligible = supportsGptSpeedBinding(provider, model)
        if (!eligible) return
        val next = GptSpeedModePolicy.cycle(rememberedGptSpeedMode(provider.id, model.id), model.modelId, eligible)
        val saved = runCatching { mainAgentSpeedDefaults.remember(provider, model, next) }.getOrElse {
            Toast.makeText(appContext, appContext.getString(R.string.appst_gpt_speed_save_failed), Toast.LENGTH_LONG).show()
            return
        }
        if (!saved) return
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        updateCurrentConversation(homeState.copy(gptSpeedMode = next))
        // Refresh other loaded conversations bound to this exact selection. Runtime configs remain frozen.
        conversationsById.toList().forEach { (id, state) ->
            if (id != selectedConversationId && state.conversationContentLoaded &&
                state.providerId == provider.id && state.modelId == model.id) {
                updateConversation(id, state.copy(gptSpeedMode = next), updateTimestamp = false)
            }
        }
        // Never rewrite provider custom bodies, reasoning preferences, child defaults or presets.
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
    }

    fun selectModel(modelId: String, providerId: String = "") {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (homeState.isCompressingContext) {
            Toast.makeText(appContext, appContext.getString(R.string.composer_picker_compressing_locked), Toast.LENGTH_LONG).show()
            return
        }
        val provider = selectionProviders.filter { it.isEnabled && (providerId.isBlank() || it.id == providerId) && it.models.any { m -> m.id == modelId && m.isEnabled } }.singleOrNull() ?: return
        if (selectionProviders.count { it.id == provider.id } != 1) return
        val model = provider.models.singleOrNull { it.id == modelId }?.takeIf { it.isEnabled } ?: return
        if (homeState.providerId == provider.id && homeState.modelId == model.id) {
            // 点回本轮正在用的模型，等于撤销这条待生效选择。
            cancelPendingModelSelection()
            return
        }
        // 生成中改选只登记待生效绑定：本轮仍按发起时冻结的配置跑，终态提交后下一轮生效。
        if (homeState.isStreaming && !homeState.isPaused) {
            stagePendingModelSelection(provider, model)
            return
        }
        applyModelSelection(provider, model)
    }

    private fun cancelPendingModelSelection() {
        val key = selectedConversationId.orEmpty()
        val pending = pendingSelectionByKey[key] ?: return
        if (pending.providerId.isBlank()) return
        pendingSelectionByKey[key] = pending.copy(providerId = "", modelId = "")
        publishPendingSelection()
    }

    private fun stagePendingModelSelection(
        provider: io.github.mangi.eta.data.model.ProviderSetting,
        model: io.github.mangi.eta.data.model.Model,
    ) {
        val key = selectedConversationId.orEmpty()
        pendingSelectionByKey[key] = (pendingSelectionByKey[key] ?: PendingSelection())
            .copy(providerId = provider.id, modelId = model.id)
        publishPendingSelection()
        Toast.makeText(
            appContext,
            appContext.getString(R.string.composer_pending_selection_toast, model.displayName.ifBlank { model.modelId }),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 把模型绑定落到选中会话：档位与速度按目标模型自己的记忆恢复，不沿用上一个模型的偏好。 */
    private fun applyModelSelection(
        provider: io.github.mangi.eta.data.model.ProviderSetting,
        model: io.github.mangi.eta.data.model.Model,
    ) {
        if (homeState.providerId == provider.id && homeState.modelId == model.id) return
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        modelBindingGeneration++
        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant = null)
        val requestedEffort = rememberedModelReasoningEffort(provider.id, model.id, model.preferredReasoningEffort)
        val nextEffort = ConversationReasoningPolicy.resolve(requestedEffort, config.reasoningCapabilities)
        updateCurrentConversation(homeState.copy(providerId = provider.id, modelId = model.id,
            gptSpeedMode = rememberedGptSpeedMode(provider.id, model.id),
            reasoningEffort = nextEffort, thinkingEnabled = nextEffort.enablesReasoning,
            livePromptTokens = null, livePromptIsProjected = false))
        billedOverheadTokens = null
        refreshBoundModelPicker()
        if (nextEffort != requestedEffort) Toast.makeText(appContext,
            appContext.getString(R.string.appst_reasoning_tier_adjusted), Toast.LENGTH_SHORT).show()
        if (selectedConversationId != null) persistConversations()
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
    }

    private fun resolvePendingSelection(pending: PendingSelection): Pair<io.github.mangi.eta.data.model.ProviderSetting, io.github.mangi.eta.data.model.Model>? {
        val provider = pending.providerId.takeIf { it.isNotBlank() }
            ?.let { id -> selectionProviders.singleOrNull { it.id == id }?.takeIf { it.isEnabled } } ?: return null
        val model = provider.models.singleOrNull { it.id == pending.modelId }?.takeIf { it.isEnabled } ?: return null
        return provider to model
    }

    /**
     * 本轮终态：把待生效选择落到会话。走的是即时路径同样的绑定写入，因此提交之后
     * [updateConversationProjected] 的 modelChanged 分支重置圆环与回执本就是期望行为。
     */
    private fun commitPendingSelection(conversationKey: String) {
        val pending = pendingSelectionByKey.remove(conversationKey) ?: return
        val assistantId = pending.assistantId?.takeIf { AssistantRepository.profile(it) != null }
        if (conversationKey == selectedConversationId.orEmpty()) {
            assistantId?.let { applyConversationAssistant(it, persist = false) }
            if (pending.providerId.isNotBlank() && pending.modelId.isNotBlank()) {
                resolvePendingSelection(pending)?.let { (provider, model) -> applyModelSelection(provider, model) }
            }
            publishPendingSelection()
            return
        }
        // 后台会话终态：只改它自己的绑定，不碰选中会话，也不提前改全局活动助手。
        val state = conversationState(conversationKey) ?: return
        val targetModel = if (pending.providerId.isNotBlank() && pending.modelId.isNotBlank()) {
            resolvePendingSelection(pending)
        } else null
        val next = if (targetModel != null) {
            val (provider, model) = targetModel
            val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model, assistant = null)
            val nextEffort = ConversationReasoningPolicy.resolve(
                rememberedModelReasoningEffort(provider.id, model.id, model.preferredReasoningEffort),
                config.reasoningCapabilities,
            )
            state.copy(
                providerId = provider.id,
                modelId = model.id,
                gptSpeedMode = rememberedGptSpeedMode(provider.id, model.id),
                reasoningEffort = nextEffort,
                thinkingEnabled = nextEffort.enablesReasoning,
                livePromptTokens = null,
                livePromptIsProjected = false,
            )
        } else state
        val withAssistant = assistantId?.let { next.copy(assistantId = it) } ?: next
        if (withAssistant == state) return
        updateConversation(conversationKey, withAssistant)
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
    }

    suspend fun searchHistory(
        query: String,
        currentConversationOnly: Boolean = false,
    ): List<MessageSearchHit> {
        if (query.isBlank()) return emptyList()
        val roles = MessageSearchRoleLabels(
            user = appContext.getString(R.string.search_history_role_user),
            assistant = appContext.getString(R.string.search_history_role_assistant),
            thinking = appContext.getString(R.string.search_history_role_thinking),
            tool = appContext.getString(R.string.search_history_role_tool),
        )
        val unnamed = appContext.getString(R.string.conversation_unnamed)
        val titleRole = appContext.getString(R.string.search_history_role_title)
        val source = if (currentConversationOnly) currentConversationSearchScope() else
            conversationsById.toMutableMap().also { all ->
                selectedConversationId?.let { all[it] = homeState }
                if (selectedConversationId == null && homeState.messages.isNotEmpty()) all[""] = homeState
            }.filterKeys { it !in conversationDeletedAt }
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        // 展示名与真实标题分离：调度台搜索既要命中真实标题关键词，也要能搜到固定显示名「调度台」。
        val consoleId = dispatchConsoleId()
        val consoleDisplayTitle = dispatchConsoleTitle()
        val displayTitles = consoleId?.let { titles + (it to consoleDisplayTitle) } ?: titles
        return runInterruptible(Dispatchers.IO) {
        source.entries.sortedWith(compareBy<Map.Entry<String, AgentChatHomeUiState>> {
            timestamps[it.key] ?: 0L
        }.thenBy { it.key }).flatMap { (id, state) ->
            // 标题与正文一起匹配：标题命中排在同一会话的正文命中之前。
            val realTitle = titles[id].orEmpty()
            val displayTitle = if (id == consoleId) consoleDisplayTitle else realTitle
            val titleHit = searchConversationTitle(id, realTitle, query, titleRole)
                ?: searchConversationTitle(id, displayTitle, query, titleRole)
            val messageHits = if (state.conversationContentLoaded) {
                searchConversationMessages(mapOf(id to state), displayTitles, timestamps, query, unnamed, roles)
            } else {
                AgentConversationStore.searchStoredConversation(appContext, id,
                    displayTitles[id].orEmpty(), timestamps[id] ?: 0L, query, unnamed, roles)
            }
            listOfNotNull(titleHit) + messageHits
        }
        }
    }

    private fun currentConversationSearchScope(): Map<String, AgentChatHomeUiState> {
        val currentId = selectedConversationId
        return when {
            currentId != null -> mapOf(currentId to homeState)
            homeState.messages.isNotEmpty() -> mapOf("" to homeState)
            else -> emptyMap()
        }
    }

    fun openHistorySearchHit(hit: MessageSearchHit) {
        // 标题命中没有目标消息：只切会话，不设置滚动锚点（否则会滚到一个不存在的 id）。
        val scrollTarget = hit.messageId.takeIf { it.isNotEmpty() }
        if (hit.conversationId.isNotEmpty() && hit.conversationId != selectedConversationId) {
            selectConversationContent(hit.conversationId) { pendingScrollToMessageId = scrollTarget }
        } else if (scrollTarget != null) {
            pendingScrollToMessageId = scrollTarget
        }
    }

    fun consumePendingScrollToMessage() {
        pendingScrollToMessageId = null
    }

    fun selectConversation(conversationId: String) = selectConversationContent(conversationId) {}

    private fun selectConversationContent(conversationId: String, onSelected: () -> Unit) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (homeState.messageEdit != null) cancelMessageEdit()
        val version = ++conversationSelectionVersion
        conversationSelectionJob?.cancel()
        val state = conversationsById[conversationId] ?: return
        // 回收站里的会话不能被选中：入口只应来自恢复操作。
        if (conversationId in conversationDeletedAt) return
        if (state.conversationContentLoaded) {
            applySelectedConversation(conversationId, state)
            onSelected()
            return
        }
        val ownerVersion = fileAttachmentOwnerVersion
        conversationSelectionJob = scope.launch {
            try {
                val loaded = runInterruptible(Dispatchers.IO) {
                    AgentConversationStore.loadConversation(appContext, conversationId)
                } ?: return@launch
                if (version != conversationSelectionVersion || ownerVersion != fileAttachmentOwnerVersion || conversationArchiveBusy) return@launch
                val latest = conversationsById[conversationId] ?: return@launch
                // Runtime recovery may have hydrated/updated it while the database read ran.
                applySelectedConversation(conversationId, if (latest.conversationContentLoaded) latest else loaded)
                onSelected()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Conversation load failed: ${failure.safeLogType()}")
                Toast.makeText(appContext, appContext.getString(R.string.appst_conversation_load_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applySelectedConversation(conversationId: String, state: AgentChatHomeUiState) {
        check(state.conversationContentLoaded)
        // 选中本机会话即从远程视图切回本机数据源。
        selectedTarget = io.github.mangi.eta.ui.remote.ChatTarget.Local
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        if (conversationId in conversationCompletionMarkers) {
            conversationCompletionMarkers = conversationCompletionMarkers - conversationId
        }
        val owner = ownerContext(conversationId)
        val view = owner.projection()
        val ordered = orderedTerminalState(state.withCurrentGptSpeedBinding()).copy(
            childContexts = view.children, selectedContextTaskId = view.selectedTaskId,
            childStatusRoster = owner.roster(),
        )
        conversationsById = conversationsById + (conversationId to ordered)
        homeState = ordered
        billedOverheadConversationId = null
        billedOverheadTokens = null
        syncBilledOverhead(conversationId, ordered.messages)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        refreshConversationSummaries()
        persistConversations()
        restoreConversationRuntimeModel()
        requestOwnerContext(conversationId)
    }

    fun createConversation() {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (homeState.messageEdit != null) cancelMessageEdit()
        if (!beginNewSubAgentDraft()) return
        val conversationId = newConversationId()
        if (!bindSubAgentDraft(conversationId)) return
        // A pending lazy selection must not replace the explicitly created conversation.
        conversationSelectionVersion += 1
        conversationSelectionJob?.cancel()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        pendingNewConversationFolderId = selectedFolderId
        homeState = newDraftChatState()
        rememberModelReasoningEffort(homeState.providerId, homeState.modelId, homeState.reasoningEffort)
        billedOverheadConversationId = null
        billedOverheadTokens = null
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = conversationId,
            searchQuery = "",
        )
        // Explicit new conversations exist before their first message. Startup/deletion
        // placeholders still use null IDs and are not automatically added to history.
        updateConversation(conversationId, homeState)
        assignPendingFolder(conversationId)
        restoreConversationRuntimeModel()
        refreshConversationSummaries()
        persistConversations()
    }

    fun selectFolder(folderId: String?) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        selectedFolderId = folderId
        selectedRemoteServerId = null
        refreshConversationSummaries()
    }

    /**
     * 选中侧栏里的远程服务器胶囊。不加 rejectConversationArchiveMutation 守卫：
     * 这里只切换列表过滤器，不触碰任何会话数据，归档保护期间选中也应照常生效。
     */
    fun selectRemoteServerPane(serverId: String) {
        selectedRemoteServerId = serverId
    }

    fun createFolder(name: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val folder = ConversationFolderUi(
            id = "folder-${UUID.randomUUID()}",
            name = trimmed,
            sortIndex = (conversationFolders.maxOfOrNull { it.sortIndex } ?: -1) + 1,
        )
        conversationFolders = conversationFolders + folder
        selectedFolderId = folder.id
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameFolder(folderId: String, name: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        conversationFolders = conversationFolders.map { folder ->
            if (folder.id == folderId) folder.copy(name = trimmed) else folder
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteFolder(folderId: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        conversationFolders = conversationFolders.filterNot { it.id == folderId }
        conversationFolderIds = conversationFolderIds.filterValues { it != folderId }
        if (selectedFolderId == folderId) selectedFolderId = null
        if (pendingNewConversationFolderId == folderId) pendingNewConversationFolderId = null
        refreshConversationSummaries()
        persistConversations()
    }

    fun moveConversationToFolder(conversationId: String, folderId: String?) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (conversationId !in conversationsById) return
        // 调度台是系统条目：分类归属是普通会话的整理属性，一律不动（入口已在侧栏藏掉，
        // 这里防的是历史遗留归属与其他调用方）；顺带幂等清掉残留条目。
        if (isDispatchConsoleConversation(conversationId)) {
            if (conversationId in conversationFolderIds) {
                conversationFolderIds = conversationFolderIds - conversationId
                refreshConversationSummaries()
                persistConversations()
            }
            return
        }
        conversationFolderIds = if (folderId.isNullOrBlank()) {
            conversationFolderIds - conversationId
        } else {
            conversationFolderIds + (conversationId to folderId)
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun toggleConversationPinned(conversationId: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (conversationId !in conversationsById) return
        // 调度台是系统条目：置顶强制成立，不接受取消（只补齐状态，不翻转）。
        if (isDispatchConsoleConversation(conversationId)) {
            if (conversationId !in conversationPinned) {
                conversationPinned = conversationPinned + conversationId
                refreshConversationSummaries()
                persistConversations()
            }
            return
        }
        conversationPinned = if (conversationId in conversationPinned) {
            conversationPinned - conversationId
        } else {
            conversationPinned + conversationId
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteAllConversations() {
        if (rejectConversationArchiveMutation() || deleteAllConversationsJob?.isActive == true || conversationsById.isEmpty()) return
        deleteAllConversationsJob = scope.launch {
            try {
                withConversationArchive {
                    val source = conversationsById
                    val timestamps = conversationUpdatedAt
                    // 调度台是系统条目：清空全部会话时保留它（内容、标题、置顶状态都留着）。
                    // 文件夹归属不保留：调度台不参与分类，冷启动 ensureDispatchConsoleSelected
                    // 也会幂等清理历史遗留条目。
                    val consoleId = dispatchConsoleId()?.takeIf { it in source }
                    val ids = source.keys.filter { it != consoleId }
                    if (ids.isEmpty()) return@withConversationArchive
                    val aggregate = runInterruptible(Dispatchers.IO) {
                        var usage = ConversationTokenUsageUi()
                        var messageCount = 0
                        val heatmap = mutableMapOf<java.time.LocalDate, Int>()
                        ids.forEach { id ->
                            val state = source.getValue(id)
                            val content = if (state.conversationContentLoaded) state else
                                requireNotNull(AgentConversationStore.loadConversation(appContext, id))
                            val amount = conversationTokenUsage(content.messages)
                            usage = ConversationTokenUsageUi(usage.inputTokens + amount.inputTokens,
                                usage.outputTokens + amount.outputTokens, usage.cachedTokens + amount.cachedTokens)
                            messageCount += content.messages.count { it is UserMessageUi || it is AgentMessageUi }
                            timestamps[id]?.takeIf { it > 0L }?.let { timestamp ->
                                val day = java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                                heatmap[day] = (heatmap[day] ?: 0) + 1
                            }
                        }
                        Triple(usage, messageCount, heatmap.toMap())
                    }
                    check(conversationsById === source && conversationUpdatedAt === timestamps) { "会话已变化，请重试" }
                    ids.forEach(::cancelConversationExecution)
                    val keptState = consoleId?.let(source::get)
                    val keptDraft = consoleId?.let { conversationDrafts.field(it).text.toString() }
                    val keptUpdatedAt = consoleId?.let { conversationUpdatedAt[it] ?: System.currentTimeMillis() }
                    val keptCreatedAt = consoleId?.let { conversationCreatedAt[it] }
                    val keptTitle = consoleId?.let {
                        conversationTitles[it]?.takeIf(String::isNotBlank) ?: dispatchConsoleTitle()
                    }
                    val keptMarkerIds = consoleId?.takeIf { it in conversationCompletionMarkers }?.let { setOf(it) } ?: emptySet()
                    // Commit before deleting drafts/images or changing UI. Once committed,
                    // cancellation must not leave UI representing rows that no longer exist.
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                        AgentConversationStore.save(
                            appContext,
                            selectedConversationId = null,
                            conversationsById = if (consoleId != null && keptState != null) mapOf(consoleId to keptState) else emptyMap(),
                            titles = consoleId?.let { mapOf(it to (keptTitle ?: "")) } ?: emptyMap(),
                            updatedAt = consoleId?.let { mapOf(it to (keptUpdatedAt ?: 0L)) } ?: emptyMap(),
                            folderIds = emptyMap(),
                            pinnedIds = consoleId?.let { setOf(it) } ?: emptySet(),
                            completionMarkerIds = keptMarkerIds,
                            folders = conversationFolders,
                        )
                        withContext(Dispatchers.Main.immediate) {
                            pendingRetiredUsage = ConversationTokenUsageUi(
                                pendingRetiredUsage.inputTokens + aggregate.first.inputTokens,
                                pendingRetiredUsage.outputTokens + aggregate.first.outputTokens,
                                pendingRetiredUsage.cachedTokens + aggregate.first.cachedTokens,
                            )
                            pendingRetiredConversations += ids.size
                            pendingRetiredMessages += aggregate.second
                            aggregate.third.forEach { (day, count) ->
                                pendingRetiredHeatmap = pendingRetiredHeatmap + (day to ((pendingRetiredHeatmap[day] ?: 0) + count))
                            }
                            conversationDrafts.clear()
                            if (consoleId != null && keptDraft != null) conversationDrafts.replace(consoleId, keptDraft)
                            conversationsById = if (consoleId != null && keptState != null) mapOf(consoleId to keptState) else emptyMap()
                            conversationTitles = consoleId?.let { mapOf(it to (keptTitle ?: "")) } ?: emptyMap()
                            conversationUpdatedAt = consoleId?.let { mapOf(it to (keptUpdatedAt ?: 0L)) } ?: emptyMap()
                            conversationCreatedAt = consoleId?.let { id -> keptCreatedAt?.let { mapOf(id to it) } } ?: emptyMap()
                            conversationFolderIds = emptyMap()
                            conversationPinned = consoleId?.let { setOf(it) } ?: emptySet()
                            conversationCompletionMarkers = keptMarkerIds
                            conversationDeletedAt = conversationDeletedAt - ids
                            fileAttachmentOwnerVersion += 1
                            beginNewSubAgentDraft()
                            selectedConversationId = null
                            pendingNewConversationFolderId = selectedFolderId
                            homeState = newDraftChatState()
                            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
                            refreshConversationSummaries()
                        }
                        ids.forEach { id ->
                            runCatching { chatImageCache.deleteConversation(id) }
                                .onFailure { AndroidAgentLogger.warn("图片缓存清理失败：${it.javaClass.simpleName}") }
                            runCatching { io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, id).delete() }
                                .onFailure { AndroidAgentLogger.warn("压缩原文清理失败：${it.javaClass.simpleName}") }
                        }
                    }
                }
                // Retired counters are written only after deletion has durably succeeded.
                persistConversations()
                // 清空后仍落到调度台（缺失时补建），保持主页语义。
                ensureDispatchConsoleSelected()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Delete all conversations failed: ${failure.safeLogType()}")
                Toast.makeText(appContext, appContext.getString(R.string.appst_delete_incomplete), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 把会话移入回收站（软删除）：内容、草稿、图片缓存与压缩原文都保留，可随时恢复；
     * 只有 [deleteConversationForever] 才真正清理这些资源并退休用量统计。
     */
    fun deleteConversation(conversationId: String) {
        if (rejectConversationArchiveMutation()) return
        // 调度台是系统条目：不进回收站，也不打断它自己的运行。
        if (isDispatchConsoleConversation(conversationId)) return
        cancelConversationExecution(conversationId)
        if (conversationId !in conversationsById || conversationId in conversationDeletedAt) return
        if (selectedConversationId == conversationId) {
            // 软删除也要把选中项从被删会话上挪走，复用永久删除的切换逻辑（跳过回收站成员）。
            selectNextConversationAfterDeletion(excludeId = conversationId)
        }
        conversationDeletedAt = conversationDeletedAt + (conversationId to System.currentTimeMillis())
        pendingSelectionByKey.remove(conversationId)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    /** 从回收站恢复会话：仅清除软删除标记，标题、文件夹、置顶等状态原样保留。 */
    fun restoreConversationFromTrash(conversationId: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        if (conversationId !in conversationDeletedAt) return
        // 调度台归档不是独立会话：恢复语义是「接回当前调度台顶部」，并删除这条回收站记录。
        if (isDispatchConsoleArchiveSnapshot(conversationId)) {
            restoreDispatchConsoleArchive(conversationId)
            return
        }
        conversationDeletedAt = conversationDeletedAt - conversationId
        refreshConversationSummaries()
        persistConversations()
    }

    /** 永久删除：清掉全部内存痕迹、草稿、图片缓存与压缩原文，并把用量统计退休。不可恢复。 */
    fun deleteConversationForever(conversationId: String) {
        if (rejectConversationArchiveMutation()) return
        if (conversationId !in conversationsById) return
        // 调度台是系统条目：永久删除同样不可用。
        if (isDispatchConsoleConversation(conversationId)) return
        val wasSelected = selectedConversationId == conversationId
        conversationState(conversationId)?.let { state ->
            // 归档快照是原会话的副本：退休会把同一份用量重复计入，跳过。
            if (!isDispatchConsoleArchiveSnapshot(conversationId)) {
                retainDeletedConversation(state.messages, conversationUpdatedAt[conversationId])
            }
        }
        conversationDrafts.remove(conversationId)
        pendingSelectionByKey.remove(conversationId)
        pendingConversationTitles.remove(conversationId)
        titleRequestSeq.remove(conversationId)
        manuallyRenamedDuringTitleRequest -= conversationId
        io.github.mangi.eta.agent.model.ModelFeaturePreferences.forgetManuallyRenamed(conversationId)
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        conversationCreatedAt = conversationCreatedAt - conversationId
        conversationFolderIds = conversationFolderIds - conversationId
        conversationPinned = conversationPinned - conversationId
        conversationCompletionMarkers = conversationCompletionMarkers - conversationId
        conversationDeletedAt = conversationDeletedAt - conversationId
        scope.launch(Dispatchers.IO) { chatImageCache.deleteConversation(conversationId) }
        if (wasSelected) {
            selectNextConversationAfterDeletion(excludeId = conversationId)
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        if (wasSelected) restoreConversationRuntimeModel()
        refreshConversationSummaries()
        val deletion = persistConversations()
        scope.launch(Dispatchers.IO) {
            if (deletion.await()) {
                listOf(conversationId).forEach { id ->
                    runCatching { io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, id).delete() }
                        .onFailure { AndroidAgentLogger.warn("压缩原文清理失败：${it.javaClass.simpleName}") }
                }
            }
        }
    }

    /** 清空回收站：逐个走 [deleteConversationForever]，持久化队列会合并这些快照。 */
    fun emptyTrash() {
        if (rejectConversationArchiveMutation()) return
        val trashedIds = conversationDeletedAt.keys.filterNot(::isDispatchConsoleConversation)
        if (trashedIds.isEmpty()) return
        trashedIds.forEach { id ->
            if (id in conversationsById) deleteConversationForever(id)
        }
    }

    /**
     * 自动清理回收站：把软删除时间超过留存期（[Prefs.trashRetentionDays]）的会话
     * 永久删除；留存为 0（永不清理）时不动任何内容。在启动水合完成后、打开回收站
     * 视图与工具列出回收站时调用，保证回收站成员最终一定会被清理。
     */
    fun purgeExpiredTrash(now: Long = System.currentTimeMillis()) {
        if (rejectConversationArchiveMutation()) return
        val retentionDays = Prefs.trashRetentionDays()
        if (retentionDays <= 0) return
        val retentionMs = retentionDays * DAY_MILLIS
        val expired = conversationDeletedAt
            // 调度台本体永不从回收站清理；调度台归档快照也不参与自动过期，保证「随时可接回」。
            .filterKeys { id ->
                !isDispatchConsoleConversation(id) && !isDispatchConsoleArchiveSnapshot(id)
            }
            .filterValues { deletedAt -> now - deletedAt >= retentionMs }
            .keys
            .filter { it in conversationsById }
        if (expired.isEmpty()) return
        expired.forEach { deleteConversationForever(it) }
    }

    /**
     * 删除（软/硬）后的选中切换：从剩余会话里挑一个未删除的，挑不出就落到新草稿。
     * 由 [deleteConversation] 与 [deleteConversationForever] 共用，调用方负责后续的 pane 同步。
     */
    private fun selectNextConversationAfterDeletion(excludeId: String) {
        fileAttachmentOwnerVersion += 1
        val nextId = conversationsById.keys.firstOrNull { it != excludeId && it !in conversationDeletedAt }
        if (nextId != null) {
            selectedConversationId = nextId
            conversationCompletionMarkers = conversationCompletionMarkers - nextId
            // The load below can legitimately return null: this conversation may have
            // been removed concurrently, or the row can fail to decode. Crashing the
            // UI on a failed switch is worse than falling back to a fresh draft.
            val next = conversationState(nextId)
            if (next != null) {
                homeState = next
                conversationsById = conversationsById + (nextId to next)
            } else {
                conversationsById = conversationsById - nextId
                selectedConversationId = null
                beginNewSubAgentDraft()
                homeState = newDraftChatState()
            }
        } else {
            beginNewSubAgentDraft()
            selectedConversationId = null
            homeState = newDraftChatState()
        }
    }

    /**
     * 手动改过标题的会话：永久不再被自动标题覆盖（含首轮生成与每轮刷新）。
     * 持久化到 Prefs，否则重启后「每轮刷新」会把用户手改的标题重新盖掉。
     */
    private val manuallyRenamedDuringTitleRequest: MutableSet<String> by lazy {
        io.github.mangi.eta.agent.model.ModelFeaturePreferences.manuallyRenamedConversations()
            .toMutableSet()
    }

    fun renameConversation(conversationId: String, title: String) {
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) return
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        manuallyRenamedDuringTitleRequest += conversationId
        io.github.mangi.eta.agent.model.ModelFeaturePreferences.markManuallyRenamed(conversationId)
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    /** eta_manage_history 读取的文件夹快照。 */
    internal data class HistoryFolder(
        val id: String,
        val name: String,
        val sortIndex: Int,
        val conversationCount: Int,
    )

    /** eta_manage_history 读取的会话快照；[deletedAt] 非空表示在回收站里。 */
    internal data class HistoryConversation(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val createdAt: Long,
        val folderId: String?,
        val folderName: String?,
        val pinned: Boolean,
        val deletedAt: Long? = null,
    )

    /** eta_manage_history 写操作结果；失败时返回结构化错误码与说明。 */
    internal data class HistoryMutation(
        val ok: Boolean,
        val code: String = "",
        val message: String = "",
        val folder: HistoryFolder? = null,
        val conversation: HistoryConversation? = null,
        val movedConversations: Int = 0,
        val deletedConversations: Int = 0,
    )

    private fun historyFailure(code: String, message: String) = HistoryMutation(ok = false, code = code, message = message)

    /** 调度台是系统级条目：删除与取消置顶在工具侧显式拒绝；重命名则按普通会话放行。 */
    private fun protectedConversationFailure() =
        historyFailure("PROTECTED_CONVERSATION", "「调度台」是系统会话，不支持删除或取消置顶")

    // 失败文案真源在资源（三语），中文串只是纯 JVM 单测无 Context 时的兜底（同 StopSealNotices 模式）；
    // code 是模型解析用的机器语义，message 本地化不影响工具结果解析。
    private fun historyFailure(code: String, resId: Int, fallback: String, vararg args: Any) =
        HistoryMutation(ok = false, code = code, message = localizedText(resId, fallback, *args))

    private fun historyFolderSnapshot(folder: ConversationFolderUi): HistoryFolder = HistoryFolder(
        id = folder.id,
        name = folder.name,
        sortIndex = folder.sortIndex,
        conversationCount = conversationFolderIds.count { it.value == folder.id },
    )

    private fun historyConversationSnapshot(conversationId: String): HistoryConversation? {
        if (conversationId !in conversationsById) return null
        val folderId = conversationFolderIds[conversationId]?.takeIf { it.isNotBlank() }
        return HistoryConversation(
            id = conversationId,
            title = conversationTitles[conversationId]?.takeIf { it.isNotBlank() }
                ?: appContext.getString(R.string.conversation_unnamed),
            updatedAt = conversationUpdatedAt[conversationId] ?: 0L,
            createdAt = conversationCreatedAt[conversationId] ?: 0L,
            folderId = folderId,
            folderName = folderId?.let { id -> conversationFolders.firstOrNull { it.id == id }?.name },
            pinned = conversationId in conversationPinned,
            deletedAt = conversationDeletedAt[conversationId],
        )
    }

    /**
     * eta_manage_history 的只读入口：读取全部文件夹。
     *
     * 会话与文件夹的唯一真相源是本对象持有的内存快照；工具在读之前先等启动水合完成，
     * 避免看到构造期那批空种子数据。
     */
    internal suspend fun agentHistoryFolders(): List<HistoryFolder> = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        conversationFolders.sortedBy { it.sortIndex }.map(::historyFolderSnapshot)
    }

    /** eta_manage_history 的只读入口：读取全部会话，置顶在前，其后按更新时间由新到旧。 */
    internal suspend fun agentHistoryConversations(trashed: Boolean = false): List<HistoryConversation> = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        if (trashed) {
            // 列回收站前先按留存期清理一次，模型看到的即真实回收站。
            purgeExpiredTrash()
            // 回收站视图：按删除时间由新到旧。
            conversationsById.keys
                .filter { it in conversationDeletedAt }
                .sortedWith(
                    compareByDescending<String> { conversationDeletedAt[it] ?: 0L }.thenBy { it },
                )
                .mapNotNull(::historyConversationSnapshot)
        } else {
            conversationsById.keys
                .filter { it !in conversationDeletedAt }
                .sortedWith(
                    compareByDescending<String> { it in conversationPinned }
                        .thenByDescending { conversationUpdatedAt[it] ?: 0L }
                        .thenBy { it },
                )
                .mapNotNull(::historyConversationSnapshot)
        }
    }

    /** eta_manage_history 的只读入口：按消息正文检索会话。 */
    internal suspend fun agentHistorySearch(query: String): List<MessageSearchHit> {
        awaitConversationsHydrated()
        return searchHistory(query)
    }

    /**
     * eta_manage_history 的写入入口：新建文件夹。
     *
     * 写入路径必须落在主线程，且与手工操作共用同一批门面方法，因此这里只做前置校验、
     * 调用既有方法、再回读快照确认结果，保证工具改动与人手改动完全等价。
     */
    internal suspend fun agentHistoryCreateFolder(name: String): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@withContext historyFailure("INVALID_ARGUMENT", R.string.appst_history_create_folder_need_name, "新建文件夹需要提供 name")
        if (conversationFolders.any { it.name.equals(trimmed, ignoreCase = true) }) {
            return@withContext historyFailure("NAME_TAKEN", R.string.appst_history_folder_name_taken, "已存在同名文件夹：%1\$s", trimmed)
        }
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        val before = conversationFolders.mapTo(mutableSetOf()) { it.id }
        createFolder(trimmed)
        val created = conversationFolders.firstOrNull { it.id !in before }
            ?: return@withContext historyFailure("WRITE_FAILED", R.string.appst_history_folder_create_failed, "新建文件夹失败")
        HistoryMutation(ok = true, folder = historyFolderSnapshot(created))
    }

    internal suspend fun agentHistoryRenameFolder(folderId: String, name: String): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        val folder = conversationFolders.firstOrNull { it.id == folderId }
            ?: return@withContext historyFailure("FOLDER_NOT_FOUND", R.string.appst_history_folder_not_found, "找不到文件夹：%1\$s", folderId)
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@withContext historyFailure("INVALID_ARGUMENT", R.string.appst_history_rename_folder_need_name, "重命名文件夹需要提供 name")
        if (conversationFolders.any { it.id != folderId && it.name.equals(trimmed, ignoreCase = true) }) {
            return@withContext historyFailure("NAME_TAKEN", R.string.appst_history_folder_name_taken, "已存在同名文件夹：%1\$s", trimmed)
        }
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        renameFolder(folderId, trimmed)
        HistoryMutation(ok = true, folder = historyFolderSnapshot(folder.copy(name = trimmed)))
    }

    internal suspend fun agentHistoryDeleteFolder(folderId: String): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        val folder = conversationFolders.firstOrNull { it.id == folderId }
            ?: return@withContext historyFailure("FOLDER_NOT_FOUND", R.string.appst_history_folder_not_found, "找不到文件夹：%1\$s", folderId)
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        val moved = conversationFolderIds.count { it.value == folderId }
        deleteFolder(folderId)
        HistoryMutation(ok = true, folder = historyFolderSnapshot(folder), movedConversations = moved)
    }

    internal suspend fun agentHistoryRenameConversation(conversationId: String, name: String): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        if (conversationId !in conversationsById) {
            return@withContext historyFailure("CONVERSATION_NOT_FOUND", R.string.appst_history_conversation_not_found, "找不到对话：%1\$s", conversationId)
        }
        val trimmed = name.trim()
        if (trimmed.isBlank()) return@withContext historyFailure("INVALID_ARGUMENT", R.string.appst_history_rename_conversation_need_name, "重命名对话需要提供 name")
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        renameConversation(conversationId, trimmed)
        HistoryMutation(ok = true, conversation = historyConversationSnapshot(conversationId))
    }

    /** [folderId] 为 null 表示移出文件夹、回到未归类。 */
    internal suspend fun agentHistoryMoveConversation(conversationId: String, folderId: String?): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        if (conversationId !in conversationsById) {
            return@withContext historyFailure("CONVERSATION_NOT_FOUND", R.string.appst_history_conversation_not_found, "找不到对话：%1\$s", conversationId)
        }
        if (folderId != null && conversationFolders.none { it.id == folderId }) {
            return@withContext historyFailure("FOLDER_NOT_FOUND", R.string.appst_history_folder_not_found, "找不到文件夹：%1\$s", folderId)
        }
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        moveConversationToFolder(conversationId, folderId)
        HistoryMutation(ok = true, conversation = historyConversationSnapshot(conversationId))
    }

    internal suspend fun agentHistorySetConversationPinned(conversationId: String, pinned: Boolean): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        if (conversationId !in conversationsById) {
            return@withContext historyFailure("CONVERSATION_NOT_FOUND", R.string.appst_history_conversation_not_found, "找不到对话：%1\$s", conversationId)
        }
        if (!pinned && isDispatchConsoleConversation(conversationId)) {
            return@withContext protectedConversationFailure()
        }
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        // 门面是「直接设值」，只有在当前状态与目标不一致时才触发既有的翻转方法。
        if ((conversationId in conversationPinned) != pinned) toggleConversationPinned(conversationId)
        HistoryMutation(ok = true, conversation = historyConversationSnapshot(conversationId))
    }

    internal suspend fun agentHistoryDeleteConversation(conversationId: String): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        val snapshot = historyConversationSnapshot(conversationId)
            ?: return@withContext historyFailure("CONVERSATION_NOT_FOUND", R.string.appst_history_conversation_not_found, "找不到对话：%1\$s", conversationId)
        if (isDispatchConsoleConversation(conversationId)) {
            return@withContext protectedConversationFailure()
        }
        if (rejectConversationArchiveMutation()) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        // 软删除进回收站：可恢复，真正清理资源要再走永久删除。
        if (snapshot.deletedAt != null) {
            return@withContext HistoryMutation(ok = true, conversation = snapshot)
        }
        deleteConversation(conversationId)
        HistoryMutation(ok = true, conversation = historyConversationSnapshot(conversationId) ?: snapshot.copy(deletedAt = System.currentTimeMillis()))
    }

    /** 从回收站恢复会话；不在回收站时报错，避免模型把「恢复」当成通用撤销用。 */
    internal suspend fun agentHistoryRestoreConversation(conversationId: String): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        val snapshot = historyConversationSnapshot(conversationId)
            ?: return@withContext historyFailure("CONVERSATION_NOT_FOUND", R.string.appst_history_conversation_not_found, "找不到对话：%1\$s", conversationId)
        if (snapshot.deletedAt == null) {
            return@withContext historyFailure("NOT_IN_TRASH", R.string.appst_history_not_in_trash_restore, "该对话不在回收站中，无需恢复")
        }
        if (rejectConversationArchiveMutation(protectStoppingRun = false)) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        restoreConversationFromTrash(conversationId)
        HistoryMutation(ok = true, conversation = historyConversationSnapshot(conversationId) ?: snapshot.copy(deletedAt = null))
    }

    /** 永久删除：删除内容与图片缓存并退休用量统计，不可恢复。要求目标已在回收站。 */
    internal suspend fun agentHistoryDeleteConversationForever(conversationId: String): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        val snapshot = historyConversationSnapshot(conversationId)
            ?: return@withContext historyFailure("CONVERSATION_NOT_FOUND", R.string.appst_history_conversation_not_found, "找不到对话：%1\$s", conversationId)
        if (snapshot.deletedAt == null) {
            return@withContext historyFailure("NOT_IN_TRASH", R.string.appst_history_not_in_trash_purge, "该对话不在回收站中；请先删除再永久清理")
        }
        if (isDispatchConsoleConversation(conversationId)) {
            return@withContext protectedConversationFailure()
        }
        if (rejectConversationArchiveMutation()) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        deleteConversationForever(conversationId)
        HistoryMutation(ok = true, conversation = snapshot)
    }

    /** 清空回收站；返回清理数量。 */
    internal suspend fun agentHistoryEmptyTrash(): HistoryMutation = withContext(Dispatchers.Main.immediate) {
        awaitConversationsHydrated()
        if (rejectConversationArchiveMutation()) {
            return@withContext historyFailure("BUSY", R.string.appst_history_busy, "当前有对话归档任务在执行，请稍后再试")
        }
        val count = conversationDeletedAt.keys.count { !isDispatchConsoleConversation(it) }
        if (count == 0) return@withContext HistoryMutation(ok = true, deletedConversations = 0)
        emptyTrash()
        HistoryMutation(ok = true, deletedConversations = count)
    }

    fun sendCurrentMessage(submittedText: String? = null) {
        if (homeState.pendingConversationMentions.any { it.id in preparingConversationMentions }) {
            if (submittedText != null) updateCurrentConversation(homeState.copy(input = submittedText))
            Toast.makeText(appContext, appContext.getString(R.string.appst_mention_preparing), Toast.LENGTH_SHORT).show()
            return
        }
        if (rejectConversationArchiveMutation()) {
            // Preserve an early send while an archive operation is active.
            if (submittedText != null) updateCurrentConversation(homeState.copy(input = submittedText))
            return
        }
        if (io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) {
            Toast.makeText(appContext, appContext.getString(R.string.appst_restoring_backup), Toast.LENGTH_SHORT).show()
            return
        }
        if (!ensureSubAgentConfigurationReady()) return
        val prompt = (submittedText ?: currentDraftField().text.toString()).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        val pendingMentions = homeState.pendingConversationMentions
        if (!homeState.isStreaming && rejectSendIfModelUnavailable()) return
        if (homeState.isStreaming || homeState.isPaused) {
            if (
                prompt.isNotBlank() ||
                pendingImages.isNotEmpty() ||
                pendingFileReferences.isNotEmpty() || pendingMentions.isNotEmpty()
            ) {
                val conversationId = selectedConversationId
                // 纯文字短按排队；附件、引用、编辑态与暂停中的插话仍立即送入当前运行。
                if (
                    conversationId != null && homeState.isStreaming && !homeState.isPaused &&
                    homeState.messageEdit == null && pendingImages.isEmpty() &&
                    pendingFileReferences.isEmpty() && pendingMentions.isEmpty() && prompt.isNotBlank()
                ) {
                    enqueueQueuedMessage(conversationId, prompt)
                } else {
                    steerCurrentRun(submittedText ?: currentDraftField().text.toString())
                }
            }
            return
        }
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty() && pendingMentions.isEmpty()) {
            return
        }
        val generateVideo = selectedModelGeneratesVideos()
        val generateImage = !generateVideo && selectedModelGeneratesImages()
        if ((generateImage || generateVideo) && pendingMentions.isNotEmpty()) {
            Toast.makeText(appContext, appContext.getString(R.string.appst_mention_needs_chat_model), Toast.LENGTH_LONG).show()
            return
        }
        if ((generateImage || generateVideo) && prompt.isBlank()) {
            Toast.makeText(
                appContext,
                appContext.getString(
                    if (generateVideo) R.string.chat_video_prompt_required
                    else R.string.chat_image_prompt_required,
                ),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        if (rejectSendIfCompressing()) {
            return
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val edit = homeState.messageEdit
        if (edit == null && selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) {
            moveCurrentDraftToNewConversation()
            if (!subAgentDraftReady) return
        }

        val editBoundary = edit?.let {
            if (it.preparedFromHistory != null && it.preparedFromHistory != homeState.history) return@let null
            AgentConversationRevisionReducer.boundary(
                homeState.copy(history = it.preparedHistory ?: homeState.history), it.targetMessageId,
            )
        }
        if (edit != null && editBoundary == null) {
            if (submittedText != null) updateCurrentConversation(homeState.copy(input = submittedText))
            showRevisionHistoryUnavailableNotice()
            return
        }

        val history = if (editBoundary != null) {
            editBoundary.historyPrefix
        } else {
            AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
                homeState.history,
                homeState.messages,
            )
        }
        if (!generateImage && !generateVideo && rejectSendIfContextWindowExceeded(history, prompt, pendingImages, pendingFileReferences)) {
            return
        }
        val conversationId = selectedConversationId ?: newConversationId().also { id ->
            if (!bindSubAgentDraft(id)) return
            conversationDrafts.promote(id)
            selectedConversationId = id
            conversationPaneState = conversationPaneState.copy(selectedConversationId = id)
            // The pending draft callback is intentionally stale; replace it for the bound owner.
            refreshRequestOverhead()
            assignPendingFolder(id)
        }
        if (conversationId !in conversationsById) {
            updateConversation(conversationId, homeState)
            refreshConversationSummaries()
        }
        val supportsVision = generateImage || generateVideo || (modelPickerState.selectedModel?.supportsVision == true) || io.github.mangi.eta.agent.model.ModelFeaturePreferences.visionEnabled()
        val supportsVideo = modelPickerState.selectedModel?.supportsVideo ?: false
        if (pendingImages.isNotEmpty()) {
            val ownerState = homeState
            val ownerDraft = currentDraftField().text.toString()
            val ownerGeneration = modelBindingGeneration
            val ownerAssistant = AssistantRepository.active().id
            scope.launch(Dispatchers.IO) {
                val staged = stageChatImages(conversationId, pendingImages)
                withContext(Dispatchers.Main) {
                    if (staged.size != pendingImages.size || staged.any { it == null }) {
                        Toast.makeText(appContext, appContext.getString(R.string.appst_attachment_save_failed), Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    if (selectedConversationId != conversationId || modelBindingGeneration != ownerGeneration ||
                        homeState != ownerState || currentDraftField().text.toString() != ownerDraft ||
                        AssistantRepository.active().id != ownerAssistant) {
                        Toast.makeText(appContext, appContext.getString(R.string.appst_send_state_changed), Toast.LENGTH_LONG).show()
                        return@withContext
                    }
                    if (homeState.isStreaming || rejectSendIfCompressing()) return@withContext
                    val outbound = pendingImages.filter { image ->
                        if (image.isVideo) supportsVideo || supportsVision else supportsVision
                    }
                    // 视觉模型看得到画面，但也要拿到落盘路径：否则改图标、转存这类文件操作
                    // 只能去猜路径，曾因此在别的会话缓存里挑中旧截图。气泡按媒体路径去重，不会多出文件卡片。
                    val extraFiles = staged.filterNotNull()
                    startPreparedSend(
                        prompt = prompt,
                        uiImages = pendingImages,
                        modelImages = outbound,
                        fileReferences = fileReferences + extraFiles,
                        persistedImages = staged.filterNotNull(),
                        history = history,
                        editBoundary = editBoundary,
                        conversationId = conversationId,
                    )
                }
            }
            return
        }
        startPreparedSend(
            prompt = prompt,
            uiImages = pendingImages,
            modelImages = pendingImages,
            fileReferences = fileReferences,
            persistedImages = emptyList(),
            history = history,
            editBoundary = editBoundary,
            conversationId = conversationId,
        )
    }

    /** 把纯文字消息追加到会话队尾，并清空该会话草稿（正文已移入队列，不再保留在输入框）。 */
    private fun enqueueQueuedMessage(conversationId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val queue = queuedMessagesByConversation[conversationId].orEmpty()
        queuedMessagesByConversation[conversationId] = queue + QueuedMessageUi(
            id = "queued-${UUID.randomUUID()}",
            content = trimmed,
        )
        conversationDrafts.replace(conversationId, "")
    }

    /** 撤回队尾消息：从队列移除并合并回主输入框继续编辑。 */
    fun recallQueuedMessage(id: String) {
        val conversationId = selectedConversationId ?: return
        val queue = queuedMessagesByConversation[conversationId].orEmpty()
        val item = queue.firstOrNull { it.id == id } ?: return
        queuedMessagesByConversation[conversationId] = queue.filterNot { it.id == id }
        val existing = currentDraftField().text.toString()
        conversationDrafts.replace(
            conversationId,
            if (existing.isBlank()) item.content else existing + "\n" + item.content,
        )
    }

    /** 立即插话：把排队消息送入当前运行，并从队列中移除。 */
    fun steerQueuedMessage(id: String) {
        if (rejectConversationArchiveMutation()) return
        val conversationId = selectedConversationId ?: return
        val queue = queuedMessagesByConversation[conversationId].orEmpty()
        val item = queue.firstOrNull { it.id == id } ?: return
        if (!homeState.isStreaming || homeState.isPaused) return
        queuedMessagesByConversation[conversationId] = queue.filterNot { it.id == id }
        steerCurrentRun(item.content)
    }

    /** 丢弃排队消息：直接从队列移除，不撤回输入框。 */
    fun removeQueuedMessage(id: String) {
        val conversationId = selectedConversationId ?: return
        val queue = queuedMessagesByConversation[conversationId].orEmpty()
        if (queue.none { it.id == id }) return
        queuedMessagesByConversation[conversationId] = queue.filterNot { it.id == id }
    }

    /**
     * 运行自然结束后续发队首；用户主动停止时由调用方跳过，保留队列不自动发出。
     * @return 是否已真正发出（发出后调用方不应再对本轮次启动压缩，避免与新运行抢占）。
     */
    private fun flushQueuedMessages(conversationId: String): Boolean {
        val queue = queuedMessagesByConversation[conversationId].orEmpty()
        val next = queue.firstOrNull() ?: return false
        if (conversationId != selectedConversationId) return false
        if (homeState.isStreaming || homeState.isPaused || homeState.isCompressingContext) return false
        if (homeState.messageEdit != null) return false
        if (conversationId.isReadOnlyExternalArchiveConversation()) return false
        if (conversationRevisionBusy || conversationArchiveBusy) return false
        if (io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) return false
        if (rejectSendIfModelUnavailable()) return false
        val history = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
            homeState.history,
            homeState.messages,
        )
        if (rejectSendIfContextWindowExceeded(history, next.content, emptyList(), emptyList())) return false
        queuedMessagesByConversation[conversationId] = queue.drop(1)
        startPreparedSend(
            prompt = next.content,
            uiImages = emptyList(),
            modelImages = emptyList(),
            fileReferences = emptyList(),
            persistedImages = emptyList(),
            history = history,
            editBoundary = null,
            conversationId = conversationId,
            preserveDraft = true,
        )
        return true
    }

    private fun startPreparedSend(
        prompt: String,
        uiImages: List<PendingImageUi>,
        modelImages: List<PendingImageUi>,
        fileReferences: List<AgentFileReference>,
        persistedImages: List<AgentFileReference>,
        history: List<AgentModelClient.ConversationMessage>,
        editBoundary: AgentConversationRevisionReducer.Boundary?,
        conversationId: String,
        preserveDraft: Boolean = false,
    ) {
        // 兜底：上一轮若没走到终态收口（异常 / 重连路径），发下一轮前先把待生效选择落地，
        // 否则下面的发送期校验会拿旧绑定判定模型可用性。
        commitPendingSelection(conversationId)
        if (conversationId != selectedConversationId || rejectSendIfModelUnavailable()) return
        if (rejectSendIfCompressing()) return
        val runtimePrompt = AgentFileReferencePromptCodec.format(
            prompt,
            fileReferences,
            if (preserveDraft) {
                emptyList()
            } else {
                homeState.pendingConversationMentions.toMentionedConversations()
            },
        )
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = uiImages.map { it.dataUrl },
            isEdited = editBoundary != null,
            imageSources = when {
                persistedImages.size == uiImages.size -> persistedImages.map { it.absolutePath }
                else -> uiImages.map { it.uri }
            },
            imageIsVideo = uiImages.map { it.isVideo },
            imageDurationsMs = uiImages.map { it.durationMs },
        )
        val messages = if (editBoundary == null) {
            // 编辑保存沿用原气泡、原位替换，不算「新发出的一条」；只有全新发送才标记入场。
            sentUserMessageEntranceId = userMessage.id
            homeState.messages + userMessage
        } else {
            homeState.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val imageDims = if (persistedImages.size == uiImages.size) uiImages else emptyList()
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = runtimePrompt,
            persistedImages = persistedImages.mapIndexed { index, reference ->
                val ui = imageDims.getOrNull(index)
                AgentConversationCodec.PersistedImage(
                    path = reference.absolutePath,
                    mimeType = mimeTypeForFileName(reference.displayName),
                    displayName = reference.displayName,
                    // 带上原图宽高，下一轮预算才能按统一视觉公式估算，而不是按字节兜底。
                    width = ui?.widthPx,
                    height = ui?.heightPx,
                )
            },
        )

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt.ifBlank {
            homeState.pendingConversationMentions.firstOrNull()?.let { "@${it.title}" }.orEmpty()
        }, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle.isNullOrBlank())
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeIf(String::isNotBlank) ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = modelImages,
            // 调度台停止/暂停期间积压的派发回报：并进用户这一轮的 history（会随本轮持久化），
            // 让模型在用户主动发言时读到它并向用户报告，而不是靠自动拉起。
            history = history + drainPendingConsoleReceipts(conversationId),
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = if (preserveDraft) {
                // 队列续发：正文已在队列里，草稿与引用保留给用户继续编辑。
                homeState.copy(messageEdit = null)
            } else {
                homeState.copy(
                    input = "",
                    pendingImages = emptyList(),
                    pendingFileReferences = emptyList(),
                    pendingConversationMentions = emptyList(),
                    messageEdit = null,
                )
            },
            reasoningEffort = homeState.reasoningEffort,
            consumeDraft = !preserveDraft,
            logicalTurnId = editBoundary?.logicalTurnId ?: runId,
        )
    }

    private fun stageChatImages(
        conversationId: String,
        images: List<PendingImageUi>,
    ): List<AgentFileReference?> =
        images.mapIndexed { index, image ->
            if (image.isVideo) {
                val file = java.io.File(image.uri.removePrefix("file://"))
                if (!file.isFile) return@mapIndexed null
                chatImageCache.stageFromFile(
                    conversationId,
                    file,
                    image.cacheDisplayName(index),
                    MAX_AGENT_VIDEO_BYTES,
                )
            } else {
                val bytes = AgentChatImageCache.readBytes(image.uri)
                    ?: AgentChatImageCache.readBytes(image.dataUrl)
                    ?: return@mapIndexed null
                chatImageCache.stage(conversationId, bytes, image.cacheDisplayName(index))
            }
        }

    /** One unpublished transaction: IO may only produce a candidate, never mutate the source. */
    private fun launchConversationRevision(
        messageId: String,
        allowActiveSource: Boolean = false,
        publish: suspend (String, AgentChatHomeUiState, AgentChatHomeUiState, () -> Boolean) -> Unit,
    ) {
        if ((!allowActiveSource && (homeState.isStreaming || homeState.isPaused)) || homeState.messageEdit != null) return
        if (rejectConversationArchiveMutation() || rejectSendIfCompressing()) return
        if (modelPickerState.isChanging || runtimeRecoveryInProgress.get()) return
        val conversationId = selectedConversationId ?: return
        if (!allowActiveSource && runConversationIds.any { (run, owner) -> owner == conversationId && run in runJobs }) return
        val snapshot = homeState
        val runningBranch = allowActiveSource && (snapshot.isStreaming || snapshot.isPaused ||
            runConversationIds.any { (run, owner) -> owner == conversationId && run in runJobs })
        // Capture the request token and already-consumed TEXT with the click, before any suspension.
        val branchBoundary = if (runningBranch) runConversationIds.entries
            .singleOrNull { it.value == conversationId && it.key in runJobs }
            ?.key?.let { branchRequestBoundaries[it] } else null
        val selection = conversationSelectionVersion
        val modelGeneration = modelBindingGeneration
        val assistant = AssistantRepository.active().id
        val draft = currentDraftField().text.toString()
        val stillCurrent = {
            selectedConversationId == conversationId && conversationSelectionVersion == selection &&
                modelBindingGeneration == modelGeneration && AssistantRepository.active().id == assistant &&
                (runningBranch || (homeState == snapshot && conversationState(conversationId) == snapshot)) &&
                conversationState(conversationId) != null && currentDraftField().text.toString() == draft &&
                (allowActiveSource || (!homeState.isStreaming && !homeState.isPaused)) &&
                !modelPickerState.isChanging && !runtimeRecoveryInProgress.get() && !isCompressionBlockingSend() &&
                !conversationArchiveBusy && !io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance &&
                stoppingRuns.keys.none { runConversationIds[it] == conversationId } &&
                (allowActiveSource || runConversationIds.none { (run, owner) -> owner == conversationId && run in runJobs })
        }
        conversationRevisionBusy = true
        // UNDISPATCHED installs finally even if the owner scope is cancelled before IO starts.
        scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
            try {
                yield()
                val prepared = runInterruptible(Dispatchers.IO) {
                    val archive = io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, conversationId)
                    val ordinary = AgentConversationRevisionReducer.prepareForRevision(snapshot, messageId, archive::restoreHistory)
                    if (ordinary != null || branchBoundary == null) ordinary else {
                        val runtime = branchHistorySnapshotLoader(conversationId, branchBoundary.runId, branchBoundary.snapshotId)
                        if (runtime == null || runtime.id != branchBoundary.snapshotId || runtime.round != branchBoundary.round) null else {
                            val candidate = AgentRunningBranchSnapshot.prepare(snapshot, messageId,
                                branchBoundary.runId, branchBoundary.round, runtime.history, branchBoundary.textBaseline)
                            candidate?.let { AgentConversationRevisionReducer.prepareForRevision(it, messageId, archive::restoreHistory) }
                        }
                    }
                }
                coroutineContext.ensureActive()
                if (!stillCurrent()) return@launch
                if (prepared == null) {
                    showRevisionHistoryUnavailableNotice()
                    return@launch
                }
                publish(conversationId, snapshot, prepared, stillCurrent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                val reason = (failure.cause ?: failure).message.orEmpty().takeIf {
                    it in setOf("分支归档引用层数超限", "分支归档存在循环引用", "分支根归档缺失或无效",
                        "分支归档数量超限", "旧分支归档扫描数量超限", "旧分支归档副本数量超限",
                        "旧分支归档副本不一致", "缺失的旧分支归档没有可验证副本")
                }.orEmpty()
                AndroidAgentLogger.warn("会话修订失败：${if (failure is BranchArchiveCopyException) "branch_archive_copy" else "revision_prepare"} ${failure.cause?.javaClass?.simpleName ?: failure.javaClass.simpleName} $reason")
                if (stillCurrent()) {
                    if (failure is BranchArchiveCopyException) Toast.makeText(appContext,
                        appContext.getString(R.string.appst_branch_archive_copy_failed), Toast.LENGTH_LONG).show()
                    else showRevisionHistoryUnavailableNotice()
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) { conversationRevisionBusy = false }
            }
        }
    }

    fun beginMessageEdit(messageId: String) {
        launchConversationRevision(messageId) { _, snapshot, prepared, _ ->
            val boundary = AgentConversationRevisionReducer.boundary(prepared, messageId) ?: run {
                showRevisionHistoryUnavailableNotice()
                return@launchConversationRevision
            }
            applyPreparedMessageEdit(snapshot, prepared, boundary)
        }
    }

    private fun applyPreparedMessageEdit(
        snapshot: AgentChatHomeUiState,
        prepared: AgentChatHomeUiState,
        boundary: AgentConversationRevisionReducer.Boundary,
    ) {
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            val video = boundary.userMessage.isVideoAt(index)
            PendingImageUi(
                id = "edit-${boundary.userMessage.id}-$index",
                uri = boundary.userMessage.fullImageSourceAt(index),
                dataUrl = dataUrl,
                mimeType = if (video) "video/mp4" else dataUrl.imageMimeType(),
                isVideo = video,
                durationMs = boundary.userMessage.durationMsAt(index),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        // 图片自己的落盘路径也写在消息的文件段里（给模型用）。编辑时它们已经作为图片回填，
        // 再当文件卡片回填会重复，重发时还会发两次。
        val fileReferences = boundary.userMessage.visibleFileReferences(parsedPrompt.references).mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            snapshot.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                pendingConversationMentions = parsedPrompt.conversations.map { mentioned ->
                    PendingConversationMentionUi("mention-${UUID.randomUUID()}", mentioned.id, mentioned.title, "", mentioned.snapshotPath, mentioned.toolsIndexPath)
                },
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = currentDraftField().text.toString(),
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    previousConversationMentions = homeState.pendingConversationMentions,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                    preparedHistory = prepared.history,
                    preparedFromHistory = snapshot.history,
                ),
            )
        )
        conversationDrafts.replace(selectedConversationId, parsedPrompt.request)
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
    }

    fun cancelMessageEdit() {
        if (rejectConversationArchiveMutation()) return
        val edit = homeState.messageEdit ?: return
        conversationDrafts.replace(selectedConversationId, edit.previousInput)
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                pendingConversationMentions = edit.previousConversationMentions,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? {
        // Confirmation is UI-only. A compacted target is validated/restored later on IO.
        val target = homeState.messages.indices.singleOrNull { homeState.messages[it].id == messageId } ?: return null
        val user = (target downTo 0).firstOrNull { homeState.messages[it] is UserMessageUi } ?: return null
        return MessageRevisionImpact(laterTurnCount = homeState.messages.drop(user + 1).count {
            it is UserMessageUi && !it.isSteerSupplement()
        })
    }

    /** 删除确认框：按操作栏分段，统计目标段下面会一起删掉的段数。 */
    fun messageDeleteImpact(messageId: String): MessageRevisionImpact? =
        AgentConversationRevisionReducer.laterSegmentCount(homeState, messageId)?.let { count ->
            MessageRevisionImpact(laterTurnCount = count)
        }

    fun deleteMessageTurn(messageId: String) {
        launchConversationRevision(messageId) { conversationId, snapshot, prepared, _ ->
            val revised = AgentConversationRevisionReducer.deleteFromTurn(prepared, messageId) ?: run {
                showRevisionHistoryUnavailableNotice()
                return@launchConversationRevision
            }
            applyPreparedMessageDeletion(conversationId, snapshot, revised)
        }
    }

    private fun applyPreparedMessageDeletion(
        conversationId: String,
        snapshot: AgentChatHomeUiState,
        revised: AgentChatHomeUiState,
    ) {
        if (revised.messages.isEmpty()) {
            // 调度台是系统会话：删光消息也不移除会话本体，避免侧栏出现「无法再加回来」的缺口。
            if (isDispatchConsoleConversation(conversationId)) {
                updateConversation(conversationId, revised)
                refreshConversationSummaries()
                persistConversations()
                return
            }
            retainDeletedConversation(snapshot.messages, conversationUpdatedAt[conversationId])
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
        conversationCreatedAt = conversationCreatedAt - conversationId
            conversationFolderIds = conversationFolderIds - conversationId
            conversationPinned = conversationPinned - conversationId
            conversationCompletionMarkers = conversationCompletionMarkers - conversationId
            scope.launch(Dispatchers.IO) { chatImageCache.deleteConversation(conversationId) }
            fileAttachmentOwnerVersion += 1
            beginNewSubAgentDraft()
            selectedConversationId = null
            homeState = newDraftChatState()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    private class BranchArchiveCopyException(cause: Exception) : Exception(cause)

    /** Old completed replies may branch while a different run generates, but not that run's partial text. */
    private fun isUnfinishedAssistantBranchTarget(messageId: String): Boolean {
        val target = homeState.messages.singleOrNull { it.id == messageId } as? AgentMessageUi ?: return false
        if (target.isStreaming) return true
        val owner = selectedConversationId ?: return false
        val activeRuns = runConversationIds.filter { (run, conversation) -> conversation == owner && run in runJobs }.keys
        if (!homeState.isStreaming && !homeState.isPaused && activeRuns.isEmpty()) return false
        // A run can finish several text/provider rounds before the reply itself finishes.
        // Branch-copied message IDs retain the execution ID after their conversation prefix.
        val id = target.id.substringAfterLast(':')
        return activeRuns.any { run -> id == "assistant-$run" || id.startsWith("assistant-$run-") }
    }

    fun branchConversation(messageId: String) {
        if (isUnfinishedAssistantBranchTarget(messageId)) return
        launchConversationRevision(messageId, allowActiveSource = true) { sourceId, snapshot, prepared, stillCurrent ->
            val prefix = AgentConversationRevisionReducer.branchPrefix(prepared, messageId) ?: run {
                showRevisionHistoryUnavailableNotice()
                return@launchConversationRevision
            }
            if (prefix.messages.isEmpty()) return@launchConversationRevision
            val newId = newConversationId()
            var published = false
            try {
                runInterruptible(Dispatchers.IO) {
                    // Retained A and its tool/checkpoint closure must be independent of the source.
                    try {
                        io.github.mangi.eta.agent.model.AgentCompactionArchiveFork.copyReferenced(
                            appContext.filesDir, sourceId, newId, prefix.history,
                            rewriteAttachmentPath = { value -> chatImageCache.rewriteCachedPath(value, sourceId, newId) },
                            recoverLegacyDependencies = true,
                        )
                        chatImageCache.copyConversation(sourceId, newId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (failure: Exception) {
                        throw BranchArchiveCopyException(failure)
                    }
                }
                coroutineContext.ensureActive()
                if (!stillCurrent()) return@launchConversationRevision
                publishPreparedBranch(sourceId, newId, snapshot, prefix) { published = true }
            } finally {
                if (!published) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, newId).delete() }
                        .onFailure { AndroidAgentLogger.warn("未发布分支归档清理失败：${it.javaClass.simpleName}") }
                    runCatching { chatImageCache.deleteConversation(newId) }
                        .onFailure { AndroidAgentLogger.warn("未发布分支附件清理失败：${it.javaClass.simpleName}") }
                }
            }
        }
    }

    private fun publishPreparedBranch(
        sourceId: String,
        newId: String,
        snapshot: AgentChatHomeUiState,
        prefix: AgentConversationRevisionReducer.BranchPrefix,
        onPublished: () -> Unit,
    ) {
        val rewrite = { value: String -> chatImageCache.rewriteCachedPath(value, sourceId, newId) }
        val branchMessages = runMessageProjector.interruptRunningTools(
            appContext.getString(R.string.appst_branch_tools_frozen), freezeStreamingMessages(prefix.messages),
        ).map { message ->
            val frozen = message.withId("$newId:${message.id}").rewritePaths(rewrite)
            if (frozen is AgentQuestionMessageUi) AgentQuestionProjection.freezeForBranch(frozen, newId)
            else frozen
        }
        val branched = snapshot.copy(
            messages = branchMessages,
            isWaitingForAnswer = AgentQuestionProjection.hasWaiting(branchMessages),
            history = prefix.history.map { it.rewritePaths(rewrite) },
            input = "",
            isStreaming = false,
            isPaused = false,
            isCompressingContext = false,
            isWaitingForCompression = false,
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
                pendingConversationMentions = emptyList(),
            appliedRuntimeRunIds = emptyList(),
            activeRunContextWindow = null,
            childContexts = emptyList(), childStatusRoster = emptyList(),
            childContextRunId = "", selectedContextTaskId = null,
            messageEdit = null,
            livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
        )
        val sourceTitle = conversationTitles[sourceId].orEmpty().ifBlank {
            appContext.getString(R.string.conversation_unnamed)
        }
        // Preferences may fail durably; finish them before making any branch visible.
        conversationSubAgentPreferences.createConversation(
            SubAgentConfigKey.Conversation(newId), SubAgentConfigKey.Conversation(sourceId),
        )
        conversationsById = conversationsById + (newId to branched)
        conversationTitles = conversationTitles + (
            newId to appContext.getString(R.string.conversation_branch_title, sourceTitle)
        )
        conversationUpdatedAt = conversationUpdatedAt + (newId to System.currentTimeMillis())
        conversationCreatedAt = conversationCreatedAt + (newId to System.currentTimeMillis())
        conversationFolderIds[sourceId]?.let { folderId ->
            conversationFolderIds = conversationFolderIds + (newId to folderId)
        }
        fileAttachmentOwnerVersion += 1
        selectedConversationId = newId
        homeState = branched
        billedOverheadConversationId = newId
        billedOverheadTokens = null
        conversationPaneState = conversationPaneState.copy(selectedConversationId = newId)
        onPublished() // No suspensions between the validated snapshot and this publication boundary.
        refreshConversationSummaries()
        persistConversations()
    }

    fun regenerateMessage(messageId: String) {
        if (rejectConversationArchiveMutation()) return
        regenerateMessage(messageId, ignoreCompression = false)
    }

    private fun regenerateMessage(messageId: String, ignoreCompression: Boolean) {
        if (homeState.isStreaming || homeState.isPaused) return
        if (rejectConversationArchiveMutation()) return
        if (homeState.messageEdit != null) return
        if (!ignoreCompression && rejectSendIfCompressing()) return
        if (rejectSendIfModelUnavailable()) return
        abortActiveRunForRevision()
        val conversationId = selectedConversationId ?: return
        val boundary = AgentConversationRevisionReducer.boundary(homeState, messageId) ?: run {
            showRevisionHistoryUnavailableNotice()
            return
        }
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            val video = boundary.userMessage.isVideoAt(index)
            PendingImageUi(
                id = "regenerate-${boundary.userMessage.id}-$index",
                uri = boundary.userMessage.fullImageSourceAt(index),
                dataUrl = dataUrl,
                mimeType = if (video) "video/mp4" else dataUrl.imageMimeType(),
                isVideo = video,
                durationMs = boundary.userMessage.durationMsAt(index),
            )
        }
        val parsed = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val generateVideo = selectedModelGeneratesVideos()
        val generateImage = !generateVideo && selectedModelGeneratesImages()
        val supportsVision = generateImage || generateVideo || (modelPickerState.selectedModel?.supportsVision == true) || io.github.mangi.eta.agent.model.ModelFeaturePreferences.visionEnabled()
        if (!generateImage && !generateVideo && rejectSendIfContextWindowExceeded(boundary.historyPrefix, parsed.request, images, parsed.references.mapIndexed { index, reference ->
                PendingFileReferenceUi(id = "regen-$index", reference = reference)
            }, parsed.conversations.map { PendingConversationMentionUi(it.id, it.id, it.title, "", it.snapshotPath, it.toolsIndexPath) })) {
            return
        }
        if (!ignoreCompression && boundary.contextWasCompacted) showCompactedRevisionNotice()
        if (images.isNotEmpty()) {
            val ownerState = homeState
            val ownerGeneration = modelBindingGeneration
            val ownerAssistant = AssistantRepository.active().id
            scope.launch(Dispatchers.IO) {
                val extra = if (parsed.references.isEmpty()) {
                    stageChatImages(conversationId, images).filterNotNull()
                } else {
                    emptyList()
                }
                val runtimePrompt = AgentFileReferencePromptCodec.format(
                    parsed.request,
                    parsed.references + extra,
                    parsed.conversations,
                )
                // 重生成回填的 PendingImageUi 只有预览 dataUrl，没有宽高；在 IO 线程只读解码原图边界，
                // 让重新持久化的历史仍带上真实尺寸，后续轮预算不再退化成按字节兜底。
                val stagedDims = if (extra.size == images.size) {
                    images.map { image ->
                        val w = image.widthPx
                        val h = image.heightPx
                        if (w != null && h != null && w > 0 && h > 0) {
                            w to h
                        } else if (image.isVideo) {
                            null
                        } else {
                            AgentImageCodec.boundsOfReference(appContext, image.uri)
                        }
                    }
                } else {
                    emptyList()
                }
                val persisted = extra.ifEmpty { parsed.references }.mapIndexed { index, reference ->
                    val dims = stagedDims.getOrNull(index)
                    AgentConversationCodec.PersistedImage(
                        path = reference.absolutePath,
                        mimeType = mimeTypeForFileName(reference.displayName),
                        displayName = reference.displayName,
                        width = dims?.first,
                        height = dims?.second,
                    )
                }
                withContext(Dispatchers.Main) {
                    if (selectedConversationId != conversationId || homeState != ownerState ||
                        modelBindingGeneration != ownerGeneration || AssistantRepository.active().id != ownerAssistant ||
                        rejectSendIfModelUnavailable()) return@withContext
                    if (homeState.isStreaming || rejectSendIfCompressing()) return@withContext
                    val runId = "run-${UUID.randomUUID()}"
                    // 这次补了落盘路径（旧消息的信封里没有），历史里的用户消息文本随之变化。
                    // 界面里的同一条消息也要改成同样的内容和路径，否则之后编辑、重生成、分支、删除
                    // 都按文本对不上历史，这一轮就改不动了。
                    val retainedMessages = homeState.messages.take(boundary.userMessageIndex + 1)
                    val messages = if (extra.isEmpty()) retainedMessages else {
                        retainedMessages.mapIndexed { index, message ->
                            if (index != boundary.userMessageIndex || message !is UserMessageUi) message
                            else message.copy(
                                content = runtimePrompt,
                                imageSources = if (extra.size == message.images.size) {
                                    extra.map { it.absolutePath }
                                } else message.imageSources,
                            )
                        }
                    }
                    launchConversationRun(
                        conversationId = conversationId,
                        runId = runId,
                        prompt = runtimePrompt,
                        images = if (supportsVision) images else emptyList(),
                        history = boundary.historyPrefix,
                        userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
                            text = runtimePrompt,
                            persistedImages = persisted,
                        ),
                        messages = messages,
                        state = homeState,
                        reasoningEffort = homeState.reasoningEffort,
                        skipAutoCompress = ignoreCompression,
                        logicalTurnId = boundary.logicalTurnId,
                        // 重生成不是用户新发一轮，不按每轮刷新改写标题。
                        refreshTitleAfterTurn = false,
                    )
                }
            }
            return
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = boundary.userMessage.content,
            images = images.toHistoryImages(),
        )
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = boundary.userMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState,
            reasoningEffort = homeState.reasoningEffort,
            skipAutoCompress = ignoreCompression,
            logicalTurnId = boundary.logicalTurnId,
            // 重生成不是用户新发一轮，不按每轮刷新改写标题。
            refreshTitleAfterTurn = false,
        )
    }


    private fun rejectSendIfContextWindowExceeded(
        history: List<AgentModelClient.ConversationMessage>,
        prompt: String,
        images: List<PendingImageUi>,
        fileReferences: List<PendingFileReferenceUi> = emptyList(),
        conversationMentions: List<PendingConversationMentionUi> = homeState.pendingConversationMentions,
    ): Boolean {
        // 普通历史前缀增长、补齐可见 assistant、turnId 元数据差都复用同一次云端实测
        // （contextStateForRequestHistory）；只有编辑/改写历史或换路由才会退化成未实测。
        val requestState = contextStateForRequestHistory(homeState, history)
        val measured = if (homeState.messageEdit == null) validMeasuredContextTokens(requestState) else null
        val usage = compressionContextUsage(
            history = history,
            currentInput = prompt,
            pendingImages = images,
            selectedModel = modelPickerState.selectedModel,
            pendingFileReferences = fileReferences,
            pendingConversationMentions = conversationMentions,
            billedContextTokens = measured,
            requestOverheadTokens = requestOverheadTokens,
            billedOverheadTokens = requestState.cloudRequestOverheadTokens.takeIf { measured != null },
            billedHistoryTokens = requestState.cloudHistoryTokens.takeIf { measured != null },
        )
        // 真未知不能仅凭本地估算的 99% 拦截；known（有效实测）保留原有拦截与 autoCompress 短路。
        if (measured == null || !shouldBlockSendForContextWindow(autoCompressEnabled, usage)) {
            return false
        }
        Toast.makeText(
            appContext,
            appContext.getString(R.string.context_window_send_blocked),
            Toast.LENGTH_LONG,
        ).show()
        return true
    }

    /**
     * 判断是否应自动压缩对话历史。
     */
    private fun coerceKeepRecent(value: Int): Int = AgentContextCompactor.coerceKeepRecent(value)

    private fun keepRecentFor(): Int = AgentContextCompactor.keepRecentFor()

    private fun billedPromptTokens(state: AgentChatHomeUiState): Int? =
        state.livePromptTokens.takeUnless { state.livePromptIsProjected }

    /** Ordinary commits keep the latest actual; edits/truncation open a new receipt epoch. */
    private fun contextStateForRequestHistory(
        state: AgentChatHomeUiState,
        history: List<AgentModelClient.ConversationMessage>,
    ): AgentChatHomeUiState {
        // turnId is local metadata (runtime drain can omit it), not provider request content.
        fun matchesRequestPrefix(prefix: List<AgentModelClient.ConversationMessage>): Boolean =
            prefix.size <= history.size && prefix.indices.all { index ->
                prefix[index].copy(turnId = "") == history[index].copy(turnId = "")
            }
        if (matchesRequestPrefix(state.history)) return state
        // Only the real send reducer's exact visible-assistant commit may complete old text.
        // Arbitrary content startsWith matches would incorrectly accept genuine history edits.
        val committedHistory = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
            state.history, state.messages)
        if (matchesRequestPrefix(committedHistory)) return state
        return state.copy(
            livePromptTokens = null, livePromptIsProjected = false,
            cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
            contextAwaitingReceipt = true, contextHasStarted = true,
            cloudReceiptRequestId = null, cloudRouteSignature = null,
            contextReceiptEvidence = null, receiptPredictionTokens = null,
        )
    }

    /** 预算 seed 与未测豁免共用同一个有效实际回执判定：custom gateway 的真实回执也能继续
     * 作为同一路由/模型的预算锚点，不再被已退场的 learning 签名拦成“未测”。 */
    private fun budgetReceiptTokens(state: AgentChatHomeUiState): Int? =
        (billedPromptTokens(state) ?: state.contextBudgetReceiptTokens)
            .takeIf { hasEffectiveContextReceipt(state) }

    private fun compressionContextWindow(fallback: Int? = null): Int? =
        modelPickerState.selectedModel?.contextWindow?.takeIf { it > 0 }
            ?: fallback?.takeIf { it > 0 }

    /** Window of the model the conversation is bound to; the picker may show another model. */
    private fun boundCompressionWindow(state: AgentChatHomeUiState): Int? =
        AgentModelPickerProjector.project(selectionProviders, state.providerId, state.modelId).selectedModel
            ?.takeIf { it.providerId == state.providerId && it.id == state.modelId }?.contextWindow?.takeIf { it > 0 }
            ?: compressionContextWindow()

    /** Same fallback AgentLoop uses when a model has no configured window. */
    private fun compressionBoundaryWindow(window: Int?): Int =
        window?.takeIf { it > 0 } ?: io.github.mangi.eta.agent.model.AgentLoop.CompactPolicy.Disabled.contextWindow

    private fun shouldAutoCompress(
        history: List<AgentModelClient.ConversationMessage>,
        contextWindow: Int?,
        estimatedTokens: Int?,
    ): Boolean {
        if (!autoCompressPreference.enabled) return false
        val window = contextWindow?.takeIf { it > 0 } ?: return false
        return AgentContextCompactor.shouldCompress(
            history = history,
            contextWindow = window,
            keepRecentMessages = keepRecentFor(),
            estimatedTokens = estimatedTokens,
        )
    }

    /**
     * 尝试压缩对话历史，失败时回退原历史。
     */
    private suspend fun tryCompressHistory(
        history: List<AgentModelClient.ConversationMessage>,
        compressModelConfig: AgentModelClient.ModelConfig?,
        keepRecent: Int? = null,
        conversationId: String? = null,
        contextWindow: Int? = null,
        billedTokens: Int? = null,
        localTokens: Int? = null,
        sourceModelConfig: AgentModelClient.ModelConfig? = null,
        /** 仅当本次确实产生了可用的摘要并已 ready 成功时触发；prune-only/摘要失败不触发。 */
        onSummaryCommitted: (() -> Unit)? = null,
    ): List<AgentModelClient.ConversationMessage> {
        val resolvedKeepRecent = keepRecent ?: keepRecentFor()
        val archive = conversationId?.let {
            io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, it)
        }
        val window = compressionBoundaryWindow(contextWindow)
        val config = AgentContextCompactor.Config(
            keepRecentMessages = coerceKeepRecent(resolvedKeepRecent),
            compressModelConfig = compressModelConfig,
            compactionArchive = archive,
            usageConversationId = conversationId,
            sourceModelConfig = sourceModelConfig?.copy(contextWindow = window),
            summaryTokenBudget = io.github.mangi.eta.agent.model.AgentCompressionBoundary.summaryProgressBudget(window),
        )
        return try {
            var summaryFailure: String? = null
            val compressed = runInterruptible {
                // billed/local lets the 16% tail target the provider's bill, as in AgentLoop.
                val initialCut = io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(
                    history, window, billedTokens = billedTokens, localTokens = localTokens,
                    opaqueItems = config::replayedOpaqueItems)
                val working = AgentContextCompactor.pruneOversizedToolResults(history, archive, initialCut)
                val cut = io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(
                    working, window, billedTokens = billedTokens, localTokens = localTokens,
                    opaqueItems = config::replayedOpaqueItems)
                if (cut <= 0 || cut >= working.size) return@runInterruptible working
                val boundArchive = archive ?: error("缺少会话身份，无法保存压缩原文")
                val prefix = working.take(cut)
                val id = boundArchive.savePreservingRevisionChain(prefix)
                boundArchive.record(id, "started")
                boundArchive.canAttach(id, prefix.size.coerceAtLeast(working.size - cut + 1), working.size - cut)
                try {
                    val summary = AgentContextCompactor.compress(
                        working,
                        config.copy(compactionArchive = null),
                        keepStartOverride = cut,
                    )
                    val result = boundArchive.attachReferences(prefix, id, summary, working.size - cut)
                    require(io.github.mangi.eta.agent.model.AgentCompressionBoundary.compactionReduced(
                        history, result, working.size - cut, config.progressBudget(),
                        countsOpaque = { config.replayedOpaqueItems(it) > 0 },
                    )) { "摘要及索引未缩小上下文" }
                    // 压缩边界标记：被压缩前缀里出现过的延迟组写入一条 system 标记，
                    // 跨 run 恢复不依赖被压缩掉的工具名。此处拿不到 MCP 快照，
                    // 动态组由 AgentLoop 内压缩路径负责（两者共用同一标记格式）。
                    val markerGroups = io.github.mangi.eta.agent.model.AgentToolGroups.deferredGroupsOf(
                        io.github.mangi.eta.agent.model.AgentToolGroups
                            .toolNamesFromToolCallsJson(prefix.map { it.toolCallsJson }),
                    )
                    val committed = io.github.mangi.eta.agent.model.AgentToolGroups
                        .openedGroupsMarker(markerGroups)
                        ?.let { content -> result + AgentModelClient.ConversationMessage(role = "system", content = content) }
                        ?: result
                    boundArchive.record(id, "ready")
                    // attachReferences + compactionReduced + ready 全部成功之后才宣告摘要提交成功。
                    onSummaryCommitted?.invoke()
                    committed
                } catch (failure: Exception) {
                    runCatching { boundArchive.record(id, "failed") }
                    // Never turn stop/coroutine cancellation into pruning-only success.
                    if (failure is CancellationException || failure is InterruptedException ||
                        failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException ||
                        Thread.currentThread().isInterrupted) throw failure
                    if (working.sumOf { AgentContextBudget.countMessage(it).toLong() } < history.sumOf { AgentContextBudget.countMessage(it).toLong() }) {
                        summaryFailure = failure.message ?: localizedText(R.string.appst_summary_failed, "摘要失败")
                        AndroidAgentLogger.warn("摘要失败，已保留工具修剪结果：${failure.javaClass.simpleName}: ${failure.message}")
                        return@runInterruptible working
                    }
                    throw failure
                }
            }
            if (summaryFailure != null) withContext(Dispatchers.Main) {
                if (conversationId == selectedConversationId) Toast.makeText(appContext,
                    appContext.getString(R.string.appst_compress_conversation_failed, summaryFailure), Toast.LENGTH_LONG).show()
            }
            compressed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (Thread.currentThread().isInterrupted || failure is InterruptedException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) {
                throw CancellationException("摘要已取消").also { it.initCause(failure) }
            }
            AndroidAgentLogger.warn("压缩失败，保留原文：${failure.javaClass.simpleName}: ${failure.message}")
            withContext(Dispatchers.Main) {
                if (conversationId == selectedConversationId) Toast.makeText(appContext,
                    failure.message ?: appContext.getString(R.string.appst_compress_failed_history_kept), Toast.LENGTH_LONG).show()
            }
            history
        }
    }

    private suspend fun resolveCompressModelConfig(
        fallback: AgentModelClient.ModelConfig?,
    ): AgentModelClient.ModelConfig? {
        val prefs = Prefs.localAgentPreferences()
        val customEnabled = Prefs.isCustomCompressModelEnabled(prefs)
        val resolvedProviderId = prefs?.takeIf { customEnabled }
            ?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null)
        val resolvedModelId = prefs?.takeIf { customEnabled }
            ?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, null)
        val resolved = if (resolvedProviderId.isNullOrBlank() || resolvedModelId.isNullOrBlank()) {
            fallback
        } else {
            val assistant = fallback?.assistantId?.takeIf { it.isNotBlank() }?.let {
                runCatching { AssistantRepository.currentProfile(it) }.getOrNull()
            }
            RuntimeConfigRepository.configForProviderAndModel(resolvedProviderId, resolvedModelId, assistant)
                ?.let { model ->
                    fallback?.let { source ->
                        model.copy(assistantId = source.assistantId, systemPrompt = source.systemPrompt)
                    } ?: model
                }
                ?: fallback
        }
        val compressed = resolved?.let(AgentRuntimePolicy::forCompression) ?: return null
        return AgentCompressionEndpoint.apply(compressed, prefs?.getString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, null)).copy(
            errorReconnectPolicy = fallback?.errorReconnectPolicy
                ?: SettingsDataStore.settings().errorReconnectPolicy.persistedValue,
        )
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
        skipAutoCompress: Boolean = false,
        logicalTurnId: String = runId,
        consumeDraft: Boolean = false,
        /** 重生成/失败重试/压缩后续写不是「用户新发一轮」，关闭后不按每轮刷新登记标题。 */
        refreshTitleAfterTurn: Boolean = true,
    ) {
        val preparedAt = SystemClock.elapsedRealtime()
        val runProvider = selectionProviders.singleOrNull { it.id == state.providerId }?.takeIf { it.isEnabled }
        val runModel = runProvider?.models?.singleOrNull { it.id == state.modelId }?.takeIf { it.isEnabled }
        if (runProvider == null || runModel == null) {
            Toast.makeText(appContext, appContext.getString(R.string.appst_bound_model_unavailable), Toast.LENGTH_LONG).show()
            return
        }
        if (runModel.supportsSpeechSynthesis) {
            Toast.makeText(appContext, appContext.getString(R.string.appst_tts_model_chat_unsupported), Toast.LENGTH_LONG).show()
            return
        }
        if (consumeDraft) conversationDrafts.replace(conversationId, "")
        val runAssistant = requestOverheadAssistant(state)
        // Resolve and freeze the remembered mode for THIS submitted binding before any coroutine.
        // Later UI toggles must not mutate an in-flight request or affect a different model.
        val runConfig = GptSpeedModePolicy.snapshot(
            RuntimeConfigRepository.buildRuntimeConfig(runProvider, runModel, runAssistant),
            rememberedGptSpeedMode(state.providerId, state.modelId),
            supportsGptSpeedBinding(runProvider, runModel),
        )
        val runModelOption = AgentModelPickerProjector.project(listOf(runProvider), runProvider.id, runModel.id).selectedModel
        val runProviders = selectionProviders.toList()
        val taggedUserHistoryMessage = userHistoryMessage.copy(turnId = logicalTurnId)
        bindUsageRun(runId, conversationId)
        runConfig.contextWindow?.takeIf { it > 0 }?.let { runContextWindows[runId] = it }
        val generateVideo = runModel.supportsVideoGeneration
        val generateImage = !generateVideo && runModel.supportsImageGeneration
        val mediaController = if (generateImage || generateVideo) {
            imageGenerationRunIds += runId
            directMediaRuns.start(runId)
        } else null

        // 自动压缩只看圆环：同一段历史的云端实测。没有实测（首次请求、压缩后、换模型）不压缩。
        val willCompress = !generateImage && !generateVideo && !skipAutoCompress && shouldAutoCompress(
            history = history,
            contextWindow = runConfig.contextWindow,
            estimatedTokens = if (history == state.history) billedPromptTokens(state) else null,
        )
        val historyRewritten = history != state.history
        val relatedHistory = history == state.history.take(history.size) || state.history == history.take(state.history.size)
        // 同一有效实际回执判定：custom gateway 的真实回执同样可以作为压缩/预算锚点。
        val validReceiptRoute = hasEffectiveContextReceipt(state)
        val contextState = contextStateForRequestHistory(state, history)
        val runMessages = if (generateImage || generateVideo) {
            messages + AgentMessageUi(
                id = "assistant-$runId-1",
                content = "",
                isStreaming = true,
            )
        } else {
            messages
        }
        updateConversation(
            conversationId,
            contextState.copy(
                isStreaming = true,
                // 运行身份交给回复自动朗读区分「看着开跑」与「切进来接到半截」。
                activeRunId = runId,
                isPaused = false,
                // Keep the latest actual until a new valid usage replaces it. Estimates are only
                // for contexts without a cloud receipt, never a delta replacement for an actual.
                receiptPredictionTokens = null,
                isCompressingContext = willCompress,
                history = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(history) + taggedUserHistoryMessage,
                messages = runMessages,
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        // Write-ahead only needs this turn's conversation durable. Other loaded conversations
        // keep their content in memory and are written by their own saves; metadata still goes
        // through the same serialized snapshot so pending saves cannot be reordered.
        val initialPersistence = persistConversations(contentConversationIds = setOf(conversationId))

        val preparationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                ConversationReasoningPolicy.resolve(reasoningEffort, runConfig.reasoningCapabilities)
            } else {
                ReasoningEffort.OFF
            }
            val config = runConfig.copy(
                errorReconnectPolicy = SettingsDataStore.settings().errorReconnectPolicy.persistedValue,
                terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                screenTools = agentBooleanForUi(Prefs.Keys.AGENT_SCREEN_TOOLS),
                deviceTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_TOOLS),
                personalTools = agentBooleanForUi(Prefs.Keys.AGENT_PERSONAL_TOOLS),
                thinkingEnabled = permittedReasoningEffort.enablesReasoning,
                reasoningEffort = permittedReasoningEffort,
            )
            // The display estimate duplicates prompt/tool assembly the runtime also performs.
            // Run it beside write-ahead persistence instead of after it; the run still waits for
            // both so compression and the context ring see this turn's overhead.
            val overheadDeferred = if (mediaController == null) async(Dispatchers.IO) {
                try {
                    estimateRequestOverhead(config, runAssistant, SubAgentConfigKey.Conversation(conversationId), runProviders)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } else null
            // write-ahead：用户消息未提交前不把可能产生副作用的 run 交给 Runtime。
            if (!initialPersistence.await()) {
                overheadDeferred?.cancel()
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = lastConversationPersistenceError
                                ?.takeIf { it.isNotBlank() }
                                ?.let { "${appContext.getString(R.string.conversation_persistence_failed)}（$it）" }
                                ?: appContext.getString(R.string.conversation_persistence_failed),
                        )
                    )
                }
                return@launch
            }
            if (stoppingRuns.containsKey(runId)) {
                overheadDeferred?.cancel()
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止"))
                }
                return@launch
            }
            if (mediaController != null) {
                directMediaRuns.execute(mediaController) {
                    if (generateVideo) executeVideoGeneration(runId, conversationId, config, prompt, images, mediaController)
                    else executeImageGeneration(runId, conversationId, config, prompt, images, mediaController)
                }
                return@launch
            }
            // Recompute from THIS run's frozen model/assistant, never a positive global UI value.
            val runOverhead = overheadDeferred?.await()
            withContext(Dispatchers.Main) {
                if (runOverhead != null && runId in runJobs && !stoppingRuns.containsKey(runId)) {
                    runOverheadTokens[runId] = runOverhead
                }
            }
            val supportsVideo = config.supportsVideo
            // 附件在发请求前从原始引用重编码成「尽量无损」档（无损 WebP 1920，超预算按阶梯降级），
            // 不再把聊天里的 1024 有损预览发给模型。视频、编码失败或旧草稿仍回退预览 dataUrl。
            val modelImages = images.map { pending ->
                pending.toRequestModelImage(appContext, supportsVideo).copy(source = "user_attach")
            }
            val compressModelConfig = resolveCompressModelConfig(config)
            val billedForCompression = if (!historyRewritten && validReceiptRoute) billedPromptTokens(state) else null
            // Related same-route history may reuse an old receipt as a DELTA anchor, never as a new actual.
            // It does not become an 80% auto-compression trigger for a rewritten request.
            val receiptAnchorForDelta = budgetReceiptTokens(state).takeIf {
                validReceiptRoute && relatedHistory && !state.contextAwaitingReceipt
            }
            val calibratedForCompression = receiptAnchorForDelta != null && receiptAnchorForDelta > 0 &&
                runOverhead != null && state.cloudHistoryTokens != null && state.cloudRequestOverheadTokens != null
            val estimatedTokens = runOverhead?.let { overhead ->
                compressionContextUsage(
                    history = history,
                    currentInput = prompt,
                    pendingImages = images,
                    selectedModel = runModelOption,
                    billedContextTokens = receiptAnchorForDelta,
                    requestOverheadTokens = overhead,
                    billedOverheadTokens = state.cloudRequestOverheadTokens,
                    billedHistoryTokens = state.cloudHistoryTokens,
                ).contextTokens
            }
            // Same request in local units, so the retained tail can be scaled to the bill.
            // An unknown overhead cannot establish a calibration or overwrite a valid receipt.
            val localForCompression = runOverhead?.takeIf { billedForCompression != null }?.let { overhead ->
                compressionContextUsage(history = history, currentInput = prompt, pendingImages = images,
                    selectedModel = runModelOption, requestOverheadTokens = overhead).contextTokens
            }
            val pendingInRunCompact = withContext(Dispatchers.Main) {
                pendingInRunCompactConversationIds.remove(conversationId)
            }
            val shouldCompress = !skipAutoCompress && (
                pendingInRunCompact ||
                    shouldAutoCompress(
                        history,
                        config.contextWindow,
                        // 与圆环同一个数；estimatedTokens 只用于压缩时按账单缩放保留尾部。
                        billedForCompression,
                    )
            )
            if (shouldCompress != willCompress) {
                withContext(Dispatchers.Main) {
                    setConversationCompressing(conversationId, shouldCompress)
                }
            }
            var summaryCommitted = false
            val historyToSend = if (shouldCompress) {
                val compressed = tryCompressHistory(
                    history = history,
                    compressModelConfig = compressModelConfig,
                    sourceModelConfig = config,
                    conversationId = conversationId,
                    contextWindow = config.contextWindow,
                    billedTokens = estimatedTokens.takeIf { billedForCompression != null },
                    localTokens = localForCompression,
                    onSummaryCommitted = { summaryCommitted = true },
                )
                withContext(Dispatchers.Main) {
                    applyCompressedHistoryToConversation(
                        conversationId = conversationId,
                        originalHistory = history,
                        compressedHistory = compressed,
                        userHistoryMessage = taggedUserHistoryMessage,
                        compressorLabel = compressorLabel(compressModelConfig),
                        summaryCommitted = summaryCommitted,
                    )
                    setConversationCompressing(conversationId, false)
                }
                compressed
            } else {
                history
            }
            // 只有摘要真正成功才算“已预压缩”：prune-only/摘要失败返回 working 时历史也可能不等，
            // 不能拿 history != historyToSend 冒充压缩成功。
            val compactedBeforeSend = shouldCompress && summaryCommitted
            // Pre-send compaction can outlive the 20s stop seal: the watchdog then settles the
            // run and drops it from runJobs. Re-check before handing anything to Runtime, or a
            // stopped run would start executing tools with no UI left to stop it.
            val stillWanted = withContext(Dispatchers.Main.immediate) {
                when {
                    stoppingRuns.containsKey(runId) -> {
                        applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止"))
                        false
                    }
                    runId !in runJobs -> false
                    else -> true
                }
            }
            if (!stillWanted) return@launch
            // Diagnostics only: numeric snapshot of the legacy basis the calibration is measured in.
            // Never on Main, never allowed to interrupt the send path.
            if (AppFileLogger.isEnabled()) {
                try {
                    val snapshotOverhead = runOverhead ?: 0
                    val hasReceiptDelta = !compactedBeforeSend && calibratedForCompression
                    val rawHistory = (historyToSend.sumOf { AgentContextBudget.countMessage(it).toLong() } +
                        AgentContextBudget.countCurrentTurn(prompt, modelImages))
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val estimate = if (hasReceiptDelta) estimatedTokens ?: 0 else
                        (rawHistory.toLong() + snapshotOverhead).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    val uiActual = billedPromptTokens(contextState).takeIf {
                        !compactedBeforeSend && contextState.cloudRouteSignature != null &&
                            contextState.cloudRouteSignature == contextRouteSignature(contextState)
                    }
                    val uiTokens = if (contextState.contextAwaitingReceipt) null else uiActual
                    contextEstimateDiagnostics.capture(runId, ContextEstimateDiagnostics.Snapshot(
                        basis = if (hasReceiptDelta) ContextEstimateDiagnostics.Basis.RECEIPT_DELTA
                            else ContextEstimateDiagnostics.Basis.LOCAL_FALLBACK,
                        localEstimateTokens = estimate, overheadTokensEst = snapshotOverhead, historyTokensEst = rawHistory,
                        uiTokens = uiTokens,
                        uiState = when {
                            uiActual != null -> ContextEstimateDiagnostics.UiState.ACTUAL
                            !state.contextHasStarted -> ContextEstimateDiagnostics.UiState.NONE
                            else -> ContextEstimateDiagnostics.UiState.UNKNOWN
                        },
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A diagnostic failure must never block the run.
                }
            }
            // Pairs with AgentRunTiming inside the runtime: this covers persistence,
            // compression and stop re-checks before the request leaves the UI process.
            AndroidAgentLogger.debug {
                "Agent UI handoff prepared: elapsed_ms=${SystemClock.elapsedRealtime() - preparedAt}, " +
                    "compacted=$compactedBeforeSend, history=${historyToSend.size}, images=${modelImages.size}"
            }
            val result = runInterruptible {
                AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                    request = AgentRuntimeWire.RunRequest(
                        runId = runId,
                        turnId = logicalTurnId,
                        assistantId = runAssistant.id,
                        prompt = prompt,
                        config = config,
                        images = modelImages,
                        history = historyToSend,
                        // UI owns context via auto-compress / 99% send block; Runtime must not trimHistory.
                        historyAlreadyCompacted = true,
                        // 只在估算确实以同一段历史的云端回执为底、且没有真正压缩掉这段历史时传。
                        // 摘要尝试失败/仅 prune 不清掉仍然有效的旧锚点。
                        calibratedInputTokens = estimatedTokens?.takeIf {
                            !compactedBeforeSend && calibratedForCompression && it > 0
                        },
                        // 只有实际未知（本次请求没有有效实测）或摘要真正提交过才放行；
                        // known 一律沿用既有校准锚点，不免除 Runtime 的本地硬限。
                        allowUnmeasuredContextSend = unmeasuredContextSendAllowed(contextState, compactedBeforeSend),
                        modelSessionId = conversationId,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                    isStopRequested = { stoppingRuns.containsKey(runId) },
                    mainStopReason = { mainStopReasons[runId] ?: AgentChildControlPolicy.Reason.USER_STOP },
                )
            }
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
        runJobs[runId] = preparationJob
        if (!RootAccess.isGranted) {
            val leaseId = "prepare:$runId"
            val acquired = AgentExecutionService.acquire(appContext, leaseId) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (runId in runJobs) stopRun(runId)
                }
            }
            if (!acquired) {
                preparationJob.cancel()
                applyRunResult(runId, AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = appContext.getString(R.string.capability_background_failed),
                ))
                return
            }
            preparationJob.invokeOnCompletion { AgentExecutionService.release(leaseId) }
        }
        if (mediaController != null) preparationJob.invokeOnCompletion { directMediaRuns.cancel(runId) }
        preparationJob.start()
        val followTitle = refreshTitleAfterTurn &&
            io.github.mangi.eta.agent.model.ModelFeaturePreferences.titleFollowsConversation()
        if (!generateImage && !generateVideo && (history.isEmpty() || followTitle)) {
            // 标题不在这里生成：等本轮回复结束后，把本轮用户问题与助手最终回答一起交给标题模型。
            // 首轮用「问题 + 首轮回答」定初始标题；开关打开后，后续每轮都带当前标题再刷一遍，
            // 主题没有明显变化时标题模型会原样返回，落库前还会做同值判断。
            registerPendingConversationTitle(
                conversationId = conversationId,
                state = state,
                prompt = prompt,
                firstMessageId = messages.lastOrNull()?.id,
                follow = !history.isEmpty(),
            )
        }
    }

    /** 标题请求登记：首轮一次性生成，或每轮刷新。 */
    private class PendingConversationTitle(
        val question: String,
        val firstMessageId: String,
        val expectedTitle: String,
        val providerId: String,
        val modelId: String,
        /** 该会话标题请求的单调序号，用于「最新一轮胜出」。 */
        val seq: Int,
        /** true = 每轮刷新（后续轮）；false = 首轮一次性生成，沿用旧回写守卫。 */
        val follow: Boolean,
    )

    private val pendingConversationTitles = mutableMapOf<String, PendingConversationTitle>()

    /**
     * 每个会话的标题请求单调序号：登记时自增并写进 pending，回写前要求仍是该序号，
     * 避免上一轮晚到的结果覆盖本轮更新。只在主线程读写（登记在主线程，回写在 Main 协程）。
     */
    private val titleRequestSeq = mutableMapOf<String, Int>()

    private fun registerPendingConversationTitle(
        conversationId: String,
        state: AgentChatHomeUiState,
        prompt: String,
        firstMessageId: String?,
        follow: Boolean,
    ) {
        if (firstMessageId == null || conversationId in manuallyRenamedDuringTitleRequest) return
        val expectedTitle = conversationTitles[conversationId] ?: return
        // Only send the visible question, never hidden tool snapshots, file bytes or system prompts.
        val question = AgentFileReferencePromptCodec.parse(prompt).request.ifBlank { expectedTitle }
        val seq = (titleRequestSeq[conversationId] ?: 0) + 1
        titleRequestSeq[conversationId] = seq
        // 只保留标题模型回退需要的绑定信息，不持有整个会话快照。
        pendingConversationTitles[conversationId] = PendingConversationTitle(
            question = question,
            firstMessageId = firstMessageId,
            expectedTitle = expectedTitle,
            providerId = state.providerId,
            modelId = state.modelId,
            seq = seq,
            follow = follow,
        )
    }

    /**
     * 本轮回复收尾后生成标题：[answer] 是本轮 run 自己的最终正文，为空的场景见 [runTitleAnswer]。
     * 首轮失败/被停止而没有回答时，退化为仅依据问题生成；每轮刷新模式则直接跳过。
     */
    private fun consumePendingConversationTitle(conversationId: String, answer: String) {
        val pending = pendingConversationTitles.remove(conversationId) ?: return
        // 每轮刷新只在真的产生了本轮回复时才调标题模型，避免拿空回答或上一轮旧内容生成垃圾标题。
        if (pending.follow && answer.isBlank()) return
        scope.launch {
            try {
                val title = withContext(Dispatchers.IO) {
                    val current = runtimeConfigForBoundModel(pending.providerId, pending.modelId)
                        ?: return@withContext null
                    val config = io.github.mangi.eta.agent.model.ConversationTitleModel.resolve(
                        io.github.mangi.eta.agent.model.ModelFeaturePreferences.selection(
                            io.github.mangi.eta.agent.model.ModelFeature.TITLE),
                        current)
                    kotlinx.coroutines.runInterruptible {
                        io.github.mangi.eta.agent.model.ConversationTitleModel.generate(
                            config = config,
                            question = pending.question,
                            firstAnswer = answer,
                            controller = io.github.mangi.eta.agent.runtime.AgentRunController(),
                            conversationId = conversationId,
                            previousTitle = if (pending.follow) pending.expectedTitle else "",
                        )
                    }
                } ?: return@launch
                if (pending.follow &&
                    !io.github.mangi.eta.agent.model.ModelFeaturePreferences.titleFollowsConversation()) {
                    // 请求在飞时用户关掉了开关：本轮结果作废。
                    return@launch
                }
                val current = conversationState(conversationId)
                val applied = if (pending.follow) {
                    io.github.mangi.eta.agent.model.ConversationTitleModel.mayApplyRefresh(
                        exists = current != null,
                        renamed = conversationId in manuallyRenamedDuringTitleRequest,
                        currentTitle = conversationTitles[conversationId],
                        expected = pending.expectedTitle,
                        latestRequest = titleRequestSeq[conversationId] == pending.seq,
                    )
                } else {
                    io.github.mangi.eta.agent.model.ConversationTitleModel.mayApply(
                        current != null, conversationId in manuallyRenamedDuringTitleRequest,
                        conversationTitles[conversationId], pending.expectedTitle,
                        current?.messages?.firstOrNull()?.id, pending.firstMessageId)
                }
                if (conversationArchiveBusy || !applied) return@launch
                // 主题未变时模型会原样返回当前标题：不写内存、不刷新摘要、不落库。
                if (title == pending.expectedTitle) return@launch
                conversationTitles = conversationTitles + (conversationId to title)
                refreshConversationSummaries()
                persistConversations()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Title requests are optional: preserve the local title and the chat run.
                AndroidAgentLogger.warn("标题生成失败，保留本地标题")
            }
        }
    }

    /**
     * 只取本轮 run 自己产生的最终正文。失败/被停止且本轮没有任何输出时返回空串，
     * 绝不回退到上一轮回答——否则标题模型会把过期内容当成本轮素材。
     * 本轮 UI 投影缺失时用 Runtime 的最终文本兜底。
     */
    private fun runTitleAnswer(
        conversationId: String,
        runId: String,
        result: AgentRuntimeWire.RunResult,
    ): String {
        val prefix = assistantMessagePrefix(runId)
        val fromUi = conversationState(conversationId)?.messages
            ?.filterIsInstance<AgentMessageUi>()
            ?.lastOrNull { it.id.startsWith(prefix) && it.content.isNotBlank() }
            ?.content
            .orEmpty()
        return fromUi.ifBlank { result.content }
    }

    private fun selectedModelGeneratesImages(): Boolean =
        modelPickerState.selectedModel?.supportsImageGeneration == true

    private fun selectedModelGeneratesVideos(): Boolean =
        modelPickerState.selectedModel?.supportsVideoGeneration == true

    private suspend fun executeImageGeneration(
        runId: String,
        conversationId: String,
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<PendingImageUi>,
        controller: io.github.mangi.eta.agent.runtime.AgentRunController,
    ) {
        try {
            controller.throwIfCancelled()
            val apiPrompt = AgentFileReferencePromptCodec.parse(prompt).request.trim()
            if (apiPrompt.isBlank()) {
                error(appContext.getString(R.string.chat_image_prompt_required))
            }
            if (config.providerType == ProviderTypes.ANTHROPIC) {
                error(appContext.getString(R.string.chat_image_generation_unsupported))
            }
            val inputImages = images.filter { !it.isVideo }.mapNotNull { image ->
                val bytes = AgentChatImageCache.readBytes(image.uri)
                    ?: AgentChatImageCache.readBytes(image.dataUrl)
                    ?: return@mapNotNull null
                AgentImageGenerationClient.InputImage(
                    bytes = bytes,
                    mimeType = image.mimeType.ifBlank { "image/jpeg" },
                )
            }
            val generated = AgentImageGenerationClient(runController = controller).generate(
                config = config,
                prompt = apiPrompt,
                images = inputImages,
            )
            if (generated.images.isEmpty()) {
                error(appContext.getString(R.string.chat_image_generation_empty))
            }
            controller.throwIfCancelled()
            val staged = generated.images.mapIndexedNotNull { index, image ->
                controller.throwIfCancelled()
                val ext = AgentImageGenerationParser.extensionForMime(image.mimeType)
                chatImageCache.stage(conversationId, image.bytes, "generated-${index + 1}.$ext")
            }
            if (staged.isEmpty()) {
                error(appContext.getString(R.string.chat_image_generation_empty))
            }
            val markdown = AgentImageGenerationParser.markdown(
                paths = staged.map { it.absolutePath },
                text = generated.text,
            )
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) finishImageGenerationRun(runId, markdown)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (controller.isCancelled) return
            val message = failure.message?.trim().orEmpty().ifBlank {
                appContext.getString(R.string.chat_image_generation_empty)
            }
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) failImageGenerationRun(runId, message)
            }
        }
    }

    private suspend fun executeVideoGeneration(
        runId: String,
        conversationId: String,
        config: AgentModelClient.ModelConfig,
        prompt: String,
        images: List<PendingImageUi>,
        controller: io.github.mangi.eta.agent.runtime.AgentRunController,
    ) {
        try {
            controller.throwIfCancelled()
            val apiPrompt = AgentFileReferencePromptCodec.parse(prompt).request.trim()
            if (apiPrompt.isBlank()) {
                error(appContext.getString(R.string.chat_video_prompt_required))
            }
            if (config.providerType == ProviderTypes.ANTHROPIC) {
                error(appContext.getString(R.string.chat_video_generation_unsupported))
            }
            val inputImages = images.filter { !it.isVideo }.mapNotNull { image ->
                val bytes = AgentChatImageCache.readBytes(image.uri)
                    ?: AgentChatImageCache.readBytes(image.dataUrl)
                    ?: return@mapNotNull null
                AgentVideoGenerationClient.InputImage(
                    bytes = bytes,
                    mimeType = image.mimeType.ifBlank { "image/jpeg" },
                )
            }
            val generated = AgentVideoGenerationClient(runController = controller).generate(
                config = config,
                prompt = apiPrompt,
                images = inputImages,
            )
            if (generated.videos.isEmpty()) {
                error(appContext.getString(R.string.chat_video_generation_empty))
            }
            controller.throwIfCancelled()
            val staged = generated.videos.mapIndexedNotNull { index, video ->
                controller.throwIfCancelled()
                val ext = AgentVideoGenerationParser.extensionForMime(video.mimeType)
                chatImageCache.stage(
                    conversationId,
                    video.bytes,
                    "generated-${index + 1}.$ext",
                    maxBytes = AgentVideoGenerationClient.MAX_GENERATED_VIDEO_BYTES,
                )
            }
            if (staged.isEmpty()) {
                error(appContext.getString(R.string.chat_video_generation_empty))
            }
            val markdown = AgentVideoGenerationParser.markdown(
                paths = staged.map { it.absolutePath },
                text = generated.text,
            )
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) finishImageGenerationRun(runId, markdown)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (controller.isCancelled) return
            val message = failure.message?.trim().orEmpty().ifBlank {
                appContext.getString(R.string.chat_video_generation_empty)
            }
            withContext(Dispatchers.Main) {
                if (!controller.isCancelled) failImageGenerationRun(runId, message)
            }
        }
    }

    private fun finishImageGenerationRun(runId: String, content: String) {
        if (runId !in imageGenerationRunIds) return
        runJobs.remove(runId)
        directMediaRuns.cancel(runId)
        imageGenerationRunIds.remove(runId)
        completeLatestAssistantMessage(runId, content, generatedAtMillis = System.currentTimeMillis())
        snapshotPartialAssistantToHistory(runId)
        setConversationStreaming(runId, false)
        val conversationId = conversationIdForRun(runId)
        runGeneratedAtMillis.remove(runId)
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
        runCompressedDuringRun.remove(runId)
        refreshConversationSummaries()
        persistConversations()
        if (conversationId != null) {
            onConversationRunSettled(conversationId)
        }
    }

    private fun failImageGenerationRun(runId: String, error: String) {
        if (runId !in imageGenerationRunIds) return
        runJobs.remove(runId)
        directMediaRuns.cancel(runId)
        imageGenerationRunIds.remove(runId)
        replaceLatestAssistantWithNotice(runId, SystemNoticeCode.RuntimeFailed, error)
        setConversationStreaming(runId, false)
        val conversationId = conversationIdForRun(runId)
        runGeneratedAtMillis.remove(runId)
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
        runCompressedDuringRun.remove(runId)
        refreshConversationSummaries()
        persistConversations()
        if (conversationId != null) {
            onConversationRunSettled(conversationId)
        }
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> {
        val supportsVideo = modelPickerState.selectedModel?.supportsVideo == true
        return map { it.toOutboundModelImage(supportsVideo) }
    }

    private fun mimeTypeForFileName(name: String): String = when {
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".webp", ignoreCase = true) -> "image/webp"
        name.endsWith(".gif", ignoreCase = true) -> "image/gif"
        name.endsWith(".mp4", ignoreCase = true) -> "video/mp4"
        name.endsWith(".webm", ignoreCase = true) -> "video/webm"
        name.endsWith(".mov", ignoreCase = true) -> "video/quicktime"
        name.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
        name.endsWith(".3gp", ignoreCase = true) -> "video/3gpp"
        else -> "image/jpeg"
    }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request.ifBlank { parsed.conversations.firstOrNull()?.let { "@${it.title}" }.orEmpty() }, parsed.references)
    }

    /**
     * 压缩成功后只补丁当前会话 history，避免用发送前快照覆盖 isStreaming / messages。
     * 失败或原样返回时不提示、不落盘。
     */
    private fun compressorLabel(config: AgentModelClient.ModelConfig?): String {
        val provider = config?.providerName?.trim().orEmpty()
        val model = config?.modelDisplayName?.trim().orEmpty()
            .ifBlank { config?.model?.trim().orEmpty() }
        return when {
            provider.isNotBlank() && model.isNotBlank() -> "$provider · $model"
            model.isNotBlank() -> model
            provider.isNotBlank() -> provider
            else -> ""
        }
    }

    private fun applyCompressedHistoryToConversation(
        conversationId: String,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage,
        compressorLabel: String = "",
        summaryCommitted: Boolean = false,
    ) {
        if (compressedHistory == originalHistory) return
        val current = conversationState(conversationId) ?: return
        val expected = originalHistory + userHistoryMessage
        if (current.history.map { it.copy(turnId = "") } != expected.map { it.copy(turnId = "") }) return
        if (!summaryCommitted) {
            // Pruning or a failed summary only changes the silent budget delta, not this receipt epoch.
            updateConversation(conversationId, current.copy(
                history = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(compressedHistory) + userHistoryMessage,
                isCompressingContext = false, isWaitingForCompression = false,
            ))
            persistConversations()
            return
        }
        updateConversation(
            conversationId,
            current.copy(
                isCompressingContext = false,
                isWaitingForCompression = false,
                history = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(compressedHistory) + userHistoryMessage,
                livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
                messages = AgentContextCompactionUi.applyMarker(
                    messages = current.messages,
                    originalHistory = originalHistory,
                    compressedHistory = compressedHistory,
                    extraKeptUserMessages = 1,
                    compressorLabel = compressorLabel,
                    baselineTokens = (compressedHistory + userHistoryMessage).sumOf {
                        AgentContextBudget.countMessage(it)
                    },
                    resumeRound = 1,
                ),
            ),
        )
        if (conversationId == selectedConversationId) {
            billedOverheadConversationId = conversationId
            billedOverheadTokens = null
        }
        persistConversations()
        showCompressionCompletedToast(conversationId, originalHistory, compressedHistory, compressorLabel)
    }

    /** Called on Main only after an accepted history update, never from replay/projection.
     *  The notice belongs to the conversation that finished, even if the user has switched away. */
    private fun showCompressionCompletedToast(
        conversationId: String?,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        compressorLabel: String,
    ) {
        val count = AgentContextCompactionUi.completedMessageCount(
            originalHistory, compressedHistory, compressorLabel,
        )
        if (count <= 0) return
        val body = appContext.resources.getQuantityString(R.plurals.context_compacted_messages, count, count)
        val title = conversationId?.let { conversationTitles[it] }?.trim().orEmpty()
            .let { raw -> if (raw.length <= 3) raw else raw.take(3) + "…" }
        val text = if (conversationId != selectedConversationId && title.isNotBlank()) "$title：$body" else body
        Toast.makeText(appContext, text, Toast.LENGTH_SHORT).show()
    }

    private fun showRevisionHistoryUnavailableNotice() {
        Toast.makeText(appContext, R.string.revision_history_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun showCompactedRevisionNotice() {
        Toast.makeText(
            appContext,
            R.string.state_ui_the_earlier_context_has_been_compressed_and_will_cf6c86,
            Toast.LENGTH_LONG,
        ).show()
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                // 预算估算用原图尺寸（不是 1024 预览）；fromReference 通常已带边界，
                // data URL 等缺失时再只读解码一次边界。
                val srcWidth = image.width
                val srcHeight = image.height
                val bounds = if (srcWidth != null && srcHeight != null && srcWidth > 0 && srcHeight > 0) {
                    srcWidth to srcHeight
                } else {
                    AgentImageCodec.boundsOfReference(appContext, image.reference)
                }
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                    widthPx = bounds?.first,
                    heightPx = bounds?.second,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun attachVideo(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val parsed = Uri.parse(uri)
                val attachment = AgentVideoCodec.importFromUri(appContext, parsed)
                if (attachment == null) {
                    withContext(Dispatchers.Main) {
                        val tooLarge = runCatching {
                            appContext.contentResolver.openAssetFileDescriptor(parsed, "r")?.use { it.length }
                        }.getOrNull()?.let { it > MAX_AGENT_VIDEO_BYTES.toLong() } == true
                        Toast.makeText(
                            appContext,
                            if (tooLarge) {
                                appContext.getString(
                                    R.string.state_ui_video_exceeds_limit,
                                    MAX_AGENT_VIDEO_BYTES / 1024 / 1024,
                                )
                            } else {
                                appContext.getString(R.string.state_ui_unable_to_read_this_video)
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val pending = PendingImageUi(
                    id = "vid-${UUID.randomUUID()}",
                    uri = attachment.file.absolutePath,
                    dataUrl = attachment.thumbnail.reference,
                    mimeType = attachment.mimeType,
                    isVideo = true,
                    durationMs = attachment.durationMs.takeIf { it > 0L },
                    byteSize = attachment.bytes,
                    widthPx = attachment.width?.takeIf { it > 0 },
                    heightPx = attachment.height?.takeIf { it > 0 },
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachSharedUris(uris: List<String>) {
        if (uris.isEmpty()) return
        val images = mutableListOf<String>()
        val videos = mutableListOf<String>()
        val files = mutableListOf<String>()
        uris.forEach { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
            runCatching {
                appContext.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = appContext.contentResolver.getType(uri).orEmpty()
            val name = uri.lastPathSegment.orEmpty()
            when {
                mime.startsWith("image/") || name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
                    name.endsWith(".jpeg", true) || name.endsWith(".webp", true) || name.endsWith(".gif", true) ->
                    images += raw
                mime.startsWith("video/") || name.endsWith(".mp4", true) || name.endsWith(".webm", true) ||
                    name.endsWith(".mov", true) || name.endsWith(".mkv", true) || name.endsWith(".3gp", true) ->
                    videos += raw
                else -> files += raw
            }
        }
        images.forEach(::attachImage)
        videos.forEach(::attachVideo)
        if (files.isNotEmpty()) attachFiles(files)
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                )
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun attachConversationMention(conversationId: String): Boolean {
        val pending = homeState.pendingConversationMentions
        if (conversationId == selectedConversationId || pending.any { it.conversationId == conversationId }) return false
        val sourceSnapshot = conversationsById[conversationId] ?: return false
        if (pending.size >= ConversationMention.MAX_ATTACHED) {
            Toast.makeText(appContext, appContext.getString(R.string.appst_mention_limit), Toast.LENGTH_SHORT).show()
            return false
        }
        val status = if (sourceSnapshot.isStreaming) "[选择时快照：来源会话仍在运行，未包含后续输出]\n" else ""
        val mentionId = "mention-${UUID.randomUUID()}"
        val ownerVersion = fileAttachmentOwnerVersion
        preparingConversationMentions += mentionId
        updateCurrentConversation(homeState.copy(pendingConversationMentions = pending + PendingConversationMentionUi(
            id = mentionId, conversationId = conversationId,
            title = conversationTitles[conversationId].orEmpty().ifBlank { appContext.getString(R.string.mention_unnamed_title) },
            transcript = appContext.getString(R.string.mention_preparing_transcript),
        )))
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val source = if (sourceSnapshot.conversationContentLoaded) sourceSnapshot else
                        requireNotNull(AgentConversationStore.loadConversation(appContext, conversationId))
                    val evidence = io.github.mangi.eta.ui.model.ConversationToolEvidence(source.messages)
                    evidence.add(source.history, "source conversation model history")
                    io.github.mangi.eta.agent.model.AgentCompactionArchive(appContext.filesDir, conversationId)
                        .visitForConversationMention(evidence::add)
                    val toolFiles = mutableListOf<String>()
                    val body = status + ConversationMention.transcript(
                        source.messages, ConversationMention.SNAPSHOT_MAX_CHARS - status.length,
                        appContext.filesDir, conversationId, evidence, toolFiles,
                    )
                    val snapshot = ConversationMention.writeSnapshot(appContext.filesDir, conversationId, body)
                    val index = snapshot?.let { ConversationMention.writeToolIndex(it, toolFiles) }
                    Triple(snapshot?.absolutePath.orEmpty(), index?.absolutePath.orEmpty(), body)
                }
                if (ownerVersion != fileAttachmentOwnerVersion) return@launch
                if (conversationId !in conversationsById) {
                    removeConversationMention(mentionId)
                    return@launch
                }
                val current = homeState.pendingConversationMentions
                if (current.none { it.id == mentionId }) return@launch
                val (snapshotPath, toolsIndexPath, _) = prepared
                if (snapshotPath.isBlank()) {
                    removeConversationMention(mentionId)
                    Toast.makeText(appContext, appContext.getString(R.string.appst_mention_empty), Toast.LENGTH_SHORT).show()
                } else {
                    updateCurrentConversation(homeState.copy(pendingConversationMentions = current.map {
                        if (it.id == mentionId) it.copy(transcript = "", snapshotPath = snapshotPath, toolsIndexPath = toolsIndexPath) else it
                    }))
                }
            } catch (failure: Exception) {
                if (ownerVersion == fileAttachmentOwnerVersion) {
                    removeConversationMention(mentionId)
                    Toast.makeText(appContext, appContext.getString(R.string.appst_mention_prepare_failed), Toast.LENGTH_SHORT).show()
                }
                if (failure is kotlinx.coroutines.CancellationException) throw failure
            } finally {
                preparingConversationMentions -= mentionId
                // Switching conversations must not leave a sendable "preparing" placeholder.
                fun isUnfinished(item: PendingConversationMentionUi) =
                    item.id == mentionId && item.transcript == appContext.getString(R.string.mention_preparing_transcript)
                conversationsById.toMap().forEach { (id, state) ->
                    if (state.pendingConversationMentions.any(::isUnfinished)) {
                        updateConversation(id, state.copy(pendingConversationMentions =
                            state.pendingConversationMentions.filterNot(::isUnfinished)), updateTimestamp = false)
                    }
                }
                if (selectedConversationId == null && homeState.pendingConversationMentions.any(::isUnfinished)) {
                    homeState = homeState.copy(pendingConversationMentions =
                        homeState.pendingConversationMentions.filterNot(::isUnfinished))
                }
            }
        }
        return true
    }

    fun removeConversationMention(id: String) {
        updateCurrentConversation(homeState.copy(
            pendingConversationMentions = homeState.pendingConversationMentions.filterNot { it.id == id },
        ))
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath -> appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound -> appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType -> appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch -> appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable -> appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied -> appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed -> appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge -> appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut -> appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    fun stopCurrentRun() {
        blockConsoleDispatchAutoRun()
        val runId = activeRunIdForSelectedConversation() ?: return
        stopRun(runId)
    }

    private fun stopRun(
        runId: String,
        keepChildren: Boolean = true,
        reason: AgentChildControlPolicy.Reason = AgentChildControlPolicy.Reason.USER_STOP,
    ) {
        if (activeRunIdForSelectedConversation() == runId) io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stop("run_stop")
        if (stoppingRuns.containsKey(runId)) return
        val imageGen = imageGenerationRunIds.remove(runId)
        if (imageGen) directMediaRuns.cancel(runId)
        flushPendingRunDelta(runId)
        val retrying = modelRetryState.isWaiting(runId)
        if (!imageGen) {
            // Publish scope before the stop flag observed by the delivery thread.
            if (keepChildren) mainStopReasons[runId] = reason else mainStopReasons.remove(runId)
            stoppingRuns[runId] = retrying
            armStopSealWatchdog(runId)
            scope.launch(Dispatchers.IO) {
                val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
                val accepted = if (keepChildren) client.stopMainRun(runId, reason) else { client.cancelRun(runId); true }
                if (!accepted) withContext(Dispatchers.Main.immediate) {
                    // A late transport failure cannot resurrect a run already settled by its
                    // result/watchdog, nor mark a newer run in the same conversation streaming.
                    if (!stoppingRuns.containsKey(runId)) return@withContext
                    stoppingRuns.remove(runId)
                    cancelStopSealWatchdog(runId)
                    conversationIdForRun(runId)?.let { owner ->
                        conversationState(owner)?.let { current ->
                            updateConversation(
                                owner,
                                current.copy(isStreaming = true, isPaused = false, activeRunId = runId),
                                updateTimestamp = false,
                            )
                        }
                    }
                    AndroidAgentLogger.warn("Stop request not confirmed for run=$runId")
                    Toast.makeText(appContext, appContext.getString(R.string.appst_stop_unconfirmed), Toast.LENGTH_SHORT).show()
                    // Do not unlock settings or drop the result subscriber on an uncertain outcome.
                    refreshRuntimeResults()
                }
            }
            updateRunTrace(runId) { messages ->
                val thinking = runMessageProjector.finalizeThinking(runId, messages)
                runMessageProjector.failRunningTools(
                    SYNTHETIC_STATUS_STOPPED, runMessageProjector.finalizeText(runId, thinking),
                )
            }
        } else {
            runJobs.remove(runId)?.cancel()
        }
        replaceLatestAssistantWithNotice(
            runId,
            SystemNoticeCode.Stopped,
            detail = when {
                imageGen -> appContext.getString(R.string.appst_stop_image_generation)
                retrying -> appContext.getString(R.string.appst_stop_waiting_retry)
                else -> null
            },
        )
        // Immediate UI feedback, without cancelling the result subscriber or losing history.
        setConversationStreaming(runId, false)
        if (imageGen) {
            discardQueuedRunEvents(runId)
            runMessageProjector.clearRun(runId)
            runMessageProjector.seal(runId)
            runGeneratedAtMillis.remove(runId)
            branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId)
            runOverheadTokens.remove(runId); runContextWindows.remove(runId)
            contextEstimateDiagnostics.clear(runId)
            runCompressedDuringRun.remove(runId)
        }
        refreshConversationSummaries()
        persistConversations()
    }

    fun pauseCurrentRun() {
        val runId = activeRunIdForSelectedConversation() ?: return
        if (stoppingRuns.containsKey(runId)) return
        if (runId in imageGenerationRunIds) {
            // Media APIs have no resumable pause contract. Stop local I/O instead of ignoring the button.
            stopRun(runId)
            return
        }
        if (homeState.isPaused) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).pauseRun(runId)
        }
        // 只标记暂停，不把消息冻成 isStreaming=false。否则 StreamingMarkdown 会当成
        // 生成结束切到整段 Text；继续后每个 token 都整段重组，流式输出会明显卡顿。
        updateCurrentConversation(homeState.copy(isPaused = true))
    }

    fun abandonPausedRun() {
        if (rejectConversationArchiveMutation()) return
        if (!homeState.isPaused) return
        stopCurrentRun()
        Toast.makeText(
            appContext,
            appContext.getString(R.string.chat_paused_run_abandoned),
            Toast.LENGTH_SHORT,
        ).show()
    }

    fun selectAssistant(id: String) {
        if (rejectConversationArchiveMutation()) return
        if (homeState.isCompressingContext) {
            Toast.makeText(appContext, appContext.getString(R.string.composer_picker_compressing_locked), Toast.LENGTH_LONG).show()
            return
        }
        if (AssistantRepository.profile(id) == null) return
        if (homeState.assistantId == id) {
            cancelPendingAssistantSelection()
            return
        }
        // 与模型同一套待生效语义：system prompt 已在发起时烘进本轮配置，助手也只到下一轮换。
        if (homeState.isStreaming && !homeState.isPaused) {
            stagePendingAssistant(id)
            return
        }
        val replacedRun = activeRunIdForSelectedConversation().takeIf { homeState.isPaused }
        applyConversationAssistant(id, persist = true)
        replacedRun?.let { stopRun(it, reason = AgentChildControlPolicy.Reason.SETTINGS_CHANGED) }
    }

    private fun cancelPendingAssistantSelection() {
        val key = selectedConversationId.orEmpty()
        val pending = pendingSelectionByKey[key]?.takeIf { it.assistantId != null } ?: return
        pendingSelectionByKey[key] = pending.copy(assistantId = null)
        publishPendingSelection()
    }

    private fun stagePendingAssistant(id: String) {
        val key = selectedConversationId.orEmpty()
        pendingSelectionByKey[key] = (pendingSelectionByKey[key] ?: PendingSelection()).copy(assistantId = id)
        publishPendingSelection()
        Toast.makeText(
            appContext,
            appContext.getString(R.string.composer_pending_selection_toast, AssistantRepository.profile(id)?.name.orEmpty()),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun abortActiveRunForRevision() {
        abortConversationRun(
            conversationId = selectedConversationId,
            startPendingCompress = pendingManualCompress != null,
        )
    }

    /** Dropping a conversation must also drop its foreground execution lease.
     *  Unbind before cancelling so the stop result cannot write the conversation back. */
    private fun cancelConversationExecution(conversationId: String) {
        val runId = runIdForConversation(conversationId)
        if (runId != null) releaseConversationRun(conversationId, runId)
        // The UI binding can already be gone while Runtime still holds the execution lease.
        scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            if (runId != null) client.cancelRun(runId)
            client.cancelConversation(conversationId)
        }
    }

    private fun releaseConversationRun(conversationId: String, runId: String) {
        directMediaRuns.cancel(runId)
        imageGenerationRunIds.remove(runId)
        runJobs.remove(runId)?.cancel()
        io.github.mangi.eta.agent.runtime.AgentExecutionService.release("prepare:$runId")
        discardQueuedRunEvents(runId)
        runMessageProjector.clearRun(runId)
        runMessageProjector.seal(runId)
        runGeneratedAtMillis.remove(runId)
        branchRequestBoundaries.remove(runId)
        runConversationIds.remove(runId)
        runUsageOwners.remove(runId)
        runUsageRoutes.remove(runId)
        runRequestRounds.remove(runId)
        runUsageResumeRounds.remove(runId)
        invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId)
        runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
        runCompressedDuringRun.remove(runId)
        contextBudgetBlockedRuns.remove(runId)
        if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
        if (usageRunByConversation[conversationId] == runId) usageRunByConversation.remove(conversationId)
    }

    private fun abortConversationRun(conversationId: String?, startPendingCompress: Boolean) {
        contextBudgetBlockedRuns.entries.removeAll { it.value.conversationId == conversationId }
        if (contextBudgetPrompt?.conversationId == conversationId) contextBudgetPrompt = null
        val runId = runIdForConversation(conversationId)
        if (runId != null) {
            directMediaRuns.cancel(runId)
            imageGenerationRunIds.remove(runId)
            runJobs.remove(runId)?.cancel()
            scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            discardQueuedRunEvents(runId)
            runMessageProjector.clearRun(runId)
            runMessageProjector.seal(runId)
            runGeneratedAtMillis.remove(runId)
            branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId)
            runOverheadTokens.remove(runId); runContextWindows.remove(runId)
            contextEstimateDiagnostics.clear(runId)
            runCompressedDuringRun.remove(runId)
        }
        if (conversationId == null) {
            val frozen = runMessageProjector.failRunningTools(
                SYNTHETIC_STATUS_STOPPED,
                freezeStreamingMessages(homeState.messages),
            )
            homeState = homeState.copy(
                isStreaming = false,
                isPaused = false,
                isCompressingContext = shouldKeepCompressingIndicator(null),
                isWaitingForCompression = false,
                messages = frozen,
                history = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
                    homeState.history,
                    frozen,
                ),
            )
        } else {
            val state = conversationState(conversationId)
            if (state != null) {
                val frozen = runMessageProjector.failRunningTools(
                    SYNTHETIC_STATUS_STOPPED,
                    freezeStreamingMessages(state.messages),
                )
                updateConversation(
                    conversationId,
                    state.copy(
                        isStreaming = false,
                        isPaused = false,
                        isCompressingContext = shouldKeepCompressingIndicator(conversationId),
                        isWaitingForCompression = false,
                        messages = frozen,
                        history = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
                            state.history,
                            frozen,
                        ),
                    ),
                )
            }
        }
        // 这条路径绕过 setConversationStreaming 直写 isStreaming=false，待生效选择要在这里落地。
        commitPendingSelection(conversationId.orEmpty())
        persistConversations()
        if (startPendingCompress && pendingManualCompress != null) {
            conversationId?.let { onConversationRunSettled(it, flushQueue = false) }
                ?: startPendingManualCompress()
        }
    }

    fun continuePausedGeneration() {
        if (rejectConversationArchiveMutation()) return
        // Resuming a paused run only releases Runtime's pause gate; an in-run compaction that
        // was interrupted by the pause must be allowed to finish, not block its own resume.
        if (!homeState.isPaused && rejectSendIfCompressing()) return
        if (!homeState.isPaused) {
            continueDisconnectedGeneration()
            return
        }
        val runId = activeRunIdForSelectedConversation() ?: return
        contextBudgetBlockedRuns[runId]?.let { contextBudgetPrompt = it; return }
        // 调度台暂停期间积压的派发回报：恢复是显式动作，可以把它们并入当前 run（steer），
        // 让模型在这一轮里读到并向用户报告；不另起一轮。
        val pendingReceipts = takePendingConsoleReceiptsForSteer(selectedConversationId)
        scope.launch(Dispatchers.IO) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            client.resumeRun(runId)
            pendingReceipts?.let { runCatching { client.steerRun(runId, it) } }
        }
        updateCurrentConversation(homeState.copy(isPaused = false))
    }

    private fun continueDisconnectedGeneration() {
        if (homeState.isStreaming || homeState.isPaused) return
        if (rejectSendIfModelUnavailable()) return
        if (!canContinueDisconnectedRun(homeState.messages)) return
        val conversationId = selectedConversationId ?: return
        val state = conversationState(conversationId) ?: return
        val committed = AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(
            state.history,
            state.messages,
        )
        val continuedHistory = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(committed)
        val prompt = RESUME_AFTER_COMPRESS_PROMPT
        val runId = "run-${UUID.randomUUID()}"
        state.messages.filterIsInstance<ErrorReconnectMessageUi>()
            .lastOrNull { it.status == ErrorReconnectStatus.Stopped }
            ?.let { runMessageProjector.claimStoppedReconnect(runId, it.id) }
        val resumeMessage = UserMessageUi(
            id = "user-$runId-supplement-resume",
            content = prompt,
        )
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = prompt,
            images = emptyList(),
            history = continuedHistory,
            userHistoryMessage = AgentModelClient.ConversationMessage(
                role = "user",
                content = prompt,
            ),
            messages = state.messages + resumeMessage,
            state = state,
            reasoningEffort = state.reasoningEffort,
            skipAutoCompress = true,
            // 失败那一轮已经结束并保留。点击重试是新开一轮继续，不再复用旧 turnId。
            logicalTurnId = runId,
            // 续写用的 prompt 是占位串，不能当成本轮用户素材改写标题。
            refreshTitleAfterTurn = false,
        )
    }

    fun steerCurrentRun(text: String) {
        if (rejectConversationArchiveMutation()) return
        val runId = activeRunIdForSelectedConversation() ?: return
        if (runId in imageGenerationRunIds) return
        if (rejectSendIfCompressing()) return
        val prompt = text.trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        val pendingMentions = homeState.pendingConversationMentions
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty() && pendingMentions.isEmpty()) return
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val conversationId = selectedConversationId
        if (pendingSteerDrafts.values.any { it.conversationId == conversationId }) return
        val requestId = UUID.randomUUID().toString()
        pendingSteerDrafts[requestId] = PendingSteerDraft(conversationId,
            pendingImages.map { it.id }.toSet(), pendingFileReferences.map { it.id }.toSet(),
            pendingMentions.map { it.id }.toSet(), submittedText = text)
        scope.launch(Dispatchers.IO) {
          try {
            // Preserve position and reject partial staging, rather than silently shifting sources.
            val staged = conversationId?.let { stageChatImages(it, pendingImages) }.orEmpty()
            if (staged.size != pendingImages.size || staged.any { it == null }) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.appst_steer_attachment_save_failed), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val references = staged.filterNotNull()
            val previews = pendingImages.mapIndexed { index, image ->
                if (image.isVideo) {
                    val bytes = AgentChatImageCache.readBytes(image.dataUrl)
                    val preview = bytes?.let { chatImageCache.stage(conversationId!!, it, "preview-${image.id}.jpg") }
                    preview?.absolutePath ?: image.dataUrl
                } else references[index].absolutePath
            }
            val imagesJson = io.github.mangi.eta.ui.model.encodeUserMessageImages(
                previews, references.map { it.absolutePath },
                pendingImages.map { it.isVideo }, pendingImages.map { it.durationMs },
            )
            // Never put a large base64 preview into Binder; leave the draft intact on failure.
            if (imagesJson.length > 64_000) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.appst_steer_preview_save_failed), Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            val steerText = AgentFileReferencePromptCodec.format(prompt, fileReferences + references, pendingMentions.toMentionedConversations())
                .ifBlank { appContext.getString(R.string.appst_steer_attachment_fallback) }
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val sent = client.steerRun(runId, steerText, requestId, imagesJson)
            if (sent) {
                // 暂停中的调度台：这次插话被接受后，再把积压的派发回报并入当前 run，
                // 避免发送失败时白白丢掉积压（也避免为它单开一轮）。
                val pending = withContext(Dispatchers.Main.immediate) {
                    if (homeState.isPaused) takePendingConsoleReceiptsForSteer(conversationId) else null
                }
                pending?.let { runCatching { client.steerRun(runId, it) } }
            }
            withContext(Dispatchers.Main) {
                if (!sent) {
                    Toast.makeText(appContext, appContext.getString(R.string.appst_steer_not_delivered), Toast.LENGTH_LONG).show()
                }
            }
            if (sent) {
                // Only the accepted event clears a draft, not a successful Binder send.
                kotlinx.coroutines.delay(15_000)
                withContext(Dispatchers.Main) {
                    if (requestId in pendingSteerDrafts) {
                        Toast.makeText(appContext, appContext.getString(R.string.appst_steer_unconfirmed), Toast.LENGTH_LONG).show()
                    }
                }
            }
          } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, appContext.getString(R.string.appst_steer_failed), Toast.LENGTH_LONG).show()
            }
          } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                pendingSteerDrafts.remove(requestId)
            }
          }
        }
    }

    private fun snapshotPartialAssistantToHistory(runId: String) {
        val conversationId = conversationIdForRun(runId) ?: selectedConversationId ?: return
        val state = conversationState(conversationId) ?: return
        val assistant = state.messages.lastOrNull { message ->
            message is AgentMessageUi && message.content.isNotBlank()
        } as? AgentMessageUi ?: return
        val taggedHistory = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(state.history)
        val last = taggedHistory.lastOrNull()
        if (last?.role == "assistant" && last.content == assistant.content) return
        updateConversation(
            conversationId,
            state.copy(
                history = taggedHistory + AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = assistant.content,
                    turnId = taggedHistory.lastOrNull { it.turnId.isNotBlank() }?.turnId ?: runId,
                ),
            ),
        )
    }

    private var permissionRefreshJob: Job? = null

    fun refreshPermissionHealth() {
        permissionRefreshJob?.cancel()
        permissionRefreshJob = scope.launch(Dispatchers.IO) {
            val refreshed = buildPermissionHealthState(appContext)
            withContext(Dispatchers.Main) { permissionHealthState = refreshed }
        }
    }

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_read_skills_599082),
                            message = appContext.getString(R.string.state_the_skill_list_is_temporarily_unavailable_please_try_29b0be),
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(skills = items, isLoading = false)
            }
            refreshRequestOverhead()
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_update_skills_04e56c),
                            message = appContext.getString(R.string.state_the_skill_switch_has_not_changed_please_try_again_la_fa262f),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_has_been_deleted_34c29b),
                            message = appContext.getString(R.string.skill_deleted_message, skillName),
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_delete_skill_1583c9),
                            message = appContext.getString(R.string.state_deletion_is_not_complete_eta_will_try_to_recover_whe_c4297e),
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_read_skill_pack_a53563),
                    message = appContext.getString(R.string.state_please_select_the_zip_file_provided_by_the_system_fi_fea145),
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_skill_pack_is_no_longer_available_please_select_the__7cdfb4),
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = appContext.getString(R.string.state_unable_to_continue_installation_136d7c),
                    message = appContext.getString(R.string.state_replacement_confirmation_has_expired_please_select_t_fce9f2),
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error(appContext.getString(R.string.state_ui_unable_to_open_selection_9f0004))
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun restoreRunEvents(runId: String, events: List<AgentEvent>) {
        // Snapshot batches publication only. Defer terminal ordering and summaries
        // explicitly, rather than rescanning the full history for every event.
        Snapshot.withMutableSnapshot {
            flushPendingRunDelta(runId)
            runReplayBatch.replay(
                runId = runId,
                events = events,
                reset = {
                    updateMessages(runId, updateTimestamp = false) { messages ->
                        runMessageProjector.resetForReplay(
                            runId = runId,
                            messages = messages,
                            replaySupplementIndexes = events.filterIsInstance<AgentEvent.UserSupplementReceived>()
                                .mapTo(mutableSetOf()) { it.index },
                        )
                    }
                },
                apply = { event -> applyRunEvent(runId, event, persistSupplement = false, replaying = true) },
                finish = {
                    updateMessages(runId, updateTimestamp = false) { it }
                    refreshConversationSummaries()
                },
            )
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (!StreamPerformanceDiagnostics.enabled) {
            enqueueRunEventBudgeted(runId, event)
            return
        }
        val conversationId = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) runConversationIds[runId] else null
        StreamUiEventDiagnostics.withEvent(runId, conversationId,
            conversationId?.let { it == selectedConversationId }, event) {
            StreamPerformanceDiagnostics.measure("ui.enqueue") { enqueueRunEventBudgeted(runId, event) }
        }
    }

    private fun enqueueRunEventBudgeted(runId: String, event: AgentEvent) {
        // Runtime delivers events on an IO job. Keep the existing main-thread
        // ownership, but do not let a burst monopolize one dispatcher turn.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            scope.launch(Dispatchers.Main.immediate) { enqueueRunEvent(runId, event) }
            return
        }
        if (runMessageProjector.isSealed(runId) && !event.allowedAfterSeal()) return
        runEventBudgets.getOrPut(runId) { AgentRunEventBudget(STREAM_EVENT_FRAME_BUDGET_NS) }
            .offer(event)
        if (runId !in runEventBudgetCallbacks) {
            drainRunEventBudget(runId, force = false)
        }
    }

    private fun drainRunEventBudget(runId: String, force: Boolean, singleEvent: Boolean = false) {
        val queue = runEventBudgets[runId] ?: return
        if (!force && runEventFrameWorkDepth > 0) {
            scheduleRunEventBudgetDrain(runId)
            return
        }
        if (!drainingRunEventBudgets.add(runId)) return
        runFrameEventBudget.measureWork {
            // Also schedule a one-shot reset when this drain empties the queue. New
            // arrivals in the same frame must not receive another fresh 2 ms budget.
            scheduleRunEventBudgetDrain(runId)
            runEventFrameWorkDepth++
            try {
                queue.drain(force, frameBudget = runFrameEventBudget,
                    maxEvents = if (singleEvent) 1 else Int.MAX_VALUE,
                ) { event -> enqueueRunEventNow(runId, event) }
            } finally {
                runEventFrameWorkDepth--
                drainingRunEventBudgets.remove(runId)
            }
            if (queue.isEmpty) {
                runEventBudgets.remove(runId)
                cancelRunEventBudgetCallback(runId)
            } else {
                scheduleRunEventBudgetDrain(runId)
            }
        }
    }

    private fun scheduleRunEventBudgetDrain(runId: String) {
        if (runEventBudgets.containsKey(runId) || runId in runEventDeferredFlushes) runEventBudgetCallbacks.add(runId)
        if (runEventBudgetFrameCallback != null) return
        val callback = Choreographer.FrameCallback { frameTimeNs ->
            runEventBudgetFrameCallback = null
            runFrameEventBudget.beginFrame(frameTimeNs)
            runFrameEventBudget.measureWork {
                // One event per turn: a busy run goes behind the other waiting runs.
                // All runs share the frame budget without starving later arrivals.
                while (runEventBudgetCallbacks.isNotEmpty() && !runFrameEventBudget.exhausted) {
                    val nextRun = runEventBudgetCallbacks.first()
                    runEventBudgetCallbacks.remove(nextRun)
                    val deferredFlush = runEventDeferredFlushes.remove(nextRun)
                    if (deferredFlush != null) {
                        deferredFlush()
                        if (runEventBudgets.containsKey(nextRun)) scheduleRunEventBudgetDrain(nextRun)
                    } else {
                        drainRunEventBudget(nextRun, force = false, singleEvent = true)
                    }
                }
                if (runEventBudgetCallbacks.isNotEmpty()) {
                    scheduleRunEventBudgetDrain(runEventBudgetCallbacks.first())
                }
            }
        }
        runEventBudgetFrameCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun cancelRunEventBudgetCallback(runId: String) {
        if (runId !in runEventDeferredFlushes) runEventBudgetCallbacks.remove(runId)
        // Keep the single pending frame callback: it resets the budget even if
        // the last queue was emptied or cancelled before the next vsync.
    }

    private fun discardQueuedRunEvents(runId: String) {
        runEventDeferredFlushes.remove(runId)
        runEventFlushJobs.remove(runId)?.cancel()
        cancelRunEventBudgetCallback(runId)
        runEventBudgets.remove(runId)
        runEventCoalescer.flush(runId)
    }

    private fun enqueueRunEventNow(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            StreamPerformanceDiagnostics.record("ui.delta.received", value = event.delta.length.toLong())
        }
        if (stoppingRuns.containsKey(runId)) {
            // A terminal event is the runtime's own confirmation; dropping it left the watchdog as
            // the only way out of the stopping state.
            if (RunStopEventGate.isRunTerminal(event)) {
                finishStopSeal(runId)
            } else if (
                event !is AgentEvent.ContextCompacted &&
                event !is AgentEvent.UserSupplementReceived &&
                event !is AgentEvent.UsageReceived &&
                event !is AgentEvent.ChildContextUpdated &&
                !(event is AgentEvent.ErrorReconnectChanged && event.status != "running")
            ) {
                return
            }
        }
        if (runMessageProjector.isSealed(runId) && !event.allowedAfterSeal()) {
            runEventFlushJobs.remove(runId)?.cancel()
            runEventCoalescer.flush(runId)
            return
        }
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return
            runEventCoalescer.append(runId, event)?.let { ready ->
                StreamUiEventDiagnostics.measure("ui.flush.blockSwitch") {
                    applyRunEvent(runId, ready)
                }
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId, diagnosticStage = "ui.flush.nonDelta")
        applyRunEvent(runId, event)
    }

    /**
     * 停止请求发出后启动看门狗。Runtime 可能永远不回 RunResult（子任务收尾阻塞、Binder 丢失），
     * 所以解除界面锁定不能只依赖它；到点后由看门狗补一条终态结果，并如实说明本轮结果未确认。
     */
    private fun armStopSealWatchdog(
        runId: String,
        timeout: RunStopSealTimeout = stopSealTimeout,
    ) {
        val ticket = timeout.beginStop(runId) ?: return
        stopSealWatchdogJobs.remove(runId)?.cancel()
        stopSealWatchdogJobs[runId] = scope.launch {
            delay(timeout.timeoutMillis)
            withContext(Dispatchers.Main.immediate) {
                if (!timeout.claimUnlock(ticket)) return@withContext
                stopSealWatchdogJobs.remove(runId)
                // applyRunResult consumes the stoppingRuns entry itself; removing it here would
                // erase the retry flag that decides which stop notice the user sees.
                if (!stoppingRuns.containsKey(runId)) return@withContext
                AndroidAgentLogger.warn("Stop seal timed out without a terminal result for run=$runId")
                val timedOutNotice = StopSealNotices.timedOut()
                applyRunResult(
                    runId,
                    AgentRuntimeWire.RunResult(
                        runId = runId,
                        ok = false,
                        content = "",
                        error = timedOutNotice,
                    ),
                )
                Toast.makeText(appContext, timedOutNotice, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Runtime 已确认终态，撤掉看门狗，避免它稍后再改写一条已经落定的结果。 */
    private fun cancelStopSealWatchdog(runId: String) {
        stopSealWatchdogJobs.remove(runId)?.cancel()
        stopSealTimeout.release(runId)
        stopSealTerminalTimeout.release(runId)
    }

    /**
     * 收到终态事件：本轮已经结束，只给正式 RunResult 一个短宽限期。
     * 这里不动 stoppingRuns，transcript 归属与 retry 标志仍由 applyRunResult 统一收尾。
     */
    private fun finishStopSeal(runId: String) {
        // Duplicate terminal notifications must not cancel the one remaining grace watchdog.
        if (stopSealTerminalTimeout.isPending(runId)) return
        stopSealWatchdogJobs.remove(runId)?.cancel()
        stopSealTimeout.release(runId)
        armStopSealWatchdog(runId, stopSealTerminalTimeout)
    }

    private fun AgentEvent.allowedAfterSeal(): Boolean =
        this is AgentEvent.UsageReceived || this is AgentEvent.ChildContextUpdated ||
            (this is AgentEvent.ErrorReconnectChanged && status != "running")

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        val scheduledAtNs = if (StreamPerformanceDiagnostics.enabled) System.nanoTime() else null
        val diagnosticAttribution = StreamPerformanceDiagnostics.captureAttribution()
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            if (scheduledAtNs != null && StreamPerformanceDiagnostics.enabled) {
                StreamPerformanceDiagnostics.record("ui.flushDelay", System.nanoTime() - scheduledAtNs)
            }
            runEventFlushJobs.remove(runId)
            if (runFrameEventBudget.exhausted) {
                runEventDeferredFlushes[runId] = {
                    StreamPerformanceDiagnostics.withAttribution(diagnosticAttribution) {
                        StreamPerformanceDiagnostics.measure("ui.flush") {
                            flushPendingRunDelta(runId, diagnosticStage = "ui.flush.timer", drainQueuedEvents = false)
                        }
                    }
                }
                scheduleRunEventBudgetDrain(runId)
                return@launch
            }
            runFrameEventBudget.measureWork {
                StreamPerformanceDiagnostics.withAttribution(diagnosticAttribution) {
                    StreamPerformanceDiagnostics.measure("ui.flush") {
                        flushPendingRunDelta(
                            runId,
                            diagnosticStage = "ui.flush.timer",
                            drainQueuedEvents = false,
                        )
                    }
                }
            }
        }
    }

    private fun flushPendingRunDelta(
        runId: String,
        diagnosticStage: String? = null,
        drainQueuedEvents: Boolean = true,
    ) {
        if (drainQueuedEvents && runId !in drainingRunEventBudgets) {
            drainRunEventBudget(runId, force = true)
        }
        runEventDeferredFlushes.remove(runId)
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            // Only count a reason when a pending delta is actually applied. Other
            // callers (replay/result/stop) retain their existing default flush path.
            val started = if (drainQueuedEvents) null else System.nanoTime()
            if (started != null) {
                scheduleRunEventBudgetDrain(runId)
                runEventFrameWorkDepth++
            }
            try {
                StreamUiEventDiagnostics.measure(diagnosticStage) {
                    applyRunEvent(runId, event)
                }
            } finally {
                if (started != null) {
                    runEventFrameWorkDepth--
                    runFrameEventBudget.record(System.nanoTime() - started)
                }
            }
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_skill_installed_b07e54),
                            message = appContext.getString(
                                R.string.skill_enabled_message,
                                installed.name.safeSkillDisplayName(),
                            ),
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> appContext.getString(R.string.state_ui_the_selected_file_is_not_a_valid_zip_package_bff052)
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> appContext.getString(R.string.state_ui_the_skill_pack_exceeds_the_safe_size_or_file_num_42e151)
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> appContext.getString(R.string.state_ui_the_skill_pack_contains_an_unsafe_file_path_and__d8cfc0)
            SkillZipImportOutcome.FailureCode.NO_SKILL -> appContext.getString(R.string.state_ui_skill_md_not_found_in_zip_a57975)
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> appContext.getString(R.string.state_ui_the_local_zip_must_contain_only_one_skill_b89daf)
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> appContext.getString(R.string.state_ui_skill_md_is_missing_required_information_or_is_i_debe6e)
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> appContext.getString(R.string.state_ui_the_zip_content_has_changed_please_reselect_and__ab8b12)
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> appContext.getString(R.string.state_ui_built_in_skills_with_the_same_name_are_protected_446ad3)
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                appContext.getString(R.string.state_ui_the_target_with_the_same_name_is_not_a_user_skil_00474b)
            SkillZipImportOutcome.FailureCode.READ_FAILED -> appContext.getString(R.string.state_ui_the_selected_file_cannot_be_read_please_select_a_7265b9)
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> appContext.getString(R.string.state_ui_unable_to_save_skills_original_skills_have_been__9d4748)
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                appContext.getString(R.string.state_ui_the_installation_failed_and_automatic_recovery_d_0d9e01)
        }
        return newSkillNotice(
            title = appContext.getString(R.string.state_unable_to_install_skill_0ec70b),
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = appContext.getString(R.string.state_unable_to_restore_skills_6b3d23),
                            message = appContext.getString(R.string.state_the_built_in_skills_have_not_changed_please_try_agai_34e5e3),
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { appContext.getString(R.string.state_ui_unnamed_skill_a58008) }.take(80)

    private fun applyRunEvent(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean = true,
        replaying: Boolean = false,
    ) {
        // 只加计时：流式增量（value=1）和其它事件分开看单次耗时。
        val conversationId = if (StreamPerformanceDiagnostics.enabled) runConversationIds[runId] else null
        StreamUiEventDiagnostics.withEvent(runId, conversationId,
            conversationId?.let { it == selectedConversationId }, event, replaying) {
            StreamPerformanceDiagnostics.measure(
                "ui.runEvent",
                if (event is AgentEvent.AssistantBlockDelta) 1L else 0L,
            ) {
                StreamUiEventDiagnostics.measureEvent(event) {
                    applyRunEventNow(runId, event, persistSupplement, replaying)
                }
            }
        }
    }

    private fun applyRunEventNow(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean,
        replaying: Boolean,
    ) {
        modelRetryState.accept(runId, event)
        val thinkingEvent = event.opensThinking()
        if (thinkingEvent && !replaying) runsWithLiveReasoning += runId
        // 流式增量很密，只在这次运行确实有推理块在跑时才扫一遍消息。
        val reasoningBefore = if (replaying || thinkingEvent || event is AgentEvent.RunFailed ||
            runId !in runsWithLiveReasoning
        ) {
            emptySet()
        } else {
            streamingThinkingIds(runId).also { if (it.isEmpty()) runsWithLiveReasoning -= runId }
        }
        applyRunEventBody(runId, event, persistSupplement, replaying)
        if (reasoningBefore.isNotEmpty()) noteCompletedReasoning(runId, reasoningBefore)
        if (event is AgentEvent.RunFinished || event is AgentEvent.RunFailed) runsWithLiveReasoning -= runId
    }

    private fun AgentEvent.opensThinking(): Boolean = when (this) {
        is AgentEvent.AssistantBlockStart -> kind == AgentEvent.AssistantBlockKind.THINKING
        is AgentEvent.AssistantBlockDelta -> kind == AgentEvent.AssistantBlockKind.THINKING
        else -> false
    }

    /** 有推理块可能还在「正在推理」的运行；只在主线程读写。 */
    private val runsWithLiveReasoning = mutableSetOf<String>()

    /** 本次运行里仍在「正在推理」的思考块。没有时返回空集，事件处理后不用再比。 */
    private fun streamingThinkingIds(runId: String): Set<String> {
        val state = conversationState(conversationIdForRun(runId)) ?: return emptySet()
        val prefix = "$runId-thinking-"
        var ids: MutableSet<String>? = null
        for (message in state.messages) {
            if (message is ThinkingMessageUi && message.isStreaming && message.id.startsWith(prefix)) {
                (ids ?: mutableSetOf<String>().also { ids = it }).add(message.id)
            }
        }
        return ids ?: emptySet()
    }

    /** 处理前在推理、处理后已结束的块各震一次；失败或停止不算完成。 */
    private fun noteCompletedReasoning(runId: String, before: Set<String>) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        state.messages.forEach { message ->
            if (message is ThinkingMessageUi && !message.isStreaming && message.id in before) {
                io.github.mangi.eta.ui.haptics.StreamingHaptics.noteReasoningCompleted(message.id, conversationId)
            }
        }
    }

    /** Draft edits and submissions are bound to stored four-ID ownership, not selected chat. */
    fun updateQuestionDraft(conversationId: String, questionId: String, draft: AgentQuestionAnswer) {
        val state = conversationState(conversationId) ?: return
        val question = state.messages.filterIsInstance<AgentQuestionMessageUi>()
            .singleOrNull { it.request.questionId == questionId } ?: return
        if (question.status != AgentQuestionStatus.Waiting || question.submitting) return
        val kind = when {
            draft.kind == "option" -> "option"
            draft.kind == "other" && question.request.allowOther -> "other"
            draft.kind == "delegate" && question.request.allowDelegation -> "delegate"
            else -> return
        }
        val selected = draft.optionId?.takeIf { id -> question.request.options.any { it.id == id } }
        val updated = question.copy(answerKind = kind, selectedOptionId = selected,
            otherText = draft.otherText.take(2000), note = if (question.request.allowNote) draft.note.take(2000) else "",
            error = null)
        updateConversation(conversationId, state.copy(messages = state.messages.map {
            if (it.id == question.id) updated else it
        }))
        persistConversations()
    }

    fun submitQuestionAnswer(conversationId: String, questionId: String) {
        val state = conversationState(conversationId) ?: return
        val question = state.messages.filterIsInstance<AgentQuestionMessageUi>()
            .singleOrNull { it.request.questionId == questionId } ?: return
        if (question.request.conversationId != conversationId || question.status != AgentQuestionStatus.Waiting || question.submitting) return
        val answer = AgentQuestionProjection.draftAnswer(question)
        val validation = AgentQuestionCodec.validateAnswer(question.request, answer)
        if (!validation.accepted) {
            updateConversation(conversationId, state.copy(messages = state.messages.map {
                if (it.id == question.id) question.copy(error = validation.message.ifBlank { validation.code }) else it
            }))
            return
        }
        val owner = question.request
        updateConversation(conversationId, state.copy(messages = state.messages.map {
            if (it.id == question.id) question.copy(submitting = true, error = null) else it
        }))
        scope.launch(Dispatchers.Main.immediate) {
            val receipt = withContext(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).submitQuestionAnswer(
                    owner.conversationId, owner.runId, owner.questionId, owner.toolCallId, answer)
            }
            val current = conversationState(conversationId) ?: return@launch
            val messages = current.messages.map { m -> if (m is AgentQuestionMessageUi &&
                AgentQuestionProjection.sameOwner(m.request, owner)) AgentQuestionProjection.acknowledged(m, answer, receipt) else m }
            updateConversation(conversationId, current.copy(messages = messages))
            persistConversations()
            // A receipt only acknowledges the request. Query every non-Answered local card,
            // including Interrupted/Cancelled cards closed by a racing terminal event; an
            // authoritative Answered snapshot may correct those, while Waiting never reopens them.
            if (receipt.accepted) delay(1_000)
            val checkMessages = conversationState(conversationId)?.messages.orEmpty()
            if (AgentQuestionProjection.needsAuthoritativeQuery(checkMessages, owner)) {
                val snapshot = withContext(Dispatchers.IO) {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).queryQuestion(owner.conversationId,
                        owner.runId, owner.questionId, owner.toolCallId)
                }
                val latest = conversationState(conversationId) ?: return@launch
                updateConversation(conversationId, latest.copy(
                    messages = AgentQuestionProjection.reconcileMessages(latest.messages, owner, snapshot)
                ))
                persistConversations()
            }
        }
    }

    private fun applyRunEventBody(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean,
        replaying: Boolean,
    ) {
        when (event) {
            is AgentEvent.QuestionRequested -> {
                val owner = conversationIdForRun(runId) ?: return
                updateRunTrace(runId) { messages -> runMessageProjector.requestQuestion(owner, runId, event, messages, replaying) }
                if (!replaying) persistConversations()
            }
            is AgentEvent.QuestionResolved -> {
                val owner = conversationIdForRun(runId) ?: return
                updateRunTrace(runId) { messages -> runMessageProjector.resolveQuestion(owner, runId, event, messages) }
                if (!replaying) persistConversations()
            }
            is AgentEvent.AssistantBlockStart -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.startAssistantBlock(runId, event, messages)
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateMessages(
                    runId = runId,
                    updateTimestamp = false,
                    normalizeTerminalOrder = false,
                    recomputeWaitingQuestion = false,
                ) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.appendTextDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.appendReasoningDelta(
                                runId,
                                event.round,
                                event.index,
                                event.delta,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
                if (event.kind != AgentEvent.AssistantBlockKind.TOOL_CALL) {
                    io.github.mangi.eta.ui.haptics.StreamingHaptics.noteBackgroundOutput(
                        event.deltaChars.coerceAtLeast(event.delta.length),
                        conversationIdForRun(runId),
                        reasoning = event.kind == AgentEvent.AssistantBlockKind.THINKING,
                    )
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.finalizeTextBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.finalizeThinkingBlock(
                                runId,
                                event.round,
                                event.index,
                                event.replacementContent,
                                messages,
                            )

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.ChildContextUpdated -> {
                // Parent events invalidate telemetry only; old run snapshots cannot replace newer owner state.
                conversationIdForRun(runId)?.let(::requestOwnerContext)
            }

            is AgentEvent.UsageReceived -> {
                if (event.projected) {
                    if (!isStaleUsageAfterCompact(runId, event.round)) {
                        val occupancy = io.github.mangi.eta.ui.model.windowTokensFromUsage(event.usage.toUi())
                        updateLivePromptTokens(runId, occupancy, projected = true)
                    }
                } else if (!isStaleUsageAfterCompact(runId, event.round)) {
                    val occupancy = io.github.mangi.eta.ui.model.windowTokensFromUsage(event.usage.toUi())
                    // Record the bill and advance occupancy independently of local token growth.
                    // A local estimate cannot invalidate a cloud receipt or pin the ring to an old bill.
                    updateAssistantUsage(
                        runId,
                        event.round,
                        event.usage.toUi(),
                        // 只有真实云侧回执才带请求前缀指纹；本地估算没有诊断价值。
                        io.github.mangi.eta.data.model.UsageDiagnostics(
                            prefixState = event.requestPrefixState,
                            toolsState = event.requestToolsState,
                            systemState = event.requestSystemState,
                            historyState = event.requestHistoryState,
                            cacheCreationTokens = event.usage.cacheCreationTokens,
                        ).takeUnless { it.isEmpty },
                    )
                    val localBasis = event.requestHistoryTokens?.let { history ->
                        history + (event.requestOverheadTokens ?: 0)
                    }
                    val conversation = conversationIdForRun(runId)?.let(::conversationState)
                    // Judge the receipt against the window this run was launched with,
                    // not against a limit the user may have changed mid-run.
                    val window = runContextWindows[runId] ?: conversation?.let(::boundCompressionWindow)
                    // A gateway cache_read above its own prompt or the window is a billing
                    // artefact, not occupancy: drop it and keep the previous cloud receipt.
                    // Same inputs as AgentSilentContextBudget, so ring and compaction agree.
                    val inflatedCache = io.github.mangi.eta.agent.model.AgentBilledPromptPlausibility
                        .isInflatedCacheRead(occupancy, event.usage.cachedTokens, window)
                    val measured = occupancy.takeIf {
                        io.github.mangi.eta.ui.model.CloudReceiptPlausibility.isOccupancy(
                            tokens = it, contextWindow = window)
                    }
                    if (inflatedCache) {
                        // Ring keeps the last trusted cloud value; same rule as AgentSilentContextBudget.
                    } else if (measured != null) {
                        updateLivePromptTokens(runId, measured, projected = false,
                            historyTokens = event.requestHistoryTokens, overheadTokens = event.requestOverheadTokens,
                            round = event.round)
                        // 诊断只配对本次真实回执；显示学习通路已删除，不再持久化三样本比率。
                        if (!replaying) contextEstimateDiagnostics.receipt(runId, measured,
                            event.usage.cachedTokens, round = event.round,
                            historyTokens = event.requestHistoryTokens,
                            overheadTokens = event.requestOverheadTokens)
                    } else if (localBasis != null && localBasis > 0) {
                        // Keep a usable, self-consistent basis instead of an implausible bill.
                        updateLivePromptTokens(runId, localBasis, projected = true)
                    }
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                conversationIdForRun(runId)?.let { id ->
                    conversationState(id)?.takeIf { it.isPaused }?.let { state ->
                        updateConversation(id, state.copy(isPaused = false))
                    }
                }
                pendingSteerDrafts.remove(event.requestId)?.let { draft ->
                    val id = draft.conversationId
                    if (conversationDrafts.field(id).text.toString() == draft.submittedText) {
                        conversationDrafts.replace(id, "")
                    }
                    val state = id?.let(::conversationState)
                    if (id != null && state != null) {
                        updateConversation(id, state.copy(
                            pendingImages = state.pendingImages.filterNot { it.id in draft.imageIds },
                            pendingFileReferences = state.pendingFileReferences.filterNot { it.id in draft.fileIds },
                            pendingConversationMentions = state.pendingConversationMentions.filterNot { it.id in draft.mentionIds },
                        ))
                    }
                }
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement,
                    requestId = event.requestId, imagesJson = event.imagesJson)
            }

            is AgentEvent.ToolStarted -> {
                if (!replaying) {
                    io.github.mangi.eta.ui.haptics.StreamingHaptics.noteToolAppeared(
                        "$runId-tool-${event.round}-${event.toolCallId.ifBlank { "unknown" }}",
                        conversationIdForRun(runId),
                    )
                }
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.ToolFinished -> {
                // 派发工具的返回里带 dispatch_id/conversation_id/run_id：调用本身结束，
                // 但子任务仍在跑，因此把这同一张卡留在 Running，并写入可跳转身份。
                val dispatchMeta =
                    if (event.name == io.github.mangi.eta.agent.dispatch.AgentDispatchReport.TOOL_NAME) {
                        io.github.mangi.eta.agent.dispatch.AgentDispatchReport
                            .parse(event.resultDetail ?: event.resultSummary)
                    } else {
                        null
                    }
                updateRunTrace(runId) { messages ->
                    val finished = runMessageProjector.finishTool(runId, event, messages)
                    if (dispatchMeta == null) {
                        finished
                    } else {
                        runMessageProjector.markDispatchedTool(
                            runId = runId,
                            round = event.round,
                            toolCallId = event.toolCallId,
                            meta = dispatchMeta,
                            title = pendingDispatchTitles.remove(dispatchMeta.dispatchId).orEmpty(),
                            messages = finished,
                        )
                    }
                }
                if (dispatchMeta != null && !replaying) persistConversations()
            }

            is AgentEvent.HostedToolStarted -> {
                if (!replaying) {
                    io.github.mangi.eta.ui.haptics.StreamingHaptics.noteToolAppeared(
                        "$runId-tool-${event.round}-${event.toolCallId.ifBlank { "unknown" }}",
                        conversationIdForRun(runId),
                    )
                }
                updateRunTrace(runId) { messages ->
                    val finalizedThinking =
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startHostedTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.HostedToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishHostedTool(runId, event, messages)
                }
            }

            is AgentEvent.ModelRetryScheduled -> {
                contextEstimateDiagnostics.clear(runId)
                revokeContextActual(runId)
                updateRunTrace(runId) { messages ->
                    runMessageProjector.scheduleModelRetry(runId, event, messages)
                }
            }

            is AgentEvent.ErrorReconnectChanged -> {
                if (event.status == "running") {
                    contextEstimateDiagnostics.clear(runId)
                    revokeContextActual(runId)
                }
                updateRunTrace(runId) { messages ->
                    runMessageProjector.reconnectChanged(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    runMessageProjector.terminalFailure(runId, event.reason,
                        runMessageProjector.failRunningTools(event.reason, finalizedText))
                }
                runMessageProjector.seal(runId)
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.ensureCompletedThinking(
                            runId = runId,
                            round = event.round,
                            content = event.reasoningContent,
                            messages = messages,
                        )
                    }
                }
            }

            is AgentEvent.RunFinished -> {
                event.generatedAtMillis?.takeIf { it > 0 }?.let { runGeneratedAtMillis[runId] = it }
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalized = runMessageProjector.finalizeText(runId, finalizedThinking)
                    stampCompletedReply(finalized, runId, event.generatedAtMillis)
                }
                runMessageProjector.seal(runId)
            }

            is AgentEvent.AutoCompactWaiting -> {
                conversationIdForRun(runId)?.let { setConversationWaitingForCompression(it, true) }
            }

            is AgentEvent.ContextCompactionStarted -> {
                conversationIdForRun(runId)?.let { setConversationCompressing(it, true, event.modelName) }
            }

            is AgentEvent.ContextCompacted -> {
                applyRuntimeCompactedHistory(runId, event, notifyCompletion = !replaying)
            }

            is AgentEvent.ProviderRequestStarted -> {
                if (runRequestRounds.put(runId, event.round) != null || event.round != 1) contextEstimateDiagnostics.clear(runId)
                revokeContextActual(runId)
                runMessageProjector.beginProviderRequest(runId, event.round)
                if (contextBudgetBlockedRuns.remove(runId) != null) {
                    if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
                    conversationIdForRun(runId)?.let { id -> conversationState(id)?.let { updateConversation(id, it.copy(isPaused = false)) } }
                }
            }

            is AgentEvent.RunStarted -> {
                conversationIdForRun(runId)?.let { id ->
                    conversationState(id)?.let { current ->
                        updateConversation(id, current.copy(childContextRunId = runId), updateTimestamp = false)
                    }
                    requestOwnerContext(id)
                }
            }
            is AgentEvent.DispatchCreated -> {
                // 派发落定：物化目标会话后立刻挂接子 run，让工具调用/结果/错误与模型输出
                // 走会话自己的事件投影并随终态持久化，而不是只留在 Runtime 内存里。
                // outbox 回收必须排到物化之后：新建的派生会话在本次进程里还没进内存，
                // 提前回收会因找不到会话而把结果丢掉。
                //
                // 调度台的 task_dispatch 卡片：标题就是子会话身份。事件可能早于或晚于
                // ToolFinished 到达，两条路径都要落一次标题（排队单稍后开跑时也走这里）。
                if (pendingDispatchTitles.size > 32) pendingDispatchTitles.clear()
                pendingDispatchTitles[event.dispatchId] = event.title
                bindDispatchCardTitle(runId, event.dispatchId, event.title, event.runId)
                markDispatchManaged(event.conversationId)
                materializeDispatchConversation(event.conversationId, event.title) {
                    attachDispatchedRun(event.conversationId, event.runId, event.brief)
                    refreshRuntimeResults()
                }
            }
            is AgentEvent.RoundStarted -> {
                val source = conversationIdForRun(runId)?.let(::conversationState)
                if (source != null && event.historySnapshotId.isNotBlank()) {
                    branchRequestBoundaries[runId] = BranchRequestBoundary(runId, event.round, event.historySnapshotId,
                        source.messages.filterIsInstance<AgentMessageUi>()
                            .filter { AgentRunningBranchSnapshot.isTextForRound(it.id, runId, event.round) }
                            .associate { it.id to it.content })
                }
            }
            is AgentEvent.DecisionFallback -> {
                // 判断模型不可用时的对话内提示。一次 run 只插一条，重复事件直接忽略。
                val detail = appContext.getString(
                    if (event.policy == "fail_closed") {
                        R.string.system_notice_decision_fallback_closed
                    } else {
                        R.string.system_notice_decision_fallback_open
                    },
                )
                updateMessages(runId) { messages ->
                    val noticeId = "decision-fallback-$runId"
                    if (messages.any { it.id == noticeId }) {
                        messages
                    } else {
                        messages + SystemNoticeMessageUi(
                            noticeId,
                            SystemNoticeCode.DecisionFallback,
                            detail,
                        )
                    }
                }
            }
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.GuiControlChanged,
            -> Unit
        }
    }

    private fun applyRuntimeCompactedHistory(
        runId: String,
        event: AgentEvent.ContextCompacted,
        notifyCompletion: Boolean,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        if (!event.applied || event.history.isEmpty()) {
            setConversationCompressing(conversationId, false)
            pendingInRunCompactConversationIds.remove(conversationId)
            if (event.blocked) {
                val prompt = ContextBudgetPrompt(runId, conversationId, event.reason)
                contextBudgetBlockedRuns[runId] = prompt
                if (selectedConversationId == conversationId) contextBudgetPrompt = prompt
                conversationState(conversationId)?.let { updateConversation(conversationId, it.copy(isPaused = true)) }
            } else if (event.reason.isNotBlank() && selectedConversationId == conversationId) {
                Toast.makeText(appContext, event.reason, Toast.LENGTH_LONG).show()
            }
            return
        }
        contextEstimateDiagnostics.clear(runId) // The pre-send request no longer exists, including pruning-only rewrites.
        runUsageResumeRounds[runId] = event.round
        val current = conversationState(conversationId) ?: return
        if (event.pruningOnly) {
            // Automatic and manual tool pruning both keep the cloud reading and
            // raw-history calibration anchor. Only the silent delta has changed.
            // Do not insert a summary marker or finish a pending manual summary.
            updateConversation(conversationId, current.copy(history = event.history))
            persistConversations()
            return
        }
        runCompressedDuringRun.add(runId)
        updateConversation(
            conversationId,
            current.copy(
                isCompressingContext = false,
                isWaitingForCompression = false,
                history = event.history,
                livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
                messages = AgentContextCompactionUi.applyMarker(
                    messages = current.messages,
                    originalHistory = current.history,
                    compressedHistory = event.history,
                    extraKeptUserMessages = 0,
                    compressorLabel = event.compressorLabel,
                    baselineTokens = 0,
                    resumeRound = event.round,
                ),
            ),
        )
        pendingInRunCompactConversationIds.remove(conversationId)
        billedOverheadConversationId = conversationId
        billedOverheadTokens = null
        persistConversations()
        if (notifyCompletion) {
            showCompressionCompletedToast(conversationId, current.history, event.history, event.compressorLabel)
        }
    }

    /**
     * 用量达到自动压缩阈值时压缩已提交的历史。
     * 运行中由 Runtime 在安全边界统一处理；结束后按已提交历史检查。
     */
    private fun scheduleAutoCompress(
        conversationId: String,
        allowRepeat: Boolean,
        runId: String? = null,
    ) {
        if (!autoCompressPreference.enabled) return
        if (!allowRepeat && runId != null && runId in runCompressedDuringRun) return
        val state = conversationState(conversationId) ?: return
        if (state.isStreaming || state.isPaused) {
            return
        }
        val boundModel = AgentModelPickerProjector.project(selectionProviders, state.providerId, state.modelId).selectedModel
            ?.takeIf { it.providerId == state.providerId && it.id == state.modelId } ?: return
        val contextWindow = boundModel.contextWindow
        val estimatedTokens = compressionContextUsage(
            history = state.history,
            currentInput = "",
            pendingImages = emptyList(),
            selectedModel = boundModel,
            billedContextTokens = budgetReceiptTokens(state),
            requestOverheadTokens = if (conversationId == selectedConversationId) requestOverheadTokens else state.cloudRequestOverheadTokens ?: 0,
            billedOverheadTokens = state.cloudRequestOverheadTokens,
            billedHistoryTokens = state.cloudHistoryTokens,
        ).contextTokens
        // 与圆环同一个数：云端实测。estimatedTokens 只用于压缩时按账单缩放保留尾部。
        if (!shouldAutoCompress(state.history, contextWindow, billedPromptTokens(state))) return
        val billed = billedPromptTokens(state)
        val local = billed?.let {
            compressionContextUsage(history = state.history, currentInput = "", pendingImages = emptyList(),
                selectedModel = boundModel,
                requestOverheadTokens = if (conversationId == selectedConversationId) requestOverheadTokens else state.cloudRequestOverheadTokens ?: 0,
            ).contextTokens
        }
        if (runId != null && !allowRepeat) {
            runCompressedDuringRun.add(runId)
        }
        setConversationCompressing(conversationId, true)
        val previous = compressionJob
        compressionJobConversationId = conversationId
        compressionJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                compressSubmittedHistory(conversationId, estimatedTokens.takeIf { billed != null }, local)
            } finally {
                withContext(Dispatchers.Main) {
                    // Only a manual request queued for *this* conversation keeps its indicator.
                    if (pendingManualCompress?.conversationId != conversationId) {
                        setConversationCompressing(conversationId, false)
                    }
                    startPendingManualCompress()
                    // 压缩期间被挡下的排队消息在压缩结束后继续续发。
                    flushQueuedMessages(conversationId)
                }
            }
        }
    }

    private suspend fun compressSubmittedHistory(
        conversationId: String,
        billedTokens: Int? = null,
        localTokens: Int? = null,
    ) {
        val snapshot = withContext(Dispatchers.Main) {
            conversationState(conversationId)
        } ?: return
        val originalHistory = snapshot.history
        val fallback = runtimeConfigForBoundModel(snapshot, assistant = null)
            ?: return
        val compressModelConfig = resolveCompressModelConfig(fallback)
        var summaryCommitted = false
        val compressed = tryCompressHistory(originalHistory, compressModelConfig,
            sourceModelConfig = fallback,
            conversationId = conversationId, contextWindow = fallback.contextWindow,
            billedTokens = billedTokens, localTokens = localTokens,
            onSummaryCommitted = { summaryCommitted = true })
        if (compressed == originalHistory) return
        withContext(Dispatchers.Main) {
            val latest = conversationState(conversationId) ?: return@withContext
            if (latest.history != originalHistory) return@withContext
            if (!summaryCommitted) {
                updateConversation(conversationId, latest.copy(
                    history = compressed, isCompressingContext = false, isWaitingForCompression = false,
                ))
                persistConversations()
                return@withContext
            }
            updateConversation(
                conversationId,
                latest.copy(
                    isCompressingContext = false,
                    isWaitingForCompression = false,
                    history = compressed,
                    livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
                    messages = AgentContextCompactionUi.applyMarker(
                        messages = latest.messages,
                        originalHistory = originalHistory,
                        compressedHistory = compressed,
                        extraKeptUserMessages = 0,
                        compressorLabel = compressorLabel(compressModelConfig),
                    ),
                ),
            )
            billedOverheadConversationId = conversationId
            billedOverheadTokens = null
            persistConversations()
            showCompressionCompletedToast(conversationId, originalHistory, compressed, compressorLabel(compressModelConfig))
        }
    }

    private fun markConversationCompleted(conversationId: String) {
        conversationCompletionMarkers = conversationCompletionMarkers + conversationId
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        val stoppedDuringRetry = stoppingRuns.remove(runId)
        cancelStopSealWatchdog(runId)
        mainStopReasons.remove(runId)
        modelRetryState.clear(runId)
        contextBudgetBlockedRuns.remove(runId)
        if (contextBudgetPrompt?.runId == runId) contextBudgetPrompt = null
        flushPendingRunDelta(runId)
        runJobs.remove(runId)
        directMediaRuns.cancel(runId)
        imageGenerationRunIds.remove(runId)
        updateRunTrace(runId) { messages -> runMessageProjector.finalizeRun(runId, messages) }
        applyConversationHistoryResult(runId, result.transcript)
        when {
            stoppedDuringRetry != null -> replaceLatestAssistantWithNotice(
                runId,
                SystemNoticeCode.Stopped,
                detail = if (stoppedDuringRetry) appContext.getString(R.string.appst_stop_waiting_retry) else null,
            )
            result.ok && (result.content.isNotBlank() || VirtualCompletionNotice.confirmed(result)) -> completeLatestAssistantMessage(
                runId,
                fallbackContent = result.content,
            )
            result.ok -> replaceLatestAssistantWithNotice(runId, SystemNoticeCode.EmptyResult)
            result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED ->
                replaceLatestAssistantWithNotice(runId, SystemNoticeCode.Stopped)
            else -> replaceLatestAssistantWithNotice(
                runId,
                SystemNoticeCode.RuntimeFailed,
                result.error,
            )
        }
        if (stoppedDuringRetry == null) {
            updateMessages(runId) { VirtualCompletionNotice.append(it, runId, result) }
            if (acknowledgeRuntimeResult && VirtualCompletionNotice.confirmed(result)) {
                Toast.makeText(appContext, appContext.getString(R.string.appst_task_completed), Toast.LENGTH_SHORT).show()
            }
        }
        // A stop/failure without this round's receipt still keeps the latest actual from this epoch.
        // setConversationStreaming consumes first-turn NONE without fabricating a cloud receipt.
        revokeContextActual(runId)
        setConversationStreaming(runId, false)
        val conversationId = conversationIdForRun(runId)
        if (conversationId != null && ConversationCompletionMarker.shouldMark(
                result, wasStopped = stoppedDuringRetry != null,
                isSelected = conversationId == selectedConversationId,
            )) {
            markConversationCompleted(conversationId)
        }
        conversationId?.let(pendingInRunCompactConversationIds::remove)
        runMessageProjector.clearRun(runId)
        runGeneratedAtMillis.remove(runId)
        branchRequestBoundaries.remove(runId); runConversationIds.remove(runId); runUsageOwners.remove(runId); runUsageRoutes.remove(runId); runRequestRounds.remove(runId); runUsageResumeRounds.remove(runId); invalidatedUsageRuns.remove(runId)
        runOverheadTokens.remove(runId); runContextWindows.remove(runId)
        contextEstimateDiagnostics.clear(runId)
        runCompressedDuringRun.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
        if (conversationId != null) {
            // 本轮回复已收尾：此刻对话里同时有本轮用户问题与助手最终回答，才生成/刷新标题。
            consumePendingConversationTitle(conversationId, runTitleAnswer(conversationId, runId, result))
            // 用户主动停止（含重试等待中停止）时保留队列，不自动续发。
            onConversationRunSettled(conversationId, flushQueue = stoppedDuringRetry == null)
        }
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        updateMessages(runId, transform = transform)
        refreshConversationSummaries()
    }

    private fun isStaleUsageAfterCompact(runId: String, round: Int): Boolean =
        (runUsageResumeRounds[runId]?.let { round < it } ?: false) ||
            (runRequestRounds[runId]?.let { round < it } ?: false)

    private fun updateAssistantUsage(
        runId: String,
        round: Int,
        usage: TokenUsageUi,
        diagnostics: io.github.mangi.eta.data.model.UsageDiagnostics? = null,
    ) {
        if (usage.isEmpty) return
        if (isStaleUsageAfterCompact(runId, round)) return
        // 只补充 token 用量。不能触碰 isStreaming：Usage 事件紧跟在文本块结束之后，
        // 若把 isStreaming 改回 true，流式渲染会在流式/静态两种视图间反复切换，整段重渲染。
        // A recovered/unknown run must not borrow the currently selected model's estimate.
        val overhead = runOverheadTokens[runId]
        val conversationId = conversationIdForRun(runId)
        updateMessages(runId) { messages ->
            val textIndex = messages.indexOfLast { message ->
                message is AgentMessageUi &&
                    isAssistantMessageForRound(message.id, runId, round) &&
                    message.content.isNotBlank()
            }
            val holderIndex = messages.indexOfLast { message ->
                message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)
            }
            val targetIndex = if (textIndex >= 0) textIndex else holderIndex
            if (targetIndex < 0) {
                messages + AgentMessageUi(
                    id = "${assistantMessagePrefix(runId)}$round-usage",
                    content = "",
                    isStreaming = false,
                    usage = usage,
                    usageDiagnostics = diagnostics,
                )
            } else {
                // Preserve every previous snapshot; only the target chunk changes.
                // 后续修正账单的回执若不带诊断，保留已落下的前缀指纹，别用 null 抹掉证据。
                val target = messages[targetIndex] as AgentMessageUi
                messages.incrementalSnapshot().replacing(
                    targetIndex,
                    target.copy(usage = usage, usageDiagnostics = diagnostics ?: target.usageDiagnostics),
                )
            }
        }
        billedOverheadConversationId = conversationId
        billedOverheadTokens = overhead
    }

    /** Request/retry/terminal boundaries only revoke a receipt if its model or route became invalid.
     * Same-route requests keep actual and evidence, including same-request usage corrections.
     * A new request's evidence is separated by ContextReceiptEvidence.merge(requestId), not here. */
    private fun revokeContextActual(runId: String) {
        val id = conversationIdForRun(runId) ?: return
        val state = conversationState(id) ?: return
        if (usageRunByConversation[id] != runId || runId in invalidatedUsageRuns) return
        val route = contextRouteSignature(state)
        if (route != null && runUsageOwners[runId] == (state.providerId to state.modelId) &&
            runUsageRoutes[runId] == route &&
            (state.cloudRouteSignature == null || state.cloudRouteSignature == route)) return
        invalidatedUsageRuns.add(runId)
        updateConversation(id, state.copy(
            livePromptTokens = null, livePromptIsProjected = false,
            cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
            contextAwaitingReceipt = state.contextAwaitingReceipt || state.contextHasStarted,
            cloudReceiptRequestId = null, cloudRouteSignature = null,
            contextReceiptEvidence = null, receiptPredictionTokens = null,
        ), updateTimestamp = false)
    }

    private fun bindUsageRun(runId: String, conversationId: String) {
        // Reattachment cannot revive an invalidated run or steal a newer run's receipt ownership.
        if (runId in invalidatedUsageRuns) return
        runConversationIds[runId] = conversationId
        usageRunByConversation[conversationId] = runId
        // A run's owner, route and compression resume floor are frozen at its first binding.
        if (runId in runUsageOwners) return
        conversationState(conversationId)?.let {
            runUsageOwners[runId] = it.providerId to it.modelId
            contextRouteSignature(it)?.let { signature -> runUsageRoutes[runId] = signature }
        }
    }

    private fun updateLivePromptTokens(runId: String, tokens: Int?, projected: Boolean = false,
        historyTokens: Int? = null, overheadTokens: Int? = null, round: Int? = null) {
        if (projected && stoppingRuns.containsKey(runId)) return
        if (tokens == null || tokens <= 0 || runId in invalidatedUsageRuns) return
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        val owner = runUsageOwners[runId] ?: return
        if (owner != (state.providerId to state.modelId) || usageRunByConversation[conversationId] != runId) return
        val route = runUsageRoutes[runId] ?: return
        if (route != contextRouteSignature(state)) return
        // Runtime projections are budget evidence only, not learned display estimates.
        if (projected) return
        val requestId = round?.let { "$runId:$it" } ?: return
        val evidence = io.github.mangi.eta.ui.model.ContextReceiptEvidence.merge(
            state.contextReceiptEvidence, requestId, tokens, historyTokens, overheadTokens)
        val next = state.copy(livePromptTokens = tokens, livePromptIsProjected = false,
            contextBudgetReceiptTokens = tokens,
            contextHasStarted = true, contextAwaitingReceipt = false, receiptPredictionTokens = null,
            cloudReceiptRequestId = requestId, cloudRouteSignature = route, contextReceiptEvidence = evidence,
            cloudHistoryTokens = evidence.history, cloudRequestOverheadTokens = evidence.overhead)
        if (next == state) return
        updateConversation(
            conversationId,
            next,
            updateTimestamp = false,
        )
    }

    private fun insertSupplementMessage(
        runId: String,
        index: Int,
        text: String,
        persist: Boolean = true,
        requestId: String = "",
        imagesJson: String = "[]",
    ) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        requestId = requestId,
                        imagesJson = imagesJson,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        if (persist) persistConversations()
    }

    private fun completeLatestAssistantMessage(
        runId: String,
        fallbackContent: String,
        generatedAtMillis: Long? = runGeneratedAtMillis[runId],
    ) {
        updateMessages(runId) { messages ->
            val targetIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
            val resultTail = AgentRunMessageProjector.completedResultTail(runId, messages, targetIndex, fallbackContent)
            if (targetIndex < 0) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = resultTail,
                    isStreaming = false,
                    renderMarkdown = true,
                    generatedAtMillis = generatedAtMillis,
                )
            } else {
                val targetRound = (messages[targetIndex] as AgentMessageUi).id
                    .assistantRound(runId)
                val sameRoundBlocks = targetRound?.let { round ->
                    messages.count { message ->
                        message is AgentMessageUi && message.id.assistantRound(runId) == round
                    }
                } ?: 0
                messages.mapIndexed { index, message ->
                    if (index == targetIndex && message is AgentMessageUi) {
                        message.copy(
                            content = mergeCompletedAssistantContent(message.content, resultTail, sameRoundBlocks),
                            isStreaming = false,
                            renderMarkdown = true,
                            generatedAtMillis = message.generatedAtMillis ?: generatedAtMillis,
                        )
                    } else {
                        message
                    }
                }
            }
        }
    }

    private fun replaceLatestAssistantWithNotice(
        runId: String,
        code: SystemNoticeCode,
        detail: String? = null,
    ) {
        updateMessages(runId) { messages ->
            // Error details belong in a clickable marker, never in the answer body.
            // Keep stop/control notices and the existing child-disposition flow intact.
            when (code) {
                SystemNoticeCode.RuntimeFailed -> runMessageProjector.terminalFailure(
                    runId, detail.orEmpty(), messages,
                )
                SystemNoticeCode.Stopped -> runMessageProjector.runStopped(runId, messages) +
                    SystemNoticeMessageUi("interrupted-$runId", code, detail)
                else -> messages + SystemNoticeMessageUi("interrupted-$runId", code, detail)
            }
        }
    }

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun assistantFallbackMessageId(runId: String): String =
        "${assistantMessagePrefix(runId)}1"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun String.assistantRound(runId: String): Int? =
        removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()

    private fun orderedTerminalState(state: AgentChatHomeUiState): AgentChatHomeUiState {
        if (!state.conversationContentLoaded) return state
        val ordered = state.messages.withTerminalBodiesInOrder()
        return if (ordered == state.messages) state else state.copy(messages = ordered)
    }

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        normalizeTerminalOrder: Boolean = true,
        recomputeWaitingQuestion: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        StreamPerformanceDiagnostics.measure("ui.messages.apply", state.messages.size.toLong()) {
            val projected = StreamUiEventDiagnostics.measure("ui.messages.transform", state.messages.size.toLong()) {
                transform(state.messages)
            }
            // Text/thinking deltas only replace or append the active run block. They
            // cannot create a terminal notice; defer the cross-history terminal
            // ordering pass to the next boundary/terminal event.
            val nextMessages = if (normalizeTerminalOrder) {
                StreamUiEventDiagnostics.measure("ui.messages.normalize", projected.size.toLong()) {
                    runReplayBatch.normalize(runId, projected)
                }
            } else {
                projected
            }
            // A sealed/filtered event can legitimately project the exact same immutable list.
            // Delta paths already disable timestamp and waiting-question recomputation, so
            // publishing that no-op only invalidates Compose observers and repeats routing work.
            if (shouldSkipNoOpStreamingPublication(
                    state.messages,
                    nextMessages,
                    updateTimestamp,
                    normalizeTerminalOrder,
                    recomputeWaitingQuestion,
                )
            ) {
                return@measure
            }
            StreamUiEventDiagnostics.measure("ui.messages.publish", nextMessages.size.toLong()) {
                updateConversationProjected(
                    conversationId = conversationId,
                    state = state.copy(messages = nextMessages),
                    updateTimestamp = updateTimestamp,
                    recomputeWaitingQuestion = recomputeWaitingQuestion,
                )
            }
        }
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        val draft = homeState.copy(input = currentDraftField().text.toString())
        if (!beginNewSubAgentDraft()) return
        conversationDrafts.replace(null, draft.input)
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.reasoningEffort.enablesReasoning,
            reasoningEffort = draft.reasoningEffort,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            pendingImages = draft.pendingImages,
            pendingFileReferences = draft.pendingFileReferences,
            pendingConversationMentions = draft.pendingConversationMentions,
            providerId = currentBoundProviderId(),
            modelId = currentBoundModelId(),
            assistantId = currentBoundAssistantId(),
        ).withCurrentGptSpeedBinding()
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    private fun shouldSkipNoOpStreamingPublication(
        previousMessages: List<AgentChatMessageUi>,
        nextMessages: List<AgentChatMessageUi>,
        updateTimestamp: Boolean,
        normalizeTerminalOrder: Boolean,
        recomputeWaitingQuestion: Boolean,
    ): Boolean =
        nextMessages === previousMessages && !updateTimestamp &&
            !normalizeTerminalOrder && !recomputeWaitingQuestion

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) = updateConversationProjected(conversationId, state, updateTimestamp, recomputeWaitingQuestion = true)

    private fun updateConversationProjected(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean,
        recomputeWaitingQuestion: Boolean,
    ) {
        check(state.conversationContentLoaded) { "Conversation content must be loaded before editing" }
        val previous = conversationState(conversationId)
        val projected = StreamUiEventDiagnostics.measure("ui.conversation.route") {
            val modelChanged = previous != null &&
                (previous.providerId != state.providerId || previous.modelId != state.modelId ||
                    (state.cloudRouteSignature != null && state.cloudRouteSignature != contextRouteSignature(state)))
            if (modelChanged) runConversationIds.filterValues { it == conversationId }.keys.forEach { invalidatedUsageRuns.add(it) }
            when {
                modelChanged -> state.copy(livePromptTokens = null, livePromptIsProjected = false,
                    cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                    // Selecting a model is not a completed first turn or a compression boundary.
                    contextAwaitingReceipt = state.contextAwaitingReceipt || state.contextHasStarted,
                    contextReceiptEvidence = null,
                    receiptPredictionTokens = null, cloudReceiptRequestId = null, cloudRouteSignature = null)
                state.contextAwaitingReceipt || state.cloudHistoryTokens == null || state.cloudRequestOverheadTokens == null ->
                    state.copy(contextBudgetReceiptTokens = null)
                (state.livePromptTokens == null || state.livePromptIsProjected) && state.contextBudgetReceiptTokens == null -> state.copy(
                    cloudHistoryTokens = null, cloudRequestOverheadTokens = null)
                else -> state
            }
        }
        val ownerContext = ownerContexts[conversationId]
        val view = StreamUiEventDiagnostics.measure("ui.conversation.owner") { ownerContext?.projection() }
        val questionProjected = if (recomputeWaitingQuestion) {
            StreamUiEventDiagnostics.measure("ui.conversation.waiting", projected.messages.size.toLong()) {
                projected.copy(isWaitingForAnswer = AgentQuestionProjection.hasWaiting(projected.messages))
            }
        } else {
            projected
        }
        // Owner projection is timed in two slices, not moved across the question scan.
        val current = StreamUiEventDiagnostics.measure("ui.conversation.owner") {
            if (view == null || ownerContext == null) questionProjected else questionProjected.copy(
                childContexts = view.children, selectedContextTaskId = view.selectedTaskId,
                childStatusRoster = ownerContext.roster(),
            )
        }
        StreamUiEventDiagnostics.measure("ui.conversation.publish") {
            conversationsById = conversationsById + (conversationId to current)
            if (conversationId !in conversationCreatedAt) {
                conversationCreatedAt = conversationCreatedAt + (conversationId to System.currentTimeMillis())
            }
            if (updateTimestamp) {
                conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
            }
            if (conversationId == selectedConversationId) {
                homeState = current
            }
        }
        if (previous?.isStreaming != state.isStreaming) {
            refreshConversationSummaries()
        }
        // 暂停/继续不改变 isStreaming，但会改变抽屉行尾的运行态，必须同步重建摘要。
        if (previous?.isPaused != state.isPaused) {
            refreshConversationSummaries()
        }
    }

    private fun setConversationWaitingForCompression(conversationId: String?, waiting: Boolean) {
        if (conversationId == null) {
            homeState = homeState.copy(isWaitingForCompression = waiting)
            return
        }
        val current = conversationState(conversationId) ?: return
        updateConversation(conversationId, current.copy(isWaitingForCompression = waiting))
    }

    private fun setConversationCompressing(conversationId: String?, compressing: Boolean, modelName: String = "") {
        if (conversationId == null) {
            homeState = homeState.copy(isCompressingContext = compressing, isWaitingForCompression = false, compactingModelName = if (compressing) modelName else "")
            return
        }
        val current = conversationState(conversationId) ?: return
        updateConversation(conversationId, current.copy(isCompressingContext = compressing, isWaitingForCompression = false, compactingModelName = if (compressing) modelName else ""))
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationState(conversationId) ?: return
        if (state.isStreaming && !isStreaming) {
            ProviderBalanceStore.requestRefresh(scope)
        }
        updateConversation(
            conversationId,
            state.copy(
                isStreaming = isStreaming,
                // 只有真在跑的这一轮才有运行身份；结束即交还，残留值不会骗到朗读判定。
                activeRunId = if (isStreaming) runId else null,
                // Normal, failed, stopped and direct-media completion all consume first-turn NONE.
                contextHasStarted = state.contextHasStarted || !isStreaming,
                // A parent terminal event does not determine independently owned child status.
                // Keep the last reported child snapshot; the task-group registry owns liveness.
                childContexts = state.childContexts,
                isWaitingForCompression = isStreaming && state.isWaitingForCompression,
                isPaused = if (isStreaming) state.isPaused else false,
                // Show the percentage against the window this run actually flies with;
                // a settled run releases the override back to the picker's window.
                activeRunContextWindow = if (isStreaming) runContextWindows[runId] else null,
                isCompressingContext = when {
                    isStreaming -> state.isCompressingContext
                    shouldKeepCompressingIndicator(conversationId) -> true
                    else -> false
                },
            ),
        )
        // 本轮终态（正常结束 / 停止 / 失败）都收口在这里：此时提交待生效选择，
        // 圆环按新模型的窗口重置正是期望行为，队列续发的下一轮也就用上了新绑定。
        if (state.isStreaming && !isStreaming) commitPendingSelection(conversationId)
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    private fun activeRunIdForSelectedConversation(): String? {
        val conversationId = selectedConversationId ?: return null
        return runConversationIds.entries.firstOrNull { it.value == conversationId }?.key
    }

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationState(conversationId) ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun conversationState(id: String?): AgentChatHomeUiState? {
        if (id == null) return null
        val cached = conversationsById[id] ?: return null
        if (cached.conversationContentLoaded) return cached
        val loaded = AgentConversationStore.loadConversation(appContext, id)?.let(::orderedTerminalState)
            ?.withCurrentGptSpeedBinding() ?: return null
        val current = conversationsById[id] ?: return null
        if (current !== cached) return conversationState(id)
        conversationsById = conversationsById + (id to loaded)
        return loaded
    }

    /**
     * Hydrate the conversations a recovery pass is about to project, off the main thread.
     *
     * [conversationState] reads Room synchronously whenever the cached entry is still a
     * preview, and every non-selected conversation is demoted back to a preview after any
     * persist. The recovery loops below used to resolve their ids inside
     * `withContext(Dispatchers.Main)`, paying one full conversation transaction per lookup
     * on the UI thread. Loading ahead of that block turns those lookups into cache hits.
     *
     * Mirrors [reconcileRecoveredQuestions]: read on IO, then re-check on Main and only
     * fill an entry that is still unloaded, so a concurrent edit/delete is never clobbered.
     * Values published here are exactly what [conversationState] would have produced.
     */
    /**
     * 归档导入前的会话预加载：与 [preloadConversationsForRecovery] 同一套读盘规则，区别是
     * 「完全不在内存」的会话也要装进来——归档导入要改的正是这类会话（例如系统语音浮窗
     * 刚建出来的 `assistant-<hash>`）。Room 里查不到就是真新会话，交给调用方建空态。
     */
    private suspend fun preloadArchivedConversations(conversationIds: Collection<String>) {
        val pending = withContext(Dispatchers.Main.immediate) {
            conversationIds.distinct().filter { id ->
                conversationsById[id]?.conversationContentLoaded != true
            }
        }
        if (pending.isEmpty()) return
        val loaded = withContext(Dispatchers.IO) {
            pending.mapNotNull { id ->
                AgentConversationStore.loadConversation(appContext, id)?.let { id to it }
            }
        }
        if (loaded.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            loaded.forEach { (id, state) ->
                val current = conversationsById[id]
                if (current == null || !current.conversationContentLoaded) {
                    conversationsById = conversationsById +
                        (id to orderedTerminalState(state).withCurrentGptSpeedBinding())
                }
            }
        }
    }

    private suspend fun preloadConversationsForRecovery(conversationIds: Collection<String>) {
        val pending = withContext(Dispatchers.Main.immediate) {
            conversationIds.filter { id ->
                conversationsById[id]?.conversationContentLoaded == false
            }
        }
        if (pending.isEmpty()) return
        val loaded = withContext(Dispatchers.IO) {
            pending.mapNotNull { id ->
                AgentConversationStore.loadConversation(appContext, id)?.let { id to it }
            }
        }
        if (loaded.isEmpty()) return
        withContext(Dispatchers.Main.immediate) {
            loaded.forEach { (id, state) ->
                val current = conversationsById[id]
                if (current != null && !current.conversationContentLoaded) {
                    conversationsById = conversationsById +
                        (id to orderedTerminalState(state).withCurrentGptSpeedBinding())
                }
            }
        }
    }

    private fun releasePersistedConversationContent(
        saved: Map<String, AgentChatHomeUiState>,
        contentConversationIds: Set<String>? = null,
    ) {
        if (conversationArchiveBusy) return
        val runOwners = runConversationIds.values.toSet()
        conversationsById = conversationsById.mapValues { (id, state) ->
            // A scoped save skips other conversations' content; unloading them would discard
            // in-memory changes that were never written by this save.
            val persistedHere = contentConversationIds == null || id in contentConversationIds
            val pinned = !persistedHere ||
                id == selectedConversationId || id in runOwners || state.isStreaming || state.isPaused ||
                state.isCompressingContext || state.isWaitingForCompression || state.messageEdit != null ||
                state.pendingImages.isNotEmpty() || state.pendingFileReferences.isNotEmpty() ||
                state.pendingConversationMentions.isNotEmpty() || state.input.isNotEmpty()
            if (!pinned && state.conversationContentLoaded && saved[id] === state) {
                AgentConversationStore.unloadedPreview(state)
            } else state
        }
    }

    private fun observeConversationSummaryDates() {
        scope.launch(Dispatchers.Main.immediate) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    // Summary labels are cached; invalidate even when no conversation changes.
                    refreshConversationSummaries()
                }
            }
            appContext.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_DATE_CHANGED)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_LOCALE_CHANGED)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                appContext.unregisterReceiver(receiver)
            }
        }
    }

    private fun refreshConversationSummaries() {
        if (runReplayBatch.isActive) return
        StreamUiEventDiagnostics.measure("ui.summaries.refresh", conversationsById.size.toLong()) {
            refreshConversationSummariesNow()
        }
    }

    private fun refreshConversationSummariesNow() {
        // Date labels depend on the local day (including year), locale, zone and clock format.
        // Capture once per refresh, not once per conversation; midnight/config changes invalidate
        // all entries without changing the createdAt-before-updatedAt ordering/label semantics.
        val nowMillis = System.currentTimeMillis()
        val timeZone = java.util.TimeZone.getDefault()
        val locale = appContext.resources.configuration.locales[0]
        val use24HourClock = DateFormat.is24HourFormat(appContext)
        val environment = ConversationSummaryEnvironment(
            // Only the locale affects labels; orientation/uiMode must not invalidate the cache.
            configuration = appContext.resources.configuration.locales.toLanguageTags(),
            localDay = java.time.Instant.ofEpochMilli(nowMillis).atZone(timeZone.toZoneId()).toLocalDate().toEpochDay(),
            timeZone = timeZone,
            use24HourClock = use24HourClock,
        )
        conversationSummaryCache.retain(conversationsById.keys)
        // 调度台身份在列表构建期间不会变化，这里只解析一次，避免每行重复扫描。
        val consoleId = dispatchConsoleId()
        // 回收站成员不进正常列表；单独生成回收站快照，排序按删除时间由新到旧。
        fun buildSummary(id: String, state: AgentChatHomeUiState): ConversationSummaryUi {
            val lastMessage = state.messages.lastOrNull()
            val key = ConversationSummaryKey(
                title = conversationTitles[id].orEmpty(),
                previewInput = conversationSummaryPreviewInput(lastMessage),
                createdAtMillis = conversationCreatedAt[id],
                updatedAtMillis = conversationUpdatedAt[id],
                isPinned = id in conversationPinned,
                // 暂停不算回复中：暂停后运行已挂起，抽屉行尾不应继续转 loading。
                isActiveRun = state.isStreaming && !state.isPaused,
                hasCompletionMarker = id in conversationCompletionMarkers,
                isWaitingQuestion = id in conversationWaitingQuestionIds,
                isDispatchManaged = id in conversationDispatchMarks,
                isDispatchConsole = id == consoleId,
                folderId = conversationFolderIds[id],
                environment = environment,
            )
            // The open conversation's preview changes on every token. Keep the last
            // summary while it is streaming so the manage list is not rebuilt per delta,
            // but never keep its old day/locale/zone after the label environment changes.
            val cached = if (state.isStreaming && id == selectedConversationId) {
                conversationSummaryCache.current(id, environment)?.let { summary ->
                    // 流式增量只改预览，预览/时间标签沿用缓存；运行态必须实时反映，
                    // 否则新开的会话一开始回复时缓存里的 isActiveRun 仍是 false，抽屉永远不显示 loading。
                    // 等待提问同理：提问/解答都在流式期间发生，随 key 实时覆盖。
                    summary.copy(
                        isActiveRun = key.isActiveRun,
                        isWaitingQuestion = key.isWaitingQuestion,
                        isDispatchConsole = key.isDispatchConsole,
                    )
                }
            } else {
                null
            }
            return cached ?: conversationSummaryCache.getOrBuild(id, key) {
                val realTitle = conversationTitles[id].orEmpty().ifBlank {
                    appContext.getString(R.string.conversation_unnamed)
                }
                ConversationSummaryUi(
                    id = id,
                    title = realTitle,
                    // 只有展示名固定；底层真实标题仍随自动标题/重命名变化。
                    displayTitle = if (id == consoleId) dispatchConsoleTitle() else realTitle,
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> appContext.getString(
                            when (lastMessage.code) {
                                SystemNoticeCode.Stopped -> R.string.system_notice_stopped
                                SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
                                SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
                                SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
                                SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
                                SystemNoticeCode.DecisionFallback -> R.string.system_notice_decision_fallback
                                SystemNoticeCode.Completed -> R.string.system_notice_completed
                            },
                        )
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        else -> appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    // 列表时间取「最后活动时间」：原先优先取 createdAt，继续对话后侧栏时间标签
                    // 永远停在建会话那一刻，表现为用户看到的「对话记录时间不动」。
                    timeLabel = (conversationUpdatedAt[id] ?: conversationCreatedAt[id])?.let { timestamp ->
                        ConversationTimeLabels.label(
                            timestampMillis = timestamp,
                            nowMillis = nowMillis,
                            locale = locale,
                            timeZone = timeZone,
                            use24HourClock = use24HourClock,
                            yesterdayLabel = appContext.getString(R.string.time_yesterday),
                            recentLabel = appContext.getString(R.string.time_recent),
                        )
                    } ?: appContext.getString(R.string.time_recent),
                    updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                    createdAtMillis = conversationCreatedAt[id] ?: conversationUpdatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    isPinned = id in conversationPinned,
                    isActiveRun = state.isStreaming && !state.isPaused,
                    hasCompletionMarker = id in conversationCompletionMarkers,
                    isWaitingQuestion = id in conversationWaitingQuestionIds,
                    isDispatchConsole = id == consoleId,
                    folderId = conversationFolderIds[id],
                    // 「今天」分组同样按最后活动判定，否则今天继续聊过的老会话仍被归到旧日期。
                    isToday = ConversationTimeLabels.isToday(
                        timestampMillis = conversationUpdatedAt[id] ?: conversationCreatedAt[id] ?: 0L,
                        nowMillis = nowMillis,
                        timeZone = timeZone,
                    ),
                )
            }
        }
        val summaries = conversationsById.entries
            .filterNot { (id, _) -> id in conversationDeletedAt }
            .sortedWith(
                compareByDescending<Map.Entry<String, AgentChatHomeUiState>> { (id, _) ->
                    id in conversationPinned
                }.thenByDescending { (id, _) ->
                    // 排序也按最后活动：否则刚聊过的会话会被压在旧会话下面。
                    conversationUpdatedAt[id] ?: conversationCreatedAt[id] ?: 0L
                },
            )
            .map { (id, state) -> buildSummary(id, state) }
        val trashSummaries = conversationsById.entries
            .filter { (id, _) -> id in conversationDeletedAt }
            .sortedWith(
                compareByDescending<Map.Entry<String, AgentChatHomeUiState>> { (id, _) ->
                    conversationDeletedAt[id] ?: 0L
                }.thenBy { (id, _) -> id },
            )
            .map { (id, state) ->
                // 回收站行的副文本是「删除于 …」而不是会话时间：copy 覆盖展示字段，
                // 不改 ConversationSummaryKey，缓存照常命中。
                buildSummary(id, state).copy(timeLabel = trashDeletedLabel(id, nowMillis))
            }
        val folderVisible = summaries.filterForFolder(selectedFolderId)
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            // Keep the complete folder source. The pane derives search results on
            // every edit; storing a filtered subset makes deletions unable to restore chats.
            conversations = folderVisible,
            folders = conversationFolders,
            selectedFolderId = selectedFolderId,
            historyConversations = summaries,
            trashConversations = trashSummaries,
        )
    }

    /** 回收站行的副文本：按软删除时间生成「今天/昨天/N 天前删除」。 */
    private fun trashDeletedLabel(id: String, nowMillis: Long): String {
        val deletedAt = conversationDeletedAt[id]
            ?: return appContext.getString(R.string.time_recent)
        val deletedDate = Instant.ofEpochMilli(deletedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return when (val days = ChronoUnit.DAYS.between(deletedDate, today)) {
            in Long.MIN_VALUE..0L -> appContext.getString(R.string.trash_deleted_today)
            1L -> appContext.getString(R.string.trash_deleted_yesterday)
            else -> appContext.getString(R.string.trash_deleted_days_ago, days)
        }
    }

    private fun retainDeletedConversation(
        messages: List<AgentChatMessageUi>,
        timestampMillis: Long?,
    ) {
        val usage = conversationTokenUsage(messages)
        pendingRetiredUsage = ConversationTokenUsageUi(
            inputTokens = pendingRetiredUsage.inputTokens + usage.inputTokens,
            outputTokens = pendingRetiredUsage.outputTokens + usage.outputTokens,
            cachedTokens = pendingRetiredUsage.cachedTokens + usage.cachedTokens,
        )
        pendingRetiredConversations += 1
        pendingRetiredMessages += messages.count { it is UserMessageUi || it is AgentMessageUi }
        val dayMillis = timestampMillis ?: 0L
        if (dayMillis <= 0L) return
        val day = java.time.Instant.ofEpochMilli(dayMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
        pendingRetiredHeatmap = pendingRetiredHeatmap + (day to ((pendingRetiredHeatmap[day] ?: 0) + 1))
    }

    private data class ConversationSaveSnapshot(
        val selected: String?,
        val conversations: Map<String, AgentChatHomeUiState>,
        val titles: Map<String, String>,
        val timestamps: Map<String, Long>,
        val folderIds: Map<String, String>,
        val pinnedIds: Set<String>,
        val completionMarkerIds: Set<String>,
        val deletedAt: Map<String, Long>,
        val folders: List<ConversationFolderUi>,
        val retired: ConversationTokenUsageUi,
        val retiredConversations: Int,
        val retiredMessages: Int,
        val retiredHeatmap: Map<java.time.LocalDate, Int>,
        /** null means every loaded conversation rewrites its content (the historical behavior). */
        val contentConversationIds: Set<String>? = null,
    ) {
        fun mergeRetiredFrom(older: ConversationSaveSnapshot): ConversationSaveSnapshot = copy(
            retired = ConversationTokenUsageUi(
                inputTokens = retired.inputTokens + older.retired.inputTokens,
                outputTokens = retired.outputTokens + older.retired.outputTokens,
                cachedTokens = retired.cachedTokens + older.retired.cachedTokens,
            ),
            retiredConversations = retiredConversations + older.retiredConversations,
            retiredMessages = retiredMessages + older.retiredMessages,
            retiredHeatmap = (retiredHeatmap.keys + older.retiredHeatmap.keys).associateWith {
                (retiredHeatmap[it] ?: 0) + (older.retiredHeatmap[it] ?: 0)
            },
            // Never narrow an older pending snapshot: it may be the only write scheduled for a
            // conversation whose content changed before this send.
            contentConversationIds = when {
                contentConversationIds == null || older.contentConversationIds == null -> null
                else -> contentConversationIds + older.contentConversationIds
            },
        )
    }

    private fun persistConversations(
        allowArchive: Boolean = false,
        contentConversationIds: Set<String>? = null,
        onSaved: (() -> Unit)? = null,
    ): Deferred<Boolean> {
        if (conversationArchiveBusy && !allowArchive) {
            return kotlinx.coroutines.CompletableDeferred<Boolean>().also { deferredArchiveSaves += it to onSaved }
        }
        if (io.github.mangi.eta.agent.runtime.AgentExecutionService.backupMaintenance) {
            return kotlinx.coroutines.CompletableDeferred(false)
        }
        // Saves are all-or-nothing: AgentConversationStore.save deletes every stored
        // conversation absent from the snapshot it receives. Startup hydration publishes an
        // intentionally empty map for the first frames, so persisting during that window
        // would erase every stored conversation. Fail closed: report "not saved" and let the
        // caller retry once the real map is published. Hydration that fails leaves this
        // false deliberately — refusing a save is recoverable, silently wiping data is not.
        if (!conversationsHydrated) {
            AndroidAgentLogger.warnThrottled("conversation_persist_before_hydration") {
                "Refused a conversation save before startup hydration completed"
            }
            return kotlinx.coroutines.CompletableDeferred(false)
        }
        return synchronized(persistenceLock) {
            val snapshot = ConversationSaveSnapshot(
                selectedConversationId, conversationsById, conversationTitles, conversationUpdatedAt,
                conversationFolderIds, conversationPinned, conversationCompletionMarkers, conversationDeletedAt,
                conversationFolders,
                pendingRetiredUsage, pendingRetiredConversations, pendingRetiredMessages, pendingRetiredHeatmap,
                contentConversationIds,
            )
            pendingRetiredUsage = ConversationTokenUsageUi()
            pendingRetiredConversations = 0
            pendingRetiredMessages = 0
            pendingRetiredHeatmap = emptyMap()
            persistenceQueue.submit(snapshot, onSaved)
        }
    }

    private suspend fun writeConversationSnapshot(snapshot: ConversationSaveSnapshot): Boolean {
        var retirementCommitted = false
        try {
            conversationPersistenceMutex.withLock {
                AgentConversationStore.save(
                    context = appContext, selectedConversationId = snapshot.selected,
                    conversationsById = snapshot.conversations, titles = snapshot.titles,
                    updatedAt = snapshot.timestamps, folderIds = snapshot.folderIds,
                    pinnedIds = snapshot.pinnedIds, completionMarkerIds = snapshot.completionMarkerIds,
                    folders = snapshot.folders, deletedAt = snapshot.deletedAt,
                    contentConversationIds = snapshot.contentConversationIds,
                )
                SettingsDataStore.addRetiredUsage(
                    inputTokens = snapshot.retired.inputTokens,
                    outputTokens = snapshot.retired.outputTokens,
                    cachedTokens = snapshot.retired.cachedTokens,
                    conversations = snapshot.retiredConversations,
                    messages = snapshot.retiredMessages,
                    heatmap = snapshot.retiredHeatmap,
                )
                retirementCommitted = true
            }
            withContext(Dispatchers.Main.immediate) {
                releasePersistedConversationContent(snapshot.conversations, snapshot.contentConversationIds)
                pendingSubAgentDraftBindings.toMap().forEach { (id, draft) ->
                    if (id in snapshot.conversations && id in conversationsById) {
                        if (conversationSubAgentPreferences.confirmBoundDraft(draft, SubAgentConfigKey.Conversation(id))) {
                            pendingSubAgentDraftBindings.remove(id)
                        }
                    }
                }
            }
            lastConversationPersistenceError = null
            return true
        } catch (cancelled: CancellationException) {
            if (!retirementCommitted) restorePendingRetiredUsage(snapshot)
            throw cancelled
        } catch (failure: Exception) {
            if (!retirementCommitted) restorePendingRetiredUsage(snapshot)
            AndroidAgentLogger.error(
                "Agent conversation persistence failed: type=${failure.safeLogType()} message=${failure.message}"
            )
            lastConversationPersistenceError = failure.message
            return false
        }
    }

    private suspend fun restorePendingRetiredUsage(snapshot: ConversationSaveSnapshot) {
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
            synchronized(persistenceLock) {
                pendingRetiredUsage = ConversationTokenUsageUi(
                    inputTokens = pendingRetiredUsage.inputTokens + snapshot.retired.inputTokens,
                    outputTokens = pendingRetiredUsage.outputTokens + snapshot.retired.outputTokens,
                    cachedTokens = pendingRetiredUsage.cachedTokens + snapshot.retired.cachedTokens,
                )
                pendingRetiredConversations += snapshot.retiredConversations
                pendingRetiredMessages += snapshot.retiredMessages
                snapshot.retiredHeatmap.forEach { (day, count) ->
                    pendingRetiredHeatmap = pendingRetiredHeatmap + (day to ((pendingRetiredHeatmap[day] ?: 0) + count))
                }
            }
        }
    }

    private fun currentBoundProviderId(): String =
        modelPickerState.selectedModel?.providerId.orEmpty()

    private fun currentBoundModelId(): String =
        modelPickerState.selectedModel?.id.orEmpty()

    /**
     * 新会话默认绑定的助手。全局活动助手会被「选中会话」联动改写（[applyConversationAssistant]
     * 把当前会话助手登记为全局活动），用户用过调度台后全局活动即调度台；若原样继承，手动新建的
     * 普通会话会带上调度身份——只派发不执行，行为完全不对。因此调度台身份不外溢，回落到默认
     * 助手（被删时退而取任一非调度台助手）；自定义助手不受影响，照旧继承。
     *
     * 系统语音助手等外部入口走同一规则（[AssistantRepository.plainActiveId]），规则只此一份。
     */
    private fun currentBoundAssistantId(): String = AssistantRepository.plainActiveId()

    private fun resolvedAssistantId(state: AgentChatHomeUiState): String {
        val stored = state.assistantId
        if (stored.isNotBlank() && AssistantRepository.profile(stored) != null) return stored
        return currentBoundAssistantId()
    }

    private fun applyConversationAssistant(assistantId: String, persist: Boolean) {
        if (homeState.assistantId != assistantId) {
            updateCurrentConversation(homeState.copy(assistantId = assistantId))
            if (persist) persistConversations()
        }
        // Bind the local preview immediately to the selected conversation assistant. Remote
        // preference sync may suspend/fail; it must not keep the previous assistant's count.
        refreshRequestOverhead()
        if (AssistantRepository.activeId.value == assistantId) {
            return
        }
        scope.launch(Dispatchers.IO) {
            AssistantRepository.select(assistantId)
            RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            withContext(Dispatchers.Main) {
                if (resolvedAssistantId(homeState) != assistantId) return@withContext
                refreshRequestOverhead()
            }
        }
    }

    // Copy the active conversation once; later edits remain local to each conversation.
    private fun newDraftChatState(): AgentChatHomeUiState =
        emptyChatState(false)
            .copy(
                providerId = currentBoundProviderId(),
                modelId = currentBoundModelId(),
                assistantId = currentBoundAssistantId(),
                reasoningEffort = homeState.reasoningEffort,
                thinkingEnabled = homeState.reasoningEffort.enablesReasoning,
                availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            ).withCurrentGptSpeedBinding()

    private fun restoreConversationRuntimeModel() {
        modelBindingGeneration++
        refreshBoundModelPicker()
        val assistantId = resolvedAssistantId(homeState)
        applyConversationAssistant(assistantId, persist = homeState.assistantId != assistantId)
    }

    private suspend fun runtimeConfigForBoundModel(
        state: AgentChatHomeUiState,
        assistant: io.github.mangi.eta.data.model.AssistantProfile? = null,
    ): AgentModelClient.ModelConfig? = runtimeConfigForBoundModel(state.providerId, state.modelId, assistant)

    private suspend fun runtimeConfigForBoundModel(
        providerId: String,
        modelId: String,
        assistant: io.github.mangi.eta.data.model.AssistantProfile? = null,
    ): AgentModelClient.ModelConfig? {
        if (providerId.isBlank() || modelId.isBlank()) return null
        return RuntimeConfigRepository.configForProviderAndModel(providerId, modelId, assistant)
    }

    private companion object {
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        const val LEGACY_STOPPED_ERROR = "已停止"
        const val SYNTHETIC_STATUS_STOPPED = "eta_status:stopped"
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        // 这与 Kimi 将流式数据和视觉动画分层的做法一致。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 150L
        // A 120 Hz frame is 8.33 ms; reserve most of it for Compose/layout/draw.
        const val STREAM_EVENT_FRAME_BUDGET_NS = 2_000_000L

        /** 一天的毫秒数，用于回收站留存期计算。 */
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** 调度台归档快照 id 前缀：与固定 id `dispatch-console` 区分，也不会被调度台保护逻辑拦截。 */
        const val DISPATCH_CONSOLE_ARCHIVE_PREFIX = "dispatch-console-archive-"

        /** 归档快照内消息 id 前缀：`archive-<16位序号>-<原 id>`。 */
        const val ARCHIVE_MESSAGE_ID_PREFIX = "archive-"

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }

    private fun assignPendingFolder(conversationId: String) {
        val folderId = pendingNewConversationFolderId
            ?.takeIf { id -> conversationFolders.any { it.id == id } }
            ?: return
        conversationFolderIds = conversationFolderIds + (conversationId to folderId)
    }

    fun updateAutoCompressEnabled(enabled: Boolean) {
        autoCompressPreference.setEnabled(enabled)
    }

    fun selectContextTask(taskId: String?, capturedOwnerId: String?) {
        if (capturedOwnerId == null || capturedOwnerId != selectedConversationId) return
        ownerContext(capturedOwnerId).select(capturedOwnerId, taskId)
        publishOwnerContext(capturedOwnerId)
    }

    /** Capture the selected task at click time; later selection changes cannot retarget this request. */
    fun compressCurrentConversation(
        onFinished: (Boolean) -> Unit,
    ) {
        if (rejectConversationArchiveMutation()) {
            onFinished(false)
            return
        }
        val targetId = homeState.selectedContextTaskId
        if (targetId != null) {
            val child = homeState.childContexts.firstOrNull { it.taskId == targetId }
            val ownerId = selectedConversationId
            if (child == null || child.status != "running" || child.role in setOf("image_generation", "video_generation") || ownerId == null) {
                Toast.makeText(appContext, appContext.getString(R.string.appst_compact_subtask_unsupported), Toast.LENGTH_LONG).show()
                onFinished(false)
                return
            }
            onFinished(true)
            scope.launch(Dispatchers.IO) {
                val sent = runCatching {
                    // 模型与端点统一取压缩设置页里的偏好，不再有独立的手动压缩配置。
                    AgentChildTaskGroups.requestCompact(ownerId, targetId, keepRecentFor(), null)
                }.getOrDefault(false)
                if (!sent) withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.appst_compact_request_unconfirmed), Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val runInFlight = homeState.isStreaming || homeState.isPaused
        val busyWithOtherConversation = compressionJob?.isActive == true &&
            compressionJobConversationId != selectedConversationId
        if (compressionJob?.isActive == true && !busyWithOtherConversation) {
            onFinished(true)
            return
        }
        if (homeState.isCompressingContext || homeState.isWaitingForCompression) {
            onFinished(true)
            return
        }
        val keepRecentMessages = keepRecentFor()
        if (!runInFlight &&
            io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(homeState.history,
                compressionBoundaryWindow(boundCompressionWindow(homeState))) <= 0
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.compress_conversation_nothing_to_compress),
                Toast.LENGTH_SHORT,
            ).show()
            onFinished(false)
            return
        }
        val conversationId = selectedConversationId
        onFinished(true)
        if (runInFlight) {
            requestInRunCompress(
                conversationId,
                keepRecentMessages,
            )
            return
        }
        setConversationWaitingForCompression(conversationId, true)
        pendingManualCompress = PendingManualCompress(
            conversationId = conversationId,
            keepRecent = keepRecentMessages,
            resumeAfter = false,
        )
        startPendingManualCompress()
    }


    private fun requestInRunCompress(
        conversationId: String?,
        keepRecent: Int,
    ) {
        val runId = runIdForConversation(conversationId) ?: run {
            Toast.makeText(appContext, appContext.getString(R.string.appst_compact_run_ended), Toast.LENGTH_LONG).show()
            return
        }
        if (conversationId != null && !pendingInRunCompactConversationIds.add(conversationId)) return
        // Show queued maintenance alongside live output until Runtime reaches a safe boundary.
        setConversationWaitingForCompression(conversationId, true)
        val snapshot = conversationState(conversationId) ?: homeState
        scope.launch(Dispatchers.IO) {
            val sent = try {
                val model = resolveCompressModelConfig(
                    fallback = runtimeConfigForBoundModel(snapshot, assistant = null),
                )
                model != null && AgentRuntimeClient(appContext, AndroidAgentLogger).compactRun(
                    runId = runId,
                    keepRecent = keepRecent,
                    compressModelConfig = model,
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                false
            }
            if (!sent) withContext(Dispatchers.Main) {
                conversationId?.let(pendingInRunCompactConversationIds::remove)
                setConversationWaitingForCompression(conversationId, false)
                Toast.makeText(appContext, appContext.getString(R.string.appst_compact_request_not_delivered), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun runIdForConversation(conversationId: String?): String? {
        if (conversationId == null) return activeRunIdForSelectedConversation()
        return runConversationIds.entries.firstOrNull { it.value == conversationId }?.key
    }

    private fun onConversationRunSettled(conversationId: String, flushQueue: Boolean = true) {
        // 压缩与续发互斥：队列已有待发消息时优先续发，不再为本轮次排压缩。
        if (flushQueue && flushQueuedMessages(conversationId)) return
        val pending = pendingManualCompress
        if (pending != null && (pending.conversationId == null || pending.conversationId == conversationId)) {
            startPendingManualCompress()
        } else {
            scheduleAutoCompress(conversationId, allowRepeat = true)
        }
    }

    private fun isCompressionBlockingSend(): Boolean {
        val pending = pendingManualCompress
        return conversationCompressionBlocksSend(
            conversationId = selectedConversationId,
            isCompressingContext = homeState.isCompressingContext,
            jobActive = compressionJob?.isActive == true,
            jobConversationId = compressionJobConversationId,
            hasPending = pending != null,
            pendingConversationId = pending?.conversationId,
        )
    }

    private fun rejectSendIfCompressing(): Boolean {
        if (!isCompressionBlockingSend()) return false
        Toast.makeText(
            appContext,
            appContext.getString(R.string.compress_conversation_in_progress),
            Toast.LENGTH_SHORT,
        ).show()
        return true
    }

    private fun shouldKeepCompressingIndicator(conversationId: String?): Boolean {
        val pending = pendingManualCompress
        return keepsCompressingIndicator(
            conversationId = conversationId,
            jobActive = compressionJob?.isActive == true,
            jobConversationId = compressionJobConversationId,
            hasPending = pending != null,
            pendingConversationId = pending?.conversationId,
        )
    }

    private fun startPendingManualCompress() {
        val request = pendingManualCompress ?: return
        // A running job may be another conversation's auto compaction (or the caller's own
        // finally block); the new job joins it below, so queue behind it instead of dropping.
        pendingManualCompress = null
        val resumeAfter = request.resumeAfter
        val previous = compressionJob
        compressionJobConversationId = request.conversationId
        compressionJob = scope.launch(Dispatchers.IO) {
            previous?.join()
            try {
                val snapshot = withContext(Dispatchers.Main) {
                    request.conversationId?.let { conversationState(it) }
                        ?: homeState.takeIf { selectedConversationId == request.conversationId }
                } ?: return@launch
                if (snapshot.isStreaming || snapshot.isPaused) {
                    withContext(Dispatchers.Main) {
                        pendingManualCompress = request
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    setConversationCompressing(request.conversationId, true)
                }
                val originalHistory = snapshot.history
                val fallback = runtimeConfigForBoundModel(snapshot, assistant = null)
                val modelConfig = resolveCompressModelConfig(fallback = fallback)
                if (modelConfig == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.compress_conversation_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                if (io.github.mangi.eta.agent.model.AgentCompressionBoundary.selectStart(originalHistory,
                        compressionBoundaryWindow(fallback?.contextWindow)) <= 0) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, appContext.getString(
                            R.string.compress_conversation_nothing_to_compress), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                var summaryCommitted = false
                val compressed = tryCompressHistory(
                    history = originalHistory,
                    compressModelConfig = modelConfig,
                    sourceModelConfig = fallback,
                    keepRecent = request.keepRecent,
                    conversationId = request.conversationId,
                    contextWindow = fallback?.contextWindow,
                    onSummaryCommitted = { summaryCommitted = true },
                )
                withContext(Dispatchers.Main) {
                    if (compressed == originalHistory) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.compress_conversation_failed),
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@withContext
                    }
                    applyManualCompressedHistory(
                        request.conversationId,
                        originalHistory,
                        compressed,
                        compressorLabel(modelConfig),
                        summaryCommitted,
                    )
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (pendingManualCompress == null) {
                        if (resumeAfter) {
                            resumeLastTurnAfterCompress(request.conversationId)
                        }
                        val resumed = request.conversationId?.let { conversationState(it)?.isStreaming } == true
                        if (!resumed) {
                            setConversationCompressing(request.conversationId, false)
                        }
                    }
                }
            }
        }
    }

    private fun resumeLastTurnAfterCompress(conversationId: String?) {
        if (conversationId != selectedConversationId) return
        if (homeState.isStreaming || homeState.isPaused) return
        if (homeState.hasPartialAssistantAfterLastUser()) {
            continuePartialTurnAfterCompress(conversationId)
            return
        }
        val lastUser = homeState.messages.lastOrNull { message ->
            message is UserMessageUi && !message.isSteerSupplement()
        } as? UserMessageUi ?: return
        regenerateMessage(lastUser.id, ignoreCompression = true)
    }

    private fun continuePartialTurnAfterCompress(conversationId: String?) {
        val id = conversationId ?: return
        val state = conversationState(id) ?: return
        val partial = state.messages.lastOrNull { message ->
            message is AgentMessageUi && message.content.isNotBlank()
        } as? AgentMessageUi ?: return
        val continuedHistory = io.github.mangi.eta.agent.model.AgentTurnIdentity.migrate(
            AgentConversationRevisionReducer.historyWithTrailingPartial(state.history, partial))
        val prompt = RESUME_AFTER_COMPRESS_PROMPT
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = "user-$runId-supplement-resume",
            content = prompt,
        )
        launchConversationRun(
            conversationId = id,
            runId = runId,
            prompt = prompt,
            images = emptyList(),
            history = continuedHistory,
            userHistoryMessage = AgentModelClient.ConversationMessage(
                role = "user",
                content = prompt,
            ),
            messages = state.messages + userMessage,
            state = state,
            reasoningEffort = state.reasoningEffort,
            skipAutoCompress = true,
            logicalTurnId = continuedHistory.lastOrNull { it.turnId.isNotBlank() }?.turnId ?: runId,
            // 压缩后续写的 prompt 是占位串，不能当成本轮用户素材改写标题。
            refreshTitleAfterTurn = false,
        )
    }

    private fun freezeStreamingMessages(
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> = messages.map { message ->
        when (message) {
            is AgentMessageUi -> if (message.isStreaming) message.copy(isStreaming = false) else message
            is ThinkingMessageUi -> if (message.isStreaming) message.copy(isStreaming = false) else message
            else -> message
        }
    }

    private fun applyManualCompressedHistory(
        conversationId: String?,
        originalHistory: List<AgentModelClient.ConversationMessage>,
        compressedHistory: List<AgentModelClient.ConversationMessage>,
        compressorLabel: String = "",
        summaryCommitted: Boolean = false,
    ) {
        if (compressedHistory == originalHistory) return
        if (!summaryCommitted) {
            val current = if (conversationId == null) homeState.takeIf { selectedConversationId == null }
                else conversationState(conversationId)
            if (current == null || current.isStreaming || current.isPaused || current.history != originalHistory) return
            val pruned = current.copy(history = compressedHistory)
            if (conversationId == null) homeState = pruned else updateConversation(conversationId, pruned)
            persistConversations()
            return
        }
        if (conversationId != null) {
            val current = conversationState(conversationId) ?: return
            if (current.isStreaming || current.isPaused) return
            if (current.history != originalHistory) return
            updateConversation(
                conversationId,
                current.copy(
                    history = compressedHistory,
                    livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
                    messages = AgentContextCompactionUi.applyMarker(
                        messages = current.messages,
                        originalHistory = originalHistory,
                        compressedHistory = compressedHistory,
                        compressorLabel = compressorLabel,
                    ),
                ),
            )
        } else if (
            selectedConversationId == null &&
            !homeState.isStreaming &&
            !homeState.isPaused &&
            homeState.history == originalHistory
        ) {
            homeState = homeState.copy(
                history = compressedHistory,
                livePromptTokens = null,
                livePromptIsProjected = false,
                cloudHistoryTokens = null, cloudRequestOverheadTokens = null, contextBudgetReceiptTokens = null,
                contextAwaitingReceipt = true, contextHasStarted = true,
                cloudReceiptRequestId = null, cloudRouteSignature = null,
                contextReceiptEvidence = null, receiptPredictionTokens = null,
                messages = AgentContextCompactionUi.applyMarker(
                    messages = homeState.messages,
                    originalHistory = originalHistory,
                    compressedHistory = compressedHistory,
                    compressorLabel = compressorLabel,
                ),
            )
        } else {
            return // No accepted update: do not persist or announce a stale result.
        }
        billedOverheadConversationId = conversationId
        billedOverheadTokens = null
        persistConversations()
        showCompressionCompletedToast(conversationId, originalHistory, compressedHistory, compressorLabel)
    }
}

private val RESUME_AFTER_COMPRESS_PROMPT =
    AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT

private data class PendingManualCompress(
    val conversationId: String?,
    val keepRecent: Int,

    val resumeAfter: Boolean = false,
)

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

/**
 * 运行结束时是否保留「正在压缩」提示。只认属于这个会话的压缩任务或待办：
 * 别的会话在压缩时，这里结束的回复不能被标成压缩中，否则那边压完不会回来清它。
 * 会话未知（null）时保持旧行为，按匹配处理。
 */
internal fun keepsCompressingIndicator(
    conversationId: String?,
    jobActive: Boolean,
    jobConversationId: String?,
    hasPending: Boolean,
    pendingConversationId: String?,
): Boolean {
    fun matches(owner: String?) = conversationId == null || owner == null || owner == conversationId
    if (jobActive && matches(jobConversationId)) return true
    return hasPending && matches(pendingConversationId)
}

private fun String.isReadOnlyExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

private fun archiveConversationId(source: String, conversationKey: String): String {
    val prefix = if (source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
        ASSISTANT_CONVERSATION_PREFIX
    } else {
        EXTERNAL_ARCHIVE_CONVERSATION_PREFIX
    }
    return prefix + stableArchiveId("$source:$conversationKey")
}

private const val ASSISTANT_CONVERSATION_PREFIX = "assistant-"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

/**
 * 工具页状态：按**界面能力**（[ToolCapabilities]）分卡，不是按模型组。
 *
 * 一张卡恰好一个开关，`switchEnabled` 直接读那个键。模型侧 [AgentToolGroups] 拆得更细
 * （日历、设备状态各有独立延迟组），这里只在卡片层面合并——两者不再是 1:1，这正是
 * 「关掉日历卡、设备控制也跟着关」那个 bug 的根源。
 */
internal fun buildToolsState(context: Context): AgentToolsUiState {
    val disabled = Prefs.disabledTools()
    val catalog = toolItemCatalog(context)
    // 外部闸门（设置页里的执行策略开关）：关掉时卡内对应工具行置灰并指路，
    // 避免行还亮着、点了开关却没有任何反应。
    val blockedBy = ToolCapabilities.externalGates
        .filter { !ToolGroupSwitch.isEnabled(it.switchKey) }
        .flatMap { gate -> gate.gatedTools.map { it to context.getString(gate.labelRes) } }
        .toMap()
    val blockedTemplate = if (blockedBy.isNotEmpty()) {
        context.getString(R.string.tools_row_blocked_by_setting)
    } else null
    return AgentToolsUiState(
        capabilities = ToolCapabilities.all.map { capability ->
            // 按 groupIds 的顺序逐组取工具：groupIds 表达的是卡内语义先后。
            // 若直接按目录的全局顺序过滤，日历写工具（目录里排在 device 段）会跑到
            // 个人数据卡的最前面，看起来像是设备能力。
            val tools = capability.groupIds.flatMap { groupId ->
                val names = AgentToolGroups.toolsOfGroup(groupId)
                catalog.filter { io.github.mangi.eta.ui.model.actualToolName(it.id) in names }
            }.map {
                val name = io.github.mangi.eta.ui.model.actualToolName(it.id)
                it.copy(
                    enabled = name !in disabled,
                    blockedReason = blockedBy[name]?.let { label ->
                        blockedTemplate?.format(label)
                    },
                )
            }
            ToolCapabilityUi(
                id = capability.id,
                title = context.getString(capability.titleRes),
                summary = context.getString(capability.summaryRes),
                tools = tools,
                switchKey = capability.switchKey,
                switchEnabled = ToolGroupSwitch.isEnabled(capability.switchKey),
            )
        },
    )
}

/**
 * 工具清单页的展示顺序与文案。「属于哪张卡」由 [ToolCapabilities] 的 groupIds 决定，
 * 这里只管顺序与措辞，避免 UI 归属与文案共用一个函数后各自漂移。
 */
private fun toolItemCatalog(context: Context): List<ToolItemUi> = listOf(
    // ── basic ──
        ToolItemUi("search_apps", context.getString(R.string.tool_ui_search_apps_897fdf), context.getString(R.string.tool_ui_query_installed_applications_by_name_or_package__32b004)),
        ToolItemUi("get_current_context", context.getString(R.string.tool_ui_time_and_location_693893), context.getString(R.string.tool_ui_read_system_time_and_recent_location_b9f4ae)),
        ToolItemUi("launch_app", context.getString(R.string.tool_ui_open_app_7c65e7), context.getString(R.string.tool_ui_start_the_specified_package_name_or_application__beabff)),
        ToolItemUi("open_uri", context.getString(R.string.tool_ui_open_with_app_32c24e), context.getString(R.string.tool_ui_explicitly_hand_over_links_or_deep_links_to_exte_35ff26)),
        ToolItemUi("text_to_speech", context.getString(R.string.tool_ui_text_to_speech), context.getString(R.string.tool_ui_text_to_speech_summary)),
        ToolItemUi("memory_get", context.getString(R.string.tool_ui_read_memory_979135), context.getString(R.string.tool_ui_paged_to_read_or_retrieve_long_term_memory_in_me_88afc4)),
        ToolItemUi("ask_user", context.getString(R.string.tool_ui_ask_user), context.getString(R.string.tool_ui_ask_user_summary)),
        ToolItemUi("skills_list", context.getString(R.string.tool_ui_skills_list), context.getString(R.string.tool_ui_skills_list_summary)),
        ToolItemUi("skills_read", context.getString(R.string.tool_ui_skills_read), context.getString(R.string.tool_ui_skills_read_summary)),
        ToolItemUi("skills_read_resource", context.getString(R.string.tool_ui_skills_read_resource), context.getString(R.string.tool_ui_skills_read_resource_summary)),
        ToolItemUi("skills_list_curated", context.getString(R.string.tool_ui_skills_list_curated), context.getString(R.string.tool_ui_skills_list_curated_summary)),
    // ── screen ──
        ToolItemUi("observe_screen", context.getString(R.string.tool_ui_watch_the_screen_e70f2a), context.getString(R.string.tool_ui_read_the_current_node_and_attach_the_original_im_df1fec)),
        ToolItemUi("tap", context.getString(R.string.tool_tap), context.getString(R.string.tool_ui_click_by_coordinate_area_2ad961)),
        ToolItemUi("tap_element", context.getString(R.string.tool_ui_click_element_7a3d91), context.getString(R.string.tool_ui_click_on_the_most_recently_observed_node_b4cf5a)),
        ToolItemUi("tap_area", context.getString(R.string.tool_ui_click_area_cbaa08), context.getString(R.string.tool_ui_click_by_coordinate_area_2ad961)),
        ToolItemUi("long_press", context.getString(R.string.tool_ui_long_press_f7a417), context.getString(R.string.tool_ui_long_press_on_coordinates_or_elements_796384)),
        ToolItemUi("long_press_element", context.getString(R.string.tool_long_press_element), context.getString(R.string.tool_ui_long_press_on_coordinates_or_elements_796384)),
        ToolItemUi("swipe", context.getString(R.string.tool_ui_slide_3723aa), context.getString(R.string.tool_ui_perform_up_down_left_and_right_swipe_gestures_3ef0de)),
        ToolItemUi("scroll", context.getString(R.string.tool_ui_scroll_220e68), context.getString(R.string.tool_ui_scroll_the_page_or_specify_a_node_83ab24)),
        ToolItemUi("scroll_element", context.getString(R.string.tool_scroll_element), context.getString(R.string.tool_ui_scroll_the_page_or_specify_a_node_83ab24)),
        ToolItemUi("type_text", context.getString(R.string.tool_ui_type_text), context.getString(R.string.tool_ui_type_text_summary)),
        ToolItemUi("input_text", context.getString(R.string.tool_ui_enter_text_ae47ab), context.getString(R.string.tool_ui_append_or_paste_text_to_the_current_focus_1efdcc)),
        ToolItemUi("replace_text", context.getString(R.string.tool_ui_replacement_text_1a5c8d), context.getString(R.string.tool_ui_replace_text_in_focus_or_node_30d332)),
        ToolItemUi("clear_text", context.getString(R.string.tool_ui_clear_text_d4cb57), context.getString(R.string.tool_ui_clear_focus_or_node_text_3e754a)),
        ToolItemUi("paste_text", context.getString(R.string.tool_ui_paste_text_791b85), context.getString(R.string.tool_ui_reliably_enter_long_text_with_the_clipboard_b6041e)),
        ToolItemUi("set_clipboard", context.getString(R.string.tool_set_clipboard), context.getString(R.string.tool_ui_copy_text_to_the_system_clipboard)),
        ToolItemUi("get_clipboard", context.getString(R.string.tool_get_clipboard), context.getString(R.string.tool_ui_read_the_current_system_clipboard)),
        ToolItemUi("wait", context.getString(R.string.tool_wait), context.getString(R.string.tool_ui_wait_for_the_given_number_of_milliseconds)),
        ToolItemUi("wait_for_text", context.getString(R.string.tool_ui_wait_for_text_9e9a54), context.getString(R.string.tool_ui_wait_for_the_specified_text_to_appear_on_the_scr_43f9b0)),
        ToolItemUi("wait_for_package", context.getString(R.string.tool_wait_for_package), context.getString(R.string.tool_ui_wait_for_the_specified_app_to_come_to_the_foreground)),
        ToolItemUi("press_key", context.getString(R.string.tool_ui_button_02eafa), context.getString(R.string.tool_ui_system_buttons_such_as_return_homepage_recent_ta_1b4cf0)),
        ToolItemUi("open_system_panel", context.getString(R.string.tool_ui_system_panel_b0f7a3), context.getString(R.string.tool_ui_open_the_notification_bar_quick_settings_and_oth_5e51cf)),
        ToolItemUi("start_virtual_session", context.getString(R.string.tool_ui_start_virtual_session), context.getString(R.string.tool_ui_start_virtual_session_summary)),
        ToolItemUi("keep_virtual_result", context.getString(R.string.tool_ui_keep_virtual_result), context.getString(R.string.tool_ui_keep_virtual_result_summary)),
        ToolItemUi("finish_virtual_session", context.getString(R.string.tool_ui_finish_virtual_session), context.getString(R.string.tool_ui_finish_virtual_session_summary)),
        ToolItemUi("inspect_virtual_backend", context.getString(R.string.tool_ui_inspect_virtual_backend), context.getString(R.string.tool_ui_inspect_virtual_backend_summary)),
    // ── web ──
        ToolItemUi("web_search", context.getString(R.string.tool_web_search), context.getString(R.string.tool_web_search_description)),
        ToolItemUi("fetch_url", context.getString(R.string.tool_fetch_url), context.getString(R.string.tool_fetch_url_description)),
        ToolItemUi("browser_use", context.getString(R.string.tool_ui_agent_browser_a66bd5), context.getString(R.string.tool_ui_open_web_pages_off_screen_and_keep_a_takeover_br_72972e)),
        ToolItemUi("browser_read", context.getString(R.string.tool_ui_read_web_pages_4f0bb9), context.getString(R.string.tool_ui_extract_rendered_text_lists_and_links_8bdcdd)),
        ToolItemUi("browser_interact", context.getString(R.string.tool_ui_web_page_interaction_331b3f), context.getString(R.string.tool_ui_find_click_and_enter_page_elements_8f102d)),
        ToolItemUi("browser_screenshot", context.getString(R.string.tool_ui_page_screenshot_c823a2), context.getString(R.string.tool_ui_give_the_current_web_page_viewport_to_the_visual_f62274)),
    // ── terminal ──
        ToolItemUi("read_image", context.getString(R.string.tool_ui_read_pictures_ae993b), context.getString(R.string.tool_ui_read_pictures_of_known_paths_and_hand_them_over__7f9569)),
        ToolItemUi("terminal", context.getString(R.string.tool_ui_session_terminal_09c6e6), context.getString(R.string.tool_ui_user_root_shell_conversational_execution_and_asy_13c2ab)),
        ToolItemUi("run_command", context.getString(R.string.tool_ui_execute_command_bf1627), context.getString(R.string.tool_ui_directly_execute_a_single_shell_command_c40cef)),
        ToolItemUi("read_file", context.getString(R.string.tool_ui_read_file_dc995c), context.getString(R.string.tool_ui_read_the_contents_of_mobile_phone_files_bf3066)),
        ToolItemUi("write_file", context.getString(R.string.tool_ui_write_file_e620fd), context.getString(R.string.tool_ui_write_or_overwrite_mobile_files_29fae4)),
        ToolItemUi("list_directory", context.getString(R.string.tool_ui_list_directory_96e765), context.getString(R.string.tool_ui_list_directory_contents_feff30)),
        ToolItemUi("stat_file", context.getString(R.string.tool_ui_stat_file), context.getString(R.string.tool_ui_stat_file_summary)),
        ToolItemUi("edit_file", context.getString(R.string.tool_ui_edit_file), context.getString(R.string.tool_ui_edit_file_summary)),
        ToolItemUi("glob_files", context.getString(R.string.tool_ui_glob_files), context.getString(R.string.tool_ui_glob_files_summary)),
        ToolItemUi("grep_files", context.getString(R.string.tool_ui_grep_files), context.getString(R.string.tool_ui_grep_files_summary)),
    // ── device ──
        ToolItemUi("set_timer", context.getString(R.string.tool_ui_set_timer_aee60c), context.getString(R.string.tool_ui_directly_create_system_timers_up_to_24_hours_87c476)),
        ToolItemUi("delete_alarm", context.getString(R.string.tool_ui_delete_alarm), context.getString(R.string.tool_ui_delete_alarm_summary)),
        ToolItemUi("update_alarm", context.getString(R.string.tool_ui_update_alarm), context.getString(R.string.tool_ui_update_alarm_summary)),
        ToolItemUi("device_status", context.getString(R.string.tool_ui_device_status_567a4c), context.getString(R.string.tool_ui_read_power_memory_storage_and_system_version_c501d5)),
        ToolItemUi("network_info", context.getString(R.string.tool_ui_network_status_6bd556), context.getString(R.string.tool_ui_read_networking_method_and_current_wi_fi_status_68016a)),
        ToolItemUi("media_control", context.getString(R.string.tool_ui_media_control_585edc), context.getString(R.string.tool_ui_play_pause_and_switch_songs_without_operating_th_311cb8)),
        ToolItemUi("set_volume", context.getString(R.string.tool_ui_set_volume_85a691), context.getString(R.string.tool_ui_set_by_media_alarm_clock_ringtone_and_other_chan_3fcc3e)),
        ToolItemUi("set_ringer_mode", context.getString(R.string.tool_ui_set_ringer_mode), context.getString(R.string.tool_ui_set_ringer_mode_summary)),
        ToolItemUi("set_sound_mode", context.getString(R.string.tool_ui_set_sound_mode), context.getString(R.string.tool_ui_set_sound_mode_summary)),
        ToolItemUi("set_flashlight", context.getString(R.string.tool_ui_set_flashlight), context.getString(R.string.tool_ui_set_flashlight_summary)),
        ToolItemUi("set_hotspot", context.getString(R.string.tool_ui_set_hotspot), context.getString(R.string.tool_ui_set_hotspot_summary)),
        ToolItemUi("top_memory_apps", context.getString(R.string.tool_ui_memory_ranking_408ca1), context.getString(R.string.tool_ui_view_the_currently_most_occupied_processes_8646c4)),
        ToolItemUi("top_storage_apps", context.getString(R.string.tool_ui_storage_ranking_86a16c), context.getString(R.string.tool_ui_check_application_data_and_cache_usage_837e9f)),
        ToolItemUi("set_screen_timeout", context.getString(R.string.tool_ui_set_screen_timeout), context.getString(R.string.tool_ui_set_screen_timeout_summary)),
        ToolItemUi("set_alarm_enabled", context.getString(R.string.tool_ui_set_alarm_enabled), context.getString(R.string.tool_ui_set_alarm_enabled_summary)),
        ToolItemUi("update_alarm_time", context.getString(R.string.tool_ui_update_alarm_time), context.getString(R.string.tool_ui_update_alarm_time_summary)),
        ToolItemUi("set_setting", context.getString(R.string.tool_ui_modify_system_settings_ae1f4c), context.getString(R.string.tool_ui_modify_android_settings_keys_91a37e)),
        ToolItemUi("set_device_state", context.getString(R.string.tool_ui_network_switch_834347), context.getString(R.string.tool_ui_directly_control_wi_fi_or_bluetooth_4fa0b9)),
        ToolItemUi("set_do_not_disturb", context.getString(R.string.tool_ui_set_do_not_disturb), context.getString(R.string.tool_ui_set_do_not_disturb_summary)),
        ToolItemUi("set_brightness", context.getString(R.string.tool_ui_set_brightness), context.getString(R.string.tool_ui_set_brightness_summary)),
        ToolItemUi("screen_action", context.getString(R.string.tool_ui_screen_action), context.getString(R.string.tool_ui_screen_action_summary)),
        ToolItemUi("manage_app", context.getString(R.string.tool_ui_manage_app), context.getString(R.string.tool_ui_manage_app_summary)),
        ToolItemUi("inspect_app", context.getString(R.string.tool_ui_inspect_app), context.getString(R.string.tool_ui_inspect_app_summary)),
        ToolItemUi("app_state_control", context.getString(R.string.tool_ui_application_status_930ff0), context.getString(R.string.tool_ui_stop_freeze_or_unfreeze_apps_a27438)),
        ToolItemUi("manage_file", context.getString(R.string.tool_ui_manage_file), context.getString(R.string.tool_ui_manage_file_summary)),
        ToolItemUi("system_property", context.getString(R.string.tool_ui_system_property), context.getString(R.string.tool_ui_system_property_summary)),
        ToolItemUi("display_control", context.getString(R.string.tool_ui_display_control), context.getString(R.string.tool_ui_display_control_summary)),
        ToolItemUi("deep_launch", context.getString(R.string.tool_ui_deep_launch), context.getString(R.string.tool_ui_deep_launch_summary)),
        ToolItemUi("package_control", context.getString(R.string.tool_ui_package_control), context.getString(R.string.tool_ui_package_control_summary)),
        ToolItemUi("user_control", context.getString(R.string.tool_ui_user_control), context.getString(R.string.tool_ui_user_control_summary)),
        ToolItemUi("appops_control", context.getString(R.string.tool_ui_appops_control), context.getString(R.string.tool_ui_appops_control_summary)),
        ToolItemUi("power_control", context.getString(R.string.tool_ui_power_control), context.getString(R.string.tool_ui_power_control_summary)),
        ToolItemUi("ime_control", context.getString(R.string.tool_ui_ime_control), context.getString(R.string.tool_ui_ime_control_summary)),
        ToolItemUi("connect_wifi", context.getString(R.string.tool_ui_connect_wifi), context.getString(R.string.tool_ui_connect_wifi_summary)),
        ToolItemUi("set_timezone", context.getString(R.string.tool_ui_set_timezone), context.getString(R.string.tool_ui_set_timezone_summary)),
        ToolItemUi("create_calendar_event", context.getString(R.string.tool_ui_create_calendar_event), context.getString(R.string.tool_ui_create_calendar_event_summary)),
        ToolItemUi("create_calendar_events", context.getString(R.string.tool_ui_create_calendar_events), context.getString(R.string.tool_ui_create_calendar_events_summary)),
        ToolItemUi("update_calendar_event", context.getString(R.string.tool_ui_update_calendar_event), context.getString(R.string.tool_ui_update_calendar_event_summary)),
        ToolItemUi("delete_calendar_event", context.getString(R.string.tool_ui_delete_calendar_event), context.getString(R.string.tool_ui_delete_calendar_event_summary)),
        ToolItemUi("create_note", context.getString(R.string.tool_ui_create_note), context.getString(R.string.tool_ui_create_note_summary)),
        ToolItemUi("delete_note", context.getString(R.string.tool_ui_delete_note), context.getString(R.string.tool_ui_delete_note_summary)),
        ToolItemUi("set_alarm", context.getString(R.string.tool_ui_set_alarm), context.getString(R.string.tool_ui_set_alarm_summary)),
    // ── personal ──
        ToolItemUi("read_sms_code", context.getString(R.string.tool_ui_read_verification_code_7d1121), context.getString(R.string.tool_ui_only_extract_verification_codes_from_recent_sms__0fb8c1)),
        ToolItemUi("recent_notifications", context.getString(R.string.tool_ui_read_notification_7fdc09), context.getString(R.string.tool_ui_read_the_current_notification_title_and_text_0faee7)),
        ToolItemUi("search_notification_history", context.getString(R.string.tool_ui_notification_history_95d015), context.getString(R.string.tool_ui_retrieve_the_last_7_days_of_notifications_saved__643e43)),
        ToolItemUi("recent_app_activity", context.getString(R.string.tool_ui_recently_applied_08f74c), context.getString(R.string.tool_ui_view_recently_opened_apps_and_times_bf9d50)),
        ToolItemUi("app_usage_summary", context.getString(R.string.tool_ui_app_usage_statistics_ee20d3), context.getString(R.string.tool_ui_summarize_recent_app_usage_by_foreground_duratio_b346c8)),
        ToolItemUi("get_current_location", context.getString(R.string.tool_ui_current_location_b458ea), context.getString(R.string.tool_ui_read_the_closest_location_the_system_already_has_255a6c)),
        ToolItemUi("get_device_environment", context.getString(R.string.tool_ui_equipment_environment_1026ec), context.getString(R.string.tool_ui_read_lock_screen_do_not_disturb_audio_output_and_9260b8)),
        ToolItemUi("get_flashlight", context.getString(R.string.tool_ui_get_flashlight), context.getString(R.string.tool_ui_get_flashlight_summary)),
        ToolItemUi("get_sound_state", context.getString(R.string.tool_ui_get_sound_state), context.getString(R.string.tool_ui_get_sound_state_summary)),
        ToolItemUi("get_hotspot", context.getString(R.string.tool_ui_get_hotspot), context.getString(R.string.tool_ui_get_hotspot_summary)),
        ToolItemUi("get_display_state", context.getString(R.string.tool_ui_get_display_state), context.getString(R.string.tool_ui_get_display_state_summary)),
        ToolItemUi("get_device_state", context.getString(R.string.tool_ui_get_device_state), context.getString(R.string.tool_ui_get_device_state_summary)),
        ToolItemUi("list_alarms", context.getString(R.string.tool_ui_alarm_clock_schedule_acae32), context.getString(R.string.tool_ui_read_the_alarm_clock_that_has_been_created_in_th_2320d6)),
        ToolItemUi("list_active_timers", context.getString(R.string.tool_ui_activity_timer_36f107), context.getString(R.string.tool_ui_read_running_or_paused_timers_3437c8)),
        ToolItemUi("search_clipboard_history", context.getString(R.string.tool_ui_clipboard_history_b377bb), context.getString(R.string.tool_ui_retrieve_clipboard_contents_saved_by_system_inpu_1dc9db)),
        ToolItemUi("get_health_summary", context.getString(R.string.tool_ui_health_summary_951c0b), context.getString(R.string.tool_ui_summarize_steps_sleep_exercise_and_body_metrics_6ff66f)),
        ToolItemUi("wifi_credentials", context.getString(R.string.tool_ui_wi_fi_password_80e9a4), context.getString(R.string.tool_ui_read_the_network_credentials_saved_by_the_phone_96d43a)),
        ToolItemUi("get_setting", context.getString(R.string.tool_ui_read_system_settings_d455ce), context.getString(R.string.tool_ui_read_the_specified_settings_key_496975)),
        ToolItemUi("list_installed_apps", context.getString(R.string.tool_ui_list_installed_apps), context.getString(R.string.tool_ui_list_installed_apps_summary)),
        ToolItemUi("get_logcat", context.getString(R.string.tool_ui_system_log_096733), context.getString(R.string.tool_ui_bounded_reading_and_filtering_of_recent_logs_0a268a)),
        ToolItemUi("search_settings", context.getString(R.string.tool_ui_search_settings), context.getString(R.string.tool_ui_search_settings_summary)),
        ToolItemUi("dumpsys_diag", context.getString(R.string.tool_ui_dumpsys_diag), context.getString(R.string.tool_ui_dumpsys_diag_summary)),
        ToolItemUi("search_media", context.getString(R.string.tool_ui_album_pictures_23bcc2), context.getString(R.string.tool_ui_retrieve_pictures_by_file_name_or_album_path_c08236)),
        ToolItemUi("search_audio", context.getString(R.string.tool_ui_audio_file_1ccf2e), context.getString(R.string.tool_ui_search_audio_by_title_filename_or_author_82e20d)),
        ToolItemUi("search_recordings", context.getString(R.string.tool_ui_system_recording_15eb19), context.getString(R.string.tool_ui_retrieve_recording_files_from_system_media_libra_314c4d)),
        ToolItemUi("search_files", context.getString(R.string.tool_ui_share_files_a3b376), context.getString(R.string.tool_ui_retrieve_documents_and_files_from_shared_storage_7d6193)),
        ToolItemUi("search_calendar_events", context.getString(R.string.tool_ui_calendar_events_970349), context.getString(R.string.tool_ui_search_events_by_title_location_or_description_1afd77)),
        ToolItemUi("list_calendars", context.getString(R.string.tool_ui_list_calendars), context.getString(R.string.tool_ui_list_calendars_summary)),
        ToolItemUi("read_calendar_event", context.getString(R.string.tool_ui_read_calendar_event), context.getString(R.string.tool_ui_read_calendar_event_summary)),
        ToolItemUi("search_contacts", context.getString(R.string.tool_ui_address_book_9070cb), context.getString(R.string.tool_ui_retrieve_contact_name_and_open_address_6dacc5)),
        ToolItemUi("search_call_history", context.getString(R.string.tool_ui_call_history_88e57b), context.getString(R.string.tool_ui_retrieve_calls_by_number_or_contact_name_2ce431)),
        ToolItemUi("search_messages", context.getString(R.string.tool_ui_short_message_17e1a4), context.getString(R.string.tool_ui_search_text_messages_by_sender_or_text_keywords_e14363)),
        ToolItemUi("search_downloads", context.getString(R.string.tool_ui_download_history_8494d7), context.getString(R.string.tool_ui_retrieve_system_download_tasks_and_files_3301b9)),
        ToolItemUi("search_coloros_notes", context.getString(R.string.tool_ui_coloros_notes_6c324c), context.getString(R.string.tool_ui_retrieve_notes_to_dos_and_text_content_e806d7)),
        ToolItemUi("read_note", context.getString(R.string.tool_ui_read_note), context.getString(R.string.tool_ui_read_note_summary)),
        ToolItemUi("search_coloros_recordings", context.getString(R.string.tool_ui_coloros_recording_a4e425), context.getString(R.string.tool_ui_retrieve_normal_recordings_and_call_recordings_55c192)),
        ToolItemUi("search_recording_summaries", context.getString(R.string.tool_ui_recording_summary_2fe550), context.getString(R.string.tool_ui_retrieve_transcribed_summaries_and_notes_associa_9cb00f)),
        ToolItemUi("search_coloros_memories", context.getString(R.string.tool_ui_coloros_system_memory_eff961), context.getString(R.string.tool_ui_retrieve_collected_information_and_its_structure_9c1c71)),
        ToolItemUi("search_saved_places", context.getString(R.string.tool_ui_save_location_c29782), context.getString(R.string.tool_ui_retrieve_location_information_from_system_memory_52ea48)),
        ToolItemUi("search_personal_orders", context.getString(R.string.tool_ui_personal_order_25e4c9), context.getString(R.string.tool_ui_retrieve_takeout_shopping_express_delivery_ticke_f8d002)),
        ToolItemUi("search_bills", context.getString(R.string.tool_ui_search_bills), context.getString(R.string.tool_ui_search_bills_summary)),
        ToolItemUi("summarize_bills", context.getString(R.string.tool_ui_summarize_bills), context.getString(R.string.tool_ui_summarize_bills_summary)),
        ToolItemUi("search_todos", context.getString(R.string.tool_ui_search_todos), context.getString(R.string.tool_ui_search_todos_summary)),
        ToolItemUi("search_calendar_todos", context.getString(R.string.tool_ui_search_calendar_todos), context.getString(R.string.tool_ui_search_calendar_todos_summary)),
        ToolItemUi("search_memory_collections", context.getString(R.string.tool_ui_search_memory_collections), context.getString(R.string.tool_ui_search_memory_collections_summary)),
        ToolItemUi("search_daily_events", context.getString(R.string.tool_ui_search_daily_events), context.getString(R.string.tool_ui_search_daily_events_summary)),
        ToolItemUi("search_flights", context.getString(R.string.tool_ui_search_flights), context.getString(R.string.tool_ui_search_flights_summary)),
        ToolItemUi("search_hotels", context.getString(R.string.tool_ui_search_hotels), context.getString(R.string.tool_ui_search_hotels_summary)),
        ToolItemUi("search_trains", context.getString(R.string.tool_ui_search_trains), context.getString(R.string.tool_ui_search_trains_summary)),
        ToolItemUi("read_personal_item", context.getString(R.string.tool_ui_read_personal_item), context.getString(R.string.tool_ui_read_personal_item_summary)),
        ToolItemUi("search_qq_chat_images", context.getString(R.string.tool_ui_qq_chat_pictures_e21bf9), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_qq_chat_picture_cach_b8f009)),
        ToolItemUi("search_wechat_chat_images", context.getString(R.string.tool_ui_wechat_chat_pictures_72b268), context.getString(R.string.tool_ui_retrieve_recent_pictures_in_wechat_chat_picture__ab66f7)),
        ToolItemUi( "semantic_search_media", context.getString(R.string.tool_ui_semantic_search_media), context.getString(R.string.tool_ui_semantic_search_media_summary), ),
        ToolItemUi("search_notes", context.getString(R.string.tool_ui_search_notes), context.getString(R.string.tool_ui_search_notes_summary)),
    // ── extensions ──
        ToolItemUi("memory_write", context.getString(R.string.tool_ui_organize_memory_2b08eb), context.getString(R.string.tool_ui_partially_update_append_or_clear_long_term_memor_c1bab6)),
        ToolItemUi("eta_get_settings", context.getString(R.string.tool_ui_eta_get_settings), context.getString(R.string.tool_ui_eta_get_settings_summary)),
        ToolItemUi("eta_set_settings", context.getString(R.string.tool_ui_eta_set_settings), context.getString(R.string.tool_ui_eta_set_settings_summary)),
        ToolItemUi("eta_manage_models", context.getString(R.string.tool_ui_eta_manage_models), context.getString(R.string.tool_ui_eta_manage_models_summary)),
        ToolItemUi("eta_manage_subagents", context.getString(R.string.tool_ui_eta_manage_subagents), context.getString(R.string.tool_ui_eta_manage_subagents_summary)),
        ToolItemUi("eta_manage_history", context.getString(R.string.tool_ui_eta_manage_history), context.getString(R.string.tool_ui_eta_manage_history_summary)),
        ToolItemUi("eta_configure_mcp", context.getString(R.string.tool_ui_eta_configure_mcp), context.getString(R.string.tool_ui_eta_configure_mcp_summary)),
        ToolItemUi("eta_author_tool", context.getString(R.string.tool_ui_eta_author_tool), context.getString(R.string.tool_ui_eta_author_tool_summary)),
        ToolItemUi("eta_list_recipes", context.getString(R.string.tool_ui_eta_list_recipes), context.getString(R.string.tool_ui_eta_list_recipes_summary)),
        ToolItemUi("skills_inspect_github", context.getString(R.string.tool_ui_skills_inspect_github), context.getString(R.string.tool_ui_skills_inspect_github_summary)),
        ToolItemUi("skills_install_from_github", context.getString(R.string.tool_ui_skills_install_from_github), context.getString(R.string.tool_ui_skills_install_from_github_summary)),
        ToolItemUi("skills_install_local", context.getString(R.string.tool_ui_skills_install_local), context.getString(R.string.tool_ui_skills_install_local_summary)),
)

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val appListEnabled = hasAppListAccess(context)
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val rootEnabled = RootAccess.isGranted
    val notificationsEnabled = context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    val locationAccess = DeviceLocationProvider.accessState(context)
    val notificationHistoryEnabled = io.github.mangi.eta.agent.device.AgentNotificationHistoryService.isEnabled(context)
    val usageAccessEnabled = io.github.mangi.eta.agent.tool.AgentPersonalContextTools.hasUsageAccess(context)

    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "background",
                title = context.getString(R.string.state_background_running_permission_dde21b),
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = context.getString(R.string.ui_floating_window_permissions_076b77),
                summary = "",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = context.getString(R.string.state_application_list_reading_135f16),
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "location",
                title = context.getString(R.string.state_location_permissions_b53f9c),
                summary = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_used_to_understand_the_location_of_mobile_phones_af52e9)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.capability_location_foreground)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_system_location_service_is_turned_off_3902e7)
                    DeviceLocationProvider.AccessState.AVAILABLE -> context.getString(R.string.state_ui_only_read_when_the_agent_calls_the_tool_8cf77b)
                },
                status = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> PermissionStatusUi.Missing
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> PermissionStatusUi.Warning
                    DeviceLocationProvider.AccessState.DISABLED -> PermissionStatusUi.Disabled
                    DeviceLocationProvider.AccessState.AVAILABLE -> PermissionStatusUi.Available
                },
                primaryActionLabel = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> context.getString(R.string.state_ui_to_authorize_762ec4)
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> context.getString(R.string.state_ui_go_to_settings_1f2998)
                    DeviceLocationProvider.AccessState.DISABLED -> context.getString(R.string.state_ui_to_open_13ec17)
                    DeviceLocationProvider.AccessState.AVAILABLE -> null
                },
            ),
            // 本地网络闸门。
            //
            // 放在权限总览里是因为它不属于某一个功能：远程智能体、局域网 MCP、
            // 任何连同一个网段设备的工具都会踩到它。而且它的失败形态极其误导人——
            // 系统静默丢包，用户看到的是「连接超时」，跟服务器不回应完全一样。
            run {
                val lanState = io.github.mangi.eta.agent.device.LocalNetworkPermission.accessState(context)
                PermissionHealthItemUi(
                    id = "local_network",
                    title = context.getString(R.string.permission_local_network_title),
                    summary = when (lanState) {
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.DENIED ->
                            context.getString(R.string.permission_local_network_summary_blocked)
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.NOT_GRANTED ->
                            context.getString(R.string.permission_local_network_summary_not_granted)
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.NOT_REQUIRED ->
                            context.getString(R.string.permission_local_network_summary_not_required)
                        else -> context.getString(R.string.permission_local_network_summary)
                    },
                    status = when (lanState) {
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.GRANTED,
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.NOT_REQUIRED,
                        -> PermissionStatusUi.Available
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.NOT_GRANTED ->
                            PermissionStatusUi.Missing
                        // 「权限在、但系统拦着」是警告而不是「缺失」：
                        // 让用户去点「授权」是没用的，情况和缺权限相反。
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.DENIED,
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.UNKNOWN,
                        -> PermissionStatusUi.Warning
                    },
                    primaryActionLabel = when (lanState) {
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.GRANTED,
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.NOT_REQUIRED,
                        -> null
                        io.github.mangi.eta.agent.device.LocalNetworkPermission.AccessState.NOT_GRANTED ->
                            context.getString(R.string.state_ui_to_authorize_762ec4)
                        else -> context.getString(R.string.state_ui_go_to_settings_1f2998)
                    },
                )
            },
            PermissionHealthItemUi(
                id = "calendar",
                title = context.getString(R.string.permission_calendar_title),
                summary = context.getString(R.string.permission_calendar_summary),
                status = if (io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, write = false)) {
                    PermissionStatusUi.Available
                } else {
                    PermissionStatusUi.Missing
                },
                primaryActionLabel = if (io.github.mangi.eta.agent.device.CalendarPermissions.granted(context, write = false)) {
                    null
                } else {
                    context.getString(R.string.state_ui_to_authorize_762ec4)
                },
            ),
            PermissionHealthItemUi(
                id = "notification_history",
                title = context.getString(R.string.state_notice_of_use_rights_1ae29a),
                summary = if (notificationHistoryEnabled) {
                    context.getString(R.string.state_ui_natively_bounded_storage_of_last_7_days_of_notif_ca7f01)
                } else {
                    context.getString(R.string.state_ui_start_logging_searchable_notification_history_af_b36af6)
                },
                status = if (notificationHistoryEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (notificationHistoryEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "usage_access",
                title = context.getString(R.string.state_usage_access_20f1f8),
                summary = context.getString(R.string.state_used_to_read_recently_opened_applications_and_foregr_73e796),
                status = if (usageAccessEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (usageAccessEnabled) null else context.getString(R.string.state_ui_to_authorize_762ec4),
            ),
            PermissionHealthItemUi(
                id = "accessibility",
                title = context.getString(R.string.state_accessibility_permissions_f80103),
                summary = "",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
            PermissionHealthItemUi(
                id = "notifications",
                title = context.getString(R.string.capability_notifications_title),
                summary = context.getString(R.string.capability_notifications_summary),
                status = if (notificationsEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = context.getString(R.string.state_ui_go_to_settings_1f2998),
            ),
            PermissionHealthItemUi(
                id = "root",
                title = context.getString(R.string.capability_enhancements),
                summary = context.getString(R.string.capability_optional_root),
                status = if (rootEnabled) PermissionStatusUi.Available else PermissionStatusUi.Disabled,
                primaryActionLabel = if (rootEnabled) null else context.getString(R.string.state_ui_to_open_13ec17),
            ),
        )
    )
}

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}

private fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
    )

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}

/** Full provider/model value comparison, never an ID-only or picker-option comparison.
 * Repository emissions are immutable domain data classes (including nested model/config lists).
 * A settings-only preferences re-emission retains the providers reference, so the common path
 * does no list walk. A newly loaded list is compared in order and includes every config field.
 */
internal fun runtimeSelectionUnchanged(
    previous: Triple<String?, String?, List<io.github.mangi.eta.data.model.ProviderSetting>>,
    next: Triple<String?, String?, List<io.github.mangi.eta.data.model.ProviderSetting>>,
): Boolean = previous.first == next.first && previous.second == next.second &&
    (previous.third === next.third || previous.third == next.third)

internal data class ConversationSummaryEnvironment(
    val configuration: String,
    val localDay: Long,
    val timeZone: java.util.TimeZone,
    val use24HourClock: Boolean,
)

/** Do not retain attachments/history or invalidate on usage, thinking text and reveal flags. */
internal data class ConversationSummaryPreviewInput(val kind: String, val text: String = "", val notice: SystemNoticeCode? = null)

internal fun conversationSummaryPreviewInput(message: AgentChatMessageUi?): ConversationSummaryPreviewInput = when (message) {
    is UserMessageUi -> ConversationSummaryPreviewInput("user", message.content)
    is AgentMessageUi -> ConversationSummaryPreviewInput("assistant", message.content)
    is SystemNoticeMessageUi -> ConversationSummaryPreviewInput("notice", notice = message.code)
    is ThinkingMessageUi -> ConversationSummaryPreviewInput("thinking")
    is ToolActivityMessageUi -> ConversationSummaryPreviewInput("tool", message.toolName)
    else -> ConversationSummaryPreviewInput("empty")
}

/** Only inputs read by the existing summary projection, not the full transcript/history. */
internal data class ConversationSummaryKey(
    val title: String,
    val previewInput: ConversationSummaryPreviewInput,
    val createdAtMillis: Long?,
    val updatedAtMillis: Long?,
    val isPinned: Boolean,
    val isActiveRun: Boolean,
    val hasCompletionMarker: Boolean,
    val isWaitingQuestion: Boolean = false,
    val isDispatchManaged: Boolean = false,
    val isDispatchConsole: Boolean = false,
    val folderId: String?,
    val environment: ConversationSummaryEnvironment,
)

/** Main-owned, one entry per live conversation; deleted/archive-replaced IDs are pruned. */
internal class ConversationSummaryCache {
    private data class Entry(val key: ConversationSummaryKey, val summary: ConversationSummaryUi)
    private val entries = mutableMapOf<String, Entry>()

    fun current(id: String, environment: ConversationSummaryEnvironment): ConversationSummaryUi? =
        entries[id]?.takeIf { it.key.environment == environment }?.summary

    fun retain(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }

    fun getOrBuild(id: String, key: ConversationSummaryKey, build: () -> ConversationSummaryUi): ConversationSummaryUi {
        val previous = entries[id]
        if (previous?.key == key) return previous.summary
        return build().also { entries[id] = Entry(key, it) }
    }
}
