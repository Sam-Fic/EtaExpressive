package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillInstallToolCatalogTest {
    @Test
    fun `Skill resource reader is always visible with bounded relative path schema`() {
        val function = catalog().function("skills_read_resource")
        val parameters = function.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")

        assertEquals(1_000, properties.getJSONObject("relativePath").getInt("maxLength"))
        assertEquals(512, properties.getJSONObject("maxChars").getInt("minimum"))
        assertEquals(64_000, properties.getJSONObject("maxChars").getInt("maximum"))
        assertTrue("skillId" in parameters.requiredNames())
        assertTrue("relativePath" in parameters.requiredNames())
    }

    @Test
    fun `GitHub tools are only exposed at their authorization level`() {
        val base = catalog().toolNames()
        val discovery = catalog(skillGitHubDiscovery = true).toolNames()
        val install = catalog(skillGitHubDiscovery = true, skillGitHubInstall = true).toolNames()

        assertFalse("skills_list_curated" in base)
        assertFalse("skills_inspect_github" in base)
        assertFalse("skills_install_from_github" in base)
        assertTrue("skills_list_curated" in discovery)
        assertTrue("skills_inspect_github" in discovery)
        assertFalse("skills_install_from_github" in discovery)
        assertTrue("skills_install_from_github" in install)
    }

    @Test
    fun `install schema requires explicit string paths and replacement flag is boolean`() {
        val function = catalog(skillGitHubDiscovery = true, skillGitHubInstall = true)
            .function("skills_install_from_github")
        val parameters = function.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")
        val paths = properties.getJSONObject("paths")

        assertEquals("array", paths.getString("type"))
        assertEquals("string", paths.getJSONObject("items").getString("type"))
        assertEquals(1, paths.getInt("minItems"))
        assertEquals(20, paths.getInt("maxItems"))
        assertEquals("boolean", properties.getJSONObject("replaceExisting").getString("type"))
        assertEquals("string", properties.getJSONObject("expectedReplacementId").getString("type"))
        assertEquals(500, properties.getJSONObject("expectedReplacementId").getInt("maxLength"))
        assertTrue("repository" in parameters.requiredNames())
        assertTrue("paths" in parameters.requiredNames())
        assertTrue(function.getString("description").contains("next turn"))
    }

    @Test
    fun `local install tool is always visible and only requires a directory`() {
        val base = catalog()
        assertTrue("skills_install_local" in base.toolNames())
        val function = base.function("skills_install_local")
        val parameters = function.getJSONObject("parameters")
        val properties = parameters.getJSONObject("properties")

        assertEquals("string", properties.getJSONObject("directory").getString("type"))
        assertEquals(1_000, properties.getJSONObject("directory").getInt("maxLength"))
        assertEquals("boolean", properties.getJSONObject("replaceExisting").getString("type"))
        assertEquals("string", properties.getJSONObject("expectedReplacementId").getString("type"))
        assertEquals(500, properties.getJSONObject("expectedReplacementId").getInt("maxLength"))
        assertEquals(setOf("directory"), parameters.requiredNames())
        assertTrue(function.getString("description").contains("next turn"))
    }

    /** Skills 工具分属基础组与扩展组；扩展组默认延迟，这里统一视为已打开。 */
    private fun catalog(
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
    ): JSONArray = AgentToolCatalog.build(
        terminalTools = false,
        browserTools = false,
        skillGitHubDiscovery = skillGitHubDiscovery,
        skillGitHubInstall = skillGitHubInstall,
        openedToolGroups = AgentToolGroups.allIds,
    )

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }

    private fun JSONObject.requiredNames(): Set<String> {
        val values = getJSONArray("required")
        return (0 until values.length()).mapTo(mutableSetOf(), values::getString)
    }
}
