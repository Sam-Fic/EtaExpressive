package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 同一批次的幂等只读工具并行执行；事件仍按模型给出的 index 顺序投递，
 * 历史与串行执行时一致。
 */
class AgentPureReadBatchTest {
    private fun catalog(vararg names: String): JSONArray = JSONArray().also { tools ->
        names.forEach { name ->
            tools.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", name).put(
                        "parameters",
                        JSONObject().put("type", "object").put("properties", JSONObject())
                            .put("additionalProperties", true),
                    ),
                ),
            )
        }
    }

    private fun toolCall(id: String, name: String) = JSONObject()
        .put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))

    private data class Run(
        val outcomes: List<String>,
        val toolStartedOrder: List<String>,
        val toolFinishedOrder: List<String>,
        val toolMessageOrder: List<String>,
        val concurrencyPeak: Int,
    )

    private fun run(
        toolNames: List<String>,
        gate: CountDownLatch? = null,
        started: CountDownLatch? = null,
    ): Run {
        val history = JSONArray().put(AgentConversationCodec.userTextMessage("go"))
        val calls = toolNames.mapIndexed { index, name -> toolCall("call-$index", name) }
        val requests = AtomicInteger()
        val provider = object : AgentProviderClient {
            override val id = "pure-read-test"
            override val capabilities = ProviderCapabilities(
                EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false,
            )
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                if (requests.incrementAndGet() == 1) {
                    return ProviderResponse(
                        JSONObject().put("role", "assistant").put("content", "working")
                            .put("finish_reason", "tool_calls").put("tool_calls", JSONArray(calls)),
                    )
                }
                return ProviderResponse(
                    JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
                )
            }
        }
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val finished = mutableListOf<String>()
        val startedOrder = mutableListOf<String>()
        val messagesOrder = mutableListOf<String>()
        AgentLoop(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid", apiKey = "k", model = "m", systemPrompt = "",
                contextWindow = 1_000_000,
            ),
            messages = history,
            tools = catalog(*toolNames.toTypedArray()),
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { tool ->
                val now = running.incrementAndGet()
                peak.updateAndGet { previous -> maxOf(previous, now) }
                started?.countDown()
                // 只读工具的等待会重叠：串行时这里永远等不到所有人同时到达。
                gate?.await(2, TimeUnit.SECONDS)
                running.decrementAndGet()
                synchronized(finished) { finished += tool.id }
                AgentModelClient.ToolResult("""{"ok":true,"id":"${tool.id}"}""")
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = { event ->
                when (event) {
                    is AgentEvent.ToolStarted -> startedOrder += event.toolCallId
                    is AgentEvent.ToolFinished -> messagesOrder += event.toolCallId
                    else -> Unit
                }
            },
        ).run()
        assertEquals(2, requests.get())
        return Run(finished.toList(), startedOrder.toList(), messagesOrder.toList(),
            messagesOrder.toList(), peak.get())
    }

    @Test fun pureReadBatchOverlapsAndKeepsModelOrder() {
        val names = listOf("read_file", "web_search", "memory_get", "fetch_url")
        // 4 个只读调用：让每个 worker 都先到齐，只有真正并行才能全部通过。
        val gate = CountDownLatch(4)
        val started = CountDownLatch(1)
        val result = run(names, gate = gate, started = started)
        assertTrue("只读批次应并行，峰值并发=${result.concurrencyPeak}", result.concurrencyPeak > 1)
        // 事件按模型给出的 index 顺序回放，不受完成顺序影响。
        assertEquals(listOf("call-0", "call-1", "call-2", "call-3"), result.toolStartedOrder)
        assertEquals(listOf("call-0", "call-1", "call-2", "call-3"), result.toolFinishedOrder)
    }

    @Test fun mixedBatchStaysSerial() {
        // 批次里含 GUI 工具时整批串行，保持原有的主屏独占语义。
        val result = run(listOf("read_file", "observe_screen"))
        assertEquals(1, result.concurrencyPeak)
        assertEquals(listOf("call-0", "call-1"), result.toolFinishedOrder)
    }

    @Test fun cheapLocalReadsAreNotOffloaded() {
        // 本地廉价查询不值得为它开线程：并行只对会等网络/磁盘的工具开放。
        assertTrue(!AgentPureReadTools.isPureRead("get_current_context"))
        assertTrue(!AgentPureReadTools.isPureRead("device_status"))
        assertTrue(!AgentPureReadTools.isPureRead("find_apps"))
    }

    @Test fun singlePureReadCallAlsoStaysSerial() {
        val result = run(listOf("read_file"))
        assertEquals(1, result.concurrencyPeak)
        assertEquals(listOf("call-0"), result.toolFinishedOrder)
    }

    @Test fun externalAndRecipeToolsAreNeverParallel() {
        // 外部服务语义未知，不能假设幂等。
        assertTrue(!AgentPureReadTools.isPureRead("mcp_server_search_deadbeef"))
        assertTrue(!AgentPureReadTools.isPureRead("recipe_mine"))
        assertTrue(!AgentPureReadTools.isPureRead("eta_set_settings"))
        assertTrue(!AgentPureReadTools.isPureRead("observe_screen"))
        assertTrue(AgentPureReadTools.isPureRead("read_file"))
        assertEquals(emptySet<Int>(), AgentPureReadTools.parallelIndices(listOf("read_file", "mcp_x")))
        assertEquals(setOf(0, 1), AgentPureReadTools.parallelIndices(listOf("read_file", "web_search")))
    }
}
