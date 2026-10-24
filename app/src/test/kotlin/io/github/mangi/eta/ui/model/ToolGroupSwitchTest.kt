package io.github.mangi.eta.ui.model

import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具页内联开关：只有「整组恰好由一个全局开关控制」时才能提供开关。
 * 否则关掉它只会关掉组里一半工具，是误导性状态。
 */
class ToolGroupSwitchTest {
    @Test fun groupsBackedByASingleSwitchExposeIt() {
        assertEquals(Prefs.Keys.AGENT_BROWSER_TOOLS, ToolGroupSwitch.keyFor("web"))
        assertEquals(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS, ToolGroupSwitch.keyFor("device_direct"))
        assertEquals(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS, ToolGroupSwitch.keyFor("device_sensitive"))
        assertEquals(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS, ToolGroupSwitch.keyFor("personal_data"))
        assertEquals(Prefs.Keys.AGENT_TERMINAL_TOOLS, ToolGroupSwitch.keyFor("terminal"))
        assertEquals(Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS, ToolGroupSwitch.keyFor("media_search"))
        assertEquals(Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS, ToolGroupSwitch.keyFor("self"))
    }

    @Test fun groupsWithoutASingleSwitchDoNotOfferOne() {
        // 记忆是按助手的开关，不是全局 Prefs：不能在工具页给一个全局开关假装控制它。
        assertNull(ToolGroupSwitch.keyFor("memory"))
        // 屏幕/文本/应用组没有对应的全局开关。
        assertNull(ToolGroupSwitch.keyFor("screen"))
        assertNull(ToolGroupSwitch.keyFor("text"))
        assertNull(ToolGroupSwitch.keyFor("app"))
        assertFalse(ToolGroupSwitch.showsInGroup("memory"))
    }

    @Test fun fileVisionDoesNotDuplicateTheTerminalSwitch() {
        // file_vision 与 terminal 共用一个开关：只在 terminal 组显示，避免两处开关互相打架。
        assertEquals(Prefs.Keys.AGENT_TERMINAL_TOOLS, ToolGroupSwitch.keyFor("file_vision"))
        assertFalse(ToolGroupSwitch.showsInGroup("file_vision"))
        assertTrue(ToolGroupSwitch.showsInGroup("terminal"))
    }

    @Test fun everyKeyIsALocalAgentKey() {
        // 开关必须落在运行时真正消费的键集合里，否则改了也不生效。
        val groups = listOf(
            "web", "device_direct", "device_sensitive", "personal_data",
            "file_vision", "terminal", "media_search", "self",
        )
        groups.forEach { group ->
            val key = requireNotNull(ToolGroupSwitch.keyFor(group)) { "$group 应有开关" }
            assertTrue("$key 必须是本地 Agent 开关", key in Prefs.Keys.LOCAL_AGENT_KEYS)
        }
    }
}
