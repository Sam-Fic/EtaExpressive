package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSelfSettingsToolCatalogTest {

    @Test
    fun everyActionIsDeclaredAndRequiresAnAction() {
        val tools = catalog()
        listOf(
            AgentSelfSettingsToolCatalog.GET_SETTINGS,
            AgentSelfSettingsToolCatalog.SET_SETTINGS,
            AgentSelfSettingsToolCatalog.MANAGE_MODELS,
            AgentSelfSettingsToolCatalog.MANAGE_SUBAGENTS,
            AgentSelfSettingsToolCatalog.MANAGE_HISTORY,
            AgentSelfSettingsToolCatalog.CONFIGURE_MCP,
        ).forEach { name -> assertTrue("$name missing", name in tools.toolNames()) }

        val manage = tools.function(AgentSelfSettingsToolCatalog.MANAGE_MODELS)
        assertEquals(
            listOf("list", "select", "save_provider", "delete_provider", "save_model", "delete_model", "fetch_models"),
            manage.properties()
                .getJSONObject("action")
                .getJSONArray("enum")
                .stringValues(),
        )
        assertEquals(listOf("action"), manage.parameters().requiredNames())
        assertEquals(
            listOf("chat", "tts", "voice", "compress"),
            manage.properties().getJSONObject("target").getJSONArray("enum").stringValues(),
        )
        assertTrue(manage.getString("description").contains("next reply"))
    }

    @Test
    fun readingSettingsTakesNoArguments() {
        val read = catalog().function(AgentSelfSettingsToolCatalog.GET_SETTINGS)
        // 生产侧对无参工具不输出 required 键，缺失与空列表语义相同。
        assertEquals(emptyList<String>(), read.parameters().optJSONArray("required")?.stringValues().orEmpty())
        assertTrue(read.properties().length() == 0)
        assertTrue(read.getString("description").contains(AgentSelfSettingsToolCatalog.MANAGE_MODELS))
    }

    @Test
    fun changingSettingsRequiresKeyAndValuePairsAndIsBounded() {
        val write = catalog().function(AgentSelfSettingsToolCatalog.SET_SETTINGS)
        val properties = write.properties()
        assertEquals(listOf("settings"), write.parameters().requiredNames())
        val items = properties.getJSONObject("settings").getJSONObject("items")
        assertEquals(listOf("key", "value"), items.optJSONArray("required")?.stringValues())
        assertEquals(24, properties.getJSONObject("settings").getInt("maxItems"))
        assertTrue(items.getJSONObject("properties").getJSONObject("value").getString("description").contains("on"))
        assertTrue(write.getString("description").contains("next reply"))
    }

    @Test
    fun modelConfigurationKeepsApiKeyOptionalAndModelsArrayShaped() {
        val properties = catalog().function(AgentSelfSettingsToolCatalog.MANAGE_MODELS).properties()
        assertTrue(properties.getJSONObject("api_key").getString("description").contains("secret"))
        assertEquals(
            "array",
            properties.getJSONObject("models").getString("type"),
        )
        assertEquals(
            "string",
            properties.getJSONObject("models").getJSONObject("items").getString("type"),
        )
        assertTrue(properties.getJSONObject("provider").getString("description").contains("id or displayed name"))
    }

    @Test
    fun providerEditingCoversEnablingAndModelEditingCoversTheModelItself() {
        val properties = catalog().function(AgentSelfSettingsToolCatalog.MANAGE_MODELS).properties()
        assertEquals("boolean", properties.getJSONObject("is_enabled").getString("type"))
        assertTrue(properties.getJSONObject("is_enabled").getString("description").contains("save_provider"))

        assertEquals("boolean", properties.getJSONObject("enabled").getString("type"))
        assertEquals(
            listOf("on", "off", "auto"),
            properties.getJSONObject("vision").getJSONArray("enum").stringValues(),
        )
        assertEquals(
            listOf("on", "off", "auto"),
            properties.getJSONObject("reasoning").getJSONArray("enum").stringValues(),
        )
        assertEquals("integer", properties.getJSONObject("context_window").getString("type"))
        assertEquals(1, properties.getJSONObject("context_window").getInt("minimum"))
        assertEquals(
            listOf("off", "default", "minimal", "low", "medium", "high", "xhigh", "max"),
            properties.getJSONObject("reasoning_effort").getJSONArray("enum").stringValues(),
        )
        listOf("enabled", "vision", "reasoning", "context_window", "reasoning_effort", "display_name")
            .forEach { field ->
                assertTrue(
                    "$field must be scoped to save_model",
                    properties.getJSONObject(field).getString("description").startsWith("save_model only"),
                )
            }
        assertEquals("boolean", properties.getJSONObject("add_new").getString("type"))
        assertTrue(properties.getJSONObject("add_new").getString("description").contains("Default false"))
    }

    @Test
    fun subAgentGroupManagementCoversGroupAndAgentActions() {
        val manage = catalog().function(AgentSelfSettingsToolCatalog.MANAGE_SUBAGENTS)
        assertEquals(
            listOf(
                "list", "create_group", "rename_group", "delete_group", "set_delegation",
                "add_agent", "update_agent", "remove_agent", "apply",
            ),
            manage.properties().getJSONObject("action").getJSONArray("enum").stringValues(),
        )
        assertEquals(listOf("action"), manage.parameters().requiredNames())
        assertEquals(
            listOf("implementation", "review", "image_generation", "video_generation"),
            manage.properties().getJSONObject("role").getJSONArray("enum").stringValues(),
        )
        assertEquals(
            listOf("simple", "regular", "complex"),
            manage.properties().getJSONObject("task_tier").getJSONArray("enum").stringValues(),
        )
        assertEquals(
            listOf("low", "medium", "high", "ultra"),
            manage.properties().getJSONObject("image_resolution").getJSONArray("enum").stringValues(),
        )
        assertTrue(manage.getString("description").contains("next reply"))
    }

    @Test
    fun historyManagementCoversFoldersAndConversations() {
        val manage = catalog().function(AgentSelfSettingsToolCatalog.MANAGE_HISTORY)
        assertEquals(
            listOf(
                "list_folders", "list_conversations", "search_conversations",
                "create_folder", "rename_folder", "delete_folder",
                "rename_conversation", "move_conversation", "set_pinned", "delete_conversation",
                "restore_conversation", "delete_conversation_forever", "empty_trash",
            ),
            manage.properties().getJSONObject("action").getJSONArray("enum").stringValues(),
        )
        assertEquals(listOf("action"), manage.parameters().requiredNames())
        assertEquals("boolean", manage.properties().getJSONObject("pinned").getString("type"))
        assertEquals("boolean", manage.properties().getJSONObject("trashed").getString("type"))
        assertTrue(manage.getString("description").contains("current"))
    }

    @Test
    fun mcpConfigurationCoversServersSecretsAndToolSwitches() {
        val configure = catalog().function(AgentSelfSettingsToolCatalog.CONFIGURE_MCP)
        assertEquals(
            listOf("list", "add", "update", "delete", "refresh", "set_tool"),
            configure.properties().getJSONObject("action").getJSONArray("enum").stringValues(),
        )
        assertEquals(listOf("action"), configure.parameters().requiredNames())

        val properties = configure.properties()
        val server = properties.getJSONObject("server")
        assertEquals("string", server.getString("type"))
        // server 只用于定位，不能进必填，否则 add 会被卡住。
        assertFalse(configure.parameters().requiredNames().contains("server"))
        assertTrue(server.getString("description").contains("id"))
        assertTrue(server.getString("description").contains("url"))

        assertEquals(
            listOf("none", "bearer"),
            properties.getJSONObject("authorization").getJSONArray("enum").stringValues(),
        )
        assertEquals(
            listOf("auto", "latest", "legacy"),
            properties.getJSONObject("protocol").getJSONArray("enum").stringValues(),
        )
        // 密钥字段的措辞必须点明是密钥，且说明留空 / 缺省两种语义。
        val token = properties.getJSONObject("token").getString("description")
        assertTrue(token.contains("secret"))
        assertTrue(token.contains("empty string"))

        assertEquals("string", properties.getJSONObject("url").getString("type"))
        assertEquals("string", properties.getJSONObject("name").getString("type"))
        assertEquals("string", properties.getJSONObject("tool").getString("type"))

        assertEquals("boolean", properties.getJSONObject("is_enabled").getString("type"))
        assertEquals("boolean", properties.getJSONObject("enable_all").getString("type"))
        assertEquals("boolean", properties.getJSONObject("enabled").getString("type"))
        assertTrue(properties.getJSONObject("enabled").getString("description").contains("set_tool"))

        assertEquals("array", properties.getJSONObject("tools").getString("type"))
        assertEquals("string", properties.getJSONObject("tools").getJSONObject("items").getString("type"))
        assertEquals(60, properties.getJSONObject("tools").getInt("maxItems"))

        assertTrue(configure.getString("description").contains("next reply"))
    }

    @Test
    fun selfSettingsToolsAreAFeatureFlagThatDefaultsToOn() {
        val names = catalog().toolNames()
        assertTrue(names.containsAll(SELF_TOOLS))

        val disabled = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            selfSettingsTools = false,
        ).toolNames()
        SELF_TOOLS.forEach { name -> assertFalse("$name must disappear", name in disabled) }
    }

    // 管理域默认折叠成 tools_use_group；这里断言域内 schema，因此按「已打开」构建。
    private fun catalog(): JSONArray = AgentToolCatalog.build(
        terminalTools = true,
        browserTools = true,
        openedToolGroups = setOf(AgentToolGroups.SELF_SETTINGS_GROUP.id),
    )

    private fun JSONArray.toolNames(): List<String> =
        (0 until length()).map { getJSONObject(it).getJSONObject("function").getString("name") }

    private fun JSONArray.function(name: String): JSONObject =
        (0 until length())
            .map { getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == name }

    private fun JSONObject.properties(): JSONObject =
        getJSONObject("parameters").getJSONObject("properties")

    private fun JSONObject.parameters(): JSONObject = getJSONObject("parameters")

    private fun JSONObject.requiredNames(): List<String> =
        optJSONArray("required")?.stringValues().orEmpty()

    private fun JSONArray.stringValues(): List<String> = (0 until length()).map(::getString)

    private companion object {
        val SELF_TOOLS = listOf(
            AgentSelfSettingsToolCatalog.GET_SETTINGS,
            AgentSelfSettingsToolCatalog.SET_SETTINGS,
            AgentSelfSettingsToolCatalog.MANAGE_MODELS,
            AgentSelfSettingsToolCatalog.MANAGE_SUBAGENTS,
            AgentSelfSettingsToolCatalog.MANAGE_HISTORY,
            AgentSelfSettingsToolCatalog.CONFIGURE_MCP,
        )
    }
}
