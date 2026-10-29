package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.voice.VoiceChatSnapshot
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeController
import io.github.mangi.eta.agent.voice.VoiceModeState
import io.github.mangi.eta.agent.voice.ReplyReadAloudController
import io.github.mangi.eta.agent.voice.VOICE_MODE_SPEECH_OWNER_PREFIX
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AnswerFoldLevel
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.app.DeviceCapabilitiesUi
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.latestBilledContextTokens
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import io.github.mangi.eta.ui.model.isRetryableFailure
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.ConversationMentionInputUi
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress
import io.github.mangi.eta.ui.model.isSteerSupplement
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.layout.MaxChatSuggestionWidth
import io.github.mangi.eta.ui.layout.rememberPageContentSidePadding
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.graphics.shapes.RoundedPolygon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults

/**
 * 壳层顶栏（含状态栏）占用的总高度。
 *
 * 对话列表延伸到顶栏背后，由顶栏渐变板淡出滚入的消息；
 * 列表用它作为顶部 contentPadding，静止时首条消息仍停在顶栏下方。
 */
val LocalShellTopBarInset = compositionLocalOf { 0.dp }

/**
 * 会话空状态与底部提示的外观适配。
 *
 * 全部取官方 [MaterialTheme] 角色与排版档位。
 */
@Composable
internal fun chatEmptyStateHeadlineStyle(): androidx.compose.ui.text.TextStyle =
    etaTextStyle(EtaType.Hero)

@Composable
internal fun chatSecondaryLabelStyle(): androidx.compose.ui.text.TextStyle =
    etaTextStyle(EtaType.Secondary)

@Composable
private fun chatMutedLabelColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.onSurfaceVariant

/** 新消息入场淡入：取官方 effects 规格。 */
@Composable
private fun chatMessageFadeInSpec(): FiniteAnimationSpec<Float> =
    MaterialTheme.motionScheme.defaultEffectsSpec()

@Composable
private fun chatBodyLabelColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.onSurface

/** 卡片描边：用官方 outlineVariant 角色。 */
@Composable
private fun chatCardBorderColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.outlineVariant

