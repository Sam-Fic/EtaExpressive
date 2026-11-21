package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「看屏幕」意图正则的判定口径：
 * - 屏幕名词直接命中（不再要求与内容动词共现）；
 * - 没有屏幕名词时，可视指代 + 内容动作共现命中；
 * - 屏幕控制/存图语境与无关指令不命中。
 */
class AgentScreenIntentTest {

    @Test fun `屏幕名词直接命中`() {
        assertTrue(AgentScreenIntent.matches("帮我整理屏幕内容"))
        assertTrue(AgentScreenIntent.matches("屏幕上的东西是什么"))
        assertTrue(AgentScreenIntent.matches("帮我把屏幕上的日程加到日历"))
        assertTrue(AgentScreenIntent.matches("你看看我的屏幕上有什么"))
        assertTrue(AgentScreenIntent.matches("帮我添加屏幕上的日程"))
        assertTrue(AgentScreenIntent.matches("帮我翻译屏幕内容"))
        assertTrue(AgentScreenIntent.matches("What's on my screen"))
        assertTrue(AgentScreenIntent.matches("Translate my screen"))
    }

    @Test fun `可视指代与内容动作共现命中`() {
        assertTrue(AgentScreenIntent.matches("帮我看看这个"))
        assertTrue(AgentScreenIntent.matches("这上面写的是什么"))
        assertTrue(AgentScreenIntent.matches("帮我翻译一下这张图"))
        assertTrue(AgentScreenIntent.matches("截图里的字念一下"))
        assertTrue(AgentScreenIntent.matches("What's this"))
    }

    @Test fun `控制与存图语境不命中`() {
        assertFalse(AgentScreenIntent.matches("把屏幕调亮一点"))
        assertFalse(AgentScreenIntent.matches("帮我截个屏"))
        assertFalse(AgentScreenIntent.matches("锁屏"))
        assertFalse(AgentScreenIntent.matches("turn up the brightness"))
    }

    @Test fun `指代但没有内容动作不命中`() {
        assertFalse(AgentScreenIntent.matches("帮我截图"))
        assertFalse(AgentScreenIntent.matches("这个文件怎么改"))
    }

    @Test fun `无关指令与空串不命中`() {
        assertFalse(AgentScreenIntent.matches("帮我写一首诗"))
        assertFalse(AgentScreenIntent.matches("明天天气怎么样"))
        assertFalse(AgentScreenIntent.matches(""))
    }
}
