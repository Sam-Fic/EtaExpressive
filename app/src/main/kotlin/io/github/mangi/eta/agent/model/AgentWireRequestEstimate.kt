package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import org.json.JSONArray
import org.json.JSONObject

/** Pure projection of the final HTTP body, NOT a provider tokenizer or a send/compaction budget.
 * Only known prompt-bearing fields are priced. Ciphertext, signatures, URLs and base64 are never
 * treated as text tokens. Opaque replay/hosted-tool internal costs remain unknown.
 */
internal object AgentWireRequestEstimate {
    data class Component(val chars: Long = 0, val tokens: Int = 0, val utf8Bytes: Long = 0)
    data class Shape(
        val endpoint: EndpointKind,
        val instructions: Component,
        val text: Component,
        val reasoning: Component,
        val toolCalls: Component,
        val toolResults: Component,
        val tools: Component,
        val format: Component,
        val media: Component,
        val framingTokens: Int,
        val messageCount: Int,
        val toolCount: Int,
        val hostedToolCount: Int,
        val imageCount: Int,
        val videoCount: Int,
        val opaqueItems: Int,
        val encryptedChars: Long,
        val unknownBlocks: Int,
    ) {
        val tokens: Int get() = (listOf(instructions, text, reasoning, toolCalls, toolResults,
            tools, format, media).sumOf { it.tokens.toLong() } + framingTokens)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun endpoint(config: AgentModelClient.ModelConfig): EndpointKind = when {
        config.providerType == ProviderTypes.ANTHROPIC -> EndpointKind.ANTHROPIC_MESSAGES
        config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES -> EndpointKind.RESPONSES
        else -> EndpointKind.CHAT_COMPLETIONS
    }

    fun sendsPlaintextReasoning(endpoint: EndpointKind): Boolean = endpoint == EndpointKind.CHAT_COMPLETIONS

    /** Preview only: reuse real builders, never reconstruct a second version of the payload. */
    fun previewBody(config: AgentModelClient.ModelConfig, messages: JSONArray, tools: JSONArray): JSONObject =
        when (endpoint(config)) {
            EndpointKind.CHAT_COMPLETIONS -> OpenAiChatCompletionsProvider.buildRequestJson(config, messages, tools)
            EndpointKind.RESPONSES -> ResponsesRequestBuilder.build(config, messages, tools)
            EndpointKind.ANTHROPIC_MESSAGES -> AnthropicMessagesProvider.buildRequestJson(config, messages, tools)
        }

    fun measure(body: JSONObject, endpoint: EndpointKind, includeBytes: Boolean = true): Shape =
        Counter(endpoint, includeBytes).measure(body)

    /** Same numeric snapshot drives the existing estimate channel and the bounded diagnostic. */
    fun publish(body: JSONObject, endpoint: EndpointKind, request: ProviderRequest, onEvent: (ProviderEvent) -> Unit,
        serializedBodyBytes: Long? = null, serializedBody: String? = null) {
        // UTF-8 byte volumes are diagnostic-only; skipping them avoids a full copy of every
        // payload string on the pre-network path when diagnostics are off.
        val shape = measure(body, endpoint, includeBytes = request.toolDiagnosticAttempt != null)
        runCatching {
            request.toolDiagnosticAttempt?.requestShape(
                AgentRequestContextDiagnostics.wireBodyFields(shape).apply {
                    serializedBodyBytes?.takeIf { it >= 0 }?.let { put("body_utf8_bytes", it) }
                    if (endpoint == EndpointKind.RESPONSES) {
                        val legacy = AgentRequestContextDiagnostics.responseBodyFields(
                            AgentRequestContextDiagnostics.responseBody(body, request.messages))
                        legacy.keys().forEach { key -> if (!has(key)) put(key, legacy.get(key)) }
                    }
                }, serializedBody, body)
        }
        onEvent(ProviderEvent.RequestEstimate(shape.tokens))
    }

    private class Sum(private val includeBytes: Boolean) {
        var chars = 0L
        var bytes = 0L
        var tokens = 0L
        fun add(value: Any?) {
            if (value == null || value === JSONObject.NULL) return
            val text = value.toString()
            chars += text.length
            if (includeBytes) bytes += text.toByteArray(Charsets.UTF_8).size
            tokens += AgentContextBudget.countTokens(text)
        }
        fun component() = Component(chars, tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), bytes)
    }

    private class Counter(private val endpoint: EndpointKind, includeBytes: Boolean) {
        val instructions = Sum(includeBytes); val text = Sum(includeBytes); val reasoning = Sum(includeBytes)
        val calls = Sum(includeBytes); val results = Sum(includeBytes); val tools = Sum(includeBytes)
        val format = Sum(includeBytes); val media = Sum(includeBytes)
        var framing = 0L; var messages = 0; var toolCount = 0; var hosted = 0
        var images = 0; var videos = 0; var opaque = 0; var encrypted = 0L; var unknown = 0

