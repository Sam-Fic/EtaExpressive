package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具页分组开关：分组 id 取自 [AgentToolGroups]，这里断言「组 → 用户开关」的映射。
 * 每个组恰好由一个全局开关控制；组内的细粒度收紧由单工具禁用集承接。
 */
class ToolGroupSwitchTest {
    @Test fun everyGroupMapsToItsOwnSwitch() {
        val expected = mapOf(
            "basic" to Prefs.Keys.AGENT_BASIC_TOOLS,
            "screen" to Prefs.Keys.AGENT_SCREEN_TOOLS,
            "web" to Prefs.Keys.AGENT_BROWSER_TOOLS,
            "terminal" to Prefs.Keys.AGENT_TERMINAL_TOOLS,
            "device" to Prefs.Keys.AGENT_DEVICE_TOOLS,
            "calendar" to Prefs.Keys.AGENT_DEVICE_TOOLS,
            "personal" to Prefs.Keys.AGENT_PERSONAL_TOOLS,
            "device_state" to Prefs.Keys.AGENT_PERSONAL_TOOLS,
            "extensions" to Prefs.Keys.AGENT_EXTENSIONS_TOOLS,
        )
        // 分组定义是唯一来源：新增一个组而忘了给开关，这条会先失败。
        assertEquals(AgentToolGroups.groups.map { it.id }.toSet(), expected.keys)
        expected.forEach { (group, key) ->
            assertEquals("$group 的开关", key, ToolGroupSwitch.keyFor(group))
            assertTrue(ToolGroupSwitch.showsInGroup(group))
        }
    }

    @Test fun retiredAndCrossCuttingGroupsDoNotOfferASwitch() {
        // 记忆是按助手的开关，不是全局 Prefs：不能在工具页给一个全局开关假装控制它。
        assertNull(ToolGroupSwitch.keyFor("memory"))
        assertFalse(ToolGroupSwitch.showsInGroup("memory"))
        // 旧展示分组已被并入新组：read_image 属于终端组，媒体搜索属于个人数据组。
        assertNull(ToolGroupSwitch.keyFor("file_vision"))
        assertNull(ToolGroupSwitch.keyFor("media_search"))
        assertNull(ToolGroupSwitch.keyFor("app"))
        assertNull(ToolGroupSwitch.keyFor("text"))
    }

    @Test fun everyKeyIsALocalAgentKey() {
        // 开关必须落在运行时真正消费的键集合里，否则改了也不生效。
        AgentToolGroups.groups.forEach { group ->
            val key = requireNotNull(ToolGroupSwitch.keyFor(group.id)) { "${group.id} 应有开关" }
            assertTrue("$key 必须是本地 Agent 开关", key in Prefs.Keys.LOCAL_AGENT_KEYS)
            assertTrue("$key 必须有默认值", key in Prefs.Keys.BOOLEAN_DEFAULTS)
        }
    }
}
