package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 淡出带作用在滚动区的哪一端。
 *
 * 两端都淡是滚动列表的常态（首尾内容都可能被视口切断）；但有些内容天生只有一端会被切：
 * 比如语音实时转写始终滚到尾部，尾部是刚说出的新内容、右端永远不会溢出，
 * 只有左端（旧内容被挤走的方向）需要淡出。
 */
internal enum class ScrollEdgeFadeEdges {
    /** 两端都淡。 */
    Both,

    /** 只淡起始端（横向为左，纵向为上）。 */
    Start,

    /** 只淡终止端（横向为右，纵向为下）。 */
    End;

    /** 起始端是否需要淡出。 */
    val fadesStart: Boolean get() = this != End

    /** 终止端是否需要淡出。 */
    val fadesEnd: Boolean get() = this != Start
}

/**
 * 横向滚动区两端的截断淡出带（DstIn 蒙版版）。
 *
 * 横向可滚动的行视口自身没有内边距：滚到中间时，首尾内容会被视口边缘竖直切开，
 * 留下一条生硬的截断边（热力图是被切一半的格子，胶囊行是被切一半的按钮）。
 * 这里在两端各蒙一层渐变，把切开的部分「擦」掉。
 *
 * **削内容自身的 alpha，而不是盖一层底色**：用 [BlendMode.DstIn] 蒙版直接削掉
 * 内容像素的 alpha，底下露出什么底就是什么底——不要求容器背景已知，也不怕
 * 盖色把容器底色压深或洗掉。浮窗（语音助手气泡）背景透明时更只有这条路走得通。
 * 蒙版用 [CompositingStrategy.Offscreen] 隔离在自己的离屏层，不会擦到下层内容。
 *
 * 只在对应方向真的还有内容可滚时才出现（[ScrollState.canScrollBackward] /
 * [ScrollState.canScrollForward]），停在两端时不会把完整可见的内容再罩一层；
 * 带子的宽度从 0 长出来（不是整条一起淡入），滚到两端时自然收回。
 *
 * 用法：放在 [androidx.compose.foundation.horizontalScroll] **之前**（外层），
 * 蒙版坐标系才是视口坐标而不是滚动内容坐标：
 *
 * ```
 * Row(
 *     modifier = Modifier
 *         .fillMaxWidth()
 *         .horizontalScrollEdgeFade(scrollState)
 *         .horizontalScroll(scrollState),
 * )
 * ```
 */
@Composable
internal fun Modifier.horizontalScrollEdgeFade(
    scrollState: ScrollState,
    fadeWidth: Dp = ScrollEdgeFadeWidth,
    edges: ScrollEdgeFadeEdges = ScrollEdgeFadeEdges.Both,
): Modifier {
    val fadeSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    // 只把 State 传进蒙版、在绘制期才读 .value：带宽动画的每一帧只失效绘制，
    // 不再逐帧生成新 modifier 实例、不重组调用方（解包读值的老写法两者都会发生）。
    val startWidth = animateDpAsState(
        targetValue = if (edges.fadesStart && scrollState.canScrollBackward) fadeWidth else 0.dp,
        animationSpec = fadeSpec,
        label = "scrollEdgeFadeStart",
    )
    val endWidth = animateDpAsState(
        targetValue = if (edges.fadesEnd && scrollState.canScrollForward) fadeWidth else 0.dp,
        animationSpec = fadeSpec,
        label = "scrollEdgeFadeEnd",
    )
    return edgeFadeMask(startWidth = startWidth, endWidth = endWidth, horizontal = true)
}

/**
 * 纵向滚动区上下两端的截断淡出带（DstIn 蒙版版）。
 *
 * 适用于**限高的内容预览区**：内容超出容器高度时被上下边缘硬切，
 * 淡出带告诉用户「下面还有内容」，而不是让人以为内容就到此为止。
 * 整体可滚的表单 / 列表不用——那种滚动有明确的滚动条与惯性预期，
 * 加淡出反而像内容被裁掉了。
 *
 * 用法与 [horizontalScrollEdgeFade] 相同：放在 `verticalScroll` **之前**。
 */