@Composable
private fun chatCardContainerColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.surfaceContainerLow

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    voiceController: VoiceModeController,
    messages: List<AgentChatMessageUi>,
    history: List<AgentModelClient.ConversationMessage>,
    modelPickerState: AgentModelPickerUiState,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    livePromptTokens: Int? = null,
    livePromptIsProjected: Boolean = false,
    billedHistoryTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    childContexts: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats> = emptyList(),
    compactingModelName: String = "",
    selectedContextTaskId: String? = null,
    onContextTaskSelected: ((String?) -> Unit)? = null,
    autoCompressEnabled: Boolean = false,
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String) -> Unit = {},
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onSubmit: (String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit = {},
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit = { _, _, _ -> },
    onSubmitQuestionAnswer: (String, String) -> Unit = { _, _ -> },
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    /** 长按「助手与模型」按钮：进入设置页的子代理配置页（预设组列表 / 预设详情）。 */
    onOpenSubAgentSettings: () -> Unit = {},
    onAssistantSelected: (String) -> Unit = {},
    gptSpeedMode: io.github.mangi.eta.data.model.GptSpeedMode = io.github.mangi.eta.data.model.GptSpeedMode.NORMAL,
    onCycleGptSpeedMode: () -> Unit = {},
    isDrawerOpen: Boolean = false,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    /** 本机刚发出的用户消息 id：该气泡首帧自己播入场，播完由 [onSentUserMessageEntranceConsumed] 作废。 */
    sentUserMessageEntranceId: String? = null,
    onSentUserMessageEntranceConsumed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val chatComposeStartedNs = if (StreamPerformanceDiagnostics.enabled) System.nanoTime() else 0L
    val chatUiActive = LocalChatUiActive.current
    val routeCovered = LocalChatRouteCovered.current
    val transitionActive = LocalChatTransitionActive.current
    // 停稳且完全盖住时保留当前快照，避免设置页和管理页跟着重排。
    // 横滑手势和回弹期间露出的部分必须继续走正常打字机，不能追平、不能停住。
    val followLiveTranscript = chatUiActive && (!routeCovered || transitionActive)
    val frozenChatSnapshot = remember(followLiveTranscript) {
        if (followLiveTranscript) null else Triple(messages, isStreaming, isPaused)
    }
    val uiMessages = frozenChatSnapshot?.first ?: messages
    val uiStreaming = frozenChatSnapshot?.second ?: isStreaming
    val uiPaused = frozenChatSnapshot?.third ?: isPaused
    // 一盖住就停，不等横滑停稳。打字机可以继续，震动不行。
    SideEffect {
        StreamPerformanceDiagnostics.record("chat.compose", value = messages.size.toLong())
        if (chatComposeStartedNs != 0L) {
            StreamPerformanceDiagnostics.record(
                "chat.compose.elapsed",
                System.nanoTime() - chatComposeStartedNs,
            )
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val view = LocalView.current
    val context = LocalContext.current
    val voiceState by voiceController.state.collectAsState()
    val browserShortcut by remember {
        AgentBrowserSession.snapshots
            .map { snapshot ->
                Triple(snapshot.available, snapshot.lastAgentRunId, snapshot.lastAgentToolCallId)
            }
            .distinctUntilChanged()
    }.collectAsState(initial = Triple(false, null, null))
    val visibleMessagesCache = remember { AgentVisibleMessagesCache() }
    val visibleMessages = remember(uiMessages, messageEdit?.targetMessageId) {
        visibleMessagesCache.project(uiMessages, messageEdit?.targetMessageId)
    }
    LaunchedEffect(messages, isStreaming) {
        val last = messages.filterIsInstance<AgentMessageUi>().lastOrNull()
        val speechText = last?.content.orEmpty()
        val snapshot = VoiceChatSnapshot(
            isStreaming = isStreaming,
            lastAgentId = last?.id,
            lastAgentText = speechText,
        )
        voiceController.updateChat(snapshot)
        // 自动朗读与语音对话共用同一份快照：是否开口由各自的开关决定。
        ReplyReadAloudController.updateChat(context, snapshot)
    }
    val speechPlayback by io.github.mangi.eta.agent.voice.tts.SpeechPlayback.state.collectAsState()
    LaunchedEffect(visibleMessages, messageEdit?.targetMessageId, speechPlayback.owner) {
        val owner = speechPlayback.owner
        val visibleCompletedIds = visibleMessages.mapNotNull { message ->
            (message as? AgentMessageUi)?.takeIf { !it.isStreaming }?.id
        }.toSet()
        if (shouldStopOrphanSpeechPlayback(owner, messageEdit != null, visibleCompletedIds)) {
            io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stop("orphan_reply")
        }
    }
    // Project once for both the initial tail anchor and the rendered rows below. In
    // particular, do not derive a second full timeline just to ask for its size: this
    // list can contain thousands of streaming/tool messages.
    // 「激进折叠」档位决定归组方式：激进档把中间回复并进工作组，普通/不折叠档与旧版一致。
    // 缓存只在「激进与否」翻转时重建，档位在两者间切换不重投影。
    val answerFoldLevel = LocalAppearanceSettings.current.answerFoldLevel
    val aggressiveFold = answerFoldLevel == AnswerFoldLevel.AGGRESSIVE
    val timelineProjection = remember(aggressiveFold) {
        AgentTimelineProjectionCache(
            fullProjection = { it.toTimelineEntries(aggressiveFold) },
            aggressiveGrouping = aggressiveFold,
        )
    }
    val timelineEntries = remember(visibleMessages) {
        StreamPerformanceDiagnostics.measure("timeline.project", visibleMessages.size.toLong()) { timelineProjection.project(visibleMessages) }
    }
    val hasCompactingChildContext = remember(childContexts) { childContexts.any { it.isCompacting } }
    val initialBottomItemIndex = remember(timelineEntries.size, isCompressingContext, isWaitingForCompression, hasCompactingChildContext) {
        initialTimelineItemIndex(
            timelineEntries = timelineEntries,
            isCompressingContext = isCompressingContext,
            isWaitingForCompression = isWaitingForCompression,
            hasCompactingChildContext = hasCompactingChildContext,
        )
    }
    // A default one-item prefetch is too shallow for mixed short tool rows and tall Markdown.
    // Keep one viewport on both sides: ahead prepares incoming rows; behind prevents
    // immediate disposal/recomposition when expansion or a direction reversal moves the boundary.
    val chatCacheWindow = remember {
        LazyLayoutCacheWindow(
            aheadFraction = CHAT_CACHE_AHEAD_VIEWPORTS,
            behindFraction = CHAT_CACHE_BEHIND_VIEWPORTS,
        )
    }
    val scrollState = rememberLazyListState(
        cacheWindow = chatCacheWindow,
        initialFirstVisibleItemIndex = initialBottomItemIndex,
    )

    io.github.mangi.eta.ui.haptics.StreamingHaptics.Observe(
        enabled = !isPaused && !routeCovered,
        conversationId = collaborationConversationId,
    )

    val currentBrowserMessageId = remember(
        visibleMessages,
        browserShortcut,
    ) {
        val (available, runId, toolCallId) = browserShortcut
        if (!available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    val submitScrollScope = rememberCoroutineScope()
    var keepBottomAnchored by remember { mutableStateOf(true) }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            hideChatInputIme(focusManager, keyboard, view)
        }
    }

    val billedContextTokens = remember(livePromptTokens, livePromptIsProjected, messageEdit) {
        if (messageEdit != null) {
            null
        } else {
            livePromptTokens.takeUnless { livePromptIsProjected }
        }
    }
    val projectedContextTokens = livePromptTokens.takeIf { livePromptIsProjected && messageEdit == null && uiStreaming }
    val imageSourceCache = remember { ChatImageSourceCache() }
    val previewGallery by produceState<List<String>>(emptyList(), visibleMessages, pendingImages) {
        // This used to parse EVERY historical reply synchronously on each text delta.
        // Cancelling this producer prevents obsolete galleries from being published.
        value = withContext(Dispatchers.Default) {
            StreamPerformanceDiagnostics.measure("gallery.scan", visibleMessages.size.toLong()) {
                collectPreviewableChatImages(visibleMessages, pendingImages) { message ->
                    imageSourceCache.sources(message.id, message.content)
                }
            }
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAgentContextTelemetry provides AgentContextTelemetry(childContexts, modelPickerState.selectedModel?.displayName.orEmpty(), compactingModelName, selectedContextTaskId, onContextTaskSelected)
    ) {
        ChatImagePreviewHost(
            gallery = previewGallery,
            onAttachImageToConversation = onAttachImage,
            onAttachVideoToConversation = onAttachVideo,
        ) {
            AgentChatScaffold(
                collaborationConversationId = collaborationConversationId,
                visibleMessages = visibleMessages,
                timelineEntries = timelineEntries,
                hasMessages = visibleMessages.isNotEmpty(),
                scrollState = scrollState,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                measuredContextTokens = measuredContextTokens,
                contextDisplayPolicy = contextDisplayPolicy,
                billedOverheadTokens = billedOverheadTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                isStreaming = uiStreaming,
                isPaused = uiPaused,
                isCompressingContext = isCompressingContext,
                isWaitingForCompression = isWaitingForCompression,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                conversationMentions = conversationMentions,
                queuedMessages = queuedMessages,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                messageEdit = messageEdit,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = voiceController::start,
                onStopVoiceMode = voiceController::stop,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = { keepBottomAnchored = it },
                onSubmit = { text ->
                    // Hide only for this submit action, never for a later streaming/tool update.
                    keyboard?.hide()
                    // 发送即重新锚定底部：用户从历史上方直接发送时，同帧内 isStreaming 与
                    // 新消息一起到位，立即回到底部并恢复后续的流式平滑跟底。
                    keepBottomAnchored = true
                    onSubmit(text)
                    submitScrollScope.launch {
                        // Cancel an old fling, then anchor the edited/replaced list after layout.
                        scrollState.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) { }
                        withFrameNanos { }
                        keepBottomAnchored = true
                        val last = scrollState.layoutInfo.totalItemsCount - 1
                        // 回到底部走平滑动画：跨项用翻页曲线赶路，可见差额用弹簧逐像素收拢，
                        // 末尾仍由 settle 内的精修收在精确贴底，替换原来的瞬跳定位。
                        if (last >= 0) settleListToBottom(
                            scrollState,
                            last,
                            scrollSpec = ChatSendSettleSpec,
                        )
                    }
                },
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onEditMessage = onEditMessage,
                onCancelMessageEdit = onCancelMessageEdit,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                onQuestionDraftChanged = onQuestionDraftChanged,
                onSubmitQuestionAnswer = onSubmitQuestionAnswer,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditAssistant = onEditAssistant,
                onOpenModelProviders = onOpenModelProviders,
                onOpenAssistants = onOpenAssistants,
                onOpenSubAgentSettings = onOpenSubAgentSettings,
                onAssistantSelected = onAssistantSelected,
                gptSpeedMode = gptSpeedMode,
                onCycleGptSpeedMode = onCycleGptSpeedMode,
                currentBrowserMessageId = currentBrowserMessageId,
                scrollToMessageId = scrollToMessageId,
                onScrollToMessageConsumed = onScrollToMessageConsumed,
                sentUserMessageEntranceId = sentUserMessageEntranceId,
                onSentUserMessageEntranceConsumed = onSentUserMessageEntranceConsumed,
                modifier = modifier,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatScaffold(
    visibleMessages: List<AgentChatMessageUi>,
    timelineEntries: List<AgentTimelineEntry>,
    hasMessages: Boolean,
    scrollState: LazyListState,
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String) -> Unit = {},
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit = {},
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit = { _, _, _ -> },
    onSubmitQuestionAnswer: (String, String) -> Unit = { _, _ -> },
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    /** 长按「助手与模型」按钮：进入设置页的子代理配置页（预设组列表 / 预设详情）。 */
    onOpenSubAgentSettings: () -> Unit = {},
    onAssistantSelected: (String) -> Unit = {},
    currentBrowserMessageId: String?,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    sentUserMessageEntranceId: String? = null,
    onSentUserMessageEntranceConsumed: (String) -> Unit = {},
    gptSpeedMode: io.github.mangi.eta.data.model.GptSpeedMode = io.github.mangi.eta.data.model.GptSpeedMode.NORMAL,
    onCycleGptSpeedMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val appearance = LocalAppearanceSettings.current
    // 宽屏（平板横屏）下消息列与输入栏一起收进居中的内容列，避免一行文字横贯整块屏幕。
    val contentSidePadding = rememberPageContentSidePadding()
    // Pure function of the visible list, but it scans to the last user turn and then
    // forward for model output. Memoised so a recomposition that does not change the
    // list (or the run flags) does not rescan a multi-thousand-message transcript.
    val showMorphLoading = remember(
        visibleMessages, isStreaming, isPaused, isCompressingContext,
        appearance.morphLoadingIndicator, appearance.morphLoadingBeforeResponseOnly,
    ) {
        shouldShowMorphLoadingIndicator(
            messages = visibleMessages,
            isStreaming = isStreaming,
            isPaused = isPaused,
            isCompressingContext = isCompressingContext,
            enabled = appearance.morphLoadingIndicator,
            beforeResponseOnly = appearance.morphLoadingBeforeResponseOnly,
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            AgentChatBottomBar(
                collaborationConversationId = collaborationConversationId,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = historyForContextSendBudget(
                    history, measuredContextTokens, autoCompressEnabled,
                ),
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                measuredContextTokens = measuredContextTokens,
                contextDisplayPolicy = contextDisplayPolicy,
                billedOverheadTokens = billedOverheadTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                isPaused = isPaused,
                canContinueDisconnected = remember(visibleMessages) {
                    canContinueDisconnectedRun(visibleMessages)
                },
                isCompressingContext = isCompressingContext,
                showMorphLoading = showMorphLoading,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
            conversationMentions = conversationMentions,
                queuedMessages = queuedMessages,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                messageEdit = messageEdit,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                onEditAssistant = onEditAssistant,
                onOpenModelProviders = onOpenModelProviders,
                onOpenAssistants = onOpenAssistants,
                onOpenSubAgentSettings = onOpenSubAgentSettings,
                onAssistantSelected = onAssistantSelected,
                gptSpeedMode = gptSpeedMode,
                onCycleGptSpeedMode = onCycleGptSpeedMode,
                horizontalPadding = contentSidePadding,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        val topBarInset = LocalShellTopBarInset.current
        if (!hasMessages) {
            // 键盘起落期间逐帧变化的只有一样：Scaffold 底栏高度（bottom padding）。把它隔离进
            // 外层 Box（只触发测量/布局）；IME 的逐帧 inset 则全部推迟到 EmptyChatState 内部的
            // layout / graphicsLayer lambda 里读（组合期不读），键盘动画期间空态子树不重组，
            // 只逐帧重测量与重绘，四块建议形状不会被拖进逐帧重组。
            // 形状放不放得下用「不带 bottom padding 的完整高度」判定：这个高度在键盘起落时
            // 恒定，判定结果不会在键盘动画中途翻转，也不参与逐帧重组。
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val showShapes = maxHeight >= suggestionShapesMinHeight(maxWidth)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topBarInset, bottom = bottomPadding),
                ) {
                    EmptyChatState(
                        showShapes = showShapes,
                        onSuggestionClick = onSuggestionClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            AgentConversationMessages(
                visibleMessages = visibleMessages,
                timelineEntries = timelineEntries,
                scrollState = scrollState,
                isStreaming = isStreaming,
                isPaused = isPaused,
                isCompressingContext = isCompressingContext,
                isWaitingForCompression = isWaitingForCompression,
                bottomInset = bottomPadding,
                topInset = topBarInset,
                horizontalPadding = contentSidePadding,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = onBottomAnchorChanged,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                onQuestionDraftChanged = onQuestionDraftChanged,
                onSubmitQuestionAnswer = onSubmitQuestionAnswer,
                messageActionsEnabled = !isStreaming && !isPaused &&
                    !isCompressingContext &&
                    messageEdit == null,
                branchEnabled = !isCompressingContext && messageEdit == null,
                editTargetMessageId = messageEdit?.targetMessageId,
                currentBrowserMessageId = currentBrowserMessageId,
                scrollToMessageId = scrollToMessageId,
                onScrollToMessageConsumed = onScrollToMessageConsumed,
                sentUserMessageEntranceId = sentUserMessageEntranceId,
                onSentUserMessageEntranceConsumed = onSentUserMessageEntranceConsumed,
                modifier = Modifier
                    .fillMaxSize(),
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    timelineEntries: List<AgentTimelineEntry>? = null,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    isWaitingForCompression: Boolean = false,
    bottomInset: Dp,
    topInset: Dp = 0.dp,
    horizontalPadding: Dp = 0.dp,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSuggestionClick: (String) -> Unit = {},
    onRunTraceClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    onQuestionDraftChanged: (String, String, io.github.mangi.eta.agent.question.AgentQuestionAnswer) -> Unit = { _, _, _ -> },
    onSubmitQuestionAnswer: (String, String) -> Unit = { _, _ -> },
    messageActionsEnabled: Boolean = false,
    branchEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    scrollToMessageId: String? = null,
    onScrollToMessageConsumed: () -> Unit = {},
    sentUserMessageEntranceId: String? = null,
    onSentUserMessageEntranceConsumed: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Independent, trace-gated telemetry also covers idle conversations. No frame loop.
    val scrollTraceEnabled = rememberChatScrollTraceEnabled()
    val chatListTrace = ChatScrollMonitor(state = scrollState, enabled = scrollTraceEnabled)
    // Observe attach/detach only; never change lazy keys or remount completed content.
    val diagnosticGeneration = StreamPerformanceDiagnostics.sessionGeneration.longValue
    val diagnosticList = remember(scrollState, diagnosticGeneration) { nextStreamDiagnosticListId() }
    val diagnosticListAttribution = StreamPerformanceDiagnostics.listAttribution(diagnosticList)
    traceChatListOwnerExecution(chatListTrace, scrollTraceEnabled)
    // Retain successful parses beyond individual lazy-row compositions.
    val completedMarkdownCache = remember(scrollState) { CompletedMarkdownCache() }
    // AgentChatBody supplies this projection so the initial tail anchor and the
    // rendered rows share one remembered full-list derivation. The standalone voice
    // panel still computes it here when it calls this renderer directly.
    // AgentChatBody 传入的 timelineEntries 与下面的独立投影必须同源：同样按「激进档」
    // 决定归组（激进档把中间回复并进工作组），否则语音面板与主列表会呈现不同结构。
    val answerFoldLevel = LocalAppearanceSettings.current.answerFoldLevel
    val aggressiveFold = answerFoldLevel == AnswerFoldLevel.AGGRESSIVE
    val projectedTimelineEntries = timelineEntries ?: run {
        val standaloneTimelineProjection = remember(aggressiveFold) {
            AgentTimelineProjectionCache(
                fullProjection = { it.toTimelineEntries(aggressiveFold) },
                aggressiveGrouping = aggressiveFold,
            )
        }
        remember(visibleMessages) {
            StreamPerformanceDiagnostics.measure("timeline.project", visibleMessages.size.toLong()) {
                standaloneTimelineProjection.project(visibleMessages)
            }
        }
    }
    // 用户手动展开过的折叠组用 overrides 记住（组消失时清理）。
    val expansionSaver = remember {
        listSaver<Map<String, Boolean>, String>(
            save = { value -> value.flatMap { (key, expanded) -> listOf(key, expanded.toString()) } },
            restore = { value -> value.chunked(2).filter { it.size == 2 }.associate { it[0] to (it[1] == "true") } },
        )
    }
    var workExpansionOverrides by rememberSaveable(stateSaver = expansionSaver) {
        mutableStateOf<Map<String, Boolean>>(emptyMap())
    }
    // 「本轮回答出来，就把回答之前的内容收起来」：只在整轮停下之后生效，
    // 生成过程中一律摊开，不打扰看进度。收起范围由档位决定：
    // 普通/激进=折叠成摘要行（激进档连中间回复一起归组）；不折叠=彻底不收。
    val answerFoldEnabled = answerFoldLevel != AnswerFoldLevel.NONE
    val isTurnSettled = !isStreaming && !isPaused && !isCompressingContext
    val foldedWorkKeys = remember(projectedTimelineEntries, answerFoldEnabled, isTurnSettled) {
        projectedTimelineEntries.autoFoldedWorkKeys(answerFoldEnabled && isTurnSettled)
    }
    LaunchedEffect(projectedTimelineEntries) {
        val activeKeys = projectedTimelineEntries.filterIsInstance<AgentTimelineEntry.WorkProcess>().mapTo(mutableSetOf()) { it.key }
        if (workExpansionOverrides.keys.any { it !in activeKeys }) {
            workExpansionOverrides = workExpansionOverrides.filterKeys { it in activeKeys }
        }
    }
    val timelineRowsProjection = remember { AgentTimelineRowsCache() }
    val timelineRows = remember(projectedTimelineEntries, foldedWorkKeys, workExpansionOverrides) {
        StreamPerformanceDiagnostics.measure("timeline.project", projectedTimelineEntries.size.toLong()) {
            timelineRowsProjection.project(
                projectedTimelineEntries,
                foldedWorkKeys,
                workExpansionOverrides.filterValues { it }.keys,
            )
        }
    }
    // 连续用户气泡的组内位置（Single / GroupFirst / GroupMid / GroupLast），按实际行相邻判定。
    val userBubblePositions = remember(timelineRows) {
        buildMap {
            timelineRows.forEachIndexed { index, row ->
                val user = (row as? AgentTimelineRow.Message)?.message as? UserMessageUi
                    ?: return@forEachIndexed
                put(user.id, timelineRows.userBubblePosition(index))
            }
        }
    }
    LaunchedEffect(scrollToMessageId, timelineRows) {
        val target = scrollToMessageId ?: return@LaunchedEffect
        val index = timelineRows.indexOfFirst { it.containsMessageId(target) }
        if (index >= 0) {
            onBottomAnchorChanged(false)
            scrollState.animateScrollToItem(index)
            onScrollToMessageConsumed()
        } else if (timelineRows.isNotEmpty()) {
            onScrollToMessageConsumed()
        }
    }
    // Project onto the EXACT rows consumed by LazyColumn. Expansion and late
    // records move only the footer anchor, never the message or callback owner.
    // Generation only relaxes branching on PREVIOUS completed turns. The current
    // turn has no action bar until it closes, even between text/tool blocks.
    val turnFooters = remember(timelineRows, isStreaming, isPaused, isCompressingContext) {
        timelineRows.turnFooters(
            isStreaming = isStreaming,
            isCompressingContext = isCompressingContext,
            isPaused = isPaused,
        )
    }
    val finalResultMessageIds = remember(turnFooters) {
        turnFooters.values.mapTo(mutableSetOf()) { it.id }
    }
    // Reveal dependencies follow the projection's existing anchors, not a
    // second ownership heuristic. A stopped notice can own actions while an
    // earlier answer in that same turn is still revealing. New user boundaries
    // and completed footer anchors prevent one turn from blocking another.
    val footerRevealMessages = remember(timelineRows, turnFooters) {
        buildMap<String, List<AgentMessageUi>> {
            val answers = mutableListOf<AgentMessageUi>()
            timelineRows.forEach { row ->
                val message = (row as? AgentTimelineRow.Message)?.message
                if (message is UserMessageUi && !message.isSteerSupplement()) answers.clear()
                if (message is AgentMessageUi && message.content.isNotBlank()) answers.add(message)
                if (row.key in turnFooters) {
                    put(row.key, answers.toList())
                    answers.clear()
                }
            }
        }
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val fallbackStreamingStates = remember { mutableStateMapOf<String, StreamingMarkdownState>() }
    val streamingMarkdownStates = LocalStreamingMarkdownStates.current ?: fallbackStreamingStates
    // Messages already on screen before this live run must not replay. A text block that
    // arrives and ends in one snapshot is absent from this set, so it still gets a typewriter.
    val settledMessageIds = remember {
        mutableSetOf<String>().apply {
            if (isStreaming || isPaused) visibleMessages.forEach { add(it.id) }
        }
    }
    // Last list that populated settledMessageIds. Deliberately NOT snapshot state: it is
    // written from SideEffect purely as a change detector, and observable writes there
    // would schedule the extra recomposition this guard exists to avoid.
    val settledMessageSource = remember { arrayOfNulls<Any>(1) }
    // Rebuild only when the settled source list actually changes. This ran on every
    // composition and re-added every historical message id, which is O(n) on the commit
    // frame of an otherwise unrelated recomposition (keyboard, drawer, theme, settings).
    SideEffect {
        if (!isStreaming && !isPaused && settledMessageSource[0] !== visibleMessages) {
            settledMessageSource[0] = visibleMessages
            settledMessageIds.clear()
            visibleMessages.forEach { settledMessageIds.add(it.id) }
        }
    }
    // Pruning only matters when the set of visible messages changes membership: an
    // in-place delta keeps every existing key valid, so re-scanning all visible ids per
    // token is pure overhead. Size plus the edge ids is O(1) and still detects appends,
    // deletions and conversation switches (which change the first id).
    val visibleMessageEdges = remember(visibleMessages) {
        Triple(visibleMessages.size, visibleMessages.firstOrNull()?.id, visibleMessages.lastOrNull()?.id)
    }
    LaunchedEffect(visibleMessageEdges, streamingMarkdownStates) {
        if (streamingMarkdownStates.isEmpty()) return@LaunchedEffect
        val activeIds = visibleMessages.mapTo(HashSet()) { it.id }
        streamingMarkdownStates.keys.retainAll(activeIds)
    }
    val telemetry = LocalAgentContextTelemetry.current
    val compressingChildren = telemetry.children.filter { it.isCompacting }
    val compressingItemCount = if (isCompressingContext || isWaitingForCompression || compressingChildren.isNotEmpty()) 1 else 0
    val bottomItemIndex = timelineRows.size + compressingItemCount
    val userMessageTargets = remember(timelineRows) { timelineRows.lazyUserMessageIndices() }
    val directionThreshold = with(LocalDensity.current) { 12.dp.toPx() }
    val directionTracker = remember(scrollState, directionThreshold) {
        ConversationNavigationDirectionTracker(directionThreshold)
    }
    // The tracker keeps the gesture direction internally; nothing observes a Compose copy.
    // This used to be a `mutableStateOf` written from onPreScroll on every scroll event and
    // never read, so each gesture event scheduled snapshot work for no consumer.
    var messageNavigationJob by remember(scrollState) { mutableStateOf<Job?>(null) }
    DisposableEffect(scrollState) {
        onDispose { messageNavigationJob?.cancel() }
    }
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    // 手指拖走后的惯性也算用户滚动；跟底自己的 scrollBy 不能把这个标志打开。
    var isUserScrolling by remember { mutableStateOf(false) }
    // Observe user motion synchronously, before the asynchronous drag collector
    // and before another scheduled follow frame can mutate the list position.
    // 手指是否按在列表上。只观察、不消费事件。
    val pointerDown = remember { mutableStateOf(false) }
    var heldLeadingGapPx by remember { mutableIntStateOf(0) }
    var programmaticUserScrolls by remember { mutableIntStateOf(0) }
    val userScrollConnection = remember(scrollState, directionTracker) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    // 有些滚动也用 UserInput 来源，但手指根本没按下：把某块内容带进可视区
                    // （BringIntoView，例如取得焦点的选区）、无障碍滚动。它们不算用户滑动，
                    // 不能关掉跟底；否则快速输出时跟底一断，已经落后的那截一帧画进输入框。
                    if (!isUserScrollGesture(pointerDown.value)) {
                        if (StreamPerformanceDiagnostics.enabled && programmaticUserScrolls < 6) {
                            programmaticUserScrolls++
                            val frames = Throwable().stackTrace
                            StreamPerformanceDiagnostics.note("scroll") {
                                // 跟底状态在后面才声明；同一时刻的 follow 行已由越线诊断记录。
                                "source=userInputWithoutPointer dy=${available.y.toInt()} " +
                                    "userScroll=$isUserScrolling stack=${compactStack(frames)}"
                            }
                        }
                        return Offset.Zero
                    }
                    messageNavigationJob?.cancel()
                    isUserScrolling = true
                    // Result intentionally discarded: the tracker keeps direction for the
                    // next navigation request. Assigning it to Compose state made every
                    // scroll event invalidate the chat body.
                    directionTracker.onScroll(available.y, userInput = true)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                directionTracker.endGesture()
                return Velocity.Zero
            }
        }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()
    val currentAnchor = rememberUpdatedState(keepBottomAnchored)
    val currentStreaming = rememberUpdatedState(isStreaming)
    val currentVisibleMessages = rememberUpdatedState(visibleMessages)
    val currentDragging = rememberUpdatedState(isUserDragging)

    LaunchedEffect(scrollState) {
        snapshotFlow { currentDragging.value to scrollState.isScrollInProgress }
            .collect { (dragging, inProgress) ->
                StreamPerformanceDiagnostics.record("scroll.state", value = if (dragging) 1 else 0)
                isUserScrolling = when {
                    dragging -> true
                    !inProgress -> false
                    else -> isUserScrolling
                }
            }
    }

    var hasLeftBottom by remember { mutableStateOf(false) }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            if (messageNavigationJob != null) {
                null
            } else {
                // 手指压在列表上同样算拖动：onPreScroll 的 pointerDown 判定偶尔收不到
                // （例如手势从输入区边缘起手），只靠 isUserScrolling 会让跟底逻辑
                // 把已经翻到旧消息的列表又拽回底部，表现为「只能往一个方向滑」。
                ChatListFollowState(
                    dragging = isUserScrolling || pointerDown.value,
                    atBottom = scrollState.isConversationAtBottom(),
                    anchored = currentAnchor.value,
                )
            }
        }
            .distinctUntilChanged()
            .collect { state ->
                val (dragging, atBottom, anchored) = state ?: return@collect
                if (dragging && !atBottom) hasLeftBottom = true
                val next = resolveKeepBottomAnchored(
                    current = anchored,
                    isUserDragging = dragging,
                    isAtBottom = atBottom,
                    hasLeftBottom = hasLeftBottom,
                )
                if (next && atBottom) hasLeftBottom = false
                if (next != anchored) onBottomAnchorChanged(next)
            }
    }

    var isBottomSettling by remember { mutableStateOf(isStreaming) }
    LaunchedEffect(scrollState) {
        snapshotFlow {
            bottomFollowSettlingState(
                streaming = currentStreaming.value,
                anchored = currentAnchor.value,
                userScrolling = isUserScrolling,
            ) {
                hasPendingAssistantReveal(currentVisibleMessages.value) { message ->
                    val retained = streamingMarkdownStates[message.id]
                    retained != null && retained.revealedContent != message.content
                }
            }
        }
            .distinctUntilChanged()
            .collectLatest { state ->
                if (state == BottomFollowSettlingState.Disabled) {
                    isBottomSettling = false
                } else if (state == BottomFollowSettlingState.Active) {
                    isBottomSettling = true
                } else if (isBottomSettling) {
                    withFrameNanos { }
                    withFrameNanos { }
                    snapshotFlow { !scrollState.canScrollForward }.first { it }
                    isBottomSettling = false
                }
            }
    }

    var initialBottomPositionPending by remember(scrollState) { mutableStateOf(true) }
    val currentScrollTarget by rememberUpdatedState(scrollToMessageId)
    val shouldFollowBottom by rememberUpdatedState(
        resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = initialBottomPositionPending || isUserScrolling || messageNavigationJob != null,
            isBottomSettling = isBottomSettling,
        )
    )
    // 跟底时上提让尾部停在静止线附近：列表变高多少，上提就按缓动补多少，
    // 新行从静止线下方慢慢滑入，由跟底滚动接力收尾，下沿不会整行跳。
    // 以前点开最底部一行时先暂停上提，让内容从上沿往下长过静止线、再由跟底滚动追回来，
    // 标签会先不动、再被推上去，看起来像折了两次。现在展开从下沿长出（见 tailDetailsEnter），
    // 上提照常，标签随动画一帧一帧往上让开。
    val shouldLiftTail = shouldFollowBottom
    val markdownViewport = remember { io.github.mangi.eta.ui.markdown.MarkdownDrawViewport() }
    val markdownTransitionActive = LocalChatTransitionActive.current
    // 被盖住但仍露在屏幕上时不走整列离屏裁剪。完全打开时仍按原来的条件。
    val routeCovered = LocalChatRouteCovered.current
    val shouldClipTail = shouldClipChatTail(
        isStreaming = isStreaming,
        isBottomSettling = isBottomSettling,
        keepBottomAnchored = keepBottomAnchored,
        isUserScrolling = isUserScrolling,
        isUserDragging = isUserDragging,
        navigationActive = messageNavigationJob != null || scrollToMessageId != null,
        routeCovered = routeCovered,
    )
    SideEffect {
        markdownViewport.updateEnabled(
            !shouldLiftTail && !shouldClipTail && !routeCovered && !markdownTransitionActive,
        )
    }
    // 附件、输入框换行和 IME 改的是实际尾部留白，不是新消息增长。
    // 静态时没有跟底控制器接手；只在仍锚定且视口静止线确实变化时重新停靠。
    AnchorChatTailOnViewportChange(scrollState, bottomItemIndex) {
        currentAnchor.value && !currentStreaming.value && !isBottomSettling &&
            !initialBottomPositionPending && !pointerDown.value && !currentDragging.value &&
            !isUserScrolling && messageNavigationJob == null && currentScrollTarget == null
    }
    SideEffect {
        traceChatListOwnerCommit(
            trace = chatListTrace,
            enabled = scrollTraceEnabled,
            isUserDragging = isUserDragging,
            isUserScrolling = isUserScrolling,
            isBottomSettling = isBottomSettling,
            keepBottomAnchored = keepBottomAnchored,
            shouldClipTail = shouldClipTail,
            shouldFollowBottom = shouldFollowBottom,
            navigationActive = messageNavigationJob != null,
        )
    }
    val isListScrollable by remember {
        derivedStateOf { scrollState.canScrollForward || scrollState.canScrollBackward }
    }
    var streamFilledViewport by remember { mutableStateOf(false) }
    LaunchedEffect(isStreaming, isListScrollable) {
        streamFilledViewport = if (isStreaming) streamFilledViewport || isListScrollable else false
    }
    // 点击回调里问一次：这一行下面的内容会不会停在原处。只读 State，不参与组合；
    // remember 后引用不变，作为 CompositionLocal 提供时不会让整页重组。
    val expansionPinnedNow: () -> Boolean = remember(scrollState) {
        {
            resolveExpansionHoldsBottom(
                following = shouldFollowBottom,
                arrangedToBottom = shouldPinConversationToBottom(currentStreaming.value, streamFilledViewport),
                listScrollable = isListScrollable,
            )
        }
    }
    // 下沿被钉住的展开期间，跟底滚动每帧把新增高度一次吃掉，不走平滑加速。
    // 平滑跟底第一帧不动、之后慢慢加速，其余全靠绘制上提；上提一旦超过列表尾部留白，
    // 尾部哨兵被挤出可视区，上提归零，内容一帧掉下去三百多像素。展开动画本身已经是平滑的，
    // 这段时间里标签随动画逐帧上移即可。只在协程里读，写入不触发重组。
    var bottomSnapUntilNanos by remember { mutableLongStateOf(0L) }
    val expansionHoldsBottom: () -> Boolean = remember(scrollState) {
        {
            expansionPinnedNow().also {
                // 标题钉在原位，展开期间不做整表底部快照（那是标题位移的来源）。
            }
        }
    }
    // A measured/recovered message can be virtualized during a large expansion. Its stable
    // key must not replay the root opacity animation when composed again in the same chat.
    val appearedMessageKeys = remember { HashSet<String>() }
    // 点开工具或推理后，逐帧记下列表状态：跟底、上提、暂停上提、哨兵相对静止线的位置、
    // 用户是否在拖动。只在点击窗口内运行，读 layoutInfo 不参与组合。
    LaunchedEffect(scrollState) {
        snapshotFlow { StreamPerformanceDiagnostics.probeRequests.intValue }
            .collectLatest { request ->
                if (request == 0) return@collectLatest
                while (StreamPerformanceDiagnostics.probing) {
                    val frameNanos = withFrameNanos { it }
                    StreamPerformanceDiagnostics.probeListSample(frameNanos) {
                        val info = scrollState.layoutInfo
                        // shouldLiftTail 是组合时的快照，这里按当前的跟底状态重新算。
                        val liftingNow = shouldFollowBottom
                        val lift = if (liftingNow) {
                            resolveFollowTailLag(true, scrollState.followTailOverflow()).liftPx.toInt()
                        } else 0
                        "follow=$shouldFollowBottom lift=$liftingNow liftPx=$lift pinned=${expansionPinnedNow()} snap=${System.nanoTime() < bottomSnapUntilNanos} " +
                            "userScroll=$isUserScrolling overflowPx=${scrollState.followTailOverflow()} " +
                            "first=${scrollState.firstVisibleItemIndex}:${scrollState.firstVisibleItemScrollOffset} " +
                            "visible=${info.visibleItemsInfo.size} total=${info.totalItemsCount}"
                    }
                }
            }
    }
    // 快速输出时尾部越过静止线、画进输入框的诊断。只在布局或跟底状态变化时取样（snapshotFlow），
    // 不额外请求帧。跟底上提时列表裁在静止线上，真正画进输入框的只能是没在上提的时候；
    // 上提时越线的部分被裁掉，只计数。跟底的各个条件变化、越线开始和结束各记一行。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val generation = StreamPerformanceDiagnostics.sessionGeneration.longValue
            generation to (StreamPerformanceDiagnostics.enabled && (currentStreaming.value || isBottomSettling))
        }
            .distinctUntilChanged()
            .collectLatest { (generation, active) ->
                if (!active) return@collectLatest
                var lastState = ""
                var breachSamples = 0
                var breachMaxPx = 0
                snapshotFlow {
                    if (StreamPerformanceDiagnostics.sessionGeneration.longValue != generation ||
                        !StreamPerformanceDiagnostics.enabled) return@snapshotFlow null
                    val info = scrollState.layoutInfo
                    val sentinel = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
                    val last = info.visibleItemsInfo.lastOrNull()
                    val tailBottom = resolveTailBottomPx(
                        sentinelBottom = sentinel?.let { it.offset + it.size },
                        lastVisibleIndex = last?.index,
                        lastVisibleBottom = last?.let { it.offset + it.size },
                        totalItems = info.totalItemsCount,
                    )
                    val restLine = info.viewportEndOffset - info.afterContentPadding
                    val lifting = shouldFollowBottom
                    val lift = if (lifting) {
                        resolveFollowTailLag(true, scrollState.followTailOverflow()).liftPx.toInt()
                    } else 0
                    TailBreachSample(
                        overPx = resolveTailDrawnOverflow(tailBottom, restLine, lift),
                        tailBottomPx = tailBottom,
                        restLinePx = restLine,
                        padPx = info.afterContentPadding,
                        sentinelVisible = sentinel != null,
                        lastIndex = last?.index ?: -1,
                        totalItems = info.totalItemsCount,
                        lifting = lifting,
                        state = "follow=$lifting streaming=${currentStreaming.value} " +
                            "anchored=${currentAnchor.value} userScroll=$isUserScrolling " +
                            "settling=$isBottomSettling initialPending=$initialBottomPositionPending " +
                            "nav=${messageNavigationJob != null}",
                    )
                }
                    .distinctUntilChanged()
                    .collect { sample ->
                        if (sample == null || StreamPerformanceDiagnostics.sessionGeneration.longValue != generation ||
                            !StreamPerformanceDiagnostics.enabled) return@collect
                        val over = sample.overPx
                        if (sample.state != lastState) {
                            StreamPerformanceDiagnostics.note("follow") {
                                "${sample.state} tailVisible=${sample.sentinelVisible} overPx=$over padPx=${sample.padPx}"
                            }
                            lastState = sample.state
                        }
                        if (over != null && over > 1) {
                            StreamPerformanceDiagnostics.record(
                                if (sample.lifting) "tail.clippedPx" else "tail.breachPx", value = over.toLong(),
                            )
                        }
                        if (!sample.lifting && over != null && over > 1) {
                            if (breachSamples == 0) {
                                StreamPerformanceDiagnostics.note("breach") {
                                    "state=start overPx=$over tailBottomPx=${sample.tailBottomPx} " +
                                        "restLinePx=${sample.restLinePx} padPx=${sample.padPx} " +
                                        "sentinel=${sample.sentinelVisible} last=${sample.lastIndex}/${sample.totalItems} " +
                                        sample.state
                                }
                            }
                            breachSamples++
                            breachMaxPx = maxOf(breachMaxPx, over)
                        } else if (breachSamples > 0) {
                            StreamPerformanceDiagnostics.note("breach") {
                                "state=end samples=$breachSamples maxOverPx=$breachMaxPx ${sample.state}"
                            }
                            breachSamples = 0
                            breachMaxPx = 0
                        }
                    }
            }
    }
    val viewportRecovery = remember(scrollState, densityScale) {
        BottomFollowViewportRecovery(scrollState, ChatBottomSentinelKey, density = densityScale)
    }
    val canOwnWorkExpansionViewport: () -> Boolean = remember(scrollState) {
        {
            resolveWorkExpansionViewportOwnership(
                keepBottomAnchored = currentAnchor.value,
                initialBottomPositionPending = initialBottomPositionPending,
                pointerDown = pointerDown.value,
                isUserDragging = currentDragging.value,
                isUserScrolling = isUserScrolling,
                navigationActive = messageNavigationJob != null || currentScrollTarget != null,
            )
        }
    }

    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    // Initial positioning owns the list only once. New timeline items and network
    // completion must go through the continuous follow controller below.
    LaunchedEffect(scrollState) {
        try {
            val initial = snapshotFlow {
                InitialBottomPosition(
                    bottomItemIndex = currentBottomItemIndex,
                    hasLayout = scrollState.layoutInfo.visibleItemsInfo.isNotEmpty(),
                    anchored = currentAnchor.value,
                    interrupted = isUserScrolling || messageNavigationJob != null || currentScrollTarget != null,
                )
            }.first { it.ready }
            if (initial.shouldPosition) {
                StreamPerformanceDiagnostics.record("follow.initialSnap")
                // 初始定位同样平滑收尾：列表已按底部索引起步时差额极小，通常一记
                // 短弹簧即收住；布局滞后导致的跨项差额走翻页动画，替代瞬跳。
                settleListToBottom(scrollState, initial.bottomItemIndex) {
                    currentAnchor.value && !isUserScrolling &&
                        messageNavigationJob == null && currentScrollTarget == null
                }
            }
        } finally {
            initialBottomPositionPending = false
        }
    }

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            // Preserve the existing gate; disabling must still send a zero target.
            if (!shouldFollowBottom || pointerDown.value) return@snapshotFlow InactiveBottomFollowLayout
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
            BottomFollowLayout(
                enabled = true,
                bottomItemIndex = currentBottomItemIndex,
                // 哨兵被挤出可视区、但最后一段内容还可见时，用它的下沿，不再按整屏估算。
                sentinelBottom = resolveTailBottomPx(
                    sentinelBottom = sentinel?.let { it.offset + it.size },
                    lastVisibleIndex = lastVisible?.index,
                    lastVisibleBottom = lastVisible?.let { it.offset + it.size },
                    totalItems = layoutInfo.totalItemsCount,
                ),
                // 视口已扣除底栏高度；这里只扣列表自身的尾部留白。
                // 跟底目标仍是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = lastVisible?.index,
                viewportSizePx = layoutInfo.viewportSize.height,
                canScrollForward = scrollState.canScrollForward,
                lastVisibleOffset = lastVisible?.offset,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                    viewportSizePx = layout.viewportSizePx,
                    canScrollForward = layout.canScrollForward,
                )
                StreamPerformanceDiagnostics.record("follow.decision", value = decision.scrollByPx.toLong())
                bottomFollowDecisions.trySend(decision)
            }
    }

    // One controller retains velocity across layout targets, including line-height
    // steps. Neither a missing sentinel nor stream completion may bypass it.
    LaunchedEffect(scrollState, bottomFollowDecisions, densityScale) {
        val motion = BottomFollowMotion()
        var remainingDistancePx = 0f
        var previousFrameNanos: Long? = null
        fun reset() {
            remainingDistancePx = 0f
            previousFrameNanos = null
            motion.reset()
        }
        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.coerceAtLeast(0).toFloat()
        }
        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f) {
                reset()
                accept(bottomFollowDecisions.receive())
            }
            while (true) {
                accept(bottomFollowDecisions.tryReceive().getOrNull() ?: break)
            }
            if (!shouldFollowBottom || pointerDown.value || isUserScrolling || messageNavigationJob != null) {
                reset()
                continue
            }
            if (remainingDistancePx <= 0f) continue
            val frameNanos = withFrameNanos { it }
            previousFrameNanos?.let { previous ->
                StreamPerformanceDiagnostics.record("follow.frameGap", frameNanos - previous)
            }
            previousFrameNanos = frameNanos
            while (true) {
                accept(bottomFollowDecisions.tryReceive().getOrNull() ?: break)
            }
            if (!shouldFollowBottom || pointerDown.value || isUserScrolling || messageNavigationJob != null) {
                reset()
                continue
            }
            val snapping = System.nanoTime() < bottomSnapUntilNanos
            val layout = scrollState.layoutInfo
            val latestOverflow = layout.measuredTailOverflow()
            val expansionOwnsViewport = viewportRecovery.hasActiveExpansion(canOwnWorkExpansionViewport())
            if (expansionOwnsViewport && latestOverflow == null) {
                // The measured-anchor owner recovers unknown-tail expansion. Do not let the
                // ordinary one-viewport fallback race it; retain the pending target for expiry.
                motion.reset()
                previousFrameNanos = null
                continue
            }
            if ((snapping || expansionOwnsViewport) && latestOverflow != null) {
                remainingDistancePx = latestOverflow.coerceAtLeast(0).toFloat()
            }
            val step = if (snapping) {
                motion.reset()
                remainingDistancePx
            } else {
                // 超出绘制缓冲的差额也并进同一个速度控制器：一次性长高（工具卡片、
                // 整段正文）同样从静止起步、按加速度先快后慢地追上，收尾时速度随
                // 剩余距离一起回落。以前这段按固定速度逐帧推，长得越多越像匀速硬推。
                val catchUpPx = latestOverflow
                    ?.let { (it - layout.afterContentPadding).coerceAtLeast(0) }
                    ?: 0
                val followDistancePx = maxOf(remainingDistancePx, catchUpPx.toFloat())
                val smoothStep = motion.step(followDistancePx, frameNanos, densityScale)
                snapFollowScrollStep(
                    // 实测溢出优先：追赶永远不滚过尾部的真实差额。
                    latestOverflow?.let { minOf(smoothStep, it.coerceAtLeast(0).toFloat()) }
                        ?: smoothStep,
                    followDistancePx,
                )
            }
            // Within the draw buffer, the first frame still only establishes timing.
            if (step <= 0f) continue
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    if (!pointerDown.value && !isUserScrolling && messageNavigationJob == null && shouldFollowBottom) {
                        // Post-layout expansion recovery may already have consumed this target.
                        // Recheck fresh geometry inside the scroll owner, not the queued decision.
                        val currentStep = resolveFollowScrollStepAfterRecovery(
                            step, scrollState.layoutInfo.measuredTailOverflow(),
                            expansionOwnsViewport = viewportRecovery.hasActiveExpansion(canOwnWorkExpansionViewport()),
                        )
                        consumedStep = StreamPerformanceDiagnostics.measure("follow.scroll") { scrollBy(currentStep) }
                        if (StreamPerformanceDiagnostics.probing) {
                            StreamPerformanceDiagnostics.probeNote("follow") {
                                "stepPx=${"%.1f".format(step)} consumedPx=${"%.1f".format(consumedStep)} " +
                                    "remainingPx=${"%.1f".format(remainingDistancePx - consumedStep)}"
                            }
                        }
                        StreamPerformanceDiagnostics.record("follow.step", value = (consumedStep * 1000).toLong())
                    }
                }
                if (consumedStep > 0f) {
                    remainingDistancePx = (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else reset()
            } catch (cancelled: CancellationException) {
                StreamPerformanceDiagnostics.record("follow.cancelled")
                if (!currentCoroutineContext().isActive) throw cancelled
                reset()
            }
        }
    }

    // 输入器悬浮在会话之上：视口铺满到屏幕底，输入框四周透明、能看到后面的消息。
    // 跟底输出期间（思考/正文生成、未手动滑动），卡片/正文每长一行，跟底滚动要晚几帧
    // 才追上。绘制阶段会把已经量到的尾部上提；输出及渲染收尾时才裁在输入框上方的静止线，
    // 覆盖超快输出在 isStreaming 结束后、列表滚动尚未完成的过渡帧。
    // 静态锚定只用于尾部停靠，不是永久绘制遮挡；无需先滑动，透明周边也能透出消息。
    // 用户一拖动或跳转消息，输出保护也立即解除。
    val restClip = remember(bottomInset) { ComposerRestClip(bottomInset + ConversationComposerGap) }
    // 尾部这一帧量不到时不能把上提清零，否则卡片会掉进输入框再弹回来。
    val heldTailLift = remember { intArrayOf(0) }
    // 上提自身的缓动：起步/收尾都比滚动更慢，避免每行一次“硬”上跳。
    val tailLiftMotion = remember { createTailLiftMotion() }
    // The callback reports message.id, but a work step's lazy key is work-step:<id>.
    // ThinkingRow reports the message id; only the anchor release matters here.
    val onThinkingRowToggle: (String, Boolean) -> Unit = { _, willExpand ->
        // 展开/收起一律钉住标题。用户主动展开＝解除底部锚定，面板自上而下向
        // 屏幕下方生长（超出静止线的部分由用户滚动查看）；不再回收视口、不做底部
        // 快照——这两者都会把整列上提，正是标题位移的根源。
        if (willExpand) onBottomAnchorChanged(false)
    }
    Box(
        modifier = modifier
            .onGloballyPositioned(markdownViewport::updateCoordinates)
            .clipToBounds()
            // clipRect 和普通 clip 都裁不到子级 graphicsLayer（跟底上提、思考卡片的离屏纹理）。
            // 先把整列画进一张按静止线裁切的离屏纹理，文字才不会露进输入框。
            .then(
                if (shouldClipTail) {
                    Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        clip = true
                        shape = restClip
                    }
                } else {
                    Modifier
                },
            )
            .drawWithContent {
                // 不跟底时不要读 layoutInfo，否则每次滑动都让绘制层失效。
                // 上提用的是本帧布局。输出很快时，新长出的一行会先画过静止线、进到输入框里。
                // 跟底期间一律裁在静止线；上提仍然把已经量到的尾部停在线上方。
                if (!shouldClipTail) {
                    drawContent()
                    return@drawWithContent
                }
                val restLine = (size.height - (bottomInset + ConversationComposerGap).toPx()).coerceAtLeast(0f)
                clipRect(bottom = restLine) { this@drawWithContent.drawContent() }
            },
    ) {
        val speechPrefaceProjection = remember { AgentSpeechPrefaceCache() }
        // 朗读范围与界面展示一致：激进折叠收起且未展开的工作组里，中间回复不可见，
        // 朗读 preface 同步剔除。展开摘要后这些回复重新可见，preface 随之恢复。
        val foldedReplyIds = remember(timelineRows) {
            buildSet {
                timelineRows.forEach { row ->
                    if (row is AgentTimelineRow.WorkSummary && !row.expanded) {
                        row.group.messages.forEach { message ->
                            if (message is AgentMessageUi) add(message.id)
                        }
                    }
                }
            }
        }
        val speechPrefaces = remember(visibleMessages, finalResultMessageIds, foldedReplyIds) {
            StreamPerformanceDiagnostics.measure("timeline.prefaces", visibleMessages.size.toLong()) {
                speechPrefaceProjection.project(visibleMessages, finalResultMessageIds, foldedReplyIds)
            }
        }
        val messageActions = remember { ChatMessageActions() }
        SideEffect {
            messageActions.onSuggestionClick = onSuggestionClick
            messageActions.onRunTraceClick = onRunTraceClick
            messageActions.onOpenBrowser = onOpenBrowser
            messageActions.onEditMessage = onEditMessage
            messageActions.onDeleteMessage = onDeleteMessage
            messageActions.onRegenerateMessage = onRegenerateMessage
            messageActions.onBranchMessage = onBranchMessage
            messageActions.onQuestionDraftChanged = onQuestionDraftChanged
            messageActions.onSubmitQuestionAnswer = onSubmitQuestionAnswer
            // Commit count only (ns=0): proves this content lambda was applied, not its cost.
            StreamPerformanceDiagnostics.record("chat.content.commit", value = 1)
        }
        androidx.compose.runtime.CompositionLocalProvider(
            io.github.mangi.eta.ui.markdown.LocalMarkdownDrawViewport provides markdownViewport,
        ) {
        LazyColumn(
            state = scrollState,
            // 钉底只在列表真实放得下时启用，直接以测量结果为准：
            // 旧条件（非流式即钉底）会给「可滚动」的空闲列表套上 Arrangement.Bottom，
            // 它会把越界内容的滚动位置重新钳到末端，表现为只能往一个方向滚；
            // 且旧条件还随 pointerDown 在 Bottom/Top 间翻转，手势中重新布局会跳动。
            verticalArrangement = if (isListScrollable) Arrangement.Top else Arrangement.Bottom,
            modifier = Modifier
                .streamDiagnosticMeasure("list.measure", diagnosticListAttribution)
                .streamDiagnosticPlacement("list.place", diagnosticListAttribution)
                .fillMaxSize()
                // 层本身就会在滑动时整列重录。只有跟底上提才挂上；手指滑动时列表直接滚动，
                // 已完成块的离屏纹理可以复用。打字机和振动不在这里停。
                .then(if (shouldLiftTail) {
                    Modifier.graphicsLayer {
                        val overflow = scrollState.followTailOverflow()
                        val held = heldTailLift[0]
                        // 上提按缓动曲线逐帧追赶溢出：新行缓慢滑入，而不是匀速冲到目标后硬停；
                        // 目标回落时仍由 nextHeldTailLift 立即让位，尾部不会越过静止线。
                        val risePx = if (pointerDown.value || overflow == null) {
                            tailLiftMotion.reset()
                            0f
                        } else {
                            tailLiftMotion.step(
                                distancePx = (overflow - held).coerceAtLeast(0).toFloat(),
                                frameTimeNanos = System.nanoTime(),
                                density = densityScale,
                            )
                        }
                        translationY = -nextHeldTailLift(
                            shouldLift = true,
                            overflowPx = overflow,
                            heldPx = held,
                            pointerDown = pointerDown.value,
                            maxRisePx = ceil(risePx).toInt().coerceAtLeast(1),
                        ).also { heldTailLift[0] = it }.toFloat()
                    }
                } else {
                    Modifier.also {
                        heldTailLift[0] = 0
                        tailLiftMotion.reset()
                    }
                })
                .onGloballyPositioned {
                    StreamPerformanceDiagnostics.recordListGeometry(diagnosticList, scrollState, visibleMessages.size)
                    // Post-layout, before draw: pinned work insertion preserves a measured
                    // pre-click key even when the tail is outside the lazy measurement window.
                    // Normal follow still consumes only excess beyond the existing draw buffer.
                    val consumed = viewportRecovery.recover(
                        canRecoverExpansion = canOwnWorkExpansionViewport,
                    ) {
                        shouldFollowBottom && canOwnWorkExpansionViewport()
                    }
                    if (consumed > 0f) {
                        StreamPerformanceDiagnostics.record(
                            "follow.viewportRecovery", value = (consumed * 1000).toLong(),
                        )
                    }
                }
                .pointerInput(Unit) {
                    try {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.any { it.pressed }
                                if (pointerDown.value != pressed) {
                                    // Preserve short-list bottom alignment as real leading space.
                                    // Otherwise tail growth moves the first row before scrollBy runs.
                                    heldLeadingGapPx = if (pressed && !scrollState.canScrollBackward && !scrollState.canScrollForward) {
                                        scrollState.layoutInfo.visibleItemsInfo.firstOrNull()
                                            ?.takeIf { it.index == 0 }?.offset?.coerceAtLeast(0) ?: 0
                                    } else 0
                                    pointerDown.value = pressed
                                }
                            }
                        }
                    } finally {
                        pointerDown.value = false
                        heldLeadingGapPx = 0
                    }
                }
                .nestedScroll(userScrollConnection),
            // 顶部让出壳层顶栏的高度（列表延伸到顶栏背后，滚入的部分
            // 由顶栏渐变板淡出）；最后一条静止时停在输入框上方 14dp。
            contentPadding = PaddingValues(
                top = topInset + 12.dp + with(LocalDensity.current) { heldLeadingGapPx.toDp() },
                bottom = ConversationComposerGap + bottomInset,
                start = horizontalPadding,
                end = horizontalPadding,
            ),
            overscrollEffect = null,
        ) {
            items(
                items = timelineRows,
                key = { it.key },
                contentType = { row ->
                    when (row) {
                        is AgentTimelineRow.Message -> "message"
                        is AgentTimelineRow.WorkSummary -> "work-header"
                        is AgentTimelineRow.WorkStep -> when (row.message) {
                            is ToolActivityMessageUi -> "work-tool"
                            is ThinkingMessageUi -> "work-thinking"
                            is AgentQuestionMessageUi -> "work-question"
                            else -> "work-summary"
                        }
                    }
                },
            ) { entry ->
                // No UI container or rendering modifier: record committed row identity only.
                ChatRowTrace(
                    rowKey = entry.key,
                    rowType = when (entry) {
                        is AgentTimelineRow.Message -> when (entry.message) {
                            is UserMessageUi -> "user"
                            is AgentMessageUi -> "agent"
                            is ThinkingMessageUi -> "thinking"
                            is ToolActivityMessageUi -> "tool"
                            else -> "message"
                        }
                        is AgentTimelineRow.WorkSummary -> "work-header"
                        is AgentTimelineRow.WorkStep -> when (entry.message) {
                            is ToolActivityMessageUi -> "work-tool"
                            is ThinkingMessageUi -> "work-thinking"
                            is AgentQuestionMessageUi -> "work-question"
                            else -> "work-summary"
                        }
                    },
                    enabled = scrollTraceEnabled,
                )
                // Keep the row key/index and animate its root, including its footer.
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalExpansionHoldsBottom provides expansionHoldsBottom,
                    LocalChatAnchorRelease provides {
                        // 展开前解除底部锚定（标题钉住、面板向下生长）。
                        onBottomAnchorChanged(false)
                    },
                    LocalCompletedMarkdownCache provides completedMarkdownCache,
                    LocalStreamDiagnosticRow provides StreamPerformanceDiagnostics.rowAttribution(
                        diagnosticList, entry.key, timelineDiagnosticRowType(entry)),
                ) {
                val diagnosticRow = LocalStreamDiagnosticRow.current
                val firstMessageAppearance = if (entry is AgentTimelineRow.Message) {
                    // Retire an existing root's appearance on an explicit toggle even
                    // if LazyColumn retains its composition while temporarily unmeasured.
                    // New message keys still get the normal fade.
                    remember(entry.key) { appearedMessageKeys.add(entry.key) }
                } else false
                // 刚发出的用户气泡：入场交给气泡自己演（轻上浮 + 淡入），列表层不再叠一遍淡入。
                // 记在行内：入场作废后这行不重算，避免透明度被两段动画相乘。
                val sentEntrance = remember(entry.key) {
                    entry is AgentTimelineRow.Message &&
                        entry.message is UserMessageUi &&
                        entry.message.id == sentUserMessageEntranceId
                }
                Column(
                    modifier = Modifier
                        .streamDiagnosticMeasure("row.measure", diagnosticRow)
                        .streamDiagnosticPlacement("row.place", diagnosticRow)
                        .streamDiagnosticDraw("row.draw", diagnosticRow)
                        .fillMaxWidth().then(
                        if (entry is AgentTimelineRow.Message) Modifier.animateItem(
                            fadeInSpec = if (firstMessageAppearance && !sentEntrance) chatMessageFadeInSpec() else null,
                            placementSpec = null,
                            fadeOutSpec = null,
                        ) else Modifier,
                    ),
                ) {
                when (entry) {
                    is AgentTimelineRow.Message -> {
                        val message = entry.message
                        if (sentEntrance) {
                            // 播过即作废：滚动回收后重新组合不再重播。
                            LaunchedEffect(message.id) { onSentUserMessageEntranceConsumed(message.id) }
                        }
                        ChatMessageItem(
                            message = message,
                            speechPreface = "",
                            retainedStreamingState = (message as? AgentMessageUi)
                                ?.takeIf { agentMessage ->
                                    agentMessage.isStreaming ||
                                        streamingMarkdownStates.containsKey(agentMessage.id) ||
                                        (
                                            (isStreaming || isPaused) &&
                                                agentMessage.id !in settledMessageIds &&
                                                agentMessage.content.isNotEmpty()
                                            )
                                }
                                ?.let { agentMessage ->
                                    streamingMarkdownStates.getOrPut(agentMessage.id) {
                                        StreamingMarkdownState()
                                    }
                                },
                            actions = messageActions,
                            showBrowserShortcut = message is ToolActivityMessageUi &&
                                message.toolName == "browser_use" &&
                                message.id == currentBrowserMessageId,
                            enableLivePreview = message is ToolActivityMessageUi && !isStreaming,
                            // UserMessageBubble 忽略这些开关；其操作行改为点气泡展开（见 UserMessageBubble）。
                            showCopyAction = false,
                            showMessageActions = false,
                            messageActionsEnabled = message is UserMessageUi && messageActionsEnabled && !isStreaming && !isPaused,
                            branchEnabled = message is UserMessageUi && branchEnabled,
                            isEditing = message is UserMessageUi && message.id == editTargetMessageId,
                            justSent = sentEntrance,
                            userBubblePosition = userBubblePositions[message.id]
                                ?: UserBubblePosition.Single,
                            isPaused = isPaused,
                            onThinkingToggle = if (message is ThinkingMessageUi) {
                                { _, willExpand -> onThinkingRowToggle(entry.key, willExpand) }
                            } else null,
                            // Keep this modifier stable. Attaching fadeIn only after the run
                            // ends replays appearance on the already-visible answer.
                            modifier = Modifier,
                        )
                    }

                    is AgentTimelineRow.WorkSummary -> {
                        AgentWorkProcessSummary(
                            group = entry.group,
                            expanded = entry.expanded,
                            isPaused = isPaused,
                            onToggle = {
                                // 手动展开的组在投影里恢复为「摘要行 + 步骤行」；再点收起。
                                workExpansionOverrides = if (entry.expanded) {
                                    workExpansionOverrides - entry.key
                                } else {
                                    workExpansionOverrides + (entry.key to true)
                                }
                            },
                            // 组首与列表内容的间距 2dp；展开后与步骤行之间是 2dp 露缝（1+1）。
                            modifier = Modifier.padding(
                                top = 2.dp,
                                bottom = if (entry.expanded) 1.dp else 2.dp,
                            ),
                        )
                    }
                    is AgentTimelineRow.WorkStep -> {
                        val message = entry.message
                        val retainedState = if (message is ThinkingMessageUi &&
                            (message.isStreaming || streamingMarkdownStates.containsKey(message.id))) {
                            streamingMarkdownStates.getOrPut(message.id) { StreamingMarkdownState() }
                        } else null
                        ChatMessageItem(
                            message = message,
                            actions = messageActions,
                            retainedStreamingState = retainedState,
                            showBrowserShortcut = message is ToolActivityMessageUi &&
                                message.id == currentBrowserMessageId,
                            enableLivePreview = message is ToolActivityMessageUi && !isStreaming,
                            isPaused = isPaused,
                            onThinkingToggle = if (message is ThinkingMessageUi) {
                                { _, willExpand -> onThinkingRowToggle(message.id, willExpand) }
                            } else null,
                            // 连续步骤是设置页「同组项」式的连续分段列表：组内露缝 2dp（1+1），
                            // 组首/组末与相邻内容保持 2dp 间距。
                            segmentedPosition = when {
                                entry.isFirst && entry.isLast -> SegmentedRowPosition.Whole
                                entry.isFirst -> SegmentedRowPosition.First
                                entry.isLast -> SegmentedRowPosition.Last
                                else -> SegmentedRowPosition.Middle
                            },
                            modifier = Modifier.padding(
                                top = if (entry.isFirst) 2.dp else 1.dp,
                                bottom = if (entry.isLast) 2.dp else 1.dp,
                            ),
                        )
                    }
                }
                turnFooters[entry.key]?.let { owner ->
                    val revealPending = footerRevealMessages[entry.key].orEmpty().any { answer ->
                        val retained = streamingMarkdownStates[answer.id]
                        answer.isStreaming ||
                            (retained != null && retained.revealedContent != answer.content && (isStreaming || isPaused)) ||
                            (retained == null && (isStreaming || isPaused) && answer.id !in settledMessageIds)
                    }
                    AgentTurnFooter(
                        message = owner,
                        actions = messageActions,
                        revealPending = revealPending,
                        isRunActive = (isStreaming || isPaused) && owner.id !in finalResultMessageIds,
                        speechPreface = speechPrefaces[owner.id].orEmpty(),
                        messageActionsEnabled = messageActionsEnabled && !isStreaming && !isPaused,
                        branchEnabled = branchEnabled,
                    )
                }
                }
                }
            }
            if (compressingItemCount > 0) {
                item(key = ChatContextCompressingKey) {
                    Column {
                        if (isCompressingContext || isWaitingForCompression) ContextCompressingIndicator(
                            modelName = "${telemetry.compactingModelName.ifBlank { telemetry.mainModelName }}（${stringResource(R.string.acm_main_agent)}）",
                            waiting = isWaitingForCompression && !isCompressingContext,
                            modifier = Modifier.animateItem(
                                fadeInSpec = chatMessageFadeInSpec(),
                                placementSpec = null,
                                fadeOutSpec = null,
                            ),
                        )
                        compressingChildren.forEach { child ->
                            androidx.compose.runtime.key(child.taskId) {
                                ContextCompressingIndicator(modelName = child.contextLabel(context))
                            }
                        }
                    }
                }
            }
            item(key = ChatBottomSentinelKey) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        }

        fun navigateUserMessage(direction: ConversationNavigationDirection, toEdge: Boolean) {
            // Do not queue animations on rapid taps; a new drag cancels the active jump.
            if (messageNavigationJob != null) return
            // 方向由用户按的按钮决定（上一轮 / 下一轮），不再按「当前是否在底部」推断。
            val target = conversationUserMessageTarget(
                userMessageTargets, scrollState.firstVisibleItemIndex, bottomItemIndex, direction, toEdge,
                firstVisibleScrollOffset = scrollState.firstVisibleItemScrollOffset,
            )
            onBottomAnchorChanged(false)
            messageNavigationJob = coroutineScope.launch {
                try {
                    // Let the follow/boundary-haptic observers yield before moving the list.
                    withFrameNanos { }
                    // Keep one continuous motion, slowing only at the selected user-message target.
                    scrollState.animateToConversationTurn(target)
                    if (target == bottomItemIndex) snapListToBottom(scrollState, currentBottomItemIndex)
                    onBottomAnchorChanged(
                        direction == ConversationNavigationDirection.Down && scrollState.isConversationAtBottom(),
                    )
                } finally {
                    messageNavigationJob = null
                }
            }
        }
        val canGoUp by remember(scrollState) {
            derivedStateOf { scrollState.canScrollBackward }
        }
        val canGoDown by remember(scrollState) {
            derivedStateOf { scrollState.canScrollForward }
        }
        // 手指滑动（含松手后的惯性）期间亮出按钮对；滚动停稳后再倒数几秒淡出。
        // 跳转动画进行中也保持可见，避免按钮在动画途中先从眼前消失。
        var turnNavigationShown by remember { mutableStateOf(false) }
        LaunchedEffect(isUserScrolling) {
            if (isUserScrolling) {
                turnNavigationShown = true
            } else {
                delay(TurnNavigationIdleHideMillis)
                turnNavigationShown = false
            }
        }
        val showMessageNavigation =
            (turnNavigationShown || messageNavigationJob != null) && (canGoUp || canGoDown)
        ConversationTurnNavigationButton(
            upEnabled = canGoUp,
            downEnabled = canGoDown,
            visible = showMessageNavigation,
            onStep = { direction -> navigateUserMessage(direction, toEdge = false) },
            onEdge = { direction -> navigateUserMessage(direction, toEdge = true) },
            modifier = Modifier
                // 右下角默认落位：只用 Box 的 BottomEnd 对齐，不再为「与通话圆钮同心」手算水平偏移。
                // 底栏渐变板向上越过胶囊顶盖圆心（40dp 渐变条）且整体压在内容层之上，
                // 按钮组整体贴在右下方。
                // 垂直：让按钮组中线与「滚到底时最后一轮页脚动作组（复制/朗读/fork…）」的中线
                // 同高——两组都贴着右下角，同高后读作一行并列的控件。
                .align(Alignment.BottomEnd)
                .padding(
                    bottom = bottomInset + TurnNavigationCenterAboveBottom -
                        IconButtonDefaults.smallContainerSize().height / 2,
                    // 页边距取全站惯用的 16dp 屏幕留白；与上面同高对齐的页脚动作组
                    // （horizontal = 16dp）右缘因此也落在同一条竖线上。
                    // 宽屏下再补内容列留白，让按钮跟着消息列靠里，而不是飘到屏幕最右。
                    end = 16.dp + horizontalPadding,
                ),
        )
    }
}

