package io.github.mangi.eta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 主应用前台唤起令牌与已消费水位（[MainActivity] companion 进程级状态）的回归护栏。
 *
 * 锁的用户可感知行为（41336b3e7 切会话误入听写修复）：
 * - 唤起一次令牌恰好 +1：一次唤醒只开一次听写/聚焦；
 * - 水位只取 max 不回退：乱序上报不把记账拉回去；
 * - 「令牌 > 水位」消费闸门在消费上报后即闭合：composer 随会话切换整树重建时，
 *   用水位播种的新实例不再把旧唤起令牌当成新唤起重放（否则每次切会话都误开听写）；
 * - 听写与聚焦两对令牌/水位各自独立，互不串扰。
 *
 * companion 状态随进程存活且无重置口（刻意设计），因此所有断言都基于测试开始时
 * 读取的相对增量，不假设绝对值。
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityDictationWatermarkTest {

    @Test
    fun `唤起令牌单调递增每次恰好加一`() {
        val before = MainActivity.inAppDictationRequest.value
        MainActivity.requestInAppDictation()
        assertEquals(before + 1, MainActivity.inAppDictationRequest.value)
        MainActivity.requestInAppDictation()
        assertEquals(before + 2, MainActivity.inAppDictationRequest.value)
    }

    @Test
    fun `水位只进不退取历史最大值`() {
        val baseline = MainActivity.consumedInAppDictationRequest.value
        // 乱序迟到的小令牌不把水位拉回去。
        MainActivity.markInAppDictationConsumed(baseline - 3)
        assertEquals(baseline, MainActivity.consumedInAppDictationRequest.value)
        MainActivity.markInAppDictationConsumed(baseline + 5)
        assertEquals(baseline + 5, MainActivity.consumedInAppDictationRequest.value)
        MainActivity.markInAppDictationConsumed(baseline + 2)
        assertEquals(baseline + 5, MainActivity.consumedInAppDictationRequest.value)
    }

    @Test
    fun `消费上报后重放防护闸门闭合`() {
        // 当前令牌（不必真唤起）：消费它并上报水位后，本实例的水位已覆盖该令牌，
        // 随后整树重建的新实例据此播种——ComposerBody 的「令牌 > 记账」闸门不再放行。
        val token = MainActivity.inAppDictationRequest.value
        MainActivity.markInAppDictationConsumed(token)
        val consumed = MainActivity.consumedInAppDictationRequest.value
        assertTrue(consumed >= token)
        assertFalse("已消费的令牌不得再触发唤起（切会话误入听写回归）", token > consumed)
    }

    @Test
    fun `听写与聚焦两对令牌水位互不污染`() {
        val dictationToken = MainActivity.inAppDictationRequest.value
        val focusToken = MainActivity.inAppFocusRequest.value
        val focusConsumed = MainActivity.consumedInAppFocusRequest.value
        val dictationConsumed = MainActivity.consumedInAppDictationRequest.value

        MainActivity.requestInAppDictation()
        MainActivity.markInAppDictationConsumed(dictationToken + 1)

        assertEquals(focusToken, MainActivity.inAppFocusRequest.value)
        assertEquals(focusConsumed, MainActivity.consumedInAppFocusRequest.value)

        MainActivity.requestInAppFocus()
        MainActivity.markInAppFocusConsumed(focusToken + 1)

        assertEquals(dictationToken + 1, MainActivity.inAppDictationRequest.value)
        assertEquals(dictationConsumed + 1, MainActivity.consumedInAppDictationRequest.value)
    }
}
