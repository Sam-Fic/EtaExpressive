package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import io.github.mangi.eta.ui.adaptive.EtaTooltipIconButton

/**
 * 顶栏（大标题栏右上角）动作按钮的唯一实现，内部委托 [EtaTooltipIconButton]
 * （官方 `IconButton` + Tooltip + 触觉 + 破坏色，图标 24dp 官方 medium 档）。
 *
 * 顶栏动作**一律纯图标**，不用文字按钮：
 * - 文字按钮挨着 M3 大标题会被读成正文，图标与左侧返回箭头左右对称，一眼就是「动作」；
 * - 图标固定 48dp 点击区、不随文案长短跳动，标题也不会被挤窄。
 * 需要文字表达的动作放页面里（分段行、底部动作条），或收进行尾三点菜单。
 *
 * 破坏性动作（清空、删除）传 `destructive = true`，图标与涟漪同取 error 色，
 * 不另写 colors——判据只有「会不会毁数据」一条。
 *
 * @param icon 取自 `Icons.Rounded.*`，与兄弟页保持同一套圆角图标。
 * @param contentDescription 无障碍与长按提示的唯一文案，必须传具体动作而不是「按钮」
 *   （语义挂在按钮级，防 TalkBack 重复朗读）。
 * @param tooltipText 需要区分标签与提示时才传，默认与 [contentDescription] 同源。
 * @param disabledTooltipText 禁用时优先展示的提示（写原因）；为空则沿用 [tooltipText]。
 * @param shapes 容器形状，直通 [EtaTooltipIconButton] → 官方 `IconButton(shapes = ...)`
 *   （M3E 按压 morph）。默认 [IconButtonDefaults.shapes]，即官方默认造型，不传时外观不变。
 */
@Composable
internal fun TopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    tooltipText: String = contentDescription,
    disabledTooltipText: String? = null,
    shapes: IconButtonShapes = IconButtonDefaults.shapes(),
) {
    EtaTooltipIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        enabled = enabled,
        tooltipText = tooltipText,
        disabledTooltipText = disabledTooltipText,
        destructive = destructive,
        shapes = shapes,
    )
}
