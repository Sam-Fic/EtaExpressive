package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具组的分层加载：只有基础交互常驻，其余组只留一行索引，按下需展开。
 * 这里钉住四件事：折叠确实省下 schema、打开后组内工具真的出现、索引与目录互相对应、
 * 分组定义本身自洽（一个工具只属于一个组）。
 */
class AgentToolGroupsTest {
    private fun catalog(
        opened: Set<String> = emptySet(),
        basicTools: Boolean = true,
        screenTools: Boolean = true,
        browserTools: Boolean = false,
        terminalTools: Boolean = false,
        deviceTools: Boolean = true,
        personalTools: Boolean = true,
        extensionsTools: Boolean = true,
        disabledTools: Set<String> = emptySet(),
    ): JSONArray = AgentToolCatalog.build(
        basicTools = basicTools,
        terminalTools = terminalTools,
        browserTools = browserTools,
        screenTools = screenTools,
        deviceTools = deviceTools,
        personalTools = personalTools,
        extensionsTools = extensionsTools,
        capabilities = AgentToolCapabilities(rootAvailable = true, lsposedAvailable = true),
        disabledTools = disabledTools,
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

    /** 索引里一行的前缀；直接用组 id 做子串匹配会撞上别的组摘要里的 "device" 之类的词。 */
    private fun indexEntry(groupId: String): String = "id=$groupId |"

    @Test fun onlyTheBasicGroupIsResident() {
        val resident = catalog().names()
        AgentToolGroups.deferredGroups.forEach { group ->
            group.toolNames.forEach { assertFalse("$it 属 ${group.id} 组，不该常驻", it in resident) }
        }
        // 常驻部分必须正好是基础组 + 一个开启入口，否则「全延迟」名不副实。
        assertEquals(
            emptySet<String>(),
            resident - AgentToolGroups.BASIC_GROUP.toolNames - AgentToolGroupCatalog.USE_GROUP,
        )
        listOf("ask_user", "get_current_context", "launch_app").forEach {
            assertTrue("$it 应保持常驻", it in resident)
        }
        assertTrue(AgentToolGroupCatalog.USE_GROUP in resident)
    }

    @Test fun deferralActuallySavesSchemaTokens() {
        val collapsedTokens = AgentRequestTokenEstimate.tools(catalog())
        val openedTokens = AgentRequestTokenEstimate.tools(catalog(AgentToolGroups.allIds))
        assertTrue(
            "折叠应省下 schema：collapsed=$collapsedTokens opened=$openedTokens",
            collapsedTokens < openedTokens,
        )
    }

    @Test fun openingAGroupPublishesItsTools() {
        assertFalse(AgentSelfSettingsToolCatalog.SET_SETTINGS in catalog().names())

        val opened = catalog(setOf(AgentToolGroups.EXTENSIONS_GROUP.id)).names()
        AgentSelfSettingsToolCatalog.manageTools().forEach { assertTrue("$it 应随组打开而下发", it in opened) }
        assertTrue(AgentSelfSettingsToolCatalog.GET_SETTINGS in opened)
        assertTrue(AgentSelfSettingsToolCatalog.SET_SETTINGS in opened)
        // 开启入口仍然存在，因为还有别的组没打开；只有全部组都打开时它才该消失。
        assertTrue(AgentToolGroupCatalog.USE_GROUP in opened)
    }

    @Test fun perToolDisableSurvivesAnOpenGroup() {
        val names = catalog(AgentToolGroups.allIds, disabledTools = setOf("set_alarm")).names()
        assertFalse("set_alarm" in names)
        assertTrue("set_volume" in names)
    }

    @Test fun indexListsExactlyTheGroupsStillClosed() {
        val allAllowed = AgentToolGroups.availability(emptySet()) { true }
        val closedIndex = requireNotNull(AgentToolGroups.indexMessage(allAllowed))
        AgentToolGroups.deferredGroups.forEach { assertTrue(closedIndex.contains(indexEntry(it.id))) }
        assertTrue(closedIndex.contains(AgentToolGroupCatalog.USE_GROUP))

        // 全部打开后就没有索引消息了：不该再提示模型去打开任何组。
        assertEquals(null, AgentToolGroups.indexMessage(AgentToolGroups.availability(AgentToolGroups.allIds) { true }))
    }

    @Test fun indexLineCarriesOnlyTheIdAndWhatTheGroupContains() {
        val index = requireNotNull(AgentToolGroups.indexMessage(AgentToolGroups.availability(emptySet()) { true }))
        // 「何时才该打开」由表头和结尾各说一次；逐行重复的 title 与 “Open when …” 尾巴
        // 只是把省下来的 schema token 又还回去。
        assertFalse("索引行不该再带 title=", index.contains("title="))
        assertFalse("索引行不该再逐组重复开启条件", index.contains("Open when"))
        AgentToolGroups.deferredGroups.forEach { group ->
            assertTrue("索引行应正好是 id + 摘要：${group.id}", index.contains("- id=${group.id} | ${group.summary}"))
        }
        assertEquals(AgentToolGroups.deferredGroups.size, index.lines().count { it.startsWith("- id=") })
    }

    @Test fun openingNeverWidensPermissions() {
        // 打开组只是让 schema 进入目录；能力过滤仍在同一处生效，因此无 Root 时
        // 组内工具不会因为「被打开」而绕过 ROOT_REQUIRED 之类的判定。
        val rootless = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            openedToolGroups = AgentToolGroups.allIds,
            capabilities = AgentToolCapabilities(rootAvailable = false),
        )
        rootless.names().forEach { name ->
            val code = AgentToolCapabilities(rootAvailable = false).unavailableCode(name)
            assertTrue("$name 不应在无 Root 时下发（$code）", code == null)
        }
    }