internal enum class BottomFollowSettlingState { Disabled, Active, Draining }

/** Branch-equivalent short circuit: do not scan messages until reveal completion matters. */
internal inline fun bottomFollowSettlingState(
    streaming: Boolean,
    anchored: Boolean,
    userScrolling: Boolean,
    pendingReveal: () -> Boolean,
): BottomFollowSettlingState = when {
    !anchored || userScrolling -> BottomFollowSettlingState.Disabled
    streaming || pendingReveal() -> BottomFollowSettlingState.Active
    else -> BottomFollowSettlingState.Draining
}

private val InactiveBottomFollowLayout = BottomFollowLayout(
    enabled = false, bottomItemIndex = 0, sentinelBottom = null, viewportEnd = 0,
    lastVisibleIndex = null, viewportSizePx = 0, canScrollForward = false, lastVisibleOffset = null,
)

internal data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
    val viewportSizePx: Int,
    val canScrollForward: Boolean,
    // Keep missing-sentinel targets live while scrolling inside one very tall item.
    val lastVisibleOffset: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
    viewportSizePx: Int = viewportEnd,
    canScrollForward: Boolean = true,
): BottomFollowDecision {
    if (!enabled || !canScrollForward) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null && lastVisibleIndex != null && lastVisibleIndex < bottomItemIndex ->
            BottomFollowDecision(scrollByPx = viewportSizePx.coerceAtLeast(1))
        else -> BottomFollowDecision()
    }
}

