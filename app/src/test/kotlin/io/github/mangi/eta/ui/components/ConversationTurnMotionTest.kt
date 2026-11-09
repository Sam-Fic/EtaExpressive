package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTurnMotionTest {
    private val viewport = 800f
    private val frame = 1f / 60f

    private data class Run(val frames: Int, val travelledPx: Float, val peakStepPx: Float, val steps: List<Float>)

    /** 全额消费的理想帧循环；返回实际走完的距离与每帧步长。 */
    private fun simulate(totalPx: Float): Run {
        val motion = ConversationTurnMotion(viewport)
        var travelled = 0f
        val steps = mutableListOf<Float>()
        var frames = 0
        while (!motion.budgetExhausted() && frames < 1_000) {
            val remaining = (totalPx - travelled).coerceAtLeast(0f)
            val step = motion.advance(frame, travelled, remaining)
            travelled += step
            steps += step
            frames++
        }
        return Run(frames, travelled, steps.maxOrNull() ?: 0f, steps)
    }

    @Test fun distanceOnlyChoosesDurationAndTheDurationIsCapped() {
        val near = resolveConversationJumpDurationMillis(viewport * 0.25f, viewport)
        val oneScreen = resolveConversationJumpDurationMillis(viewport, viewport)
        val severalScreens = resolveConversationJumpDurationMillis(viewport * 4f, viewport)
        val fullScale = resolveConversationJumpDurationMillis(
            viewport * ConversationTurnFullScaleScreens, viewport)
        val enormous = resolveConversationJumpDurationMillis(viewport * 1_000f, viewport)
        assertTrue(near >= ConversationTurnMinDurationMillis)
        assertTrue(oneScreen >= near)
        assertTrue(severalScreens > oneScreen)
        assertEquals(ConversationTurnMaxDurationMillis, fullScale)
        assertEquals(ConversationTurnMaxDurationMillis, enormous)
    }

    @Test fun ultraLongJumpEndsWithinTheSameBudgetAsAShortOne() {
        val short = simulate(viewport * 0.5f)
        val long = simulate(viewport * 200f)
        val maxFrames = ConversationTurnMaxDurationMillis / 1000f / frame + 2f
        assertTrue("long jump must respect the time cap: ${long.frames}", long.frames <= maxFrames)
        // 距离差 400 倍，帧数不该同量级增长——旧的巡航速度上限会让它滚十秒以上。
        assertTrue("${short.frames} vs ${long.frames}", long.frames <= short.frames * 4)
        assertEquals(viewport * 200f, long.travelledPx, viewport * 0.02f)
    }

    @Test fun longDistanceRaisesSpeedInsteadOfExtendingTheAnimation() {
        val short = simulate(viewport * 0.5f)
        val long = simulate(viewport * 200f)
        // 没有 cruiseSpeed / 半屏单帧钳位：峰值步长随距离线性放大。
        assertTrue("${short.peakStepPx} vs ${long.peakStepPx}",
            long.peakStepPx > short.peakStepPx * 40f)
        assertTrue(long.peakStepPx > viewport * 0.5f)
    }

    @Test fun easingRampsUpAndDownAndCoversTheWholeDistance() {
        assertEquals(0f, conversationJumpEasing(0f), 0f)
        assertEquals(1f, conversationJumpEasing(1f), 0f)
        val steps = simulate(viewport * 8f).steps
        assertTrue(steps.size > 10)
        assertTrue(steps.first() < steps.max())
        assertTrue(steps.last() < steps.max())
        assertTrue(steps.all { it >= 0f })
    }

    @Test fun easingIsOfficialM3ExpressiveCurveWithoutOvershoot() {
        assertTrue(ConversationTurnEasing is CubicBezierEasing)
        // 官方 (0.2, 0, 0, 1)：t=0.5 时恰好走过 20% 时间、走完 50% 距离。
        assertEquals(0.5f, conversationJumpEasing(0.2f), 0.002f)
        assertTrue("${conversationJumpEasing(0.5f)}", conversationJumpEasing(0.5f) > 0.85f)
        // 两端切线斜率都趋于 0：起步缓、收尾缓，不是中途才起步的纯减速曲线。
        assertTrue(conversationJumpEasing(0.01f) < 0.01f)
        assertTrue(conversationJumpEasing(1f) - conversationJumpEasing(0.99f) < 0.001f)
        // 全程单调且落在 [0,1]：y 控制点是 0 与 1，不可能过冲或回弹。
        var previous = 0f
        for (i in 0..100) {
            val value = conversationJumpEasing(i / 100f)
            assertTrue("overshoot at $i: $value", value >= 0f && value <= 1f)
            assertTrue("non-monotonic at $i: $value < $previous", value >= previous - 0.0001f)
            previous = value
        }
        // 越界进度钳到端点，绝不产生超过目标的位移。
        assertEquals(1f, conversationJumpEasing(2f), 0f)
    }

    @Test fun frameGapsCannotStretchTheBudgetOrProduceEndlessMotion() {
        val motion = ConversationTurnMotion(viewport)
        val steps = List(100) { motion.advance(1f, 0f, viewport * 100f) }
        // dt 钳在 50ms，650ms 预算最多 13 帧，之后必须彻底停住（调用方会 snap）。
        assertTrue(steps.take(13).all { it > 0f })
        assertTrue(steps.drop(13).all { it <= 0f })
        assertTrue(motion.budgetExhausted())
    }

    @Test fun remeasuredDistanceNeverResetsProgressOrOvershoots() {
        val motion = ConversationTurnMotion(viewport)
        var travelled = 0f
        for (remaining in listOf(500f, 1200f, 200f, 900f, 50f, 2_000f)) {
            val step = motion.advance(frame, travelled, remaining)
            assertTrue("step=$step remaining=$remaining", step >= 0f && step <= remaining)
            travelled += step
        }
        assertTrue(travelled > 0f)
    }

    @Test fun underConsumedFramesAreMadeUpInsteadOfBeingDropped() {
        // 超长跳转一帧跨好几屏时 scrollBy 只消费一部分；绝对差必须在后续帧补回，
        // 否则预算到点后还要靠 snap 跳过一大截。
        val total = viewport * 8f
        val motion = ConversationTurnMotion(viewport)
        var travelled = 0f
        var frames = 0
        while (!motion.budgetExhausted() && frames < 1_000) {
            val remaining = (total - travelled).coerceAtLeast(0f)
            val step = motion.advance(frame, travelled, remaining)
            travelled += step * 0.4f
            frames++
        }
        assertTrue("travelled=$travelled total=$total", travelled > total * 0.9f)
    }

    @Test fun arrivedTargetAndZeroTimeDoNotMove() {
        val motion = ConversationTurnMotion(viewport)
        assertEquals(0f, motion.advance(0f, 0f, 1_000f), 0f)
        assertEquals(0f, motion.advance(frame, 0f, 0f), 0f)
        assertEquals(0f, motion.advance(-1f, 0f, 1_000f), 0f)
    }
}
