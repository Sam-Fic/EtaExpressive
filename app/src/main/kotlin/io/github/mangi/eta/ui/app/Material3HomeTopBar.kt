package io.github.mangi.eta.ui.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.components.FooterTooltipBox
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** 顶栏左右槽位宽：S 号圆钮 40dp + 与中间组的 8dp 间距。 */
private val TopBarSideSlotWidth = 48.dp

/**
 * 首页顶栏（Material 3 Expressive）。
 *
 * 一行三件套：菜单色调图标按钮（贴 Start）、会话名官方 SplitButton（严格相对中线居中，
 * 左段点击重命名、右段箭头展开溢出菜单）、可选的「回到调度台」按钮（贴 End）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Material3HomeTopBar(
    title: String,
    onOpenConversationPane: () -> Unit,
    onRenameCurrentConversation: () -> Unit,
    /**
     * 当前聊天目标是否为远程智能体会话（`ChatTarget.Remote` 精确匹配）。
     * 为 true 时右槽恢复「新建会话」，点击新建该服务器上的会话。
     */
    isRemoteTarget: Boolean = false,
    onNewConversation: () -> Unit = {},
    /** 非调度台会话时显示右侧「回到调度台」按钮（原「新建对话」位，写法相同、功能换了）。 */
    showBackToConsole: Boolean = false,
    onBackToConsole: () -> Unit = {},
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    autoCompressEnabled: Boolean,
    isCompressingContext: Boolean,
    onToggleAutoCompress: (Boolean) -> Unit,
    /** 「压缩会话」：立即压缩当前会话（原跳压缩设置页）。 */
    onCompressConversation: () -> Unit,
    onOpenSearchHistory: () -> Unit,
    onOpenTokenUsage: () -> Unit,
    onOpenSubAgentStatus: () -> Unit,
    canDeleteCurrentConversation: Boolean = false,
    isDispatchConsole: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
    /** 调度台「清空主会话」：归档当前上下文并开新篇；为空或生成中时禁用。 */
    canClearDispatchConsole: Boolean = false,
    onClearDispatchConsole: () -> Unit = {},
) {
    val view = LocalView.current
    // 不加 Surface 底板：顶栏直接透在页面背景上，与下方内容保持同一底色。
    // 用 Box 覆盖整条顶栏：中间组按整宽中线居中，左右动作各自贴 Start / End，
    // 中间组位置不再随「回到调度台」按钮的显示与否而偏移。
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.statusBars.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        // 左侧：会话侧栏入口，固定贴 Start（RTL 下自动翻转为右侧语义）。
        // 顶栏圆钮属「单个但重要的操作」：按官方 M3E 建议开启按压 morph（IconButtonDefaults.shapes()），
        // 并包官方 TooltipBox（长按出提示气泡，复用消息操作条的 FooterTooltipBox 同款封装）。
        Box(modifier = Modifier.align(Alignment.CenterStart)) {
            val historyDescription = stringResource(R.string.action_conversation_history)
            FooterTooltipBox(text = historyDescription, enabled = true) {
                EtaIconButton(
                    icon = Icons.Rounded.Menu,
                    contentDescription = historyDescription,
                    onClick = {
                        TouchHaptics.click(view)
                        onOpenConversationPane()
                    },
                    size = EtaIconSize.S,
                    // 与会话侧栏顶部胶囊同款配色 token（surfaceContainerHighest / onSurface）。
                    style = EtaButtonStyle.SurfaceHighest,
                    shapes = IconButtonDefaults.shapes(),
                )
            }
        }
        // 中间组：左右各预留一个对称槽位，保证严格以屏幕（可用宽度）中线居中；
        // 槽位宽度固定为圆钮 40dp + 8dp 间距，两种会话状态下几何完全一致。
        // 超长标题时由 SplitButton 内部单行省略，中间组最大宽度不会侵入左右按钮。
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = TopBarSideSlotWidth),
            contentAlignment = Alignment.Center,
        ) {
            // 官方 M3 Expressive 拆分按钮：左段主操作（重命名会话）+ 右段菜单箭头。
            // 形状 / 尺寸 / 内边距 / 图标尺寸全部取 SplitButtonDefaults 默认值：
            // 尾段在 checked（菜单展开）时按默认 checkedShape 形变为全圆角胶囊。
            var menuExpanded by remember { mutableStateOf(false) }
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(
                        onClick = {
                            // 远程智能体：标题左段不承担重命名（协议侧没有 rename），点击直接忽略。
                            // 不改成 enabled=false：禁用态会把主标题降成低透明度，远程会话名会看不清。
                            if (!isRemoteTarget) {
                                TouchHaptics.click(view)
                                onRenameCurrentConversation()
                            }
                        },
                        // 与会话侧栏顶部胶囊同款配色 token（surfaceContainerHighest / onSurface），
                        // 与两侧的圆钮共用同一档，顶栏整排色调统一。
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        // 文案样式取官方按钮默认的 labelLarge；长标题在 SplitButtonLayout
                        // 给首段预留的剩余宽度里省略，不会把尾段挤出可见区域。
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailingButton = {
                    // 弹窗锚点必须落在箭头按钮自己的 Box 上：Popup 以组合位置的布局节点为锚，
                    // 若挂在整行居中的标题 Box 上，菜单会掉到屏幕左侧（汉堡按钮下方）。
                    // 官方 DropdownMenu 的对齐策略是 start-to-start 优先、放不下自动退到
                    // end-to-end，锚在尾段按钮上时右缘自然与按钮右缘对齐。
                    Box {
                        // 箭头段是纯图标按钮，同样包 TooltipBox；菜单锚点仍由外层 Box 承担。
                        FooterTooltipBox(text = stringResource(R.string.action_more), enabled = true) {
                            SplitButtonDefaults.TonalTrailingButton(
                                checked = menuExpanded,
                                onCheckedChange = { expanded ->
                                    TouchHaptics.click(view)
                                    menuExpanded = expanded
                                },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                // 官方 SplitButton 尾段没有内建箭头/旋转 API（1.5.0-alpha29 只有
                                // checked 状态与 18dp 图标槽），官方示例也是在内容槽里自己转：
                                // 收起态朝下（0°），展开态朝上（180°）。这里刻意不套行尾箭头
                                // 的 0°→90° 规则——SplitButton 是另一套语义。KeyboardArrowDown
                                // 是竖向箭头，RTL 下无需镜像，两个方向都保持「下 → 上」。
                                val chevronRotation by animateFloatAsState(
                                    targetValue = if (menuExpanded) 180f else 0f,
                                    animationSpec = MaterialTheme.motionScheme
                                        .defaultSpatialSpec<Float>(),
                                    label = "split-button-trailing-chevron",
                                )
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = stringResource(R.string.action_more),
                                    modifier = Modifier
                                        .size(SplitButtonDefaults.TrailingIconSize)
                                        .rotate(chevronRotation),
                                    tint = LocalContentColor.current,
                                )
                            }
                        }
                        EtaMenuPopup(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            // TopBarMenuItems 自己输出单个官方分组容器（段落用分割线分开），
                            // 这里不再套一层 EtaMenuGroup。
                            TopBarMenuItems(
                                dismiss = { menuExpanded = false },
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
                                onCompressConversation = onCompressConversation,
                                onOpenSearchHistory = onOpenSearchHistory,
                                onOpenTokenUsage = onOpenTokenUsage,
                                onOpenSubAgentStatus = onOpenSubAgentStatus,
                                canDeleteCurrentConversation = canDeleteCurrentConversation,
                                isDispatchConsole = isDispatchConsole,
                                isRemoteTarget = isRemoteTarget,
                                onDeleteCurrentConversation = onDeleteCurrentConversation,
                            )
                        }
                    }
                },
            )
        }
        // 主页纯化为调度台：「新建对话」入口收进会话侧栏 FAB；这里只在打开其他会话时
        // 显示「回到调度台」——按钮写法沿用原新建按钮（TooltipBox + EtaIconButton + morph）。
        // 固定贴 End；缺席时中间组仍按对称槽位居中，外观不受影响。
        // 右槽按「当前聊天目标」精确分流（顺序即优先级，三者互斥）：
        // 远程智能体 → 新建会话；本机非调度台会话 → 回到调度台；调度台 → 清空主会话。
        // 判据只取 ChatTarget / 路由，不取「是否连上远程」「本机 pane 选中了谁」这类间接条件：
        // 远程视图下本机 pane 通常仍停在调度台，用后者会把远程目标误判进调度台分支。
        if (isRemoteTarget) {
            // 远程智能体：恢复原「新建会话」位。点击由上层按远程服务器分流（requestNewSession），
            // 不是只换图标——远程目标下行为同样是新建该服务器上的会话。
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                val newConversationDescription = stringResource(R.string.action_new_conversation)
                FooterTooltipBox(text = newConversationDescription, enabled = true) {
                    EtaIconButton(
                        icon = Icons.Rounded.AddComment,
                        contentDescription = newConversationDescription,
                        onClick = {
                            TouchHaptics.click(view)
                            onNewConversation()
                        },
                        size = EtaIconSize.S,
                        // 与会话侧栏顶部胶囊同款配色 token（surfaceContainerHighest / onSurface）。
                        style = EtaButtonStyle.SurfaceHighest,
                        shapes = IconButtonDefaults.shapes(),
                    )
                }
            }
        } else if (showBackToConsole) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                val backToConsoleDescription = stringResource(R.string.action_back_to_dispatch_console)
                FooterTooltipBox(text = backToConsoleDescription, enabled = true) {
                    EtaIconButton(
                        icon = Icons.Rounded.Home,
                        contentDescription = backToConsoleDescription,
                        onClick = {
                            TouchHaptics.click(view)
                            onBackToConsole()
                        },
                        size = EtaIconSize.S,
                        // 与会话侧栏顶部胶囊同款配色 token（surfaceContainerHighest / onSurface）。
                        style = EtaButtonStyle.SurfaceHighest,
                        shapes = IconButtonDefaults.shapes(),
                    )
                }
            }
        } else if (isDispatchConsole) {
            // 调度台右槽：清空主会话（归档 + 开新篇）。危险确认由上层弹窗负责，这里只出按钮。
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                val clearDescription =
                    if (canClearDispatchConsole) "清空主会话" else "当前没有可清空的内容"
                FooterTooltipBox(text = clearDescription, enabled = true) {
                    EtaIconButton(
                        icon = Icons.Rounded.RestartAlt,
                        contentDescription = clearDescription,
                        onClick = {
                            TouchHaptics.click(view)
                            onClearDispatchConsole()
                        },
                        size = EtaIconSize.S,
                        style = EtaButtonStyle.SurfaceHighest,
                        shapes = IconButtonDefaults.shapes(),
                        enabled = canClearDispatchConsole,
                        // 禁用态只淡图标，底板保持 surfaceContainerHighest 不透明。
                        keepContainerWhenDisabled = true,
                    )
                }
            }
        }
    }
}
