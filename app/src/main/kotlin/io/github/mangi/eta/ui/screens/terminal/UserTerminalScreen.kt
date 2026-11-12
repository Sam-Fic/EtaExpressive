package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.label
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.ui.adaptive.EtaConnectedGroupRow
import io.github.mangi.eta.ui.adaptive.EtaConnectedIconAction
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.rememberCopyToClipboard
import io.github.mangi.eta.ui.app.TerminalBlockUi
import io.github.mangi.eta.ui.app.UserTerminalStore
import io.github.mangi.eta.ui.app.UserTerminalUiState
import io.github.mangi.eta.ui.components.TERMINAL_RENDER_WINDOW_CHARS
import io.github.mangi.eta.ui.components.ansiPlainText
import io.github.mangi.eta.ui.components.ansiToAnnotatedString
import io.github.mangi.eta.ui.components.horizontalScrollEdgeFade
import io.github.mangi.eta.ui.components.terminalRenderWindow

/** 快捷命令 chips（shell 命令本身不翻译）：点击填入输入框，不直接执行。 */
private val TerminalQuickCommands = listOf("ls", "pwd", "df -h", "free -h", "ps aux", "whoami")

/**
 * 用户手动终端：块式输出（命令、输出、退出码），给人用；与 AI 工具调用的任务模型分开。
 * 会话由 [UserTerminalStore] 持有，离开页面后正在运行的命令仍在常驻会话里继续。
 */
@Composable
internal fun UserTerminalScreen(
    store: UserTerminalStore,
    onOpenEnvironment: () -> Unit,
    onOpenDaemonTasks: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
) {
    val state by store.uiState.collectAsState()
    var input by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // 官方 SnackbarHost：复制 / 停止 / 切换环境等操作反馈（原来这些动作是完全静默的）。
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }

    // 从安装页返回后刷新 Linux 就绪态，引导页才会自动让位给终端；守护任务状态一并刷新。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                store.refreshLinuxReady()
                store.refreshDaemonTasks()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val showLinuxGuide = state.environment.isLinux && !state.linuxReady

    fun submit() {
        val command = input.trim()
        if (command.isEmpty()) return
        // 运行中输入发给前台进程 stdin，否则作为新命令执行。
        if (state.running) {
            store.sendInput(command)
        } else {
            store.send(command)
        }
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(terminalRootSurface())
            .imePadding(),
        // 导航栏留白不垫在整页上：底栏 Surface 铺到屏幕底缘，由底栏自身把内容抬到手势条上方。
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                showLinuxGuide -> LinuxGuide(onOpenEnvironment = onOpenEnvironment)
                state.blocks.isEmpty() -> EmptyHint(state.failMessage)
                else -> BlockList(
                    blocks = state.blocks,
                    onReinput = { input = it },
                    onMessage = showMessage,
                )
            }
            // 官方 Snackbar 浮在列表底部、位于底部操作区之上（官方推荐位置）。
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            )
        }
        if (showLinuxGuide) {
            // 引导态仍保留环境切换：已切到未安装的 Linux 时，用户必须能切回 Android，
            // 否则会困在引导页（引导页本身没有任何环境入口）。
            EnvironmentSwitchBar(
                environment = state.environment,
                linuxEnvironment = state.linuxEnvironment,
                onSwitchEnvironment = store::switchEnvironment,
                onMessage = showMessage,
            )
        } else {
            TerminalBottomBar(
                state = state,
                input = input,
                onSwitchEnvironment = store::switchEnvironment,
                onStop = store::stop,
                onOpenTasks = {
                    store.refreshDaemonTasks()
                    onOpenDaemonTasks()
                },
                onOpenSessions = { showSessions = true },
                onOpenConsole = onOpenConsole,
                onInputChange = { input = it },
                onSubmit = ::submit,
                onMessage = showMessage,
            )
        }
    }

    if (showSessions) {
        SessionListDialog(
            rows = state.sessions.map { session ->
                SessionDialogRow(
                    id = session.id,
                    environment = session.environment,
                    subtitle = session.cwd,
                    active = session.id == state.activeSessionId,
                    running = session.running,
                    alive = session.alive,
                )
            },
            onDismiss = { showSessions = false },
            onSelect = store::switchSession,
            onRestart = store::restartSession,
            onClose = store::closeSession,
            onNew = store::newSession,
        )
    }
}

/**
 * 引导态底栏：只放环境切换，让用户能从「已切到但未安装的 Linux」切回 Android。
 * 与 [TerminalBottomBar] 用同一颗 [Surface]（tonalElevation）保持海拔一致。
 */
