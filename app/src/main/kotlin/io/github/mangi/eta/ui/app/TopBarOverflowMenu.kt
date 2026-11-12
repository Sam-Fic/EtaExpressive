package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.InteractiveModePreference
import io.github.mangi.eta.ui.adaptive.EtaExpressiveSwitch
import io.github.mangi.eta.ui.adaptive.EtaMenuDivider
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.components.rememberInteractiveModeEnabled
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 顶栏溢出菜单（more 图标 + 菜单）。
 *
 * 与首页 M3 拆分按钮菜单共用 [TopBarMenuItems]；弹层统一走 [EtaMenuPopup]，
 * 条目排版、分组圆角与组间距全部取官方 Expressive 令牌（与「模型自建工具」的溢出菜单同款）。
 */
@Composable
internal fun TopBarOverflowMenu(
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onRefreshKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenVoiceSettings: () -> Unit = {},
    autoCompressEnabled: Boolean,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit,
    onOpenSearchHistory: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSubAgentStatus: () -> Unit = {},
    /** 「压缩会话」菜单行：立即压缩当前会话（不再跳压缩设置页）。 */
    onCompressConversation: () -> Unit = {},
    canDeleteCurrentConversation: Boolean = false,
    isDispatchConsole: Boolean = false,
    /** 远程智能体目标：隐藏只对本机会话有效的条目（搜索当前会话/用量/压缩/移入回收站）。 */
    isRemoteTarget: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        TopBarIconButton(
            icon = Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.action_more),
            onClick = {
                onRefreshKimiWeb()
                menuExpanded = true
            },
            shapes = IconButtonDefaults.shapes(),
        )

        EtaMenuPopup(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
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
}

/**
 * 顶栏菜单项集合（不含触发按钮与弹层容器），供普通溢出菜单与首页 M3 拆分按钮共用。
 *
 * 整张菜单只用一个官方 [EtaMenuGroup] 容器，条目按语义分段，段与段之间用
 * [EtaMenuDivider] 分开，不再拆成多个组靠组间距分区。
 *
 * @param dismiss 关闭外层菜单容器。
 */
