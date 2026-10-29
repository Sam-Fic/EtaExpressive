package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * M3 顶栏不启用毛玻璃，backdrop 恒为空。
 *
 * 保留 `Nothing?` 形参只是为了维持既有调用签名（`TopBarBackdrop` / `captureForTopBar` /
 * `topBarContainerColor` 的入参），调用方无需改动。
 */
@Composable
internal fun rememberTopBarBackdrop(): Nothing? = null

@Composable
internal fun TopBarBackdrop(
    backdrop: Nothing?,
    content: @Composable () -> Unit,
) {
    // 页面背景由壳层 Scaffold 显式取官方 surfaceContainer，这里同色。
    // 面板 = 状态栏 + 64dp 顶栏行；不用 background 渐变按整块面板对半分（那会让渐变
    // 从按钮可视区上半段就开始），而是 drawBehind 显式画两段：
    // - 实色盖板：面板顶到顶栏按钮中线，滚入的内容在线以上完全不可见；
    // - 渐变条：总高与顶栏按钮同高（40dp），顶部从中线起、向下越过行底 8dp，
    //   消息从中线处开始淡出。与底部输入栏的渐变板对称。
    // 8dp 越界是纯绘制延伸（drawBehind 不受 bounds 限制，壳层无裁剪）：
    // 不能真的撑高面板，否则 Scaffold 的 top padding 跟着变大，列表整体下移。
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier.drawBehind {
            val centerlineY = size.height - TopBarCenterlineAboveRowBottom.toPx()
            val gradientHeight = TopBarGradientHeight.toPx()
            drawRect(
                color = surfaceColor,
                size = Size(size.width, centerlineY),
            )
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(surfaceColor, surfaceColor.copy(alpha = 0f)),
                    startY = centerlineY,
                    endY = centerlineY + gradientHeight,
                ),
                topLeft = Offset(0f, centerlineY),
                size = Size(size.width, gradientHeight),
            )
        },
    ) { content() }
}

internal fun Modifier.captureForTopBar(backdrop: Nothing?): Modifier = this

@Composable
internal fun topBarContainerColor(backdrop: Nothing?): Color =
    MaterialTheme.colorScheme.surface

/** 壳层顶栏行高 64dp，按钮 40dp 垂直居中：中线在行底上方 32dp。 */
private val TopBarCenterlineAboveRowBottom = 32.dp

/** 渐变条总高 = 顶栏按钮高度（40dp），从中线向下越过行底 8dp。 */
private val TopBarGradientHeight = 40.dp