    @Test fun switchedOffGroupIsNeitherAdvertisedNorOpenable() {
        // 关掉设备控制：它既不该出现在 tools_use_group 的枚举里，也不该出现在系统提示索引里。
        assertFalse(AgentToolGroups.DEVICE_GROUP.id in catalog(deviceTools = false).useGroupEnum())
        assertTrue(AgentToolGroups.EXTENSIONS_GROUP.id in catalog(deviceTools = false).useGroupEnum())

        val availability = AgentToolGroups.availability(emptySet()) { group ->
            group.id != AgentToolGroups.DEVICE_GROUP.id
        }
        assertFalse(AgentToolGroups.DEVICE_GROUP.id in AgentToolGroups.openableIds(availability))
        val index = requireNotNull(AgentToolGroups.indexMessage(availability))
        assertFalse("关掉的组不该出现在索引里", index.contains(indexEntry(AgentToolGroups.DEVICE_GROUP.id)))

        // 扩展组关闭：它不再宣传，但设备组仍在枚举里（tools_use_group 不被连带禁用）。
        assertFalse(AgentToolGroups.EXTENSIONS_GROUP.id in catalog(extensionsTools = false).useGroupEnum())
        assertTrue(AgentToolGroups.DEVICE_GROUP.id in catalog(extensionsTools = false).useGroupEnum())
    }

    @Test fun allGroupsOpenedLeavesNoUseGroupTool() {
        // 全部打开后没有可打开的组，tools_use_group 不该下发（否则是一个必然失败的枚举）。
        val all = catalog(AgentToolGroups.allIds)
        assertFalse(AgentToolGroupCatalog.USE_GROUP in all.names())
        assertEquals(emptySet<String>(), all.useGroupEnum())
    }

    @Test fun availabilityDropsGroupsWhoseSwitchTurnedOffAfterOpening() {
        // 用户先打开了组，随后又把开关关掉：已打开的记录不能让组复活。
        val availability = AgentToolGroups.availability(AgentToolGroups.allIds) { group ->
            group.id != AgentToolGroups.DEVICE_GROUP.id
        }
        assertFalse(
            "开关关掉后已打开集合里不应再有该组",
            AgentToolGroups.DEVICE_GROUP.id in availability.opened,
        )
        assertTrue(AgentToolGroups.EXTENSIONS_GROUP.id in availability.opened)
        assertEquals(emptySet<String>(), availability.openable)
    }

    @Test fun everyToolBelongsToAtMostOneGroup() {
        val seen = mutableMapOf<String, String>()
        AgentToolGroups.groups.forEach { group ->
            group.toolNames.forEach { name ->
                val previous = seen.put(name, group.id)
                assertNull("$name 同时属于 $previous 与 ${group.id}", previous)
                assertEquals(group.id, AgentToolGroups.groupOfTool(name))
            }
        }
        // 配方工具等动态工具不属于任何组，因此也不受组开关影响。
        assertEquals(null, AgentToolGroups.groupOfTool("eta_recipe_whatever"))
    }

    @Test fun legacyGroupIdsMapOntoTheNewTaxonomy() {
        assertEquals(
            setOf(AgentToolGroups.DEVICE_GROUP.id, AgentToolGroups.CALENDAR_GROUP.id),
            AgentToolGroups.migrateLegacyGroupId("device_direct"),
        )
        assertEquals(
            setOf(AgentToolGroups.DEVICE_GROUP.id, AgentToolGroups.CALENDAR_GROUP.id),
            AgentToolGroups.migrateLegacyGroupId("device_sensitive"),
        )
        assertEquals(setOf(AgentToolGroups.PERSONAL_GROUP.id), AgentToolGroups.migrateLegacyGroupId("personal_data"))
        assertEquals(setOf(AgentToolGroups.PERSONAL_GROUP.id), AgentToolGroups.migrateLegacyGroupId("device_state"))
        assertEquals(setOf(AgentToolGroups.EXTENSIONS_GROUP.id), AgentToolGroups.migrateLegacyGroupId("self"))
        assertEquals(setOf("terminal"), AgentToolGroups.migrateLegacyGroupId("terminal"))
        assertEquals(emptySet<String>(), AgentToolGroups.migrateLegacyGroupId("media_search"))
    }

    @Test fun groupOpeningIsNotPureReadAndNeverParallel() {
        // 开启动作会改变下一轮目录，必须留在串行路径上。
        assertFalse(AgentPureReadTools.isPureRead(AgentToolGroupCatalog.USE_GROUP))
    }

    @Test fun unknownGroupIsReportedWithTheKnownIds() {
        val result = JSONObject(AgentToolGroupCatalog.unknownGroup("nope", listOf("a", "b")))
        assertFalse(result.getBoolean("ok"))
        assertEquals("UNKNOWN_TOOL_GROUP", result.getString("code"))
        assertEquals(
            listOf("a", "b"),
            (0 until result.getJSONArray("available_groups").length())
                .map { result.getJSONArray("available_groups").getString(it) },
        )
    }
}
