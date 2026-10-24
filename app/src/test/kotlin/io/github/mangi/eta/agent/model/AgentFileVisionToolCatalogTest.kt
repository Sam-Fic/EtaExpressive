package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentFileVisionToolCatalogTest {
    @Test
    fun readImageFollowsFileToolsInsteadOfPersonalDataReads() {
        assertFalse("read_image" in names(terminalTools = false, sensitiveReads = true))
        assertTrue("read_image" in names(terminalTools = true, sensitiveReads = false))
        assertTrue(readImageDescription().contains("at most once per round"))
        assertTrue(readImageDescription().contains("reading the next one in a later round"))
        assertTrue(readImageDescription().contains("video"))
        assertTrue(readImageDescription().contains("/workspace"))
        assertTrue(readImageDescription().contains("Markdown image syntax"))
    }

    private fun readImageDescription(): String {
        val tools = AgentToolCatalog.build(terminalTools = true, browserTools = false)
        return (0 until tools.length())
            .map(tools::getJSONObject)
            .first { it.getJSONObject("function").getString("name") == "read_image" }
            .getJSONObject("function")
            .getString("description")
    }

    private fun names(terminalTools: Boolean, sensitiveReads: Boolean): Set<String> =
        AgentToolCatalog.build(
            terminalTools = terminalTools,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = sensitiveReads,
            deviceSensitiveActionTools = false,
        ).toolNames()

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
