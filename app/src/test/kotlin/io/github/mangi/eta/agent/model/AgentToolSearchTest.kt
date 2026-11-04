package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具级搜索（移植自 ZCode 的 ToolSearch 本地投影）：
 * 分词（camelCase/snake_case/MCP 名）、select: 精确选择、+term 必含词与评分排序，
 * 以及 `tools_search` 在目录中的下发条件（与 tools_use_group 同一门面）。
 */
class AgentToolSearchTest {
    private fun candidate(
        name: String,
        description: String? = null,
        mcp: Boolean = false,
        groupId: String = "g",
    ) = AgentToolSearch.Candidate(name, description, null, mcp, groupId)

    @Test fun keywordMatchesCamelCaseTokens() {
        val hits = AgentToolSearch.search(
            "send text",
            listOf(
                candidate("sendTextMessage", "Send a text message to a contact"),
                candidate("observeScreen", "Read the current screen"),
            ),
        )
        assertEquals(listOf("sendTextMessage"), hits.map { it.name })
    }

    @Test fun snakeCaseTokensMatch() {
        val hits = AgentToolSearch.search(
            "terminal run",
            listOf(
                candidate("run_command", "Run a shell command in a terminal session"),
                candidate("read_file", "Read a file"),
            ),
        )
        assertEquals(listOf("run_command"), hits.map { it.name })
    }

    @Test fun mcpNameTokensScoreServerAndTool() {
        val hits = AgentToolSearch.search(
            "browser navigate",
            listOf(
                candidate("mcp_webtools_browser_navigate_ab12", "Navigate the browser", mcp = true, groupId = "mcp:s1"),
                candidate("mcp_webtools_notes_add_cd34", "Add a note", mcp = true, groupId = "mcp:s1"),
            ),
        )
        assertEquals(listOf("mcp_webtools_browser_navigate_ab12"), hits.map { it.name })
    }

    @Test fun mcpPrefixQueryReturnsPrefixMatches() {
        val hits = AgentToolSearch.search(
            "mcp_webtools_",
            listOf(
                candidate("mcp_webtools_browser_navigate_ab12", null, mcp = true, groupId = "mcp:s1"),
                candidate("read_file", null),
            ),
        )
        assertEquals(listOf("mcp_webtools_browser_navigate_ab12"), hits.map { it.name })
    }

    @Test fun selectPicksExactNames() {
        val hits = AgentToolSearch.search(
            "select:read_file,run_command",
            listOf(
                candidate("read_file"),
                candidate("run_command"),
                candidate("observe_screen"),
            ),
        )
        assertEquals(listOf("read_file", "run_command"), hits.map { it.name })
    }

    @Test fun requiredTermFiltersNonMatches() {
        val hits = AgentToolSearch.search(
            "+slack send",
            listOf(
                candidate("send_message", "Send a slack message to a channel"),
                candidate("send_message_sms", "Send an SMS"),
            ),
        )
        assertEquals(listOf("send_message"), hits.map { it.name })
    }

    @Test fun descriptionTermsRankCandidates() {
        val hits = AgentToolSearch.search(
            "calendar",
            listOf(
                candidate("create_event", "Create a calendar event"),
                candidate("read_file", "Read a file"),
            ),
        )
        assertEquals(listOf("create_event"), hits.map { it.name })
    }

    @Test fun noMatchReturnsEmpty() {
        val hits = AgentToolSearch.search(
            "quantum",
            listOf(candidate("read_file", "Read a file")),
        )
        assertTrue(hits.isEmpty())
    }

    @Test fun maxResultsBoundsOutput() {
        val candidates = (1..10).map { index -> candidate("tool_$index", "tool number $index for testing") }
        val hits = AgentToolSearch.search("tool", candidates, maxResults = 3)
        assertEquals(3, hits.size)
    }

    /** tools_search 与 tools_use_group 同一门面：有可打开组才下发，全部打开后消失。 */
    @Test fun searchToolOnlyEmittedWhenGroupsOpenable() {
        fun emittedTools(
            opened: Set<String>,
            toolSearchEnabled: Boolean = true,
        ): Set<String> =
            AgentToolCatalog.build(
                terminalTools = true,
                browserTools = true,
                deviceTools = true,
                personalTools = true,
                extensionsTools = true,
                openedToolGroups = opened,
                toolSearchEnabled = toolSearchEnabled,
            ).let { tools ->
                (0 until tools.length()).mapTo(mutableSetOf()) {
                    tools.getJSONObject(it).getJSONObject("function").getString("name")
                }
            }
        assertTrue(AgentToolSearchCatalog.SEARCH in emittedTools(emptySet()))
        val allOpened = AgentToolGroups.deferredGroups.mapTo(mutableSetOf()) { it.id }
        assertFalse(AgentToolSearchCatalog.SEARCH in emittedTools(allOpened))
        assertFalse(AgentToolGroupCatalog.USE_GROUP in emittedTools(allOpened))
    }

    /** 按组档（最初行为）：tools_search 不下发，tools_use_group 照常，其余目录不变。 */
    @Test fun groupModeKeepsUseGroupButDropsSearchTool() {
        fun emittedTools(toolSearchEnabled: Boolean): Set<String> =
            AgentToolCatalog.build(
                terminalTools = true,
                browserTools = true,
                deviceTools = true,
                personalTools = true,
                extensionsTools = true,
                toolSearchEnabled = toolSearchEnabled,
            ).let { tools ->
                (0 until tools.length()).mapTo(mutableSetOf()) {
                    tools.getJSONObject(it).getJSONObject("function").getString("name")
                }
            }
        val groupMode = emittedTools(toolSearchEnabled = false)
        val searchMode = emittedTools(toolSearchEnabled = true)
        assertFalse(AgentToolSearchCatalog.SEARCH in groupMode)
        assertTrue(AgentToolGroupCatalog.USE_GROUP in groupMode)
        // 除 tools_search 外目录完全一致：按组档只是去掉搜索入口，不改延迟折叠。
        assertEquals(searchMode - AgentToolSearchCatalog.SEARCH, groupMode)
    }

