package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCompressionBoundaryTest {
    @get:Rule val timeout = org.junit.rules.Timeout.seconds(45)
    @get:Rule val temporary = TemporaryFolder()
    private fun message(role: String, text: String = "", id: String = "", calls: String = "") =
        AgentModelClient.ConversationMessage(role, content = text, toolCallId = id, toolCallsJson = calls)

    /**
     * 工具结果写入历史前的限长形态（见 AgentLoop.boundedToolResult）：
     * 超过 8 192 字符只保留头 4 608 / 尾 1 536，中间以省略提示连接。
     */
    private fun bounded(raw: String): String {
        if (raw.length <= 8_192) return raw
        val head = raw.take(4_608)
        val tail = raw.takeLast(1_536)
        return head + "\n\n…[省略 ${raw.length - head.length - tail.length} 字符：" +
            "工具结果入历史时只保留头 4608 / 尾 1536 字符。需要后续内容请用更窄的范围重新调用该工具。]…\n\n" + tail
    }

    @Test fun defaultPressureStartsAtEightyPercent() {
        val history = listOf(message("user", "old"), message("assistant", "result"), message("user", "current"))
        assertFalse(AgentContextCompactor.shouldCompress(history, 100_000, 1, estimatedTokens = 79_999))
        assertTrue(AgentContextCompactor.shouldCompress(history, 100_000, 1, estimatedTokens = 80_000))
    }

    @Test fun compressionCutsDoNotDependOnRequestRoundsOrSupplementTurnIds() {
        val history = listOf(message("user", "task"), message("assistant", "x".repeat(8000)),
            message("user", AgentContextCompactor.steeringUserContent("supplement")),
            message("assistant", calls = """[{"id":"a"},{"id":"b"}]"""),
            message("tool", "a", id = "a"), message("tool", "b", id = "b"))
        val sameTurn = history.map { it.copy(turnId = "original-turn") }
        val separateIds = history.mapIndexed { i, entry -> entry.copy(turnId = "request-$i") }
        val cut = AgentCompressionBoundary.selectStart(sameTurn, 10_000)
        assertTrue(cut > 0)
        assertEquals(cut, AgentCompressionBoundary.selectStart(separateIds, 10_000))
        assertTrue(cut in AgentCompressionBoundary.balancedCuts(sameTurn))
        assertTrue(cut <= 3) // Never split the latest parallel tool batch.
    }

    @Test fun parallelToolResultsCannotBeSplit() {
        val history = listOf(message("user", "old"),
            message("assistant", calls = "[{\"id\":\"a\"},{\"id\":\"b\"}]"),
            message("tool", "one", id = "a"), message("tool", "two", id = "b"), message("assistant", "done"))
        assertEquals(listOf(0, 1, 4, 5), AgentCompressionBoundary.balancedCuts(history))
        assertEquals(4, AgentCompressionBoundary.continuationStart(history, 1))
        assertEquals(1, AgentCompressionBoundary.continuationStart(history.dropLast(1), 1))
    }

    @Test fun continuationRetentionMatchesHarnessRatio() {
        assertEquals(3_200, AgentCompressionBoundary.continuationRetentionBudget(20_000))
        assertEquals(8_000, AgentCompressionBoundary.continuationRetentionBudget(50_000))
        assertEquals(20_480, AgentCompressionBoundary.continuationRetentionBudget(128_000))
        assertEquals(80_000, AgentCompressionBoundary.continuationRetentionBudget(500_000))
        assertEquals(1, AgentCompressionBoundary.continuationRetentionBudget(500_000, overflow = true))
    }

    @Test fun shortHistoryUnderOverheadPressureKeepsTheNewerHalfNotOnlyTheLastUnit() {
        // Field case: 500K window, 80K verbatim ceiling, ~35K local history, pressure from
        // request overhead/media. The old fallback kept 2 of 50 messages (~4%).
        val history = mutableListOf(message("user", "task"))
        repeat(24) { i ->
            history += message("assistant", calls = "[{\"id\":\"c$i\"}]")
            history += message("tool", "r".repeat(4_000), id = "c$i")
        }
        val cut = AgentCompressionBoundary.selectStart(history, 500_000)
        val kept = AgentCompressionBoundary.retainedTokens(history, cut)
        val total = AgentCompressionBoundary.retainedTokens(history, 0)
        assertTrue(cut in AgentCompressionBoundary.balancedCuts(history))
        assertTrue("kept=$kept total=$total", kept * 2 >= total)
        assertTrue(cut > 0 && cut < history.size - 2)
        // Confirmed overflow still collapses to the newest complete unit.
        assertEquals(history.size - 2, AgentCompressionBoundary.selectStart(history, 500_000, overflow = true))
    }

    @Test fun billedToLocalRatioScalesTheVerbatimBudgetDown() {
        assertEquals(80_000, AgentCompressionBoundary.localRetentionBudget(80_000, null, 40_000))
        assertEquals(80_000, AgentCompressionBoundary.localRetentionBudget(80_000, 0, 40_000))
        assertEquals(80_000, AgentCompressionBoundary.localRetentionBudget(80_000, 30_000, -1))
        // Same-request local over-count enlarges, but not past 1.5x.
        assertEquals(106_666, AgentCompressionBoundary.localRetentionBudget(80_000, 30_000, 40_000))
        assertEquals(120_000, AgentCompressionBoundary.localRetentionBudget(80_000, 10_000, 40_000))
        assertEquals(10_000, AgentCompressionBoundary.localRetentionBudget(80_000, 400_000, 50_000))
        val history = mutableListOf(message("user", "task"))
        repeat(40) { i ->
            history += message("assistant", calls = "[{\"id\":\"c$i\"}]")
            history += message("tool", "r".repeat(4_000), id = "c$i")
        }
        val plain = AgentCompressionBoundary.selectStart(history, 100_000)
        val billed = AgentCompressionBoundary.selectStart(history, 100_000, billedTokens = 400_000, localTokens = 40_000)
        assertTrue(billed > plain)
        assertTrue(billed in AgentCompressionBoundary.balancedCuts(history))
    }

    @Test fun continuationUsesTheSameTokenTailInIdleAndActiveRuns() {
        val history = listOf(message("user", "one long task"),
            message("assistant", calls = """[{"id":"old"}]"""),
            message("tool", "x".repeat(20_000), id = "old"),
            message("assistant", calls = """[{"id":"latest"}]"""),
            message("tool", "y".repeat(8_000), id = "latest"))
        val idle = AgentCompressionBoundary.selectStart(history, 10_000)
        val active = AgentCompressionBoundary.selectStart(history, 10_000)
        assertEquals(3, idle)
        assertEquals(idle, active)
        assertTrue(AgentContextCompactor.shouldCompress(history, 10_000, 0,
            estimatedTokens = 9500))
    }

    @Test fun overflowCanReduceRetentionButNeverSplitsTheLatestParallelBatch() {
        val history = listOf(message("user", "task"), message("assistant", "x".repeat(8000)),
            message("assistant", calls = """[{"id":"a"},{"id":"b"}]"""),
            message("tool", "a", id = "a"), message("tool", "b", id = "b"))
        assertEquals(1, AgentCompressionBoundary.selectStart(history, 10_000))
        assertEquals(2, AgentCompressionBoundary.selectStart(history, 10_000, overflow = true))
    }

    @Test fun orphanedOrUnfinishedToolsCannotBeCompacted() {
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(listOf(message("tool", "orphan", id = "a"))) }
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(listOf(message("assistant", calls = "[{\"id\":\"a\"}]"))) }
    }

    @Test fun budgetsReserveOutputAndSafetyMargin() {
        assertEquals(4392, AgentCompressionBoundary.inputLimit(9000))
        assertEquals(0, AgentCompressionBoundary.inputLimit(100))
        assertEquals(6000, AgentCompressionBoundary.outputReserve(config().copy(extraBodyJson = "{\"max_tokens\":6000}")))
    }

    @Test fun summaryInputKeepsToolArgumentsCorrelationAndStructuredTextNotHiddenReasoning() {
        val input = message("assistant", calls = "[{\"id\":\"c1\",\"function\":{\"name\":\"terminal\",\"arguments\":\"exact command\"}}]")
            .copy(contentJson = "[{\"type\":\"text\",\"text\":\"structured evidence\"}]", reasoningContent = "PRIVATE_REASONING")
        val text = AgentContextCompactor.messageToSummaryText(input)
        assertTrue(text.contains("exact command"))
        assertTrue(text.contains("structured evidence"))
        assertFalse(text.contains("PRIVATE_REASONING"))
        assertTrue(AgentContextCompactor.messageToSummaryText(message("tool", "exit_code=0", "c1")).contains("c1"))
    }

    @Test fun manualRequestsAreNotInheritedByAnotherController() {
        val controller = AgentRunController()
        controller.requestCompact()
        assertTrue(controller.hasPendingCompact)
        assertFalse(AgentRunController().hasPendingCompact)
    }

    @Test fun strictOverflowPausesWithoutSendingOrDeletingHistory() {
        val controller = AgentRunController()
        val source = JSONArray().put(JSONObject().put("role", "system").put("content", "x".repeat(40_000)))
            .put(AgentConversationCodec.userTextMessage("protected user"))
        val original = source.toString()
        val provider = provider { error("Over-budget request must never be sent") }
        var paused = false
        val loop = AgentLoop(config(9000), source, JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            systemCountProvider = { 1 }, onEvent = { if (it is AgentEvent.ContextCompacted && it.blocked) { paused = true; controller.cancel() } })
        assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        assertTrue(paused)
        source.getJSONObject(1).remove(AgentTurnIdentity.JSON_KEY)
        assertEquals(original, source.toString())
    }

    @Test fun forcedCompressionPreservesLongLiveToolBodyAndProviderFields() {
        val controller = AgentRunController()
        val toolBody = "Z".repeat(70_000)
        val currentUser = AgentConversationCodec.userTextMessage("active request").put("provider_private", JSONObject().put("opaque", "keep"))
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("old question " + "x".repeat(8000)))
            .put(JSONObject().put("role", "assistant").put("content", "old answer"))
            .put(currentUser)
        var calls = 0
        var compressed = false
        val provider = provider { request ->
            if (calls++ == 0) JSONObject().put("role", "assistant").put("content", "")
                .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "tool-1").put("type", "function").put("function", JSONObject()
                        .put("name", "get_current_context").put("arguments", "{}"))))
            else {
                val liveCall = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .single { it.has("tool_calls") }
                assertEquals("keep", liveCall.getJSONObject("provider_private").getString("opaque"))
                val result = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .single { it.optString("role") == "tool" }
                assertEquals(bounded(toolBody), result.getString("content"))
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
            }
        }
        val model = config(100_000)
        AgentLoop(model, source, AgentToolCatalog.build(terminalTools = false, browserTools = false), provider,
            AgentModelClient.ToolExecutor {
                // Inject into the actual replay snapshot, after normal response serialization.
                source.getJSONObject(source.length() - 1).put("provider_private", JSONObject().put("opaque", "keep"))
                controller.requestCompact(1)
                AgentModelClient.ToolResult(toolBody)
            },
            controller, AgentTraceFormatter(), onEvent = { if (it is AgentEvent.ContextCompacted && it.applied) compressed = true },
            compactPolicy = AgentLoop.CompactPolicy(false, 100_000, 1, model),
            compactHistory = { history, policy -> listOf(message("user", "[Conversation summary]\nold work")) + history.drop(requireNotNull(policy.keepStartOverride)) },
        ).run()
        assertTrue(compressed)
        assertEquals(2, calls)
    }

    @Test fun firstRequestDoesNotAutoCompactFromLocalHistoryEstimate() {
        // The fixture must sit inside the band between the 80% auto-compaction pressure line and
        // the conservative uncalibrated hard send limit (same shape as AgentAutomaticCompactionTest).
        // A 100k window can no longer host that band once the 12% reserve landed: its hard limit
        // (~78.9k) is below the 80% line (~80k), so the old ~90k fixture paused instead of sending.
        // 260k reopens a 208k..211.7k band for the same local-only estimate.
        val window = 260_000
        val huge = "x".repeat(830_000)
        val source = JSONArray().put(AgentConversationCodec.userTextMessage(huge))
            .put(JSONObject().put("role", "assistant").put("content", "old result"))
            .put(AgentConversationCodec.userTextMessage("y".repeat(8000)))
        val model = config(window)
        val outputReserve = AgentCompressionBoundary.outputReserve(model)
        val pressure = AgentContextCompactor.autoPressureTokens(window)
        val hardLimit = AgentCompressionBoundary.inputLimit(window, outputReserve, calibrated = false)
        val local = AgentRequestTokenEstimate.boundary(source, JSONArray(), model.supportsVision, model.supportsVideo)
        // Above 80% of the window, so a target receipt could schedule a summary...
        assertTrue("local=$local pressure=$pressure", local >= pressure)
        // ...but below the uncalibrated send limit, so the first request still leaves unsummarized.
        assertTrue("local=$local hardLimit=$hardLimit", local <= hardLimit)
        // The 12% reserve must keep the uncalibrated ceiling strictly below the calibrated one.
        assertTrue(hardLimit < AgentCompressionBoundary.inputLimit(window, outputReserve))
        var compacted = false
        var requests = 0
        val controller = AgentRunController()
        AgentLoop(model, source, JSONArray(), provider { request ->
            requests++
            assertFalse(compacted)
            assertEquals(huge, request.messages.getJSONObject(0).getString("content"))
            assertEquals("y".repeat(8000), request.messages.getJSONObject(2).getString("content"))
            JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
        }, AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = {
                if (it is AgentEvent.ContextCompacted) {
                    if (it.applied) compacted = true
                    // A hard-limit pause must fail fast instead of hanging until the 45s rule.
                    if (it.blocked) controller.cancel()
                }
            },
            compactPolicy = AgentLoop.CompactPolicy(true, window, 1, model),
            compactHistory = { _, _ -> error("No cloud usage: must not summarize") },
        ).run()
        assertEquals(1, requests)
        assertFalse(compacted)
    }

    @Test fun manualContinuationSummarizesOldStepsButPreservesLatestBatchWithinOneRun() {
        val controller = AgentRunController()
        val model = config(20_000)
        val events = mutableListOf<AgentEvent>()
        // 工具结果入历史前会限长到约 6.2k 字符；拉丁正文只值约 1.5k token，
        // 整段历史低于 3.2k 保留预算时切点会退化成 0。改用 CJK 稠密正文，
        // 限长后最新批次仍超过预算，切点才会停在它（第 3 条）之前。
        val tail = "中".repeat(16_000)
        var requests = 0
        var toolRuns = 0
        var summaries = 0
        val result = AgentLoop(model, JSONArray().put(AgentConversationCodec.userTextMessage("one long task")),
            JSONArray("""[{"type":"function","function":{"name":"get_current_context","parameters":{"type":"object","properties":{}}}}]"""), provider { request ->
                requests++
                if (requests <= 2) JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                        .put("id", "call-$requests").put("type", "function").put("function", JSONObject()
                            .put("name", "get_current_context").put("arguments", "{}"))))
                else {
                    assertEquals(1, summaries)
                    val liveTool = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                        .single { it.optString("role") == "tool" }
                    assertEquals("call-2", liveTool.getString("tool_call_id"))
                    assertEquals(bounded(tail), liveTool.getString("content"))
                    JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
                }
            }, AgentModelClient.ToolExecutor {
                toolRuns++
                if (toolRuns == 2) controller.requestCompact(0)
                AgentModelClient.ToolResult(if (toolRuns == 1) "O".repeat(20_000) else tail)
            }, controller, AgentTraceFormatter(), onEvent = {
                if (it is AgentEvent.ContextCompacted) assertFalse(it.reason, it.blocked)
                events += it
            },
            compactPolicy = AgentLoop.CompactPolicy(false, 20_000, 0, model),
            compactionArchive = AgentCompactionArchive(temporary.root, "same-run"),
            compactHistory = { history, _ ->
                summaries++
                // 旧工具结果在写入历史时已限长，压缩前不再需要额外修剪；被摘要的仍是完整单元。
                assertEquals(bounded("O".repeat(20_000)), history[2].content)
                assertEquals(bounded(tail), history.last().content)
                val cut = AgentCompressionBoundary.selectStart(history, 20_000)
                assertEquals(3, cut)
                listOf(message("user", "[Conversation summary]\nfirst step verified")) + history.drop(cut)
            },
        ).run()
        assertEquals("done", result.content)
        assertEquals(3, requests)
        assertEquals(2, toolRuns)
        // 工具结果写入历史时已限长到 8 192 字符以内，不再触发额外的「仅修剪」提交，
        // 因此本次 run 只提交一次真正的摘要压缩。
        assertEquals(1, events.filterIsInstance<AgentEvent.ContextCompacted>().count { it.applied })
    }

    @Test fun continuationCompactionRedactsSensitiveToolsInsteadOfFailing() {
        val controller = AgentRunController()
        val model = config(20_000)
        val events = mutableListOf<AgentEvent>()
        val tail = "L".repeat(16_000)
        val secret = "visible-pixels-secret"
        var requests = 0
        var toolRuns = 0
        var summaries = 0
        val tools = JSONArray(
            """[{"type":"function","function":{"name":"read_image","parameters":{"type":"object","properties":{}}}},""" +
                """{"type":"function","function":{"name":"get_current_context","parameters":{"type":"object","properties":{}}}}]"""
        )
        val result = AgentLoop(
            model,
            JSONArray().put(AgentConversationCodec.userTextMessage("one long task " + "x".repeat(20_000))),
            tools,
            provider { request ->
                requests++
                when (requests) {
                    1 -> JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put(
                            "tool_calls",
                            JSONArray().put(
                                JSONObject().put("id", "call-image").put("type", "function").put(
                                    "function",
                                    JSONObject().put("name", "read_image").put("arguments", """{"path":"/secret.jpg"}"""),
                                ),
                            ),
                        )
                    2 -> JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put(
                            "tool_calls",
                            JSONArray().put(
                                JSONObject().put("id", "call-live").put("type", "function").put(
                                    "function",
                                    JSONObject().put("name", "get_current_context").put("arguments", "{}"),
                                ),
                            ),
                        )
                    else -> {
                        assertEquals(1, summaries)
                        assertFalse(request.messages.toString().contains(secret))
                        JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
                    }
                }
            },
            AgentModelClient.ToolExecutor { call ->
                toolRuns++
                if (call.name == "read_image") AgentModelClient.ToolResult(secret, sensitive = true)
                else {
                    controller.requestCompact(0)
                    AgentModelClient.ToolResult(tail)
                }
            },
            controller,
            AgentTraceFormatter(),
            onEvent = {
                if (it is AgentEvent.ContextCompacted) {
                    assertFalse(it.reason, it.blocked)
                    assertFalse(it.reason.contains("不允许持久化"))
                }
                events += it
            },
            compactPolicy = AgentLoop.CompactPolicy(false, 20_000, 0, model),
            compactionArchive = AgentCompactionArchive(temporary.root, "sensitive-run"),
            compactHistory = { history, _ ->
                summaries++
                val encoded = history.joinToString { it.content + it.toolCallsJson }
                assertFalse(encoded.contains(secret))
                assertFalse(encoded.contains("/secret.jpg"))
                assertEquals(bounded(tail), history.last().content)
                val lastBulk = history.indexOfLast { it.content == bounded(tail) }
                val keepFrom = if (lastBulk > 0 && history[lastBulk - 1].toolCallsJson.contains("get_current_context")) {
                    lastBulk - 1
                } else {
                    lastBulk.coerceAtLeast(0)
                }
                assertFalse(history.take(keepFrom).joinToString { it.content + it.toolCallsJson }.contains(secret))
                listOf(message("user", "[Conversation summary]\nlooked at an image")) + history.drop(keepFrom)
            },
        ).run()
        assertEquals("done", result.content)
        assertEquals(3, requests)
        assertEquals(2, toolRuns)
        assertEquals(1, summaries)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompacted>().any { it.applied })
        val archived = temporary.root.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(archived.contains(secret))
        assertFalse(archived.contains("/secret.jpg"))
    }

    @Test fun providerOverflowDoesNotBlindlyRetryAnUnchangedRequest() {
        var calls = 0
        val controller = AgentRunController()
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("active"))
        val loop = AgentLoop(config(9000), source, JSONArray(), provider {
            calls++
            throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "too large")
        }, AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = { if (it is AgentEvent.ContextCompacted && it.blocked) controller.cancel() })
        assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        assertEquals(1, calls)
        assertEquals("active", source.getJSONObject(0).getString("content"))
    }

    @Test fun largeWindowDoesNotPauseAtHalfCapacityJustBecauseHistoryIsLong() {
        var calls = 0
        val controller = AgentRunController()
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(900_000)))
        val loop = AgentLoop(
            config(500_000),
            source,
            JSONArray(),
            provider {
                calls++
                JSONObject().put("role", "assistant").put("content", "ok").put("finish_reason", "stop")
            },
            AgentModelClient.ToolExecutor { error("No tools") },
            controller,
            AgentTraceFormatter(),
            onEvent = {},
        )
        val result = loop.run()
        assertEquals("ok", result.content)
        assertEquals(1, calls)
    }

    @Test fun overflowClassificationDoesNotTreatAllBadRequestsAsContextErrors() {
        assertEquals("CONTEXT_WINDOW_EXCEEDED", AgentModelFailure.http(400, "{\"error\":{\"code\":\"context_length_exceeded\"}}").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400, "{\"error\":{\"message\":\"unknown parameter\"}}").code)
        assertEquals("HTTP_401", AgentModelFailure.http(401, "{\"error\":{\"code\":\"context_length_exceeded\"}}").code)
    }

    @Test fun compressionEndpointOverridesOpenAiCompatibleProviders() {
        val responses = config().copy(openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        val chat = AgentCompressionEndpoint.apply(responses, io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS, chat.openAiEndpointMode)
        val back = AgentCompressionEndpoint.apply(chat, io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES, back.openAiEndpointMode)
    }

    @Test fun compressionEndpointDoesNotOverrideCodexOrRemovedBackend() {
        // Codex 由订阅认证方式识别（不是 baseUrl 长得像官方域名）。
        val codex = config().copy(
            baseUrl = "https://chatgpt.com/backend-api/codex",
            authMode = io.github.mangi.eta.data.model.ProviderAuthModes.OPENAI_CHATGPT,
            openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES,
        )
        assertEquals(codex, AgentCompressionEndpoint.apply(codex, io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS))
        val removed = config().copy(
            baseUrl = "https://cloudcode-pa.googleapis.com",
            openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES,
        )
        assertEquals(removed, AgentCompressionEndpoint.apply(removed, io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS))
    }

    @Test fun queuedManualEndpointOnlyOverridesThatCompression() {
        val automatic = config(100_000)
        val manual = automatic.copy(model = "manual-summary",
            openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        val controller = AgentRunController()
        controller.requestCompact(compressModelConfig = manual)
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("old ".repeat(4000)))
            .put(AgentConversationCodec.userTextMessage("latest"))
        var summaryCalls = 0
        AgentLoop(automatic, source, JSONArray(), provider { request ->
            assertEquals(automatic.model, request.config.model)
            assertEquals(automatic.openAiEndpointMode, request.config.openAiEndpointMode)
            JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
        }, AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = {}, compactPolicy = AgentLoop.CompactPolicy(false, 100_000, 0, automatic),
            compactHistory = { history, policy ->
                summaryCalls++
                assertEquals(manual, policy.compressModelConfig)
                listOf(message("user", "[Conversation summary] old")) + history.drop(requireNotNull(policy.keepStartOverride))
            }).run()
        assertEquals(1, summaryCalls)
    }

    @Test fun compressionEndpointLeavesNativeProviderAndSessionUntouched() {
        val native = config().copy(providerType = io.github.mangi.eta.data.model.ProviderTypes.ANTHROPIC)
        assertEquals(native, AgentCompressionEndpoint.apply(native, io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES))
        val session = config().copy(openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS,
            AgentCompressionEndpoint.apply(session, null).openAiEndpointMode)
        assertEquals(io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES, session.openAiEndpointMode)
    }

    @Test fun singleUserFiftySevenRoundOpaqueRunCutsToNewestBatchNotTheOpeningUser() {
        val history = opaqueToolRun(rounds = 57)
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 0))
        val cut = AgentCompressionBoundary.selectStart(history, 500_000)
        assertEquals(cut, AgentCompressionBoundary.opaqueTailCut(history, 0))
        assertTrue(cut in AgentCompressionBoundary.balancedCuts(history))
        assertEquals(history.size - 2, cut)
        assertEquals(1, history.drop(cut).count { AgentCompressionBoundary.opaqueReplayItemCount(it) > 0 })
        assertEquals(56, history.take(cut).count { AgentCompressionBoundary.opaqueReplayItemCount(it) > 0 })
        val plan = AgentCompressionBoundary.planRetention(history, 500_000)
        assertNull(plan.stopReason)
        assertEquals(1, plan.opaqueReplayItems)
        assertTrue(plan.opaqueEncryptedChars > 0)
    }

    @Test fun ordinaryHistoryWithoutOpaqueReplayKeepsThePricedTail() {
        val history = mutableListOf(message("user", "task"))
        repeat(40) { index ->
            history += message("assistant", calls = "[{\"id\":\"c$index\"}]")
            history += message("tool", "r".repeat(4_000), id = "c$index")
        }
        assertEquals(
            AgentCompressionBoundary.selectStart(history, 100_000, billedTokens = 400_000, localTokens = 40_000),
            AgentCompressionBoundary.planRetention(history, 100_000, billedTokens = 400_000, localTokens = 40_000).cut,
        )
    }

    @Test fun pendingParallelToolPairStaysWithTheNewestCompleteBatch() {
        val older = opaqueToolRun(rounds = 2)
        val history = older + listOf(
            message("assistant", calls = "[{\"id\":\"a\"},{\"id\":\"b\"}]").copy(responsesReasoningJson = reasoningJson("live")),
            message("tool", "one", id = "a"),
        )
        val cut = AgentCompressionBoundary.selectStart(history, 500_000)
        assertTrue(cut in AgentCompressionBoundary.availableCuts(history))
        assertFalse(cut in runCatching { AgentCompressionBoundary.balancedCuts(history) }.getOrDefault(emptyList()))
        val tail = history.drop(cut)
        assertTrue(tail.any { it.toolCallsJson.contains("\"a\"") && it.toolCallsJson.contains("\"b\"") })
        assertTrue(tail.any { it.toolCallId == "a" })
        assertTrue(history.take(cut).any { AgentCompressionBoundary.opaqueReplayItemCount(it) > 0 })
    }

    @Test fun malformedOrMissingReasoningDoesNotInventACut() {
        val plain = listOf(message("user", "only"), message("assistant", "done"))
        assertEquals(AgentCompressionBoundary.selectStart(plain, 10_000), AgentCompressionBoundary.selectStart(plain, 10_000))
        val malformed = plain.map { it.copy(responsesReasoningJson = "{not-json") }
        assertEquals(0, AgentCompressionBoundary.opaqueReplayItemCount(malformed[1]))
        assertEquals(
            AgentCompressionBoundary.selectStart(plain, 10_000),
            AgentCompressionBoundary.selectStart(malformed, 10_000),
        )
        val noUser = listOf(
            message("assistant", calls = "[{\"id\":\"a\"}]").copy(responsesReasoningJson = reasoningJson("a")),
            message("tool", "a", id = "a"),
            message("assistant", calls = "[{\"id\":\"b\"}]").copy(responsesReasoningJson = reasoningJson("b")),
            message("tool", "b", id = "b"),
        )
        assertEquals(2, AgentCompressionBoundary.selectStart(noUser, 500_000))
    }

    @Test fun unsplittableOpaqueChainStopsWithoutClaimingSuccess() {
        val history = listOf(
            message("assistant", calls = "[{\"id\":\"only\"}]").copy(responsesReasoningJson = reasoningJson("only")),
            message("tool", "done", id = "only"),
        )
        val plan = AgentCompressionBoundary.planRetention(history, 500_000)
        assertEquals(0, plan.cut)
        assertEquals(AgentCompressionBoundary.OPAQUE_CUT_STOP, plan.stopReason)
    }

    @Test fun removedOpaqueUnitsCountAsProgressEvenWhenPricedTokensGrow() {
        val before = opaqueToolRun(rounds = 57)
        val cut = AgentCompressionBoundary.selectStart(before, 500_000)
        val summary = message("user", "S".repeat(24_000))
        val after = listOf(summary) + before.drop(cut)
        assertTrue(AgentContextBudget.countMessage(summary).toLong() + before.drop(cut).sumOf { AgentContextBudget.countMessage(it).toLong() } >=
            before.sumOf { AgentContextBudget.countMessage(it).toLong() })
        assertTrue(AgentCompressionBoundary.compactionReduced(
            before, after, before.size - cut, AgentCompressionBoundary.summaryProgressBudget(500_000)))
        assertFalse(AgentCompressionBoundary.compactionReduced(
            before, before, before.size, AgentCompressionBoundary.summaryProgressBudget(500_000)))
        assertFalse(AgentCompressionBoundary.compactionReduced(
            before, listOf(summary) + before, before.size, AgentCompressionBoundary.summaryProgressBudget(500_000)))
    }

    @Test fun explicitCutStaysAuthoritativeAndChatCompletionsCannotExcuseGrowth() {
        val responses = config().copy(openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.RESPONSES)
        val chat = responses.copy(openAiEndpointMode = io.github.mangi.eta.data.model.OpenAiEndpointMode.CHAT_COMPLETIONS)
        val other = responses.copy(model = "other-model")
        val live = JSONObject().put("role", "assistant").put("content", "note")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "seed").put("type", "function")
                .put("function", JSONObject().put("name", "tool").put("arguments", "{}"))))
        ResponsesEphemeralState.attachOutputItems(live, JSONArray().put(
            JSONObject().put("type", "reasoning").put("id", "rs").put("encrypted_content", "cipher"),
        ))
        ResponsesReasoningState.capture(live, responses)
        val reasoned = AgentConversationCodec.fromJsonObject(live)
        assertTrue(AgentCompressionBoundary.replayedOpaqueItemCount(reasoned, responses) > 0)
        assertEquals(0, AgentCompressionBoundary.replayedOpaqueItemCount(reasoned, chat))
        assertEquals(0, AgentCompressionBoundary.replayedOpaqueItemCount(reasoned, other))
        val history = mutableListOf(message("user", "task"))
        repeat(8) { index ->
            history += reasoned.copy(toolCallsJson = "[{\"id\":\"c$index\"}]", content = "note")
            history += message("tool", "ok", id = "c$index")
        }
        val plain = history.map { it.copy(responsesReasoningJson = "") }
        val plainCut = AgentCompressionBoundary.selectStart(plain, 500_000)
        assertEquals(plainCut, AgentCompressionBoundary.selectStart(history, 500_000, opaqueItems = {
            AgentCompressionBoundary.replayedOpaqueItemCount(it, chat)
        }))
        assertEquals(plainCut, AgentCompressionBoundary.selectStart(history, 500_000, opaqueItems = {
            AgentCompressionBoundary.replayedOpaqueItemCount(it, other)
        }))
        val kept = AgentCompressionBoundary.selectStart(history, 500_000, opaqueItems = {
            AgentCompressionBoundary.replayedOpaqueItemCount(it, responses)
        })
        assertTrue(kept > plainCut)
        assertEquals(1, AgentContextCompactor.resolveKeepStart(history, 0, 1))
        assertEquals(
            AgentCompressionBoundary.opaqueTailCut(history, AgentContextCompactor.recentKeepStartIndex(history, 0)),
            AgentContextCompactor.resolveKeepStart(history, 0, null) {
                AgentCompressionBoundary.replayedOpaqueItemCount(it, responses)
            },
        )
        val summary = message("user", "S".repeat(24_000))
        val after = listOf(summary) + history.drop(kept)
        val budget = AgentCompressionBoundary.summaryProgressBudget(500_000)
        assertTrue(after.sumOf { AgentContextBudget.countMessage(it).toLong() } >= history.sumOf { AgentContextBudget.countMessage(it).toLong() })
        assertTrue(AgentCompressionBoundary.compactionReduced(history, after, history.size - kept, budget) {
            AgentCompressionBoundary.replayedOpaqueItemCount(it, responses) > 0
        })
        assertFalse(AgentCompressionBoundary.compactionReduced(history, after, history.size - kept, budget) {
            AgentCompressionBoundary.replayedOpaqueItemCount(it, chat) > 0
        })
    }

    private fun opaqueToolRun(rounds: Int): List<AgentModelClient.ConversationMessage> {
        val history = mutableListOf(message("user", "single task"))
        repeat(rounds) { index ->
            history += message("assistant", "note", calls = "[{\"id\":\"c$index\"}]")
                .copy(responsesReasoningJson = reasoningJson("c$index"))
            history += message("tool", "ok", id = "c$index")
        }
        return history
    }

    private fun reasoningJson(id: String) = org.json.JSONObject()
        .put("scope", "ab".repeat(32))
        .put("items", org.json.JSONArray().put(
            org.json.JSONObject().put("type", "reasoning").put("id", id).put("encrypted_content", "cipher-$id"),
        ))
        .toString()

    private fun config(window: Int = 9000) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = "", contextWindow = window,
    )

    private fun provider(block: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            ProviderResponse(block(request))
    }
}
