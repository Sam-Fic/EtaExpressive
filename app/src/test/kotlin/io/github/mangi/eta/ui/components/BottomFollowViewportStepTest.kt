package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [resolveBottomFollowViewportStep].
 *
 * These pin the arithmetic of the resolver only. They are not a device frame-by-frame verification
 * of the follow controller; the real LazyColumn draw path is covered elsewhere.
 */
class BottomFollowViewportStepTest {

    @Test fun largeMeasuredOverflowUpgradesSmoothStepToRealDeficit() {
        // Representative aggregate magnitudes; the 39.384 px smooth input is synthetic. The
        // deficit the draw buffer cannot absorb is 1260 - 426 = 834 px. The normal 39.384 px smooth
        // step is far below that, so it must be upgraded to 834 px for this frame.
        assertEquals(
            834f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 39.384f,
                measuredOverflowPx = 1260,
                afterContentPaddingPx = 426,
            ),
            0.0001f,
        )
    }

    @Test fun firstFrameWithZeroSmoothStillClearsTheDeficit() {
        assertEquals(
            834f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 0f,
                measuredOverflowPx = 1260,
                afterContentPaddingPx = 426,
            ),
            0.0001f,
        )
    }

    @Test fun overflowInsideDrawBufferDoesNotAccelerate() {
        // 426 px of overflow fits exactly in the 426 px buffer, so the smooth step is untouched.
        assertEquals(
            39.384f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 39.384f,
                measuredOverflowPx = 426,
                afterContentPaddingPx = 426,
            ),
            0.0001f,
        )
        assertEquals(
            39.384f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 39.384f,
                measuredOverflowPx = 400,
                afterContentPaddingPx = 426,
            ),
            0.0001f,
        )
    }

    @Test fun smoothStepAboveRequiredCompensationIsKept() {
        // Required compensation is 500 - 426 = 74 px, below the 100 px smooth step, so the smooth
        // step wins and no extra speed is added.
        assertEquals(
            100f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 100f,
                measuredOverflowPx = 500,
                afterContentPaddingPx = 426,
            ),
            0.0001f,
        )
    }

    @Test fun unknownTailOnlyReturnsTheSmoothStep() {
        assertEquals(
            39.384f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 39.384f,
                measuredOverflowPx = null,
                afterContentPaddingPx = 426,
            ),
            0.0001f,
        )
        // With an unknown tail a negative smooth input never becomes motion.
        assertEquals(
            0f,
            resolveBottomFollowViewportStep(
                smoothStepPx = -12f,
                measuredOverflowPx = null,
                afterContentPaddingPx = 426,
            ),
            0f,
        )
    }

    @Test fun nonPositiveOverflowStops() {
        assertEquals(0f, resolveBottomFollowViewportStep(39.384f, 0, 426), 0f)
        assertEquals(0f, resolveBottomFollowViewportStep(39.384f, -50, 426), 0f)
    }

    @Test fun smoothStepBeyondMeasuredOverflowIsClamped() {
        assertEquals(
            100f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 1000f,
                measuredOverflowPx = 100,
                afterContentPaddingPx = 0,
            ),
            0.0001f,
        )
    }

    @Test fun zeroOrNegativePaddingGivesNoBuffer() {
        // No usable buffer, so the whole measured overflow is the deficit.
        assertEquals(
            300f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 5f,
                measuredOverflowPx = 300,
                afterContentPaddingPx = 0,
            ),
            0.0001f,
        )
        assertEquals(
            300f,
            resolveBottomFollowViewportStep(
                smoothStepPx = 5f,
                measuredOverflowPx = 300,
                afterContentPaddingPx = -80,
            ),
            0.0001f,
        )
    }

    @Test fun oneBackfillRoundLeavesResidualInsideDrawBuffer() {
        val overflow = 1260
        val budget = 426
        var remaining = overflow
        var rounds = 0
        while (remaining > budget && rounds < 8) {
            val step = resolveBottomFollowViewportStep(39.384f, remaining, budget)
            assertTrue("the step must move toward the tail", step > 0f)
            remaining -= step.toInt()
            rounds++
        }
        assertEquals("the excess must clear in one controller step", 1, rounds)
        assertTrue("the residual must fit inside the draw buffer", remaining <= budget)
        assertEquals(
            "the remaining drawn overflow must be zero",
            0,
            (remaining - budget).coerceAtLeast(0),
        )
    }

    @Test fun legacySmoothOnlyStepWouldLeaveLargeClipping() {
        val overflow = 1260
        val budget = 426
        val legacyStep = 39.384f

        // A smooth-only step leaves almost the whole overflow drawn past the buffer.
        val legacyRemaining = overflow - legacyStep.toInt()
        val legacyClipped = (legacyRemaining - budget).coerceAtLeast(0)
        assertTrue("a smooth-only step keeps a large clipped remainder", legacyClipped > 700)

        // The resolved step clears the drawn overflow in the same round.
        val resolved = resolveBottomFollowViewportStep(legacyStep, overflow, budget)
        assertEquals(834f, resolved, 0.0001f)
        val resolvedRemaining = overflow - resolved.toInt()
        assertEquals("the resolved step clears the drawn overflow", 0, (resolvedRemaining - budget).coerceAtLeast(0))
    }

    @Test fun fractionalStepScrollsWholePixels() {
        assertEquals(1f, snapFollowScrollStep(0.4f, 2f), 0f)
        assertEquals(3f, snapFollowScrollStep(2.6f, 8f), 0f)
        assertEquals(2f, snapFollowScrollStep(2.6f, 2f), 0f)
        assertEquals(0f, snapFollowScrollStep(0.4f, 0.4f), 0f)
        assertEquals(0f, snapFollowScrollStep(0f, 4f), 0f)
        assertEquals(0f, snapFollowScrollStep(Float.NaN, 4f), 0f)
    }

    @Test fun restLineStaysAboveTheComposer() {
        assertEquals(400f, composerRestLinePx(900f, 500f), 0f)
        assertEquals(0f, composerRestLinePx(100f, 140f), 0f)
    }

    @Test fun strictFollowTargetPrefersFreshMeasuredOverflow() {
        // 严格贴底：本帧实测差额就是唯一目标，队列里的过时估算一律作废。
        assertEquals(37f, resolveStrictBottomFollowTarget(900f, 37), 0f)
        assertEquals(37f, resolveStrictBottomFollowTarget(0f, 37), 0f)
        // 已经贴住就不再滚，避免在同一个亚像素残量上逐帧空转。
        assertEquals(0f, resolveStrictBottomFollowTarget(900f, 0), 0f)
        // 尾部测不到（哨兵被挤出测量窗口）才沿用队列里的整屏估算。
        assertEquals(900f, resolveStrictBottomFollowTarget(900f, null), 0f)
        assertEquals(0f, resolveStrictBottomFollowTarget(-12f, null), 0f)
        assertEquals(0f, resolveStrictBottomFollowTarget(0f, -8), 0f)
    }

    @Test fun strictAnchorLiftCompensatesTheWholeDeficitInOneFrame() {
        // 不传逐帧预算时一帧补满；旧缓动会在这里只走几像素，让列表「追」底部。
        assertEquals(60, nextHeldTailLift(true, 60, 0))
        assertEquals(60, nextHeldTailLift(true, 60, 47))
        assertEquals(120, nextHeldTailLift(true, 120, 60))
        // 滚动一次吃满差额后 overflow 归零，上提同步归零，两者之和不超过真实溢出。
        assertEquals(0, nextHeldTailLift(true, 0, 60))
        assertEquals(30, nextHeldTailLift(true, 30, 60))
        // 只有真实用户滚动/导航才冻结，单纯手指按着不停补偿。
        assertEquals(60, nextHeldTailLift(true, 60, 0, pointerDown = false))
        assertEquals(0, nextHeldTailLift(true, 60, 0, pointerDown = true))
    }

    @Test fun missingTailMeasurementKeepsThePreviousLift() {
        assertEquals(47, nextHeldTailLift(shouldLift = true, overflowPx = null, heldPx = 47))
        assertEquals(12, nextHeldTailLift(shouldLift = true, overflowPx = 12, heldPx = 47))
        assertEquals(0, nextHeldTailLift(shouldLift = true, overflowPx = 0, heldPx = 47))
        assertEquals(0, nextHeldTailLift(shouldLift = false, overflowPx = null, heldPx = 47))
    }
    @Test fun heldFingerFreezesLiftUntilReleaseWithoutDroppingExistingTranslation() {
        assertEquals(47, nextHeldTailLift(true, 100, 47, pointerDown = true))
        assertEquals(47, nextHeldTailLift(true, 0, 47, pointerDown = true))
        assertEquals(47, nextHeldTailLift(true, null, 47, pointerDown = true))
        assertEquals(100, nextHeldTailLift(true, 100, 47, pointerDown = false))
        assertEquals(0, nextHeldTailLift(false, 100, 47, pointerDown = true))
    }

    @Test fun liftRisesByAWholeBudgetPerFrameAndCapsAtOverflow() {
        // 新长出的一行不再一帧上提整行，而是每帧只走一个预算。
        var lift = 0
        repeat(3) { lift = nextHeldTailLift(true, 60, lift, maxRisePx = 8) }
        assertEquals(24, lift)
        lift = nextHeldTailLift(true, 60, lift, maxRisePx = 8)
        assertEquals(32, lift)
        // 溢出变小（滚动已经追上）时立即回落：滚动量与上提量之和永远不超过真实溢出。
        assertEquals(30, nextHeldTailLift(true, 30, lift, maxRisePx = 8))
        assertEquals(0, nextHeldTailLift(true, 0, lift, maxRisePx = 8))
        // 手指按住时冻结，不继续增长。
        assertEquals(32, nextHeldTailLift(true, 60, 32, pointerDown = true, maxRisePx = 8))
    }

    @Test fun tailLiftEasesInInsteadOfJumpingStraightToTarget() {
        // 上提走缓动曲线：起步每帧只挪一点点，之后逐帧加速，不会一帧跨掉半个行高。
        val motion = createTailLiftMotion()
        val frameNanos = 1_000_000_000L / 60
        var clock = 1L
        motion.step(60f, clock, 1f) // 第一帧只建立时钟。
        var lift = 0f
        val moves = mutableListOf<Float>()
        repeat(8) {
            clock += frameNanos
            moves += motion.step(60f - lift, clock, 1f)
            lift += moves.last()
        }
        assertTrue(moves.first() in 0f..3f)
        assertTrue((moves.maxOrNull() ?: 0f) <= 4f)
        assertTrue(moves.zipWithNext().all { (previous, next) -> next >= previous })
        assertTrue(lift > 0f)
        assertTrue(lift < 20f)
    }

}
