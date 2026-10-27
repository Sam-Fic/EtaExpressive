package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolCatalogTest {
    @Test
    fun featureFlagsProduceExactUniqueToolUnions() {
        val base = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).toolNames()
        val baseTools = base.toSet()
        val variants = listOf(
            ToolVariant(terminalTools = false, browserTools = false, addedTools = emptySet()),
            ToolVariant(terminalTools = false, browserTools = true, addedTools = BROWSER_TOOLS),
            ToolVariant(terminalTools = true, browserTools = false, addedTools = TERMINAL_TOOLS),
            ToolVariant(
                terminalTools = true,
                browserTools = true,
                addedTools = BROWSER_TOOLS + TERMINAL_TOOLS,
            ),
        )

        assertEquals(base.size, baseTools.size)
        assertTrue(
            base.containsAll(
                setOf("observe_screen", "skills_list", "skills_read", "skills_read_resource"),
            ),
        )
        assertFalse("browser_use" in base)
        assertFalse("terminal" in base)

        variants.forEach { variant ->
            val names = AgentToolCatalog.build(
                terminalTools = variant.terminalTools,
                browserTools = variant.browserTools,
            ).toolNames()
            val label = "terminal=${variant.terminalTools}, browser=${variant.browserTools}"

            assertEquals("$label must not contain duplicate tools", names.size, names.toSet().size)
            assertEquals("$label must be an exact union", baseTools + variant.addedTools, names.toSet())
        }
    }

    @Test
    fun browserToolAllowsArbitraryUrlsAndFormSubmission() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = true,
        ).function("browser_use")
        val properties = function
            .getJSONObject("parameters")
            .getJSONObject("properties")

        assertEquals("string", properties.getJSONObject("url").getString("type"))
        assertEquals("boolean", properties.getJSONObject("submit").getString("type"))
        assertFalse(properties.getJSONObject("url").getString("description").contains("HTTPS"))
        assertFalse(function.getString("description").contains("拦截"))
        val actions = properties.getJSONObject("action").getJSONArray("enum").let { array ->
            (0 until array.length()).map { array.getString(it) }.toSet()
        }
        assertTrue("execute_js" in actions)
        assertTrue("set_user_agent" in actions)
        assertTrue("get_backbone" in actions)
        assertTrue("wait_for_selector" in actions)
        assertTrue("go_back" in actions)
    }

    @Test
    fun elementToolsRequireObservationIdFromTheSameObservation() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        listOf("tap_element", "long_press_element", "scroll_element").forEach { name ->
            val function = tools.function(name)
            val parameters = function.getJSONObject("parameters")
            val properties = parameters.getJSONObject("properties")

            assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
            assertTrue("observation_id must be required for $name", "observation_id" in parameters.requiredNames())
            assertTrue(function.getString("description").contains("the same most recent"))
            assertTrue(function.getString("description").contains("observe_screen"))
            assertTrue(function.getString("description").contains("observe again"))
        }
    }

    @Test
    fun screenObservationDefaultsToTreeAndDescribesVisualEscalation() {
        val function = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
        ).function("observe_screen")
        val properties = function.getJSONObject("parameters").getJSONObject("properties")

        assertFalse(properties.getJSONObject("include_screenshot").getBoolean("default"))
        assertTrue(properties.getJSONObject("include_ui_tree").getBoolean("default"))
        assertEquals(60, properties.getJSONObject("max_nodes").getInt("default"))
        assertEquals(1, properties.getJSONObject("max_nodes").getInt("minimum"))
        assertEquals(120, properties.getJSONObject("max_nodes").getInt("maximum"))
        assertTrue(function.getString("description").contains("By default it returns only"))
        assertTrue(function.getString("description").contains("include_screenshot=true"))
        assertTrue(function.getString("description").contains("keep include_ui_tree=true"))
        assertTrue(function.getString("description").contains("never mix a new screenshot with old nodes"))
    }

    @Test
    fun scrollDirectionsUseContentBrowsingSemantics() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val expectedDirections = listOf("up", "down", "left", "right")

        listOf("scroll", "scroll_element").forEach { name ->
            val function = tools.function(name)
            val directions = function
                .getJSONObject("parameters")
                .getJSONObject("properties")
                .getJSONObject("direction")
                .getJSONArray("enum")
                .stringValues()

            assertEquals(expectedDirections, directions)
            assertTrue(function.getString("description").contains("down shows content below"))
            assertTrue(function.getString("description").contains("up shows content above"))
        }
    }

    @Test
    fun indexedTextToolsDescribeObservationPairingWithoutRequiringItForFocusedInput() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        listOf("replace_text", "clear_text").forEach { name ->
            val function = tools.function(name)
            val parameters = function.getJSONObject("parameters")
            val properties = parameters.getJSONObject("properties")

            assertEquals("string", properties.getJSONObject("observation_id").getString("type"))
            assertFalse("observation_id remains optional when $name targets focus", "observation_id" in parameters.requiredNames())
            assertTrue(function.getString("description").contains("index and observation_id"))
            assertTrue(properties.getJSONObject("index").getString("description").contains("requires the observation_id"))
        }

        val inputText = tools.function("input_text")
        val inputProperties = inputText
            .getJSONObject("parameters")
            .getJSONObject("properties")
        assertEquals("integer", inputProperties.getJSONObject("index").getString("type"))
        assertEquals(
            "string",
            inputProperties.getJSONObject("observation_id").getString("type"),
        )
        assertTrue(
            inputProperties.getJSONObject("index").getString("description")
                .contains("observation_id"),
        )
    }

    @Test
    fun textToolsDeclareTheSameLimitsAsRuntime() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)

        assertEquals(1_000, tools.maxTextLength("input_text"))
        assertEquals(4_000, tools.maxTextLength("replace_text"))
        assertEquals(20_000, tools.maxTextLength("set_clipboard"))
        assertEquals(20_000, tools.maxTextLength("paste_text"))
    }

    @Test
    fun terminalSeparatesAndroidAndLinuxEnvironments() {
        val terminal = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
        ).function("terminal")
        val environment = terminal
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("environment")

        assertEquals(listOf("android", "linux"), environment.getJSONArray("enum").stringValues())
        assertTrue(terminal.getString("description").contains("environment=android"))
        assertTrue(terminal.getString("description").contains("environment=linux"))
    }

    @Test
    fun memoryToolsAreExposedOnlyWhenEnabledAndDeclareBoundedOperations() {
        val disabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = false,
        ).toolNames()
        assertFalse("memory_get" in disabled)
        assertFalse("memory_write" in disabled)

        val enabled = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            memoryTools = true,
        )
        assertTrue("memory_get" in enabled.toolNames())
        val write = enabled.function("memory_write")
        val properties = write.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(3_500, properties.getJSONObject("content").getInt("maxLength"))
        assertEquals(
            listOf("replace_range", "append", "clear"),
            properties.getJSONObject("mode").getJSONArray("enum").stringValues(),
        )
    }


    @Test
    fun screenSwitchRemovesEveryGuiToolButKeepsNonGuiTools() {
        val capabilities = io.github.mangi.eta.agent.tool.AgentToolCapabilities(
            rootAvailable = true,
            virtualDisplay = true,
        )
        val on = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            screenTools = true,
            capabilities = capabilities,
        ).toolNames().toSet()
        val offTools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            screenTools = false,
            capabilities = capabilities,
        )
        val off = offTools.toolNames().toSet()

        val gui = io.github.mangi.eta.agent.tool.AgentToolRequirements.SCREEN_TOOL_NAMES
        assertTrue("screen-on must advertise the GUI family", on.containsAll(gui))
        val leaked = gui.filter { it in off }
        assertTrue("screen-off must drop the whole GUI family, leaked=$leaked", leaked.isEmpty())
        assertTrue("non-GUI core tools must survive", off.containsAll(setOf("ask_user", "get_current_context", "launch_app")))
        assertTrue(offTools.toolNames().size == off.size)
    }

    private fun JSONArray.toolNames(): List<String> =
        (0 until length()).map { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .asSequence()
            .map { index -> getJSONObject(index).getJSONObject("function") }
            .first { function -> function.getString("name") == name }

    private fun JSONObject.requiredNames(): Set<String> =
        optJSONArray("required")?.stringValues()?.toSet().orEmpty()

    private fun JSONArray.stringValues(): List<String> =
        (0 until length()).map(::getString)

    private fun JSONArray.maxTextLength(name: String): Int =
        function(name)
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("text")
            .getInt("maxLength")

    private data class ToolVariant(
        val terminalTools: Boolean,
        val browserTools: Boolean,
        val addedTools: Set<String>,
    )

    private companion object {
        val BROWSER_TOOLS = setOf("browser_use", "web_search", "fetch_url")
        val TERMINAL_TOOLS = setOf(
            "read_image",
            "terminal",
            "run_command",
            "read_file",
            "write_file",
            "list_directory",
            "stat_file",
            "edit_file",
            "glob_files",
            "grep_files",
        )
    }
}
