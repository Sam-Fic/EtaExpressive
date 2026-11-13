package io.github.mangi.eta.ui.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import io.github.mangi.eta.ui.components.StreamingMarkdownCache
import io.github.mangi.eta.ui.components.LocalStreamingMarkdownStates
import io.github.mangi.eta.ui.components.remoteSessionTitle
import io.github.mangi.eta.ui.components.LocalChatRouteCovered
import io.github.mangi.eta.ui.components.LocalChatTransitionActive
import io.github.mangi.eta.ui.components.LocalChatUiActive
import io.github.mangi.eta.ui.components.LocalAssistantDictationConsumed
import io.github.mangi.eta.ui.components.LocalAssistantDictationRequest
import io.github.mangi.eta.ui.components.LocalAssistantFocusConsumed
import io.github.mangi.eta.ui.components.LocalAssistantFocusRequest
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.remote.ChatTarget
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceLocationProvider
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.AppUpdateCoordinator
import io.github.mangi.eta.data.repository.AppUpdateRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.AgentTaskPreferenceScreen
import io.github.mangi.eta.ui.AgentTaskSurfacePrompt
import io.github.mangi.eta.ui.AppearanceSettingsScreen
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaMotion
import io.github.mangi.eta.ui.adaptive.EtaSegmentedCheckboxRow
import io.github.mangi.eta.ui.adaptive.LocalEtaDetailPaneDepth
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.components.LocalOpenVoiceSettings
import io.github.mangi.eta.ui.components.LocalShellTopBarInset
import io.github.mangi.eta.ui.screens.virtualdisplay.VirtualDisplayScreen
import io.github.mangi.eta.ui.HapticsSettingsScreen
import io.github.mangi.eta.ui.ErrorReconnectSettingsScreen
import io.github.mangi.eta.ui.LiveActivitySettingsScreen
import io.github.mangi.eta.ui.ContextCompressionSettingsScreen
import io.github.mangi.eta.ui.DecisionModelSettingsScreen
import io.github.mangi.eta.ui.SettingsScreen
import io.github.mangi.eta.ui.SettingsDetailEmptyPane
import io.github.mangi.eta.ui.SettingsDetailFirstRoute
import io.github.mangi.eta.ui.SettingsDetailInstantTransition
import io.github.mangi.eta.ui.SettingsDetailPaneEffects
import io.github.mangi.eta.ui.SettingsDetailPaneTransition
import io.github.mangi.eta.ui.SettingsWideHost
import io.github.mangi.eta.ui.settingsAnchorKeyForRoute
import io.github.mangi.eta.ui.layout.rememberIsWideScreen
import io.github.mangi.eta.ui.components.AppUpdateDialog
import io.github.mangi.eta.ui.components.AgentPendingChildStopDialog
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolGroupSwitch
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.allConversations
import io.github.mangi.eta.ui.navigation.AgentNavigator
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.isKnownAppRoute
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.pages.providers.ProviderAuthMethodScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderSearchScreen
import io.github.mangi.eta.ui.pages.providers.ProviderModelEditScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantEditScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantSearchScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantsScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserScreen
import io.github.mangi.eta.ui.screens.chat.ConversationTokenUsageScreen
import io.github.mangi.eta.ui.screens.chat.ManageChatsScreen
import io.github.mangi.eta.ui.screens.chat.ManageFoldersScreen
import io.github.mangi.eta.ui.screens.chat.TrashScreen
import io.github.mangi.eta.ui.screens.chat.TrashSearchScreen
import io.github.mangi.eta.ui.screens.chat.MessageSearchScreen
import io.github.mangi.eta.ui.screens.chat.SubAgentStatusScreen
import io.github.mangi.eta.ui.screens.enhance.SystemEnhanceScreen
import io.github.mangi.eta.ui.screens.home.AgentHomeScreen
import io.github.mangi.eta.ui.screens.mcp.McpServerDetailScreen
import io.github.mangi.eta.ui.screens.mcp.McpServerAddScreen
import io.github.mangi.eta.ui.screens.mcp.McpServersScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryOverviewScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryScreen
import io.github.mangi.eta.ui.screens.permissions.PermissionHealthScreen
import io.github.mangi.eta.ui.screens.settings.MobileActionsScreen
import io.github.mangi.eta.ui.screens.settings.MobileActionsSettingsScreen
import io.github.mangi.eta.ui.screens.tools.ToolRecipeEditScreen
import io.github.mangi.eta.ui.screens.tools.ToolRecipesScreen
import io.github.mangi.eta.ui.screens.skills.AgentSkillsScreen
import io.github.mangi.eta.ui.screens.stats.UsageStatsScreen
import io.github.mangi.eta.ui.screens.terminal.DaemonTasksScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxEnvironmentScreen
import io.github.mangi.eta.ui.screens.terminal.LinuxFilesScreen
import io.github.mangi.eta.ui.screens.terminal.SharedFolderPickerScreen
import io.github.mangi.eta.ui.screens.terminal.SharedFoldersScreen
import io.github.mangi.eta.ui.screens.terminal.TerminalEntryScreen
import io.github.mangi.eta.ui.screens.terminal.WorkspaceScreen
import io.github.mangi.eta.ui.screens.tools.AgentToolsScreen
import io.github.mangi.eta.ui.screens.tools.WebToolsSettingsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import top.yukonga.miuix.kmp.nav.core.LocalNavTransitionScope
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
fun AgentAppRoot(
    /** 完成横幅点击直达的会话 id；路由落地后由 [onLiveTargetConsumed] 复位。 */
    liveTargetConversationId: String? = null,
    /** 助手浮窗口述「去配置」请求令牌；语音设置页落地后由 [onVoiceSettingsRequestConsumed] 复位。 */
    openVoiceSettingsRequest: Int = 0,
    inboundShareUris: List<String> = emptyList(),
    onInboundShareConsumed: () -> Unit = {},
    onLiveTargetConsumed: () -> Unit = {},
    onVoiceSettingsRequestConsumed: () -> Unit = {},
    /** 边缘横移返回开关；关闭时所有路由的 swipeDismiss 方向为 [NavSwipeDirection.None]。 */
    swipeBackEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    // miuix 返回栈：SnapshotStateList<NavKey>，由 rememberSaveable 做多态序列化持久化。
    // 起点固定 Home；所有入栈操作只接受 AppRoute（见 AgentNavigator）。
    val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
    val navigator = remember(backStack) { AgentNavigator(backStack) }
    // 宽屏设置页右栏的独立返回栈：栈底就是「第一个子设置页」，进设置页即显示，
    // 二级/三级设置页只压这里，主栈始终停在 AppRoute.Settings，左栏设置主页因此
    // 不会重建、不会变化。两个 rememberNavBackStack 按组合位置各自 rememberSaveable，
    // 互不干扰（miuix NavBackStack.kt；NavDisplay.kt:847-856 处理嵌套 display 的返回仲裁）。
    val settingsDetailStack = rememberNavBackStack<AppRoute>(SettingsDetailFirstRoute)
    val wideScreenActive = rememberIsWideScreen()
    // 组合期直接读 SnapshotStateList 的可观察快照，用它派生当前路由与「是否还有上一级」。
    val currentRoute by remember { derivedStateOf { backStack.lastOrNull() as? AppRoute } }
    val canPopRoute by remember { derivedStateOf { backStack.size > 1 } }
    // 宽屏设置态：设置子页的 push/pop 全部改走右栏栈（见 pushRoute / popRoute）。
    val settingsWideActive = wideScreenActive && currentRoute == AppRoute.Settings
    // 左栏点选二级页 = 整栈替换 + 官方 NavTransitions.None 瞬时换页；右栏自己 push 的
    // 三级/四级页要带动画。两者共用一个 NavDisplay，故用这个标记切换全局 transition。
    // 只有「栈内只剩一个二级页」时会是 true，此时没有任何可播放转场的层。
    var detailInstantSwap by remember { mutableStateOf(false) }
    // 右栏 display 的重建代次：只在「瞬时换页」（进设置页 / 左栏换项 / 搜索层压退）时递增。
    // 三级页 push/pop 不动它，右栏才能保留自己的转场动画。
    var detailInstantEpoch by remember { mutableStateOf(0) }
    // 每次重新进入宽屏设置页（含从别的页返回、旋转回宽屏）都回到第一个子设置页，
    // 不把上一次浏览到的三级页带回来。主栈不变，左栏自然也不会重建。
    LaunchedEffect(currentRoute, wideScreenActive) {
        if (currentRoute == AppRoute.Settings && wideScreenActive) {
            Snapshot.withMutableSnapshot {
                detailInstantSwap = true
                detailInstantEpoch++
                settingsDetailStack.clear()
                settingsDetailStack.add(SettingsDetailFirstRoute)
            }
        }
    }
    // 容器兜底：miuix 0.9.4 没有 entryProvider fallback（未登记类型会在 build() 直接抛异常），
    // 所以渲染前先找出不可渲染的条目，命中时改渲染最小错误页（见下方 NavDisplay 处）。
    // 两个栈都要筛，右栏栈里出现未登记类型同样会在 build() 崩。
    val unrenderableRoute by remember {
        derivedStateOf {
            backStack.firstOrNull { !isKnownAppRoute(it) }
                ?: settingsDetailStack.firstOrNull { !isKnownAppRoute(it) }
        }
    }
    // 转场活跃信号（LocalChatTransitionActive 的唯一来源）。写入点在 RoutedShell 里，
    // 由 miuix 的 LocalNavTransitionScope.isRunning 驱动；这里先声明，供其捕获。
    val navigationActive = remember { mutableStateOf(false) }
    // miuix 默认转场效果：只把圆角裁切半径跟随系统屏幕圆角，时长/曲线用 NavTransitions.MiuixDefault。
    val navSystemCornerRadius = rememberNavSystemCornerRadius()
    val navEffects = remember(navSystemCornerRadius) {
        NavDisplayEffects(cornerClipRadius = navSystemCornerRadius)
    }
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    // 常亮只在「对话页可见 + 正在流式回复 + Runtime 还有活着的 run」三者同时成立时挂上；
    // 设置页、空闲会话与纯后台任务不额外保持常亮，GUI 浮层由 Runtime 服务自己维护。
    val runtimeActive by AgentRuntimeService.keepScreenOn.collectAsState()
    // 系统助手在主应用前台唤醒：直接应用内响应——落到会话输入区（其它路由先切回主页），
    // 听写/聚焦由聊天输入区按令牌分派。令牌单调递增不复位；composer
    // 实例随切会话整树销毁重建（实例内记账一并丢失），已消费水位供重建实例播种，
    // 旧令牌不再被重放（否则每次切会话都会误开听写/重弹键盘）。
    val inAppDictationRequest by MainActivity.inAppDictationRequest.collectAsState()
    val inAppFocusRequest by MainActivity.inAppFocusRequest.collectAsState()
    val inAppDictationConsumed by MainActivity.consumedInAppDictationRequest.collectAsState()
    val inAppFocusConsumed by MainActivity.consumedInAppFocusRequest.collectAsState()
    /**
     * 外部入口（系统助手应用内唤醒 / 完成横幅 / 搜索跳转）指名了具体会话时，主页那一次
     * 「进主页即落调度台」的复位要让位，否则刚被打开的会话会被立刻换成调度台。
     *
     * 存的是被指名的会话 id：主页条目下次组合时若该会话仍是选中项就只消费不复位，
     * 否则照常落调度台（一次性，用完即清）。
     */
    var homeEntryPinnedConversationId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(inAppDictationRequest, inAppFocusRequest) {
        if (inAppDictationRequest == 0 && inAppFocusRequest == 0) return@LaunchedEffect
        if (currentRoute !is AppRoute.Home) {
            homeEntryPinnedConversationId = agentState.conversationPaneState.selectedConversationId
            navigator.replace(AppRoute.Home)
        }
    }
    val keepScreenOnView = LocalView.current
    val keepScreenOn = currentRoute is AppRoute.Home &&
        agentState.homeState.isStreaming && runtimeActive
    DisposableEffect(keepScreenOnView, keepScreenOn) {
        keepScreenOnView.keepScreenOn = keepScreenOn
        onDispose { keepScreenOnView.keepScreenOn = false }
    }
    val subAgentOwner = agentState.subAgentConfigOwner
    val subAgentEditor = remember(agentState, subAgentOwner) { agentState.subAgentEditor(subAgentOwner) }
    fun stopMainReply() {
        val captured = agentState.captureStopRequest() ?: return
        if (!agentState.stopMainReply(captured)) {
            Toast.makeText(context, context.getString(R.string.appst_stop_selection_changed), Toast.LENGTH_SHORT).show()
        }
    }
    val usageConversationId = agentState.conversationPaneState.selectedConversationId
    val recordedUsageState by remember(usageConversationId) {
        io.github.mangi.eta.data.repository.UsageStatsRepository.conversationUsageFlow(usageConversationId)
            .map { usageConversationId to it }
    }.collectAsState(initial = null)
    val recordedUsage = recordedUsageState?.takeIf { it.first == usageConversationId }?.second
    // 输出中每条增量都会换成新的 homeState。侧边栏只需要压缩状态、子任务和累计用量，
    // 这些没变时不能跟着消息重组。
    val shellTokenUsage = remember { io.github.mangi.eta.ui.model.ConversationTokenUsageProjection() }
    val shellHomeState by remember {
        derivedStateOf {
            val state = agentState.homeState
            ShellHomeState(
                selectedContextTaskId = state.selectedContextTaskId,
                isCompressingContext = state.isCompressingContext,
                isWaitingForCompression = state.isWaitingForCompression,
                childContexts = state.childContexts,
                childStatusRoster = state.childStatusRoster,
                providerId = state.providerId,
                modelId = state.modelId,
                assistantId = state.assistantId,
                pendingConversationMentions = state.pendingConversationMentions,
                tokenUsage = shellTokenUsage.project(state.messages),
                // 只投影布尔量与运行 id：逐条流式增量不会改变这三个值，派生状态相等即不触发重组。
                hasMessages = state.messages.isNotEmpty(),
                isStreaming = state.isStreaming,
                activeRunId = state.activeRunId,
            )
        }
    }
    val cumulativeUsage = recordedUsage?.let {
        // 账单累计来自持久账本（只有四项），当前上下文只能取实时投影的最后一轮回执。
        // 位置参数构造的话后加的字段会静默落回 0，界面上就会显示「当前上下文 0」。
        io.github.mangi.eta.ui.model.ConversationTokenUsageUi(
            inputTokens = it.input,
            outputTokens = it.output,
            cachedTokens = it.cached,
            cacheCreationTokens = it.cacheCreation,
            lastInputTokens = shellHomeState.tokenUsage.lastInputTokens,
        )
    } ?: shellHomeState.tokenUsage

    DisposableEffect(currentRoute, agentState.conversationPaneState.selectedConversationId) {
        onDispose { io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stopUiBound("route_change") }
    }
    val speechPlayback by io.github.mangi.eta.agent.voice.tts.SpeechPlayback.state.collectAsState()
    AgentTaskSurfacePrompt()
    LaunchedEffect(speechPlayback.error) {
        speechPlayback.error?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show() }
    }
    val streamingMarkdownCache = remember { StreamingMarkdownCache() }
    val requestExecutionNotifications = rememberExecutionNotificationRequest()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        agentState.refreshPermissionHealth()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val updateOffer by AppUpdateCoordinator.offer.collectAsState()
    val currentVersion = remember { AppUpdateRepository.currentVersionName(context) }
    DisposableEffect(lifecycleOwner, focusManager, keyboard) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // 「后台继续朗读」只放行自动朗读；手动朗读、试听与语音对话照旧停止。
                    val owner = io.github.mangi.eta.agent.voice.tts.SpeechPlayback.state.value.owner
                    if (!io.github.mangi.eta.agent.voice.ReplyReadAloudController.keepsPlayingInBackground(owner)) {
                        io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stopUiBound("pause")
                    }
                    keyboard?.hide()
                    focusManager.clearFocus(force = true)
                }
                Lifecycle.Event.ON_RESUME -> {
                    RootAccess.refresh(context)
                    appViewModel.refreshKimiWeb()
                    agentState.refreshPermissionHealth()
                    agentState.refreshRuntimeResults()
                    agentState.refreshRequestOverhead()
                    // 更新检查只在冷启动做一次（见下方 LaunchedEffect）。
                    // 回到前台（授权、分享、装完 APK…）不应该再弹更新弹窗。
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }


    var conversationPaneOpen by remember { mutableStateOf(false) }
    val diagnosticLoggingEnabled by remember {
        io.github.mangi.eta.data.datastore.SettingsDataStore.fileLoggingEnabledFlow()
    }.collectAsState(initial = false)
    io.github.mangi.eta.ui.components.StreamPerformanceMonitor(
        loggingEnabled = diagnosticLoggingEnabled,
        page = when {
            conversationPaneOpen -> io.github.mangi.eta.ui.components.FrameDiagnosticPage.ConversationDrawer
            else -> currentRoute?.frameDiagnosticPage()
                ?: io.github.mangi.eta.ui.components.FrameDiagnosticPage.Unknown
        },
    )
    var conversationRenameTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    var conversationDeleteTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    // 调度台「清空主会话」：危险确认（归档到回收站，主会话从空白继续）。
    var confirmClearDispatchConsole by remember { mutableStateOf(false) }
    var conversationMoveTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    // pi keeps ONE active session per server, so opening a different remote session switches
    // it on the PC (and every other client) too. Hold the pending target and confirm first.
    var remoteSwitchTarget by remember {
        mutableStateOf<io.github.mangi.eta.ui.remote.ChatTarget.Remote?>(null)
    }
    var conversationExportId by rememberSaveable { mutableStateOf<String?>(null) }

    // 定时发送：应用在前台且正停留在目标会话时，到点的消息自动发出。
    val scheduledSendContext = androidx.compose.ui.platform.LocalContext.current
    val scheduledSends by io.github.mangi.eta.data.repository.ComposerQueueStore
        .scheduledSendsFlow(scheduledSendContext)
        .collectAsState(initial = emptyList())
    val selectedConversationId = agentState.conversationPaneState.selectedConversationId
    LaunchedEffect(scheduledSends, selectedConversationId) {
        val due = scheduledSends.firstOrNull {
            it.conversationId == selectedConversationId &&
                it.dueAtMillis <= System.currentTimeMillis()
        } ?: return@LaunchedEffect
        io.github.mangi.eta.data.repository.ComposerQueueStore
            .clearScheduled(scheduledSendContext, due.conversationId)
        agentState.sendCurrentMessage(due.text)
    }
    var conversationExportTitle by rememberSaveable { mutableStateOf("") }
    var conversationExportBusy by remember { mutableStateOf(false) }
    var conversationExportConfirmation by rememberSaveable { mutableStateOf(false) }

    var conversationAnalysisExportId by rememberSaveable { mutableStateOf<String?>(null) }
    var conversationAnalysisExportTitle by rememberSaveable { mutableStateOf("") }
    var conversationAnalysisIncludeRawIo by rememberSaveable { mutableStateOf(false) }
    var conversationAnalysisIncludeImages by rememberSaveable { mutableStateOf(false) }
    var conversationAnalysisExportBusy by remember { mutableStateOf(false) }
    var conversationAnalysisExportOptions by rememberSaveable { mutableStateOf(false) }

    val conversationExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val id = conversationExportId
        val title = conversationExportTitle
        conversationExportId = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (id == null) {
            Toast.makeText(context, context.getString(R.string.appst_export_target_lost), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        uiScope.launch {
            conversationExportBusy = true
            var touchedDestination = false
            val temporary = java.io.File(context.cacheDir, "conversation-export-${java.util.UUID.randomUUID()}.zip")
            val validation = java.io.File(context.cacheDir, "conversation-export-check-${java.util.UUID.randomUUID()}")
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    temporary.outputStream().use { agentState.exportConversation(id, it) }
                    io.github.mangi.eta.data.repository.BackupArchiveSafety.stageZip(temporary, validation)
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    touchedDestination = true
                    val output = context.contentResolver.openOutputStream(uri, "wt")
                        ?: error(context.getString(R.string.data_backup_file_open_failed))
                    output.use { sink -> temporary.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                        }
                    } }
                }
                Toast.makeText(context, context.getString(R.string.conversation_exported, title), Toast.LENGTH_SHORT).show()
            } catch (failure: Throwable) {
                val cleaned = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                }
                if (failure is kotlinx.coroutines.CancellationException) {
                    if (!cleaned && touchedDestination) Toast.makeText(context, context.getString(R.string.appst_export_cancelled_partial), Toast.LENGTH_LONG).show()
                    throw failure
                }
                val detail = failure.message ?: context.getString(R.string.conversation_export_failed)
                Toast.makeText(context, detail + if (!cleaned) {
                    if (touchedDestination) "\n" + context.getString(R.string.appst_export_partial_suffix) else "\n" + context.getString(R.string.appst_export_empty_suffix)
                } else "", Toast.LENGTH_LONG).show()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    temporary.delete()
                    validation.deleteRecursively()
                }
                conversationExportBusy = false
            }
        }
    }

    val conversationAnalysisExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val id = conversationAnalysisExportId
        val title = conversationAnalysisExportTitle
        val includeRawIo = conversationAnalysisIncludeRawIo
        val includeImages = conversationAnalysisIncludeImages
        conversationAnalysisExportId = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (id == null) {
            Toast.makeText(context, context.getString(R.string.appst_export_target_lost), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        uiScope.launch {
            conversationAnalysisExportBusy = true
            var touchedDestination = false
            try {
                val payload = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    agentState.exportConversationAnalysis(
                        id,
                        io.github.mangi.eta.data.repository.EtaConversationAnalysisOptions(
                            includeRawRuntimeIo = includeRawIo,
                            includeImages = includeImages,
                        ),
                    )
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                touchedDestination = true
                val output = context.contentResolver.openOutputStream(uri, "wt")
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                output.use { sink -> sink.write(payload.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(
                    context,
                    context.getString(R.string.conversation_analysis_exported, title),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (failure: Throwable) {
                val cleaned = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false)
                }
                if (failure is kotlinx.coroutines.CancellationException) {
                    if (!cleaned && touchedDestination) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.appst_export_cancelled_partial),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    throw failure
                }
                val detail = failure.message ?: context.getString(R.string.conversation_export_failed)
                Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
            } finally {
                conversationAnalysisExportBusy = false
            }
        }
    }

    var messageDeleteTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }
    var messageRegenerateTarget by remember { mutableStateOf<MessageMutationTarget?>(null) }

    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
    }

    // 冷启动（进程新建）只自动检查一次更新；Activity recreate、回前台都不重弹。
    LaunchedEffect(Unit) {
        AppUpdateCoordinator.checkOnceOnStartup(context)
    }

    // 「+」新建远程会话的落位：服务端建好并把新会话置为 current 后，这里把聊天视图
    // 切到新会话（不收起侧栏，与本机新建同口径；不再发 switch_session——服务端已切好）。
    // 必须收在 App 级作用域：miuix 只保证被 1 层盖住的条目仍在组合，栈深 ≥ 3 时
    // 底部条目会被移出组合，若挂进 entry 的 RoutedShell，newSessionReady（无 replay 的
    // SharedFlow）在那一刻发出的事件会永久丢失。根部单收集者天然只切一次。
    LaunchedEffect(Unit) {
        appViewModel.remoteCoordinator.newSessionReady.collect { target ->
            agentState.selectRemoteTarget(target)
        }
    }

    // 完成横幅直达会话：selectConversation 对未知/回收站会话是安全空操作（内部早退）。
    // replace 而非 push：从通知回来要的是「落在会话里」，回退仍回主页。
    LaunchedEffect(liveTargetConversationId) {
        val conversationId = liveTargetConversationId ?: return@LaunchedEffect
        agentState.selectConversation(conversationId)
        homeEntryPinnedConversationId = conversationId
        navigator.replace(AppRoute.Home)
        onLiveTargetConsumed()
    }

    // 浮窗关闭时把在跑的 run 交接给了应用内（见 AgentLiveRunHandoff）：这里只留主页钉子，
    // 不强行导航——用户可能正在别的页面做事，下一次进主页时落进这一单即可。
    // 选中与否由状态层按「当时还在调度台」判断，界面层不重复决定。
    LaunchedEffect(agentState.liveExternalConversationRequest) {
        val conversationId = agentState.liveExternalConversationRequest ?: return@LaunchedEffect
        homeEntryPinnedConversationId = conversationId
        agentState.consumeLiveExternalConversationRequest(conversationId)
    }

    // 助手浮窗口述「去配置」：落地语音设置页（push，回退仍回原页），随后复位请求令牌。
    // 直接走 navigator.push：pushRoute 是下方才声明的局部函数，不能前向引用，
    // 且 VoiceSettings 不需要 pushRoute 里 Browser 的失焦特判。
    LaunchedEffect(openVoiceSettingsRequest) {
        if (openVoiceSettingsRequest == 0) return@LaunchedEffect
        navigator.push(AppRoute.VoiceSettings)
        onVoiceSettingsRequestConsumed()
    }

    LaunchedEffect(inboundShareUris) {
        if (inboundShareUris.isEmpty()) return@LaunchedEffect
        agentState.attachSharedUris(inboundShareUris)
        navigator.popToHome()
        onInboundShareConsumed()
    }

    /**
     * 宽屏设置态下哪些路由压进右栏栈。主界面目的地仍走主栈；设置树的一切子页走右栏，
     * 这样从二级页再进三级页、四级页都只动右栏，左栏连重组都不会发生。
     */
    fun isSettingsDetailRoute(route: AppRoute): Boolean = when (route) {
        AppRoute.Home, AppRoute.Browser, AppRoute.Terminal,
        AppRoute.Settings, AppRoute.SettingsDetailEmpty,
        AppRoute.ManageChats, AppRoute.ManageFolders, AppRoute.ConversationTrash,
        AppRoute.ConversationTrashSearch, AppRoute.ConversationTokenUsage,
        AppRoute.SubAgentStatus, AppRoute.TerminalDaemonTasks -> false
        is AppRoute.MessageSearch -> false
        else -> true
    }

    fun pushRoute(route: AppRoute) {
        if (route == AppRoute.Browser) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            conversationPaneOpen = false
        }
        // 宽屏设置页：右栏自己 push 的三级/四级页进右栏栈，带右栏内转场；
        // 主栈保持 Settings 不动（左栏常驻）。与 AgentNavigator.push 一致按 == 去重。
        if (settingsWideActive && isSettingsDetailRoute(route)) {
            Snapshot.withMutableSnapshot {
                detailInstantSwap = false
                if (settingsDetailStack.none { it == route }) settingsDetailStack.add(route)
            }
            return
        }
        navigator.push(route)
    }

    /**
     * 左栏点选设置项：右栏整栈替换成该二级页，瞬时换页、不压层级、不播放页面转场。
     *
     * 不做 push，是为了修掉「页面层级」问题——以前每点一个左栏项就压一层，返回键得
     * 把历次点过的项逐个退出来。现在右栏栈始终只有「当前二级页 + 它上面的三级页」，
     * 左栏再点一项就整个换掉，返回键直接退出设置页。
     */
    fun selectSettingsDetail(route: AppRoute) {
        if (!settingsWideActive) {
            pushRoute(route)
            return
        }
        // 搜索是临时任务层，不是二级设置页：瞬时压在栈上（同样不播放页面转场），
        // 返回时退回原来的二级页，左栏选中态（只看栈底）也不变。否则搜索结果命中
        // 「行内控件」时调 popRoute 会一路退出整个设置页，左栏刚滚过去高亮的那一行
        // 就看不见了。
        if (route == AppRoute.SettingsSearch) {
            Snapshot.withMutableSnapshot {
                detailInstantSwap = true
                detailInstantEpoch++
                if (settingsDetailStack.none { it == route }) settingsDetailStack.add(route)
            }
            return
        }
        if (settingsDetailStack.size == 1 && settingsDetailStack.lastOrNull() == route) return
        // 转场标记、重建代次与栈变更放同一个快照：绝不出现「栈换了但 transition 还是旧的」
        // 那一帧，也让右栏 display 直接从新栈起步，不跑旧层的退场收敛。
        Snapshot.withMutableSnapshot {
            detailInstantSwap = true
            detailInstantEpoch++
            settingsDetailStack.clear()
            settingsDetailStack.add(route)
        }
    }

    /** 「我的声音」子页统一入口：走真实路由，复用导航容器的入栈/出栈转场。 */
    fun openVoiceSubPage(page: io.github.mangi.eta.ui.VoiceSubPage) {
        pushRoute(
            when (page) {
                io.github.mangi.eta.ui.VoiceSubPage.DOUBAO_VOICES -> AppRoute.DoubaoVoices
                io.github.mangi.eta.ui.VoiceSubPage.MIMO_VOICES -> AppRoute.MimoVoices
            },
        )
    }

    fun pushFromDrawer(route: AppRoute) {
        // 保持侧栏展开直接入栈：让侧栏作为首页的可见表面随首页一起退场。
        // 若先收起侧栏，会在目标页入场前露出一段聊天主页，看起来就是闪一下。
        pushRoute(route)
    }

    /**
     * 设置搜索命中「行内控件」后要聚焦的主页锚点键（SettingsKeys 里的常量）。
     *
     * 开关 / 选择器 / 一次性动作没有自己的子页，搜索结果只能回主页定位到那一行；这个值
     * 由主页消费（滚动 + 短暂高亮）后清空，不进路由参数，返回栈因此不会多压一层设置页。
     */
    var settingsFocusKey by rememberSaveable { mutableStateOf<String?>(null) }

    val exitGuard = remember { DoublePressExitGuard() }
    // 上一次从应用内出栈的时刻。新页推入 / 旧页退场的那段时间里，上一页顶栏仍然可点：
    // 紧随其后的那一下不能被当成「已经停在根页面按了返回」，否则会冒出退出提示。
    // 窗口取导航转场时长本身（EtaMotion 单一来源），不另立魔数。
    var lastPopAtMs by remember { mutableStateOf(0L) }

    // 进入任何新页面 / 新层级时清掉「等待第二次返回」的残留计时。
    // 否则在主页按过一次返回（只弹了提示）后 2 秒内进入子页，那次按键的记忆会被带上去：
    // 一旦子页里再次走到退出分支，第一次点击就直接结束 Activity，而不是再提示一次。
    LaunchedEffect(currentRoute) { exitGuard.reset() }

    /**
     * 主 `NavDisplay` 自己的返回（系统预测式返回 / 屏幕左缘横移手势）：**只弹主栈**。
     *
     * 宽屏设置页里右栏有独立返回栈。若主 display 的 `onBack` 也调 [popRoute]，手势会把
     * 整页（含左栏）拖向上一页，提交时却只弹了右栏栈、主栈纹丝不动，`animatedTop`
     * 只能弹回原位 —— 用户看到的「界面错乱」就是这一帧。让手势与它驱动的栈一致即可。
     * 右栏自己的手势/返回仍走 [popRoute]，逐层退出右栏。
     *
     * 取证：miuix-nav 0.9.4 sources jar `NavDisplay.kt:616-625` 的
     * `navSwipeDismissImpl(onCommit = currentOnBack.value)`，手势提交直接调 display 的
     * `onBack`；同文件 `:964` 默认 `onBack = { backStack.removeLastOrNull() }` 也只动本栈。
     */
    fun popRootRoute() {
        if (navigator.pop()) {
            exitGuard.reset()
            lastPopAtMs = SystemClock.uptimeMillis()
        }
    }

    fun popRoute() {
        // 宽屏设置页：右栏栈还有子页时先退出栈，主栈不动，左栏保持原位。
        // 右栏只剩空态栈底时落到下面的主栈逻辑，返回键自然退出整个设置页。
        if (settingsWideActive && settingsDetailStack.size > 1) {
            // 搜索层是左栏瞬时打开的，退回去同样无动画；三级/四级页出栈切回右栏内转场。
            val dismissingSearch = settingsDetailStack.lastOrNull() == AppRoute.SettingsSearch
            detailInstantSwap = dismissingSearch
            if (dismissingSearch) detailInstantEpoch++
            settingsDetailStack.removeAt(settingsDetailStack.lastIndex)
            exitGuard.reset()
            lastPopAtMs = SystemClock.uptimeMillis()
            return
        }
        // 「还有没有上一级」与系统返回键用同一个依据（miuix 返回栈 `SnapshotStateList<NavKey>`），
        // 只有确实在根页面时才进入双击退出逻辑。
        if (!navigator.canPop()) {
            // 刚出栈不到一个转场时长：此刻根页面还在入场、上一页还在退场，这次点击
            // 属于同一个「返回手势」的余波，不计入双击退出。
            if (SystemClock.uptimeMillis() - lastPopAtMs <
                EtaMotion.NavigationFallbackDurationMillis
            ) {
                return
            }
            if (exitGuard.consume()) {
                (context as? Activity)?.finish()
                return
            }
            Toast.makeText(
                context.applicationContext,
                context.getString(R.string.app_press_back_again_to_exit),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        // 有上一级：这次点击只负责出栈。直接读返回栈，转场未停稳也不会把这次点击
        // 误当成「已经在根」去提示退出——宁可这次没弹成，也不能误退出应用。
        if (navigator.pop()) {
            exitGuard.reset()
            lastPopAtMs = SystemClock.uptimeMillis()
        }
    }

    /**
     * 回到承载对话的页面（主页）。
     *
     * 搜索命中点开后要直接落在对话里：从管理聊天页进来时栈是
     * `Home → ManageChats → MessageSearch`，只弹一层会停在管理聊天页。
     * 命中可能跨到别的会话，弹回主页时钉住该会话，避免主页的调度台复位把它换掉。
     */
    fun popToConversation() {
        homeEntryPinnedConversationId = agentState.conversationPaneState.selectedConversationId
        navigator.popToConversationHost()
        exitGuard.reset()
        // 与 popRoute 同口径：被弹走的那几页还在退场，它们的顶栏仍可点，
        // 紧随其后的一下不该被当成「停在根页面按了返回」。
        lastPopAtMs = SystemClock.uptimeMillis()
    }

    // 导航容器只在还能出栈时消费返回；根页面必须自己接住，否则系统会直接 finish Activity。
    // 这里的「在根」与返回按钮共用 canPopRoute，不再用按路由表映射出来的条数判断。
    val interceptExitBack = !canPopRoute && !conversationPaneOpen
    val exitBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = exitBackState,
        isBackEnabled = interceptExitBack,
        onBackCompleted = { popRoute() },
    )

    fun selectConversation(conversationId: String) {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        agentState.selectConversation(conversationId)
    }

    // 新建会话不在这里收起侧栏：宽屏并排时侧栏要留着方便连续操作，
    // 窄屏全屏侧栏由 ConversationSidePaneScaffold 自己收起。
    fun createConversation() {
        focusManager.clearFocus()
        agentState.createConversation()
    }

    @Composable
    fun RoutedShell(
        route: AppRoute,
        underlapTopBar: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        // 转场活跃信号：miuix 在 entry 内容内部提供 LocalNavTransitionScope，isRunning 在
        // 手势拖动、settle 与程序化动画期间都为 true（退出隐藏页也仍被组合，所以底部路由
        // 的探针能覆盖整条返回栈上的每一次转场）。
        // 结束时再多等一帧才置 false：否则流式内容会在转场最后一帧抖一下
        // （与 KernelSU rememberContentReady 同一做法）。
        val navTransitionRunning = LocalNavTransitionScope.current.isRunning
        LaunchedEffect(navTransitionRunning) {
            if (navTransitionRunning) {
                navigationActive.value = true
            } else {
                withFrameNanos { }
                navigationActive.value = false
            }
        }
        CompositionLocalProvider(
            // 被 1 层盖住时页面仍在组合里，保持实时消息，让露出的字继续长。
            // 注意 miuix 的可见窗口是 -1 < d <= opaqueDepth（默认 opaqueDepth = 1）：
            // 栈深 ≥ 3 时更底部的条目会被移出组合——rememberSaveable 由
            // NavSaveableStateHolder 恢复、ViewModel 由 display 级注册表保留，
            // 但 LaunchedEffect(Unit) 会在回到窗口内时重跑，所以一次性副作用不要挂进 entry。
            // 完全盖住后的冻结由 LocalChatRouteCovered + LocalChatTransitionActive 负责。
            LocalChatUiActive provides true,
            LocalChatRouteCovered provides (currentRoute != route),
            // 输入区口述未配置时的「去配置」圆钮：与顶栏「语音」同一目标页。
            LocalOpenVoiceSettings provides { pushRoute(AppRoute.VoiceSettings) },
        ) {
        // 顶栏标题与右槽都以「当前聊天目标」为判据：远程目标走远程侧栏同一份投影，
        // paneSection 每次重组从可观察 map 现算，切会话 / 新建落位 / 服务端改名都会即时反映。
        val remoteTarget = agentState.selectedTarget as? ChatTarget.Remote
        val remotePaneSection = appViewModel.remoteCoordinator.paneSection(agentState.selectedTarget)
        // 当前聊天的流式 Markdown 状态 key：与对话页拿到的是同一个值
        // （远程 remote:serverId:path，本机为选中会话 id / "new-conversation"）。
        // 不能只用本机 selectedConversationId，否则远程聊天会复用本机会话的解析与打字机状态。
        val activeConversationKey = remoteTarget
            ?.let { appViewModel.remoteCoordinator.conversationKey(it) }
            ?: (agentState.conversationPaneState.selectedConversationId ?: "new-conversation")
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = currentRoute == route,
            conversationPaneState = agentState.conversationPaneState,
            currentConversationTitle = if (remoteTarget != null) {
                // 远程目标下标题取远程侧栏同源取名（name → 首条消息 → 路径尾段），
                // 不再沿用本机 pane 的选中会话标题（远程视图下它通常还停在调度台）。
                // 会话列表尚未回填时退到服务器名，避免闪回本机标题。
                remotePaneSection.servers
                    .firstOrNull { it.id == remoteTarget.serverId }
                    ?.let { server ->
                        server.sessions.firstOrNull { it.path == remoteTarget.path }
                            ?.let(::remoteSessionTitle)
                            ?: server.name
                    }
            } else {
                // 全量池查标题：pane.conversations 是文件夹筛选子集，回到调度台等跨文件夹
                // 跳转后选中会话不在其中，查池外会话标题会变成空。
                agentState.conversationPaneState.selectedConversationId?.let { id ->
                    agentState.conversationPaneState.allConversations.find { it.id == id }?.title
                }
            },
            isConversationPaneOpen = conversationPaneOpen,
            onBack = { popRoute() },
            // 手势打开只负责「置为展开」；汉堡按钮单独走 toggle，两者不能共用同一个回调。
            onOpenConversationPane = { conversationPaneOpen = true },
            onToggleConversationPane = { conversationPaneOpen = !conversationPaneOpen },
            onDismissConversationPane = { conversationPaneOpen = false },
            onNewConversation = {
                // 「+」按胶囊分流：远程胶囊选中时新建的是那台服务器上的会话；
                // 本机作用域（本机/未分类/文件夹）才建本机聊天。
                val remoteServerId = agentState.selectedRemoteServerId
                if (remoteServerId != null && appViewModel.remoteCoordinator.servers.any { it.id == remoteServerId }) {
                    appViewModel.remoteCoordinator.requestNewSession(remoteServerId)
                } else {
                    // 处于远程会话时新建本机聊天须落回 Local，否则视图仍钉在远程状态上。
                    agentState.selectLocalTarget()
                    createConversation()
                }
            },
            // 远程目标与首页右槽的分流判据：只认 ChatTarget，不认「本机 pane 选中了谁」。
            isRemoteTarget = remoteTarget != null,
            // 非调度台会话（且非远程目标）时，顶栏右侧显示「回到调度台」。
            showBackToConsole = agentState.selectedTarget is io.github.mangi.eta.ui.remote.ChatTarget.Local &&
                !agentState.currentIsDispatchConsole,
            onBackToConsole = {
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                agentState.ensureDispatchConsoleSelected()
            },
            canClearDispatchConsole = agentState.currentIsDispatchConsole &&
                shellHomeState.hasMessages &&
                !shellHomeState.isStreaming &&
                shellHomeState.activeRunId == null,
            onClearDispatchConsole = { confirmClearDispatchConsole = true },
            onOpenTerminal = { pushRoute(AppRoute.Terminal) },
            onLaunchKimiWeb = {
                requestExecutionNotifications()
                if (appViewModel.kimiWebState.phase != KimiWebPhase.NOT_INSTALLED) {
                    appViewModel.launchKimiWeb { result ->
                        if (result is KimiWebLaunchResult.Failed) {
                            Toast.makeText(
                                context,
                                result.message(context),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else {
                    pushRoute(AppRoute.LinuxEnvironment)
                }
            },
            kimiWebLabel = appViewModel.kimiWebState.actionLabel(context),
            canStopKimiWeb = appViewModel.kimiWebState.canStop,
            onStopKimiWeb = appViewModel::stopKimiWeb,
            onRefreshKimiWeb = appViewModel::refreshKimiWeb,
            onOpenBrowser = { pushRoute(AppRoute.Browser) },
            onOpenWorkspace = { pushRoute(AppRoute.Workspace) },
            onOpenVoiceSettings = { pushRoute(AppRoute.VoiceSettings) },
            autoCompressEnabled = agentState.autoCompressEnabled,
            isCompressingContext = if (shellHomeState.selectedContextTaskId == null)
                shellHomeState.isCompressingContext || shellHomeState.isWaitingForCompression
            else shellHomeState.childContexts.any { it.taskId == shellHomeState.selectedContextTaskId &&
                (it.isCompacting || it.manualCompactionState == "pending") },
            onToggleAutoCompress = { agentState.updateAutoCompressEnabled(it) },
            // 溢出菜单「压缩会话」：立即压缩当前会话，与原设置页底部按钮共用同一段压缩逻辑
            // （模型与端点取压缩设置页里配置的同一份偏好）；进行中的反馈由聊天流指示器与 Toast 承担。
            onCompressConversation = { agentState.compressCurrentConversation { } },
            onOpenSearchHistory = { pushRoute(AppRoute.MessageSearch(conversationScoped = true)) },
            onOpenTokenUsage = { pushRoute(AppRoute.ConversationTokenUsage) },
            onOpenSubAgentStatus = { pushRoute(AppRoute.SubAgentStatus) },
            selectedProviderId = agentState.modelPickerState.selectedModel?.providerId,
            onSelectConversation = { conversationId -> selectConversation(conversationId) },
            onConversationRename = { conversation ->
                conversationRenameTarget = conversation
            },
            onConversationDelete = { conversation ->
                conversationDeleteTarget = conversation
            },
            onMoveConversationToFolder = { conversation ->
                conversationMoveTarget = conversation
            },
            onConversationTogglePin = { conversation ->
                agentState.toggleConversationPinned(conversation.id)
            },
            onConversationExport = { conversation ->
                if (conversationExportBusy || conversationExportId != null) {
                    Toast.makeText(context, context.getString(R.string.appst_export_in_progress), Toast.LENGTH_SHORT).show()
                } else {
                    conversationExportId = conversation.id
                    conversationExportTitle = conversation.title.ifBlank { context.getString(R.string.conversation_unnamed) }
                    conversationExportConfirmation = true
                }
            },
            onConversationAnalysisExport = { conversation ->
                if (conversationAnalysisExportBusy || conversationAnalysisExportId != null) {
                    Toast.makeText(context, context.getString(R.string.appst_export_in_progress), Toast.LENGTH_SHORT).show()
                } else {
                    conversationAnalysisExportId = conversation.id
                    conversationAnalysisExportTitle = conversation.title.ifBlank { context.getString(R.string.conversation_unnamed) }
                    conversationAnalysisExportOptions = true
                }
            },
            onOpenManageChats = { pushFromDrawer(AppRoute.ManageChats) },
            // 抽屉搜索与管理聊天页的放大镜同一个目的地：跨会话全文搜索。
            onOpenDrawerSearch = { pushFromDrawer(AppRoute.MessageSearch()) },
            onOpenManageFolders = { pushFromDrawer(AppRoute.ManageFolders) },
            onSelectFolder = { folderId -> agentState.selectFolder(folderId) },
            onRestoreConversation = { conversationId -> agentState.restoreConversationFromTrash(conversationId) },
            onConversationDeleteForever = { conversationId -> agentState.deleteConversationForever(conversationId) },
            onEmptyTrash = { agentState.emptyTrash() },
            onOpenTrash = { pushFromDrawer(AppRoute.ConversationTrash) },
            onSelectAssistant = { id -> agentState.selectAssistant(id) },
            onEditAssistant = { id -> pushFromDrawer(AppRoute.AssistantEdit(id)) },
            onOpenAssistants = { pushFromDrawer(AppRoute.Assistants()) },
            onOpenUsageStats = { pushFromDrawer(AppRoute.UsageStats) },
            onOpenSettings = { pushFromDrawer(AppRoute.Settings) },
            remoteSection = remotePaneSection,
            onSelectRemoteSession = { serverId, path ->
                focusManager.clearFocus(force = true)
                keyboard?.hide()
                val target = io.github.mangi.eta.ui.remote.ChatTarget.Remote(serverId, path)
                val remoteCoordinator = appViewModel.remoteCoordinator
                // 显式选择会话行：丢弃挂起中的新建请求，避免稍后的落位把这次选择顶掉。
                remoteCoordinator.consumeNewSessionRequest()
                val serverCurrent = remoteCoordinator.sessionsById[serverId]?.current
                if (serverCurrent != null && serverCurrent != path) {
                    remoteSwitchTarget = target
                } else {
                    agentState.selectRemoteTarget(target)
                    remoteCoordinator.selectRemoteSession(target)
                }
            },
            onOpenRemoteSettings = { pushFromDrawer(AppRoute.RemoteAgents) },
            // 侧栏胶囊行的远程服务器过滤器：与文件夹选中同一层——只切列表，不切聊天视图。
            selectedRemoteServerId = agentState.selectedRemoteServerId,
            onSelectRemoteServer = { agentState.selectRemoteServerPane(it) },
        ) { padding ->
            // M3 对话页让消息列表延伸到顶栏背后，滚入的部分由顶栏渐变板淡出；
            // 顶栏高度经 LocalShellTopBarInset 交给列表做顶部 contentPadding。
            // MIUI X 保持原样：内容整体排在顶栏下方，不下压。
            val outerPadding = if (underlapTopBar) {
                PaddingValues(
                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                    bottom = padding.calculateBottomPadding(),
                )
            } else {
                padding
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding)
            ) {
                CompositionLocalProvider(
                    LocalStreamingMarkdownStates provides streamingMarkdownCache.forConversation(
                        activeConversationKey
                    ),
                    LocalShellTopBarInset provides if (underlapTopBar) padding.calculateTopPadding() else 0.dp,
                ) {
                    content()
                }
            }
        }
        }
    }

    CompositionLocalProvider(
        io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor provides subAgentEditor,
        LocalChatTransitionActive provides navigationActive.value,
        // 系统助手在主应用前台唤醒：聊天输入区按唤起方式分派响应（听写/聚焦）。
        // 水位随令牌一起下发：composer 重建实例播种，防旧唤起令牌重放。
        LocalAssistantDictationRequest provides inAppDictationRequest,
        LocalAssistantFocusRequest provides inAppFocusRequest,
        LocalAssistantDictationConsumed provides inAppDictationConsumed,
        LocalAssistantFocusConsumed provides inAppFocusConsumed,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    // 兜底：miuix 0.9.4 的 NavEntryBuilder 没有 catch-all，未登记的 route 会在 build() 直接
    // 抛异常（entryProvider / 低层 NavDisplay 重载均为 internal，无法自建 fallback）。
    // 因此渲染前先挡掉不可渲染的条目：改渲染最小错误页，返回一次即可剔除并恢复返回栈。
    if (unrenderableRoute != null) {
        UnknownRoutePage(
            onRecover = {
                Snapshot.withMutableSnapshot {
                    backStack.removeAll { !isKnownAppRoute(it) }
                    settingsDetailStack.removeAll { !isKnownAppRoute(it) }
                    if (backStack.isEmpty()) backStack.add(AppRoute.Home)
                    if (settingsDetailStack.isEmpty()) {
                        settingsDetailStack.add(AppRoute.SettingsDetailEmpty)
                    }
                }
            },
        )
    } else {
        // 边缘横移返回：直接用 miuix 自带的 NavSwipeDismiss，不自己写 pointerInput。
        // 签名经 miuix-nav-android-0.9.4.aar 内 classes.jar 的 javap 核实：
        //   NavEntryBuilder.entry(Function1<NavKey,Any?>, NavTransition, NavSwipeDirection,
        //                        Map<String,Any?>, Function3<T,Composer,Int,Unit>)
        // 第 3 位即 Kotlin 参数 swipeDismiss；entry$default 在未传该位时写 aconst_null。
        // NavDisplayLayout 只从栈顶 entry 的 metadata[NAV_SWIPE_DISMISS_METADATA_KEY] 取方向，
        // 所以必须逐条显式传值——漏一条就等于那一页没有手势。
        // 方向必须在 builder 外先读成普通值再捕获：NavDisplay 用 remember(builder) 缓存
        // entryProvider，若在 builder 内读 State.value，builder 引用不变，已建好的 NavEntry
        // 会一直保留旧方向。RTL 下从右边缘起滑，方向反过来。
        val navSwipeDismiss = when {
            !swipeBackEnabled -> NavSwipeDirection.None
            LocalLayoutDirection.current == LayoutDirection.Rtl -> NavSwipeDirection.RightToLeft
            else -> NavSwipeDirection.LeftToRight
        }
        // entry DSL 抽成本文件内的局部函数：主 NavDisplay 与右栏 NavDisplay 共用同一份，
        // 不复制任何 entry（test_app_route_entry_parity_contract.py 禁止重复登记）。
        // 局部函数可递归调用自己，Settings entry 里的右栏 NavDisplay 再次调用它即可。
        // 函数体保持原有的 12 空格缩进与 entry 顺序，文本级契约断言照常命中。
        fun NavEntryBuilder.appEntries() {
            // 栈底特例：Home 永远是返回栈第一项（没有 pushRoute(AppRoute.Home) 的调用），
            // 给它横移返回既没有「上一页」可回，又会和首页左边缘拉出会话抽屉抢手势，
            // 还可能让 popRoute() 的双击退出守卫弹出提示。其余路由统一用全局方向。
            entry<AppRoute.Home>(swipeDismiss = NavSwipeDirection.None) {
                val coordinator = appViewModel.remoteCoordinator
                val remote = agentState.selectedTarget as? io.github.mangi.eta.ui.remote.ChatTarget.Remote
                // 主页是调度台：每次回到主页都落到调度台会话（远程目标除外）。
                // 外部入口刚指名的会话仍在选中时不复位，见 [homeEntryPinnedConversationId]。
                androidx.compose.runtime.LaunchedEffect(remote) {
                    if (remote != null) return@LaunchedEffect
                    val pinned = homeEntryPinnedConversationId
                    homeEntryPinnedConversationId = null
                    val keep = pinned != null &&
                        agentState.conversationPaneState.selectedConversationId == pinned
                    if (!keep) agentState.ensureDispatchConsoleSelected()
                }
                RoutedShell(route = AppRoute.Home, underlapTopBar = true) {
                    // 远程会话只有「服务器上那个助手」一个，本机无法切换。
                    // 把名字往下传（CompositionLocal），助手列表据此只显示这一项。
                    // 重连/失败状态一并下传：断线期间输入框胶囊整体变成状态胶囊。
                    val remoteComposerStatus = remoteComposerStatusOf(coordinator, remote) {
                        pushRoute(AppRoute.RemoteAgents)
                    }
                    androidx.compose.runtime.CompositionLocalProvider(
                        io.github.mangi.eta.ui.remote.LocalRemoteAgentName provides
                            remoteAgentNameOf(coordinator, remote),
                        io.github.mangi.eta.ui.remote.LocalRemoteComposerStatus provides remoteComposerStatus,
                    ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    AgentHomeScreen(
                        stateProvider = { if (remote != null) coordinator.chatState(remote) else agentState.homeState },
                        conversationMentions = if (remote != null) {
                            io.github.mangi.eta.ui.model.ConversationMentionInputUi()
                        } else {
                            io.github.mangi.eta.ui.model.ConversationMentionInputUi(
                                conversations = agentState.conversationPaneState.historyConversations,
                                currentConversationId = agentState.conversationPaneState.selectedConversationId,
                                pending = shellHomeState.pendingConversationMentions,
                                onAttach = agentState::attachConversationMention,
                                onRemove = agentState::removeConversationMention,
                            )
                        },
                        queuedMessages = if (remote != null) emptyList() else agentState.queuedMessages,
                        onSteerQueuedMessage = agentState::steerQueuedMessage,
                        onRecallQueuedMessage = agentState::recallQueuedMessage,
                        onRemoveQueuedMessage = agentState::removeQueuedMessage,
                        modelPickerState = if (remote != null) coordinator.modelPickerState(remote) else agentState.modelPickerState,
                        autoCompressEnabled = if (remote != null) false else agentState.autoCompressEnabled,
                        requestOverheadTokens = if (remote != null) 0 else agentState.requestOverheadTokens,
                        measuredContextTokens = { if (remote != null) null else agentState.measuredContextTokens },
                        billedOverheadTokens = if (remote != null) null else agentState.billedOverheadTokens,
                        conversationKey = if (remote != null) coordinator.conversationKey(remote) else agentState.conversationPaneState.selectedConversationId,
                        draftField = if (remote != null) coordinator.draftFieldFor(remote) else agentState.currentDraftField(),
                        onAction = { action ->
                            if (remote != null) {
                                coordinator.handleHomeAction(remote, action)
                            } else when (action) {
                                AgentHomeAction.CycleGptSpeedMode -> agentState.cycleGptSpeedMode()
                                is AgentHomeAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                is AgentHomeAction.ContextTaskSelected -> agentState.selectContextTask(action.taskId, usageConversationId)
                                is AgentHomeAction.OpenConversation -> agentState.selectConversation(action.conversationId)
                                is AgentHomeAction.ModelSelected -> agentState.selectModel(action.modelId, action.providerId)
                                is AgentHomeAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                AgentHomeAction.StopRun -> agentState.pauseCurrentRun()
                                AgentHomeAction.ContinueRun -> agentState.continuePausedGeneration()
                                AgentHomeAction.AbortPausedRun -> stopMainReply()
                                is AgentHomeAction.AssistantSelected -> agentState.selectAssistant(action.id)
                                is AgentHomeAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentHomeAction.VideoAttached -> agentState.attachVideo(action.uri)
                                is AgentHomeAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentHomeAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentHomeAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentHomeAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentHomeAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentHomeAction.SteerQueuedMessage -> agentState.steerQueuedMessage(action.id)
                                is AgentHomeAction.RecallQueuedMessage -> agentState.recallQueuedMessage(action.id)
                                is AgentHomeAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentHomeAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentHomeAction.DeleteMessage -> {
                                    agentState.messageDeleteImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentHomeAction.BranchMessage -> agentState.branchConversation(action.id)
                                is AgentHomeAction.QuestionDraftChanged -> agentState.updateQuestionDraft(action.conversationId, action.questionId, action.answer)
                                is AgentHomeAction.SubmitQuestionAnswer -> agentState.submitQuestionAnswer(action.conversationId, action.questionId)
                                AgentHomeAction.OpenTools -> pushRoute(AppRoute.Tools)
                                AgentHomeAction.OpenSkills -> pushRoute(AppRoute.Skills)
                                AgentHomeAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                                AgentHomeAction.OpenSystemEnhance -> pushRoute(AppRoute.SystemEnhance)
                                AgentHomeAction.OpenSettings -> pushRoute(AppRoute.Settings)
                                AgentHomeAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                is AgentHomeAction.EditAssistant -> pushRoute(AppRoute.AssistantEdit(action.id))
                                AgentHomeAction.OpenModelProviders -> pushRoute(AppRoute.ModelProviders)
                                AgentHomeAction.OpenAssistants -> pushRoute(AppRoute.Assistants())
                                // 「+」菜单「去搜索」：媒体语义搜索页，多选后可添加回当前会话。
                                AgentHomeAction.OpenMediaSearch -> pushRoute(AppRoute.MediaSearch)
                                // 长按「助手与模型」入口复用设置页的子代理配置页，不单独做页面。
                                AgentHomeAction.OpenSubAgentSettings -> pushRoute(AppRoute.SubAgents)
                                AgentHomeAction.ExpandRunTrace -> Unit
                            }
                        },
                        isDrawerOpen = conversationPaneOpen,
                        scrollToMessageId = if (remote != null) null else agentState.pendingScrollToMessageId,
                        onScrollToMessageConsumed = agentState::consumePendingScrollToMessage,
                        sentUserMessageEntranceId = if (remote != null) null else agentState.sentUserMessageEntranceId,
                        onSentUserMessageEntranceConsumed = agentState::consumeSentUserMessageEntrance,
                    )
                    }
                    }
                    }
                }
            entry<AppRoute.Browser>(swipeDismiss = navSwipeDismiss) {
                AgentBrowserScreen(
                    onBack = ::popRoute,
                    conversationId = agentState.conversationPaneState.selectedConversationId,
                )
            }
            entry<AppRoute.Terminal>(swipeDismiss = navSwipeDismiss) {
                LaunchedEffect(Unit) { requestExecutionNotifications() }
                RoutedShell(route = AppRoute.Terminal) {
                    TerminalEntryScreen(
                        terminalStore = appViewModel.terminalStore,
                        consoleStore = appViewModel.consoleStore,
                        onOpenEnvironment = { pushRoute(AppRoute.LinuxEnvironment) },
                        onOpenDaemonTasks = { pushRoute(AppRoute.TerminalDaemonTasks) },
                    )
                }
            }
            entry<AppRoute.TerminalDaemonTasks>(swipeDismiss = navSwipeDismiss) {
                val terminalStore = appViewModel.terminalStore
                val terminalState by terminalStore.uiState.collectAsState()
                // 终端会话常驻运行：进页面先刷新一次，之后随终端状态继续更新。
                LaunchedEffect(Unit) { terminalStore.refreshDaemonTasks() }
                DaemonTasksScreen(
                    tasks = terminalState.daemonTasks,
                    onBack = ::popRoute,
                    onStop = terminalStore::stopDaemonTask,
                    onLoadLogs = terminalStore::daemonLogs,
                )
            }
            entry<AppRoute.Haptics>(swipeDismiss = navSwipeDismiss) {
                HapticsSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.AgentTaskPreference>(swipeDismiss = navSwipeDismiss) {
                AgentTaskPreferenceScreen(
                    onBack = ::popRoute,
                    onOpenVirtualDisplay = { pushRoute(AppRoute.VirtualDisplay) },
                )
            }
            // Compatibility alias for a restored old back stack; there is no separate recovery page.
            entry<AppRoute.VirtualDisplayRecovery>(swipeDismiss = navSwipeDismiss) {
                AgentTaskPreferenceScreen(
                    onBack = ::popRoute,
                    onOpenVirtualDisplay = { pushRoute(AppRoute.VirtualDisplay) },
                )
            }
            entry<AppRoute.VirtualDisplay>(swipeDismiss = navSwipeDismiss) {
                VirtualDisplayScreen(onBack = ::popRoute)
            }
            entry<AppRoute.Tools>(swipeDismiss = navSwipeDismiss) {
                // 工具页状态是快照：进页必须重读一次，否则从设置页改完开关再进来，
                // 看到的是上次构建时的旧快照（这就是「两处开关不同步」的来源）。
                // 以 configuration（含语言）为 key：切语言后必然重建，并且每次都把
                // Activity 级上下文交给 state——applicationContext 在部分 OEM 上不吃
                // per-app 语言覆盖，用它预解析的快照会把工具列表停回系统语言。
                val toolsContext = LocalContext.current
                val toolsLocales = LocalConfiguration.current.locales.toLanguageTags()
                LaunchedEffect(toolsLocales) { agentState.refreshToolsState(toolsContext) }
                AgentToolsScreen(
                    state = agentState.toolsState,
                    onAction = { action ->
                        when (action) {
                            AgentToolsAction.NavigateBack -> popRoute()
                            AgentToolsAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                            AgentToolsAction.OpenEnhancements -> pushRoute(AppRoute.SystemEnhance)
                            AgentToolsAction.OpenPermissions -> pushRoute(AppRoute.Permissions)
                            AgentToolsAction.OpenWebToolsSettings -> pushRoute(AppRoute.WebToolsSettings)
                            is AgentToolsAction.ToggleGroup -> {
                                // 写失败（commit 落盘失败）也照样重建：状态从 Prefs 重读，
                                // 开关自然弹回原位，用户不会看到「显示已关、实际没关」。
                                ToolGroupSwitch.setEnabled(action.key, action.enabled)
                                agentState.refreshToolsState()
                            }
                            is AgentToolsAction.ToggleTool -> {
                                io.github.mangi.eta.config.Prefs.setToolDisabled(
                                    action.name, disabled = !action.enabled)
                                agentState.refreshToolsState()
                            }
                        }
                    },
                )
            }
            entry<AppRoute.Skills>(swipeDismiss = navSwipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshSkills()
                }
                AgentSkillsScreen(
                    state = agentState.skillsState,
                    onAction = { action ->
                        when (action) {
                            AgentSkillsAction.NavigateBack -> popRoute()
                            is AgentSkillsAction.ImportZip -> agentState.importSkillZip(action.uri)
                            AgentSkillsAction.ConfirmZipReplacement -> agentState.confirmSkillZipReplacement()
                            AgentSkillsAction.CancelZipReplacement -> agentState.cancelSkillZipReplacement()
                            AgentSkillsAction.DismissNotice -> agentState.dismissSkillNotice()
                            is AgentSkillsAction.ToggleSkill -> agentState.toggleSkill(action.skillId, action.enabled)
                            is AgentSkillsAction.DeleteSkill -> agentState.deleteSkill(action.skillId)
                            is AgentSkillsAction.ReinstallBuiltin -> agentState.reinstallBuiltin(action.skillId)
                        }
                    },
                )
            }
            entry<AppRoute.Permissions>(swipeDismiss = navSwipeDismiss) {
                LaunchedEffect(Unit) {
                    agentState.refreshPermissionHealth()
                }
                PermissionHealthScreen(
                    state = agentState.permissionHealthState,
                    onAction = { action ->
                        when (action) {
                            PermissionHealthAction.NavigateBack -> popRoute()
                            is PermissionHealthAction.OpenItemAction -> {
                                when (action.itemId) {
                                    "accessibility" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }
                                    }
                                    "overlay" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "background" -> {
                                        if (RootAccess.isGranted && Build.MANUFACTURER.lowercase() in setOf("oppo", "realme", "oneplus")) {
                                            uiScope.launch(Dispatchers.IO) {
                                                BoundedRootCommandExecutor(AndroidAgentLogger).use {
                                                    it.execute(
                                                        "am start --user current -n " +
                                                            "com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity " +
                                                            "--es title Eta --es pkgName io.github.mangi.eta --es drainType APP",
                                                    )
                                                }
                                            }
                                        } else {
                                            runCatching {
                                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                                            }
                                        }
                                    }
                                    "app_list" -> {
                                        runCatching {
                                            context.startActivity(
                                                Intent(
                                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                            )
                                        }
                                    }
                                    "location" -> {
                                        when (DeviceLocationProvider.accessState(context)) {
                                            DeviceLocationProvider.AccessState.DENIED -> {
                                                permissionLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                                    )
                                                )
                                            }
                                            DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.DISABLED -> {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                                    )
                                                }
                                            }
                                            DeviceLocationProvider.AccessState.AVAILABLE -> {
                                                agentState.refreshPermissionHealth()
                                            }
                                        }
                                    }
                                    "calendar" -> {
                                        permissionLauncher.launch(
                                            arrayOf(
                                                Manifest.permission.READ_CALENDAR,
                                                Manifest.permission.WRITE_CALENDAR,
                                            )
                                        )
                                    }
                                    "local_network" -> {
                                        // 能申请就申请（系统会弹权限对话框）；不能申请说明
                                        // app-op 被系统按 ignore 按住，那种情况只有系统设置里有救。
                                        if (LocalNetworkPermission.canRequest(
                                                LocalNetworkPermission.accessState(context),
                                            )
                                        ) {
                                            permissionLauncher.launch(arrayOf(LocalNetworkPermission.PERMISSION))
                                        } else {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        Uri.parse("package:${context.packageName}")
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    "notification_history" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                        }
                                    }
                                    "usage_access" -> {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                        }
                                    }
                                    "notifications" -> {
                                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                    }
                                    "root" -> pushRoute(AppRoute.SystemEnhance)
                                }
                            }
                        }
                    },
                )
            }
            entry<AppRoute.SystemEnhance>(swipeDismiss = navSwipeDismiss) {
                SystemEnhanceScreen(
                    onAction = { action ->
                        when (action) {
                            AgentSystemEnhanceAction.NavigateBack -> popRoute()
                            AgentSystemEnhanceAction.RequestRoot -> { RootAccess.request(context) }
                            AgentSystemEnhanceAction.RefreshRoot -> { RootAccess.refresh(context) }
                        }
                    },
                )
            }
            entry<AppRoute.Workspace>(swipeDismiss = navSwipeDismiss) {
                WorkspaceScreen(onBack = ::popRoute)
            }
            entry<AppRoute.UsageStats>(swipeDismiss = navSwipeDismiss) {
                UsageStatsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.ManageChats>(swipeDismiss = navSwipeDismiss) {
                ManageChatsScreen(
                    conversations = agentState.conversationPaneState.historyConversations,
                    onBack = ::popRoute,
                    onOpenConversation = { conversationId ->
                        selectConversation(conversationId)
                        popRoute()
                    },
                    onTogglePin = { conversationId ->
                        agentState.toggleConversationPinned(conversationId)
                    },
                    onDeleteConversation = { conversation ->
                        agentState.deleteConversation(conversation.id)
                    },
                    onDeleteAll = { agentState.deleteAllConversations() },
                    onOpenSearchHistory = { pushRoute(AppRoute.MessageSearch()) },
                )
            }
            entry<AppRoute.ManageFolders>(swipeDismiss = navSwipeDismiss) {
                ManageFoldersScreen(
                    folders = agentState.conversationPaneState.folders,
                    conversations = agentState.conversationPaneState.historyConversations,
                    remoteCoordinator = appViewModel.remoteCoordinator,
                    onBack = ::popRoute,
                    onCreateFolder = { name -> agentState.createFolder(name) },
                    onRenameFolder = { folderId, name -> agentState.renameFolder(folderId, name) },
                    onDeleteFolder = { folderId -> agentState.deleteFolder(folderId) },
                    onOpenRemoteAgents = { pushRoute(AppRoute.RemoteAgents) },
                )
            }
            entry<AppRoute.ConversationTrash>(swipeDismiss = navSwipeDismiss) {
                TrashScreen(
                    trash = agentState.conversationPaneState.trashConversations,
                    onBack = ::popRoute,
                    onRestore = { conversationId ->
                        agentState.restoreConversationFromTrash(conversationId)
                    },
                    onDeleteForever = { conversationId ->
                        agentState.deleteConversationForever(conversationId)
                    },
                    onEmptyTrash = { agentState.emptyTrash() },
                    onPurgeExpired = { agentState.purgeExpiredTrash() },
                    onOpenSearch = { pushRoute(AppRoute.ConversationTrashSearch) },
                )
            }
            entry<AppRoute.ConversationTrashSearch>(swipeDismiss = navSwipeDismiss) {
                TrashSearchScreen(
                    onSearch = { query ->
                        agentState.conversationPaneState.trashConversations.filter {
                            it.title.contains(query, ignoreCase = true)
                        }
                    },
                    onBack = ::popRoute,
                    onRestore = { conversationId ->
                        agentState.restoreConversationFromTrash(conversationId)
                    },
                    onDeleteForever = { conversationId ->
                        agentState.deleteConversationForever(conversationId)
                    },
                )
            }
            entry<AppRoute.MessageSearch>(swipeDismiss = navSwipeDismiss) { route ->
                MessageSearchScreen(
                    onSearch = { query ->
                        agentState.searchHistory(
                            query,
                            currentConversationOnly = route.conversationScoped,
                        )
                    },
                    onOpenHit = { hit ->
                        conversationPaneOpen = false
                        agentState.openHistorySearchHit(hit)
                        // 回到对话本体：命中的是哪条消息，就落在哪条消息上。
                        popToConversation()
                    },
                    onBack = ::popRoute,
                    // 跨会话检索时命中行要标出所属会话，会话内检索则省掉这层噪音。
                    showConversationTitle = !route.conversationScoped,
                )
            }
            entry<AppRoute.ConversationTokenUsage>(swipeDismiss = navSwipeDismiss) {
                // 与旧弹窗同源：用量取当前会话的实时投影，页面留在栈上也会随对话继续变化。
                ConversationTokenUsageScreen(
                    usage = cumulativeUsage,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.SubAgentStatus>(swipeDismiss = navSwipeDismiss) {
                // 与旧弹窗同源：取当前会话的实时子助手名册。
                SubAgentStatusScreen(
                    agents = shellHomeState.childStatusRoster,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.Settings>(swipeDismiss = navSwipeDismiss) {
                // 宽屏：左栏是原封不动的设置主页（十组全渲染、自带顶栏与搜索入口），
                // 右栏是独立的二级/三级返回栈；窄屏与改动前逐字一致。
                // 左栏点选走 selectSettingsDetail（整栈替换 / 无动画），右栏页内跳转仍走
                // pushRoute（压右栏栈 / 右栏内转场）。
                val openSettingsDestination: (AppRoute) -> Unit = if (wideScreenActive) {
                    { route -> selectSettingsDetail(route) }
                } else {
                    { route -> pushRoute(route) }
                }
                // 右栏栈底（左栏选中的二级页）：选中态与右栏 display 的重组 key 同源，
                // 进三级/四级页时左栏高亮不跳。
                val detailBaseRoute = settingsDetailStack.firstOrNull()
                val selectedKey = if (wideScreenActive) {
                    settingsAnchorKeyForRoute(detailBaseRoute as? AppRoute)
                } else {
                    null
                }
                val settingsPage: @Composable () -> Unit = {
                    SettingsScreen(
                        context = context,
                        onNavigate = openSettingsDestination,
                        onBack = ::popRoute,
                        currentProviderId = shellHomeState.providerId,
                        currentModelId = shellHomeState.modelId,
                        focusKey = settingsFocusKey,
                        onFocusConsumed = { settingsFocusKey = null },
                        selectedKey = selectedKey,
                    )
                }
                if (wideScreenActive) {
                    SettingsWideHost(
                        left = settingsPage,
                        right = {
                            // 右栏栈深（0 = 栈底）：页面骨架据此决定顶栏要不要画返回箭头 ——
                            // 二级页第一层身后没有上层可返回，箭头是多余的；三级/四级页照旧。
                            // 值随栈深变化，但读取方只在页面首次组合时取一次，所以 push 时
                            // 栈底页不会重新长出箭头（见 `LocalEtaDetailPaneDepth`）。
                            CompositionLocalProvider(
                                LocalEtaDetailPaneDepth provides (settingsDetailStack.size - 1),
                            ) {
                                // 栈底换页时重建整个右栏 display：miuix 的 presentation /
                                // animatedTop 是 remember 全局的，整栈替换会把旧三级页留成
                                // leaving 层，在 None 转场下静止盖在新页上直到 500ms 收敛完才
                                // 突然消失。换 key 让它从栈深 1 起步，连收敛过程都不跑 =
                                // 真正的瞬时无动画换页。三级页 push 时栈底不变，key 不变，
                                // 转场照常在右栏内播放。
                                key(detailBaseRoute, detailInstantEpoch) {
                                    NavDisplay(
                                        backStack = settingsDetailStack,
                                        modifier = Modifier.fillMaxSize(),
                                        onBack = { popRoute() },
                                        // 搜索层压/退栈 = 官方 None 瞬时切；三级页 push/pop
                                        // = 右栏内滑入转场（带下层压深 + clipToBounds 不越界）。
                                        transition = if (detailInstantSwap) {
                                            SettingsDetailInstantTransition
                                        } else {
                                            SettingsDetailPaneTransition
                                        },
                                        // 右栏是直角 pane：关掉系统圆角裁切动画与整屏 scrim。
                                        effects = SettingsDetailPaneEffects,
                                    ) {
                                        appEntries()
                                    }
                                }
                            }
                        },
                    )
                } else {
                    settingsPage()
                }
            }
            entry<AppRoute.SettingsDetailEmpty>(swipeDismiss = navSwipeDismiss) {
                SettingsDetailEmptyPane()
            }
            entry<AppRoute.MediaSearch>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.screens.search.MediaSearchScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    // 多选「添加到会话」：选中媒体进当前会话输入区（与系统分享进入同一入口
                    // attachSharedUris，按类型落成图片/视频/文件附件），随后回聊天页看待发附件。
                    onAddToChat = { uris ->
                        agentState.attachSharedUris(uris)
                        popRoute()
                    },
                )
            }
            entry<AppRoute.MediaIndexSettings>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.screens.settings.MediaIndexSettingsScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.MobileActionsSettings>(swipeDismiss = navSwipeDismiss) {
                MobileActionsSettingsScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.MobileActions>(swipeDismiss = navSwipeDismiss) {
                MobileActionsScreen(
                    onOpenSettings = { pushRoute(AppRoute.MobileActionsSettings) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.SettingsSearch>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.SettingsSearchScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    onFocusInSettings = { key ->
                        // 行内控件（开关 / 选择器 / 一次性动作）没有子页：退出搜索页，
                        // 让主页滚到那一行并短暂高亮。
                        settingsFocusKey = key
                        popRoute()
                    },
                )
            }
            entry<AppRoute.SystemTakeover>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.SystemTakeoverSettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.About>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.AboutScreen(
                    onBack = ::popRoute,
                    onOpenCredits = { pushRoute(AppRoute.AboutCredits) },
                )
            }
            entry<AppRoute.AboutCredits>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.AboutCreditsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.AuxiliaryVision>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.ModelFeatureSettingsScreen(
                    feature = io.github.mangi.eta.agent.model.ModelFeature.VISION, onBack = ::popRoute)
            }
            entry<AppRoute.SubAgents>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.SubAgentSettingsScreen(
                    onBack = ::popRoute,
                    onOpenPreset = { presetId -> pushRoute(AppRoute.SubAgentPresetDetail(presetId)) },
                )
            }
            entry<AppRoute.SubAgentPresetDetail>(swipeDismiss = navSwipeDismiss) { route ->
                io.github.mangi.eta.ui.SubAgentPresetDetailScreen(
                    presetId = route.presetId,
                    onBack = ::popRoute,
                    isCurrentRoute = currentRoute == route,
                )
            }
            entry<AppRoute.TitleModel>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.ModelFeatureSettingsScreen(
                    feature = io.github.mangi.eta.agent.model.ModelFeature.TITLE, onBack = ::popRoute)
            }
            entry<AppRoute.ErrorReconnectSettings>(swipeDismiss = navSwipeDismiss) {
                ErrorReconnectSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.LiveActivitySettings>(swipeDismiss = navSwipeDismiss) {
                LiveActivitySettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.WebToolsSettings>(swipeDismiss = navSwipeDismiss) {
                WebToolsSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.VoiceSettings>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(
                    onBack = ::popRoute,
                    onOpenVoiceSubPage = ::openVoiceSubPage,
                )
            }
            // Retain serialized legacy routes, but show the same unified voice surface.
            entry<AppRoute.TtsSettings>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute,
                    initialSection = io.github.mangi.eta.ui.VoiceSettingsSection.READ_ALOUD,
                    onOpenVoiceSubPage = ::openVoiceSubPage)
            }
            entry<AppRoute.SpeechSettings>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(
                    onBack = ::popRoute,
                    onOpenVoiceSubPage = ::openVoiceSubPage,
                )
            }
            entry<AppRoute.VoiceModeSettings>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute,
                    initialSection = io.github.mangi.eta.ui.VoiceSettingsSection.CONVERSATION,
                    onOpenVoiceSubPage = ::openVoiceSubPage)
            }
            entry<AppRoute.DoubaoVoices>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.DoubaoVoiceSettings(onBack = ::popRoute)
            }
            entry<AppRoute.MimoVoices>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.MimoVoicesScreen(onBack = ::popRoute)
            }
            entry<AppRoute.AppearanceSettings>(swipeDismiss = navSwipeDismiss) {
                AppearanceSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.DataBackup>(swipeDismiss = navSwipeDismiss) {
                DataBackupScreen(
                    context = context,
                    onBack = ::popRoute,
                    onExport = agentState::exportBackup,
                    onImport = { input ->
                        val summary = agentState.importBackup(input)
                        appViewModel.refreshKimiWeb()
                        summary
                    },
                    onImportModels = agentState::importModels,
                    onImportAnalysis = agentState::importConversationAnalysis,
                )
            }
            entry<AppRoute.Memory>(swipeDismiss = navSwipeDismiss) {
                AgentMemoryOverviewScreen(
                    onBack = ::popRoute,
                    onOpenAssistant = { assistantId ->
                        pushRoute(AppRoute.AssistantMemory(assistantId))
                    },
                )
            }
            entry<AppRoute.AssistantMemory>(swipeDismiss = navSwipeDismiss) { route ->
                LaunchedEffect(route.assistantId) {
                    agentState.refreshMemory(route.assistantId)
                }
                AgentMemoryScreen(
                    state = agentState.memoryState,
                    onAction = { action ->
                        when (action) {
                            AgentMemoryAction.NavigateBack -> popRoute()
                            is AgentMemoryAction.ToggleEnabled -> agentState.setMemoryEnabled(action.enabled)
                            is AgentMemoryAction.DraftChanged -> agentState.updateMemoryDraft(action.content)
                            AgentMemoryAction.Save -> agentState.saveMemory()
                            AgentMemoryAction.Clear -> agentState.clearMemory()
                            AgentMemoryAction.DismissNotice -> agentState.dismissMemoryNotice()
                        }
                    },
                )
            }
            entry<AppRoute.LinuxEnvironment>(swipeDismiss = navSwipeDismiss) {
                LinuxEnvironmentScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.SharedFolders>(swipeDismiss = navSwipeDismiss) {
                SharedFoldersScreen(
                    context = context,
                    onBack = ::popRoute,
                    onOpenPicker = { pushRoute(AppRoute.SharedFolderPicker) },
                )
            }
            entry<AppRoute.SharedFolderPicker>(swipeDismiss = navSwipeDismiss) {
                SharedFolderPickerScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.LinuxFiles>(swipeDismiss = navSwipeDismiss) { route ->
                LinuxFilesScreen(
                    context = context,
                    distribution = route.distribution,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviders>(swipeDismiss = navSwipeDismiss) {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    currentProviderId = shellHomeState.providerId,
                )
            }
            entry<AppRoute.ModelProviderSearch>(swipeDismiss = navSwipeDismiss) {
                ModelProviderSearchScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    currentProviderId = shellHomeState.providerId,
                )
            }
            entry<AppRoute.McpServers>(swipeDismiss = navSwipeDismiss) {
                McpServersScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServerDetail>(swipeDismiss = navSwipeDismiss) { route ->
                McpServerDetailScreen(
                    serverId = route.serverId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.McpServerAdd>(swipeDismiss = navSwipeDismiss) {
                McpServerAddScreen(
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ToolRecipes>(swipeDismiss = navSwipeDismiss) {
                ToolRecipesScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ToolRecipeEdit>(swipeDismiss = navSwipeDismiss) { route ->
                ToolRecipeEditScreen(
                    recipeName = route.recipeName,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.RemoteAgents>(swipeDismiss = navSwipeDismiss) {
                io.github.mangi.eta.ui.screens.settings.RemoteAgentsScreen(
                    onBack = ::popRoute,
                    coordinator = appViewModel.remoteCoordinator,
                    onOpenEditor = { serverId -> pushRoute(AppRoute.RemoteAgentEditor(serverId)) },
                )
            }
            entry<AppRoute.RemoteAgentEditor>(swipeDismiss = navSwipeDismiss) { route ->
                io.github.mangi.eta.ui.screens.settings.RemoteAgentEditorScreen(
                    serverId = route.serverId,
                    onBack = ::popRoute,
                    coordinator = appViewModel.remoteCoordinator,
                )
            }
            entry<AppRoute.ModelProviderDetail>(swipeDismiss = navSwipeDismiss) { route ->
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute,
                    currentModelId = shellHomeState.modelId,
                    onSelectCurrentModel = { modelId ->
                        agentState.selectModel(modelId, route.providerId)
                    },
                    onEditModel = { modelId, isNew ->
                        pushRoute(AppRoute.ModelProviderModelEdit(route.providerId, modelId, isNew))
                    },
                )
            }
            entry<AppRoute.ContextCompression>(swipeDismiss = navSwipeDismiss) {
                // 纯配置页：立即压缩入口已移到顶栏溢出菜单「压缩会话」。
                ContextCompressionSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.DecisionModel>(swipeDismiss = navSwipeDismiss) {
                DecisionModelSettingsScreen(onBack = ::popRoute)
            }
            entry<AppRoute.Assistants>(swipeDismiss = navSwipeDismiss) { route ->
                AssistantsScreen(
                    picker = route.picker,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                    selectedAssistantId = shellHomeState.assistantId.ifBlank { null },
                    onSelectAssistant = agentState::selectAssistant,
                )
            }
            entry<AppRoute.AssistantSearch>(swipeDismiss = navSwipeDismiss) { route ->
                AssistantSearchScreen(
                    picker = route.picker,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                    selectedAssistantId = shellHomeState.assistantId.ifBlank { null },
                    onSelectAssistant = agentState::selectAssistant,
                )
            }
            entry<AppRoute.AssistantEdit>(swipeDismiss = navSwipeDismiss) { route ->
                AssistantEditScreen(
                    assistantId = route.assistantId,
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderAuthMethod>(swipeDismiss = navSwipeDismiss) { route ->
                ProviderAuthMethodScreen(
                    providerType = route.providerType,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                )
            }
            entry<AppRoute.ModelProviderNew>(swipeDismiss = navSwipeDismiss) { route ->
                ModelProviderDetailScreen(
                    newType = route.providerType,
                    authMode = route.authMode,
                    onBack = ::popRoute
                )
            }
            entry<AppRoute.ModelProviderModelEdit>(swipeDismiss = navSwipeDismiss) { route ->
                ProviderModelEditScreen(
                    providerId = route.providerId,
                    modelId = route.modelId,
                    isNew = route.isNew,
                    onBack = ::popRoute,
                )
            }
        }
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            // 宽屏设置页：主 display 的横移手势/预测式返回只弹主栈，右栏手势由右栏
            // display 的 onBack = popRoute() 接管。两者各驱动自己的栈，不再互相抢。
            onBack = { if (settingsWideActive) popRootRoute() else popRoute() },
            effects = navEffects,
        ) {
            appEntries()
        }
    }

    AgentPendingChildStopDialog(agentState.conversationPaneState.selectedConversationId)

    conversationRenameTarget?.let { conversation ->
        var renameInput by remember(conversation.id) { mutableStateOf(conversation.title) }
        EtaDialog(
            show = true,
            title = stringResource(R.string.conversation_rename_title),
            onDismissRequest = { conversationRenameTarget = null },
            icon = Icons.Rounded.Info,
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_save),
                    confirmEnabled = renameInput.isNotBlank(),
                    onCancel = { conversationRenameTarget = null },
                    onConfirm = {
                        agentState.renameConversation(conversation.id, renameInput)
                        conversationRenameTarget = null
                    },
                )
            },
        ) {
            Column {
                io.github.mangi.eta.ui.adaptive.EtaTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = stringResource(R.string.conversation_rename_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    agentState.contextBudgetPrompt?.takeIf {
        it.conversationId == agentState.conversationPaneState.selectedConversationId
    }?.let { prompt ->
        ContextBudgetPromptDialog(
            reason = prompt.reason,
            onDismiss = agentState::dismissContextBudgetPrompt,
            onCompactThisRun = { agentState.allowCurrentRunCompaction(prompt.runId) },
            onStop = { stopMainReply() },
        )
    }

    if (conversationExportConfirmation) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.conversation_export_title),
            summary = stringResource(R.string.conversation_export_summary),
            onDismissRequest = {
                conversationExportConfirmation = false
                conversationExportId = null
            },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.conversation_export_choose_location),
                    onCancel = {
                        conversationExportConfirmation = false
                        conversationExportId = null
                    },
                    onConfirm = {
                        conversationExportConfirmation = false
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        val title = conversationExportTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40)
                        conversationExportLauncher.launch("Eta-$title-$stamp.zip")
                    },
                )
            },
        ) {
        }
    }

    if (conversationAnalysisExportOptions) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.conversation_analysis_export_title),
            summary = stringResource(R.string.conversation_analysis_export_summary),
            onDismissRequest = {
                conversationAnalysisExportOptions = false
                conversationAnalysisExportId = null
            },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.conversation_export_choose_location),
                    onCancel = {
                        conversationAnalysisExportOptions = false
                        conversationAnalysisExportId = null
                    },
                    onConfirm = {
                        conversationAnalysisExportOptions = false
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        val title = conversationAnalysisExportTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40)
                        conversationAnalysisExportLauncher.launch("Eta-$title-$stamp.json")
                    },
                )
            },
        ) {
            EtaSegmentedCheckboxRow(
                title = stringResource(R.string.conversation_analysis_include_raw_io),
                summary = stringResource(R.string.conversation_analysis_include_raw_io_summary),
                checked = conversationAnalysisIncludeRawIo,
                onCheckedChange = { conversationAnalysisIncludeRawIo = it },
            )
            EtaSegmentedCheckboxRow(
                title = stringResource(R.string.conversation_analysis_include_images),
                summary = stringResource(R.string.conversation_analysis_include_images_summary),
                checked = conversationAnalysisIncludeImages,
                onCheckedChange = { conversationAnalysisIncludeImages = it },
            )
        }
    }

    conversationDeleteTarget?.let { conversation ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.conversation_delete_title),
            summary = stringResource(R.string.conversation_delete_message),
            onDismissRequest = { conversationDeleteTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.conversation_move_to_trash),
                    destructive = true,
                    onCancel = { conversationDeleteTarget = null },
                    onConfirm = {
                        agentState.deleteConversation(conversation.id)
                        conversationDeleteTarget = null
                    },
                )
            },
        ) {
        }
    }

    conversationMoveTarget?.let { conversation ->
        MoveToFolderSheet(
            folders = agentState.conversationPaneState.folders,
            currentFolderId = conversation.folderId,
            onMove = { folderId ->
                agentState.moveConversationToFolder(conversation.id, folderId)
                conversationMoveTarget = null
            },
            onDismiss = { conversationMoveTarget = null },
        )
    }

    if (confirmClearDispatchConsole) {
        // 语义是「归档 + 开新篇」，不是删除：说明消息会整体进回收站，可恢复接回顶部。
        EtaDialog(
            show = true,
            title = "清空主会话",
            summary = "当前上下文会整体移入回收站，主会话从空白继续。之后可在回收站把这段内容恢复并接回顶部。",
            onDismissRequest = { confirmClearDispatchConsole = false },
            actions = {
                EtaDialogActions(
                    confirmText = "清空并归档",
                    destructive = true,
                    onCancel = { confirmClearDispatchConsole = false },
                    onConfirm = {
                        agentState.clearDispatchConsoleContext()
                        confirmClearDispatchConsole = false
                    },
                )
            },
        ) {
        }
    }

    remoteSwitchTarget?.let { target ->
        val serverName = appViewModel.remoteCoordinator.servers
            .find { it.id == target.serverId }?.name.orEmpty()
        EtaDialog(
            show = true,
            title = stringResource(R.string.remote_switch_title),
            summary = stringResource(R.string.remote_switch_message, serverName),
            onDismissRequest = { remoteSwitchTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.remote_switch_confirm),
                    onCancel = { remoteSwitchTarget = null },
                    onConfirm = {
                        agentState.selectRemoteTarget(target)
                        appViewModel.remoteCoordinator.selectRemoteSession(target)
                        remoteSwitchTarget = null
                    },
                )
            },
        ) {
        }
    }

    messageDeleteTarget?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.conversation_delete_message_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_delete_message_body)
            } else {
                pluralStringResource(
                    R.plurals.conversation_delete_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageDeleteTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { messageDeleteTarget = null },
                    onConfirm = {
                        agentState.deleteMessageTurn(target.messageId)
                        messageDeleteTarget = null
                    },
                )
            },
        ) {
        }
    }

    AppUpdateDialog(
        offer = updateOffer,
        currentVersion = currentVersion,
        onDismiss = { AppUpdateCoordinator.dismiss() },
    )

    messageRegenerateTarget?.let { target ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.conversation_regenerate_title),
            summary = if (target.laterTurnCount == 0) {
                stringResource(R.string.conversation_regenerate_current_turn)
            } else {
                pluralStringResource(
                    R.plurals.conversation_regenerate_later_turns,
                    target.laterTurnCount,
                    target.laterTurnCount,
                )
            },
            onDismissRequest = { messageRegenerateTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_regenerate),
                    destructive = true,
                    onCancel = { messageRegenerateTarget = null },
                    onConfirm = {
                        agentState.regenerateMessage(target.messageId)
                        messageRegenerateTarget = null
                    },
                )
            },
        ) {
        }
    }
    } // task controls overlay
    } // selected conversation settings context
}

