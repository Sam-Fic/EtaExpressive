package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.data.repository.ProviderBalanceStore
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceIndicator
import io.github.mangi.eta.ui.pages.providers.activityLifecycleOwnerOrNull
import io.github.mangi.eta.ui.pages.providers.hasBalanceIndicatorContent
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.adaptive.EtaTopBarBackButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ExperimentalMaterial3Api

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏保留历史入口、独立的新建对话按钮与溢出菜单（终端、浏览器），保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    currentConversationTitle: String? = null,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    // 汉堡按钮用：宽屏下是开关（并排展开 / 收回），与「手势打开」的 onOpen 不能混用，
    // 否则抽屉内部回写打开状态时会把开关再翻一次。
    onToggleConversationPane: () -> Unit = onOpenConversationPane,
    onDismissConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    autoCompressEnabled: Boolean = false,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit = {},
    onOpenCompressConversation: () -> Unit = {},
    onOpenSearchHistory: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSubAgentStatus: () -> Unit = {},
    selectedProviderId: String? = null,
    onSelectConversation: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onConversationTogglePin: (ConversationSummaryUi) -> Unit = {},
    onConversationExport: (ConversationSummaryUi) -> Unit = {},
    onConversationAnalysisExport: (ConversationSummaryUi) -> Unit = {},
    onOpenManageChats: () -> Unit = {},
    // 抽屉工具栏里的放大镜：与管理聊天页同一入口（全文搜索页，跨会话检索）。
    onOpenDrawerSearch: () -> Unit = {},
    onOpenManageFolders: () -> Unit = {},
    onSelectFolder: (String?) -> Unit = {},
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    onDeleteFolder: (String) -> Unit = {},
    onRestoreConversation: (String) -> Unit = {},
    onConversationDeleteForever: (String) -> Unit = {},
    onEmptyTrash: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    selectedAssistantId: String = "",
    onSelectAssistant: (String) -> Unit = {},
    onEditAssistant: (String) -> Unit = {},
    onOpenAssistants: () -> Unit = {},
    onOpenUsageStats: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    // App 回到前台时补刷一次余额，并调用 start 以恢复可能已停止的轮询。
    // 这里挂在壳层的 Activity 生命周期上（壳层在每个路由都会组合），
    // 用去抖避免导航导致的观察者重建触发多余请求。
    val balanceContext = LocalContext.current
    val balanceLifecycleOwner = remember(balanceContext) { balanceContext.activityLifecycleOwnerOrNull() }
    val balanceScope = balanceLifecycleOwner?.lifecycleScope
    DisposableEffect(balanceLifecycleOwner) {
        val owner = balanceLifecycleOwner
        val scope = balanceScope
        if (owner == null || scope == null) {
            onDispose { }
        } else {
            ProviderBalanceStore.start(scope)
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastBalanceForegroundRefreshAtMs >= BalanceForegroundRefreshDebounceMs) {
                        lastBalanceForegroundRefreshAtMs = now
                        ProviderBalanceStore.start(scope)
                        ProviderBalanceStore.requestRefresh(scope)
                    }
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    // 二级页的可折叠顶栏走官方 enterAlways 行为，与首页顶栏共用同一条嵌套滚动连接。
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val backdrop = rememberTopBarBackdrop()
    val pageContent: @Composable () -> Unit = {
        Scaffold(
            // Own render node: the drawer push offset must not re-record the page.
            modifier = Modifier.fillMaxSize().graphicsLayer {},
            // 页面背景取官方 surfaceContainer（与顶栏渐变板、二级页 TopAppBar 同色）。
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            // 只取状态栏与刘海的 Top/Horizontal 两侧，且不读 safeDrawing：safeDrawing 是
            // systemBars ∪ displayCutout ∪ ime 的并集，键盘动画期间 ime 逐帧变化会让壳层
            // 每帧重组/重测量；systemBars 与 displayCutout 在键盘起落时恒定，对 Top/Horizontal
            // 两侧的取值与 safeDrawing 完全一致（ime 只有 bottom）。
            contentWindowInsets = WindowInsets.systemBars
                .union(WindowInsets.displayCutout)
                .only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            topBar = {
                if (currentRoute !is AppRoute.Settings) {
                    TopBarBackdrop(backdrop) {
                        // 顶栏菜单「删除当前会话」的可用性：pane.conversations 为全量摘要（未经筛选），
                        // 选中 id 在其中能查到才允许删除；删除走侧栏同一 onConversationDelete 确认弹窗链路。
                        val currentDeletableConversation = conversationPaneState?.let { pane ->
                            pane.selectedConversationId?.let { id -> pane.conversations.find { it.id == id } }
                        }
                        AgentTopBar(
                            route = currentRoute,
                            scrollBehavior = scrollBehavior,
                            onBack = onBack,
                            onOpenConversationPane = onToggleConversationPane,
                            onNewConversation = onNewConversation,
                            onRenameCurrentConversation = {
                                val pane = conversationPaneState
                                val conversation = pane?.selectedConversationId?.let { id ->
                                    pane.conversations.find { it.id == id }
                                }
                                if (conversation != null) onConversationRename(conversation)
                            },
                            onOpenTerminal = onOpenTerminal,
                            onLaunchKimiWeb = onLaunchKimiWeb,
                            kimiWebLabel = kimiWebLabel,
                            canStopKimiWeb = canStopKimiWeb,
                            onStopKimiWeb = onStopKimiWeb,
                            onRefreshKimiWeb = onRefreshKimiWeb,
                            onOpenBrowser = onOpenBrowser,
                            onOpenWorkspace = onOpenWorkspace,
                            onOpenVoiceSettings = onOpenVoiceSettings,
                            currentConversationTitle = currentConversationTitle,
                            autoCompressEnabled = autoCompressEnabled,
                            isCompressingContext = isCompressingContext,
                            onToggleAutoCompress = onToggleAutoCompress,
                            onOpenCompressConversation = onOpenCompressConversation,
                            onOpenSearchHistory = onOpenSearchHistory,
                            onOpenTokenUsage = onOpenTokenUsage,
                            onOpenSubAgentStatus = onOpenSubAgentStatus,
                            canDeleteCurrentConversation = currentDeletableConversation != null,
                            onDeleteCurrentConversation = {
                                currentDeletableConversation?.let(onConversationDelete)
                            },
                            selectedProviderId = selectedProviderId,
                        )
                    }
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .captureForTopBar(backdrop)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                content(padding)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onConversationSelected = onSelectConversation,
                onConversationRename = onConversationRename,
                onConversationDelete = onConversationDelete,
                onMoveConversationToFolder = onMoveConversationToFolder,
                onConversationTogglePin = onConversationTogglePin,
                onConversationExport = onConversationExport,
                onConversationAnalysisExport = onConversationAnalysisExport,
                onOpenSearchHistory = onOpenDrawerSearch,
                onOpenManageChats = onOpenManageChats,
                onOpenManageFolders = onOpenManageFolders,
                onSelectFolder = onSelectFolder,
                onRenameFolder = onRenameFolder,
                onDeleteFolder = onDeleteFolder,
                onRestoreConversation = onRestoreConversation,
                onConversationDeleteForever = onConversationDeleteForever,
                onEmptyTrash = onEmptyTrash,
                onOpenTrash = onOpenTrash,
                selectedAssistantId = selectedAssistantId,
                onSelectAssistant = onSelectAssistant,
                onEditAssistant = onEditAssistant,
                onOpenAssistants = onOpenAssistants,
                onOpenSettings = onOpenSettings,
                onOpenUsageStats = onOpenUsageStats,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTopBar(
    route: AppRoute?,
    currentConversationTitle: String? = null,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onRenameCurrentConversation: () -> Unit = {},
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    autoCompressEnabled: Boolean = false,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit = {},
    onOpenCompressConversation: () -> Unit = {},
    onOpenSearchHistory: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSubAgentStatus: () -> Unit = {},
    canDeleteCurrentConversation: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
    selectedProviderId: String? = null,
) {
    val view = LocalView.current
    val isHome = route is AppRoute.Home
    val navigationIcon: @Composable () -> Unit = {
        if (isHome) {
            IconButton(onClick = {
                TouchHaptics.click(view)
                onOpenConversationPane()
            }) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = stringResource(R.string.action_conversation_history),
                )
            }
        } else {
            EtaTopBarBackButton(
                onClick = onBack,
                contentDescription = stringResource(R.string.action_back),
            )
        }
    }
    val balanceStates by ProviderBalanceStore.states.collectAsState()
    val selectedBalanceState = selectedProviderId?.let(balanceStates::get)
    val actions: @Composable RowScope.() -> Unit = {
        if (isHome) {
            if (hasBalanceIndicatorContent(selectedBalanceState)) {
                // 按 provider 重建，切换供应商时不会把另一个账号的变化当成新扣费播放。
                key(selectedProviderId) {
                    ProviderBalanceIndicator(
                        state = selectedBalanceState,
                        modifier = Modifier.padding(end = 8.dp),
                        animateChanges = true,
                    )
                }
            }
            // 新建对话独立成顶栏按钮，避免每次都要先展开溢出菜单。
            IconButton(onClick = {
                TouchHaptics.click(view)
                onNewConversation()
            }) {
                Icon(
                    imageVector = Icons.Rounded.AddComment,
                    contentDescription = stringResource(R.string.action_new_conversation),
                )
            }
            TopBarOverflowMenu(
                onOpenTerminal = onOpenTerminal,
                onLaunchKimiWeb = onLaunchKimiWeb,
                kimiWebLabel = kimiWebLabel,
                canStopKimiWeb = canStopKimiWeb,
                onStopKimiWeb = onStopKimiWeb,
                onRefreshKimiWeb = onRefreshKimiWeb,
                onOpenBrowser = onOpenBrowser,
                onOpenWorkspace = onOpenWorkspace,
                onOpenVoiceSettings = onOpenVoiceSettings,
                autoCompressEnabled = autoCompressEnabled,
                isCompressingContext = isCompressingContext,
                onToggleAutoCompress = onToggleAutoCompress,
                onOpenCompressConversation = onOpenCompressConversation,
                onOpenSearchHistory = onOpenSearchHistory,
                onOpenTokenUsage = onOpenTokenUsage,
                onOpenSubAgentStatus = onOpenSubAgentStatus,
                canDeleteCurrentConversation = canDeleteCurrentConversation,
                onDeleteCurrentConversation = onDeleteCurrentConversation,
            )
        }
    }

    if (isHome) {
        // 首页顶栏不消费滚动偏移，但壳层内容仍挂在共享 TopAppBarScrollBehavior 的嵌套滚动连接上。
        // 二级页的可折叠顶栏会把 heightOffsetLimit 设成负的折叠区间；
        // 从二级页返回首页后该状态残留在共享 TopAppBarState 上，连接就把「朝最新消息方向」
        // （available.y <= 0）的位移整段吞掉充作折叠高度：表现为只能向上滑、不能向下滚。
        // 这里对首页做复位：区间归零后高度偏移无法移动，连接立刻回到惯性状态，不再吃位移。
        SideEffect {
            scrollBehavior.state.heightOffsetLimit = 0f
            scrollBehavior.state.heightOffset = 0f
        }
        // 首页：菜单 + 会话标题拆分按钮 + 新建对话，一行排开（聊天舞台保持紧凑）。
        Material3HomeTopBar(
            title = titleForRoute(route, currentConversationTitle),
            onOpenConversationPane = onOpenConversationPane,
            onRenameCurrentConversation = onRenameCurrentConversation,
            onNewConversation = onNewConversation,
            onOpenTerminal = onOpenTerminal,
            onLaunchKimiWeb = onLaunchKimiWeb,
            kimiWebLabel = kimiWebLabel,
            canStopKimiWeb = canStopKimiWeb,
            onStopKimiWeb = onStopKimiWeb,
            onOpenBrowser = onOpenBrowser,
            onOpenWorkspace = onOpenWorkspace,
            onOpenVoiceSettings = onOpenVoiceSettings,
            autoCompressEnabled = autoCompressEnabled,
            isCompressingContext = isCompressingContext,
            onToggleAutoCompress = onToggleAutoCompress,
            onOpenCompressConversation = onOpenCompressConversation,
            onOpenSearchHistory = onOpenSearchHistory,
            onOpenTokenUsage = onOpenTokenUsage,
            onOpenSubAgentStatus = onOpenSubAgentStatus,
            canDeleteCurrentConversation = canDeleteCurrentConversation,
            onDeleteCurrentConversation = onDeleteCurrentConversation,
        )
    } else {
        // 二级路由（Chat / Terminal）用官方 TopAppBar：容器色与页面背景同为 surfaceContainer，
        // 标题与图标全部走官方 colorScheme 角色。
        TopAppBar(
            title = {
                Text(
                    text = titleForRoute(route, currentConversationTitle),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

@Composable
private fun titleForRoute(route: AppRoute?, currentConversationTitle: String? = null): String = when (route) {
    is AppRoute.Home -> currentConversationTitle ?: stringResource(R.string.app_name)
    is AppRoute.Chat -> stringResource(R.string.route_chat)
    is AppRoute.Browser -> stringResource(R.string.route_browser)
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.TerminalDaemonTasks -> stringResource(R.string.terminal_daemon_tasks)
    is AppRoute.Tools -> stringResource(R.string.route_tools)
    is AppRoute.AgentTaskPreference -> stringResource(R.string.agent_task_surface_title)
    is AppRoute.VirtualDisplayRecovery -> stringResource(R.string.vd_recovery_title)
    is AppRoute.Haptics -> stringResource(R.string.haptics_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.SettingsSearch -> stringResource(R.string.settings_search_hint)
    is AppRoute.SystemTakeover -> stringResource(R.string.ui_system_assistant_takes_over_f46043)
    is AppRoute.About -> stringResource(R.string.ui_about_bed172)
    is AppRoute.VoiceSettings -> stringResource(R.string.voice_title)
    is AppRoute.DoubaoVoices -> stringResource(R.string.dv_my_voices)
    is AppRoute.MimoVoices -> stringResource(R.string.pv_mimo_voice)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_title)
    is AppRoute.AuxiliaryVision -> stringResource(R.string.auxiliary_vision_title)
    is AppRoute.SubAgents -> stringResource(R.string.settings_subagent_title)
    is AppRoute.SubAgentPresetDetail -> stringResource(R.string.settings_subagent_title)
    is AppRoute.TitleModel -> stringResource(R.string.title_model_title)
    is AppRoute.ErrorReconnectSettings -> stringResource(R.string.error_reconnect_title)
    is AppRoute.TtsSettings -> stringResource(R.string.tts_title)
    is AppRoute.WebToolsSettings -> stringResource(R.string.web_tools_settings_title)
    is AppRoute.VoiceModeSettings -> stringResource(R.string.voice_mode_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.AssistantMemory -> stringResource(R.string.route_memory)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.SharedFolderPicker -> stringResource(R.string.shared_folders_picker_title)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.ModelProviderSearch -> stringResource(R.string.ui_model_provider_e8c7f5)
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.ToolRecipes -> stringResource(R.string.route_tool_recipes)
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderAuthMethod -> stringResource(R.string.provider_auth_method_title)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    is AppRoute.ContextCompression -> stringResource(R.string.route_context_compression)
    is AppRoute.UsageStats -> stringResource(R.string.stats_page_title)
    is AppRoute.ManageChats -> stringResource(R.string.history_page_title)
    is AppRoute.ManageFolders -> stringResource(R.string.folders_page_title)
    is AppRoute.ConversationTrash -> stringResource(R.string.drawer_trash_title)
    is AppRoute.ConversationTrashSearch -> stringResource(R.string.trash_search_title)
    is AppRoute.MessageSearch -> stringResource(R.string.search_history_hint)
    is AppRoute.ConversationTokenUsage -> stringResource(R.string.action_token_usage)
    is AppRoute.SubAgentStatus -> stringResource(R.string.action_sub_agent_status)
    is AppRoute.Assistants -> stringResource(R.string.assistant_list_title)
    is AppRoute.AssistantSearch -> stringResource(R.string.assistant_search)
    is AppRoute.AssistantEdit -> stringResource(R.string.assistant_edit_title)
    is AppRoute.MediaSearch -> stringResource(R.string.media_search_title)
    is AppRoute.MediaIndexSettings -> stringResource(R.string.media_settings_title)
    is AppRoute.MobileActionsSettings -> stringResource(R.string.mobile_actions_settings_title)
    is AppRoute.MobileActions -> stringResource(R.string.mobile_actions_screen_title)
    null -> stringResource(R.string.app_name)
}

private const val BalanceForegroundRefreshDebounceMs = 15_000L

private var lastBalanceForegroundRefreshAtMs = 0L
