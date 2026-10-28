package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 个人数据组与设备控制组的门禁。
 *
 * 旧版是「敏感读总闸 + 个人信息/设备状态两档 + 敏感写」四个开关，用户很难会意；
 * 现在只剩两个组开关，原来的档位差异由单工具禁用集承接——能力没减，开关少了。
 */
class AgentPersonalToolGroupTest {
    private fun catalog(
        personalTools: Boolean = true,
        deviceTools: Boolean = true,
        disabledTools: Set<String> = emptySet(),
        openedToolGroups: Set<String> = AgentToolGroups.allIds,
    ): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        deviceTools = deviceTools,
        personalTools = personalTools,
        capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        disabledTools = disabledTools,
        openedToolGroups = openedToolGroups,
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    @Test fun groupOnPublishesEveryPersonalAndDeviceStateRead() {
        val names = catalog().names()
        val declared = AgentDeviceToolCatalog.personalReadToolNames() +
            AgentDeviceToolCatalog.deviceStateReadToolNames()
        assertEquals("组定义与目录必须同源", emptySet<String>(), declared - names)
    }

    @Test fun groupOffRemovesEveryReadButKeepsDeviceControl() {
        val names = catalog(personalTools = false).names()
        val declared = AgentDeviceToolCatalog.personalReadToolNames() +
            AgentDeviceToolCatalog.deviceStateReadToolNames()
        assertEquals(emptySet<String>(), declared.intersect(names))
        // 设备控制是另一组，不受个人数据开关影响。
        listOf("set_alarm", "device_status", "set_do_not_disturb", "manage_app").forEach {
            assertTrue("$it 应仍然可用", it in names)
        }
    }

    @Test fun deviceGroupIsIndependentOfPersonalGroup() {
        val names = catalog(personalTools = true, deviceTools = false).names()
        listOf("set_alarm", "device_status", "set_do_not_disturb", "manage_app").forEach {
            assertFalse("$it 不应下发", it in names)
        }
        listOf("search_contacts", "get_setting", "list_alarms").forEach {
            assertTrue("$it 应仍然可用", it in names)
        }
    }

    @Test fun perToolSwitchCarvesOutSingleReadsWithoutTouchingTheRest() {
        // 「让他看电量设闹钟，但别翻我的短信和账单」——组开关之下的细粒度控制。
        val names = catalog(disabledTools = setOf("search_messages", "search_bills")).names()
        assertFalse("search_messages" in names)
        assertFalse("search_bills" in names)
        assertTrue("search_contacts" in names)
        assertTrue("get_setting" in names)
        assertTrue("set_alarm" in names)
    }

    @Test fun bothGroupsAreDeferredUntilOpened() {
        val closed = catalog(openedToolGroups = emptySet()).names()
        listOf("search_contacts", "get_setting", "set_alarm", "manage_app").forEach {
            assertFalse("$it 未打开组时不应下发", it in closed)
        }
        // 索引入口仍然在场，模型才知道有这些组可以打开。
        assertTrue(AgentToolGroupCatalog.USE_GROUP in closed)
        val opened = catalog(openedToolGroups = setOf(AgentToolGroups.PERSONAL_GROUP.id)).names()
        assertTrue("search_contacts" in opened)
        assertFalse("set_alarm" in opened)
    }

    @Test fun readTiersStayDisjointInsideOneGroup() {
        // 两档合并成一组后，成员划分仍然要互斥：迁移把它们写进单工具禁用集时才不会互相误伤。
        val personal = AgentDeviceToolCatalog.personalReadToolNames()
        val deviceState = AgentDeviceToolCatalog.deviceStateReadToolNames()
        assertEquals(emptySet<String>(), personal.intersect(deviceState))
        assertEquals(
            personal + deviceState,
            (personal + deviceState).intersect(AgentToolGroups.PERSONAL_GROUP.toolNames),
        )
    }
}