private data class MessageMutationTarget(
    val messageId: String,
    val laterTurnCount: Int,
)

/**
 * 未登记 route 的最小兜底页。
 *
 * miuix 0.9.4 的 `NavEntryBuilder` 没有 fallback / catch-all（`entry<T>` 只精确匹配 T::class，
 * 未登记的类型在 `build()` 直接 `error(...)`），所以无法在 `NavDisplay` 内部兜底；
 * 宿主在渲染前发现不可渲染条目时改渲染本页。返回一次即把不可渲染的条目从返回栈剔除。
 */
@Composable
private fun UnknownRoutePage(onRecover: () -> Unit) {
    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = true,
        onBackCompleted = onRecover,
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("Unknown page")
    }
}

/**
 * 当前远程会话对应的智能体名字；本机会话返回 `null`。
 *
 * 挂在远程上但配置里查不到名字（服务器记录被删过）时返回**空串**而不是 null：
 * 「空串」与「本机」必须区分开，否则远程会话会列出本机的一整套助手，而切换在远程侧
 * 根本不生效。
 */
private fun remoteAgentNameOf(
    coordinator: io.github.mangi.eta.ui.remote.RemoteCoordinator,
    remote: io.github.mangi.eta.ui.remote.ChatTarget.Remote?,
): String? = remote?.let { target ->
    coordinator.servers.firstOrNull { it.id == target.serverId }?.name?.trim().orEmpty()
}