@Composable
private fun EnvironmentSwitchBar(
    environment: TerminalEnvironment,
    linuxEnvironment: TerminalEnvironment,
    onSwitchEnvironment: (TerminalEnvironment) -> Unit,
    onMessage: (String) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalEnvironmentSelector(
                environment = environment,
                linuxEnvironment = linuxEnvironment,
                onSwitchEnvironment = onSwitchEnvironment,
                onMessage = onMessage,
            )
        }
    }
}

/** 环境切换控件：Android / Linux 分段选择，切换后给出反馈文案（引导态与底栏共用）。 */
@Composable
private fun TerminalEnvironmentSelector(
    environment: TerminalEnvironment,
    linuxEnvironment: TerminalEnvironment,
    onSwitchEnvironment: (TerminalEnvironment) -> Unit,
    onMessage: (String) -> Unit,
) {
    // stringResource 是 @Composable，进不了 onSelect 回调；两条切换文案在组合期备好。
    val androidSwitchedMessage =
        stringResource(R.string.terminal_env_switched, TerminalEnvironment.ANDROID.label())
    val linuxSwitchedMessage =
        stringResource(R.string.terminal_env_switched, linuxEnvironment.label())
    TerminalEnvironmentTabs(
        labels = listOf("Android", linuxEnvironment.label()),
        selectedIndex = if (environment == TerminalEnvironment.ANDROID) 0 else 1,
        onSelect = { index ->
            val target = if (index == 0) TerminalEnvironment.ANDROID else linuxEnvironment
            onSwitchEnvironment(target)
            onMessage(if (index == 0) androidSwitchedMessage else linuxSwitchedMessage)
        },
    )
}

@Composable
private fun BlockList(
    blocks: List<TerminalBlockUi>,
    onReinput: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible >= layoutInfo.totalItemsCount - 1
        }
    }
    // 输出增长只在用户本来就停留在底部时跟随，向上翻历史不被打断。
    LaunchedEffect(blocks.size, blocks.lastOrNull()?.output?.length) {
        if (atBottom && blocks.isNotEmpty()) {
            listState.scrollToItem(blocks.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        // 官方基础布局间距：块与块之间留出 8dp，卡片不再互相贴边。
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = blocks, key = { it.id }) { block ->
            if (block.isSystem) {
                SystemBlock(block)
            } else {
                CommandBlock(block = block, onReinput = onReinput, onMessage = onMessage)
            }
        }
    }
}