@Composable
internal fun Modifier.verticalScrollEdgeFade(
    scrollState: ScrollState,
    fadeWidth: Dp = ScrollEdgeFadeWidth,
    edges: ScrollEdgeFadeEdges = ScrollEdgeFadeEdges.Both,
): Modifier {
    val fadeSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    // 与 horizontalScrollEdgeFade 同口径：State 下传、绘制期读值，动画帧不重组调用方。
    val startWidth = animateDpAsState(
        targetValue = if (edges.fadesStart && scrollState.canScrollBackward) fadeWidth else 0.dp,
        animationSpec = fadeSpec,
        label = "scrollEdgeFadeVStart",
    )
    val endWidth = animateDpAsState(
        targetValue = if (edges.fadesEnd && scrollState.canScrollForward) fadeWidth else 0.dp,
        animationSpec = fadeSpec,
        label = "scrollEdgeFadeVEnd",
    )
    return edgeFadeMask(startWidth = startWidth, endWidth = endWidth, horizontal = false)
}

/**
 * 懒加载横向列表（[LazyListState]）两端的截断淡出带。
 *
 * 与 [horizontalScrollEdgeFade] 同一套蒙版，只是滚动状态来源不同：
 * [LazyListState] 用 `canScrollBackward` / `canScrollForward` 判断两端是否还有内容。
 */
@Composable
internal fun Modifier.lazyRowEdgeFade(
    listState: LazyListState,
    fadeWidth: Dp = ScrollEdgeFadeWidth,
    edges: ScrollEdgeFadeEdges = ScrollEdgeFadeEdges.Both,
): Modifier {
    val fadeSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    // 与 horizontalScrollEdgeFade 同口径：State 下传、绘制期读值，动画帧不重组调用方。
    val startWidth = animateDpAsState(
        targetValue = if (edges.fadesStart && listState.canScrollBackward) fadeWidth else 0.dp,
        animationSpec = fadeSpec,
        label = "lazyRowEdgeFadeStart",
    )
    val endWidth = animateDpAsState(
        targetValue = if (edges.fadesEnd && listState.canScrollForward) fadeWidth else 0.dp,
        animationSpec = fadeSpec,
        label = "lazyRowEdgeFadeEnd",
    )
    return edgeFadeMask(startWidth = startWidth, endWidth = endWidth, horizontal = true)
}

/**
 * 懒加载纵向列表（[LazyListState]）两端的截断淡出带。
 *
 * 与 [verticalScrollEdgeFade] 同一套蒙版，只是滚动状态来源不同：
 * [LazyListState] 用 `canScrollBackward` / `canScrollForward` 判断两端是否还有内容。
 *
 * 纵向列表的「起始端」在上方：只在列表真能往上滚（顶部有内容被切走）时，
 * 上沿的淡出带才出现；停在最上方时第一项是完整的，不该被削淡。
 */
@Composable
internal fun Modifier.lazyColumnEdgeFade(
    listState: LazyListState,
    fadeHeight: Dp = ScrollEdgeFadeHeight,
    edges: ScrollEdgeFadeEdges = ScrollEdgeFadeEdges.Both,
): Modifier {
    val fadeSpec = MaterialTheme.motionScheme.fastSpatialSpec<Dp>()
    // 与 horizontalScrollEdgeFade 同口径：State 下传、绘制期读值，动画帧不重组调用方。
    val startHeight = animateDpAsState(
        targetValue = if (edges.fadesStart && listState.canScrollBackward) fadeHeight else 0.dp,
        animationSpec = fadeSpec,
        label = "lazyColumnEdgeFadeStart",
    )
    val endHeight = animateDpAsState(
        targetValue = if (edges.fadesEnd && listState.canScrollForward) fadeHeight else 0.dp,
        animationSpec = fadeSpec,
        label = "lazyColumnEdgeFadeEnd",
    )
    return edgeFadeMask(startWidth = startHeight, endWidth = endHeight, horizontal = false)
}

