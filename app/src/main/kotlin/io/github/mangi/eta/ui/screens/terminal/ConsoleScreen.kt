package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalView
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.label
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.ui.app.ConsoleStore
import io.github.mangi.eta.ui.app.ConsoleUiState
import io.github.mangi.eta.ui.app.UserTerminalStore
import io.github.mangi.eta.ui.components.FooterTooltipBox
import io.github.mangi.eta.ui.components.toSpanStyle
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize

/** 终端界面的根背景色。 */
@Composable
internal fun terminalRootSurface(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.surface

/** 终端正文前景色。 */
@Composable
internal fun terminalForeground(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.onSurface

/** 终端次要前景色（摘要、提示）。 */
@Composable
internal fun terminalMutedForeground(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.onSurfaceVariant

/** 终端强调色。 */
@Composable
internal fun terminalAccentColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.primary

/** 终端错误/失败态前景色。 */
@Composable
internal fun terminalErrorColor(): androidx.compose.ui.graphics.Color =
    MaterialTheme.colorScheme.error

/** 终端等宽字符网格的字阶：官方 bodySmall + 等宽字族。 */
@Composable
internal fun terminalGridTextStyle(): TextStyle =
    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/** 终端等宽正文块（官方 Text）。 */
@Composable
internal fun TerminalMonoText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = terminalForeground(),
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    val style = terminalGridTextStyle()
    M3Text(
        text = text,
        modifier = modifier,
        style = style,
        color = color,
        softWrap = softWrap,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 终端进行中指示：官方 M3E LoadingIndicator（morph 变形，尺寸收敛进传入槽位）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TerminalBusyIndicator(size: androidx.compose.ui.unit.Dp) {
    androidx.compose.material3.LoadingIndicator(modifier = Modifier.size(size))
}

/** 终端日志/正文视图：官方 Text + bodyLarge。 */
@Composable
internal fun TerminalBodyText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = terminalForeground(),
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    M3Text(
        text = text,
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 环境切换标签（Android / Linux）。
 *
 * M3：官方 `SingleChoiceSegmentedButtonRow` + `SegmentedButton`，自带选中语义、涟漪与
 * 官方连接形状（`SegmentedButtonDefaults.itemShape`）。
 */
@Composable
internal fun TerminalEnvironmentTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = {
                    TouchHaptics.click(view)
                    onSelect(index)
                },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
            ) {
                M3Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** 终端状态栏图标按钮：M3 用官方图标按钮（容器/图标尺寸取 `IconButtonDefaults` 令牌）。 */
@Composable
internal fun TerminalIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    // 会话切换 / 守护任务等图标动作不自明，长按补官方 TooltipBox 提示。
    FooterTooltipBox(text = contentDescription, enabled = true) {
        EtaIconButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = {
                TouchHaptics.click(view)
                onClick()
            },
            modifier = modifier,
            size = EtaIconSize.XS,
            style = EtaButtonStyle.Standard,
        )
    }
}

/**
 * 终端状态栏/浮层里的文字按钮。
 *
 * M3：官方 `TextButton`（默认胶囊 + `labelLarge`）；破坏性动作用官方
 * `ButtonDefaults.textButtonColors` 搭配 `errorContainer/onErrorContainer` 角色色。
 */
@Composable
internal fun TerminalTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val view = LocalView.current
    M3TextButton(
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
        modifier = modifier,
        enabled = enabled,
        colors = if (destructive) {
            M3ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            M3ButtonDefaults.textButtonColors()
        },
    ) {
        M3Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 终端入口：块式终端为本体；PTY 可用时状态栏提供控制台模式切换。 */
@Composable
internal fun TerminalEntryScreen(
    terminalStore: UserTerminalStore,
    consoleStore: ConsoleStore,
    onOpenEnvironment: () -> Unit,
    onOpenDaemonTasks: () -> Unit,
) {
    val consoleState by consoleStore.uiState.collectAsState()
    var consoleMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        terminalStore.refreshLinuxReady()
        consoleStore.refreshLinuxEnvironment()
        consoleStore.probePtySupport()
    }
    when {
        consoleMode && consoleState.ptySupported == true -> ConsoleScreen(
            store = consoleStore,
            terminalStore = terminalStore,
            onExitConsole = { consoleMode = false },
            onOpenEnvironment = onOpenEnvironment,
            onOpenDaemonTasks = onOpenDaemonTasks,
        )
        else -> UserTerminalScreen(
            store = terminalStore,
            onOpenEnvironment = onOpenEnvironment,
            onOpenDaemonTasks = onOpenDaemonTasks,
            onOpenConsole = if (consoleState.ptySupported == true) {
                { consoleMode = true }
            } else {
                null
            },
        )
    }
}

/**
 * 控制台：PTY 全屏视图，面向 TUI 与交互式 CLI。
 * 网格渲染按行复用（Line.id + version）；软键盘经隐藏输入框捕获，特殊键由键条补齐。
 */
@Composable
internal fun ConsoleScreen(
    store: ConsoleStore,
    terminalStore: UserTerminalStore,
    onExitConsole: () -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenDaemonTasks: () -> Unit,
) {
    val state by store.uiState.collectAsState()
    var showSessions by remember { mutableStateOf(false) }
    var ctrlActive by remember { mutableStateOf(false) }
    var fieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(terminalRootSurface())
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ConsoleStatusBar(
            environment = state.environment,
            linuxEnvironment = state.linuxEnvironment,
            onSwitchEnvironment = store::switchEnvironment,
            onOpenTasks = {
                terminalStore.refreshDaemonTasks()
                onOpenDaemonTasks()
            },
            onOpenSessions = { showSessions = true },
            onExitConsole = onExitConsole,
        )
        ConsoleGrid(
            state = state,
            store = store,
            focusRequester = focusRequester,
            fieldValue = fieldValue,
            onFieldChange = { newValue ->
                val inserted = diffInserted(fieldValue.text, newValue.text)
                if (inserted.isNotEmpty()) {
                    val bytes = if (ctrlActive && inserted.length == 1 && inserted[0].isLetter()) {
                        ctrlActive = false
                        (inserted[0].lowercaseChar().code and 0x1F).toChar().toString()
                    } else {
                        inserted
                    }
                    store.write(bytes.replace("\n", "\r"))
                }
                val deleted = diffDeleted(fieldValue.text, newValue.text)
                repeat(deleted) { store.write("\u007F") }
                fieldValue = if (newValue.text.length > 64) TextFieldValue("") else newValue
            },
            onOpenEnvironment = onOpenEnvironment,
        )
        ConsoleKeyBar(
            ctrlActive = ctrlActive,
            onCtrl = { ctrlActive = !ctrlActive },
            onKey = { store.write(it) },
        )
    }

    if (showSessions) {
        SessionListDialog(
            rows = state.sessions.map { session ->
                SessionDialogRow(
                    id = session.id,
                    environment = session.environment,
                    subtitle = "",
                    active = session.id == state.activeSessionId,
                    running = !session.exited,
                    alive = !session.exited,
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
 * 控制台状态栏。
 *
 * M3E：与块式终端的底部操作区同款——官方 [Surface]（tonalElevation）收拢环境切换与操作，
 * 与 PTY 网格拉开官方层级；单行排布以保证网格拿到尽可能高的高度。
 */
@Composable
private fun ConsoleStatusBar(
    environment: TerminalEnvironment,
    linuxEnvironment: TerminalEnvironment,
    onSwitchEnvironment: (TerminalEnvironment) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenSessions: () -> Unit,
    onExitConsole: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalEnvironmentTabs(
                labels = listOf("Android", linuxEnvironment.label()),
                selectedIndex = if (environment == TerminalEnvironment.ANDROID) 0 else 1,
                onSelect = { index ->
                    onSwitchEnvironment(
                        if (index == 0) TerminalEnvironment.ANDROID else linuxEnvironment,
                    )
                },
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TerminalIconButton(
                    icon = Icons.Rounded.Layers,
                    contentDescription = stringResource(R.string.terminal_sessions),
                    onClick = onOpenSessions,
                )
                TerminalIconButton(
                    icon = Icons.Rounded.Insights,
                    contentDescription = stringResource(R.string.terminal_daemon_tasks),
                    onClick = onOpenTasks,
                )
                TerminalTextButton(
                    text = stringResource(R.string.terminal_block_mode),
                    onClick = onExitConsole,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ConsoleGrid(
    state: ConsoleUiState,
    store: ConsoleStore,
    focusRequester: FocusRequester,
    fieldValue: TextFieldValue,
    onFieldChange: (TextFieldValue) -> Unit,
    onOpenEnvironment: () -> Unit,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val cellStyle = terminalGridTextStyle()
    val defaultColor = terminalForeground()
    val cursorColor = terminalForeground()
    val cursorTextColor = terminalRootSurface()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            // PTY 网格本体：点按只用于把焦点交给隐藏输入框，没有可点击语义，
            // 官���组件都会画涟漪/按压态，落在终端字符流上会造成闪烁，故保留无涟漪的手势。
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() },
    ) {
        val cellSize = remember(cellStyle) { measureCell(textMeasurer, cellStyle) }
        val cols = with(density) { (maxWidth.toPx() / cellSize.first).toInt() }
        val rows = with(density) { (maxHeight.toPx() / cellSize.second).toInt() }
        if (cols > 0 && rows > 0) {
            LaunchedEffect(cols, rows) {
                store.open(state.environment, cols, rows)
            }
        }

        val frame = state.frame
        val listState = rememberLazyListState()
        val atBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
                lastVisible >= layoutInfo.totalItemsCount - 1
            }
        }
        LaunchedEffect(frame) {
            if (atBottom && frame.lines.isNotEmpty()) {
                listState.scrollToItem(frame.lines.lastIndex)
            }
        }
        val cursorLineIndex = frame.lines.size - frame.screenRows + frame.cursorRow
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            itemsIndexed(
                items = frame.lines,
                key = { _, line -> line.id },
                contentType = { _, _ -> "console-line" },
            ) { index, line ->
                val cursorCol = if (state.connected && frame.cursorVisible && index == cursorLineIndex) {
                    frame.cursorCol
                } else {
                    null
                }
                ConsoleLine(
                    line = line,
                    cursorCol = cursorCol,
                    defaultColor = defaultColor,
                    cursorColor = cursorColor,
                    cursorTextColor = cursorTextColor,
                    style = cellStyle,
                )
            }
        }

        BasicTextField(
            value = fieldValue,
            onValueChange = onFieldChange,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester),
        )
        val lifecycleOwner = LocalLifecycleOwner.current
        var shouldAutoFocus by remember { mutableStateOf(true) }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> shouldAutoFocus = false
                    Lifecycle.Event.ON_RESUME -> shouldAutoFocus = true
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(Unit) {
            if (shouldAutoFocus) {
                focusRequester.requestFocus()
            }
        }

        if (state.exited || state.failMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(terminalRootSurface().copy(alpha = 0.92f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TerminalBodyText(
                    text = state.failMessage ?: stringResource(R.string.terminal_session_closed),
                    modifier = Modifier,
                    color = terminalMutedForeground(),
                    textAlign = TextAlign.Center,
                )
                TerminalTextButton(
                    text = stringResource(R.string.terminal_reconnect),
                    onClick = store::reconnect,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (state.failMessage != null) {
                    TerminalTextButton(
                        text = stringResource(R.string.terminal_open_environment),
                        onClick = onOpenEnvironment,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleLine(
    line: TerminalScreenBuffer.Line,
    cursorCol: Int?,
    defaultColor: Color,
    cursorColor: Color,
    cursorTextColor: Color,
    style: TextStyle,
) {
    val text = remember(line.id, line.version, cursorCol, defaultColor) {
        lineToAnnotated(line, cursorCol, cursorColor, cursorTextColor)
    }
    M3Text(
        text = text,
        style = style.copy(color = defaultColor),
        softWrap = false,
        maxLines = 1,
    )
}

/** 网格行 → AnnotatedString：连续同样式单元格合并为一个 span；光标格反色。 */
private fun lineToAnnotated(
    line: TerminalScreenBuffer.Line,
    cursorCol: Int?,
    cursorColor: Color,
    cursorTextColor: Color,
): AnnotatedString {
    val cells = line.snapshot()
    return buildAnnotatedString {
        var i = 0
        while (i < cells.size) {
            val cell = cells[i]
            if (cell.continuation) {
                i++
                continue
            }
            val isCursor = i == cursorCol
            var j = i + 1
            while (j < cells.size &&
                !cells[j].continuation &&
                cells[j].style == cell.style &&
                (j == cursorCol) == isCursor
            ) {
                j++
            }
            val span: SpanStyle = if (isCursor) {
                SpanStyle(background = cursorColor, color = cursorTextColor)
            } else {
                cell.style.toSpanStyle()
            }
            if (isCursor || !cell.style.isPlain) {
                withStyle(span) {
                    for (k in i until j) if (!cells[k].continuation) append(cells[k].text)
                }
            } else {
                for (k in i until j) if (!cells[k].continuation) append(cells[k].text)
            }
            i = j
        }
    }
}

/**
 * 控制台按键条。
 *
 * M3E：官方 [HorizontalFloatingToolbar] 承载按键（药丸形状 + 官方海拔与收展动效），
 * 取代贴在网格边缘的一排裸按钮；`expanded = true` 常驻展开。
 */
@Composable
private fun ConsoleKeyBar(
    ctrlActive: Boolean,
    onCtrl: () -> Unit,
    onKey: (String) -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        // 端盖同心：键帽高 40dp（官方 Button 最小高），水平内边距按 (64 − 40)/2 = 12dp 让首末键帽
        // 的端部圆弧与药丸端盖同心；纵向仍走官方 8dp（内容居中后实际留白就是 12dp，不影响外观）。
        contentPadding = EtaFloatingToolbarConcentricPadding(40.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        KeyChip(label = "Esc", modifier = Modifier.weight(1f)) { onKey("\u001B") }
        KeyChip(
            label = "Ctrl",
            active = ctrlActive,
            modifier = Modifier.weight(1f),
            onClick = onCtrl,
        )
        KeyChip(label = "Tab", modifier = Modifier.weight(1f)) { onKey("\t") }
        KeyChip(label = "←", modifier = Modifier.weight(1f)) { onKey("\u001B[D") }
        KeyChip(label = "↑", modifier = Modifier.weight(1f)) { onKey("\u001B[A") }
        KeyChip(label = "↓", modifier = Modifier.weight(1f)) { onKey("\u001B[B") }
        KeyChip(label = "→", modifier = Modifier.weight(1f)) { onKey("\u001B[C") }
    }
}

/** 键帽内边距：纵向取官方 ContentPadding，水平收紧到 6dp（7 个键排进一行）。 */
private val KeyChipContentPadding = PaddingValues(
    horizontal = 6.dp,
    vertical = M3ButtonDefaults.ContentPadding.calculateTopPadding(),
)

@Composable
private fun KeyChip(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    // 官方按钮：激活键用 FilledButton，其余用 FilledTonalButton；
    // 胶囊形状、容器色与按压动效全部取官方默认值，仅收紧 contentPadding 让 7 个键排进一行。
    if (active) {
        M3Button(
            onClick = {
                TouchHaptics.keyboardTap(view)
                onClick()
            },
            contentPadding = KeyChipContentPadding,
            modifier = modifier,
        ) {
            M3Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    } else {
        FilledTonalButton(
            onClick = {
                TouchHaptics.keyboardTap(view)
                onClick()
            },
            contentPadding = KeyChipContentPadding,
            modifier = modifier,
        ) {
            M3Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun measureCell(textMeasurer: TextMeasurer, style: TextStyle): Pair<Int, Int> {
    val result = textMeasurer.measure("M", style)
    return result.size.width to result.size.height
}

/** 提取新插入的文本；非追加式变化（输入法重组）返回空，由调用方维持现状。 */
private fun diffInserted(old: String, new: String): String =
    if (new.length > old.length && new.startsWith(old)) new.substring(old.length) else ""

/** 提取删除的字符数（仅末尾删除）。 */
private fun diffDeleted(old: String, new: String): Int =
    if (new.length < old.length && old.startsWith(new)) old.length - new.length else 0
