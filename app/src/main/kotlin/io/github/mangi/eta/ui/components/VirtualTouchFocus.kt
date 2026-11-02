package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import io.github.mangi.eta.agent.virtualdisplay.VirtualTouchEffect

/**
 * 虚拟屏画布内的触控焦点：水波纹之上叠加官方 M3E `MaterialShapes` 奇异形状。
 *
 * 页面舞台与顶部胶囊的展开视窗共用这一份绘制逻辑：
 * 点按、长按、滑动头各取固定调色板，序列号取模保证可复现；
 * 形状不直接「换」而是从圆 `Morph` 形变成目标剪影，和 0.45→1 的绽放共用一条进度曲线，
 * 触点不会先冒出一整块静态剪影再缩放。Morph 对象在组合期建好，绘制阶段只按进度重建路径。
 */
@Composable
internal fun VirtualTouchFocusLayer(
    effect: VirtualTouchEffect?,
    modifier: Modifier = Modifier,
) {
    val morphs = rememberTouchFocusMorphs()
    val morphPath = remember { Path() }
    val progress = remember { Animatable(0f) }
    val durationMs = when (effect) {
        is VirtualTouchEffect.Tap -> if (effect.longPress) 540 else 380
        is VirtualTouchEffect.Swipe -> effect.durationMs.coerceIn(160, 1_500)
        null -> 380
    }
    val easing = if (effect is VirtualTouchEffect.Swipe) LinearEasing else FastOutSlowInEasing

    LaunchedEffect(effect?.sequence) {
        val current = effect ?: return@LaunchedEffect
        val age = (SystemClock.uptimeMillis() - current.createdAtMs).coerceAtLeast(0L)
        if (age >= durationMs) return@LaunchedEffect
        progress.snapTo(age.toFloat() / durationMs)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = (durationMs - age).toInt().coerceAtLeast(1),
                easing = easing,
            ),
        )
    }

    val tapColor = MaterialTheme.colorScheme.primary
    val longPressColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier) {
        val current = effect ?: return@Canvas
        val p = progress.value.coerceIn(0f, 1f)
        when (current) {
            is VirtualTouchEffect.Tap -> drawTapFocus(
                effect = current,
                progress = p,
                morphs = morphs,
                path = morphPath,
                accent = if (current.longPress) longPressColor else tapColor,
            )
            is VirtualTouchEffect.Swipe -> drawSwipeFocus(
                effect = current,
                progress = p,
                morph = morphs.swipeHead,
                path = morphPath,
                accent = tapColor,
            )
        }
    }
}