@Composable
internal fun TopBarMenuItems(
    dismiss: () -> Unit,
    onOpenTerminal: () -> Unit,
    onLaunchKimiWeb: () -> Unit,
    kimiWebLabel: String,
    canStopKimiWeb: Boolean,
    onStopKimiWeb: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    autoCompressEnabled: Boolean,
    isCompressingContext: Boolean = false,
    onToggleAutoCompress: (Boolean) -> Unit,
    /** 「压缩会话」菜单行：立即压缩当前会话（不再跳压缩设置页）。 */
    onCompressConversation: () -> Unit = {},
    onOpenSearchHistory: () -> Unit = {},
    onOpenTokenUsage: () -> Unit = {},
    onOpenSubAgentStatus: () -> Unit = {},
    canDeleteCurrentConversation: Boolean = false,
    isDispatchConsole: Boolean = false,
    /** 远程智能体目标：隐藏搜索当前会话、用量、上下文压缩（含自动压缩开关）、移入回收站。 */
    isRemoteTarget: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
) {
    val interactiveModePreference = remember { InteractiveModePreference() }
    val interactiveModeEnabled by rememberInteractiveModeEnabled(interactiveModePreference)
    val view = LocalView.current

    // 远程智能体目标下，搜索当前会话 / 用量 / 压缩 / 移入回收站都指向本机会话，协议侧无对应能力，
    // 直接隐藏而不是置灰。隐藏后若某段整段为空，连同它的分割线一起不渲染（见下方 if 包裹）。
    val showSearchHistory = !isRemoteTarget
    val showConversationTools = !isRemoteTarget
    val showTrash = !isDispatchConsole && !isRemoteTarget

    // 整张菜单是一个连续的条目列表：分段只决定分割线，行形状按全局位置取首行 / 末行，
    // 末行按下的高亮才会和容器底角同心。
    // 条目总数与各段起点按实际显示的行算，隐藏段落后首行 / 末行的圆角仍落在真正的首尾行上。
    val sectionFirstCount = if (showSearchHistory) 2 else 1
    val sectionSecondCount = 1
    val sectionThirdCount = 5
    val sectionFourthCount = if (showConversationTools) 3 else 0
    val sectionFifthCount = if (showTrash) 1 else 0
    val sectionSixthCount = if (canStopKimiWeb) 1 else 0
    val itemCount = sectionFirstCount + sectionSecondCount + sectionThirdCount +
        sectionFourthCount + sectionFifthCount + sectionSixthCount
    val firstSectionStart = 0
    val secondSectionStart = firstSectionStart + sectionFirstCount
    val thirdSectionStart = secondSectionStart + sectionSecondCount
    val fourthSectionStart = thirdSectionStart + sectionThirdCount
    val fifthSectionStart = fourthSectionStart + sectionFourthCount
    val sixthSectionStart = fifthSectionStart + sectionFifthCount

    // 整张菜单只用一个圆角容器，段落之间用分割线分开。
    EtaMenuGroup(index = 0, count = 1) {
        TopBarMenuSection(first = true) {
            // 「搜索当前会话」只查本机会话历史，远程目标下隐藏（不占位、不留分割线）。
            if (showSearchHistory) {
                TopBarMenuItem(
                    text = stringResource(R.string.action_search_history),
                    index = firstSectionStart,
                    count = itemCount,
                    onClick = { dismiss(); onOpenSearchHistory() },
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                }
            }
            // 语音设置快捷入口：识别 / 朗读 / 语音对话都在一页，顶栏一步直达，不必翻设置列表。
            TopBarMenuItem(
                text = stringResource(R.string.action_voice_settings),
                index = firstSectionStart + if (showSearchHistory) 1 else 0,
                count = itemCount,
                onClick = { dismiss(); onOpenVoiceSettings() },
            ) {
                Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null)
            }
        }

        TopBarMenuSection {
            TopBarMenuItem(
                text = stringResource(R.string.action_interactive_mode),
                index = secondSectionStart,
                count = itemCount,
                modifier = Modifier.testTag("top-bar-interactive-mode-row"),
                onClick = {
                    interactiveModePreference.setEnabled(!interactiveModeEnabled)
                },
                trailingContent = {
                    EtaSwitch(
                        checked = interactiveModeEnabled,
                        onCheckedChange = { enabled ->
                            TouchHaptics.click(view)
                            interactiveModePreference.setEnabled(enabled)
                        },
                        modifier = Modifier.testTag("top-bar-interactive-mode-switch"),
                    )
                },
            ) {
                Icon(Icons.Rounded.TouchApp, contentDescription = null)
            }
        }

        TopBarMenuSection {
            TopBarMenuItem(
                text = stringResource(R.string.action_open_terminal),
                index = thirdSectionStart,
                count = itemCount,
                onClick = { dismiss(); onOpenTerminal() },
            ) {
                Icon(Icons.Rounded.Terminal, contentDescription = null)
            }
            TopBarMenuItem(
                text = stringResource(R.string.action_open_browser),
                index = thirdSectionStart + 1,
                count = itemCount,
                onClick = { dismiss(); onOpenBrowser() },
            ) {
                Icon(Icons.Rounded.Language, contentDescription = null)
            }
            TopBarMenuItem(
                text = stringResource(R.string.action_browse_workspace_files),
                index = thirdSectionStart + 2,
                count = itemCount,
                onClick = { dismiss(); onOpenWorkspace() },
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null)
            }
            TopBarMenuItem(
                text = stringResource(R.string.action_sub_agent_status),
                index = thirdSectionStart + 3,
                count = itemCount,
                onClick = { dismiss(); onOpenSubAgentStatus() },
            ) {
                Icon(Icons.Rounded.AccountTree, contentDescription = null)
            }
            TopBarMenuItem(
                text = kimiWebLabel,
                index = thirdSectionStart + 4,
                count = itemCount,
                onClick = { dismiss(); onLaunchKimiWeb() },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_kimi_code),
                    contentDescription = null,
                )
            }
        }

        // 用量 / 压缩 / 自动压缩开关都作用于本机会话：远程目标下整段隐藏，
        // 空段落连同它上面的分割线一起不渲染。
        if (showConversationTools) {
            TopBarMenuSection {
                TopBarMenuItem(
                    text = stringResource(R.string.action_token_usage),
                    index = fourthSectionStart,
                    count = itemCount,
                    onClick = { dismiss(); onOpenTokenUsage() },
                ) {
                    Icon(Icons.Rounded.DataUsage, contentDescription = null)
                }
                TopBarMenuItem(
                    text = stringResource(R.string.action_compress_conversation),
                    index = fourthSectionStart + 1,
                    count = itemCount,
                    enabled = !isCompressingContext,
                    // 图二点位：点击立即压缩当前会话，不再作为压缩设置页入口。
                    onClick = {
                        dismiss()
                        onCompressConversation()
                    },
                ) {
                    Icon(Icons.Rounded.Summarize, contentDescription = null)
                }
                // 开关只做状态显示（点击由整行承担），与官方菜单里的「仅展示」开关一致。
                TopBarMenuItem(
                    text = stringResource(R.string.action_auto_compress_context),
                    index = fourthSectionStart + 2,
                    count = itemCount,
                    onClick = { onToggleAutoCompress(!autoCompressEnabled) },
                    trailingContent = {
                        EtaExpressiveSwitch(checked = autoCompressEnabled, onCheckedChange = null)
                    },
                ) {
                    Icon(Icons.Rounded.Compress, contentDescription = null)
                }
            }
        }

        if (showTrash) {
            TopBarMenuSection {
                TopBarMenuItem(
                    text = stringResource(R.string.conversation_move_to_trash),
                    index = fifthSectionStart,
                    count = itemCount,
                    enabled = canDeleteCurrentConversation,
                    emphasizeError = true,
                    onClick = { dismiss(); onDeleteCurrentConversation() },
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        if (canStopKimiWeb) {
            TopBarMenuSection {
                TopBarMenuItem(
                    text = stringResource(R.string.capability_kimi_stop),
                    index = sixthSectionStart,
                    count = itemCount,
                    onClick = { dismiss(); onStopKimiWeb() },
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                }
            }
        }
    }
}

/**
 * 菜单段落：整张菜单共用一个 [EtaMenuGroup] 容器，段落之间用 [EtaMenuDivider] 分隔
 * （首段之前不加分割线）。
 */
@Composable
private fun ColumnScope.TopBarMenuSection(
    first: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!first) EtaMenuDivider()
    content()
}

/**
 * 顶栏菜单行：文案 + 前置图标 + 可选尾部内容，形状取官方 [EtaMenuItemShape]。
 *
 * 容器的内边距、行高、涟漪与禁用态沿用官方 [DropdownMenuItem] 默认值；
 * 点击统一带一次触感反馈，然后执行调用方动作（关闭菜单由调用方在 [onClick] 里负责）。
 */
@Composable
private fun TopBarMenuItem(
    text: String,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasizeError: Boolean = false,
    trailingContent: (@Composable () -> Unit)? = null,
    leadingIcon: @Composable () -> Unit,
) {
    val view = LocalView.current
    DropdownMenuItem(
        modifier = modifier,
        shape = EtaMenuItemShape(index = index, count = count),
        enabled = enabled,
        text = {
            // 仅在「危险操作」上显式取 error；其余交给官方条目默认色，
            // 这样禁用态仍能按官方降透明处理，不会被写死的颜色盖掉。
            if (emphasizeError) {
                Text(text = text, color = MaterialTheme.colorScheme.error)
            } else {
                Text(text = text)
            }
        },
        leadingIcon = leadingIcon,
        trailingContent = trailingContent,
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
    )
}
