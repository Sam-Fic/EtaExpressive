package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 当前内容是否已经处在 [EtaTooltip] 内。图标按钮封装据此跳过自己再包一层，
 * 避免调用方外层已包 Tooltip 时叠出两个气泡。
 */
internal val LocalEtaTooltipActive = staticCompositionLocalOf { false }

/**
 * 长按提示气泡的唯一出处。
 *
 * 内部只使用官方 `TooltipBox` + `PlainTooltip` + `rememberTooltipState`，位置与配色全部取
 * `TooltipDefaults`，不自定义尺寸或颜色。长按 500ms 触发，长按触感由 `TooltipBox` 走
 * `LocalHapticFeedback(LongPress)`，再经 `ProvideEtaHapticFeedback` 归到
 * `TouchHaptics.longPress`；调用方不要再自己补一次震动。业务页面请直接用本组件，
 * 不要手写 `TooltipBox` / `PlainTooltip`（架构测试 R5 执法）。
 *
 * @param text 提示文案：写动作用途；禁用态写原因；选中态交给 `stateDescription`。
 * @param enabled 为 false 时不响应长按，用于已禁用且无提示必要的场景。
 * @param anchor 气泡相对内容的位置，默认在内容上方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtaTooltip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    anchor: TooltipAnchorPosition = TooltipAnchorPosition.Above,
    content: @Composable () -> Unit,
) {
    val state = rememberTooltipState()
    CompositionLocalProvider(LocalEtaTooltipActive provides true) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(anchor),
            tooltip = {
                PlainTooltip {
                    Text(text = text, style = etaTextStyle(EtaType.Caption))
                }
            },
            state = state,
            modifier = modifier,
            enableUserInput = enabled,
            content = content,
        )
    }
}

/**
 * 纯图标按钮的统一实现：官方 `IconButton`（自带 48dp 最小触控目标与官方涟漪）
 * + 长按 Tooltip + `contentDescription` / `stateDescription` 语义 + 点击触觉。
 *
 * 调用方一行即可得到完整交互；不要再单独给内部 `Icon` 传 `contentDescription`，
 * 避免 TalkBack 重复朗读。
 *
 * @param contentDescription 控件的唯一可读标签，也是默认 Tooltip 文案。
 * @param tooltipText 需要区分「标签」与「提示」时才传，例如禁用原因。
 * @param disabledTooltipText 禁用时优先展示的提示（写原因）；为空则沿用 [tooltipText]。
 * @param stateDescription 开关/选中类图标按钮的状态播报，例如「已开启」「朗读中」。
 * @param destructive 破坏性动作（删除、清空）取官方 error 内容色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EtaTooltipIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tooltipText: String = contentDescription,
    disabledTooltipText: String? = null,
    tooltipEnabled: Boolean = true,
    stateDescription: String? = null,
    destructive: Boolean = false,
) {
    val view = LocalView.current
    val semanticsModifier = Modifier.semantics {
        this.contentDescription = contentDescription
        if (stateDescription != null) this.stateDescription = stateDescription
    }
    EtaTooltip(
        text = if (!enabled && disabledTooltipText != null) disabledTooltipText else tooltipText,
        enabled = tooltipEnabled,
    ) {
        IconButton(
            onClick = {
                TouchHaptics.click(view)
                onClick()
            },
            modifier = modifier.then(semanticsModifier),
            enabled = enabled,
            colors = if (destructive) {
                IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                )
            } else {
                IconButtonDefaults.iconButtonColors()
            },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(IconButtonDefaults.mediumIconSize),
            )
        }
    }
}

/**
 * 自绘可点击容器（`Box` / `Row` + `clickable`）的语义补充：
 * 统一补 `Role.Button`、`contentDescription` 与可选 `stateDescription`。
 *
 * 触控目标需要调用方自行保证 ≥ 48dp（官方 `IconButton` 已自带，自绘容器没有）。
 * 需要 Tooltip 时在容器外层再包一层 [EtaTooltip]。
 */
fun Modifier.etaTooltipSemantics(
    contentDescription: String,
    stateDescription: String? = null,
    role: Role = Role.Button,
): Modifier = this.semantics {
    this.role = role
    this.contentDescription = contentDescription
    if (stateDescription != null) this.stateDescription = stateDescription
}