/**
 * 命令块：官方滑动容器 [SwipeToDismissBox] + 官方分段列表（[EtaSegmentedColumn] /
 * [EtaSegmentedRow]，即 Usage stats、供应商页同款分段语言）。
 *
 * - 右滑 = 复用命令（填入输入框），左滑 = 复制命令；`confirmValueChange` 返回 false，
 *   动作做完条目自动回弹——命令块由 store 持有，不能被真滑掉。
 * - 长按弹出官方 M3E 动作菜单（复制命令 / 复制输出 / 复用命令），
 *   锚定在命令块本身，不使用底部 Bottom Sheet。
 * - 失败态不再手绘色条：退出码用官方 `Surface` 状态标签（errorContainer）呈现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandBlock(
    block: TerminalBlockUi,
    onReinput: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    var showActions by remember { mutableStateOf(false) }
    val copyToClipboard = rememberCopyToClipboard()
    val exitCode = block.exitCode
    val failed = exitCode != null && exitCode != 0
    val copiedCommandMessage = stringResource(R.string.terminal_copied_command)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                // 右滑：把命令放回输入框，方便改参数再跑。
                SwipeToDismissBoxValue.StartToEnd -> {
                    onReinput(block.command)
                    false
                }
                // 左滑：复制命令到剪贴板。
                SwipeToDismissBoxValue.EndToStart -> {
                    copyToClipboard(block.command)
                    onMessage(copiedCommandMessage)
                    false
                }
                SwipeToDismissBoxValue.Settled -> true
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.fillMaxWidth(),
        backgroundContent = { SwipeActionBackground(direction = dismissState.dismissDirection) },
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                item(key = block.id) {
                    // 点击只承担官方涟漪（终端块没有“按下就去哪”的语义），
                    // 长按打开官方 M3E 动作菜单；官方 SegmentedListItem 自带按压形变与选中态配色。
                    EtaSegmentedRow(
                        onClick = {},
                        onLongClick = { showActions = true },
                        onLongClickLabel = stringResource(R.string.action_terminal_menu),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TerminalMonoText(text = blockHeader(block))
                            if (block.output.isNotEmpty()) {
                                // 原始输出含 ANSI 序列；整段重解析保证流式截断的序列在下一次到达后恢复。
                                // 渲染只取尾部窗口：整段喂给一个 Text 的排版成本随输出长度增长，
                                // 流式期间每帧全量重排；更早的输出仍可通过动作菜单复制。
                                val parsedOutput = remember(block.output) {
                                    ansiToAnnotatedString(terminalRenderWindow(block.output))
                                }
                                TerminalMonoText(
                                    text = parsedOutput,
                                    color = terminalMutedForeground(),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            if (block.running) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 6.dp),
                                ) {
                                    TerminalBusyIndicator(size = 16.dp)
                                    TerminalMonoText(
                                        text = AnnotatedString(stringResource(R.string.terminal_running)),
                                        color = terminalMutedForeground(),
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            if (failed) {
                                // 退出码：官方 Surface 做状态标签（errorContainer + shapes.extraSmall）。
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                                    shape = MaterialTheme.shapes.extraSmall,
                                    modifier = Modifier.padding(top = 6.dp),
                                ) {
                                    TerminalMonoText(
                                        text = AnnotatedString(
                                            stringResource(R.string.terminal_exit_code, exitCode),
                                        ),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            // 超出渲染窗口的部分（store 截断或仅渲染窗口丢弃）对用户一样不可见。
                            if (block.truncated || block.output.length > TERMINAL_RENDER_WINDOW_CHARS) {
                                TerminalMonoText(
                                    text = AnnotatedString(stringResource(R.string.terminal_output_truncated)),
                                    color = terminalMutedForeground(),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            BlockActionMenu(
                expanded = showActions,
                block = block,
                onDismiss = { showActions = false },
                onReinput = onReinput,
                onMessage = onMessage,
            )
        }
    }
}

/** 滑动时露出的官方角色色背景 + 图标提示（右滑复用 / 左滑复制）。 */
@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue) {
    val container: Color
    val content: Color
    val icon: ImageVector?
    val alignment: Alignment
    when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> {
            container = MaterialTheme.colorScheme.primaryContainer
            content = MaterialTheme.colorScheme.onPrimaryContainer
            icon = Icons.Rounded.Refresh
            alignment = Alignment.CenterStart
        }
        SwipeToDismissBoxValue.EndToStart -> {
            container = MaterialTheme.colorScheme.secondaryContainer
            content = MaterialTheme.colorScheme.onSecondaryContainer
            icon = Icons.Rounded.ContentCopy
            alignment = Alignment.CenterEnd
        }
        SwipeToDismissBoxValue.Settled -> {
            container = Color.Transparent
            content = Color.Transparent
            icon = null
            alignment = Alignment.Center
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(container)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = content,
            )
        }
    }
}

@Composable
private fun blockHeader(block: TerminalBlockUi): AnnotatedString {
    val dim = terminalMutedForeground()
    val accent = terminalAccentColor()
    val body = terminalForeground()
    return remember(block.cwdAtStart, block.command, dim, accent, body) {
        buildAnnotatedString {
            if (block.cwdAtStart.isNotEmpty()) {
                withStyle(SpanStyle(color = dim)) { append(block.cwdAtStart) }
                append("  ")
            }
            withStyle(SpanStyle(color = accent)) { append("❯") }
            append(" ")
            withStyle(SpanStyle(color = body, fontWeight = FontWeight.Medium)) { append(block.command) }
        }
    }
}

/**
 * 命令块动作菜单。
 *
 * M3E：官方弹出窗口（[EtaMenuPopup]）锚定在所挂的 Box（命令块）展开；容器用官方的
 * [EtaMenuGroup]（动作菜单补档纵向留白），条目用官方 [DropdownMenuItem] + `EtaMenuItemShape`
 * 分段形状，行高 / 字阶 / 颜色全用官方默认值。
 */
