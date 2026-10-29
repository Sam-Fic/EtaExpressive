package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备侧工具的组归属：直达 + 敏感写 = 设备控制组，个人内容读 + 设备状态读 = 个人数据组。
 *
 * 断言的是**集合级**归属而不是逐个工具名：组成员由 [AgentToolGroups] 单点定义，目录必须与
 * 它完全一致，否则设置页会宣传一个目录里根本不存在的工具（或者反过来漏掉一个）。
 */
class AgentDeviceToolCatalogTest {
    @Test fun deviceGroupIsExactlyDirectToolsPlusSensitiveActions() {
        assertEquals(
            AgentToolGroups.DEVICE_GROUP.toolNames + AgentToolGroups.CALENDAR_GROUP.toolNames,
            names(deviceTools = true, personalTools = false),
        )
    }

    @Test fun calendarWritesAreWithheldUntilTheirOwnGroupOpens() {
        val deferred = AgentToolCatalog.build(
            basicTools = false, terminalTools = false, browserTools = false, screenTools = false,
            deviceTools = true, personalTools = false, extensionsTools = false,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            openedToolGroups = setOf(AgentToolGroups.DEVICE_GROUP.id),
        ).toolNames()
        assertEquals(AgentToolGroups.DEVICE_GROUP.toolNames, deferred)

        val opened = AgentToolCatalog.build(
            basicTools = false, terminalTools = false, browserTools = false, screenTools = false,
            deviceTools = true, personalTools = false, extensionsTools = false,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            openedToolGroups = AgentToolGroups.allIds,
        ).toolNames()
        assertEquals(
            AgentToolGroups.CALENDAR_GROUP.toolNames,
            opened - AgentToolGroups.DEVICE_GROUP.toolNames,
        )
    }

    @Test fun personalGroupIsExactlyTheTwoReadTiers() {
        assertEquals(
            AgentDeviceToolCatalog.personalReadToolNames() + AgentDeviceToolCatalog.deviceStateReadToolNames(),
            names(deviceTools = false, personalTools = true),
        )
    }

    @Test fun bothGroupsOffPublishesNothingOnTheDeviceSide() {
        assertEquals(emptySet<String>(), names(deviceTools = false, personalTools = false))
    }

    @Test fun mediaSearchStaysBehindItsOwnSwitchInsideThePersonalGroup() {
        assertFalse(AgentMediaSearchToolCatalog.SEARCH_MEDIA in names(deviceTools = false, personalTools = true))
        val withMedia = AgentToolCatalog.build(
            basicTools = false,
            terminalTools = false,
            browserTools = false,
            screenTools = false,
            personalTools = true,
            extensionsTools = false,
            mediaSearchTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            openedToolGroups = AgentToolGroups.allIds,
        ).toolNames()
        assertEquals(AgentToolGroups.PERSONAL_GROUP.toolNames, withMedia)
    }

    @Test fun representativeToolsLandInTheGroupUsersWouldExpect() {
        val device = names(deviceTools = true, personalTools = false)
        val personal = names(deviceTools = false, personalTools = true)

        // 免 Root 直达与改设备的写操作同属一组；细粒度取舍交给单工具开关。
        listOf(
            "set_alarm", "set_ringer_mode", "set_volume", "media_control", "inspect_app",
            "set_do_not_disturb", "set_brightness", "screen_action", "manage_app", "package_control",
            "power_control", "system_property", "manage_file", "display_control", "deep_launch",
            "user_control", "appops_control", "connect_wifi", "set_timezone", "ime_control",
            "update_alarm", "delete_alarm",
        ).forEach { assertTrue("$it 应属设备控制组", it in device) }

        // 个人内容读与设备状态读同属一组。
        listOf(
            "read_sms_code", "search_messages", "search_contacts", "get_health_summary",
            "search_coloros_notes", "search_coloros_recordings", "search_recording_summaries",
            "search_coloros_memories", "search_notification_history", "search_saved_places",
            "search_personal_orders", "search_qq_chat_images", "search_wechat_chat_images",
            "search_clipboard_history",
            "get_setting", "search_settings", "list_alarms", "list_active_timers",
            "get_current_location", "get_device_environment", "recent_app_activity",
            "app_usage_summary", "list_installed_apps", "dumpsys_diag",
        ).forEach { assertTrue("$it 应属个人数据组", it in personal) }

        assertEquals("两组不得互相渗透", emptySet<String>(), device.intersect(personal))
        assertFalse("send_message" in device + personal)
        assertFalse("read_image" in device + personal)
    }

    @Test fun selfAuthoringToolsArePublishedWithTheExtensionsGroup() {
        val deferred = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            extensionsTools = true,
        ).toolNames()
        assertFalse("扩展组默认折叠，省固定 token", "eta_author_tool" in deferred)

        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            extensionsTools = true,
            openedToolGroups = setOf(AgentToolGroups.EXTENSIONS_GROUP.id),
        ).toolNames()

        // 自进化：模型写工具与列出工具的接口。
        assertTrue("eta_author_tool" in tools)
        assertTrue("eta_list_recipes" in tools)
    }

    @Test fun selfAuthoringToolsDisappearWithTheExtensionsSwitch() {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            extensionsTools = false,
            openedToolGroups = AgentToolGroups.allIds,
        ).toolNames()

        assertFalse("eta_author_tool" in tools)
        assertFalse("eta_list_recipes" in tools)
    }

    @Test fun onDeviceActionModelIsNotPartOfAnyConversationModelCatalog() {
        val payload = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceTools = true,
            personalTools = true,
            openedToolGroups = AgentToolGroups.allIds,
        ).toString()

        assertFalse(payload.contains("device_actions"))
        assertFalse(payload.contains("FunctionGemma"))
        assertFalse(payload.contains("端侧"))
    }

    /** 只留设备侧两组：其余组关掉，断言才是「这个组下发了什么」而不是「目录里还有什么」。 */
    private fun names(deviceTools: Boolean, personalTools: Boolean): Set<String> =
        AgentToolCatalog.build(
            basicTools = false,
            terminalTools = false,
            browserTools = false,
            screenTools = false,
            deviceTools = deviceTools,
            personalTools = personalTools,
            extensionsTools = false,
            capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
            // 全部组默认延迟；这里断言完整目录，因此视为都已打开。
            openedToolGroups = AgentToolGroups.allIds,
        ).toolNames()

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