/**
 * 输入框胶囊的重连/失败状态；正常连接（含首次连接中）返回 `null`。
 * `statusOf` 读的是 Compose 状态映射，在组合里调用即随连接状态实时重组，
 * 不需要额外的订阅管道。
 */
private fun remoteComposerStatusOf(
    coordinator: io.github.mangi.eta.ui.remote.RemoteCoordinator,
    remote: io.github.mangi.eta.ui.remote.ChatTarget.Remote?,
    onOpenSettings: () -> Unit,
): io.github.mangi.eta.ui.remote.RemoteComposerStatus? = remote?.let { target ->
    val status = coordinator.statusOf(target.serverId)
    when (status) {
        is io.github.mangi.eta.agent.remote.RemoteConnectionStatus.Reconnecting,
        is io.github.mangi.eta.agent.remote.RemoteConnectionStatus.Failed,
        ->
            io.github.mangi.eta.ui.remote.RemoteComposerStatus(
                status = status,
                serverName = coordinator.servers.find { it.id == target.serverId }?.name.orEmpty(),
                onRetry = { coordinator.retry(target.serverId) },
                onOpenSettings = onOpenSettings,
            )
        else -> null
    }
}

@Composable
private fun MoveToFolderSheet(
    folders: List<ConversationFolderUi>,
    currentFolderId: String?,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val visible = remember(folders, query) {
        val needle = query.trim()
        if (needle.isEmpty()) folders
        else folders.filter { it.name.contains(needle, ignoreCase = true) }
    }
    EtaPickerSheet(
        title = stringResource(R.string.drawer_move_to_folder),
        onDismiss = onDismiss,
        query = query,
        onQueryChange = { query = it },
        searchPlaceholder = stringResource(R.string.drawer_search_folder),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            MoveFolderOption(
                label = stringResource(R.string.drawer_uncategorized_chip),
                selected = currentFolderId.isNullOrBlank(),
                onClick = { onMove(null) },
            )
            if (visible.isEmpty() && query.isNotBlank()) {
                Text(
                    text = stringResource(R.string.ui_no_matching_options),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            visible.forEach { folder ->
                MoveFolderOption(
                    label = folder.name,
                    selected = currentFolderId == folder.id,
                    onClick = { onMove(folder.id) },
                )
            }
        }
    }
}

private data class ShellHomeState(
    val selectedContextTaskId: String?,
    val isCompressingContext: Boolean,
    val isWaitingForCompression: Boolean,
    val childContexts: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats>,
    val childStatusRoster: List<io.github.mangi.eta.agent.delegation.SubAgentContextStats>,
    val providerId: String,
    val modelId: String,
    val assistantId: String,
    val pendingConversationMentions: List<io.github.mangi.eta.ui.model.PendingConversationMentionUi>,
    val tokenUsage: io.github.mangi.eta.ui.model.ConversationTokenUsageUi,
    val hasMessages: Boolean,
    val isStreaming: Boolean,
    val activeRunId: String?,
)
