package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感读两档拆分：个人信息（通讯录/短信/账单/健康…）与设备状态（设置/闹钟/位置/使用…）。
 * 目标是让用户能「让他看电量设闹钟，但别翻我的短信和账单」。
 */
class AgentPersonalDataSplitTest {
    private fun catalog(personal: Boolean, device: Boolean): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = false,
        personalDataReads = personal,
        deviceStateReads = device,
        // 冷门长尾与设备写操作默认折叠成域；这里断言完整目录，因此全部打开。
        openedToolGroups = AgentToolGroups.groups.map { it.id }.toSet(),
    )

    private fun catalogWith(
        sensitiveActions: Boolean,
        personal: Boolean,
        device: Boolean,
    ): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = sensitiveActions,
        personalDataReads = personal,
        deviceStateReads = device,
        openedToolGroups = AgentToolGroups.groups.map { it.id }.toSet(),
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    @Test fun bothTiersOnPublishesEverything() {
        val names = catalog(personal = true, device = true).names()
        listOf("search_contacts", "search_messages", "search_bills", "get_health_summary").forEach {
            assertTrue("$it 应下发", it in names)
        }
        listOf("get_setting", "list_alarms", "get_current_location", "device_status").forEach {
            assertTrue("$it 应下发", it in names)
        }
    }

    @Test fun turningOffPersonalKeepsDeviceStateReads() {
        val names = catalog(personal = false, device = true).names()
        // 个人信息类全部消失。
        listOf(
            "search_contacts", "search_call_history", "search_messages", "read_sms_code",
            "recent_notifications", "search_clipboard_history", "get_health_summary",
            "search_bills", "search_personal_orders", "search_coloros_notes",
            "read_note", "search_calendar_events", "list_calendars",
        ).forEach { assertFalse("$it 不应下发", it in names) }
        // 设备状态类仍然可用——这正是拆分的意义。
        listOf(
            "get_setting", "list_alarms", "list_active_timers", "get_current_location",
            "device_status", "network_info", "get_flashlight", "get_sound_state",
        ).forEach { assertTrue("$it 应仍然可用", it in names) }
    }

    @Test fun turningOffDeviceStateKeepsPersonalReads() {
        val names = catalog(personal = true, device = false).names()
        listOf("get_setting", "list_alarms", "get_current_location", "recent_app_activity").forEach {
            assertFalse("$it 不应下发", it in names)
        }
        listOf("search_contacts", "search_messages", "get_health_summary").forEach {
            assertTrue("$it 应仍然可用", it in names)
        }
    }

    @Test fun bothOffPublishesNoSensitiveReads() {
        val names = catalog(personal = false, device = false).names()
        assertFalse("search_contacts" in names)
        assertFalse("get_setting" in names)
        // 直达工具（闹钟/计时器/音量…）与敏感读是两组开关，不受影响。
        assertTrue("set_alarm" in names)
        assertTrue("device_status" in names)
    }

    @Test fun everySensitiveReadToolBelongsToExactlyOneTier() {
        // 两档之和必须正好是全部敏感读工具，且只在某一档里出现：
        // 否则两档都关掉时会有工具漏网，或关一档时误伤另一档。
        val both = catalog(personal = true, device = true).names()
        val personalOnly = catalog(personal = true, device = false).names()
        val deviceOnly = catalog(personal = false, device = true).names()
        val neither = catalog(personal = false, device = false).names()

        // device 档 = 关掉个人信息后仍在的敏感读；personal 档 = 关掉设备状态后仍在的。
        val deviceTier = both - personalOnly
        val personalTier = both - deviceOnly
        // 两档并集覆盖全部敏感读；交集为空。
        // 注意：both - personalTier - deviceTier 还包含非敏感读工具（GUI、闹钟等），
        // 所以只在「敏感读全集」上验证完整性。
        val sensitiveReads = personalTier + deviceTier
        assertEquals(emptySet<String>(), personalTier.intersect(deviceTier))
        assertEquals(53, sensitiveReads.size)
        // 两档都不为空，说明拆分真的分开了，而不是一刀切。
        assertTrue(personalTier.isNotEmpty())
        assertTrue(deviceTier.isNotEmpty())
        // 全部关闭后，这两档的工具一个都不剩。
        assertEquals(emptySet<String>(), neither.intersect(sensitiveReads))
    }

    @Test fun tiersAreIndependentOfSensitiveActions() {
        // 敏感动作组（勿扰/亮度/装应用…）由自己的开关控制，关掉个人信息档不影响它。
        val withPersonalOff = catalogWith(true, personal = false, device = true).names()
        assertTrue("set_do_not_disturb" in withPersonalOff)
        assertTrue("manage_app" in withPersonalOff)
        assertFalse("search_messages" in withPersonalOff)
    }
}