internal data class InitialBottomPosition(
    val bottomItemIndex: Int,
    val hasLayout: Boolean,
    val anchored: Boolean,
    val interrupted: Boolean,
) {
    val ready: Boolean get() = !anchored || interrupted || (bottomItemIndex > 0 && hasLayout)
    val shouldPosition: Boolean get() = ready && anchored && !interrupted
}

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式或中途压缩期间当前轮次尚未结束，最后一轮不标记，等结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 *
 * 追加/steering 的用户消息不算新一轮：被打断的正文和继续输出同属一段，
 * 操作栏只出现在整段结束后的最后一条。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
    isCompressingContext: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> if (message.isRetryableFailure()) {
                ids.add(lastAgentMessageId ?: message.id)
                lastAgentMessageId = null
            }
            is SystemNoticeMessageUi -> if (message.code.isRetryableFailure()) {
                ids.add(message.id)
                lastAgentMessageId = null
            }
            else -> Unit
        }
    }
    if (!isStreaming && !isCompressingContext) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

/** One traversal for all final bubbles, rather than one history scan per answer. */
internal fun visibleTurnSpeechPrefaces(
    messages: List<AgentChatMessageUi>,
    finalIds: Set<String>,
    hiddenIds: Set<String> = emptySet(),
): Map<String, String> {
    if (finalIds.isEmpty()) return emptyMap()
    val result = HashMap<String, String>(finalIds.size)
    val parts = ArrayList<String>()
    for (message in messages) {
        if (message.id in finalIds) result[message.id] = parts.joinToString("\n\n")
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) parts.clear()
            // 被折叠隐藏的中间回复界面上看不到，朗读也不该带上。
            is AgentMessageUi -> if (message.id !in hiddenIds) {
                message.content.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            }
            else -> Unit
        }
    }
    return result
}

