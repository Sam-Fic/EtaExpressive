package io.github.mangi.eta.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「看屏幕」意图正则的判定口径：三条典型指令命中；控制/存图语境与无关指令不命中。 */
class AgentScreenIntentTest {

    @Test fun `命中三条典型指令`() {
        assertTrue(AgentScreenIntent.matches("你看看我的屏幕上有什么"))
        assertTrue(AgentScreenIntent.matches("帮我添加屏幕上的日程"))
        assertTrue(AgentScreenIntent.matches("帮我翻译屏幕内容"))
    }

    @Test fun `命中常见变体与英文`() {
        assertTrue(AgentScreenIntent.matches("帮我看一下屏幕上的字"))
        assertTrue(AgentScreenIntent.matches("屏幕上是什么"))
        assertTrue(AgentScreenIntent.matches("总结一下屏幕里的内容"))
        assertTrue(AgentScreenIntent.matches("What's on my screen"))
        assertTrue(AgentScreenIntent.matches("Translate my screen"))
    }

    @Test fun `控制与存图语境不命中`() {
        assertFalse(AgentScreenIntent.matches("把屏幕调亮一点"))
        assertFalse(AgentScreenIntent.matches("帮我截个屏"))
        assertFalse(AgentScreenIntent.matches("锁屏"))
        assertFalse(AgentScreenIntent.matches("turn up the brightness"))
    }

    @Test fun `无关指令与空串不命中`() {
        assertFalse(AgentScreenIntent.matches("帮我写一首诗"))
        assertFalse(AgentScreenIntent.matches("明天天气怎么样"))
        assertFalse(AgentScreenIntent.matches(""))
    }
}