@Composable
private fun BlockActionMenu(
    expanded: Boolean,
    block: TerminalBlockUi,
    onDismiss: () -> Unit,
    onReinput: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val view = LocalView.current
    val copyToClipboard = rememberCopyToClipboard()
    val copiedCommandMessage = stringResource(R.string.terminal_copied_command)
    val copiedOutputMessage = stringResource(R.string.terminal_copied_output)
    val itemCount = 3
    EtaMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
    ) {
        EtaMenuGroup(index = 0, count = 1) {
            BlockActionItem(
                icon = Icons.Rounded.ContentCopy,
                text = stringResource(R.string.terminal_copy_command),
                shapeIndex = 0,
                itemCount = itemCount,
            ) {
                TouchHaptics.click(view)
                copyToClipboard(block.command)
                onMessage(copiedCommandMessage)
                onDismiss()
            }
            BlockActionItem(
                icon = Icons.Rounded.Description,
                text = stringResource(R.string.terminal_copy_output),
                shapeIndex = 1,
                itemCount = itemCount,
                enabled = block.output.isNotEmpty(),
            ) {
                TouchHaptics.click(view)
                copyToClipboard(ansiPlainText(block.output))
                onMessage(copiedOutputMessage)
                onDismiss()
            }
            BlockActionItem(
                icon = Icons.Rounded.Refresh,
                text = stringResource(R.string.terminal_reinput),
                shapeIndex = 2,
                itemCount = itemCount,
            ) {
                TouchHaptics.click(view)
                onReinput(block.command)
                onDismiss()
            }
        }
    }
}

/** 动作菜单行：官方 [DropdownMenuItem]（自带涟漪、禁用态），图标用官方 leading 槽位。 */
@Composable
private fun BlockActionItem(
    icon: ImageVector,
    text: String,
    shapeIndex: Int,
    itemCount: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        onClick = onClick,
        enabled = enabled,
        text = { M3Text(text) },
        shape = EtaMenuItemShape(index = shapeIndex, count = itemCount),
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
            )
        },
    )
}

/**
 * 系统消息（会话开始 / 重连 / 停止等）。
 *
 * M3E：官方 [Surface] 做居中状态药丸（surfaceContainerHighest + shapes.extraSmall），
 * 与命令块卡片区分开，不再是一行贴在列表里的裸文字。
 */
