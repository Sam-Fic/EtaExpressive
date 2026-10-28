package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentToolGroupCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRequirementsTest {
    @Test
    fun everyRegisteredToolHasExactlyOneRequirement() {
        // 目录有两种形态：组未打开（下发 tools_use_group）与全部打开（下发组内工具）。
        // 注册表必须恰好覆盖两者的并集，否则要么漏注册、要么注册了永远不下发的工具。
        val closed = catalog(root = true, virtualDisplay = true, openedGroups = emptySet())
        val opened = catalog(root = true, virtualDisplay = true)
        val covered = closed.names() + opened.names()
        assertEquals(AgentToolRequirements.toolNames, covered)
        // 两种形态各自内部不得重复。
        assertEquals(closed.length(), closed.names().size)
        assertEquals(opened.length(), opened.names().size)
        assertFalse(opened.toString().contains("rootRequirement"))
        // 折叠形态下必须仍能打开组；全开形态下不该出现一个必然失败的枚举工具。
        assertTrue(AgentToolGroupCatalog.USE_GROUP in closed.names())
        assertFalse(AgentToolGroupCatalog.USE_GROUP in opened.names())
    }

    @Test
    fun unknownToolCannotEnterTheModelCatalog() {
        val unknown = JSONArray().put(JSONObject().put("function", JSONObject().put("name", "new_tool")))
        assertThrows(IllegalArgumentException::class.java) {
            AgentToolRequirements.project(unknown, rootAvailable = true)
        }
    }

    @Test
    fun rootlessProjectionRemovesPrivilegedToolsAndNarrowsMixedSchemasWithoutMutatingSource() {
        val original = catalog(root = true)
        val projected = AgentToolRequirements.project(original, rootAvailable = false)
        val required = AgentToolRequirements.toolNames.filter {
            AgentToolRequirements.rootRequirement(it) == RootRequirement.REQUIRED
        }
        assertTrue(required.isNotEmpty())
        assertTrue(required.none { it in projected.names() })
        assertTrue(setOf("terminal", "read_file", "read_image", "observe_screen", "browser_use").all {
            it in projected.names()
        })
        assertEquals("[\"user\"]", projected.properties("terminal").getJSONObject("identity").getJSONArray("enum").toString())
        assertEquals(2, original.properties("terminal").getJSONObject("identity").getJSONArray("enum").length())
        assertFalse(projected.properties("press_key").getJSONObject("button").getJSONArray("enum").toString().contains("PASTE"))
        assertFalse(projected.toString().contains("/data/local/tmp/eta"))
        assertFalse(projected.properties("read_image").getJSONObject("path").toString().contains("Root"))
    }

    @Test
    fun ordinaryAuthorizationIsIndependentFromRootAndForegroundIntentsDoNotNeedAccessibility() {
        val restricted = AgentToolCapabilities(
            rootAvailable = false, accessibilityAvailable = false,
            notificationsAllowed = false, usageAllowed = false, locationAllowed = false, colorOs = false,
        )
        val names = restricted.project(catalog(root = true)).names()
        assertTrue(setOf("launch_app", "open_uri", "terminal", "browser_use").all { it in names })
        assertTrue(setOf("observe_screen", "wait_for_text", "wait_for_package", "recent_notifications", "app_usage_summary", "get_current_location").none { it in names })
        assertEquals("ROOT_REQUIRED", restricted.unavailableCode("search_coloros_notes"))
        assertEquals("DEVICE_UNSUPPORTED", restricted.copy(rootAvailable = true).unavailableCode("search_coloros_notes"))
        assertEquals(null, restricted.copy(notificationsAllowed = true).unavailableCode("recent_notifications"))
        assertEquals("NOTIFICATION_ACCESS_REQUIRED", restricted.copy(rootAvailable = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(rootAvailable = true, colorOs = true).unavailableCode("search_personal_orders"))
        assertEquals(null, restricted.copy(accessibilityAvailable = true).unavailableCode("observe_screen"))
        assertEquals(null, restricted.copy(accessibilityRecoveryAvailable = true).unavailableCode("observe_screen"))
    }

    @Test
    fun legacyRootArgumentsAreDeniedEvenWhenCallingAMixedToolDirectly() {
        assertTrue(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "root"), false))
        assertTrue(AgentToolRequirements.rootDenied("press_key", JSONObject().put("button", "PASTE"), false))
        // set_setting 已升级为 PARTIAL（免 Root 走 WRITE_SETTINGS），无 Root 时不再整体拒绝。
        assertFalse(AgentToolRequirements.rootDenied("set_setting", JSONObject(), false))
        assertFalse(AgentToolRequirements.rootDenied("terminal", JSONObject().put("identity", "user"), false))
        assertFalse(AgentToolRequirements.rootDenied("set_setting", JSONObject(), true))
    }

    @Test
    fun frameworkConnectionDoesNotGrantRootAndRootSnapshotDoesNotRequireFramework() {
        assertEquals(LsposedRequirement.OPTIONAL, AgentToolRequirements.find("search_coloros_memories")?.lsposedRequirement)
        assertEquals("ROOT_REQUIRED", AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true)
            .unavailableCode("search_coloros_memories"))
        assertEquals(null, AgentToolCapabilities(rootAvailable = true, lsposedAvailable = false)
            .unavailableCode("search_coloros_memories"))
    }

    private fun catalog(
        root: Boolean,
        virtualDisplay: Boolean = false,
        openedGroups: Set<String> = AgentToolGroups.allIds,
    ) = AgentToolCatalog.build(
        terminalTools = true, browserTools = true,
        deviceTools = true, personalTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
        mediaSearchTools = true,
        capabilities = AgentToolCapabilities(rootAvailable = root, virtualDisplay = virtualDisplay),
        openedToolGroups = openedGroups,
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.properties(name: String): JSONObject = (0 until length())
        .map { getJSONObject(it).getJSONObject("function") }
        .single { it.getString("name") == name }.getJSONObject("parameters").getJSONObject("properties")
}
