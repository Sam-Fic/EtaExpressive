package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.ui.unit.Density
import kotlin.jvm.functions.Function0
import kotlin.jvm.functions.Function1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端到端验证松手收敛参数的反射注入真的落到 `DrawerState` 的 anchored state 上。
 *
 * `DrawerMotionSetters` 的每一项都包在 `runCatching` 里，任何失败都被静默吞掉、
 * 手势收敛退回 M3 默认 `tween(256, FastOutSlowIn)`——那正是「体感线性且怎么调参
 * 都不变」的嫌疑来源。这条测试把注入从「javap 确认方法存在」推进到「运行时真的
 * 写进去了」：名字漂移 / 签名漂移 / 代理失败都会在这里显式失败。
 */
class DrawerMotionInjectionTest {
    private val density = Density(2.8125f)
    private val fullDistancePx = 1080f

    private val converter = object : TwoWayConverter<Float, AnimationVector1D> {
        override val convertToVector: (Float) -> AnimationVector1D = { AnimationVector1D(it) }
        override val convertFromVector: (AnimationVector1D) -> Float = { it.value }
    }

    /** 取 DrawerState 内部的 anchoredDraggableState（与生产注入走同一字段查找）。 */
    private fun anchoredOf(state: DrawerState): Any =
        DrawerState::class.java
            .getDeclaredField("anchoredDraggableState")
            .apply { isAccessible = true }
            .get(state)!!

    @Test
    fun snapSpecIsTheVelocityMatchedCurve() {
        val state = DrawerState(DrawerValue.Closed)
        DrawerMotionSetters.apply(state, density, fullDistancePx)
        val anchored = anchoredOf(state)
        val snap = anchored.javaClass.getMethod("getSnapAnimationSpec").invoke(anchored)
        assertTrue(
            "snap spec is ${snap?.javaClass}, expected DrawerSettleCurveSpec",
            snap is DrawerSettleCurveSpec,
        )
        // 整步距离必须随 paneWidth 传入（峰速封顶的折算基准）。
        // 合成帧位（-1080 → 0、u0=0，实际手势中不会出现，仅锚定参数化）：
        // 峰速封顶 2.8·1080 = 3024，梯形闭合反推 T = 2·1080·(2·3024)/(2·3024²) = 714.3ms。
        val animation = (snap as DrawerSettleCurveSpec).vectorize(converter)
        val fullMillis = animation.getDurationNanos(
            converter.convertToVector(-1080f),
            converter.convertToVector(0f),
            converter.convertToVector(0f),
        ) / 1_000_000.0
        assertEquals(714.3, fullMillis, 2.0)
    }

    @Test
    fun decaySpecIsReplacedByTheNoFlingStub() {
        val state = DrawerState(DrawerValue.Closed)
        DrawerMotionSetters.apply(state, density, fullDistancePx)
        val anchored = anchoredOf(state)
        val decay = anchored.javaClass.getMethod("getDecayAnimationSpec").invoke(anchored)
        // M3 默认是 exponentialDecay()；换成 NoFling 桩后类名必然不同。
        assertTrue(
            "decay spec is ${decay?.javaClass}, expected DrawerNoFlingDecay",
            decay?.javaClass?.simpleName == "DrawerNoFlingDecay",
        )
    }

    @Test
    fun positionalThresholdIsTheTunedFraction() {
        val state = DrawerState(DrawerValue.Closed)
        DrawerMotionSetters.apply(state, density, fullDistancePx)
        val anchored = anchoredOf(state)
        val threshold = anchored.javaClass
            .getMethod("getPositionalThreshold\$foundation")
            .invoke(anchored) as Function1<Float, Float>
        // 0.32·距离：1000px 的锚距 → 320px。
        assertEquals(320f, threshold.invoke(1000f), 0.5f)
    }

    @Test
    fun velocityThresholdFollowsDensity() {
        val state = DrawerState(DrawerValue.Closed)
        DrawerMotionSetters.apply(state, density, fullDistancePx)
        val anchored = anchoredOf(state)
        val threshold = anchored.javaClass
            .getMethod("getVelocityThreshold\$foundation")
            .invoke(anchored) as Function0<Float>
        // 480dp × 2.8125 = 1350px/s。
        assertEquals(480f * 2.8125f, threshold.invoke(), 0.5f)
    }
}