/** Visible assistant bubbles in the same turn, excluding collapsed thinking. */
internal fun visibleTurnSpeechPreface(
    messages: List<AgentChatMessageUi>,
    finalId: String,
): String {
    val parts = ArrayList<String>()
    for (message in messages) {
        if (message.id == finalId) break
        when (message) {
            is UserMessageUi -> if (!message.isSteerSupplement()) parts.clear()
            is AgentMessageUi -> message.content.trim().takeIf { it.isNotBlank() }?.let(parts::add)
            else -> Unit
        }
    }
    return parts.joinToString("\n\n")
}


@Composable
private fun AgentChatBottomBar(
    input: String,
    draftField: androidx.compose.foundation.text.input.TextFieldState? = null,
    modelPickerState: AgentModelPickerUiState,
    history: List<AgentModelClient.ConversationMessage>,
    billedContextTokens: Int? = null,
    projectedContextTokens: Int? = null,
    billedHistoryTokens: Int? = null,
    requestOverheadTokens: Int = 0,
    measuredContextTokens: Int? = null,
    contextDisplayPolicy: io.github.mangi.eta.ui.model.ContextDisplayPolicy = io.github.mangi.eta.ui.model.ContextDisplayPolicy(),
    billedOverheadTokens: Int? = null,
    activeRunContextWindow: Int? = null,
    autoCompressEnabled: Boolean,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    canContinueDisconnected: Boolean = false,
    isCompressingContext: Boolean = false,
    showMorphLoading: Boolean = false,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    conversationMentions: ConversationMentionInputUi = ConversationMentionInputUi(),
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String) -> Unit = {},
    messageEdit: MessageEditUiState?,
    collaborationConversationId: String? = null,
    assistantId: String = "",
    voiceState: VoiceModeState = VoiceModeState(),
    onStartVoiceMode: (VoiceEntryMode) -> Unit = {},
    onStopVoiceMode: () -> Unit = {},
    onSubmit: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onModelSelected: (String, String) -> Unit,
    onStop: () -> Unit,
    onContinue: () -> Unit = {},
    onAbortPausedRun: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onAttachVideo: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onEditAssistant: (String) -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    /** 长按「助手与模型」按钮：进入设置页的子代理配置页（预设组列表 / 预设详情）。 */
    onOpenSubAgentSettings: () -> Unit = {},
    onAssistantSelected: (String) -> Unit = {},
    gptSpeedMode: io.github.mangi.eta.data.model.GptSpeedMode = io.github.mangi.eta.data.model.GptSpeedMode.NORMAL,
    onCycleGptSpeedMode: () -> Unit = {},
    horizontalPadding: Dp = 0.dp,
) {
    val drawerBlocksIme = LocalConversationDrawerBlocksIme.current
    // 底部渐变板取页面背景同色：官方 surfaceContainer（壳层 Scaffold 同色）。
    val scrimColor = MaterialTheme.colorScheme.surfaceContainer
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 输入工具栏高度：渐变板终点的锚定输入。普通 int 数组 + 布局期回调（与
    // AgentChatInputBar 的 inputContainerTopPx 同款），避免键盘动画逐帧重组。
    val inputToolbarHeightPx = remember { intArrayOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (drawerBlocksIme) Modifier else Modifier.imePadding())
            .drawBehind {
                // 底部渐变背景板：颜色与页面背景一致。
                // 终点锚在输入胶囊「顶盖圆弧圆心」的 y（胶囊顶边 + 外圆角 36dp）：单行时胶囊
                // 就是整圆胶囊，圆心即按钮中线；多行长高后顶边上移，终点随之整体上移。
                // 渐变条总高与底栏按钮同高（40dp），从圆心向上完全透明——消息从那里开始淡出、
                // 到圆心处完全消失；圆心以下（按钮下半段 + 底部留白 + 导航栏）保持完全不透明，
                // 输入栏附近不会露出消息残影。
                val toolbarHeightPx = inputToolbarHeightPx[0]
                    .takeIf { it > 0 }?.toFloat() ?: ChatInputToolbarDefaultHeight.toPx()
                val arcCenterY = size.height -
                    (navigationBarBottom + ChatInputBarBottomGap).toPx() -
                    toolbarHeightPx +
                    ChatInputScrimAnchorRadius.toPx()
                val stripHeight = ChatInputScrimHeight.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(scrimColor.copy(alpha = 0f), scrimColor),
                        startY = arcCenterY - stripHeight,
                        endY = arcCenterY,
                    ),
                    topLeft = Offset(0f, arcCenterY - stripHeight),
                    size = Size(size.width, stripHeight),
                )
                drawRect(
                    color = scrimColor,
                    topLeft = Offset(0f, arcCenterY),
                    size = Size(size.width, size.height - arcCenterY),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                // 屏幕边缘留白是 16dp；宽屏下再补内容列留白，输入栏与消息列同宽。
                .padding(
                    start = 16.dp + horizontalPadding,
                    end = 16.dp + horizontalPadding,
                    bottom = 12.dp,
                ),
        ) {
            AgentChatInputBar(
                onToolbarHeightPx = { inputToolbarHeightPx[0] = it },
                collaborationConversationId = collaborationConversationId,
                input = input,
                draftField = draftField,
                modelPickerState = modelPickerState,
                history = history,
                billedContextTokens = billedContextTokens,
                projectedContextTokens = projectedContextTokens,
                billedHistoryTokens = billedHistoryTokens,
                requestOverheadTokens = requestOverheadTokens,
                measuredContextTokens = measuredContextTokens,
                contextDisplayPolicy = contextDisplayPolicy,
                billedOverheadTokens = billedOverheadTokens,
                activeRunContextWindow = activeRunContextWindow,
                autoCompressEnabled = autoCompressEnabled,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                isPaused = isPaused,
                canContinueDisconnected = canContinueDisconnected,
                isCompressingContext = isCompressingContext,
                showMorphLoading = showMorphLoading,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
            conversationMentions = conversationMentions,
                queuedMessages = queuedMessages,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                isEditingMessage = messageEdit != null,
                assistantId = assistantId,
                voiceState = voiceState,
                onStartVoiceMode = onStartVoiceMode,
                onStopVoiceMode = onStopVoiceMode,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                onSubmit = onSubmit,
                onReasoningEffortChange = onReasoningEffortChange,
                onModelSelected = onModelSelected,
                onStop = onStop,
                onContinue = onContinue,
                onAbortPausedRun = onAbortPausedRun,
                onAttachImage = onAttachImage,
                onAttachVideo = onAttachVideo,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                onEditAssistant = onEditAssistant,
                onOpenModelProviders = onOpenModelProviders,
                onOpenAssistants = onOpenAssistants,
                onOpenSubAgentSettings = onOpenSubAgentSettings,
                onAssistantSelected = onAssistantSelected,
                gptSpeedMode = gptSpeedMode,
                onCycleGptSpeedMode = onCycleGptSpeedMode,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal fun shouldShowMorphLoadingIndicator(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean,
    isPaused: Boolean = false,
    isCompressingContext: Boolean = false,
    enabled: Boolean,
    beforeResponseOnly: Boolean,
): Boolean {
    if (!enabled || !isStreaming || isPaused || isCompressingContext) return false
    if (!beforeResponseOnly) return true
    return isWaitingForFirstModelOutput(messages)
}

internal fun isWaitingForFirstModelOutput(messages: List<AgentChatMessageUi>): Boolean {
    val lastUserIndex = messages.indexOfLast { message ->
        (message is UserMessageUi && !message.isSteerSupplement()) ||
            (message is SystemNoticeMessageUi && message.code.isRetryableFailure()) ||
            (message is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi && message.isRetryableFailure())
    }
    if (lastUserIndex < 0) return false
    return messages.asSequence()
        .drop(lastUserIndex + 1)
        .none(::isModelOutputMessage)
}

private fun isModelOutputMessage(message: AgentChatMessageUi): Boolean = when (message) {
    is ThinkingMessageUi -> true
    is ToolActivityMessageUi -> true
    is ToolSummaryMessageUi -> true
    is io.github.mangi.eta.ui.model.ErrorReconnectMessageUi -> true
    is AgentMessageUi -> message.content.isNotBlank()
    else -> false
}

private val ChatBackToBottomButtonSlot = 52.dp

// 底栏渐变条总高：与底栏按钮同高（官方 Small 图标按钮 40dp）。
private val ChatInputScrimHeight = 40.dp

// 渐变条终点锚点半径：输入胶囊外层圆角（与 ComposerOuterCornerRadius 同值 36dp）。
private val ChatInputScrimAnchorRadius = 36.dp

// 工具栏高度未上报（首帧测量前）时的兜底：官方 HorizontalFloatingToolbar ContainerSize。
private val ChatInputToolbarDefaultHeight = 64.dp

// 输入胶囊与导航栏之间的底部留白（渐变板内 Column 的 bottom padding）。
private val ChatInputBarBottomGap = 12.dp

/**
 * 跳转轮次按钮组「中线」距列表底部的距离。滚到底时，最后一轮页脚动作组
 * （复制/朗读/fork…）的中线也落在这个高度，两组按钮因此同高、读作一行并列控件。
 * 构成：输入栏间距 [ConversationComposerGap]（14dp）+ 底部哨兵（1dp）+ 页脚垂直内边距
 * （8dp）+ 页脚按钮半径（16dp）。
 */
private val TurnNavigationCenterAboveBottom = 39.dp

/** 手指停滑后，跳转按钮对再倒数多少毫秒才淡出。 */
private const val TurnNavigationIdleHideMillis = 2_000L

private const val CHAT_CACHE_AHEAD_VIEWPORTS = 1f
private const val CHAT_CACHE_BEHIND_VIEWPORTS = 1f

private const val ChatBottomSentinelKey = "agent-chat-bottom-sentinel"
private const val ChatContextCompressingKey = "agent-chat-context-compressing"

/**
 * 发送后回底：差额通常只有一个气泡高，默认 spring() 刚度很高（1500），几十毫秒就顶到位，
 * 和气泡自己的入场节奏对不上，看起来是页面被硬推了一下。这里换成同档位的软弹簧，仍无过冲。
 */
private val ChatSendSettleSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 700f,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContextCompressingIndicator(waiting: Boolean = false, modelName: String = "", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // M3E LoadingIndicator（morph 变形）：行内 18dp 槽，按 loading 选型表
        // 与朗读按钮、工具行、浏览器预览同一套语言。
        LoadingIndicator(
            modifier = Modifier.size(18.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = if (waiting) stringResource(R.string.compress_conversation_waiting) else
                stringResource(R.string.acb_compressing, modelName.ifBlank { stringResource(R.string.acm_main_agent) }),
            style = chatSecondaryLabelStyle(),
            color = chatMutedLabelColor(),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
    hasLeftBottom: Boolean = false,
): Boolean = when {
    // 手指一开始拖就停跟底；流式长高让 sentinel 离开视口时不能当成用户上滑。
    isUserDragging -> false
    // 只有真正滑离过底部再回来，才重新贴底，避免轻触后被跟底拽回去。
    isAtBottom && (current || hasLeftBottom) -> true
    else -> current
}

internal fun shouldPinConversationToBottom(
    isStreaming: Boolean,
    isScrollable: Boolean,
): Boolean = !(isStreaming && isScrollable)

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored && !isUserDragging

/** 尾部留白负责静态停靠；整宽裁剪只负责输出及布局追平前的防溢出。 */
internal fun shouldClipChatTail(
    isStreaming: Boolean,
    isBottomSettling: Boolean,
    keepBottomAnchored: Boolean,
    isUserScrolling: Boolean,
    isUserDragging: Boolean,
    navigationActive: Boolean,
    routeCovered: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored &&
    !isUserScrolling &&
    !isUserDragging &&
    !navigationActive &&
    !routeCovered

internal fun shouldRequestInitialBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
): Boolean = isStreaming && keepBottomAnchored && !isUserDragging

internal fun shouldSnapConversationToBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    hasItems: Boolean,
    scrollToMessageId: String? = null,
): Boolean = hasItems &&
    keepBottomAnchored &&
    !isUserDragging &&
    !isStreaming &&
    scrollToMessageId == null

internal fun resolveConversationBottomSnap(
    bottomItemIndex: Int,
    lastVisibleIndex: Int?,
    lastVisibleBottom: Int?,
    viewportEnd: Int,
): BottomFollowDecision {
    if (lastVisibleIndex == null || lastVisibleBottom == null || lastVisibleIndex < bottomItemIndex) {
        return BottomFollowDecision(requestIndex = bottomItemIndex)
    }
    return BottomFollowDecision(scrollByPx = lastVisibleBottom - viewportEnd)
}

/**
 * Reposition an idle anchored tail only when the measured rest line changes.
 * Observe LazyColumn's applied padding/viewport, not a new bottomInset paired with
 * stale layoutInfo. Do not restart on enable/content changes: those belong to initial
 * positioning, navigation and the streaming follow controller respectively.
 */
@Composable
internal fun AnchorChatTailOnViewportChange(
    scrollState: LazyListState,
    bottomItemIndex: Int,
    canPosition: () -> Boolean,
) {
    val currentBottomIndex by rememberUpdatedState(bottomItemIndex)
    val currentCanPosition by rememberUpdatedState(canPosition)
    LaunchedEffect(scrollState) {
        var previousRestLine: Int? = null
        snapshotFlow {
            val info = scrollState.layoutInfo
            if (info.totalItemsCount == 0 || info.viewportSize.height == 0) null
            else info.viewportEndOffset - info.afterContentPadding
        }
            .distinctUntilChanged()
            .collectLatest { restLine ->
                if (restLine == null) return@collectLatest
                val changed = previousRestLine != null && previousRestLine != restLine
                previousRestLine = restLine
                if (changed && currentCanPosition()) {
                    snapListToBottom(scrollState, currentBottomIndex) { currentCanPosition() }
                }
            }
    }
}

/**
 * 平滑收拢到列表底部：先按实测布局量出差额，跨项差额用与翻页一致的动画赶路，
 * 可见范围内的像素差额用弹簧逐像素收拢，最后交给 snapListToBottom 精修到精确贴底。
 * 发送消息回底与打开会话的初始定位共用，替代原来的整段瞬跳。
 */
private suspend fun settleListToBottom(
    scrollState: LazyListState,
    bottomItemIndex: Int,
    scrollSpec: AnimationSpec<Float> = spring(),
    canPosition: () -> Boolean = { true },
) {
    if (!canPosition()) return
    val layout = scrollState.layoutInfo
    val lastVisible = layout.visibleItemsInfo.lastOrNull()
    val sentinelIndex = (layout.totalItemsCount - 1).coerceAtLeast(bottomItemIndex)
    // 与精修同一套判定：哨兵不可见或还没量到时按索引赶路，量得到就按像素收拢。
    val decision = resolveConversationBottomSnap(
        bottomItemIndex = sentinelIndex,
        lastVisibleIndex = lastVisible?.index,
        lastVisibleBottom = lastVisible?.let { it.offset + it.size },
        viewportEnd = layout.viewportEndOffset - layout.afterContentPadding,
    )
    when {
        decision.requestIndex != null -> scrollState.animateToConversationTurn(decision.requestIndex)
        decision.scrollByPx != 0 -> scrollState.animateScrollBy(
            decision.scrollByPx.toFloat(),
            animationSpec = scrollSpec,
        )
    }
    if (canPosition()) snapListToBottom(scrollState, bottomItemIndex, canPosition)
}

private suspend fun snapListToBottom(
    scrollState: LazyListState,
    bottomItemIndex: Int,
    canPosition: () -> Boolean = { true },
) {
    repeat(3) {
        if (!canPosition()) return
        val layout = scrollState.layoutInfo
        // 哨兵行按构造恒为最后一项；调用方传入的索引可能来自过期的行表，
        // 用总项数兜底，保证「跳到底部」永远落在真实哨兵上，不会停在半路。
        val sentinelIndex = (layout.totalItemsCount - 1).coerceAtLeast(bottomItemIndex)
        val lastVisible = layout.visibleItemsInfo.lastOrNull()
        val viewportEnd = layout.viewportEndOffset - layout.afterContentPadding
        val decision = resolveConversationBottomSnap(
            bottomItemIndex = sentinelIndex,
            lastVisibleIndex = lastVisible?.index,
            lastVisibleBottom = lastVisible?.let { it.offset + it.size },
            viewportEnd = viewportEnd,
        )
        decision.requestIndex?.let { scrollState.scrollToItem(it) }
        if (decision.scrollByPx != 0) {
            var consumedPx = 0f
            scrollState.scroll { if (canPosition()) consumedPx = scrollBy(decision.scrollByPx.toFloat()) }
            // 欠消费未必是真到边：滚动标志被陈旧地钉住时 scrollBy(+) 会被整段钳成 0，
            // 尾部却仍越在静止线下方。scrollToItem 走 requestScrollToItem 重新定位，
            // 不受滚动钳位影响；只在「想向下滚但被钳住」时兜底，真到边时尾部已贴住静止线。
            if (decision.scrollByPx > 0 && consumedPx < decision.scrollByPx - 0.5f &&
                (scrollState.layoutInfo.measuredTailOverflow() ?: 0) > 1
            ) {
                scrollState.scrollToItem(sentinelIndex)
            }
        }
        if (decision.requestIndex == null && decision.scrollByPx == 0) return
        withFrameNanos { }
    }
}

/** 尾部哨兵超出静止线的像素；哨兵不在可见项中时返回 null（尾部位置未知）。 */
private fun LazyListState.followTailOverflow(): Int? {
    val info = layoutInfo
    // 列表自身在布局边界裁剪；上提超过尾部留白只会在输入框上方露出空白，最多上提到留白为止。
    return info.measuredTailOverflow()?.coerceAtMost(info.afterContentPadding)
}

/** 未经过绘制上限截断的真实布局差额，供实际滚动消化超出缓冲的部分。 */
private fun LazyListLayoutInfo.measuredTailOverflow(): Int? {
    val sentinel = visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
    val last = visibleItemsInfo.lastOrNull()
    val bottom = resolveTailBottomPx(
        sentinelBottom = sentinel?.let { it.offset + it.size },
        lastVisibleIndex = last?.index,
        lastVisibleBottom = last?.let { it.offset + it.size },
        totalItems = totalItemsCount,
    ) ?: return null
    return bottom - (viewportEndOffset - afterContentPadding)
}

private data class TailBreachSample(
    val overPx: Int?,
    val tailBottomPx: Int?,
    val restLinePx: Int,
    val padPx: Int,
    val sentinelVisible: Boolean,
    val lastIndex: Int,
    val totalItems: Int,
    val lifting: Boolean,
    val state: String,
)

/** UserInput 来源的滚动只有在手指按着时才算用户滑动；惯性走的是 SideEffect 来源，不经过这里。 */
internal fun isUserScrollGesture(pointerDown: Boolean): Boolean = pointerDown

/** 聊天列表的跟底跟随快照：拖动状态 / 是否在底部 / 当前是否跟底。 */
private data class ChatListFollowState(
    val dragging: Boolean,
    val atBottom: Boolean,
    val anchored: Boolean,
)

/** 只保留类名和方法名（与主线程消息日志同一套截断规则），跳过本文件与协程、Compose 调度的帧。 */
internal fun compactStack(frames: Array<StackTraceElement>, limit: Int = 14): String =
    frames.asSequence()
        .drop(1)
        .map { "${toggleProbeClassName(it.className)}.${toggleProbeClassName(it.methodName)}" }
        .filterNot { it.startsWith("kotlin.") || it.startsWith("java.") }
        .take(limit)
        .joinToString("<")

/** 尾部画出来的位置越过静止线多少像素（上提之后）；尾部位置未知时为 null。 */
internal fun resolveTailDrawnOverflow(tailBottomPx: Int?, restLinePx: Int, liftPx: Int): Int? =
    tailBottomPx?.let { it - restLinePx - liftPx }

/**
 * 尾部下沿。哨兵是最后一项；它被挤出可视区、但紧挨着它的最后一段内容仍可见时，
 * 用那一段的下沿代替（相差哨兵自身 1dp）。两者都看不到时返回 null。
 */
internal fun resolveTailBottomPx(
    sentinelBottom: Int?,
    lastVisibleIndex: Int?,
    lastVisibleBottom: Int?,
    totalItems: Int,
): Int? = when {
    sentinelBottom != null -> sentinelBottom
    lastVisibleIndex != null && lastVisibleBottom != null && totalItems >= 2 &&
        lastVisibleIndex == totalItems - 2 -> lastVisibleBottom
    else -> null
}

internal data class FollowTailLag(val liftPx: Float, val unknown: Boolean = false) {
    companion object {
        val None = FollowTailLag(0f)
        val Unknown = FollowTailLag(0f, unknown = true)
    }
}

/**
 * 跟底输出时，跟底滚动尚未追上的尾部超出量改为绘制上提，让尾部停在静止线上。
 * 不跟底（用户拖动、浏览历史、输出结束）时不做任何处理；尾部不可见时交给静止线裁剪兜底。
 */
/**
 * 点开一行时，它下面的内容会不会停在屏幕原处。跟底时上提让尾部停在静止线；
 * 内容不满一屏且贴底排列时，列表变高也是往上长。两种情况下展开都该从下沿长出。
 */
internal fun resolveExpansionHoldsBottom(
    following: Boolean,
    arrangedToBottom: Boolean,
    listScrollable: Boolean,
): Boolean = following || (arrangedToBottom && !listScrollable)

// Preserve the measured recovery budget; it is NOT an entrance admission window.
private const val WORK_EXPANSION_RECOVERY_NANOS = 680_000_000L
// 180ms 展开动画加淡入，再留一点给最后一帧布局。
private const val EXPANSION_BOTTOM_SNAP_NANOS = 400_000_000L

internal fun resolveFollowTailLag(following: Boolean, tailOverflowPx: Int?): FollowTailLag = when {
    !following -> FollowTailLag.None
    tailOverflowPx == null -> FollowTailLag.Unknown
    tailOverflowPx <= 0 -> FollowTailLag.None
    else -> FollowTailLag(tailOverflowPx.toFloat())
}

/**
 * 测量暂时缺失时保留上一帧上提，避免卡片底边在输入框附近来回跳。
 *
 * 上提只限制增长：新长出一行时整列不再一帧内上跳一个行高，而是按 [maxRisePx]
 * （由 [createTailLiftMotion] 给出的缓动增量）从静止线下方滑入。
 * 回落不限速——滚动追上后上提立即让位，
 * 「滚动量 + 上提量」始终不超过真实溢出，尾部不会被抬到静止线上方。
 */
internal fun nextHeldTailLift(
    shouldLift: Boolean,
    overflowPx: Int?,
    heldPx: Int,
    pointerDown: Boolean = false,
    maxRisePx: Int = Int.MAX_VALUE,
): Int = when {
    !shouldLift -> 0
    pointerDown -> heldPx.coerceAtLeast(0)
    overflowPx == null -> heldPx.coerceAtLeast(0)
    else -> {
        val overflow = overflowPx.coerceAtLeast(0).toLong()
        val held = heldPx.coerceAtLeast(0).toLong()
        val rise = maxRisePx.coerceAtLeast(1).toLong()
        min(held + rise, overflow).toInt()
    }
}

private fun LazyListState.isConversationAtBottom(): Boolean {
    val info = layoutInfo
    val sentinel = info.visibleItemsInfo.firstOrNull { it.key == ChatBottomSentinelKey }
    return if (sentinel == null) {
        !canScrollForward
    } else {
        val viewportEnd = info.viewportEndOffset - info.afterContentPadding
        sentinel.offset + sentinel.size <= viewportEnd + 8
    }
}

@Composable
private fun EmptyChatState(
    showShapes: Boolean,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 谁能用：按当前设备能力过滤建议池，没授权的条目直接不出现。
    val device = rememberDeviceCapabilities()
    val terminalToolsEnabled = remember { Prefs.isEnabled(Prefs.Keys.AGENT_TERMINAL_TOOLS) }
    val screenToolsEnabled = remember { Prefs.isEnabled(Prefs.Keys.AGENT_SCREEN_TOOLS) }
    // 记忆开关的初值取默认值：绝大多数设备是开的，抽签结果不会因此重抽一次。
    val memoryEnabled by remember { AgentMemoryRepository.enabledFlow() }
        .collectAsState(initial = true)
    // 每次进空态换一批：seed 在这里取一次，重组不重抽。
    val suggestionSeed = remember { Random.nextInt() }
    val picked = remember(
        suggestionSeed,
        device.tools,
        device.root.isGranted,
        terminalToolsEnabled,
        screenToolsEnabled,
        memoryEnabled,
    ) {
        selectChatSuggestions(
            limit = 4,
            random = Random(suggestionSeed),
            isAvailable = { spec ->
                spec.requirement.isSatisfied(device, terminalToolsEnabled, screenToolsEnabled, memoryEnabled)
            },
        )
    }
    val suggestions = picked
        .map { spec ->
            SuggestionItem(
                title = stringResource(spec.titleRes),
                icon = spec.icon,
                prompt = stringResource(spec.promptRes),
            )
        }
        // 标题最长的那条交给最大的那块形状，文字不会被挤掉。
        .sortedByDescending { it.title.length }

    // 键盘起落时建议形状要收走/放回。可见性不能看「IME 是否已有高度」（rememberImeVisible
    // 的 inset > 0 要等键盘完全落定才翻转，形状会错过整段落键动画、最后瞬间弹出），要看动画
    // 目标态：目标在起落的第一帧就跳变，形状在整个键盘动画期间都留在组合树里，跟着 inset
    // 曲线逐帧收放。目标值一整个动画只变一次，不会带来逐帧重组。
    val density = LocalDensity.current
    val showSuggestions = WindowInsets.imeAnimationTarget.getBottom(density) == 0

    // 收放进度：0 = 全部放回，1 = 全部收走。逐帧取 IME inset 与本次动画幅度（起止两态中
    // 较大者）之比——键盘升起多少、形状就收走多少，同一拍同一条曲线。标题在整组里的位移
    // 因此只剩 inset 这一条连续曲线，既没有第二段弹簧叠加出方向回折，也不存在退场内容被
    // 提前移除时的占位残段（旧实现：快速淡出播完就拆内容，慢速占位弹簧才收到一半，标题
    // 被猛拽一下）。inset 只在下面的 layout / graphicsLayer lambda 里读（组合期不读），
    // 逐帧变化只落到这一层测量与重绘，空态子树不会被拖进逐帧重组。
    val ime = WindowInsets.ime
    val imeSource = WindowInsets.imeAnimationSource
    val imeTarget = WindowInsets.imeAnimationTarget
    fun suggestionCollapse(): Float {
        val extent = maxOf(imeSource.getBottom(density), imeTarget.getBottom(density)).toFloat()
        if (extent <= 0f) return 0f
        return (ime.getBottom(density) / extent).coerceIn(0f, 1f)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.ui_how_can_i_help_you_e75391),
                style = chatEmptyStateHeadlineStyle(),
                color = chatBodyLabelColor(),
            )

            // 窗口矮（分屏 / 横屏小窗）放不下整组形状时整组不组合，只留欢迎语单独居中。
            if (showShapes) {
                Spacer(modifier = Modifier.height(30.dp))

                AnimatedVisibility(
                    visible = showSuggestions,
                    // 进出场只管组合生命周期：透明度与缩放由下面的 graphicsLayer 按 inset 曲线
                    // 逐帧接管（落键进场的组合首帧没有进场动画，全靠这里的 alpha 从 0 长出来）。
                    // 退场弹簧必须盖过整个键盘升起动画（约 300ms）：占位高度随 inset 在键盘到位
                    // 时已归零，内容被移除时占位是 0，标题不会再被猛拽。
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.defaultSpatialSpec()),
                ) {
                    Box(
                        modifier = Modifier
                            // 宽屏下整组不再铺满窗口：形状尺寸按容器宽度的比例算，
                            // 不封顶的话平板上会放出四块巨大的色块。
                            .widthIn(max = MaxChatSuggestionWidth)
                            .fillMaxWidth()
                            // 形状整组按 (1 - 进度) 等比缩放（顶边为原点），缩后高度与占位高度
                            // 逐帧相等：内容永远落在占位边界内，超出部分不会再画到底栏渐变板
                            // 底下被不透明段盖出硬裁边。
                            .graphicsLayer {
                                val shown = 1f - suggestionCollapse()
                                alpha = shown
                                scaleX = shown
                                scaleY = shown
                                transformOrigin = TransformOrigin(0.5f, 0f)
                            }
                            // 占位高度交给同一条 inset 曲线逐帧收放：形状本体始终按完整尺寸测量，
                            // 不被压扁；占位收起时整组随标题一起上移，放回时一起下移。
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val slotHeight = (placeable.height * (1f - suggestionCollapse()))
                                    .toInt()
                                    .coerceIn(0, placeable.height)
                                layout(placeable.width, slotHeight) { placeable.placeRelative(0, 0) }
                            },
                    ) {
                        ExpressiveSuggestionBlobs(
                            suggestions = suggestions,
                            onSuggestionClick = onSuggestionClick,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 空态四块建议形状所需的最小窗口高度：形状整组是正方形容器（高 = min(容器宽 − 32dp 左右
 * 留白, [MaxChatSuggestionWidth])），另需欢迎语一行（headlineSmall，32dp）+ 30dp 间隔 +
 * 56dp 底部留白，再留约 32dp 呼吸空间。窗口低于此值（分屏 / 横屏矮窗）时整组形状
 * 不组合，只留欢迎语居中。
 */
internal fun suggestionShapesMinHeight(containerWidth: Dp): Dp =
    (containerWidth - 32.dp).coerceAtMost(MaxChatSuggestionWidth) + 150.dp

/**
 * 建议词数量与形状数量不一致时的常规网格卡片：官方 Surface(onClick) 承担容器、
 * 形状、描边与按压涟漪。
 */
@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = chatCardContainerColor(),
        border = BorderStroke(0.5.dp, chatCardBorderColor()),
    ) {
        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 12.dp)) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(EtaDimens.StatusIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                text = item.title,
                style = chatSecondaryLabelStyle(),
                color = chatBodyLabelColor(),
                maxLines = 1,
            )
        }
    }
}

