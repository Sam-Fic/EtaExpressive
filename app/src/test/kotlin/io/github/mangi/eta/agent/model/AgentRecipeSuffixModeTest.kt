package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缓存优先档下，模型新建配方不再改写请求前缀。
 *
 * 配方 schema 原先在目录构建**之后**无条件追加，绕过了 suffix 的折叠逻辑：每新建或停用
 * 一份配方，`tools` 与 system 就重写一次，而它们排在全部历史之前，整段前缀缓存随之作废。
 * 这里盯真实请求字节，并带一个渐进加载档的对照组，证明断言不是恒真的。
 */
class AgentRecipeSuffixModeTest {
    private class Prefix(val tools: String, val system: String)

    private fun prefixOf(request: ProviderRequest): Prefix {
        val system = JSONArray()
        for (index in 0 until request.messages.length()) {
            val message = request.messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "system") system.put(message)
        }
        return Prefix(request.tools.toString(), system.toString())
    }

    @Test fun authoringARecipeKeepsToolsAndSystemByteIdentical() {
        val prefixes = mutableListOf<Prefix>()
        var authored = false
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assertFalse("首轮不该有还没建好的配方", "recipe_my_recipe" in request.toolNames())
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    forward("author-1", "eta_author_tool", JSONObject(CREATE_ARGS)),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                // 配方表这一轮已经多了一份，但它不该出现在目录里。
                assertFalse("配方永不进 tools", "recipe_my_recipe" in request.toolNames())
                assertTrue("转发入口必须常驻", AgentToolGroupCatalog.CALL in request.toolNames())
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    forward("run-1", "recipe_my_recipe", JSONObject()),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assistant(content = "好了", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "造一个工具",
            provider = provider,
            toolLoadingSuffixMode = true,
            sessionId = "recipe-suffix-prefix",
            recipeToolsProvider = { if (authored) JSONArray().put(recipeSchema()) else JSONArray() },
            openedToolGroupsProvider = { setOf("extensions") },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == AgentToolGroupCatalog.CALL &&
                    JSONObject(call.argumentsJson).optString("name") == "eta_author_tool"
                ) {
                    authored = true
                }
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
        // 对照组：渐进加载档下新建配方必须让 tools 变一次，
        // 否则「缓存优先档省掉了这次前缀重写」这个结论就没有被验证到。
        val prefixes = mutableListOf<Prefix>()
        var authored = false
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    direct("author-1", "eta_author_tool", JSONObject(CREATE_ARGS)),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assertTrue("渐进加载档下一轮应出现新配方", "recipe_my_recipe" in request.toolNames())
                assistant(content = "好了", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "造一个工具",
            provider = provider,
            sessionId = "recipe-legacy-prefix",
            recipeToolsProvider = { if (authored) JSONArray().put(recipeSchema()) else JSONArray() },
            openedToolGroupsProvider = { setOf("extensions") },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == "eta_author_tool") authored = true
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        assertEquals(2, prefixes.size)
        assertNotEquals(prefixes[0].tools, prefixes[1].tools)
    }

    @Test fun theForwardingEntryTellsTheModelRecipesGoThroughIt() {
        fun entry(extensions: Boolean) = AgentToolCatalog.build(
            terminalTools = true, browserTools = false, screenTools = false,
            deviceTools = false, personalTools = false, extensionsTools = extensions,
            capabilities = AgentToolCapabilities(rootAvailable = false),
            openedToolGroups = emptySet(), nativeGroups = emptySet(),
        ).let { tools ->
            (0 until tools.length()).map { tools.getJSONObject(it) }
                .first { it.getJSONObject("function").getString("name") == AgentToolGroupCatalog.CALL }
                .getJSONObject("function").getString("description")
        }
        assertTrue("开关放行时必须说明配方也走这里", entry(extensions = true).contains("recipe_<name>"))
        // 开关关掉时配方根本不可执行，入口不该宣传一个不存在的能力。
        assertFalse(entry(extensions = false).contains("recipe_<name>"))
    }

    private fun ProviderRequest.toolNames(): Set<String> = buildSet {
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)?.optJSONObject("function")?.optString("name")
                ?.takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private fun recipeSchema(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "recipe_my_recipe")
                    .put("description", "自建工具「my_recipe」")
                    .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())),
            )

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

    private fun forward(id: String, name: String, arguments: JSONObject): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", AgentToolGroupCatalog.CALL)
                    .put("arguments", JSONObject().put("name", name).put("arguments", arguments).toString()),
            )

    private fun direct(id: String, name: String, arguments: JSONObject): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments.toString()))

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
        const val CREATE_ARGS =
            """{"action":"create","name":"my_recipe","recipe":{"name":"my_recipe","description":"自建工具","parameters":{"type":"object","properties":{}},"steps":[{"op":"literal","text":"ok"}]}}"""
    }
}