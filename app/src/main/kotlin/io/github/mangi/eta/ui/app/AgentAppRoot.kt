package io.github.mangi.eta.ui.app

import android.annotation.SuppressLint
import androidx.compose.runtime.CompositionLocalProvider
import io.github.mangi.eta.ui.components.StreamingMarkdownCache
import io.github.mangi.eta.ui.components.LocalStreamingMarkdownStates
import io.github.mangi.eta.ui.components.LocalChatRouteCovered
import io.github.mangi.eta.ui.components.LocalChatTransitionActive
import io.github.mangi.eta.ui.components.LocalChatUiActive
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceLocationProvider
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
import io.github.mangi.eta.ui.adaptive.EtaSegmentedCheckboxRow
import io.github.mangi.eta.ui.adaptive.EtaPickerSheet
import io.github.mangi.eta.ui.components.LocalShellTopBarInset
import io.github.mangi.eta.ui.HapticsSettingsScreen
import io.github.mangi.eta.ui.ErrorReconnectSettingsScreen
import io.github.mangi.eta.ui.ContextCompressionSettingsScreen
import io.github.mangi.eta.ui.SettingsScreen
import io.github.mangi.eta.ui.components.AppUpdateDialog
import io.github.mangi.eta.ui.components.AgentPendingChildStopDialog
import io.github.mangi.eta.ui.model.AgentChatAction
import io.github.mangi.eta.ui.model.AgentHomeAction
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolGroupSwitch
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.navigation.AgentNavigator
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.navigation.toAppRoute
import io.github.mangi.eta.ui.pages.providers.ModelProviderDetailScreen
import io.github.mangi.eta.ui.pages.providers.ProviderAuthMethodScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderListScreen
import io.github.mangi.eta.ui.pages.providers.ModelProviderSearchScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantEditScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantSearchScreen
import io.github.mangi.eta.ui.screens.assistants.AssistantsScreen
import io.github.mangi.eta.ui.screens.backup.DataBackupScreen
import io.github.mangi.eta.ui.screens.browser.AgentBrowserScreen
import io.github.mangi.eta.ui.screens.chat.AgentChatScreen
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
import io.github.mangi.eta.ui.screens.mcp.McpServersScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryOverviewScreen
import io.github.mangi.eta.ui.screens.memory.AgentMemoryScreen
import io.github.mangi.eta.ui.screens.permissions.PermissionHealthScreen
import io.github.mangi.eta.ui.screens.settings.MobileActionsScreen
import io.github.mangi.eta.ui.screens.settings.MobileActionsSettingsScreen
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

/**
 * Agent App 根组件：持有本地导航栈，并把 Screen actions 交给 [AgentAppState]。
 */