private data class SuggestionItem(
    val title: String,
    val icon: ImageVector,
    val prompt: String,
)

/**
 * 空状态「球」：形状 + 在 2×2 网格里占的格子，位置是容器宽度的比例。
 * 格子边长固定（[BLOB_CELL_SIZE]），形状按真实面积归一后在各自格子里居中。
 */
private class ExpressiveBlobSpec(
    val shape: RoundedPolygon,
    val cellLeft: Float,
    val cellTop: Float,
)

/** 每块色块所在格子的边长（容器宽度的比例）。 */
private const val BLOB_CELL_SIZE = 0.44f

/** 格子外边距与格子之间的间隔（容器宽度的比例）；2 × 格子 + 3 × 间隔 = 1，整组正好铺满方形容器。 */
private const val BLOB_MARGIN = 0.04f

/** 每条贝塞尔边采样成的折线段数；24 段时面积与解析值的差距远小于千分之一，肉眼看不出来。 */
private const val BLOB_CURVE_SEGMENTS = 24

/**
 * 形状轮廓的几何量：自身坐标下的真实面积，以及外框的较长边。
 *
 * 外框一样大并不等于看上去一样大：圆内接在外框里只占 π/4 ≈ 78.5%、四个角是空的，
 * 而 Cookie/Pill 这类圆角形状更接近铺满；而且官方 [MaterialShapes] 都是按最长边归一化到
 * 1×1 的，Pill / Cookie6Sided 的外框本身就不是正方形。所以「视觉等大」只能按真实面积归一。
 *
 * 关键前提：`Shape.createOutline` 先按 size 缩放（这里是正方形外框，两个方向同一个系数），
 * 再把轮廓中心平移到外框中心，因此**渲染面积 = 自身面积 × size²**，与形状的外框比例无关。
 *
 * 形状不是矩形，面积靠把贝塞尔轮廓采样成折线后用鞋带公式算。
 * [extent] 用于保证缩放后不越出自己的格子：渲染出的外框最长边 = extent × size。
 */
