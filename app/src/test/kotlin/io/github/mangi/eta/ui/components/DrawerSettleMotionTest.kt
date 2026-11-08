package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TwoWayConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 松手收敛曲线的行为契约（`DrawerSettleCurveSpec`，恒定减速度梯形）。
 *
 * 测试帧位：动画值单位 px，整步距离 1080px（小米 13 Ultra 屏宽）。期望值按闭式解手推：
 * - 梯形段：峰速 vP = min(标称解, 2.8·√(残程·整步))，a = (2vP−u0)/T，加速段 t1 = (vP−u0)/a，
 *   制动段速度线性下降 vP → 0。
 * - 纯制动（u0 ≥ 峰速封顶）：g(τ) = 2τ − τ²，T = 2·残程/u0。
 *
 * 「线性感」回归锚点：制动段速度必须**线性**下降（恒定减速度），不允许指数尾
 * （等比缓降 = 匀速巡航）也不允许高速扫屏平台（±10% 峰速带掠过半屏）。
 */
class DrawerSettleMotionTest {
    private val converter = object : TwoWayConverter<Float, AnimationVector1D> {
        override val convertToVector: (Float) -> AnimationVector1D = { AnimationVector1D(it) }
        override val convertFromVector: (AnimationVector1D) -> Float = { it.value }
    }

    private val spec = DrawerSettleCurveSpec(fullDistance = 1080f)

    private fun valueAt(initial: Float, target: Float, velocity: Float, nanos: Long): Double {
        val animation = spec.vectorize(converter)
        return converter.convertFromVector(
            animation.getValueFromNanos(
                nanos,
                converter.convertToVector(initial),
                converter.convertToVector(target),
                converter.convertToVector(velocity),
            ),
        ).toDouble()
    }

    private fun velocityAt(initial: Float, target: Float, velocity: Float, nanos: Long): Double {
        val animation = spec.vectorize(converter)
        return converter.convertFromVector(
            animation.getVelocityFromNanos(
                nanos,
                converter.convertToVector(initial),
                converter.convertToVector(target),
                converter.convertToVector(velocity),
            ),
        ).toDouble()
    }

    private fun durationMillis(initial: Float, target: Float, velocity: Float): Double {
        val animation = spec.vectorize(converter)
        return animation.getDurationNanos(
            converter.convertToVector(initial),
            converter.convertToVector(target),
            converter.convertToVector(velocity),
        ) / 1_000_000.0
    }

    /** 按 2ms 步长采样整段动画（时长 + 一帧）的位置序列。 */
    private fun sample(initial: Float, target: Float, velocity: Float): List<Double> {
        val duration = durationMillis(initial, target, velocity)
        return buildList {
            var timeMs = 0.0
            while (timeMs <= duration + 2.0) {
                add(valueAt(initial, target, velocity, (timeMs * 1_000_000).toLong()))
                timeMs += 2.0
            }
        }
    }

    /** 按 2ms 步长采样速度序列（与 [sample] 同一帧位）。 */
    private fun sampleVelocity(initial: Float, target: Float, velocity: Float): List<Double> {
        val duration = durationMillis(initial, target, velocity)
        return buildList {
            var timeMs = 0.0
            while (timeMs <= duration + 2.0) {
                add(velocityAt(initial, target, velocity, (timeMs * 1_000_000).toLong()).toDouble())
                timeMs += 2.0
            }
        }
    }

    @Test
    fun gentleTossSurgesThenBrakesLinearly() {
        // 轻轻抛出（残程 900px、松手 1200px/s）：峰速封顶 2.8·√(900·1080) ≈ 2760px/s
        // （≈981dp/s，全程可跟踪），T ≈ 563.6ms；松手速度连续，先匀加速 ~203ms 到峰速，
        // 再以同一减速度匀减速 ~360ms 精确归零——制动段速度必须线性（恒定减速度）。
        val duration = durationMillis(-900f, 0f, 1200f)
        assertEquals(563.6, duration, 5.0)
        assertEquals(1200.0, velocityAt(-900f, 0f, 1200f, 0L), 0.5)
        val velocities = sampleVelocity(-900f, 0f, 1200f)
        val peak = velocities.reduce { a, b -> max(abs(a), abs(b)) }
        assertEquals(2760.5, peak, 16.0)
        assertBrakeIsLinear(velocities, peak)
        assertEquals(0.0, velocityAt(-900f, 0f, 1200f, 564_000_000L), 1e-6)
        assertEquals(0.0, valueAt(-900f, 0f, 1200f, 564_000_000L), 1e-3)
    }

    @Test
    fun slowPullGlidesAtTrackableSpeed() {
        // 慢拉进（残程 540px、松手 500px/s）：峰速封顶 2.8·√(540·1080) ≈ 2138px/s
        // （≈760dp/s），T ≈ 458.6ms。
        assertEquals(458.6, durationMillis(-540f, 0f, 500f), 4.0)
        val velocities = sampleVelocity(-540f, 0f, 500f)
        assertEquals(2138.3, velocities.reduce { a, b -> max(abs(a), abs(b)) }, 18.0)
        assertBrakeIsLinear(velocities, 2138.3)
        assertEquals(0.0, valueAt(-540f, 0f, 500f, 459_000_000L), 1e-3)
    }

    @Test
    fun fullSwipeOutStaysWithinTheVelocityCap() {
        // 整屏出（残程 1080px、松手 1800px/s）：峰速封顶 2.8·1080 = 3024px/s（≈1075dp/s），
        // T ≈ 609.7ms——出侧栏与进侧栏同一套物理。
        assertEquals(609.7, durationMillis(-1080f, 0f, 1800f), 5.0)
        val velocities = sampleVelocity(-1080f, 0f, 1800f)
        assertEquals(3024.0, velocities.reduce { a, b -> max(abs(a), abs(b)) }, 18.0)
        assertBrakeIsLinear(velocities, 3024.0)
        assertEquals(0.0, valueAt(-1080f, 0f, 1800f, 610_000_000L), 1e-3)
    }

