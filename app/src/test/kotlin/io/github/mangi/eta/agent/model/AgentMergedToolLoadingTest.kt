package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三档合并的目录合同：suffix（缓存优先转发）不再是独立档位，而是 search/group 档的
 * 底座——延迟组工具一律经 `tool_call` 转发（组加载回执进历史尾部），高频组经会话级
 * 原生集合（[AgentModelClient.resolveNativeGroups]）仍拿原生 schema。
 *
 * 这里盯真实请求字节：同一 run 内打开组不得改写 tools 与 system 一个字节；
 * 两个转发档的唯一差别只剩 tools_search 在不在目录里。
 */
class AgentMergedToolLoadingTest {
    private class Prefix(val tools: String, val system: String)

    private fun prefixOf(request: ProviderRequest): Prefix {
        val system = JSONArray()
        for (index in 0 until request.messages.length()) {
            val message = request.messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "system") system.put(message)
        }
        return Prefix(request.tools.toString(), system.toString())
    }

    @Test fun searchModeForwardsDeferredToolsAndKeepsTheSearchTool() {
        val prefixes = mutableListOf<Prefix>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                val names = request.toolNames()
                // 无关键词命中的 prompt：原生组集合为空，所有延迟组都该走转发。
                assertTrue("转发入口必须常驻", AgentToolGroupCatalog.CALL in names)
                assertTrue("search 档保留工具级搜索", AgentToolSearchCatalog.SEARCH in names)
                assertTrue("延迟组未加载，schema 不进 tools", "set_volume" !in names)
                assertTrue(AgentToolGroupCatalog.USE_GROUP in names)
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    useGroup("open-1", AgentToolGroups.DEVICE_GROUP.id),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                // 打开了设备组，但原生组集合 run 内冻结：tools 与 system 一个字节都不变。
                assertTrue("打开的组也不改写 tools 字节", "set_volume" !in request.toolNames())
                assistant(content = "好了", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我看看这段逻辑", // 不含预开关键词：原生组集合为空
            provider = provider,
            toolLoadingSuffixMode = true,
            sessionId = "merged-search-forward",
            openedToolGroupsProvider = { emptySet() },
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
        )

        assertEquals(2, prefixes.size)
        assertEquals("第 2 轮 tools 字节变了", prefixes[0].tools, prefixes[1].tools)
        assertEquals("第 2 轮 system 字节变了", prefixes[0].system, prefixes[1].system)
    }

    @Test fun groupModeDiffersFromSearchOnlyByTheSearchTool() {
        val names = mutableListOf<Set<String>>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                names += request.toolNames()
                assistant(content = "好", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我看看这段逻辑",
            provider = provider,
            toolLoadingSuffixMode = true,
            toolLoadingSearchEnabled = false, // group 档：不下发 tools_search
            sessionId = "merged-group-forward",
            openedToolGroupsProvider = { emptySet() },
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
        )

        val tools = names.single()
        assertTrue("按组档同样走转发", AgentToolGroupCatalog.CALL in tools)
        assertTrue(AgentToolGroupCatalog.USE_GROUP in tools)
        assertFalse("按组档不下发 tools_search", AgentToolSearchCatalog.SEARCH in tools)
        assertTrue("延迟组未加载，schema 不进 tools", "set_volume" !in tools)
    }

    private fun ProviderRequest.toolNames(): Set<String> = buildSet {
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
                ?.takeIf { it.isNotBlank() }?.let(::add)
        }
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

    private fun useGroup(id: String, groupId: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", AgentToolGroupCatalog.USE_GROUP)
                    .put("arguments", JSONObject().put("group", groupId).toString()),
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
