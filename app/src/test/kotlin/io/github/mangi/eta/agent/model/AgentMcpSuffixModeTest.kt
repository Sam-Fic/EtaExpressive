package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MCP_TOOL = "mcp_demo_tool"
private val MCP_GROUP_ID: String = AgentToolGroups.mcpGroupId("demo")

/**
 * 缓存优先档下，打开 MCP server 不再改写请求前缀。
 *
 * MCP 曾是 suffix 档最后一个机制性抖动源：schema 由投影按已打开 server 逐轮追加，
 * 开一个 server 就重写一次 `tools` 与 system。快照本身能按单组渲染 schema，
 * 因此回执文档有来源，MCP 可以和静态组一样经 `tool_call` 转发。
 */
class AgentMcpSuffixModeTest {
    private class Prefix(val tools: String, val system: String)

    private fun prefixOf(request: ProviderRequest): Prefix {
        val system = JSONArray()
        for (index in 0 until request.messages.length()) {
            val message = request.messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "system") system.put(message)
        }
        return Prefix(request.tools.toString(), system.toString())
    }

    @Test fun openingAnMcpServerKeepsToolsAndSystemByteIdentical() {
        val opened = mutableSetOf<String>()
        val prefixes = mutableListOf<Prefix>()
        val projection = FakeProjection()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assertFalse("未打开的 server 不下发 schema", TOOL in request.toolNames())
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("open-1", AgentToolGroupCatalog.USE_GROUP, """{"group":"$GROUP_ID"}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assertFalse("打开后也不下发 schema：改经 tool_call 转发", TOOL in request.toolNames())
                assertTrue("转发入口必须常驻", AgentToolGroupCatalog.CALL in request.toolNames())
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("call-1", AgentToolGroupCatalog.CALL, """{"name":"$TOOL","arguments":{"q":"x"}}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我处理一件事",
            provider = provider,
            toolLoadingSuffixMode = true,
            sessionId = "mcp-suffix-prefix",
            mcpProjection = projection,
            openedToolGroupsProvider = { opened.toSet() },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == AgentToolGroupCatalog.USE_GROUP) opened += GROUP_ID
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        assertEquals(3, prefixes.size)
        prefixes.forEachIndexed { index, prefix ->
            assertEquals("第 ${index + 1} 轮 tools 字节变了", prefixes[0].tools, prefix.tools)
            assertEquals("第 ${index + 1} 轮 system 字节变了", prefixes[0].system, prefix.system)
        }
    }

    @Test fun theLegacyModeReallyDoesRewriteThePrefix() {
        // 对照组：渐进加载档下打开 server 必须让 tools 变一次，
        // 否则「缓存优先档省掉了这次前缀重写」这个结论就没有被验证到。
        val opened = mutableSetOf<String>()
        val prefixes = mutableListOf<Prefix>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("open-1", AgentToolGroupCatalog.USE_GROUP, """{"group":"$GROUP_ID"}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assertTrue("渐进加载档打开后下一轮应带上 server 工具", TOOL in request.toolNames())
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我处理一件事",
            provider = provider,
            sessionId = "mcp-legacy-prefix",
            mcpProjection = FakeProjection(),
            openedToolGroupsProvider = { opened.toSet() },
            toolExecutor = AgentModelClient.ToolExecutor {
                opened += GROUP_ID
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        assertEquals(2, prefixes.size)
        assertNotEquals(prefixes[0].tools, prefixes[1].tools)
        assertNotEquals(prefixes[0].system, prefixes[1].system)
    }

    private fun ProviderRequest.toolNames(): Set<String> = buildSet {
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
                ?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    /** 最小 MCP 投影：一个 server、一个工具，schema 与真实快照同形状。 */
    private class FakeProjection : AgentModelClient.McpToolProjection {
        override fun deferredGroups(): List<AgentToolGroup> = listOf(
            AgentToolGroup(
                id = MCP_GROUP_ID,
                summary = "demo server",
                toolNames = setOf(MCP_TOOL),
                label = "demo",
            ),
        )

        override fun appendTools(destination: JSONArray, openedGroupIds: Set<String>?) {
            if (openedGroupIds != null && MCP_GROUP_ID !in openedGroupIds) return
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", MCP_TOOL)
                            .put("description", "demo server 提供的工具")
                            .put(
                                "parameters",
                                JSONObject()
                                    .put("type", "object")
                                    .put("properties", JSONObject().put("q", JSONObject().put("type", "string")))
                                    .put("required", JSONArray().put("q")),
                            ),
                    ),
            )
        }

        override fun toolDescriptions(): Map<String, String> = mapOf(MCP_TOOL to "demo")

        override fun openedGroupIdsFrom(toolNames: Collection<String>): Set<String> =
            if (MCP_TOOL in toolNames) setOf(MCP_GROUP_ID) else emptySet()
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            terminalTools = true,
            browserTools = false,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) message.put("tool_calls", JSONArray(toolCalls))
            }

    private fun toolCall(id: String, name: String, arguments: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            onEvent(ProviderEvent.RequestStarted)
            val response = responses.getOrNull(index) ?: error("缺少第 ${index + 1} 个 scripted response")
            index += 1
            return ProviderResponse(response(request, runController))
        }
    }

    private companion object {
        const val TOOL = MCP_TOOL
        val GROUP_ID = MCP_GROUP_ID
    }
}