package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant

/** One visual card, but still one independently composed lazy item per step. */
internal enum class WorkProcessCardPart(val startsCard: Boolean, val endsCard: Boolean) {
    Whole(true, true), First(true, false), Middle(false, false), Last(false, true),
}

internal data class WorkProcessCardGeometry(
    val topExtension: Float,
    val bottomExtension: Float,
    val virtualHeight: Float,
)

/** Move joined edges outside the row's clip; never draw a horizontal seam. */
internal fun workProcessCardGeometry(
    part: WorkProcessCardPart,
    height: Float,
    radius: Float,
    strokeWidth: Float,
): WorkProcessCardGeometry {
    val extension = radius * 2f + strokeWidth
    val top = if (part.startsCard) 0f else extension
    val bottom = if (part.endsCard) 0f else extension
    return WorkProcessCardGeometry(top, bottom, height + top + bottom)
}

@Composable
internal fun WorkProcessCardSlice(
    part: WorkProcessCardPart,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    WorkProcessCardSliceM3(part, modifier, content)
}

/**
 * Material 3 Expressive 外壳：色块代替描边。
 *
 * - 填充用官方 `surfaceContainer` 角色整块上色，不再画 0.5dp 描边；
 * - 圆角全部取官方形状档位（`MaterialTheme.shapes.large`），First/Last 只圆各自的外沿角，
 *   相邻切片共用同一填充色，拼接处天然无缝，无需再靠「虚拟高度把圆角推出裁剪区」的技巧；
 * - 内容不再被自绘路径裁剪——此前贴边内容会被 borderPath 截断（卡片底部文字被切），
 *   改为官方 `clip(shape)` 后由形状本身收边，配合内容自身内边距不再出现文字重叠。
 */
@Composable
private fun WorkProcessCardSliceM3(
    part: WorkProcessCardPart,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape: Shape = when (part) {
        WorkProcessCardPart.Whole -> MaterialTheme.shapes.large
        WorkProcessCardPart.First -> RoundedCornerShape(
            topStart = MaterialTheme.shapes.large.topStart,
            topEnd = MaterialTheme.shapes.large.topEnd,
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        )
        WorkProcessCardPart.Middle -> RectangleShape
        WorkProcessCardPart.Last -> RoundedCornerShape(
            topStart = CornerSize(0.dp),
            topEnd = CornerSize(0.dp),
            bottomStart = MaterialTheme.shapes.large.bottomStart,
            bottomEnd = MaterialTheme.shapes.large.bottomEnd,
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = if (part.startsCard) 4.dp else 0.dp,
                bottom = if (part.endsCard) 4.dp else 0.dp,
            )
            .heightIn(min = 16.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) { content() }
}

// ── 工作过程类卡片的统一排版（M3） ──────────────────────────────────────
//
// 「提问」卡片是这一类卡片的范式：标题独立成一行，内容在展开后的下半部分。
// 思考过程与工具调用卡片全部复用下面两个构件，保证三者的标题行、分隔线、
// 内容内边距与行距完全一致。

/** 统一展开指示箭头：形状、尺寸、色调一处定义。 */
@Composable
internal fun WorkCardChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    // 单图标 + 旋转过渡：收起指向右（0°），展开旋转 90° 指向下。
    // 直接换图标没有过渡，与标题行的 AnimatedContent 状态切换不同档；旋转取官方
    // motionScheme 的 spatial 规格，与 tailDetailsEnter/Exit 同源。
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "work_card_chevron",
    )
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        modifier = modifier.graphicsLayer { rotationZ = rotation },
        tint = etaOnSurfaceVariant().copy(alpha = 0.7f),
    )
}

/**
 * 卡片标题栏：官方 `ListItem` 承担整行命中区、涟漪、最小行高与语义；容器透明，
 * 外壳仍由 [WorkProcessCardSlice] 绘制。折叠/展开只由这一行触发，标题行下方的
 * 内容不参与点击——与提问卡片一致。`onToggle` 为 null 时退化为纯展示行。
 */
@Composable
internal fun WorkCardDisclosureHeader(
    leadingIcon: ImageVector,
    leadingTint: Color,
    title: String,
    titleColor: Color,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leadingModifier: Modifier = Modifier,
    supporting: String? = null,
    supportingColor: Color? = null,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val leading: @Composable () -> Unit = {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            modifier = leadingModifier,
            tint = leadingTint,
        )
    }
    val headline: @Composable () -> Unit = {
        Text(
            text = title,
            style = chatSecondaryStyle(),
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val supportingSlot: (@Composable () -> Unit)? = supporting?.let { text ->
        {
            Text(
                text = text,
                style = chatTertiaryStyle(),
                color = supportingColor ?: etaOnSurfaceVariant().copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val trailing: @Composable () -> Unit = trailingContent ?: { WorkCardChevron(expanded) }
    val listColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    if (onToggle != null) {
        ListItem(
            onClick = onToggle,
            modifier = modifier,
            colors = listColors,
            leadingContent = leading,
            content = headline,
            supportingContent = supportingSlot,
            trailingContent = trailing,
        )
    } else {
        ListItem(
            modifier = modifier,
            colors = listColors,
            leadingContent = leading,
            content = headline,
            supportingContent = supportingSlot,
            trailingContent = trailing,
        )
    }
}

/**
 * 展开后的下半部分：发丝分隔线 + 统一内容列。内边距与行距与提问卡片逐值一致，
 * 分隔线缩进对齐 [ListItem] 的正文起始位置。
 */
@Composable
internal fun WorkCardExpandedBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
