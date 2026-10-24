package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The real loop/executor boundary, with no network, Android GUI or actual task side effects. */
class AgentReconnectLocalToolLoopIntegrationTest {
    @get:org.junit.Rule val timeout = org.junit.rules.Timeout.seconds(45)

    @Test fun interruptedFragmentsAreDiscardedAndRecoveredToolsExecuteExactlyOnce() {
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("finish the task"))
        val executed = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        val delays = mutableListOf<Long>()
        val catalog = JSONArray().put(tool("read_file")).put(tool("supervise_task"))
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "reconnect-local-tool-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                when (++requests) {
                    1 -> return toolResponse("already-executed", "read_file", "{\"path\":\"/fake/source\"}")
                    2 -> {
                        assertEquals(listOf("already-executed"), executed)
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "Checking the remaining task."))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 0, "{\"action\":"))
                        throw IOException("connection reset before complete tool arguments")
                    }
                    3 -> {
                        assertTrue(request.reconnectLocalToolsOnly)
                        assertFalse(request.reconnectTextOnly)
                        assertEquals(2, request.tools.length())
                        assertEquals(listOf("already-executed"), executed)
                        assertTrue(request.messages.toString().contains("already-executed"))
                        assertFalse(request.messages.toString().contains("{\\\"action\\\":"))
                        onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 0,
                            "{\"action\":\"guide\",\"task_id\":\"test-child\",\"guidance\":\"finish\"}"))
                        assertEquals("succeeded", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
                        // Restored transport does not execute a streamed fragment.
                        assertEquals(listOf("already-executed"), executed)
                        return toolResponse("recovered-call", "supervise_task",
                            "{\"action\":\"guide\",\"task_id\":\"test-child\",\"guidance\":\"finish\"}")
                    }
                    4 -> {
                        assertEquals(listOf("already-executed", "recovered-call"), executed)
                        return ProviderResponse(JSONObject().put("role", "assistant").put("content", "done")
                            .put("finish_reason", "stop"))
                    }
                    else -> throw AssertionError("unexpected extra request")
                }
            }
        }
        val result = AgentLoop(
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "test", systemPrompt = "", contextWindow = 1_000_000,
                errorReconnectPolicy = ErrorReconnectPolicy.WINDOW_30S.persistedValue),
            messages = history, tools = catalog, provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id
                AgentModelClient.ToolResult("{\"ok\":true}")
            },
            runController = AgentRunController(), traceFormatter = AgentTraceFormatter(), onEvent = events::add,
            modelRetry = AgentModelRetry(jitterFraction = 0.0) { _, delay -> delays += delay },
        ).run()
        assertEquals("done", result.content)
        assertEquals(4, requests)
        assertEquals(listOf(2_000L), delays)
        assertEquals(listOf("already-executed", "recovered-call"), executed)
        val toolResults = (0 until history.length()).map { history.getJSONObject(it) }
            .filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") }
        assertEquals(executed, toolResults)
        assertEquals(1, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().count { it.status == "succeeded" })
    }

    @Test fun historicalIdReplayIsExecutedLikeAnyOtherCallAndTheRunContinues() {
        // No id-based replay guard exists: a model echoing a historical call id is
        // executed normally and the protocol stays paired, matching the upstream loop.
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("finish remaining work"))
        val executed = mutableListOf<String>()
        var requests = 0
        val provider = object : AgentProviderClient {
            override val id = "historical-replay-loop-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,
                true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                return when (++requests) {
                    1 -> toolResponse("old", "read_file", "{}")
                    2 -> toolResponse("old", "read_file", "{}")
                    3 -> {
                        assertEquals(listOf("old", "old"), executed)
                        assertFalse(request.reconnectLocalToolsOnly)
                        toolResponse("fresh", "read_file", "{}")
                    }
                    4 -> {
                        assertEquals(listOf("old", "old", "fresh"), executed)
                        assertFalse(request.reconnectLocalToolsOnly)
                        ProviderResponse(JSONObject().put("role", "assistant")
                            .put("content", "done").put("finish_reason", "stop"))
                    }
                    else -> error("unexpected request")
                }
            }
        }
        val result = AgentLoop(
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test",
                model = "test", systemPrompt = "", contextWindow = 1_000_000,
                errorReconnectPolicy = "continuous"),
            messages = history, tools = JSONArray().put(tool("read_file")), provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                executed += call.id; AgentModelClient.ToolResult("committed")
            }, runController = AgentRunController(), traceFormatter = AgentTraceFormatter(), onEvent = {},
        ).run()
        assertEquals("done", result.content)
        assertEquals(4, requests)
        assertEquals(listOf("old", "old", "fresh"), executed)
    }

    private fun tool(name: String) = JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", name).put("parameters", JSONObject().put("type", "object")))
    private fun toolResponse(id: String, name: String, arguments: String) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
        .put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments)))))
}
