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
    private fun catalog(opened: Set<String> = emptySet()): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        selfSettingsTools = true,
        openedToolGroups = opened,
    )

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

        // 展开形态：域内工具出现，开启入口消失（没有可打开的域了）。
        admin.forEach { assertTrue("$it 应随域打开而下发", it in opened.names()) }
        assertFalse(AgentToolGroupCatalog.USE_GROUP in opened.names())

        // 折叠必须真的省下 token，否则这套机制没有意义。
        val collapsedTokens = AgentRequestTokenEstimate.tools(collapsed)
        val openedTokens = AgentRequestTokenEstimate.tools(opened)
        assertTrue("折叠应省下 schema：collapsed=$collapsedTokens opened=$openedTokens",
            collapsedTokens < openedTokens)
    }

    @Test fun indexListsExactlyTheGroupsStillClosed() {
        val closedIndex = requireNotNull(AgentToolGroups.indexMessage(emptySet()))
        assertTrue(closedIndex.contains(AgentToolGroups.SELF_SETTINGS_GROUP.id))
        assertTrue(closedIndex.contains("tools_use_group"))

        // 全部打开后就没有索引消息了：不该再提示模型去打开任何域。
        val allOpened = AgentToolGroups.groups.map { it.id }.toSet()
        assertEquals(null, AgentToolGroups.indexMessage(allOpened))
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