/**
 * 纵向淡出带（固定位置版）。
 *
 * 与 [verticalScrollEdgeFade] 的区别：带子不从容器边缘起，而是落在容器内部的固定位置。
 * 主对话流的上下两条带就属于这一类——上沿对齐顶栏盖板的下沿，下沿对齐输入胶囊顶盖的
 * 圆心；位置由盖板那一层说了算，跟滚动内容无关。
 *
 * 蒙版同样用 [BlendMode.DstIn] 削内容自身的 alpha：淡出的是内容本身，底下露出什么底
 * 就是什么底，不依赖容器底色已知。盖一层同色渐变与削 alpha 看起来等价（都是线性混合），
 * 但只有削 alpha 才能作用在「盖板下方那一层」的内容上——盖板自己画在自己层里，
 * 削不到下层。
 *
 * **调用方必须保证这块内容画在一张独立的离屏纹理里**（见
 * [androidx.compose.ui.graphics.CompositingStrategy.Offscreen]）：DstIn 削的是「当前
 * 画布上已经画过的一切」的 alpha，若这块内容与页面背景共用一张画布，背景会被一起削成
 * 半透明，透出透明窗口底就是一条纯黑带（主对话流踩过一次）。
 *
 * @param bandTopPx 带子上沿（视口坐标），绘制期求值：位置常常要等别的层量完才知道；
 *                  返回 null 表示这一帧不画（例如锚点还没量出来）
 * @param bandHeight 带高
 * @param fadeIn true = 带内 alpha 自上而下 0→1（最上不可见、最下可见）；
 *                false = 自上而下 1→0（最上可见、最下不可见）
 */
// DstIn 蒙版的两套颜色表提为常量：淡出带每帧都可能重画，listOf(...) 的逐帧分配省掉。
private val FadeInColors = listOf(Color.Transparent, Color.Black)
private val FadeOutColors = listOf(Color.Black, Color.Transparent)

internal fun Modifier.verticalFadeBand(
    bandTopPx: DrawScope.() -> Float?,
    bandHeight: Dp,
    fadeIn: Boolean,
): Modifier = this
    .drawWithContent {
        drawContent()
        val top = bandTopPx() ?: return@drawWithContent
        val height = bandHeight.toPx()
        if (height <= 0f) return@drawWithContent
        drawRect(
            brush = Brush.verticalGradient(
                colors = if (fadeIn) FadeInColors else FadeOutColors,
                startY = top,
                endY = top + height,
            ),
            topLeft = Offset(0f, top),
            size = Size(size.width, height),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * 淡出带的公共绘制：起始端从透明渐入，终止端从实心渐出，两端都用
 * [BlendMode.DstIn] 削内容自身的 alpha。
 *
 * 带宽以 [State] 传入、在绘制期才读 [State.value]：动画推进的每一帧只让这一层的
 * 绘制失效，不生成新 modifier 实例、不重组调用方——解包读值的旧写法两者都会发生，
 * 且每次值变都触发整层重组合。绘制期快照读取同样会注册观察，不会漏帧。
 */
private fun Modifier.edgeFadeMask(
    startWidth: State<Dp>,
    endWidth: State<Dp>,
    horizontal: Boolean,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val start = startWidth.value.toPx()
        if (start > 0f) {
            drawRect(
                brush = if (horizontal) {
                    Brush.horizontalGradient(
                        colors = FadeInColors,
                        startX = 0f,
                        endX = start,
                    )
                } else {
                    Brush.verticalGradient(
                        colors = FadeInColors,
                        startY = 0f,
                        endY = start,
                    )
                },
                blendMode = BlendMode.DstIn,
            )
        }
        val end = endWidth.value.toPx()
        if (end > 0f) {
            drawRect(
                brush = if (horizontal) {
                    Brush.horizontalGradient(
                        colors = FadeOutColors,
                        startX = size.width - end,
                        endX = size.width,
                    )
                } else {
                    Brush.verticalGradient(
                        colors = FadeOutColors,
                        startY = size.height - end,
                        endY = size.height,
                    )
                },
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** 截断淡出带默认宽度：约一枚 40dp 高胶囊的四分之一。 */
internal val ScrollEdgeFadeWidth: Dp = 24.dp

/** 纵向截断淡出带默认高度：与主页对话流上下的两条带同高（顶栏按钮高度）。 */
internal val ScrollEdgeFadeHeight: Dp = 40.dp