    @Test fun searchToolSurvivesPerToolDisables() {
        // 单工具禁用不影响 tools_search 下发：它没有枚举参数，且组仍可打开（可搜索）。
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceTools = true,
            personalTools = true,
            extensionsTools = true,
            disabledTools = setOf("run_command"),
        ).let { tools ->
            (0 until tools.length()).mapTo(mutableSetOf()) {
                tools.getJSONObject(it).getJSONObject("function").getString("name")
            }
        }
        assertTrue(AgentToolSearchCatalog.SEARCH in tools)
        assertFalse("run_command" in tools)
    }

    /** 搜索索引在 withheld 过滤前填：未打开延迟组的名字与描述必须可搜。 */
    @Test fun searchIndexCoversWithheldDeferredTools() {
        val index = mutableMapOf<String, String>()
        AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceTools = true,
            personalTools = true,
            extensionsTools = true,
            openedToolGroups = emptySet(),
            searchIndex = index,
        )
        assertTrue("run_command" in index)
        assertTrue("observe_screen" in index)
        assertTrue(index["run_command"]?.contains("shell") == true)
    }

    @Test fun searchIndexRespectsGroupSwitches() {
        val index = mutableMapOf<String, String>()
        AgentToolCatalog.build(
            terminalTools = true,
            browserTools = false,
            deviceTools = true,
            personalTools = true,
            extensionsTools = true,
            openedToolGroups = emptySet(),
            searchIndex = index,
        )
        assertFalse("browser_use" in index)
        assertTrue("run_command" in index)
    }

    /** 动态组（MCP server）参与可用性与枚举：未打开时进索引，打开后消失。 */
    @Test fun dynamicGroupsJoinAvailabilityAndIndex() {
        val mcpGroup = AgentToolGroup(
            id = AgentToolGroups.mcpGroupId("srv1"),
            summary = "MCP 服务器「srv1」的 2 个工具",
            toolNames = setOf("mcp_srv1_tool_a_aa11", "mcp_srv1_tool_b_bb22"),
            label = "MCP 服务器「srv1」",
        )
        val availability = AgentToolGroups.availability(
            emptySet(),
            gate = { AgentToolGroups.isMcpGroupId(it.id) },
            extraGroups = listOf(mcpGroup),
        )
        assertTrue(mcpGroup.id in availability.openable)
        assertEquals(mcpGroup.id, AgentToolGroups.groupOfTool("mcp_srv1_tool_a_aa11", listOf(mcpGroup)))
        assertTrue(AgentToolGroups.isDeferredTool("mcp_srv1_tool_a_aa11", listOf(mcpGroup)))
        // withheld：未打开的动态组整组剔除（静态延迟组同样被剔除，这里是超集断言）。
        val withheld = AgentToolGroups.withheldTools(emptySet(), emptySet(), listOf(mcpGroup))
        assertTrue(mcpGroup.toolNames.all { it in withheld })
        val withheldOpened = AgentToolGroups.withheldTools(setOf(mcpGroup.id), emptySet(), listOf(mcpGroup))
        assertTrue(mcpGroup.toolNames.none { it in withheldOpened })
        // 标签走组自带 label，不回退到 when 表。
        assertEquals("MCP 服务器「srv1」", AgentToolGroups.label(mcpGroup.id, listOf(mcpGroup)))
    }

    @Test fun deferredGroupsOfRecoversDynamicGroupsFromNames() {
        val mcpGroup = AgentToolGroup(
            id = AgentToolGroups.mcpGroupId("srv1"),
            summary = "MCP",
            toolNames = setOf("mcp_srv1_tool_a_aa11"),
        )
        val recovered = AgentToolGroups.deferredGroupsOf(
            listOf("mcp_srv1_tool_a_aa11", "run_command"),
            listOf(mcpGroup),
        )
        assertEquals(setOf(AgentToolGroups.mcpGroupId("srv1"), "terminal"), recovered)
    }

    /** 压缩边界标记：写入→解析往返；非标记内容不误读。 */
    @Test fun markerRoundTrip() {
        val marker = AgentToolGroups.openedGroupsMarker(setOf("web", AgentToolGroups.mcpGroupId("srv1")))
        assertTrue(marker!!.startsWith(AgentToolGroups.OPENED_GROUPS_MARKER_PREFIX))
        assertEquals(
            setOf("web", AgentToolGroups.mcpGroupId("srv1")),
            AgentToolGroups.openedGroupsFromMarkers(listOf(marker)),
        )
        assertNull(AgentToolGroups.openedGroupsMarker(emptySet()))
        // 无关消息与坏 JSON 不抛错、不误读。
        assertTrue(AgentToolGroups.openedGroupsFromMarkers(listOf("[Eta opened tool groups v1] not json")).isEmpty())
        assertTrue(AgentToolGroups.openedGroupsFromMarkers(listOf("普通消息")).isEmpty())
    }

    @Test fun toolNamesFromToolCallsJson() {
        val calls = """[{"id":"1","function":{"name":"read_file","arguments":"{}"}},
            {"id":"2","function":{"name":"run_command","arguments":"{}"}}]"""
        assertEquals(
            setOf("read_file", "run_command"),
            AgentToolGroups.toolNamesFromToolCallsJson(listOf(calls, "", "bad json")),
        )
    }
}
