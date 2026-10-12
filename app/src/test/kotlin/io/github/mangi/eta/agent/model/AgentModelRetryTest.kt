package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun unsupportedCodexTierNeverReconnectsEvenUnderContinuousPolicy() {
        for (policy in ErrorReconnectPolicy.entries) {
            var calls = 0
            val delivered = mutableListOf<ProviderEvent>()
            val failure = assertThrows(AgentModelFailure::class.java) {
                complete(AgentModelRetry { _, _ -> fail("local configuration must not retry") },
                    provider { _, _ ->
                        calls++
                        ResponsesRequestBuilder.build(AgentModelClient.ModelConfig(
                            baseUrl = "https://chatgpt.com/backend-api/codex", apiKey = "", model = "gpt-5",
                            systemPrompt = "", gptSpeedMode = io.github.mangi.eta.data.model.GptSpeedMode.ULTRA_FAST,
                            // Codex 档位约束按认证方式判定。
                            authMode = io.github.mangi.eta.data.model.ProviderAuthModes.OPENAI_CHATGPT,
                        ), JSONArray(), JSONArray())
                        fail("unsupported tier must fail before a request can be sent")
                        response()
                    }, onProviderEvent = { _, event -> delivered += event }, policy = policy)
            }
            assertEquals(GptServiceTier.UNSUPPORTED_CODEX_TIER, failure.code)
            assertFalse(failure.retryable)
            assertEquals(1, calls)
            assertTrue(delivered.isEmpty())
        }
    }

    @Test
    fun safeRequestRetryUsesPolicyDelayAndDistinctModelRound() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry(jitterFraction = 0.0) { _, delay -> delays += delay }
        var calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, calls)
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresDoNotReplayAndHostedFailuresUseLocalOnlyRecovery() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)
        var calls = 0
        val recovered = complete(AgentModelRetry { _, _ -> }, provider { request, emit ->
            if (calls++ == 0) {
                emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                throw SocketTimeoutException()
            }
            assertFalse(request.reconnectTextOnly)
            assertTrue(request.reconnectLocalToolsOnly)
            assertEquals(0, request.tools.length())
            response()
        })
        assertEquals(2, calls)
        assertEquals(2, recovered.round)
    }

    @Test
    fun lateUsageFromSupersededAttemptIsDroppedAfterRetry() {
        val retry = AgentModelRetry { _, _ -> }
        val delivered = mutableListOf<ProviderEvent>()
        val attemptCallbacks = mutableListOf<(ProviderEvent) -> Unit>()
        var calls = 0
        val result = complete(
            retry,
            provider { _, emit ->
                attemptCallbacks += emit
                if (calls++ == 0) throw IOException("connection reset")
                emit(ProviderEvent.RequestStarted)
                attemptCallbacks.first()(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 20)))
                response()
            },
            onProviderEvent = { _, event -> delivered += event },
        )
        assertEquals(2, result.round)
        assertEquals(listOf<ProviderEvent>(ProviderEvent.RequestStarted, ProviderEvent.Usage(AgentTokenUsage(inputTokens = 20))), delivered.toList())
        // 被取代的第一次尝试的闭包在重试之后不得再触达消费者，否则会覆盖新请求的 usage。
        attemptCallbacks.first()(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 999)))
        assertEquals(listOf<ProviderEvent>(ProviderEvent.RequestStarted, ProviderEvent.Usage(AgentTokenUsage(inputTokens = 20))), delivered.toList())
    }

    @Test
    fun lateUsageKeepsProviderAccountingButIsNotRedelivered() {
        val recordedInputs = mutableListOf<Long>()
        val delivered = mutableListOf<ProviderEvent>()
        val attemptCallbacks = mutableListOf<(ProviderEvent) -> Unit>()
        var calls = 0
        val delegate = provider { _, emit ->
            attemptCallbacks += emit
            if (calls++ == 0) throw IOException("connection reset")
            response()
        }
        val recordingProvider = UsageRecordingProvider(delegate) { delta -> recordedInputs += delta.inputTokens }
        complete(
            AgentModelRetry { _, _ -> },
            recordingProvider,
            onProviderEvent = { _, event -> delivered += event },
        )
        assertTrue(recordedInputs.isEmpty())
        // 迟到事件即使被投递闸门丢弃，仍要经过 UsageRecordingProvider 的独立账务。
        attemptCallbacks.first()(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 7)))
        assertEquals(listOf(7L), recordedInputs)
        assertTrue(delivered.isEmpty())
    }

    @Test
    fun classifiesTransientFailuresWithoutRetryingPermanentFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 529)) {
            assertTrue(AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 403, 404)) {
            assertFalse(AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertEquals("MODEL_CONNECTION_FAILED", AgentModelFailure.transport(SSLHandshakeException("certificate"))?.code)
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
        assertTrue(
            AgentModelFailure.http(
                400,
                """{"error":{"message":"The `reasoning_content` in the thinking mode must be passed back to the API."}}""",
            ).message.orEmpty().contains("reasoning_content"),
        )
        assertEquals(
            "模型请求参数无效（HTTP 400），请检查模型配置。",
            AgentModelFailure.http(400, "").message,
        )
    }

    @Test fun callbackFailureWinsEvenIfProviderReturnsNormally() {
        val original = IllegalStateException("consumer failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            complete(AgentModelRetry { _, _ -> }, provider { _, emit ->
                try { emit(ProviderEvent.RequestStarted) } catch (_: IllegalStateException) { }
                response()
            }, onProviderEvent = { _, _ -> throw original })
        }
        assertSame(original, thrown)
    }

    @Test fun repetitiveReasoningStopsWithoutRetryWhenPolicyIsNone() {
        var calls = 0
        var delivered = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(AgentModelRetry { _, _ -> fail("must not replay degenerate request") }, provider { _, emit ->
                calls++
                repeat(2000) { index ->
                    // Changing block IDs must not reset the per-request detector.
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, index, "OK.\nWrite.\nNow.\nWriting.\nDONE.\n"))
                }
                response()
            }, onProviderEvent = { _, event -> if (event is ProviderEvent.BlockDelta) delivered++ },
                policy = ErrorReconnectPolicy.NONE)
        }
        assertEquals("MODEL_REPETITIVE_REASONING", failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, calls)
        assertTrue(delivered in 1..300)
    }

    @Test fun repetitiveReasoningFailureWinsForNoneEvenIfProviderSwallowsIt() {
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(AgentModelRetry { _, _ -> fail("must not retry") }, provider { _, emit ->
                calls++
                try {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "Write.\n".repeat(2000)))
                } catch (_: AgentModelFailure) { }
                response()
            }, policy = ErrorReconnectPolicy.NONE)
        }
        assertEquals("MODEL_REPETITIVE_REASONING", failure.code)
        assertEquals(1, calls)
    }

    @Test fun repetitiveReasoningRecoversUnderContinuousPolicyEvenWhenProviderSwallowsIt() {
        var calls = 0
        val result = complete(AgentModelRetry { _, _ -> }, provider { request, emit ->
            if (calls++ == 0) {
                try {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "Write.\n".repeat(2000)))
                } catch (_: AgentModelFailure) { }
            } else {
                assertFalse(request.reconnectTextOnly)
                assertTrue(request.reconnectLocalToolsOnly)
            }
            response()
        })
        assertEquals(2, calls)
        assertEquals(2, result.round)
    }

    @Test fun continuationFlushConsumerFailureIsNotRetriedAsTransportFailure() {
        val failure = IOException("consumer flush failed")
        var calls = 0
        var waits = 0
        val thrown = assertThrows(IOException::class.java) {
            complete(AgentModelRetry { _, _ -> waits++ }, provider { _, emit ->
                if (calls++ == 0) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "Hello world"))
                    throw IOException("disconnect")
                }
                // Ambiguous overlap remains buffered until successful-response flush.
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "Hello"))
                response()
            }, onProviderEvent = { round, event ->
                if (round == 2 && event is ProviderEvent.BlockDelta) throw failure
            })
        }
        assertSame(failure, thrown)
        assertEquals(2, calls)
        assertEquals(1, waits)
    }

    @Test fun repetitiveAnswerTextStopsWithoutRetryWhenPolicyIsNone() {
        var calls = 0
        var delivered = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(AgentModelRetry { _, _ -> fail("must not replay degenerate answer") }, provider { _, emit ->
                calls++
                repeat(400) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "我先看当前步骤。\n我先确认测试进度。\n我先看构建状态。\n"))
                }
                response()
            }, onProviderEvent = { _, event -> if (event is ProviderEvent.BlockDelta) delivered++ },
                policy = ErrorReconnectPolicy.NONE)
        }
        assertEquals("MODEL_REPETITIVE_REASONING", failure.code)
        assertFalse(failure.retryable)
        assertEquals(1, calls)
        assertTrue(delivered in 1..250)
    }

    @Test fun repetitiveAnswerTextRecoversUnderContinuousPolicy() {
        var calls = 0
        val result = complete(AgentModelRetry { _, _ -> }, provider { request, emit ->
            if (calls++ == 0) {
                try {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "我先看当前步骤。\n".repeat(2000)))
                } catch (_: AgentModelFailure) { }
            } else {
                assertFalse(request.reconnectTextOnly)
                assertTrue(request.reconnectLocalToolsOnly)
            }
            response()
        })
        assertEquals(2, calls)
        assertEquals(2, result.round)
    }

    @Test fun variedAnswerTextIsNotRepetition() {
        complete(AgentModelRetry { _, _ -> fail("must not retry") }, provider { _, emit ->
            repeat(200) { index ->
                emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "步骤 $index 使用新证据 ${index * 17}。\n"))
            }
            response()
        }, policy = ErrorReconnectPolicy.NONE)
    }

    @Test fun toolProgressClearsAnswerRepetitionWindow() {
        complete(AgentModelRetry { _, _ -> fail("must not retry") }, provider { _, emit ->
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "OK.\n".repeat(800)))
            emit(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, 1))
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "OK.\n".repeat(800)))
            response()
        }, policy = ErrorReconnectPolicy.NONE)
    }

    @Test fun toolArgumentsAreNotAnswerRepetitions() {
        complete(AgentModelRetry { _, _ -> fail("must not retry") }, provider { _, emit ->
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TOOL_CALL, 1, "0,".repeat(5000)))
            response()
        }, policy = ErrorReconnectPolicy.NONE)
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
        policy: ErrorReconnectPolicy = ErrorReconnectPolicy.CONTINUOUS,
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = "", errorReconnectPolicy = policy.persistedValue),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = {},
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}