@Composable
private fun SystemBlock(block: TerminalBlockUi) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            M3Text(
                text = block.output,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 底部操作区（控制 + 输入）。
 *
 * M3E：官方 [Surface]（tonalElevation）把环境切换、工作目录、操作与输入行收进同一海拔层，
 * 与上方终端输出拉开官方层级；内部分三行——环境切换 + 停止、工作目录药丸 + 操作图标、
 * 官方 [InputRow]，避免旧版单行里路径被挤成 "/data/us…"。
 */
@Composable
private fun TerminalBottomBar(
    state: UserTerminalUiState,
    input: String,
    onSwitchEnvironment: (TerminalEnvironment) -> Unit,
    onStop: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenConsole: (() -> Unit)? = null,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val view = LocalView.current
    // 官方 Badge 数据源：运行中的后台任务数（原状态对这些完全静默）。
    val runningTaskCount = state.daemonTasks.count { it.running }
    val stoppedMessage = stringResource(R.string.terminal_stopped)
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Surface 背景延伸到屏幕底缘，控件本体避开手势条。
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TerminalEnvironmentSelector(
                    environment = state.environment,
                    linuxEnvironment = state.linuxEnvironment,
                    onSwitchEnvironment = onSwitchEnvironment,
                    onMessage = onMessage,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (state.running) {
                    // TerminalTextButton 自带轻触反馈，这里只补业务动作与反馈文案。
                    TerminalTextButton(
                        text = stringResource(R.string.terminal_stop),
                        onClick = {
                            onStop()
                            onMessage(stoppedMessage)
                        },
                        destructive = true,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 工作目录：官方 Surface 做等宽药丸，超宽时由官方省略号截断。
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                ) {
                    TerminalMonoText(
                        text = AnnotatedString(state.cwd),
                        color = terminalMutedForeground(),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                // 会话 / 后台任务（+ 控制台）：M3E 官方连体图标按钮组（对话历史侧边栏底部
                // 工具行同款）。EtaConnectedGroupRow 出官方连体间距，EtaConnectedIconAction 按
                // 首/中/末位取 ButtonGroupDefaults 连体形状；徽标仍走官方 BadgedBox。
                val sessionsDescription = stringResource(R.string.terminal_sessions)
                val tasksDescription = stringResource(R.string.terminal_daemon_tasks)
                val consoleDescription = stringResource(R.string.terminal_console_mode)
                val actionCount = 2 + if (onOpenConsole != null) 1 else 0
                EtaConnectedGroupRow {
                    BadgedBox(
                        badge = {
                            if (state.sessions.isNotEmpty()) {
                                Badge { M3Text("${state.sessions.size}") }
                            }
                        },
                    ) {
                        EtaConnectedIconAction(
                            onClick = {
                                TouchHaptics.click(view)
                                onOpenSessions()
                            },
                            enabled = true,
                            index = 0,
                            count = actionCount,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Layers,
                                contentDescription = sessionsDescription,
                                modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                            )
                        }
                    }
                    BadgedBox(
                        badge = {
                            if (runningTaskCount > 0) {
                                Badge { M3Text("$runningTaskCount") }
                            }
                        },
                    ) {
                        EtaConnectedIconAction(
                            onClick = {
                                TouchHaptics.click(view)
                                onOpenTasks()
                            },
                            enabled = true,
                            index = 1,
                            count = actionCount,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Insights,
                                contentDescription = tasksDescription,
                                modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                            )
                        }
                    }
                    if (onOpenConsole != null) {
                        EtaConnectedIconAction(
                            onClick = {
                                TouchHaptics.click(view)
                                onOpenConsole()
                            },
                            enabled = true,
                            index = 2,
                            count = actionCount,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = consoleDescription,
                                modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
                            )
                        }
                    }
                }
            }
            // 快捷命令：官方 AssistChip 一行，仅在输入为空时出现，点击填入输入框（不直接执行）。
            if (input.isBlank()) {
                val quickCommandScrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 两端截断淡出带：命令行横向滚动时，被视口切一半的胶囊用 DstIn 蒙版擦掉。
                        .horizontalScrollEdgeFade(quickCommandScrollState)
                        .horizontalScroll(quickCommandScrollState)
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TerminalQuickCommands.forEach { command ->
                        AssistChip(
                            onClick = {
                                TouchHaptics.click(view)
                                onInputChange(command)
                            },
                            label = {
                                M3Text(
                                    text = command,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }
            InputRow(
                input = input,
                running = state.running,
                onInputChange = onInputChange,
                onSubmit = onSubmit,
            )
        }
    }
}

@Composable
private fun InputRow(
    input: String,
    running: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val canSend = input.isNotBlank()
    val hint = stringResource(
        if (running) R.string.terminal_input_running_hint else R.string.terminal_input_hint,
    )
    val sendDescription = stringResource(R.string.terminal_send)
    val view = LocalView.current
    // 官方 OutlinedTextField + 官方 IconButton（发送）；发送键不加容器底板，仅保留箭头图标。
    OutlinedTextField(
        value = input,
        onValueChange = onInputChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
        // 只留 placeholder：label 与 placeholder 同文案时聚焦会出现两遍相同文字。
        placeholder = { M3Text(hint) },
        // 官方 prefix 槽位：命令提示符 $，运行中改用 > 表示输入会发给前台进程 stdin。
        prefix = {
            M3Text(
                text = if (running) ">" else "$",
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = terminalAccentColor(),
            )
        },
        trailingIcon = {
            EtaTooltip(text = sendDescription) {
                IconButton(
                    onClick = {
                        TouchHaptics.click(view)
                        onSubmit()
                    },
                    enabled = canSend,
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = sendDescription,
                        tint = if (canSend) terminalAccentColor() else terminalMutedForeground(),
                    )
                }
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        maxLines = 4,
    )
}

/**
 * 引导态 / 空态：M3E 用官方图标 + 官方正文 + 官方主按钮（filled）组成空状态，
 * 不再是一段裸文字挂一个 TextButton。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LinuxGuide(onOpenEnvironment: () -> Unit) {
    val view = LocalView.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TerminalEmptyIcon()
        TerminalBodyText(
            text = stringResource(R.string.terminal_linux_not_ready),
            color = terminalMutedForeground(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = {
                TouchHaptics.click(view)
                onOpenEnvironment()
            },
            modifier = Modifier.padding(top = 16.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            M3Text(stringResource(R.string.terminal_open_environment))
        }
    }
}

@Composable
private fun EmptyHint(message: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TerminalEmptyIcon()
        TerminalBodyText(
            text = message ?: stringResource(R.string.terminal_empty_hint),
            color = terminalMutedForeground(),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** 空态图标：官方 Icon + 官方 onSurfaceVariant 角色色。 */
@Composable
private fun TerminalEmptyIcon() {
    Icon(
        imageVector = Icons.Rounded.Terminal,
        contentDescription = null,
        modifier = Modifier.size(IconButtonDefaults.extraLargeIconSize),
        tint = terminalMutedForeground(),
    )
}
