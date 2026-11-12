package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.delegation.SubAgentTools
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 派发子任务不再重写请求前缀：结果解读规则随 `delegate_task` 回执落在历史尾部（＝后缀）。
 *
 * 规则约 700 字，原先在首次派发的下一轮插进 system，而 system 排在全部历史之前——
 * 一次派发就把整段 prompt 缓存作废。这里盯真实请求字节，并覆盖规则必须回到前缀的续跑场景。
 */
class AgentDelegationPrefixStabilityTest {
    private class Prefix(val tools: String, val system: String, val messageCount: Int)

    private fun prefixOf(request: ProviderRequest): Prefix {
        val system = JSONArray()
        for (index in 0 until request.messages.length()) {
            val message = request.messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "system") system.put(message)
        }
        return Prefix(request.tools.toString(), system.toString(), request.messages.length())
    }

    /** 只看历史（非 system）：续跑场景里规则本来就在前缀，混在一起会把它误判成回执。 */
    private fun historyText(request: ProviderRequest): String {
        val history = JSONArray()
        for (index in 0 until request.messages.length()) {
            val message = request.messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "system") history.put(message)
        }
        return history.toString()
    }

    @Test fun firstDispatchKeepsThePrefixByteIdenticalAndCarriesTheRuleInTheReceipt() {
        val prefixes = mutableListOf<Prefix>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assertFalse("派发前不该付结果规则的 token", historyText(request).contains(RULE_HEAD))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("dispatch-1", """{"task":"读一遍源码","role":"research"}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assertTrue("规则必须随回执进入历史", historyText(request).contains(RULE_HEAD))
                assertTrue("分页与验收条款一条都不能少", historyText(request).contains(RULE_TAIL))
                assertFalse("规则不该同时出现在系统前缀", prefixes.last().system.contains(RULE_HEAD))
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我调查一件事",
            provider = provider,
            additionalTools = delegationTools(),
            sessionId = "delegation-prefix-fresh",
            toolExecutor = AgentModelClient.ToolExecutor { dispatchReceipt("task-1") },
        )

        assertEquals(2, prefixes.size)
        assertEquals("派发后 tools 字节变了", prefixes[0].tools, prefixes[1].tools)
        assertEquals("派发后 system 字节变了", prefixes[0].system, prefixes[1].system)
        assertTrue("历史只应在尾部增长", prefixes[1].messageCount > prefixes[0].messageCount)
    }

    @Test fun aParallelBatchAttachesTheRuleOnlyOnce() {
        val requests = mutableListOf<ProviderRequest>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                requests += request
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("dispatch-1", """{"task":"读模块甲","role":"research"}"""),
                    toolCall("dispatch-2", """{"task":"读模块乙","role":"research"}"""),
                ))
            },
            { request, _ ->
                requests += request
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "帮我调查两件事",
            provider = provider,
            additionalTools = delegationTools(),
            sessionId = "delegation-prefix-parallel",
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                dispatchReceipt(if (call.id == "dispatch-1") "task-1" else "task-2")
            },
        )

        assertEquals(2, requests.size)
        assertEquals(
            "同一批并行派发只附一份规则",
            1,
            countOccurrences(historyText(requests[1]), RULE_HEAD),
        )
        assertEquals(prefixOf(requests[0]).system, prefixOf(requests[1]).system)
    }

    @Test fun aResumedConversationKeepsTheRuleInThePrefixFromTheFirstRound() {
        // 续跑：持久历史里的派发回执已脱敏，规则只能从本 run 第一轮起常驻前缀。
        // 这一轮之后再派发也不该改动前缀——它在 run 开始前就已定形。
        val prefixes = mutableListOf<Prefix>()
        val provider = ScriptedProvider(listOf(
            { request, _ ->
                prefixes += prefixOf(request)
                assertTrue("续跑首轮就该带上结果规则", prefixes.last().system.contains(RULE_HEAD))
                assistant(finishReason = "tool_calls", toolCalls = listOf(
                    toolCall("dispatch-2", """{"task":"接着读","role":"research"}"""),
                ))
            },
            { request, _ ->
                prefixes += prefixOf(request)
                assertFalse("规则已在前缀，回执不该再附一份", historyText(request).contains(RULE_HEAD))
                assistant(content = "完成", finishReason = "stop")
            },
        ))

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "继续",
            history = listOf(
                AgentModelClient.ConversationMessage(role = "user", content = "帮我调查一件事"),
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    toolCallsJson = JSONArray().put(
                        JSONObject()
                            .put("id", "dispatch-1")
                            .put("type", "function")
                            .put("function", JSONObject()
                                .put("name", "delegate_task")
                                .put("arguments", """{"redacted":true}""")),
                    ).toString(),
                ),
                AgentModelClient.ConversationMessage(
                    role = "tool",
                    toolCallId = "dispatch-1",
                    content = "[敏感工具结果已省略]",
                ),
            ),
            provider = provider,
            additionalTools = delegationTools(),
            sessionId = "delegation-prefix-resumed",
            toolExecutor = AgentModelClient.ToolExecutor { dispatchReceipt("task-2") },
        )

        assertEquals(2, prefixes.size)
        assertEquals(prefixes[0].tools, prefixes[1].tools)
        assertEquals(prefixes[0].system, prefixes[1].system)
    }

    private fun dispatchReceipt(taskId: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            JSONObject().put("ok", true).put("task_id", taskId).put("status", "running").toString(),
            sensitive = true,
        )

    private fun delegationTools(): JSONArray =
        JSONArray().also { SubAgentTools.appendTo(it, models = listOf("test-model")) }

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

    private fun toolCall(id: String, arguments: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", JSONObject().put("name", "delegate_task").put("arguments", arguments))

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
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

    private companion object {
        const val RULE_HEAD = "已经派出的子代理适用以下结果规则"
        const val RULE_TAIL = "completed 仅表示子任务执行结束"
    }
}