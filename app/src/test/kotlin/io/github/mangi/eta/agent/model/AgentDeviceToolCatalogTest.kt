package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceToolCatalogTest {
    @Test
    fun riskGroupsExposeOnlyTheirOwnTools() {
        val none = names(false, false, false)
        val direct = names(true, false, false)
        val reads = names(false, true, false)
        val actions = names(false, false, true)

        assertFalse("set_alarm" in none)
        assertTrue("set_alarm" in direct)
        assertFalse("device_actions" in direct)
        assertFalse("device_actions" in none)
        assertFalse("read_sms_code" in direct)
        assertTrue("read_sms_code" in reads)
        assertTrue("search_coloros_notes" in reads)
        assertTrue("search_coloros_recordings" in reads)
        assertTrue("search_recording_summaries" in reads)
        assertTrue("search_coloros_memories" in reads)
        assertTrue("search_notification_history" in reads)
        assertTrue("recent_app_activity" in reads)
        assertTrue("app_usage_summary" in reads)
        assertTrue("get_current_location" in reads)
        assertTrue("get_device_environment" in reads)
        assertTrue("list_alarms" in reads)
        assertTrue("list_active_timers" in reads)
        assertTrue("search_clipboard_history" in reads)
        assertTrue("get_health_summary" in reads)
        assertTrue("search_saved_places" in reads)
        assertTrue("search_personal_orders" in reads)
        assertTrue("search_qq_chat_images" in reads)
        assertTrue("search_wechat_chat_images" in reads)
        assertFalse("read_image" in reads)
        assertFalse("search_messages" in direct)
        assertFalse("send_message" in reads)
        assertFalse("send_message" in actions)
        assertTrue("app_state_control" in actions)
        // 日程与闹钟管理属于敏感操作组：无 Root 时对模型隐藏，不放入直达/只读组。
        assertTrue("create_calendar_event" in actions)
        assertTrue("update_calendar_event" in actions)
        assertTrue("delete_calendar_event" in actions)
        assertTrue("delete_alarm" in actions)
        assertTrue("update_alarm" in actions)
        assertFalse("create_calendar_event" in direct)
        assertFalse("create_calendar_event" in reads)
        assertFalse("delete_alarm" in reads)
        // 系统控制：铃声模式免 Root 直达；勿扰/亮度/电源/应用管理为敏感操作；应用清单为敏感只读。
        assertTrue("set_ringer_mode" in direct)
        assertFalse("set_ringer_mode" in reads)
        assertTrue("set_do_not_disturb" in actions)
        assertTrue("set_brightness" in actions)
        assertTrue("screen_action" in actions)
        assertTrue("manage_app" in actions)
        assertFalse("set_do_not_disturb" in direct)
        assertFalse("manage_app" in reads)
        assertTrue("list_installed_apps" in reads)
        assertFalse("list_installed_apps" in direct)
        // Root 深度控制：写操作归入敏感操作组；诊断读取归入敏感只读组。
        assertTrue("manage_file" in actions)
        assertTrue("system_property" in actions)
        assertTrue("display_control" in actions)
        assertTrue("deep_launch" in actions)
        assertTrue("package_control" in actions)
        assertTrue("user_control" in actions)
        assertTrue("appops_control" in actions)
        assertTrue("power_control" in actions)
        assertTrue("ime_control" in actions)
        assertTrue("connect_wifi" in actions)
        assertTrue("set_timezone" in actions)
        assertTrue("search_settings" in reads)
        assertTrue("dumpsys_diag" in reads)
        assertFalse("manage_file" in direct)
        assertFalse("system_property" in reads)
        assertFalse("power_control" in direct)
        assertFalse("dumpsys_diag" in actions)
    }

    @Test
    fun selfAuthoringToolsArePublishedWithTheSelfSettingsGroup() {
        val deferred = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            selfSettingsTools = true,
        ).toolNames()
        assertFalse("低频率自建工具默认折叠，省固定 token", "eta_author_tool" in deferred)

        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            selfSettingsTools = true,
            openedToolGroups = setOf(AgentToolGroups.SELF_SETTINGS_GROUP.id),
        ).toolNames()

        // 自进化：模型写工具与列出工具的接口。
        assertTrue("eta_author_tool" in tools)
        assertTrue("eta_list_recipes" in tools)
    }

    @Test
    fun selfAuthoringToolsDisappearWithTheSelfSettingsSwitch() {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            selfSettingsTools = false,
        ).toolNames()

        assertFalse("eta_author_tool" in tools)
        assertFalse("eta_list_recipes" in tools)
    }

    @Test
    fun onDeviceActionModelIsNotPartOfAnyConversationModelCatalog() {
        val payload = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            openedToolGroups = AgentToolGroups.groups.map { it.id }.toSet(),
        ).toString()

        assertFalse(payload.contains("device_actions"))
        assertFalse(payload.contains("FunctionGemma"))
        assertFalse(payload.contains("端侧"))
    }

    private fun names(
        direct: Boolean,
        reads: Boolean,
        actions: Boolean,
    ): Set<String> {
        // 这里断言风险组各自暴露哪些工具，与折叠无关：全部域打开，避免默认折叠把它们藏起来。
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = direct,
            deviceSensitiveReadTools = reads,
            deviceSensitiveActionTools = actions,
            openedToolGroups = AgentToolGroups.groups.map { it.id }.toSet(),
        )
        return tools.toolNames()
    }

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
