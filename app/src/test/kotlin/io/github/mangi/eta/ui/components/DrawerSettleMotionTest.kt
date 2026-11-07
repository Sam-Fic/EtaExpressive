package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TwoWayConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 松手收敛弹簧的行为契约（`DrawerSettleSpringSpec`）。
 *
 * 测试帧位：按小米 13 Ultra 的屏宽 1080px、density 2.8125 折算，停机阈值取
 * DrawerMetrics.SettleVisibilityThresholdDp（1dp = 2.8125px）。弹簧收敛到阈值即停机，
 * 所以「末采样点 ≈ ±阈值」是正确终态，断言按阈值上界判，不按 <0.01px 判。
 */
class DrawerSettleMotionTest {
    private val converter = object : TwoWayConverter<Float, AnimationVector1D> {
        override val convertToVector: (Float) -> AnimationVector1D = { AnimationVector1D(it) }
        override val convertFromVector: (AnimationVector1D) -> Float = { it.value }
    }

    private val visibilityThreshold = 2.8125f
    private val spec = DrawerSettleSpringSpec(visibilityThreshold = visibilityThreshold)

    // 与 DrawerMetrics.SettleStiffness(146f) / SettleVelocityHeadroom(0.92f) 对应；
    // ω = √stiffness ≈ 12.0831/s。调常量时同步这里，时长推导用例才会跟着报警。
    private val omega = sqrt(146f)
    private val headroom = 0.92f

    private fun valueAt(initial: Float, target: Float, velocity: Float, nanos: Long): Float {
        val animation = spec.vectorize(converter)
        return converter.convertFromVector(
            animation.getValueFromNanos(
                nanos,
                converter.convertToVector(initial),
                converter.convertToVector(target),
                converter.convertToVector(velocity),
            ),
        )
    }

    private fun velocityAt(initial: Float, target: Float, velocity: Float, nanos: Long): Float {
        val animation = spec.vectorize(converter)
        return converter.convertFromVector(
            animation.getVelocityFromNanos(
                nanos,
                converter.convertToVector(initial),
                converter.convertToVector(target),
                converter.convertToVector(velocity),
            ),
        )
    }

    private fun durationMillis(initial: Float, target: Float, velocity: Float): Double {
        val animation = spec.vectorize(converter)
        return animation.getDurationNanos(
            converter.convertToVector(initial),
            converter.convertToVector(target),
            converter.convertToVector(velocity),
        ) / 1_000_000.0
    }

    private fun sample(initial: Float, target: Float, velocity: Float): List<Float> {
        val animation = spec.vectorize(converter)
        val initialVector = converter.convertToVector(initial)
        val targetVector = converter.convertToVector(target)
        val velocityVector = converter.convertToVector(velocity)
        val duration = animation.getDurationNanos(initialVector, targetVector, velocityVector)
        val step = 8_000_000L
        return buildList {
            var time = 0L
            while (time <= duration + step) {
                add(
                    converter.convertFromVector(
                        animation.getValueFromNanos(
                            time,
                            initialVector,
                            targetVector,
                            velocityVector,
                        ),
                    ),
                )
                time += step
            }
        }
    }

    @Test
    fun fastFlickNeverOvershootsOpenAnchor() {
        // 600px 残程下 6000px/s 未触及封顶（0.92·ω·600 ≈ 6670），且 6000 < ω·600 = 7250
        // ——临界阻尼朝目标的速度不会越过锚点，全程从下方逼近 0。
        val samples = sample(initial = -600f, target = 0f, velocity = 6000f)
        assertTrue(samples.all { it <= 0.001f })
        assertTrue(abs(samples.last()) <= visibilityThreshold + 0.1f)
    }

    @Test
    fun shortRemainingDistanceCapsVelocityInsteadOfSlamming() {
        // 残程 80px 时封顶 0.92·ω·80 ≈ 889px/s：6000 的快甩被钳到无过冲界内，
        // 慢慢靠岸而不是冲过锚点再弹回。
        val samples = sample(initial = -80f, target = 0f, velocity = 6000f)
        assertTrue(samples.all { it <= 0.001f })
        assertTrue(abs(samples.last()) <= visibilityThreshold + 0.1f)
    }

    @Test
    fun flingVelocityIsCappedAtNoOvershootBound() {
        val animation = spec.vectorize(converter)
        val limit = headroom * omega * 1080f
        assertEquals(
            limit,
            velocityAt(initial = -1080f, target = 0f, velocity = 99999f, nanos = 0L),
            0.5f,
        )
        // 封顶后仍是「朝目标的速度」，起点不跳变。
        assertEquals(
            -1080f,
            converter.convertFromVector(
                animation.getValueFromNanos(
                    0L,
                    converter.convertToVector(-1080f),
                    converter.convertToVector(0f),
                    converter.convertToVector(99999f),
                ),
            ),
            1e-4f,
        )
    }

    @Test
    fun fastFlickSettlesSoonerThanSlowRelease() {
        // 同样 600px 残程：u0=5000（未封顶）解析解 ≈536ms，u0=300 ≈622ms。
        fun duration(velocity: Float): Double = durationMillis(-600f, 0f, velocity)
        assertTrue(duration(5000f) < duration(300f))
    }

    @Test
    fun releaseVelocityShapesTheApproach() {
        val slow = valueAt(-600f, 0f, 100f, 32_000_000L)
        val fast = valueAt(-600f, 0f, 4000f, 32_000_000L)
        assertTrue(fast > slow)
        assertTrue(fast < 0f)
    }

    @Test
    fun springStartsAtCurrentOffsetWithReleaseVelocity() {
        val animation = spec.vectorize(converter)
        val initial = converter.convertToVector(-600f)
        val target = converter.convertToVector(0f)
        val velocity = converter.convertToVector(4000f)
        assertEquals(-600f, converter.convertFromVector(animation.getValueFromNanos(0L, initial, target, velocity)), 1e-4f)
        assertEquals(4000f, converter.convertFromVector(animation.getVelocityFromNanos(0L, initial, target, velocity)), 1f)
    }

    @Test
    fun approachIsMonotonicWithoutBounce() {
        val samples = sample(initial = -600f, target = 0f, velocity = 2500f)
        for (i in 1 until samples.size) {
            assertTrue(samples[i] >= samples[i - 1] - 0.001f)
        }
    }

    @Test
    fun slowFullScreenReleaseKeepsTheLongMiuixTail() {
        // 手感锚定：整屏（1080px）零速轻放的停机时长 ≈675ms
        // （解 (1+ωt)e^(−ωt) = 2.8125/1080，ω=√146≈12.083 → ωt≈8.17）。
        // 九成位移落在 ~320ms。回归到旧的快照档（刚度 520..1600，整屏 <200ms）会跌破下界。
        val duration = durationMillis(initial = -1080f, target = 0f, velocity = 0f)
        assertTrue("slow full-screen settle took ${duration}ms", duration in 630.0..720.0)
    }

    @Test
    fun clampedFullScreenFlingStopsSoonerThanSlowRelease() {
        // 整屏快甩被钳到 0.92·ω·D ≈ 12006px/s：解析解 ≈526ms，仍比零速轻放（≈675ms）先停
        // ——快甩靠 seed 速度提前到达，而不是靠加刚度。
        val fling = durationMillis(initial = -1080f, target = 0f, velocity = headroom * omega * 1080f)
        val slow = durationMillis(initial = -1080f, target = 0f, velocity = 0f)
        assertTrue("fling $fling should beat slow $slow", fling < slow)
        assertTrue("clamped fling took ${fling}ms", fling in 480.0..570.0)
    }
}
