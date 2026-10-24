package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import io.github.mangi.eta.data.model.ProviderAuthModes
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CancellationException

/** No sleeps or real network: the same clock advances waits AND in-flight request work. */
class AgentErrorReconnectTest {
    private class Clock : ReconnectTiming {
        private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        private val tasks = mutableListOf<Task>()
        override fun nowMs() = now
        override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
            val task = Task(now + delayMs, action)
            tasks += task
            return AutoCloseable { task.cancelled = true }
        }
        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
                tasks.remove(task)
                now = task.at
                task.action()
            }
            now = end
        }
    }
    private fun config(policy: ErrorReconnectPolicy, authMode: String = ProviderAuthModes.API_KEY) =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "never-in-events", model = "test", systemPrompt = "",
            errorReconnectPolicy = policy.persistedValue, authMode = authMode)
    private fun ok(text: String = "done") = ProviderResponse(JSONObject().put("role", "assistant")
        .put("content", text).put("finish_reason", "stop"))
    private fun provider(endpoint: EndpointKind = EndpointKind.CHAT_COMPLETIONS,
        action: (ProviderRequest, AgentRunController, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(endpoint, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
                action(request, runController, onEvent)
        }
    private fun run(clock: Clock, policy: ErrorReconnectPolicy, provider: AgentProviderClient,
        events: MutableList<AgentEvent> = mutableListOf(), controller: AgentRunController = AgentRunController(),
        providerEvents: MutableList<ProviderEvent> = mutableListOf(), hosted: Boolean = false,
        wait: ((AgentRunController, Long) -> Unit)? = null,
        onCancelledResponse: (ProviderResponse) -> Unit = {},
        tools: JSONArray = JSONArray(),
        reconnectTextOnly: Boolean = false,
        authMode: String = ProviderAuthModes.API_KEY,
        jitter: Double = 0.0,
        jitterSource: () -> Double = { 0.5 },
    ) = AgentModelRetry(
        timing = clock,
        jitterFraction = jitter,
        jitterSource = jitterSource,
        waitBeforeRetry = { control, ms -> (wait ?: { _: AgentRunController, delay: Long -> clock.advance(delay) })(control, ms) },
    ).complete(
        1, ProviderRequest(config(policy, authMode).copy(hostedWebSearchEnabled = hosted), JSONArray(), tools,
            reconnectTextOnly = reconnectTextOnly), provider,
        controller, events::add, { _, event -> providerEvents += event }, {}, onCancelledResponse)

    @Test fun noneDoesNotHaveLegacyThreeNetworkRetries() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.NONE, provider { _, _, _ -> calls++; throw IOException() }, events,
                wait = { _, _ -> fail("none cannot wait") })
        }
        assertEquals(1, calls)
        assertEquals(listOf("failed"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
        assertTrue(events.none { it is AgentEvent.ModelRetryScheduled })
    }

    @Test fun finitePoliciesExpireFromFirstErrorNotEachRetryAndTickEverySecond() {
        for (policy in listOf(ErrorReconnectPolicy.WINDOW_30S, ErrorReconnectPolicy.WINDOW_1M, ErrorReconnectPolicy.WINDOW_5M)) {
            val clock = Clock()
            val events = mutableListOf<AgentEvent>()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, policy, provider { _, _, _ ->
                    calls++
                    if (calls == 1) clock.advance(7_000) // Not part of reconnect budget before first error.
                    else clock.advance(1_700)
                    // 暂时性错误：本例验证的是窗口机制（从首个错误计时、每秒 tick），
                    // 永久错误只允许一次重发，走的是另一条策略，见 permanentAuthFailureResendsOnce。
                    throw IOException()
                }, events)
            }
            assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
            val changed = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
            assertEquals(1, changed.map { it.reconnectId }.distinct().size)
            assertEquals("failed", changed.last().status)
            assertTrue(changed.zipWithNext().all { (a, b) -> a.elapsedMs <= b.elapsedMs })
            assertEquals((0 until policy.windowMillis!! step 1_000).toList(), changed.filter { it.status == "running" }.map { it.elapsedMs })
            assertTrue(clock.now < 7_000 + policy.windowMillis!! + 1_700)
            assertTrue(calls > 4) // No fixed network retry counter.
        }
    }

    @Test fun deadlineCancelsInflightTransportButNotTheRunAndRejectsLateSuccess() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val controller = AgentRunController()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try {
                    late = emit
                    clock.advance(29_000)
                    assertTrue(cancelled)
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late answer"))
                    ok("late answer") // A provider ignoring cancellation cannot win.
                } finally { binding.close() }
            }, events, controller, delivered)
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertFalse(controller.isCancelled)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        assertEquals("failed", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
    }

    @Test fun continuousWithoutGeneratedEventsUsesValidatedReturnAsRecoveryEvidence() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val response = run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, emit ->
            if (calls++ == 0) throw AgentModelFailure.http(400, "")
            clock.advance(305_000)
            emit(ProviderEvent.Completed("stop"))
            assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().none { it.status == "succeeded" })
            ok()
        }, events)
        assertEquals("done", response.response.assistantMessage.getString("content"))
        assertEquals("succeeded", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        assertEquals(307_000L, clock.now)
    }

    @Test fun stopWhileWaitingCancelsImmediatelyAndDoesNotStartAnotherRequest() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> calls++; throw IOException() },
                events, controller, wait = { control, _ -> control.cancel() })
        }
        assertEquals(1, calls)
        assertEquals(listOf("running", "stopped"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
    }

    @Test fun stopInflightClosesTransportAndDropsLateCallbacks() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        assertThrows(AgentRunCancelledException::class.java) {
            run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try {
                    late = emit
                    control.cancel()
                    assertTrue(cancelled)
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late text"))
                    ok()
                } finally { binding.close() }
            }, events, providerEvents = delivered)
        }
        assertTrue(cancelled)
        assertEquals(2, calls)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        val changed = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals("stopped", changed.last().status)
        assertEquals(1, changed.count { it.status == "stopped" })
        assertTrue(changed.none { it.status == "succeeded" })
    }

    @Test fun stoppedToolResponseIsRecordedThenCancelledWithoutReplayingOrPublishing() {
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        val controller = AgentRunController()
        var calls = 0
        var cancelled = false
        var late: ((ProviderEvent) -> Unit)? = null
        var retained: ProviderResponse? = null
        assertThrows(AgentRunCancelledException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, control, emit ->
                if (calls++ == 0) throw IOException()
                val binding = control.register(interruptible = true) { cancelled = true }
                try {
                    late = emit
                    control.cancel()
                    ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                            .put("id", "stopped-child-call").put("type", "function").put("function", JSONObject()
                                .put("name", "delegate_task").put("arguments", "{}")))))
                } finally { binding.close() }
            }, events, controller, providerEvents = delivered, onCancelledResponse = { retained = it })
        }
        assertTrue(controller.isCancelled)
        assertTrue(cancelled)
        assertEquals(2, calls)
        assertEquals("stopped-child-call", AgentConversationCodec.parseToolCalls(requireNotNull(retained).assistantMessage).single().id)
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertTrue(delivered.isEmpty())
        assertEquals("stopped", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().none { it.status == "succeeded" })
    }

    @Test fun stopBeforeFirstResponseReturnDoesNotRequireReconnectToCancel() {
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        var late: ((ProviderEvent) -> Unit)? = null
        assertThrows(AgentRunCancelledException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, control, emit ->
                calls++
                late = emit
                control.cancel()
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late text"))
                ok()
            }, events, providerEvents = delivered, wait = { _, _ -> fail("must not retry") })
        }
        late!!(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertEquals(1, calls)
        assertTrue(delivered.isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().isEmpty())
    }

    @Test fun cancelledResponseObserverFailureIsPreservedAndNeverRetried() {
        val original = IOException("cancelled response persistence failed")
        var calls = 0
        val thrown = assertThrows(IOException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, control, _ ->
                calls++
                control.cancel()
                ok()
            }, wait = { _, _ -> fail("must not retry") }, onCancelledResponse = { throw original })
        }
        assertSame(original, thrown)
        assertEquals(1, calls)
    }

    @Test fun partialTextContinuesWithDraftAndDoesNotRepeatStreamOrFinalBody() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val delivered = mutableListOf<ProviderEvent>()
        var calls = 0
        val result = run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { request, _, emit ->
            if (calls++ == 0) {
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "Hello world"))
                throw IOException()
            }
            assertTrue(AgentRecoveryContext.isActive(request.messages))
            val draft = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                .single { it.optString("role") == "assistant" }
            assertEquals("Hello world", draft.getString("content"))
            assertFalse(request.messages.toString().contains("tool_calls"))
            emit(ProviderEvent.RequestStarted)
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "world"))
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, " again"))
            ok("world again")
        }, events, providerEvents = delivered)
        assertEquals("Hello world again", result.response.assistantMessage.getString("content"))
        assertEquals("Hello world again", delivered.filterIsInstance<ProviderEvent.BlockDelta>().joinToString("") { it.delta })
        assertEquals(listOf("running", "running", "running", "succeeded"), events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().map { it.status })
    }

    @Test fun toolEvidenceKeepsLocalCatalogAndDisablesOnlyHostedRecovery() {
        for (event in listOf<ProviderEvent>(ProviderEvent.HostedToolStarted("h", "remote"),
            ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 0, "{}"))) {
            var calls = 0
            val events = mutableListOf<AgentEvent>()
            val result = run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { request, _, emit ->
                if (calls++ == 0) { emit(event); throw IOException() }
                assertFalse(request.reconnectTextOnly)
                assertTrue(request.reconnectLocalToolsOnly)
                assertEquals(1, request.tools.length())
                assertFalse(request.config.hostedWebSearchEnabled)
                ok()
            }, events, tools = localTools())
            assertEquals(2, calls)
            assertEquals("done", result.response.assistantMessage.getString("content"))
            assertEquals("succeeded", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        }
        var calls = 0
        run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { request, _, _ ->
            if (calls++ == 0) throw IOException()
            assertFalse(request.reconnectTextOnly)
            assertTrue(request.reconnectLocalToolsOnly)
            assertEquals(1, request.tools.length())
            assertFalse(request.config.hostedWebSearchEnabled)
            ok()
        }, hosted = true, tools = localTools())
        assertEquals(2, calls)
    }

    @Test fun repeatedToolEvidenceAndUnknownHostedFailuresOnlyFailAtDeadline() {
        for (policy in listOf(ErrorReconnectPolicy.WINDOW_30S, ErrorReconnectPolicy.WINDOW_1M,
            ErrorReconnectPolicy.WINDOW_5M)) {
            val clock = Clock()
            val events = mutableListOf<AgentEvent>()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, policy, provider { request, _, emit ->
                    if (calls++ == 0) emit(ProviderEvent.HostedToolStarted("h", "remote"))
                    else assertTrue(request.reconnectLocalToolsOnly)
                    throw IOException("offline")
                }, events, hosted = true)
            }
            assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
            assertTrue(calls > 2)
            assertEquals(policy.windowMillis, clock.now)
            val markers = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
            assertEquals(1, markers.map { it.reconnectId }.distinct().size)
            assertEquals(1, markers.count { it.status == "failed" })
            assertEquals(policy.windowMillis, markers.last().elapsedMs)
        }
    }

    @Test fun textOnlyRecoveryRejectsNewToolCallsAndStillUsesOriginalDeadline() {
        val clock = Clock()
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, _ ->
                if (calls++ == 0) throw IOException()
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                    .put("tool_calls", JSONArray().put(JSONObject().put("id", "unsafe"))))
            }, reconnectTextOnly = true)
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertEquals(30_000L, clock.now)
        assertTrue(calls > 2)
    }

    @Test fun incompleteArgumentsAndProviderParsingErrorsRetryUntilDeadline() {
        for (error in listOf<Exception>(
            AgentModelFailure("RESPONSES_TOOL_ARGUMENTS_INCOMPLETE", false, "arguments incomplete"),
            org.json.JSONException("gateway returned truncated JSON"))) {
            val clock = Clock()
            val events = mutableListOf<AgentEvent>()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, _ -> calls++; throw error }, events)
            }
            assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
            assertEquals(30_000L, clock.now)
            assertTrue(calls > 2)
            assertEquals(1, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().count { it.status == "failed" })
        }
    }

    @Test fun customPayloadCannotReenableToolsOnTextOnlyReconnect() {
        val request = ProviderRequest(config(ErrorReconnectPolicy.WINDOW_30S), JSONArray(), JSONArray(),
            reconnectTextOnly = true)
        val body = JSONObject().put("model", "test").put("tools", JSONArray())
            .put("tool_choice", "required").put("parallel_tool_calls", true)
            .put("web_search_options", JSONObject()).put("mcp_servers", JSONArray())
            .put("previous_response_id", "remote").put("conversation", "remote")
        request.restrictReconnectPayload(body)
        assertEquals(setOf("model"), body.keys().asSequence().toSet())
    }

    private fun localTools() = JSONArray().put(JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", "supervise_task").put("parameters", JSONObject().put("type", "object"))))

    @Test fun steeringDuringBackoffDoesNotIssueAnOldRequestOrLoseTheDraft() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val result = run(Clock(), ErrorReconnectPolicy.WINDOW_30S, provider { _, _, emit ->
            calls++
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "preserved draft"))
            throw IOException()
        }, events, controller, wait = { control, _ -> assertTrue(control.steer("new steering")) })
        assertEquals(1, calls)
        assertEquals(AssistantStopReason.INTERRUPTED, result.response.stopReason)
        assertEquals("preserved draft", result.response.assistantMessage.getString("content"))
        assertEquals("stopped", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
        assertEquals("new steering", controller.pollSteeringMessage())
    }

    @Test fun ordinaryPauseHoldsTheRetryFrameUntilResumeAndPreservesTheDraft() {
        val clock = Clock()
        val control = AgentRunController()
        val events = java.util.concurrent.CopyOnWriteArrayList<AgentEvent>()
        val paused = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val result = java.util.concurrent.atomic.AtomicReference<AgentModelRetry.Result>()
        val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val thread = Thread {
            try {
                result.set(run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, emit ->
                    if (calls.incrementAndGet() == 1) {
                        emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "preserved draft"))
                        throw IOException()
                    }
                    ok(" fresh")
                }, events, control, wait = { controller, _ ->
                    clock.advance(10_000)
                    controller.pause()
                    paused.countDown()
                }))
            } catch (failure: Throwable) { error.set(failure) }
            finally { done.countDown() }
        }
        thread.start()
        try {
            assertTrue(paused.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val count = events.size
            clock.advance(120_000)
            assertEquals(count, events.size)
            assertEquals(1, calls.get())
            assertEquals(1L, done.count)
            assertTrue(control.isPaused)
            control.resume()
            assertTrue(done.await(2, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(error.get())
            assertEquals(2, calls.get())
            assertEquals("preserved draft fresh", result.get().response.assistantMessage.getString("content"))
            assertEquals(10_000L, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().elapsedMs)
        } finally {
            control.cancel()
            thread.join(2_000)
        }
    }

    @Test fun pauseDuringHostedRecoveryStopsProgressWithoutSpendingTheWindow() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val result = run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, _ ->
            if (calls++ == 0) throw IOException()
            ok()
        }, events, hosted = true, wait = { control, delay ->
            control.pause()
            assertEquals("stopped", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
            val count = events.size
            clock.advance(120_000)
            assertEquals(count, events.size)
            assertEquals(1, calls)
            control.resume()
            clock.advance(delay)
        })
        assertEquals(2, calls)
        assertEquals("done", result.response.assistantMessage.getString("content"))
        val changes = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals(2, changes.map { it.reconnectId }.distinct().size)
        assertEquals("succeeded", changes.last().status)
        assertEquals(2_000L, changes.last().elapsedMs)
    }

    @Test fun fastResumeDiscardsPausedAttemptAndKeepsRecoveryBudget() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val providerEvents = mutableListOf<ProviderEvent>()
        var calls = 0
        val result = run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, control, emit ->
            when (++calls) {
                1 -> throw IOException()
                2 -> {
                    val binding = control.register(interruptible = true) {}
                    try {
                        control.pause()
                        clock.advance(120_000)
                        control.resume()
                        emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "late"))
                        ok("late")
                    } finally { binding.close() }
                }
                else -> ok("fresh")
            }
        }, events, providerEvents = providerEvents)
        assertEquals(3, calls)
        assertEquals("fresh", result.response.assistantMessage.getString("content"))
        assertTrue(providerEvents.none { it is ProviderEvent.BlockDelta && it.delta == "late" })
        val changes = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals("succeeded", changes.last().status)
        assertEquals(6_000L, changes.last().elapsedMs)
        assertEquals(2, changes.map { it.reconnectId }.distinct().size)
    }

    @Test fun pausedAttemptWithoutRegisteredHttpResourceCannotReturnLateToolCalls() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val result = run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, control, _ ->
            when (++calls) {
                1 -> throw IOException()
                2 -> {
                    // A provider may release its HTTP resource before returning its parsed response.
                    control.pause()
                    assertFalse(control.hasPausedInterrupt)
                    clock.advance(120_000)
                    control.resume()
                    ProviderResponse(JSONObject().put("role", "assistant").put("content", "late")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                            .put("id", "late-call").put("type", "function").put("function", JSONObject()
                                .put("name", "supervise_task").put("arguments", "{}")))))
                }
                else -> ok("fresh")
            }
        }, events, tools = localTools())
        assertEquals(3, calls)
        assertEquals("fresh", result.response.assistantMessage.getString("content"))
        assertEquals(0, result.response.assistantMessage.optJSONArray("tool_calls")?.length() ?: 0)
        assertEquals(6_000L, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().elapsedMs)
    }

    @Test fun ordinaryPauseDuringRetryKeepsRemainingWindowInsteadOfStartingOver() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        var waits = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, _ ->
                calls++
                throw IOException()
            }, events, wait = { control, delay ->
                if (++waits == 1) {
                    clock.advance(20_000)
                    val waitBinding = control.register(interruptible = true, marksPausedInterrupt = false) {}
                    control.pause()
                    assertFalse(control.hasPausedInterrupt)
                    val count = events.size
                    clock.advance(120_000)
                    assertEquals(count, events.size)
                    control.resume()
                    waitBinding.close()
                }
                clock.advance(delay)
            })
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertEquals(3, calls)
        val changes = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals(2, changes.map { it.reconnectId }.distinct().size)
        assertEquals(30_000L, changes.last().elapsedMs)
    }

    @Test fun rebuildingRecoveryHistoryPreservesTheGuardedEnvelopeCorrection() {
        var calls = 0
        val result = run(Clock(), ErrorReconnectPolicy.WINDOW_30S,
            provider(EndpointKind.RESPONSES) { request, _, _ ->
                if (calls++ == 0) throw AgentModelFailure(ResponsesToolEnvelopeRecovery.CODE, false,
                    "rejected JSON envelope", envelopeCorrectionAllowed = true)
                assertTrue(request.singleToolCall)
                assertTrue(request.reconnectLocalToolsOnly)
                assertEquals(1, request.tools.length())
                val hints = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .filter { it.optString("role") == "developer" }
                assertEquals(1, hints.size)
                assertEquals(ResponsesToolEnvelopeRecovery.CORRECTION, hints.single().getString("content"))
                ok()
            }, tools = localTools())
        assertEquals(2, calls)
        assertEquals("done", result.response.assistantMessage.getString("content"))
    }

    @Test fun originalThreeRetryBackoffRepeatsInsideOneFixedWindow() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, _ -> throw IOException() }, events)
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        val retries = events.filterIsInstance<AgentEvent.ModelRetryScheduled>()
        assertEquals(listOf(1, 2, 3, 1, 2, 3, 1), retries.map { it.attempt })
        assertEquals(listOf(2_000, 4_000, 8_000, 2_000, 4_000, 8_000, 2_000), retries.map { it.delayMs })
        assertTrue(retries.all { it.maxAttempts == 3 })
        assertEquals(30_000L, clock.now)
        val markers = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals(1, markers.map { it.reconnectId }.distinct().size)
        assertEquals(1, markers.count { it.status == "failed" })
    }

    @Test fun generatedOutputEndsMarkerBeforeLongResponseCompletesForEveryEnabledPolicy() {
        for (policy in listOf(ErrorReconnectPolicy.WINDOW_30S, ErrorReconnectPolicy.WINDOW_1M,
            ErrorReconnectPolicy.WINDOW_5M, ErrorReconnectPolicy.CONTINUOUS)) {
            for (kind in listOf(AssistantBlockKind.TEXT, AssistantBlockKind.THINKING, AssistantBlockKind.TOOL_CALL)) {
                val clock = Clock()
                val events = mutableListOf<AgentEvent>()
                var calls = 0
                run(clock, policy, provider { _, _, emit ->
                    if (calls++ == 0) throw IOException()
                    emit(ProviderEvent.RequestStarted)
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10)))
                    emit(ProviderEvent.BlockDelta(kind, 0, ""))
                    assertTrue(events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().none { it.status == "succeeded" })
                    emit(ProviderEvent.BlockDelta(kind, 0, "live output"))
                    val marker = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last()
                    assertEquals("succeeded", marker.status)
                    assertEquals(2_000L, marker.elapsedMs)
                    val size = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().size
                    clock.advance(360_000)
                    assertEquals(size, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().size)
                    ok()
                }, events)
                assertEquals(1, events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().count { it.status == "succeeded" })
            }
        }
    }

    @Test fun aNewDisconnectAfterRecoveredOutputStartsANewWindowAndKeepsLocalTools() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { request, _, emit ->
            when (calls++) {
                0 -> throw IOException()
                1 -> {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "first connection restored"))
                    clock.advance(35_000)
                    throw IOException()
                }
                else -> {
                    assertTrue(request.reconnectLocalToolsOnly)
                    assertFalse(request.reconnectTextOnly)
                    assertEquals(1, request.tools.length())
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, " next connection restored"))
                    ok(" next connection restored")
                }
            }
        }, events, tools = localTools())
        val markers = events.filterIsInstance<AgentEvent.ErrorReconnectChanged>()
        assertEquals(2, markers.map { it.reconnectId }.distinct().size)
        assertEquals(listOf(2_000L, 2_000L), markers.filter { it.status == "succeeded" }.map { it.elapsedMs })
        assertTrue(markers.none { it.status == "failed" })
    }

    @Test fun cancellationErrorAndContextMaintenanceAreNotNetworkRetryLoops() {
        val error = AssertionError("provider bug")
        assertSame(error, assertThrows(AssertionError::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> throw error })
        })
        assertThrows(CancellationException::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ -> throw CancellationException() })
        }
        val events = mutableListOf<AgentEvent>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(Clock(), ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
                throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "maintain") }, events)
        }
        assertEquals("CONTEXT_WINDOW_EXCEEDED", failure.code)
        assertTrue(events.none { it is AgentEvent.ErrorReconnectChanged })
    }

    @Test fun permanentApiKeyFailureResendsOnceThenSurfacesUnderContinuousPolicy() {
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
                calls++
                throw AgentModelFailure.http(401, "")
            }, events)
        }
        // 之前它会被 2s→4s→8s 无限重发直到用户点停止；现在只允许一次有界重发。
        assertEquals(2, calls)
        assertEquals("HTTP_401", failure.code)
        val retries = events.filterIsInstance<AgentEvent.ModelRetryScheduled>()
        assertEquals(1, retries.size)
        assertTrue("界面要能识别这不是网络抖动", retries.single().permanent)
        assertEquals(1, retries.single().maxAttempts)
        assertEquals(2_000, retries.single().delayMs)
        assertEquals("failed", events.filterIsInstance<AgentEvent.ErrorReconnectChanged>().last().status)
    }

    @Test fun quotaFailureIsPermanentAndNotDisguisedAsNetworkInstability() {
        for (status in listOf(403, 404)) {
            val clock = Clock()
            var calls = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
                    calls++; throw AgentModelFailure.http(status, "")
                })
            }
            assertEquals(2, calls)
            assertEquals("HTTP_$status", failure.code)
        }
        val quota = AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""")
        assertEquals(AgentModelFailure.RetryMode.PERMANENT, quota.retryMode)
        assertEquals(AgentModelFailure.PermanentReason.QUOTA, quota.permanentReason)
    }

    @Test fun oauthAuthFailureKeepsUsingTheWholeReconnectWindow() {
        // 每次尝试都重新解析 OAuth 凭据：用户在窗口内重新登录就能恢复，不能提前放弃。
        val clock = Clock()
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            run(clock, ErrorReconnectPolicy.WINDOW_30S, provider { _, _, _ ->
                calls++; throw AgentModelFailure.http(401, "")
            }, authMode = ProviderAuthModes.OPENAI_CHATGPT)
        }
        assertEquals("ERROR_RECONNECT_DEADLINE", failure.code)
        assertTrue(calls > 2)
        assertTrue(AgentModelFailure.permanentForAuthMode(
            AgentModelFailure.PermanentReason.AUTH, ProviderAuthModes.OPENAI_CHATGPT).not())
    }

    @Test fun transientRateLimitStillUsesUnboundedReconnectWindow() {
        val clock = Clock()
        var calls = 0
        // 暂时性限流不受永久错误的单次重发约束：连续 10 次 429 后仍继续重连直到成功。
        val result = run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
            calls++
            if (calls <= 10) throw AgentModelFailure.http(429, "")
            ok()
        })
        assertEquals(11, calls)
        assertEquals("done", result.response.assistantMessage.getString("content"))
        assertEquals(AgentModelFailure.RetryMode.TRANSIENT,
            AgentModelFailure.http(429, "").retryMode)
    }

    @Test fun retryAfterRaisesTheBackoffAndJitterOnlyAdds() {
        val headers = okhttp3.Headers.Builder().add("Retry-After", "45").build()
        val hinted = AgentModelFailure.http(429, "", headers)
        assertEquals(45L, hinted.retryAfterSec)
        val clock = Clock()
        val events = mutableListOf<AgentEvent>()
        var calls = 0
        run(clock, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
            if (calls++ == 0) throw hinted
            ok()
        }, events)
        val retry = events.filterIsInstance<AgentEvent.ModelRetryScheduled>().single()
        // 不能比提供方要求的时间更早重发，否则只是继续撞限流。
        assertEquals(45_000, retry.delayMs)

        // 抖动只放宽本地基线：0.2 比例、源取 0.5 → 2s 基线变成 2.2s。
        val jittered = Clock()
        val jitterEvents = mutableListOf<AgentEvent>()
        var jitterCalls = 0
        run(jittered, ErrorReconnectPolicy.CONTINUOUS, provider { _, _, _ ->
            if (jitterCalls++ == 0) throw AgentModelFailure.http(500, "")
            ok()
        }, jitterEvents, jitter = 0.2)
        assertEquals(2_200, jitterEvents.filterIsInstance<AgentEvent.ModelRetryScheduled>().single().delayMs)
        assertEquals(2_200L, jittered.now)
    }

    @Test fun retryAfterAcceptsHttpDateAndGoogleRetryInfoOnlyWhenInTheFuture() {
        // 语义统一为「要等多少秒」：0 表示没有额外约束，退回常规退避；无法解析才是 null。
        assertEquals(30L, AgentModelFailure.parseRetryAfter("30"))
        assertEquals(30L, AgentModelFailure.parseRetryAfter("  seconds=30 "))
        assertEquals(0L, AgentModelFailure.parseRetryAfter("0"))
        assertNull(AgentModelFailure.parseRetryAfter("not-a-date"))
        assertNull(AgentModelFailure.parseRetryAfter(null))
        assertEquals("已过期的 HTTP-date 不再构成等待", 0L,
            AgentModelFailure.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals("本机时钟与上游有偏差时也不产生负等待", 0L, AgentModelFailure.parseRetryAfter(
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.OffsetDateTime.now().minusSeconds(60))))
        val future = AgentModelFailure.parseRetryAfter(
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.OffsetDateTime.now().plusSeconds(20)))
        assertTrue("$future", future!! in 15..25)
        // 退避下限：0 或 null 都不能把等待压到基线以下。
        assertEquals(2_000L, ModelRetryBackoff.delayMs(1, 0L, null, 0.0) { 0.0 })
        assertEquals(2_000L, ModelRetryBackoff.delayMs(1, null, null, 0.0) { 0.0 })
        assertEquals(45_000L, ModelRetryBackoff.delayMs(1, 45L, null, 0.0) { 0.0 })
        // 窗口剩余时间是上限，不能让单次等待跨过边界而错过终态。
        assertEquals(1_500L, ModelRetryBackoff.delayMs(3, null, 1_500L, 0.0) { 0.0 })
        assertEquals(8_800L, ModelRetryBackoff.delayMs(3, null, 60_000L, 0.2) { 0.5 })
        // Retry-After 是下限，但重连窗口的剩余时间仍是上限，不能让单次等待跨过终态。
        assertEquals(30_000L, ModelRetryBackoff.delayMs(1, 45L, 30_000L, 0.0) { 0.0 })
        // 异常的头值不能卡死整个窗口。
        assertEquals(120_000L, ModelRetryBackoff.delayMs(1, 86_400L, null, 0.0) { 0.0 })
        val google = AgentModelFailure.http(429, JSONObject().put("error", JSONObject()
            .put("code", 429).put("details", JSONArray()
                .put(JSONObject().put("@type", "type.googleapis.com/google.rpc.RetryInfo")
                    .put("retryDelay", "12s")))).toString())
        assertEquals(12L, google.retryAfterSec)
    }
}
