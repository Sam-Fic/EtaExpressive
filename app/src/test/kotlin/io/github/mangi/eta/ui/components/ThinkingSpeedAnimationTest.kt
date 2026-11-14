package io.github.mangi.eta.ui.components

import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.data.model.GptSpeedMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GPT 速度切换的动画策略与闸门是纯逻辑，直接在这里验证：
 * 一次长按 = 一整圈旋转 + 单峰缩放；动画中拒绝新的长按（不排队）。
 * 档位颜色走官方 colorScheme 角色（快速=tertiary、极速=error，见 etaGptSpeedColor），
 * 不再有品牌色字面量，因此不在 JVM 纯逻辑测试范围内（该映射是 @Composable）。
 */
class ThinkingSpeedAnimationTest {

    @Test
    fun oneAcceptedLongPressIsExactlyOneFullTurn() {
        assertEquals(0f, gptSpeedRotationDegrees(0f), 0f)
        assertEquals(360f, gptSpeedRotationDegrees(1f), 0f)
        // 进度越界被夹取，不会出现多圈旋转。
        assertEquals(360f, gptSpeedRotationDegrees(1.8f), 0f)
        assertEquals(0f, gptSpeedRotationDegrees(-0.5f), 0f)
    }

    @Test
    fun scaleStartsAtIdentityPeaksAtMidThenReturnsToIdentity() {
        assertEquals(1f, gptSpeedScale(0f), 1e-5f)
        assertEquals(1.12f, gptSpeedScale(0.5f), 1e-5f)
        assertEquals(1f, gptSpeedScale(1f), 1e-5f)
        assertTrue("缩放应先放大", gptSpeedScale(0.25f) > gptSpeedScale(0f))
        assertTrue("缩放应再回落", gptSpeedScale(0.75f) < gptSpeedScale(0.5f))
    }

    @Test
    fun gateAcceptsFirstPressRejectsWhileAnimatingAndRecoversAfterFinishOrReset() {
        val gate = GptSpeedAnimationGate()
        val first = gate.request()
        assertTrue(first != 0)
        // 动画进行中拒绝新的长按：不排队、不累计，避免截断当前一圈。
        assertTrue(gate.request() == 0)
        assertTrue(gate.request() == 0)
        gate.finish(first)
        assertFalse(gate.animating)
        val second = gate.request()
        assertTrue(second != 0)
        // 非 GPT 模型切换立即清动画，随后可再次长按。
        gate.reset()
        assertFalse(gate.animating)
        val third = gate.request()
        assertTrue(third != 0)
        // 旧动画被取消后延迟执行的 finish(oldToken) 不能关掉新动画的门禁。
        gate.finish(second)
        assertTrue(gate.animating)
        gate.finish(third)
        assertFalse(gate.animating)
    }

    @Test
    fun accessibilityAndToastSpellOutSpeedAndPossibleCost() {
        val description = gptSpeedChipDescription("思考强度：高", GptSpeedMode.ULTRA_FAST)
        assertTrue(description.contains("思考强度：高"))
        assertTrue(description.contains("极速"))
        // 长按 label 不能回落到默认「协作设置」。
        assertNotEquals("协作设置", gptSpeedLongPressLabel())
        val toast = gptSpeedToastMessage(GptSpeedMode.FAST)
        assertTrue(toast.contains("快速"))
        assertTrue(toast.contains("费用"))
    }
}
