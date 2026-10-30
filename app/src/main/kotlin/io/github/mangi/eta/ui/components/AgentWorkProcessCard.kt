package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

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

// ── 工作过程类卡片的旧外壳 ──────────────────────────────────────────────
//
// 思考、工具与提问卡片已全部迁到官方 `SegmentedListItem`（见 AgentWorkSegmentedBubble），
// 旧的标题栏 / 展开箭头 / 展开内容构件随之移除。WorkProcessCardSlice 暂无调用方，
// 保留给 AgentWorkProcessCardDrawTest 的拼接几何回归。
