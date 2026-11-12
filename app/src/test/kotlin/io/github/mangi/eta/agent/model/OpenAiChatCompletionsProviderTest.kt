package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ProviderAuthModes
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatCompletionsProviderTest {

    @Test
    fun promptCacheKeyIsOnlySentToOfficialOpenAi() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好"))
        fun request(
            baseUrl: String,
            authMode: String = ProviderAuthModes.API_KEY,
            sessionId: String = "conv-1",
        ) = OpenAiChatCompletionsProvider.buildRequestJson(
            config = AgentModelClient.ModelConfig(
                providerSourceType = "custom",
                baseUrl = baseUrl,
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                authMode = authMode,
            ),
            messages = messages,
            tools = JSONArray(),
            sessionId = sessionId,
        )
        assertEquals("conv-1", request("https://api.openai.com/v1").getString("prompt_cache_key"))
        assertEquals(
            "conv-1",
            request("https://gateway.example/v1", ProviderAuthModes.OPENAI_CHATGPT).getString("prompt_cache_key"),
        )
        // 第三方网关对未知字段可能直接 400；DeepSeek 等端点的前缀缓存本身不需要分片键。
        assertFalse(request("https://api.deepseek.com/v1").has("prompt_cache_key"))
        assertFalse(request("https://api.openai.com/v1", sessionId = "").has("prompt_cache_key"))
    }

    @Test
    fun steeringDiscardsUnfinishedToolBatchEvenWithParseableArguments() {
        for (arguments in listOf("{\"command\":", "{}")) {
            val controller = AgentRunController()
            val body = sseChunk(JSONObject().put("content", "before")) +
                sseChunk(JSONObject().put("tool_calls", JSONArray().put(
                    JSONObject().put("index", 0).put("id", "draft").put("type", "function")
                        .put("function", JSONObject().put("name", "terminal").put("arguments", arguments)))))
            withSseServer(body) { baseUrl ->
                val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), controller) { event ->
                    if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL) {
                        controller.steer("new instruction")
                    }
                }
                assertEquals(AssistantStopReason.INTERRUPTED, response.stopReason)
                assertEquals("before", response.assistantMessage.getString("content"))
                assertTrue(!response.assistantMessage.has("tool_calls"))
                assertTrue(controller.hasPendingSteering)
            }
        }
    }

    @Test
    fun pauseThenSteerAlsoDiscardsUnfinishedArguments() {
        val controller = AgentRunController()
        val body = sseChunk(JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", 0).put("id", "draft").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))))
        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), controller) { event ->
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL) {
                    controller.pause()
                    controller.steer("new instruction")
                }
            }
            assertEquals(AssistantStopReason.INTERRUPTED, response.stopReason)
            assertTrue(!response.assistantMessage.has("tool_calls"))
            assertTrue(controller.hasPausedInterrupt)
            assertTrue(controller.hasPendingSteering)
        }
    }

    @Test
    fun explicitToolFinishRemainsAuthoritativeEvenWhenSteeringArrives() {
        // Malformed arguments in a genuinely completed response must still reach normal validation.
        val controller = AgentRunController()
        val body = sseChunk(JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", 0).put("id", "complete").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{")))),
            finishReason = "tool_calls")
        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), controller) { event ->
                if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL) {
                    controller.steer("new instruction")
                }
            }
            assertEquals(AssistantStopReason.TOOL_USE, response.stopReason)
            assertEquals("{", response.assistantMessage.getJSONArray("tool_calls")
                .getJSONObject(0).getJSONObject("function").getString("arguments"))
        }
    }

    @Test
    fun eofWithParseableToolArgumentsButNoFinishIsNotAnExecutableBatch() {
        val body = sseChunk(JSONObject().put("tool_calls", JSONArray().put(
            JSONObject().put("index", 0).put("id", "draft").put("type", "function")
                .put("function", JSONObject().put("name", "terminal").put("arguments", "{}")))))
        withSseServer(body) { baseUrl ->
            org.junit.Assert.assertThrows(AgentModelFailure::class.java) {
                OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), AgentRunController())
            }
        }
    }

    @Test
    fun completeParsesTextDeltasWithDoneSentinel() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "Hel")))
            append(sseChunk(JSONObject().put("content", "lo"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("Hello", response.assistantMessage.getString("content"))
            assertEquals(
                "Hello",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.TEXT }
                    .joinToString("") { it.delta }
            )
        }
    }

    @Test
    fun completeSplitsVisibleBlocksWhenDeltaTypeChanges() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "先分析")))
            append(sseChunk(JSONObject().put("content", "先说明")))
            append(sseChunk(JSONObject().put("reasoning_content", "再确认")))
            append(sseChunk(JSONObject().put("content", "最终回答"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add,
            )

            assertEquals(
                listOf(
                    "start:THINKING:0",
                    "delta:THINKING:0:先分析",
                    "end:THINKING:0",
                    "start:TEXT:1",
                    "delta:TEXT:1:先说明",
                    "end:TEXT:1",
                    "start:THINKING:2",
                    "delta:THINKING:2:再确认",
                    "end:THINKING:2",
                    "start:TEXT:3",
                    "delta:TEXT:3:最终回答",
                    "end:TEXT:3",
                ),
                events.mapNotNull { event ->
                    when (event) {
                        is ProviderEvent.BlockStart -> "start:${event.kind}:${event.index}"
                        is ProviderEvent.BlockDelta -> "delta:${event.kind}:${event.index}:${event.delta}"
                        is ProviderEvent.BlockEnd -> "end:${event.kind}:${event.index}"
                        else -> null
                    }
                },
            )
        }
    }

    @Test
    fun completeAcceptsFinishReasonWhenServerClosesWithoutDone() {
        val usage = JSONObject()
            .put("prompt_tokens", 10)
            .put("completion_tokens", 2)
            .put("total_tokens", 12)
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "完成")))
            append(sseChunk(null, finishReason = "stop"))
            append(usageChunk(usage))
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("完成", response.assistantMessage.getString("content"))
            assertEquals(
                12,
                events.filterIsInstance<ProviderEvent.Usage>().single().usage.contextTokens
            )
        }
    }

    @Test
    fun completeRejectsOpenRouterMidStreamError() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "部分内容")))
            append(
                sseErrorChunk(
                    code = 502,
                    message = "Provider disconnected unexpectedly",
                    errorType = "provider_unavailable",
                )
            )
        }

        withSseServer(body) { baseUrl ->
            val thrown = runCatching {
                OpenAiChatCompletionsProvider.complete(
                    request = providerRequest(baseUrl) {
                        it.copy(providerSourceType = "openrouter")
                    },
                    runController = AgentRunController()
                )
            }.exceptionOrNull()

            assertNotNull(thrown)
            assertTrue(thrown is IllegalStateException)
            assertTrue(thrown?.message.orEmpty().contains("Provider disconnected unexpectedly"))
            assertTrue(thrown?.message.orEmpty().contains("provider_unavailable"))
        }
    }

    @Test
    fun completeTreatsTruncatedStreamWithTextAsNaturalStop() {
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "项目介绍已经写完。")))
        }

        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
            )

            assertEquals("项目介绍已经写完。", response.assistantMessage.getString("content"))
            assertEquals("stop", response.assistantMessage.getString("finish_reason"))
        }
    }

    @Test
    fun completeDoesNotRequestDeprecatedOpenRouterUsageOption() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(": OPENROUTER PROCESSING\n\n")
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(providerSourceType = "openrouter")
                },
                runController = AgentRunController(),
            )

            assertTrue(!JSONObject(requestBody.get()).has("stream_options"))
        }
    }

    @Test
    fun completeMapsHtmlPageToClassifiedFailure() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            val bytes = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val thrown = runCatching {
                OpenAiChatCompletionsProvider.complete(
                    request = providerRequest("http://127.0.0.1:${server.address.port}"),
                    runController = AgentRunController(),
                )
            }.exceptionOrNull()
            assertTrue(thrown is AgentModelFailure)
            assertTrue(thrown?.message.orEmpty().contains("网页"))
            assertTrue(!thrown?.message.orEmpty().startsWith("Invalid content-type"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun requestMergesSystemMessagesAtTheBeginningForStrictChatTemplates() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = requestBody::set) { baseUrl ->
            val request = providerRequest(baseUrl).copy(
                messages = JSONArray()
                    .put(JSONObject().put("role", "system").put("content", "基础约束"))
                    .put(JSONObject().put("role", "user").put("content", "旧问题"))
                    .put(JSONObject().put("role", "system").put("content", "动态上下文"))
                    .put(JSONObject().put("role", "assistant").put("content", "旧回答"))
                    .put(JSONObject().put("role", "user").put("content", "当前问题")),
            )

            OpenAiChatCompletionsProvider.complete(request, AgentRunController())
        }

        val sent = JSONObject(requestBody.get()).getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), sent.roles())
        assertEquals("基础约束\n\n动态上下文", sent.getJSONObject(0).getString("content"))
    }

    @Test
    fun completeParsesReasoningAliasAndFinalMessageSnapshot() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning", "先确认目录。")))
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "list_directory")
                                        .put("arguments", "{}")
                                )
                        )
                    ),
                    finishReason = "tool_calls",
                    message = JSONObject()
                        .put("role", "assistant")
                        .put("content", "")
                        .put("reasoning_content", "先确认目录。")
                )
            )
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
            )
            assertEquals("先确认目录。", response.assistantMessage.getString("reasoning_content"))
            assertEquals(
                "call_1",
                response.assistantMessage.getJSONArray("tool_calls").getJSONObject(0).getString("id"),
            )
        }
    }

    @Test
    fun requestReplaysAssistantReasoningWhenSendingToolResults() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = requestBody::set) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl).copy(
                    messages = JSONArray()
                        .put(JSONObject().put("role", "user").put("content", "列出目录"))
                        .put(
                            JSONObject()
                                .put("role", "assistant")
                                .put("content", "I will read it.")
                                .put("reasoning_content", "需要先列目录")
                                .put(
                                    "tool_calls",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("id", "call_00_test")
                                            .put("type", "function")
                                            .put(
                                                "function",
                                                JSONObject()
                                                    .put("name", "list_directory")
                                                    .put("arguments", "{\"path\":\"/tmp\"}"),
                                            ),
                                    ),
                                ),
                        )
                        .put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", "call_00_test")
                                .put("content", "{\"ok\":true}"),
                        ),
                    tools = JSONArray().put(JSONObject().put("type", "function")),
                ),
                runController = AgentRunController(),
            )
        }

        val sent = JSONObject(requestBody.get()).getJSONArray("messages")
        val assistant = (0 until sent.length())
            .map { sent.getJSONObject(it) }
            .first { it.optString("role") == "assistant" }
        assertEquals("需要先列目录", assistant.getString("reasoning_content"))
        assertTrue(assistant.has("tool_calls"))
    }

    @Test
    fun completeAccumulatesChunkedToolCalls() {
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "需要调用工具。")))
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "term")
                                        .put("arguments", "{\"a\"")
                                )
                        )
                    )
                )
            )
            append(
                sseChunk(
                    JSONObject().put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "inal")
                                        .put("arguments", ":1}")
                                )
                        )
                    ),
                    finishReason = "tool_calls"
                )
            )
            append("data: [DONE]\n\n")
        }

        withSseServer(body) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController(),
                onEvent = events::add
            )

            val toolCall = response.assistantMessage
                .getJSONArray("tool_calls")
                .getJSONObject(0)
            assertEquals("call_1", toolCall.getString("id"))
            assertEquals("terminal", toolCall.getJSONObject("function").getString("name"))
            assertEquals("{\"a\":1}", toolCall.getJSONObject("function").getString("arguments"))
            assertEquals("需要调用工具。", response.assistantMessage.getString("reasoning_content"))
            assertEquals(
                "需要调用工具。",
                events.filterIsInstance<ProviderEvent.BlockDelta>()
                    .filter { it.kind == AssistantBlockKind.THINKING }
                    .joinToString("") { it.delta }
            )
            assertEquals(2, events.filterIsInstance<ProviderEvent.BlockDelta>().count { it.kind == AssistantBlockKind.TOOL_CALL })
        }
    }

    @Test
    fun completeParsesReasoningUsageAndMergesExtraBody() {
        val usage = JSONObject()
            .put("prompt_tokens", 10)
            .put("completion_tokens", 8)
            .put("total_tokens", 18)
            .put(
                "completion_tokens_details",
                JSONObject().put("reasoning_tokens", 5)
            )
            .put(
                "prompt_tokens_details",
                JSONObject().put("cached_tokens", 3).put("cache_creation_tokens", 2)
            )
        val body = buildString {
            append(sseChunk(JSONObject().put("reasoning_content", "先分析")))
            append(sseChunk(JSONObject().put("content", "结果"), finishReason = "stop"))
            append(usageChunk(usage))
            append("data: [DONE]\n\n")
        }

        val requestBody = AtomicReference<String>()
        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            val events = mutableListOf<ProviderEvent>()
            val response = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(
                    baseUrl = baseUrl,
                    configTransform = {
                        it.copy(
                            thinkingEnabled = true,
                            extraBodyJson = """{"enable_thinking":false,"thinking_budget":50}"""
                        )
                    }
                ),
                runController = AgentRunController(),
                onEvent = events::add
            )

            assertEquals("结果", response.assistantMessage.getString("content"))
            assertEquals("先分析", response.assistantMessage.getString("reasoning_content"))
            val parsedUsage = events.filterIsInstance<ProviderEvent.Usage>().single().usage
            assertEquals(18, parsedUsage.contextTokens)
            assertEquals(10, parsedUsage.inputTokens)
            assertEquals(8, parsedUsage.outputTokens)
            assertEquals(5, parsedUsage.reasoningTokens)
            assertEquals(3, parsedUsage.cachedTokens)
            assertEquals(2, parsedUsage.cacheCreationTokens)

            val request = JSONObject(requestBody.get())
            assertEquals(false, request.getBoolean("enable_thinking"))
            assertEquals(50, request.getInt("thinking_budget"))
            assertTrue(
                request.getJSONObject("stream_options").getBoolean("include_usage")
            )
        }
    }

    @Test
    fun completeRejectsStreamThatEndsBeforeDone() {
        val body = sseChunk(JSONObject().put("content", "partial"))

        withSseServer(body) { baseUrl ->
            val result = OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl),
                runController = AgentRunController()
            )
            assertEquals("partial", result.assistantMessage.optString("content"))
            assertEquals("stop", result.assistantMessage.optString("finish_reason"))
        }
    }

    @Test
    fun completeBuildsDeepSeekThinkingRequest() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(
                        providerSourceType = "deepseek",
                        model = "deepseek-v4-pro",
                        thinkingEnabled = true,
                        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.HIGH,
                    )
                },
                runController = AgentRunController(),
            )

            val request = JSONObject(requestBody.get())
            assertEquals("enabled", request.getJSONObject("thinking").getString("type"))
            assertEquals("high", request.getString("reasoning_effort"))
        }
    }

    @Test
    fun completeBuildsKimiPreservedThinkingRequest() {
        val requestBody = AtomicReference<String>()
        val body = buildString {
            append(sseChunk(JSONObject().put("content", "ok"), finishReason = "stop"))
            append("data: [DONE]\n\n")
        }

        withSseServer(body, onRequest = { requestBody.set(it) }) { baseUrl ->
            OpenAiChatCompletionsProvider.complete(
                request = providerRequest(baseUrl) {
                    it.copy(
                        providerSourceType = "moonshot",
                        model = "kimi-k2.6",
                        thinkingEnabled = true,
                    )
                },
                runController = AgentRunController(),
            )

            val thinking = JSONObject(requestBody.get()).getJSONObject("thinking")
            assertEquals("enabled", thinking.getString("type"))
            assertEquals("all", thinking.getString("keep"))
        }
    }

    @Test(timeout = 10000)
    fun identicalReasoningDeltasAreNotSwallowedAndRetryGuardStopsTheRealSse() {
        val body = sseChunk(JSONObject().put("reasoning_content", "Write.\n")).repeat(2000)
        var requests = 0
        withSseServer(body, onRequest = { requests++ }) { baseUrl ->
            val failure = org.junit.Assert.assertThrows(AgentModelFailure::class.java) {
                AgentModelRetry { _, _ -> org.junit.Assert.fail("must not replay") }.complete(
                    initialRound = 1,
                    request = providerRequest(baseUrl),
                    provider = OpenAiChatCompletionsProvider,
                    controller = AgentRunController(),
                    onEvent = {},
                    onProviderEvent = { _, _ -> },
                    discardAttemptReasoning = {},
                )
            }
            assertEquals("MODEL_REPETITIVE_REASONING", failure.code)
            assertTrue(!failure.retryable)
            assertEquals(1, requests)
        }
    }

    @Test fun shortIdenticalDeltasAppendButFinalSnapshotIsNotDuplicated() {
        val body = sseChunk(JSONObject().put("reasoning_content", "Check. ")).repeat(2) +
            sseChunk(null, finishReason = "stop", message = JSONObject()
                .put("reasoning_content", "Check. Check. ").put("content", "ok")) +
            "data: [DONE]\n\n"
        withSseServer(body) { baseUrl ->
            val response = OpenAiChatCompletionsProvider.complete(providerRequest(baseUrl), AgentRunController())
            assertEquals("Check. Check. ", response.assistantMessage.getString("reasoning_content"))
        }
    }

    private fun providerRequest(
        baseUrl: String,
        configTransform: (AgentModelClient.ModelConfig) -> AgentModelClient.ModelConfig = { it }
    ): ProviderRequest =
        ProviderRequest(
            config = configTransform(
                AgentModelClient.ModelConfig(
                    providerSourceType = "custom",
                    baseUrl = baseUrl,
                    apiKey = "test-key",
                    model = "test-model",
                    systemPrompt = "",
                    terminalTools = true
                )
            ),
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi")),
            tools = JSONArray()
        )

    private fun sseChunk(
        delta: JSONObject?,
        finishReason: String? = null,
        message: JSONObject? = null,
    ): String {
        val choice = JSONObject()
            .put("delta", delta ?: JSONObject.NULL)
            .put("finish_reason", finishReason ?: JSONObject.NULL)
        if (message != null) {
            choice.put("message", message)
        }
        return "data: ${JSONObject().put("choices", JSONArray().put(choice))}\n\n"
    }

    private fun usageChunk(usage: JSONObject): String =
        "data: ${JSONObject().put("choices", JSONArray()).put("usage", usage)}\n\n"

    private fun sseErrorChunk(
        code: Int,
        message: String,
        errorType: String,
    ): String =
        "data: ${JSONObject()
            .put("error", JSONObject()
                .put("code", code)
                .put("message", message)
                .put("metadata", JSONObject().put("error_type", errorType)))
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("delta", JSONObject().put("content", ""))
                    .put("finish_reason", "error")
            ))}\n\n"

    private fun withSseServer(
        body: String,
        onRequest: (String) -> Unit = {},
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        server.createContext("/chat/completions") { exchange ->
            onRequest(exchange.requestBody.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            })
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { output -> output.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun JSONArray.roles(): List<String> =
        (0 until length()).map { index -> getJSONObject(index).getString("role") }
}
