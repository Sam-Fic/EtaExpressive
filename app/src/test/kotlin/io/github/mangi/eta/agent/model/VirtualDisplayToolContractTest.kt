package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.RootRequirement
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualDisplayToolContractTest {
    @Test
    fun inspectVirtualBackendIsRootOnlyAndTakesNoArguments() {
        val rooted = catalog(rootAvailable = true)
        assertTrue("inspect_virtual_backend" in rooted.names())
        assertEquals(
            RootRequirement.REQUIRED,
            AgentToolRequirements.rootRequirement("inspect_virtual_backend"),
        )
        val function = rooted.function("inspect_virtual_backend")
        val parameters = function.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertEquals(0, parameters.optJSONArray("required")?.length() ?: 0)
        val description = function.getString("description")
        assertTrue(description.contains("mutations_enabled"))
        assertTrue(description.contains("session_authenticated"))
        assertTrue(description.contains("false"))
        assertTrue(description.contains("does not grant authorisation"))
        assertFalse(description.contains("vd start"))

        val rootless = AgentToolRequirements.project(rooted, rootAvailable = false)
        assertFalse("inspect_virtual_backend" in rootless.names())
        assertEquals(
            "ROOT_REQUIRED",
            AgentToolCapabilities(rootAvailable = false).unavailableCode("inspect_virtual_backend"),
        )
        assertEquals(
            null,
            AgentToolCapabilities(rootAvailable = true).unavailableCode("inspect_virtual_backend"),
        )
    }

    @Test
    fun keepVirtualResultRemainsCallableButDoesNotPromiseRetentionOrCleanup() {
        assertEquals(RootRequirement.NONE, AgentToolRequirements.rootRequirement("keep_virtual_result"))
        val description = catalog(rootAvailable = false).function("keep_virtual_result").getString("description")
        assertTrue(description.contains("finish_virtual_session"))
        assertTrue(description.contains("only marks"))
        assertFalse(description.contains("shut down"))
        assertFalse(description.contains("close the virtual display"))
        assertFalse(description.contains("中间应用不要"))
        assertFalse(description.contains("all be closed"))
        assertTrue("keep_virtual_result" in catalog(rootAvailable = false).names())
    }

    @Test
    fun unmarkedFinishCleansUpWithoutDeliveryAndLaunchReusesVirtualTask() {
        // finish_virtual_session is root-only, so read it from the rooted catalog.
        val finish = catalog(rootAvailable = true).function("finish_virtual_session").getString("description")
        assertTrue(finish.contains("handedOff=false"))
        assertTrue(finish.contains("released=true"))
        assertTrue(finish.contains("without delivering"))
        val launch = catalog(rootAvailable = false).function("launch_app").getString("description")
        assertTrue(launch.contains("reused=true"))
    }

    private fun catalog(rootAvailable: Boolean): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        capabilities = AgentToolCapabilities(rootAvailable = rootAvailable, virtualDisplay = true),
    )

    private fun JSONArray.names(): Set<String> = (0 until length()).mapTo(linkedSetOf()) {
        getJSONObject(it).getJSONObject("function").getString("name")
    }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .map { getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == name }
}