private class BlobGeometry(val area: Float, val extent: Float)

private fun RoundedPolygon.geometry(): BlobGeometry {
    val bounds = calculateBounds()
    val points = sampledOutline()
    var doubleArea = 0f
    for (index in points.indices) {
        val point = points[index]
        val next = points[(index + 1) % points.size]
        doubleArea += point.x * next.y - next.x * point.y
    }
    return BlobGeometry(
        area = abs(doubleArea) / 2f,
        extent = max(bounds[2] - bounds[0], bounds[3] - bounds[1]),
    )
}

/**
 * 把形状的贝塞尔轮廓采样成折线（形状空间坐标）。每条边只取 [0, 1) 的 t，
 * 终点留给下一条边的起点，折线自然首尾相接。
 */
private fun RoundedPolygon.sampledOutline(): List<Offset> {
    val points = ArrayList<Offset>(cubics.size * BLOB_CURVE_SEGMENTS)
    cubics.forEach { cubic ->
        repeat(BLOB_CURVE_SEGMENTS) { step ->
            val t = step.toFloat() / BLOB_CURVE_SEGMENTS
            val inverse = 1f - t
            val weight0 = inverse * inverse * inverse
            val weight1 = 3f * inverse * inverse * t
            val weight2 = 3f * inverse * t * t
            val weight3 = t * t * t
            points += Offset(
                weight0 * cubic.anchor0X + weight1 * cubic.control0X +
                    weight2 * cubic.control1X + weight3 * cubic.anchor1X,
                weight0 * cubic.anchor0Y + weight1 * cubic.control0Y +
                    weight2 * cubic.control1Y + weight3 * cubic.anchor1Y,
            )
        }
    }
    return points
}

