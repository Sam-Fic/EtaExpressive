package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型在本 run 里新建的配方，下一轮就必须真的能调。
 *
 * 配方表每轮重读（[AgentModelClient.complete] 的 recipeToolsProvider）：固定快照会让
 * 新建的配方在本 run 内永远缺席，模型下一次调用它就会被校验器以
 * 「工具未在本次运行的能力目录中声明」拒掉。
 */
class AgentRecipeToolRoundTest {
    /**
     * eta_author_tool 的合法入参：action=create 必须同时给 name/description/parameters/steps。
     * 少一个字段就会被参数校验拒掉，配方根本建不出来，测的就不是「下一轮可调用」了。
     */
    private val createRecipeArgs = """
        {"action":"create","name":"my_recipe","description":"自建工具","parameters":{"type":"object","properties":{}},"steps":[{"op":"literal","text":"ok"}]}
    """.trimIndent()

    @Test
    fun aRecipeAuthoredInRoundOneIsCallableInRoundTwo() {
        val requests = mutableListOf<ProviderRequest>()
        var authored = false
        val provider = object : AgentProviderClient {
            override val id: String = "recipe"
            override val capabilities: ProviderCapabilities = ProviderCapabilities(
                endpoint = EndpointKind.CHAT_COMPLETIONS,
                streamingText = true,
                streamingToolCalls = true,
                imageInput = true,
                toolResultImages = false,
                strictTools = false,
                parallelToolCalls = false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                onEvent(ProviderEvent.RequestStarted)
                requests += request
                return ProviderResponse(
                    when (requests.size) {
                        1 -> assistant(
                            finishReason = "tool_calls",
                            toolCalls = listOf(toolCall("author-1", "eta_author_tool", createRecipeArgs)),
                        )
                        2 -> assistant(
                            finishReason = "tool_calls",
                            toolCalls = listOf(toolCall("run-1", "recipe_my_recipe", """{}""")),
                        )
                        else -> assistant(content = "好了", finishReason = "stop")
                    },
                )
            }
        }

        val executed = mutableListOf<String>()
        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "造一个工具",
            provider = provider,
            recipeToolsProvider = {
                if (!authored) {
                    JSONArray()
                } else {
                    JSONArray().put(recipeSchema())
                }
            },
            // eta_author_tool 属于「Eta 自身与扩展」延迟组；不开这个组它根本不在目录里。
            openedToolGroupsProvider = { setOf("extensions") },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.name
                if (call.name == "eta_author_tool") authored = true
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        assertEquals(listOf("eta_author_tool", "recipe_my_recipe"), executed)
        assertFalse(
            "第一轮目录里不该有还没建好的配方",
            "recipe_my_recipe" in requests.first().toolNames(),
        )
        assertTrue(
            "第二轮目录里必须出现新建的配方",
            "recipe_my_recipe" in requests.last().toolNames(),
        )
        assertTrue(
            "配方被拒会写进对话，模型下一轮就再也调不动了",
            !requests.last().messages.toString().contains("工具未在本次运行的能力目录中声明"),
        )
    }

    private fun recipeSchema(): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "recipe_my_recipe")
                    .put("description", "自建工具「my_recipe」")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject())
                            .put("additionalProperties", false),
                    ),
            )

    private fun ProviderRequest.toolNames(): Set<String> = buildSet {
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)
                ?.optJSONObject("function")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }

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

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            browserTools = false,
        )
}