    @Test
    fun hardFlingIsAPureConstantDeceleration() {
        // 快甩（6000px/s 高于峰速封顶）：纯制动 g(τ)=2τ−τ²，T = 2·残程/u0 = 360ms，
        // 速度线性下降 6000 → 0，中点恰走 3/4 残程。
        assertEquals(360.0, durationMillis(-1080f, 0f, 6000f), 2.0)
        assertEquals(6000.0, velocityAt(-1080f, 0f, 6000f, 0L), 0.5)
        assertEquals(4500.0, velocityAt(-1080f, 0f, 6000f, 90_000_000L), 20.0)
        assertEquals(3000.0, velocityAt(-1080f, 0f, 6000f, 180_000_000L), 20.0)
        assertEquals(1500.0, velocityAt(-1080f, 0f, 6000f, 270_000_000L), 20.0)
        assertEquals(-270.0, valueAt(-1080f, 0f, 6000f, 180_000_000L), 0.6)
        assertEquals(0.0, valueAt(-1080f, 0f, 6000f, 360_000_000L), 1e-3)
    }

    @Test
    fun smallCorrectionPopsQuickly() {
        // 近锚点小修正（残程 80px、松手 300px/s）：峰速封顶随残程收缩到 ≈823px/s，
        // T ≈ 170.3ms——快而不突兀。
        assertEquals(170.3, durationMillis(-80f, 0f, 300f), 4.0)
        val samples = sample(-80f, 0f, 300f)
        assertEquals(0.0, samples.last(), 1e-3)
    }

    @Test
    fun awaywardReleaseDriftsBackBeforeTurning() {
        // 阈值下反向松手（u0 = −400）：梯形加速段先折返（速度过零）再正向加速，
        // 回摆深度 ≤ 2% 残程，终点精确归零。
        val duration = durationMillis(-540f, 0f, -400f)
        assertEquals(562.2, duration, 5.0)
        assertEquals(-400.0, velocityAt(-540f, 0f, -400f, 0L), 0.5)
        val samples = sample(-540f, 0f, -400f)
        val dip = samples.reduce(::min)
        assertTrue("backswing dipped to $dip", samples.all { it >= -540f * 1.02f })
        assertTrue(samples.all { it <= 0.001f })
        assertEquals(0.0, samples.last(), 1e-3)
    }

    @Test
    fun standstillReleaseIsASymmetricConstantAccelerationArc() {
        // 手已停（u0 = 0）：速度三角对称（匀加速到峰速再匀减速），中点恰走一半残程。
        val duration = durationMillis(-540f, 0f, 0f)
        assertEquals(505.1, duration, 4.0)
        assertEquals(-270.0, valueAt(-540f, 0f, 0f, (duration * 0.5 * 1_000_000).toLong()), 0.6)
        val velocities = sampleVelocity(-540f, 0f, 0f)
        assertEquals(2138.3, velocities.reduce { a, b -> max(abs(a), abs(b)) }, 18.0)
        assertBrakeIsLinear(velocities, 2138.3)
    }

    @Test
    fun noOvershootAcrossSpeedSweep() {
        // 结构性无过冲的全速域扫描：从零速到极限快甩，任意时刻不越过目标锚点
        // （全屏抽屉越锚会露出底下的主页）。
        for (velocity in listOf(0f, 300f, 1200f, 2500f, 4000f, 6000f, 99999f)) {
            for (distance in listOf(80f, 540f, 900f, 1080f)) {
                val samples = sample(-distance, 0f, velocity)
                val worst = samples.reduce(::max)
                assertTrue(
                    "overshoot at d=$distance v=$velocity: $worst",
                    samples.all { it <= 0.01f },
                )
            }
        }
    }

    @Test
    fun zeroDistanceCollapsesImmediately() {
        assertEquals(0.0, durationMillis(0f, 0f, 5000f), 1e-6)
        assertEquals(0.0, valueAt(0f, 0f, 5000f, 16_000_000L), 1e-4)
    }

    /** 制动段（峰速之后）速度必须线性下降——恒定减速度的「刹车感」所在。 */
    private fun assertBrakeIsLinear(velocities: List<Double>, peak: Double) {
        // 峰索引从采样列表自寻：理论峰速一般不落在 2ms 步长上，按理论值找会找不到（-1）。
        val sampledPeak = velocities.reduce { a, b -> max(abs(a), abs(b)) }
        val peakIndex = velocities.indexOfFirst { abs(it) >= sampledPeak - 1e-6 }
        val brakeSamples = velocities.size - 1 - peakIndex
        assertTrue("no brake phase found", brakeSamples >= 8)
        // 制动段占全程的比例要够大（≥40%），减速才看得见。
        assertTrue(
            "brake phase only ${(brakeSamples * 2.0)}ms of ${velocities.size * 2.0}ms",
            brakeSamples * 2.0 >= 0.40 * (velocities.size - 1) * 2.0,
        )
        for (fraction in listOf(0.25, 0.5, 0.75)) {
            val index = peakIndex + (brakeSamples * fraction).toInt()
            val expected = peak * (1.0 - fraction)
            val actual = abs(velocities[index])
            assertEquals(
                "brake not linear at $fraction: $actual vs $expected",
                expected,
                actual,
                0.02 * peak,
            )
        }
    }

}
