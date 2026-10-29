package io.github.mangi.eta.ui.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 首页顶栏（Material 3 Expressive）。
 *
 * 一行三件套：菜单色调图标按钮、会话名官方 SplitButton（左段点击重命名、右段箭头展开
 * 溢出菜单）、新建对话色调图标按钮（横向拉伸占满剩余宽度）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Material3HomeTopBar(
    title: String,
    onOpenConversationPane: () -> Unit,
    onRenameCurrentConversation: () -> Unit,
    onNewConversation: () -> Unit,
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
    onOpenCompressConversation: () -> Unit,
    onOpenSearchHistory: () -> Unit,
    onOpenTokenUsage: () -> Unit,
    onOpenSubAgentStatus: () -> Unit,
    canDeleteCurrentConversation: Boolean = false,
    onDeleteCurrentConversation: () -> Unit = {},
) {
    val view = LocalView.current
    // 不加 Surface 底板：顶栏直接透在页面背景上，与下方内容保持同一底色。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.statusBars.only(
                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                ),
            )
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EtaIconButton(
            icon = Icons.Rounded.Menu,
            contentDescription = stringResource(R.string.action_conversation_history),
            onClick = {
                TouchHaptics.click(view)
                onOpenConversationPane()
            },
            size = EtaIconSize.S,
            // 与会话侧栏顶部胶囊同款配色 token（surfaceContainerHighest / onSurface）。
            style = EtaButtonStyle.SurfaceHighest,
        )
        // 会话名按钮在两侧图标之间的剩余空间里居中（与官方 CenterAlignedTopAppBar 的标题布局一致）。
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // 官方 M3 Expressive 拆分按钮：左段主操作（重命名会话）+ 右段菜单箭头。
            // 形状 / 尺寸 / 内边距 / 图标尺寸全部取 SplitButtonDefaults 默认值：
            // 尾段在 checked（菜单展开）时按默认 checkedShape 形变为全圆角胶囊。
            var menuExpanded by remember { mutableStateOf(false) }
            val arrowRotation by animateFloatAsState(
                targetValue = if (menuExpanded) 180f else 0f,
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
                label = "m3-topbar-menu-arrow",
            )
            SplitButtonLayout(
                leadingButton = {
                    SplitButtonDefaults.TonalLeadingButton(
                        onClick = {
                            TouchHaptics.click(view)
                            onRenameCurrentConversation()
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
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.action_more),
                                modifier = Modifier
                                    .size(SplitButtonDefaults.TrailingIconSize)
                                    .graphicsLayer { rotationZ = arrowRotation },
                            )
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
                                onOpenCompressConversation = onOpenCompressConversation,
                                onOpenSearchHistory = onOpenSearchHistory,
                                onOpenTokenUsage = onOpenTokenUsage,
                                onOpenSubAgentStatus = onOpenSubAgentStatus,
                                canDeleteCurrentConversation = canDeleteCurrentConversation,
                                onDeleteCurrentConversation = onDeleteCurrentConversation,
                            )
                        }
                    }
                },
            )
        }
        EtaIconButton(
            icon = Icons.Rounded.AddComment,
            contentDescription = stringResource(R.string.action_new_conversation),
            onClick = {
                TouchHaptics.click(view)
                onNewConversation()
            },
            size = EtaIconSize.S,
            // 与会话侧栏顶部胶囊同款配色 token（surfaceContainerHighest / onSurface）。
            style = EtaButtonStyle.SurfaceHighest,
        )
    }
}