@Composable
@SuppressLint("RestrictedApi") // navController.currentBackStack：无公开的完整返回栈 Flow 替代，仅 collectAsState 观察
fun AgentAppRoot(
    assistantConversationKey: String? = null,
    inboundShareUris: List<String> = emptyList(),
    onAssistantConversationOpened: (Boolean) -> Unit = {},
    onInboundShareConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val navController = rememberNavController()
    val navigator = remember(navController) { AgentNavigator(navController) }
    // 当前返回栈（含导航图条目）：收集后才能在组合期读当前路由，而不是直接读 StateFlow 的值。
    val navEntries by navController.currentBackStack.collectAsState()
    val navRoutes = remember(navEntries) { navEntries.mapNotNull { it.toAppRoute() } }
    val currentRoute = navRoutes.lastOrNull()
    val appViewModel = viewModel<AgentAppViewModel>()
    val agentState = appViewModel.state
    // 常亮只在「对话页可见 + 正在流式回复 + Runtime 还有活着的 run」三者同时成立时挂上；
    // 设置页、空闲会话与纯后台任务不额外保持常亮，GUI 浮层由 Runtime 服务自己维护。
    val runtimeActive by AgentRuntimeService.keepScreenOn.collectAsState()
    val keepScreenOnView = LocalView.current
    val keepScreenOn = (currentRoute is AppRoute.Home || currentRoute is AppRoute.Chat) &&
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
                    io.github.mangi.eta.agent.voice.tts.SpeechPlayback.stopUiBound("pause")
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
    var conversationMoveTarget by remember { mutableStateOf<ConversationSummaryUi?>(null) }
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

    LaunchedEffect(assistantConversationKey) {
        val conversationKey = assistantConversationKey ?: return@LaunchedEffect
        val opened = agentState.openAssistantConversation(conversationKey)
        if (opened) {
            navigator.replace(AppRoute.Chat)
        }
        onAssistantConversationOpened(opened)
    }

    LaunchedEffect(inboundShareUris) {
        if (inboundShareUris.isEmpty()) return@LaunchedEffect
        agentState.attachSharedUris(inboundShareUris)
        navigator.popToHome()
        onInboundShareConsumed()
    }

    fun pushRoute(route: AppRoute) {
        if (route == AppRoute.Browser) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            conversationPaneOpen = false
        }
        navigator.push(route)
    }

    /** 「我的声音」子页统一入口：走真实路由，复用 NavHost 的入栈/出栈转场。 */
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



    fun popRoute() {
        if (navigator.pop()) {
            exitGuard.reset()
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
    }

    /**
     * 回到承载对话的页面（首页或独立会话页）。
     *
     * 搜索命中点开后要直接落在对话里：从管理聊天页进来时栈是
     * `Home → ManageChats → MessageSearch`，只弹一层会停在管理聊天页。
     */
    fun popToConversation() {
        navigator.popToConversationHost()
        exitGuard.reset()
    }

    // NavHost 只在还能出栈时消费返回；根页面必须自己接住，否则系统会直接 finish Activity。
    val interceptExitBack = navRoutes.size <= 1 && !conversationPaneOpen
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
        CompositionLocalProvider(
            // 半遮住时页面还在组合里。保持实时消息，让露出的字继续长；
            // 完全盖住后导航会把这一页移出组合，不再绘制。
            LocalChatUiActive provides true,
            LocalChatRouteCovered provides (currentRoute != route),
        ) {
        AgentAppShell(
            currentRoute = route,
            isCurrentRoute = currentRoute == route,
            conversationPaneState = agentState.conversationPaneState,
            currentConversationTitle = agentState.conversationPaneState.selectedConversationId?.let { id ->
                agentState.conversationPaneState.conversations.find { it.id == id }?.title
            },
            isConversationPaneOpen = conversationPaneOpen,
            onBack = { popRoute() },
            // 手势打开只负责「置为展开」；汉堡按钮单独走 toggle，两者不能共用同一个回调。
            onOpenConversationPane = { conversationPaneOpen = true },
            onToggleConversationPane = { conversationPaneOpen = !conversationPaneOpen },
            onDismissConversationPane = { conversationPaneOpen = false },
            onSearchConversations = { query -> agentState.updateSearchQuery(query) },
            onNewConversation = { createConversation() },
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
            onOpenCompressConversation = { pushRoute(AppRoute.ContextCompression) },
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
            onOpenManageFolders = { pushFromDrawer(AppRoute.ManageFolders) },
            onSelectFolder = { folderId -> agentState.selectFolder(folderId) },
            onRenameFolder = { folderId, name -> agentState.renameFolder(folderId, name) },
            onDeleteFolder = { folderId -> agentState.deleteFolder(folderId) },
            onRestoreConversation = { conversationId -> agentState.restoreConversationFromTrash(conversationId) },
            onConversationDeleteForever = { conversationId -> agentState.deleteConversationForever(conversationId) },
            onEmptyTrash = { agentState.emptyTrash() },
            onOpenTrash = { pushFromDrawer(AppRoute.ConversationTrash) },
            onSelectAssistant = { id -> agentState.selectAssistant(id) },
            onEditAssistant = { id -> pushFromDrawer(AppRoute.AssistantEdit(id)) },
            onOpenAssistants = { pushFromDrawer(AppRoute.Assistants()) },
            onOpenUsageStats = { pushFromDrawer(AppRoute.UsageStats) },
            onOpenSkills = { pushFromDrawer(AppRoute.Skills) },
            onOpenPermissions = { pushFromDrawer(AppRoute.Permissions) },
            onOpenSettings = { pushFromDrawer(AppRoute.Settings) },
            onOpenModelProviders = { pushFromDrawer(AppRoute.ModelProviders) },
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
                        agentState.conversationPaneState.selectedConversationId ?: "new-conversation"
                    ),
                    LocalShellTopBarInset provides if (underlapTopBar) padding.calculateTopPadding() else 0.dp,
                ) {
                    content()
                }
            }
        }
        }
    }

    // 副屏恢复进行中仍然通过这里上报状态。
    var taskRecoveryWorking by remember { mutableStateOf(false) }
    // 转场期间只写 draw-phase 原子量；布尔值在之后的帧发布，且只在变化时发布，
    // 这样滑动不会让图形层逐帧写 snapshot state。
    val navigationActive = remember { mutableStateOf(false) }
    val publishNavigationActivity: (Boolean) -> Unit = remember {
        { active -> if (navigationActive.value != active) navigationActive.value = active }
    }
    val navigationTransition = chatNavigationActivityTransition(publishNavigationActivity)
    DisposableEffect(navigationTransition) {
        onDispose { navigationTransition.cancel() }
    }
    // 每次导航（含同层替换）都按转场时长采样活跃帧，结束后交给采样器的过期看门狗收敛。
    LaunchedEffect(navigationTransition, currentRoute) {
        navigationTransition.sampleWhileAnimating()
    }
    CompositionLocalProvider(
        io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor provides subAgentEditor,
        LocalChatTransitionActive provides navigationActive.value,
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    NavHost(
        navController = navController,
        startDestination = AppRoute.Home,
        modifier = Modifier.fillMaxSize(),
        enterTransition = navigationTransition.enterTransition,
        exitTransition = navigationTransition.exitTransition,
        popEnterTransition = navigationTransition.popEnterTransition,
        popExitTransition = navigationTransition.popExitTransition,
        predictivePopEnterTransition = navigationTransition.predictivePopEnterTransition,
        predictivePopExitTransition = navigationTransition.predictivePopExitTransition,
    ) {
            composable<AppRoute.Home> {
                RoutedShell(route = AppRoute.Home, underlapTopBar = true) {
                    AgentHomeScreen(
                        stateProvider = { agentState.homeState },
                        conversationMentions = io.github.mangi.eta.ui.model.ConversationMentionInputUi(
                            conversations = agentState.conversationPaneState.historyConversations,
                            currentConversationId = agentState.conversationPaneState.selectedConversationId,
                            pending = shellHomeState.pendingConversationMentions,
                            onAttach = agentState::attachConversationMention,
                            onRemove = agentState::removeConversationMention,
                        ),
                        queuedMessages = agentState.queuedMessages,
                        onSteerQueuedMessage = agentState::steerQueuedMessage,
                        onRecallQueuedMessage = agentState::recallQueuedMessage,
                        modelPickerState = agentState.modelPickerState,
                        autoCompressEnabled = agentState.autoCompressEnabled,
                        requestOverheadTokens = agentState.requestOverheadTokens,
                        measuredContextTokens = { agentState.measuredContextTokens },
                        billedOverheadTokens = agentState.billedOverheadTokens,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        draftField = agentState.currentDraftField(),
                        onAction = { action ->
                            when (action) {
                                AgentHomeAction.CycleGptSpeedMode -> agentState.cycleGptSpeedMode()
                                is AgentHomeAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                is AgentHomeAction.ContextTaskSelected -> agentState.selectContextTask(action.taskId, usageConversationId)
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
                                // 长按「助手与模型」入口复用设置页的子代理配置页，不单独做页面。
                                AgentHomeAction.OpenSubAgentSettings -> pushRoute(AppRoute.SubAgents)
                                AgentHomeAction.ExpandRunTrace -> Unit
                            }
                        },
                        isDrawerOpen = conversationPaneOpen,
                        scrollToMessageId = agentState.pendingScrollToMessageId,
                        onScrollToMessageConsumed = agentState::consumePendingScrollToMessage,
                        sentUserMessageEntranceId = agentState.sentUserMessageEntranceId,
                        onSentUserMessageEntranceConsumed = agentState::consumeSentUserMessageEntrance,
                    )
                }
            }
            composable<AppRoute.Chat> {
                RoutedShell(route = AppRoute.Chat, underlapTopBar = true) {
                    AgentChatScreen(
                        stateProvider = { agentState.homeState },
                        conversationMentions = io.github.mangi.eta.ui.model.ConversationMentionInputUi(
                            conversations = agentState.conversationPaneState.historyConversations,
                            currentConversationId = agentState.conversationPaneState.selectedConversationId,
                            pending = shellHomeState.pendingConversationMentions,
                            onAttach = agentState::attachConversationMention,
                            onRemove = agentState::removeConversationMention,
                        ),
                        queuedMessages = agentState.queuedMessages,
                        onSteerQueuedMessage = agentState::steerQueuedMessage,
                        onRecallQueuedMessage = agentState::recallQueuedMessage,
                        modelPickerState = agentState.modelPickerState,
                        autoCompressEnabled = agentState.autoCompressEnabled,
                        requestOverheadTokens = agentState.requestOverheadTokens,
                        measuredContextTokens = { agentState.measuredContextTokens },
                        billedOverheadTokens = agentState.billedOverheadTokens,
                        conversationKey = agentState.conversationPaneState.selectedConversationId,
                        draftField = agentState.currentDraftField(),
                        onAction = { action ->
                            when (action) {
                                AgentChatAction.NavigateBack -> popRoute()
                                AgentChatAction.CycleGptSpeedMode -> agentState.cycleGptSpeedMode()
                                is AgentChatAction.ReasoningEffortChanged ->
                                    agentState.updateReasoningEffort(action.effort)
                                is AgentChatAction.ContextTaskSelected -> agentState.selectContextTask(action.taskId, usageConversationId)
                                is AgentChatAction.ModelSelected -> agentState.selectModel(action.modelId, action.providerId)
                                is AgentChatAction.SubmitMessage -> { requestExecutionNotifications(); agentState.sendCurrentMessage(action.text) }
                                AgentChatAction.StopRun -> agentState.pauseCurrentRun()
                                AgentChatAction.ContinueRun -> agentState.continuePausedGeneration()
                                AgentChatAction.AbortPausedRun -> stopMainReply()
                                is AgentChatAction.AssistantSelected -> agentState.selectAssistant(action.id)
                                AgentChatAction.OpenBrowser -> pushRoute(AppRoute.Browser)
                                is AgentChatAction.EditAssistant -> pushRoute(AppRoute.AssistantEdit(action.id))
                                AgentChatAction.OpenModelProviders -> pushRoute(AppRoute.ModelProviders)
                                AgentChatAction.OpenAssistants -> pushRoute(AppRoute.Assistants())
                                // 长按「助手与模型」入口复用设置页的子代理配置页，不单独做页面。
                                AgentChatAction.OpenSubAgentSettings -> pushRoute(AppRoute.SubAgents)
                                is AgentChatAction.ImageAttached -> agentState.attachImage(action.uri)
                                is AgentChatAction.VideoAttached -> agentState.attachVideo(action.uri)
                                is AgentChatAction.RemoveImage -> agentState.removePendingImage(action.id)
                                is AgentChatAction.FilesAttached -> agentState.attachFiles(action.uris)
                                is AgentChatAction.FolderAttached -> agentState.attachFolder(action.uri)
                                is AgentChatAction.FilePathAttached -> agentState.attachFilePath(action.path)
                                is AgentChatAction.RemoveFileReference ->
                                    agentState.removePendingFileReference(action.id)
                                is AgentChatAction.SteerQueuedMessage -> agentState.steerQueuedMessage(action.id)
                                is AgentChatAction.RecallQueuedMessage -> agentState.recallQueuedMessage(action.id)
                                is AgentChatAction.EditMessage -> agentState.beginMessageEdit(action.id)
                                AgentChatAction.CancelMessageEdit -> agentState.cancelMessageEdit()
                                is AgentChatAction.DeleteMessage -> {
                                    agentState.messageDeleteImpact(action.id)?.let { impact ->
                                        messageDeleteTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentChatAction.RegenerateMessage -> {
                                    val impact = agentState.messageRevisionImpact(action.id)
                                    if (impact?.laterTurnCount == 0) {
                                        agentState.regenerateMessage(action.id)
                                    } else if (impact != null) {
                                        messageRegenerateTarget = MessageMutationTarget(action.id, impact.laterTurnCount)
                                    }
                                }
                                is AgentChatAction.BranchMessage -> agentState.branchConversation(action.id)
                                is AgentChatAction.QuestionDraftChanged -> agentState.updateQuestionDraft(action.conversationId, action.questionId, action.answer)
                                is AgentChatAction.SubmitQuestionAnswer -> agentState.submitQuestionAnswer(action.conversationId, action.questionId)
                            }
                        },
                        scrollToMessageId = agentState.pendingScrollToMessageId,
                        onScrollToMessageConsumed = agentState::consumePendingScrollToMessage,
                        sentUserMessageEntranceId = agentState.sentUserMessageEntranceId,
                        onSentUserMessageEntranceConsumed = agentState::consumeSentUserMessageEntrance,
                    )
                }
            }
            composable<AppRoute.Browser> {
                AgentBrowserScreen(
                    onBack = ::popRoute,
                    conversationId = agentState.conversationPaneState.selectedConversationId,
                )
            }
            composable<AppRoute.Terminal> {
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
            composable<AppRoute.TerminalDaemonTasks> {
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
            composable<AppRoute.Haptics> {
                HapticsSettingsScreen(onBack = ::popRoute)
            }
            composable<AppRoute.AgentTaskPreference> {
                AgentTaskPreferenceScreen(
                    onBack = ::popRoute,
                    onRecoveryWorkingChanged = { taskRecoveryWorking = it },
                )
            }
            // Compatibility alias for a restored old back stack; there is no separate recovery page.
            composable<AppRoute.VirtualDisplayRecovery> {
                AgentTaskPreferenceScreen(
                    onBack = ::popRoute,
                    onRecoveryWorkingChanged = { taskRecoveryWorking = it },
                )
            }
            composable<AppRoute.Tools> {
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
            composable<AppRoute.Skills> {
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
            composable<AppRoute.Permissions> {
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
            composable<AppRoute.SystemEnhance> {
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
            composable<AppRoute.Workspace> {
                WorkspaceScreen(onBack = ::popRoute)
            }
            composable<AppRoute.UsageStats> {
                UsageStatsScreen(onBack = ::popRoute)
            }
            composable<AppRoute.ManageChats> {
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
            composable<AppRoute.ManageFolders> {
                ManageFoldersScreen(
                    folders = agentState.conversationPaneState.folders,
                    conversations = agentState.conversationPaneState.historyConversations,
                    onBack = ::popRoute,
                    onCreateFolder = { name -> agentState.createFolder(name) },
                    onRenameFolder = { folderId, name -> agentState.renameFolder(folderId, name) },
                    onDeleteFolder = { folderId -> agentState.deleteFolder(folderId) },
                )
            }
            composable<AppRoute.ConversationTrash> {
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
            composable<AppRoute.ConversationTrashSearch> {
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
            composable<AppRoute.MessageSearch> { entry ->
                val route = entry.toRoute<AppRoute.MessageSearch>()
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
            composable<AppRoute.ConversationTokenUsage> {
                // 与旧弹窗同源：用量取当前会话的实时投影，页面留在栈上也会随对话继续变化。
                ConversationTokenUsageScreen(
                    usage = cumulativeUsage,
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.SubAgentStatus> {
                // 与旧弹窗同源：取当前会话的实时子助手名册。
                SubAgentStatusScreen(
                    agents = shellHomeState.childStatusRoster,
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.Settings> {
                SettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    currentProviderId = shellHomeState.providerId,
                    currentModelId = shellHomeState.modelId,
                    focusKey = settingsFocusKey,
                    onFocusConsumed = { settingsFocusKey = null },
                )
            }
            composable<AppRoute.MediaSearch> {
                io.github.mangi.eta.ui.screens.search.MediaSearchScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.MediaIndexSettings> {
                io.github.mangi.eta.ui.screens.settings.MediaIndexSettingsScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.MobileActionsSettings> {
                MobileActionsSettingsScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.MobileActions> {
                MobileActionsScreen(
                    onOpenSettings = { pushRoute(AppRoute.MobileActionsSettings) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.SettingsSearch> {
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
            composable<AppRoute.SystemTakeover> {
                io.github.mangi.eta.ui.SystemTakeoverSettingsScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.About> {
                io.github.mangi.eta.ui.AboutScreen(onBack = ::popRoute)
            }
            composable<AppRoute.AuxiliaryVision> {
                io.github.mangi.eta.ui.ModelFeatureSettingsScreen(
                    feature = io.github.mangi.eta.agent.model.ModelFeature.VISION, onBack = ::popRoute)
            }
            composable<AppRoute.SubAgents> {
                io.github.mangi.eta.ui.SubAgentSettingsScreen(
                    onBack = ::popRoute,
                    onOpenPreset = { presetId -> pushRoute(AppRoute.SubAgentPresetDetail(presetId)) },
                )
            }
            composable<AppRoute.SubAgentPresetDetail> { entry ->
                val route = entry.toRoute<AppRoute.SubAgentPresetDetail>()
                io.github.mangi.eta.ui.SubAgentPresetDetailScreen(
                    presetId = route.presetId,
                    onBack = ::popRoute,
                    isCurrentRoute = currentRoute == route,
                )
            }
            composable<AppRoute.TitleModel> {
                io.github.mangi.eta.ui.ModelFeatureSettingsScreen(
                    feature = io.github.mangi.eta.agent.model.ModelFeature.TITLE, onBack = ::popRoute)
            }
            composable<AppRoute.ErrorReconnectSettings> {
                ErrorReconnectSettingsScreen(onBack = ::popRoute)
            }
            composable<AppRoute.WebToolsSettings> {
                WebToolsSettingsScreen(onBack = ::popRoute)
            }
            composable<AppRoute.VoiceSettings> {
                io.github.mangi.eta.ui.VoiceSettingsScreen(
                    onBack = ::popRoute,
                    onOpenVoiceSubPage = ::openVoiceSubPage,
                )
            }
            // Retain serialized legacy routes, but show the same unified voice surface.
            composable<AppRoute.TtsSettings> {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute,
                    initialSection = io.github.mangi.eta.ui.VoiceSettingsSection.READ_ALOUD,
                    onOpenVoiceSubPage = ::openVoiceSubPage)
            }
            composable<AppRoute.SpeechSettings> {
                io.github.mangi.eta.ui.VoiceSettingsScreen(
                    onBack = ::popRoute,
                    onOpenVoiceSubPage = ::openVoiceSubPage,
                )
            }
            composable<AppRoute.VoiceModeSettings> {
                io.github.mangi.eta.ui.VoiceSettingsScreen(onBack = ::popRoute,
                    initialSection = io.github.mangi.eta.ui.VoiceSettingsSection.CONVERSATION,
                    onOpenVoiceSubPage = ::openVoiceSubPage)
            }
            composable<AppRoute.DoubaoVoices> {
                io.github.mangi.eta.ui.DoubaoVoiceSettings(onBack = ::popRoute)
            }
            composable<AppRoute.MimoVoices> {
                io.github.mangi.eta.ui.MimoVoicesScreen(onBack = ::popRoute)
            }
            composable<AppRoute.AppearanceSettings> {
                AppearanceSettingsScreen(onBack = ::popRoute)
            }
            composable<AppRoute.DataBackup> {
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
            composable<AppRoute.Memory> {
                AgentMemoryOverviewScreen(
                    onBack = ::popRoute,
                    onOpenAssistant = { assistantId ->
                        pushRoute(AppRoute.AssistantMemory(assistantId))
                    },
                )
            }
            composable<AppRoute.AssistantMemory> { entry ->
                val route = entry.toRoute<AppRoute.AssistantMemory>()
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
            composable<AppRoute.LinuxEnvironment> {
                LinuxEnvironmentScreen(
                    context = context,
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.SharedFolders> {
                SharedFoldersScreen(
                    context = context,
                    onBack = ::popRoute,
                    onOpenPicker = { pushRoute(AppRoute.SharedFolderPicker) },
                )
            }
            composable<AppRoute.SharedFolderPicker> {
                SharedFolderPickerScreen(
                    context = context,
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.LinuxFiles> { entry ->
                val route = entry.toRoute<AppRoute.LinuxFiles>()
                LinuxFilesScreen(
                    context = context,
                    distribution = route.distribution,
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.ModelProviders> {
                ModelProviderListScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    currentProviderId = shellHomeState.providerId,
                )
            }
            composable<AppRoute.ModelProviderSearch> {
                ModelProviderSearchScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                    currentProviderId = shellHomeState.providerId,
                )
            }
            composable<AppRoute.McpServers> {
                McpServersScreen(
                    onNavigate = { route -> pushRoute(route) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.McpServerDetail> { entry ->
                val route = entry.toRoute<AppRoute.McpServerDetail>()
                McpServerDetailScreen(
                    serverId = route.serverId,
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.ToolRecipes> {
                ToolRecipesScreen(
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.ModelProviderDetail> { entry ->
                val route = entry.toRoute<AppRoute.ModelProviderDetail>()
                ModelProviderDetailScreen(
                    providerId = route.providerId,
                    onBack = ::popRoute,
                    currentModelId = shellHomeState.modelId,
                    onSelectCurrentModel = { modelId ->
                        agentState.selectModel(modelId, route.providerId)
                    },
                )
            }
            composable<AppRoute.ContextCompression> {
                ContextCompressionSettingsScreen(
                    isCompressing = if (shellHomeState.selectedContextTaskId == null)
                        shellHomeState.isCompressingContext || shellHomeState.isWaitingForCompression
                    else shellHomeState.childContexts.any { it.taskId == shellHomeState.selectedContextTaskId &&
                        (it.isCompacting || it.manualCompactionState == "pending") },
                    onBack = ::popRoute,
                    onCompress = { onFinished ->
                        // 模型与端点取本页配置（与自动压缩同一份偏好），不再单独维护手动压缩配置。
                        agentState.compressCurrentConversation(onFinished)
                    },
                )
            }
            composable<AppRoute.Assistants> { entry ->
                val route = entry.toRoute<AppRoute.Assistants>()
                AssistantsScreen(
                    picker = route.picker,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                    selectedAssistantId = shellHomeState.assistantId.ifBlank { null },
                    onSelectAssistant = agentState::selectAssistant,
                )
            }
            composable<AppRoute.AssistantSearch> { entry ->
                val route = entry.toRoute<AppRoute.AssistantSearch>()
                AssistantSearchScreen(
                    picker = route.picker,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                    selectedAssistantId = shellHomeState.assistantId.ifBlank { null },
                    onSelectAssistant = agentState::selectAssistant,
                )
            }
            composable<AppRoute.AssistantEdit> { entry ->
                val route = entry.toRoute<AppRoute.AssistantEdit>()
                AssistantEditScreen(
                    assistantId = route.assistantId,
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.ModelProviderAuthMethod> { entry ->
                val route = entry.toRoute<AppRoute.ModelProviderAuthMethod>()
                ProviderAuthMethodScreen(
                    providerType = route.providerType,
                    onNavigate = { destination -> pushRoute(destination) },
                    onBack = ::popRoute,
                )
            }
            composable<AppRoute.ModelProviderNew> { entry ->
                val route = entry.toRoute<AppRoute.ModelProviderNew>()
                ModelProviderDetailScreen(
                    newType = route.providerType,
                    authMode = route.authMode,
                    onBack = ::popRoute
                )
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
)
