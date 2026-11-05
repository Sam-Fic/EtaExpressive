package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiUiAgentToolCatalogTest {
    @Test
    fun deprecatedProviderToolIsDetectedButNeverExposedToTheModel() {
        assertTrue("get_memory_data" in XiaomiUiAgentToolCatalog.providerToolNames)
        assertFalse("get_memory_data" in XiaomiUiAgentToolCatalog.modelToolNames)

        val tools = JSONArray()
        XiaomiUiAgentToolCatalog.appendTo(
            tools,
            XiaomiUiAgentToolCatalog.providerToolNames,
        )

        assertEquals(XiaomiUiAgentToolCatalog.modelToolNames, tools.toolNames().toSet())
    }

    @Test
    fun permissionsExposeOnlyTheMatchingRiskGroups() {
        val available = XiaomiUiAgentToolCatalog.providerToolNames
        val direct = XiaomiUiAgentToolCatalog.enabledToolNames(
            available,
            directTools = true,
            sensitiveReadTools = false,
            sensitiveActionTools = false,
        )
        val reads = XiaomiUiAgentToolCatalog.enabledToolNames(
            available,
            directTools = false,
            sensitiveReadTools = true,
            sensitiveActionTools = false,
        )
        val actions = XiaomiUiAgentToolCatalog.enabledToolNames(
            available,
            directTools = false,
            sensitiveReadTools = false,
            sensitiveActionTools = true,
        )

        assertTrue("search_mi_knowledge" in direct)
        assertTrue("settings_access" in direct)
        assertFalse("read_memory" in direct)
        assertFalse("controlApp" in direct)

        assertTrue("read_memory" in reads)
        assertTrue("favorite" in reads)
        assertFalse("settings_access" in reads)
        assertFalse("write_memory" in reads)

        assertTrue("controlApp" in actions)
        assertTrue("write_memory" in actions)
        assertTrue("favorite" in actions)
        assertTrue("settings_access" in actions)
        assertFalse("read_memory" in actions)
    }

    @Test
    fun conditionalToolsRecheckTheActualOperationAtExecutionTime() {
        assertNull(
            XiaomiUiAgentToolCatalog.authorizationIssue(
                name = "favorite",
                arguments = JSONObject().put("type", "list"),
                directTools = false,
                sensitiveReadTools = true,
                sensitiveActionTools = false,
            )
        )
        assertNotNull(
            XiaomiUiAgentToolCatalog.authorizationIssue(
                name = "favorite",
                arguments = JSONObject().put("type", "trigger"),
                directTools = false,
                sensitiveReadTools = true,
                sensitiveActionTools = false,
            )
        )
        assertNull(
            XiaomiUiAgentToolCatalog.authorizationIssue(
                name = "settings_access",
                arguments = JSONObject().put("action", "get"),
                directTools = true,
                sensitiveReadTools = false,
                sensitiveActionTools = false,
            )
        )
        assertNotNull(
            XiaomiUiAgentToolCatalog.authorizationIssue(
                name = "settings_access",
                arguments = JSONObject().put("action", "set"),
                directTools = true,
                sensitiveReadTools = false,
                sensitiveActionTools = false,
            )
        )
    }

    @Test
    fun schemasAndRuntimeValidationAgreeOnRequiredArguments() {
        val tools = JSONArray().also {
            XiaomiUiAgentToolCatalog.appendTo(
                it,
                setOf(
                    "controlApp",
                    "search_mi_knowledge",
                    "favorite",
                    "search_media_tool",
                    "play_media_tool",
                    "settings_access",
                ),
            )
        }
        assertEquals(setOf("query"), tools.requiredNames("controlApp"))
        assertEquals(
            setOf("query", "scene", "query_intent"),
            tools.requiredNames("search_mi_knowledge"),
        )
        assertEquals(setOf("type"), tools.requiredNames("favorite"))
        assertEquals(emptySet<String>(), tools.requiredNames("search_media_tool"))
        assertEquals(emptySet<String>(), tools.requiredNames("play_media_tool"))
        assertEquals(setOf("action", "key"), tools.requiredNames("settings_access"))

        assertNotNull(
            XiaomiUiAgentToolCatalog.validate(
                "favorite",
                JSONObject().put("type", "detail"),
            )
        )
        assertNull(
            XiaomiUiAgentToolCatalog.validate(
                "favorite",
                JSONObject().put("type", "detail").put("favoriteId", "fav-1"),
            )
        )
        assertNotNull(
            XiaomiUiAgentToolCatalog.validate(
                "image_to_text",
                JSONObject().put("image", "https://example.invalid/a.png").put("query", "内容"),
            )
        )
        assertNull(XiaomiUiAgentToolCatalog.validate("search_media_tool", JSONObject()))
        assertNull(XiaomiUiAgentToolCatalog.validate("play_media_tool", JSONObject()))
    }

    private fun JSONArray.toolNames(): List<String> =
        (0 until length()).map { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.requiredNames(name: String): Set<String> {
        val function = (0 until length())
            .asSequence()
            .map { getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }
        val required = function.getJSONObject("parameters").optJSONArray("required")
            ?: return emptySet()
        return (0 until required.length()).map(required::getString).toSet()
    }
}
