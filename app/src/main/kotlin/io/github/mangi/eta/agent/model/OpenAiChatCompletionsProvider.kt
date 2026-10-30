package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import java.util.UUID
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiChatCompletionsProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    override val id: String = "openai_chat_completions"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.config
        require(config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS) {
            "Responses API 已预留配置位，但当前运行时仅支持 Chat Completions"
        }
        val url = ProviderUrls.openAiChatCompletionsUrl(config.baseUrl)
        val headers = okhttp3.Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .also {
                ProviderRequestHeaders.mergeInto(
                    it, config.baseUrl, config.customHeaders, request.sessionId, config.authMode, config.model,
                )
            }
            .build()

        val requestJson = request.restrictReconnectPayload(buildRequestJson(config, request.messages, request.tools), capabilities.endpoint)
        val existingToolCallIds = ChatToolCallHistory.referencedIds(request.messages) +
            requestJson.optJSONArray("messages")?.let(ChatToolCallHistory::referencedIds).orEmpty()
        val serializedBody = requestJson.toString()
        val requestBody = serializedBody
            .toRequestBody(JSON_MEDIA_TYPE)

        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(requestBody)
            .build()

        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            AgentWireRequestEstimate.publish(requestJson, capabilities.endpoint, request, onEvent, requestBody.contentLength(), serializedBody)
            val assistantMessage = readStreamingAssistantMessage(
                httpRequest, runController, existingToolCallIds, onEvent, request.requiresCompleteStream,
                AuthorizedProviderHttp.callFactory(AgentHttpClient.modelClient, config.providerId, config.authMode, runController),
            )
            onEvent(ProviderEvent.Completed(assistantMessage.optString("finish_reason").ifBlank { null }))
            return ProviderResponse(assistantMessage)
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        }
    }

    internal fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val sourceType = ProviderSourceRegistry.resolve(
            providerId = config.providerId,
            sourceType = config.providerSourceType,
            baseUrl = config.baseUrl,
            providerType = config.providerType,
        )
        return JSONObject()
            .put("model", config.model)
            .put("stream", true)
            .put("messages", OpenAiRequestMessages.forChatCompletions(messages))
            .put("tools", tools)
            .put("tool_choice", "auto")
            .also { request ->
                if (sourceType != ProviderSourceTypes.OPENROUTER) {
                    request.put("stream_options", JSONObject().put("include_usage", true))
                }
                mergeExtraBody(request, config.extraBodyJson)
                RequestBodyMerge.mergeCustomBody(request, config.customBody)
                GptServiceTier.apply(request, config)
                request.remove("eta_media_reasoning")
                request.remove(ImageRequestParameters.CONFIG_KEY) // Local image settings never enter text protocols.
                ProviderReasoning.applyOpenAiCompatibleRequest(request, config)
                if (config.authMode == ProviderAuthModes.XAI_GROK) {
                    // 代理按请求头路由模型，正文必须与同一配置一致，并保持订阅所需的流式调用。
                    request.put("model", config.model)
                    request.put("stream", true)
                    request.put("messages", OpenAiRequestMessages.forChatCompletions(messages))
                    if (tools.length() > 0) {
                        request.put("tools", tools)
                        request.put("tool_choice", "auto")
                    } else {
                        request.remove("tools")
                        request.remove("tool_choice")
                    }
                }
                config.summaryOutputLimit?.let {
                    request.put("max_tokens", it)
                    request.remove("max_completion_tokens")
                    if (tools.length() == 0) { request.remove("tools"); request.remove("tool_choice") }
                }
            }
    }

    private fun readStreamingAssistantMessage(
        request: Request,
        runController: AgentRunController,
        existingToolCallIds: Set<String>,
        onEvent: (ProviderEvent) -> Unit,
        requireTerminal: Boolean,
        transportFactory: Call.Factory = AgentHttpClient.modelClient,
    ): JSONObject {
        val content = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = linkedMapOf<Int, StreamingToolCall>()
        var usage: AgentTokenUsage? = null
        var sawStreamData = false
        var sawDone = false
        var finishReason: String? = null
        var nextContentIndex = 0
        var activeVisibleBlock: StreamingVisibleBlock? = null

        fun finishActiveVisibleBlock() {
            val block = activeVisibleBlock ?: return
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = block.kind,
                    index = block.contentIndex,
                    content = block.content.toString(),
                )
            )
            activeVisibleBlock = null
        }

        fun appendVisibleDelta(kind: AssistantBlockKind, delta: String) {
            if (delta.isEmpty()) return
            var block = activeVisibleBlock
            if (block?.kind != kind) {
                finishActiveVisibleBlock()
                block = StreamingVisibleBlock(
                    kind = kind,
                    contentIndex = nextContentIndex++,
                ).also { created ->
                    activeVisibleBlock = created
                    onEvent(ProviderEvent.BlockStart(kind, created.contentIndex))
                }
            }
            block.content.append(delta)
            onEvent(ProviderEvent.BlockDelta(kind, block.contentIndex, delta))
        }

        fun consumeFrame(payload: String) {
                sawStreamData = true
                val chunk = JSONObject(payload)
                // Some gateways put billable usage on the same SSE frame as an error.
                // Capture it before propagating the error so a completed/failed request
                // cannot disappear from the local ledger.
                parseUsage(chunk)?.let { parsedUsage ->
                    usage = parsedUsage
                    onEvent(ProviderEvent.Usage(parsedUsage))
                }
                throwStreamingErrorIfPresent(chunk)
                val choices = chunk.optJSONArray("choices")
                if (choices == null || choices.length() == 0) return
                val choice = choices.optJSONObject(0) ?: return
                val reason = choice.optString("finish_reason")
                if (reason.isNotBlank() && reason != "null") {
                    finishReason = reason
                }
                if (reason == "error") {
                    error("模型接口 SSE 以 error 结束")
                }
                val delta = choice.optJSONObject("delta")
                val snapshot = choice.optJSONObject("message")
                if (delta == null && snapshot == null) return
                fun appendReasoning(text: String, isSnapshot: Boolean = false) {
                    if (text.isEmpty()) return
                    // A real delta is never a cumulative snapshot. Deduplicating repeated deltas
                    // hides degenerate generation from the request's repetition guard.
                    if (!isSnapshot) {
                        reasoningContent.append(text)
                        appendVisibleDelta(AssistantBlockKind.THINKING, text)
                        return
                    }
                    val already = reasoningContent.toString()
                    when {
                        already.isEmpty() -> {
                            reasoningContent.append(text)
                            appendVisibleDelta(AssistantBlockKind.THINKING, text)
                        }
                        text == already || already.startsWith(text) -> Unit
                        text.startsWith(already) -> {
                            val suffix = text.substring(already.length)
                            if (suffix.isNotEmpty()) {
                                reasoningContent.append(suffix)
                                appendVisibleDelta(AssistantBlockKind.THINKING, suffix)
                            }
                        }
                        else -> {
                            reasoningContent.append(text)
                            appendVisibleDelta(AssistantBlockKind.THINKING, text)
                        }
                    }
                }
                if (delta != null) {
                    appendReasoning(delta.optReasoningContent())
                    if (delta.has("content") && !delta.isNull("content")) {
                        val text = delta.optString("content")
                        if (text.isNotEmpty()) {
                            content.append(text)
                            appendVisibleDelta(AssistantBlockKind.TEXT, text)
                        }
                    }
                }
                if (snapshot != null) {
                    appendReasoning(snapshot.optReasoningContent(), isSnapshot = true)
                    if (content.isEmpty() && snapshot.has("content") && !snapshot.isNull("content")) {
                        val text = snapshot.optString("content")
                        if (text.isNotEmpty()) {
                            content.append(text)
                            appendVisibleDelta(AssistantBlockKind.TEXT, text)
                        }
                    }
                }
                val incomingToolCalls = delta?.optJSONArray("tool_calls")?.takeIf { it.length() > 0 }
                    ?: snapshot?.optJSONArray("tool_calls")?.takeIf { delta == null || toolCalls.isEmpty() }
                    ?: return
                if (incomingToolCalls.length() > 0) finishActiveVisibleBlock()
                for (i in 0 until incomingToolCalls.length()) {
                    val item = incomingToolCalls.optJSONObject(i) ?: continue
                    val index = item.optInt("index", i)
                    val call = toolCalls.getOrPut(index) {
                        StreamingToolCall(
                            index = index,
                            contentIndex = nextContentIndex++,
                        ).also { created ->
                            onEvent(
                                ProviderEvent.BlockStart(
                                    kind = AssistantBlockKind.TOOL_CALL,
                                    index = created.contentIndex,
                                )
                            )
                        }
                    }
                    if (item.has("id") && !item.isNull("id")) {
                        item.optString("id").takeIf(String::isNotBlank)?.let { call.id = it }
                    }
                    if (item.has("type") && !item.isNull("type")) call.type = item.optString("type").ifBlank { "function" }
                    val function = item.optJSONObject("function")
                    val nameDelta = function?.takeIf { it.has("name") && !it.isNull("name") }?.optString("name").orEmpty()
                    val argsDelta = function?.takeIf { it.has("arguments") && !it.isNull("arguments") }?.optString("arguments").orEmpty()
                    if (nameDelta.isNotEmpty()) call.name.append(nameDelta)
                    if (argsDelta.isNotEmpty()) call.arguments.append(argsDelta)
                    if (argsDelta.isNotEmpty()) {
                        onEvent(
                            ProviderEvent.BlockDelta(
                                kind = AssistantBlockKind.TOOL_CALL,
                                index = call.contentIndex,
                                delta = argsDelta,
                            )
                        )
                    }
                }
        }

        AgentSseClient.collect(
            request = request,
            runController = runController,
            onOpen = { code -> onEvent(ProviderEvent.ResponseHeaders(code)) },
            transportFactory = transportFactory,
            onEvent = sseEvent@{ _, _, data ->
                val payload = data.trim()
                if (payload.isBlank()) return@sseEvent
                if (payload == "[DONE]") {
                    sawStreamData = true
                    sawDone = true
                    finish()
                } else consumeFrame(payload)
            },
            onJson = { payload ->
                val json = AgentResponseFormat.parseJsonObject(payload)
                if (json.has("error") && !json.isNull("error") && json.optJSONObject("error") == null) {
                    throw AgentModelFailure.stream(JSONObject(), "模型接口 JSON 返回错误")
                }
                if (json.optJSONObject("error") == null && json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") == null) {
                    throw AgentModelFailure.unexpectedResponse(200, "application/json", "")
                }
                consumeFrame(payload)
                // A complete JSON message is terminal evidence, not an unfinished SSE tool delta.
                sawDone = true
            },
            shouldIgnoreFailure = {
                (!requireTerminal || sawDone || !finishReason.isNullOrBlank()) &&
                    recoveredFinishReason(finishReason, content, reasoningContent, toolCalls) != null
            },
        )

        // A name (or even parseable JSON) is not proof that the model finished its arguments.
        if (finishReason.isNullOrBlank() && (runController.hasPendingSteering || runController.hasPausedInterrupt)) {
            finishActiveVisibleBlock()
            return interruptedAssistantMessage(content.toString(), reasoningContent.toString())
        }

        if (requireTerminal && !sawDone && finishReason.isNullOrBlank()) {
            throw AgentModelFailure.incompleteStream("模型响应尚未完成，连接已中断；保留正文并按重连策略继续请求")
        }

        if (!sawStreamData) throw AgentModelFailure.incompleteStream("模型接口未返回 SSE data chunk")
        if (finishReason.isNullOrBlank() && toolCalls.isNotEmpty() && !sawDone) {
            throw AgentModelFailure.incompleteStream("工具调用缺少完整结束标记，未执行")
        }
        val recoveredReason = recoveredFinishReason(finishReason, content, reasoningContent, toolCalls)
        if (recoveredReason == null) {
            throw AgentModelFailure.incompleteStream("模型接口 SSE 流未正常结束")
        }
        finishReason = recoveredReason

        finishActiveVisibleBlock()
        resolveToolCallIds(toolCalls.values, existingToolCallIds)
        toolCalls.values.sortedBy { it.contentIndex }.forEach { call ->
            onEvent(
                ProviderEvent.BlockEnd(
                    kind = AssistantBlockKind.TOOL_CALL,
                    index = call.contentIndex,
                    blockId = call.id,
                    name = call.name.toString().ifBlank { null },
                    content = call.arguments.toString(),
                )
            )
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", content.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also { message ->
                usage?.let { message.put("usage", it.toJson()) }
            }
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put(
                        "tool_calls",
                        JSONArray().also { array ->
                            toolCalls.values.sortedBy { it.index }.forEach { call ->
                                array.put(call.toJson())
                            }
                        }
                    )
                }
            }
    }

    private fun recoveredFinishReason(
        finishReason: String?,
        content: StringBuilder,
        reasoningContent: StringBuilder,
        toolCalls: Map<Int, StreamingToolCall>,
    ): String? {
        if (!finishReason.isNullOrBlank()) return finishReason
        if (toolCalls.isNotEmpty()) {
            val complete = toolCalls.values.all { call ->
                call.name.toString().isNotBlank() &&
                    runCatching { JSONObject(call.arguments.toString()) }.isSuccess
            }
            return if (complete) "tool_calls" else null
        }
        if (content.isNotBlank() || reasoningContent.isNotBlank()) return "stop"
        return null
    }

    private fun resolveToolCallIds(calls: Collection<StreamingToolCall>, existingIds: Set<String>) {
        if (calls.isEmpty()) return
        val reservedIds = existingIds.toMutableSet().apply {
            calls.mapNotNullTo(this) { it.id }
        }
        val assignedIds = existingIds.toMutableSet()
        val prefix = "call_eta_${UUID.randomUUID().toString().replace("-", "")}_"
        var nextId = 0
        // 先保留所有服务端 ID，再统一补齐；结束事件和历史必须使用同一个已定稿 ID。
        calls.sortedBy { it.index }.forEach { call ->
            if (call.id?.let(assignedIds::add) != true) {
                var generated: String
                do {
                    generated = "$prefix${nextId++}"
                } while (!reservedIds.add(generated))
                call.id = generated
                assignedIds += generated
            }
        }
    }

    private data class StreamingToolCall(
        val index: Int,
        val contentIndex: Int,
        var id: String? = null,
        var type: String = "function",
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder()
    ) {
        fun toJson(): JSONObject {
            val functionName = name.toString().trim()
            return JSONObject()
                .put("id", requireNotNull(id))
                .put("type", type.ifBlank { "function" })
                .put(
                    "function",
                    JSONObject()
                        .put("name", functionName)
                        .put("arguments", arguments.toString())
                )
        }
    }

    private data class StreamingVisibleBlock(
        val kind: AssistantBlockKind,
        val contentIndex: Int,
        val content: StringBuilder = StringBuilder(),
    )

    private fun mergeExtraBody(request: JSONObject, extraBodyJson: String) {
        if (extraBodyJson.isBlank()) return
        val extraBody = JSONObject(extraBodyJson)
        extraBody.keys().forEach { key ->
            request.put(key, extraBody.get(key))
        }
    }

    private fun throwStreamingErrorIfPresent(chunk: JSONObject) {
        val streamError = chunk.optJSONObject("error") ?: return
        val code = streamError.opt("code")
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
        val errorType = streamError.optJSONObject("metadata")
            ?.optString("error_type")
            ?.takeIf { it.isNotBlank() && it != "null" }
        val context = listOfNotNull(
            code?.let { "code=$it" },
            errorType?.let { "type=$it" },
        ).joinToString(", ")
        val message = streamError.optString("message")
            .ifBlank { "未提供错误信息" }
            .compactError()
        throw AgentModelFailure.stream(
            streamError,
            "模型接口 SSE 返回错误${context.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}：$message",
        )
    }

    private fun parseUsage(chunk: JSONObject): AgentTokenUsage? =
        parseUsageObject(chunk.optJSONObject("usage"))
            ?: parseUsageObject(chunk.optJSONObject("response")?.optJSONObject("usage"))

    private fun parseUsageObject(usage: JSONObject?): AgentTokenUsage? {
        usage ?: return null
        return AgentTokenUsage(
            contextTokens = usage.firstInt("total_tokens"),
            inputTokens = usage.firstInt("prompt_tokens", "input_tokens"),
            outputTokens = usage.firstInt("completion_tokens", "output_tokens"),
            reasoningTokens = usage.firstNestedInt(
                "completion_tokens_details",
                "output_tokens_details",
                childKey = "reasoning_tokens"
            ),
            cachedTokens = usage.firstNestedInt(
                "prompt_tokens_details",
                "input_tokens_details",
                childKey = "cached_tokens"
            ) ?: usage.firstInt("cache_read_input_tokens"),
            // OpenAI-compatible gateways report cache writes separately from reads.
            // prompt_tokens already includes both, so this stays a subset.
            cacheCreationTokens = usage.cacheCreationTokens(),
        ).takeUnless { it.isEmpty }
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (!has(key) || isNull(key)) continue
            val raw = opt(key)
            when (raw) {
                is Number -> return raw.toInt()
                is String -> raw.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.cacheCreationTokens(): Int? =
        firstNestedInt(
            "prompt_tokens_details",
            "input_tokens_details",
            childKey = "cache_creation_tokens",
        ) ?: firstNestedInt(
            "prompt_tokens_details",
            "input_tokens_details",
            childKey = "cache_creation_input_tokens",
        ) ?: firstNestedInt(
            "prompt_tokens_details",
            "input_tokens_details",
            childKey = "cache_write_tokens",
        ) ?: firstInt(
            "cache_creation_input_tokens",
            "cache_creation_tokens",
            "cache_write_input_tokens",
        )

    private fun JSONObject.firstNestedInt(
        vararg parentKeys: String,
        childKey: String
    ): Int? {
        for (parentKey in parentKeys) {
            val parent = optJSONObject(parentKey) ?: continue
            parent.firstInt(childKey)?.let { return it }
        }
        return null
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("total_tokens", it) }
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
            cacheCreationTokens?.let { json.put("cache_creation_tokens", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
