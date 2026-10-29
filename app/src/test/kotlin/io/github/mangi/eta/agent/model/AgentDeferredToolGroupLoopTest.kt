package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 延迟工具组在运行中途打开后，组内工具必须在下一轮真的进入目录并可被调用。
 * 这里盯的是**目录本身**，不是提示词：提示里有索引不等于工具能调。
 */
class AgentDeferredToolGroupLoopTest {
    @Test
    fun openingAGroupMidRunMakesItsToolsCallableInTheNextRound() {
        val opened = mutableSetOf<String>()
        val executed = mutableListOf<String>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                assertFalse("组没打开前不该有 run_command", request.tools.toString().contains("\"run_command\""))
                assertTrue("目录里应该有 tools_use_group", request.tools.toString().contains("\"tools_use_group\""))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("open-1", "tools_use_group", """{"group":"terminal"}"""),
                ))
            },
            { request, _ ->
                assertTrue("组打开后下一轮必须带上 run_command", request.tools.toString().contains("\"run_command\""))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("cmd-1", "run_command", """{"command":"ls"}"""),
                ))
            },
            { _, _ -> assistant(content = "完成", finishReason = "stop") },
        ))

        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true),
            prompt = "看看文件",
            provider = provider,
            openedToolGroupsProvider = { opened.toSet() },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                if (call.name == "tools_use_group") opened += "terminal"
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        assertEquals(listOf("open-1", "cmd-1"), executed)
    }

    @Test
    fun anUndeclaredGroupToolTellsTheModelHowToOpenItsGroup() {
        val provider = ScriptedProvider(listOf(
            { _, _ ->
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("cmd-1", "run_command", """{"command":"ls"}"""),
                ))
            },
            { request, _ ->
                val transcript = request.messages.toString()
                assertTrue(
                    "拒绝理由必须点名该工具所属的组与打开方式",
                    transcript.contains("tools_use_group(group=terminal)"),
                )
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true),
            prompt = "看看文件",
            provider = provider,
            openedToolGroupsProvider = { emptySet() },
            toolExecutor = AgentModelClient.ToolExecutor { error("不该执行") },
        )
    }

    @Test
    fun aGroupToolCallIsRejectedWhenTheGroupWasNeverOpened() {
        val provider = ScriptedProvider(listOf(
            { _, _ ->
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("cmd-1", "run_command", """{"command":"ls"}"""),
                ))
            },
            { request, _ ->
                assertTrue(
                    "未打开的组工具必须被拒绝",
                    request.messages.toString().contains("工具未在本次运行的能力目录中声明"),
                )
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig().copy(terminalTools = true),
            prompt = "看看文件",
            provider = provider,
            openedToolGroupsProvider = { emptySet() },
            toolExecutor = AgentModelClient.ToolExecutor { error("不该执行") },
        )
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
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