        fun measure(body: JSONObject): Shape {
            val definitions = body.optJSONArray("tools")
            if (definitions != null && definitions.length() > 0) {
                tools.add(definitions)
                toolCount = definitions.length()
                each(definitions) { tool ->
                    val type = tool.optString("type")
                    if (type.isNotBlank() && type != "function" && type != "custom") hosted++
                }
            }
            format.add(if (endpoint == EndpointKind.RESPONSES)
                body.optJSONObject("text")?.opt("format") else body.opt("response_format"))
            when (endpoint) {
                EndpointKind.RESPONSES -> {
                    content(body.opt("instructions"), instructions)
                    val input = body.opt("input")
                    if (input is String) { messages++; framing += 3; text.add(input) }
                    else each(input as? JSONArray) { item ->
                        when (item.optString("type")) {
                            "", "message" -> message(item)
                            "function_call" -> { framing += 3; call(item, "arguments"); calls.add(item.opt("call_id")) }
                            "function_call_output" -> { framing += 3; results.add(item.opt("call_id")); content(item.opt("output"), results) }
                            "reasoning" -> {
                                opaque++; encrypted += item.optString("encrypted_content").length
                                content(item.opt("summary"), reasoning)
                                content(item.opt("content"), reasoning)
                                reasoning.add(item.opt("reasoning_text"))
                            }
                            else -> unknown++
                        }
                    }
                }
                EndpointKind.ANTHROPIC_MESSAGES -> {
                    content(body.opt("system"), instructions)
                    each(body.optJSONArray("messages"), ::message)
                }
                EndpointKind.CHAT_COMPLETIONS -> each(body.optJSONArray("messages"), ::message)
            }
            return Shape(endpoint, instructions.component(), text.component(), reasoning.component(),
                calls.component(), results.component(), tools.component(), format.component(), media.component(),
                framing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), messages, toolCount, hosted,
                images, videos, opaque, encrypted, unknown)
        }

        private fun message(message: JSONObject) {
            messages++; framing += 3
            val role = message.optString("role")
            val target = when (role) { "system", "developer" -> instructions; "tool", "function" -> results; else -> text }
            content(message.opt("content"), target)
            if (sendsPlaintextReasoning(endpoint)) reasoning.add(message.opt("reasoning_content"))
            results.add(message.opt("tool_call_id"))
            calls.add(message.opt("name"))
            each(message.optJSONArray("tool_calls")) { calls.add(it) }
            calls.add(message.opt("function_call"))
        }

        private fun call(item: JSONObject, argumentKey: String) {
            calls.add(item.opt("name")); calls.add(item.opt(argumentKey))
        }

        private fun content(value: Any?, target: Sum) {
            when (value) {
                is String -> target.add(value)
                is JSONArray -> for (i in 0 until value.length()) content(value.opt(i), target)
                is JSONObject -> when (value.optString("type")) {
                    "text", "input_text", "output_text", "summary_text", "reasoning_text" -> target.add(value.opt("text"))
                    "refusal" -> target.add(value.opt("refusal"))
                    "thinking" -> { reasoning.add(value.opt("thinking")); if (value.has("signature")) opaque++ }
                    "redacted_thinking" -> { opaque++; encrypted += value.optString("data").length }
                    "tool_use", "server_tool_use" -> { call(value, "input"); calls.add(value.opt("id")) }
                    "tool_result" -> { results.add(value.opt("tool_use_id")); content(value.opt("content"), results) }
                    "image", "image_url", "input_image" -> image(value, false)
                    "video", "video_url", "input_video" -> image(value, true)
                    else -> unknown++
                }
            }
        }

        private fun image(part: JSONObject, video: Boolean) {
            if (video) videos++ else images++
            val key = if (video) "video_url" else "image_url"
            val url = part.optJSONObject(key)?.optString("url") ?: part.optString(key)
            val source = part.optJSONObject("source")
            val encoded = source?.takeIf { it.optString("type") == "base64" }?.optString("data")
            val marker = url.indexOf("base64,", ignoreCase = true)
            val chars = when { encoded != null -> encoded.length.toLong(); marker >= 0 -> (url.length - marker - 7).toLong(); else -> 0L }
            media.chars += chars
            media.bytes += chars // Base64 ASCII volume, not text token cost.
            val width = part.optInt("width", part.optJSONObject(key)?.optInt("width") ?: 0)
            val height = part.optInt("height", part.optJSONObject(key)?.optInt("height") ?: 0)
            media.tokens += when {
                video -> AgentContextBudget.countVideoBytesTokens(chars * 3 / 4).toLong()
                width > 0 && height > 0 -> AgentContextBudget.countImageTokens(width, height).toLong()
                else -> AgentContextBudget.countImageBytesTokens(chars * 3 / 4).toLong()
            }
        }

        private fun each(array: JSONArray?, consume: (JSONObject) -> Unit) {
            if (array != null) for (i in 0 until array.length()) array.optJSONObject(i)?.let(consume)
        }
    }
}