/** 建议池的能力闸门：把设备状态收敛成「这条现在能不能跑」。 */
private fun ChatSuggestionRequirement.isSatisfied(
    device: DeviceCapabilitiesUi,
    terminalToolsEnabled: Boolean,
    screenToolsEnabled: Boolean,
    memoryEnabled: Boolean,
): Boolean = when (this) {
    ChatSuggestionRequirement.NONE -> true
    ChatSuggestionRequirement.ACCESSIBILITY -> device.accessibilityAvailable && screenToolsEnabled
    ChatSuggestionRequirement.NOTIFICATIONS -> device.tools.notificationsAllowed
    ChatSuggestionRequirement.USAGE -> device.tools.usageAllowed
    ChatSuggestionRequirement.LOCATION -> device.tools.locationAllowed
    ChatSuggestionRequirement.ROOT -> device.root.isGranted
    ChatSuggestionRequirement.TERMINAL -> terminalToolsEnabled
    ChatSuggestionRequirement.MEMORY -> memoryEnabled
}

/**
 * M3 Expressive 空状态建议：四块抽象形状的色块排成规整的 2×2 网格。
 *
 * 形状取官方 [MaterialShapes] 的 Cookie6Sided / Pill / Circle / Cookie4Sided，统一用
 * tertiaryContainer 实心色块、不描边；每块都落在正方形外框里，所以形状保持正比例，
 * 不会被拉伸或压扁。四块不旋转，格子外边距与格子间隔全部相等（见 BLOB_MARGIN）。
 *
 * 四块的「外框」一样大，但真实面积不同（圆内接只占 78.5%，且各自归一化后的外框比例也不同），
 * 所以尺寸按真实面积归一：算出每块的面积，按 √(目标面积 / 自身面积) 缩放，使四块面积相等；
 * 目标面积取「填充率最低」的那一块可以占的面积，于是没有一块会超出自己的格子。
 * 形状在格子里居中，整组按数值居中放在容器里。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveSuggestionBlobs(
    suggestions: List<SuggestionItem>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val specs = listOf(
        // 形状 / 格子左上角，位置均为容器宽度的比例；
        // 按调用方约定，标题最长的建议放在最前面，于是最长的那条落在左上角。
        ExpressiveBlobSpec(MaterialShapes.Cookie6Sided, cellLeft = BLOB_MARGIN, cellTop = BLOB_MARGIN),
        ExpressiveBlobSpec(MaterialShapes.Pill, cellLeft = BLOB_MARGIN * 2 + BLOB_CELL_SIZE, cellTop = BLOB_MARGIN),
        ExpressiveBlobSpec(MaterialShapes.Circle, cellLeft = BLOB_MARGIN, cellTop = BLOB_MARGIN * 2 + BLOB_CELL_SIZE),
        ExpressiveBlobSpec(MaterialShapes.Cookie4Sided, cellLeft = BLOB_MARGIN * 2 + BLOB_CELL_SIZE, cellTop = BLOB_MARGIN * 2 + BLOB_CELL_SIZE),
    )
    if (suggestions.size != specs.size) {
        // 建议词数量变化时退回常规网格，避免形状与文案错位。
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowItems.forEach { item ->
                        SuggestionCard(
                            item = item,
                            onClick = { onSuggestionClick(item.prompt) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val unit = maxWidth
        // 面积归一：让四块渲染出来的真实面积相等。
        // 目标面积取「有效填充率」最低的那块（area / extent²，即它在格子里最多能占多少面积），
        // 于是那块恰好撑满格子，其余三块按 √(目标面积 / 自身面积) 缩一点，谁都不会越出格子。
        val sizes = remember {
            val geometries = specs.map { it.shape.geometry() }
            val targetArea = BLOB_CELL_SIZE * BLOB_CELL_SIZE *
                geometries.minOf { it.area / (it.extent * it.extent) }
            geometries.map { geometry -> sqrt(targetArea / geometry.area) }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(unit),
        ) {
            specs.forEachIndexed { index, spec ->
                val item = suggestions[index]
                val size = sizes[index]
                ExpressiveSuggestionBlob(
                    item = item,
                    shape = spec.shape,
                    onClick = { onSuggestionClick(item.prompt) },
                    modifier = Modifier
                        .offset(
                            x = unit * (spec.cellLeft + (BLOB_CELL_SIZE - size) / 2f),
                            y = unit * (spec.cellTop + (BLOB_CELL_SIZE - size) / 2f),
                        )
                        .size(unit * size),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveSuggestionBlob(
    item: SuggestionItem,
    shape: RoundedPolygon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape.toShape(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                // 收进形状的内接正方形（约 58%），轮廓因此裁不到文字。
                modifier = Modifier.fillMaxWidth(0.58f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.title,
                    style = chatSecondaryLabelStyle(),
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

internal fun shouldStopOrphanSpeechPlayback(
    owner: String?,
    messageEditActive: Boolean,
    visibleCompletedAgentIds: Set<String>,
): Boolean {
    if (owner.isNullOrBlank()) return false
    // 试听、语音模式和 Agent 朗读工具都不绑定某条回复，不能按“回复不在可见列表里”收掉。
    // 自动朗读同样不绑定某条可见行：它读的是整条回复，流式中就可能开始。
    if (owner == "tts-preview" || owner == io.github.mangi.eta.agent.voice.tts.AGENT_SPEECH_OWNER ||
        owner.startsWith(VOICE_MODE_SPEECH_OWNER_PREFIX) ||
        owner.startsWith(ReplyReadAloudController.OWNER_PREFIX)
    ) return false
    return messageEditActive || owner !in visibleCompletedAgentIds
}

/** 最后一条消息静止时与输入框上沿的间距；跟底输出时正文也被裁在这条线上。 */
private val ConversationComposerGap = 14.dp

internal fun composerRestLinePx(heightPx: Float, occlusionPx: Float): Float =
    (heightPx - occlusionPx).coerceAtLeast(0f)

/** 把列表裁在输入框上沿加间隙处，子级 graphicsLayer 也遵守这条边界。 */
internal class ComposerRestClip(private val occlusion: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val bottom = with(density) { composerRestLinePx(size.height, occlusion.toPx()) }
        return Outline.Rectangle(Rect(0f, 0f, size.width, bottom))
    }

    override fun equals(other: Any?): Boolean = other is ComposerRestClip && other.occlusion == occlusion

    override fun hashCode(): Int = occlusion.hashCode()
}

