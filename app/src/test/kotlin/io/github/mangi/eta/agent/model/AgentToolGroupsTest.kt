package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具域的分层加载：常驻工具永远下发，低频管理域只留一行索引，按下需展开。
 * 这里钉住三件事：折叠确实省下 schema、打开后域内工具真的出现、索引与目录互相对应。
 */
class AgentToolGroupsTest {
    private fun catalog(
        opened: Set<String> = emptySet(),
        selfSettings: Boolean = true,
        sensitiveReads: Boolean = true,
        sensitiveActions: Boolean = true,
        personalDataReads: Boolean = sensitiveReads,
        deviceStateReads: Boolean = sensitiveReads,
    ): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        selfSettingsTools = selfSettings,
        deviceDirectTools = true,
        deviceSensitiveReadTools = sensitiveReads,
        deviceSensitiveActionTools = sensitiveActions,
        personalDataReads = personalDataReads,
        deviceStateReads = deviceStateReads,
        openedToolGroups = opened,
    )

    private fun JSONArray.useGroupEnum(): Set<String> {
        val tool = (0 until length()).map { getJSONObject(it).getJSONObject("function") }
            .firstOrNull { it.getString("name") == AgentToolGroupCatalog.USE_GROUP } ?: return emptySet()
        val enum = tool.getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("group").getJSONArray("enum")
        return (0 until enum.length()).mapTo(linkedSetOf()) { enum.getString(it) }
    }

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length()).map { getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }

    @Test fun collapsedCatalogWithholdsAdminToolsAndCostsFarLess() {
        val collapsed = catalog()
        val opened = catalog(setOf(AgentToolGroups.SELF_SETTINGS_GROUP.id))

        val admin = AgentSelfSettingsToolCatalog.manageTools()
        // 折叠形态：域内工具全部不下发，只留一个开启入口。
        admin.forEach { assertFalse("$it 不应在折叠形态下发", it in collapsed.names()) }
        assertTrue(AgentToolGroupCatalog.USE_GROUP in collapsed.names())
        // 常驻的读/改配置不受影响。
        assertTrue(AgentSelfSettingsToolCatalog.GET_SETTINGS in collapsed.names())
        assertTrue(AgentSelfSettingsToolCatalog.SET_SETTINGS in collapsed.names())

        // 展开形态：域内工具出现。
        admin.forEach { assertTrue("$it 应随域打开而下发", it in opened.names()) }
        // 开启入口仍然存在，因为还有别的域没打开；只有全部域都打开时它才该消失。
        assertTrue(AgentToolGroupCatalog.USE_GROUP in opened.names())
        val allOpened = catalog(AgentToolGroups.groups.map { it.id }.toSet())
        assertFalse(AgentToolGroupCatalog.USE_GROUP in allOpened.names())

        // 折叠必须真的省下 token，否则这套机制没有意义。
        val collapsedTokens = AgentRequestTokenEstimate.tools(collapsed)
        val openedTokens = AgentRequestTokenEstimate.tools(opened)
        assertTrue("折叠应省下 schema：collapsed=$collapsedTokens opened=$openedTokens",
            collapsedTokens < openedTokens)
    }

    @Test fun indexListsExactlyTheGroupsStillClosed() {
        val allAllowed = AgentToolGroups.availability(emptySet()) { true }
        val closedIndex = requireNotNull(AgentToolGroups.indexMessage(allAllowed))
        assertTrue(closedIndex.contains(AgentToolGroups.SELF_SETTINGS_GROUP.id))
        assertTrue(closedIndex.contains("tools_use_group"))

        // 全部打开后就没有索引消息了：不该再提示模型去打开任何域。
        val allOpened = AgentToolGroups.groups.map { it.id }.toSet()
        assertEquals(null, AgentToolGroups.indexMessage(AgentToolGroups.availability(allOpened) { true }))
    }

    @Test fun openingNeverWidensPermissions() {
        // 打开域只是让 schema 进入目录；能力过滤仍在同一处生效，因此无 Root 时
        // 域内工具不会因为「被打开」而绕过 ROOT_REQUIRED 之类的判定。
        val rootless = AgentToolCatalog.build(
            terminalTools = false, browserTools = false,
            openedToolGroups = AgentToolGroups.groups.map { it.id }.toSet(),
            capabilities = io.github.mangi.eta.agent.tool.AgentToolCapabilities(rootAvailable = false),
        )
        val rootlessNames = rootless.names()
        // 每个下发的工具都必须能说明它为什么可用；不可用的必须不在列表里。
        rootlessNames.forEach { name ->
            val code = io.github.mangi.eta.agent.tool.AgentToolCapabilities(rootAvailable = false)
                .unavailableCode(name)
            assertTrue("$name 不应在无 Root 时下发（$code）", code == null)
        }
    }

    @Test fun everydayDeviceQueriesStayResidentWhileTailQueriesAreDeferred() {
        val collapsed = catalog().names()
        // 日常高频的个人数据/设备查询必须常驻：折叠它们会让日常任务多一次往返。
        listOf(
            "search_calendar_events", "search_messages", "recent_notifications", "search_contacts",
            "list_installed_apps", "get_current_location", "get_device_state", "search_files",
            "read_sms_code", "get_setting", "get_sound_state", "recent_app_activity",
        ).forEach { assertTrue("$it 应保持常驻", it in collapsed) }
        // 冷门长尾（出行/账单/记忆合集/便签录音/聊天图片）折叠。
        listOf(
            "search_flights", "search_trains", "search_hotels", "search_bills", "summarize_bills",
            "search_memory_collections", "search_saved_places", "search_daily_events", "search_todos",
            "search_wechat_chat_images",
        ).forEach { assertFalse("$it 应折叠", it in collapsed) }
        // 改设备的写操作默认折叠（省 token，也少误触）。
        listOf("set_setting", "manage_app", "system_property", "delete_alarm")
            .forEach { assertFalse("$it 应折叠", it in collapsed) }
    }

    @Test fun switchedOffGroupIsNeitherAdvertisedNorOpenable() {
        // 关掉设备写开关：动作域既不该出现在 tools_use_group 的枚举里，也不该出现在系统提示索引里。
        val off = catalog(sensitiveActions = false)
        assertFalse(AgentToolGroups.DEVICE_ACTION_GROUP.id in off.useGroupEnum())
        val availability = AgentToolGroups.availability(emptySet()) { group ->
            group.id != AgentToolGroups.DEVICE_ACTION_GROUP.id
        }
        assertFalse(AgentToolGroups.DEVICE_ACTION_GROUP.id in AgentToolGroups.openableIds(availability))
        val index = requireNotNull(AgentToolGroups.indexMessage(availability))
        assertFalse("关掉的域不该出现在索引里", index.contains(AgentToolGroups.DEVICE_ACTION_GROUP.id))

        // 敏感读整体关闭：冷门只读域同样既不可打开也不宣传。
        val readsOff = catalog(sensitiveReads = false)
        assertFalse(AgentToolGroups.DEVICE_TAIL_READ_GROUP.id in readsOff.useGroupEnum())
        // 自设置关闭：自设置域不再宣传，但设备域仍在枚举里（tools_use_group 不再被它连带禁用）。
        val selfOff = catalog(selfSettings = false)
        assertFalse(AgentToolGroups.SELF_SETTINGS_GROUP.id in selfOff.useGroupEnum())
        assertTrue(AgentToolGroups.DEVICE_ACTION_GROUP.id in selfOff.useGroupEnum())
    }

    @Test fun allGroupsOpenedLeavesNoUseGroupTool() {
        // 全部打开后没有可打开的域，tools_use_group 不该下发（否则是一个必然失败的枚举）。
        val all = catalog(AgentToolGroups.groups.map { it.id }.toSet())
        assertFalse(AgentToolGroupCatalog.USE_GROUP in all.names())
        assertEquals(emptySet<String>(), all.useGroupEnum())
    }

    @Test fun availabilityDropsGroupsWhoseSwitchTurnedOffAfterOpening() {
        val opened = AgentToolGroups.groups.map { it.id }.toSet()
        // 用户先打开了域，随后又把开关关掉：已打开的记录不能让域复活。
        val availability = AgentToolGroups.availability(opened) { group ->
            group.id != AgentToolGroups.DEVICE_ACTION_GROUP.id
        }
        assertFalse("开关关掉后已打开集合里不应再有该域",
            AgentToolGroups.DEVICE_ACTION_GROUP.id in availability.opened)
        assertTrue(AgentToolGroups.SELF_SETTINGS_GROUP.id in availability.opened)
        assertEquals(emptySet<String>(), availability.openable)
    }

    @Test fun groupOpeningIsNotPureReadAndNeverParallel() {
        // 开启动作会改变下一轮目录，必须留在串行路径上。
        assertFalse(AgentPureReadTools.isPureRead(AgentToolGroupCatalog.USE_GROUP))
    }

    @Test fun unknownGroupIsReportedWithTheKnownIds() {
        val result = JSONObject(AgentToolGroupCatalog.unknownGroup("nope", listOf("a", "b")))
        assertFalse(result.getBoolean("ok"))
        assertEquals("UNKNOWN_TOOL_GROUP", result.getString("code"))
        assertEquals(listOf("a", "b"), (0 until result.getJSONArray("available_groups").length())
            .map { result.getJSONArray("available_groups").getString(it) })
    }
}
