package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
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
    // 从按钮可视区上半段就开始），而是 drawBehind 显式画一段实色盖板：
    // 面板顶到顶栏按钮中线，滚入的内容在线以上完全不可见。
    //
    // 中线以下那条渐变条不再由这里画：盖板画在自己层里，遮得住下层却削不到下层内容的
    // alpha（要真淡化得动内容本身）。淡出改由消息列表层用 DstIn 蒙版做——蒙版作用在
    // 消息内容上，滚上来的消息在这里化开，底下露出的自然是页面背景，
    // 与原先「盖一层同色渐变」视觉等价，但实现统一成了同一套蒙版。
    // 带子的位置（对齐中线往下 40dp）见 [TopBarGradientHeight] / [TopBarCenterlineAboveRowBottom]。
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    Box(
        modifier = Modifier.drawBehind {
            val centerlineY = size.height - TopBarCenterlineAboveRowBottom.toPx()
            drawRect(
                color = surfaceColor,
                size = Size(size.width, centerlineY),
            )
        },
    ) { content() }
}

internal fun Modifier.captureForTopBar(backdrop: Nothing?): Modifier = this

@Composable
internal fun topBarContainerColor(backdrop: Nothing?): Color =
    MaterialTheme.colorScheme.surface

/** 壳层顶栏行高 64dp，按钮 40dp 垂直居中：中线在行底上方 32dp。 */
internal val TopBarCenterlineAboveRowBottom = 32.dp

/** 盖板下沿的淡出带总高 = 顶栏按钮高度（40dp），从中线向下越过行底 8dp。 */
internal val TopBarGradientHeight = 40.dp
