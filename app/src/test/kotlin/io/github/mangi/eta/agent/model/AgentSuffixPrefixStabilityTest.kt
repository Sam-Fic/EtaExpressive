package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存优先档的核心保证：**run 中途开组不改请求前缀**。
 *
 * `tools` 与 system 都排在全部历史之前，任何一处改写都会让 provider 的前缀缓存整段失效。
 * 这里直接盯真实请求的字节，而不是盯目录构建函数的入参——后者恒等不代表前者恒等
 * （轮次签名、提示渲染、能力快照都可能在中途把它们重建掉）。
 */
class AgentSuffixPrefixStabilityTest {
    private class Prefix(val tools: String, val system: String, val messageCount: Int)

    private fun prefixOf(request: ProviderRequest): Prefix {
        val system = JSONArray()
        val messages = request.messages
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "system") system.put(message)
        }
        return Prefix(request.tools.toString(), system.toString(), messages.length())
    }

    @Test fun openingAGroupMidRunKeepsToolsAndSystemByteIdentical() {
        val opened = mutableSetOf<String>()
        val prefixes = mutableListOf<Prefix>()
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assertTrue("首轮就该有转发入口", request.tools.toString().contains("\"${AgentToolGroupCatalog.CALL}\""))
                assertFalse("未加载的组不下发 schema", request.tools.toString().contains("\"run_command\""))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("open-1", AgentToolGroupCatalog.USE_GROUP, """{"group":"terminal"}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assertFalse("开组后也不下发 schema：改经 tool_call 转发",
                    request.tools.toString().contains("\"run_command\""))
                // 回执是工具结果的正文（字符串），整体序列化后引号会被转义，只认字段名。
                assertTrue("组加载回执必须落在历史尾部", request.messages.toString().contains("call_via"))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("cmd-1", AgentToolGroupCatalog.CALL,
                        """{"name":"run_command","arguments":{"command":"ls"}}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            // 刻意不含任何预开关键词：原生组集合为空，开组的影响才看得清楚。
            prompt = "帮我处理一件事",
            provider = provider,
            toolLoadingSuffixMode = true,
            sessionId = "suffix-prefix-stability",
            openedToolGroupsProvider = { opened.toSet() },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.name
                if (call.name == AgentToolGroupCatalog.USE_GROUP) {
                    opened += AgentToolGroups.TERMINAL_GROUP.id
                    AgentModelClient.ToolResult(
                        AgentToolGroupCatalog.result(
                            group = AgentToolGroups.TERMINAL_GROUP,
                            availableTools = listOf("run_command"),
                            requiresToolCall = true,
                        ),
                    )
                } else {
                    AgentModelClient.ToolResult("""{"ok":true}""")
                }
            },
        )

        assertEquals(listOf(AgentToolGroupCatalog.USE_GROUP, AgentToolGroupCatalog.CALL), executed)
        assertEquals(3, prefixes.size)
        prefixes.forEachIndexed { index, prefix ->
            assertEquals("第 ${index + 1} 轮 tools 字节变了", prefixes[0].tools, prefix.tools)
            assertEquals("第 ${index + 1} 轮 system 字节变了", prefixes[0].system, prefix.system)
        }
        assertTrue("历史只应在尾部增长", prefixes[2].messageCount > prefixes[0].messageCount)
    }

    @Test fun theLegacyModeReallyDoesRewriteThePrefix() {
        // 对照组：证明上面的断言不是恒真的。渐进加载档开组后 tools 必须变，
        // 否则「缓存优先档省掉了一次前缀重写」这个结论就没有被验证到。
        val opened = mutableSetOf<String>()
        val prefixes = mutableListOf<Prefix>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("open-1", AgentToolGroupCatalog.USE_GROUP, """{"group":"terminal"}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assertTrue("渐进加载档开组后下一轮应带上 run_command",
                    request.tools.toString().contains("\"run_command\""))
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我处理一件事",
            provider = provider,
            sessionId = "suffix-prefix-control",
            openedToolGroupsProvider = { opened.toSet() },
            toolExecutor = AgentModelClient.ToolExecutor {
                opened += AgentToolGroups.TERMINAL_GROUP.id
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        assertEquals(2, prefixes.size)
        assertNotEquals(prefixes[0].tools, prefixes[1].tools)
        assertNotEquals(prefixes[0].system, prefixes[1].system)
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
}