private data class TouchFocusMorphs(
    val tap: List<Morph>,
    val longPress: List<Morph>,
    val swipeHead: Morph,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun rememberTouchFocusMorphs(): TouchFocusMorphs = remember {
    // 统一从圆起形：圆心与触点重合，绽放初期还是一枚小圆点，越长大越显形。
    fun morphTo(shape: RoundedPolygon) = Morph(MaterialShapes.Circle, shape)
    TouchFocusMorphs(
        tap = listOf(
            MaterialShapes.Cookie9Sided,
            MaterialShapes.Clover4Leaf,
            MaterialShapes.Flower,
            MaterialShapes.Burst,
        ).map(::morphTo),
        longPress = listOf(
            MaterialShapes.Sunny,
            MaterialShapes.SoftBoom,
        ).map(::morphTo),
        swipeHead = morphTo(MaterialShapes.PuffyDiamond),
    )
}

/** 水波纹 + MaterialShapes 奇异焦点：先扩散水波纹，再在触点绽放官方 Expressive 形状。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun DrawScope.drawTapFocus(
    effect: VirtualTouchEffect.Tap,
    progress: Float,
    morphs: TouchFocusMorphs,
    path: Path,
    accent: Color,
) {
    val fade = bloomAlpha(progress)
    if (fade <= 0f) return

    val center = Offset(effect.normalizedX * size.width, effect.normalizedY * size.height)
    val focusPx = (if (effect.longPress) 96.dp else 72.dp).toPx()
    val rippleRadius = focusPx * (0.35f + 1.25f * progress)
    val rippleAlpha = (1f - progress).coerceIn(0f, 1f)
    drawCircle(
        color = accent.copy(alpha = 0.55f * rippleAlpha),
        radius = rippleRadius,
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )
    drawCircle(
        color = accent.copy(alpha = 0.30f * rippleAlpha),
        radius = rippleRadius * 0.68f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )

    val palette = if (effect.longPress) morphs.longPress else morphs.tap
    val morph = palette[(effect.sequence % palette.size).toInt().coerceAtLeast(0)]
    // 绽放进度同时也是形变进度：小尺寸时还是圆，撑到最大时正好长成目标剪影。
    morph.toPath(progress.coerceIn(0f, 1f), path)
    val scale = 0.45f + 0.55f * progress
    val rotation = if (effect.longPress) -18f + 30f * progress else -12f + 12f * progress
    val scaledSize = focusPx * scale
    withTransform({
        // 形状以路径中心（0.5, 0.5）对齐触点；translate 必须按当前缩放后的尺寸居中，
        // 否则 0.45→1 的绽放会从左上角偏移，焦点越缩越小越偏。
        translate(left = center.x - scaledSize / 2f, top = center.y - scaledSize / 2f)
        scale(scaleX = scaledSize, scaleY = scaledSize, pivot = Offset.Zero)
        rotate(degrees = rotation, pivot = Offset(0.5f, 0.5f))
    }) {
        drawPath(path, color = accent.copy(alpha = 0.22f * fade))
        drawPath(
            path = path,
            color = accent.copy(alpha = 0.9f * fade),
            style = Stroke(
                // 路径在 0..1 归一化坐标里绘制，按当前缩放换算回 2dp 视觉描边。
                width = 2.dp.toPx() / scaledSize.coerceAtLeast(1f),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** 滑动轨迹 + 跟随插值点的形状头；时长取手势实际时长。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun DrawScope.drawSwipeFocus(
    effect: VirtualTouchEffect.Swipe,
    progress: Float,
    morph: Morph,
    path: Path,
    accent: Color,
) {
    val fade = when {
        progress < 0.15f -> progress / 0.15f
        progress < 0.85f -> 1f
        else -> ((1f - progress) / 0.15f).coerceIn(0f, 1f)
    }
    if (fade <= 0f) return

    val start = Offset(effect.startX * size.width, effect.startY * size.height)
    val end = Offset(effect.endX * size.width, effect.endY * size.height)
    val head = start + (end - start) * progress
    drawLine(
        color = accent.copy(alpha = 0.65f * fade),
        start = start,
        end = head,
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
    )
    drawCircle(
        color = accent.copy(alpha = 0.30f * fade),
        radius = 10.dp.toPx(),
        center = head,
    )

    val headPx = 44.dp.toPx()
    // 滑动走 LinearEasing，形状头在前段就完成「圆 → 菱形」形变，之后保持剪影跟随手指。
    val headMorph = FastOutSlowInEasing.transform((progress / SWIPE_HEAD_MORPH_PORTION).coerceIn(0f, 1f))
    morph.toPath(headMorph, path)
    withTransform({
        translate(left = head.x - headPx / 2f, top = head.y - headPx / 2f)
        scale(scaleX = headPx, scaleY = headPx, pivot = Offset.Zero)
    }) {
        drawPath(path, color = accent.copy(alpha = 0.22f * fade))
        drawPath(
            path = path,
            color = accent.copy(alpha = 0.9f * fade),
            style = Stroke(
                width = 2.dp.toPx() / headPx,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

private fun bloomAlpha(progress: Float): Float = when {
    progress <= 0f -> 0f
    progress < 0.15f -> progress / 0.15f
    progress < 0.70f -> 1f
    else -> ((1f - progress) / 0.30f).coerceIn(0f, 1f)
}

/** 滑动头完成形变所占的手势进度比例。 */
private const val SWIPE_HEAD_MORPH_PORTION = 0.4f
