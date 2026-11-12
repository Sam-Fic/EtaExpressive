package io.github.mangi.eta.ui.adaptive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * 行尾静态导航箭头：点击整行进入子页 / 详情。
 *
 * 箭头本身不旋转、不消费点击（点击区域与语义归行本体），因此
 * `contentDescription` 默认留空——导航动作已由整行播报，重复描述会念两遍。
 * 图标用 `AutoMirrored` 变体，RTL 下自动指向左侧。
 */
@Composable
fun EtaTrailingChevron(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = EtaDimens.TrailingChevronSize,
) {
    // 所在分段行被临时换成亮底（宽屏设置左栏选中行）时，箭头改吃官方配套前景色；
    // 没有覆盖时保持调用方给的 tint（默认 onSurfaceVariant）。
    val selectedContentColor = LocalEtaSegmentedRowContentColor.current
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = selectedContentColor ?: tint,
    )
}

/**
 * 常朝下的静态箭头：用于下拉菜单等「有展开态但箭头不参与旋转」的尾槽。
 *
 * 与 [EtaExpandedDownRotation] 同一几何：LTR 顺时针 90°，RTL 逆时针 90°，
 * 保证 AutoMirrored 图标在两种方向下都朝下，不会被动变成朝左/朝右。
 */
@Composable
fun EtaDownChevron(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = EtaDimens.TrailingChevronSize,
) {
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = contentDescription,
        modifier = modifier.size(size).rotate(EtaExpandedDownRotation()),
        tint = tint,
    )
}

/**
 * 展开 / 收起箭头：与静态导航箭头共用同一个朝右骨架，连续旋转、不换图标。
 *
 * LTR 为 0°（收起，朝右）→ 90°（展开，朝下）；RTL 下 AutoMirrored 图标朝左，
 * 因此展开角度取 -90°，避免箭头转成朝上。曲线复用官方 Expressive 的
 * `defaultSpatialSpec`，不新造时长 / 弹簧参数，与相连按钮组的 Morph 动画同源。
 *
 * `Modifier.rotate` 默认以节点中心为轴，旋转不会产生位移。
 */
@Composable
fun EtaExpandChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    size: Dp = EtaDimens.TrailingChevronSize,
) {
    val expandedRotation = EtaExpandedDownRotation()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) expandedRotation else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "eta-expand-chevron",
    )
    Icon(
        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
        contentDescription = contentDescription,
        modifier = modifier.size(size).rotate(rotation),
        tint = tint,
    )
}

/** 展开态箭头应指向下方的旋转角度：LTR +90°，RTL -90°（AutoMirrored 已把骨架翻转）。 */
@Composable
private fun EtaExpandedDownRotation(): Float =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) -90f else 90f
