package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import org.json.JSONArray
import org.json.JSONObject

internal interface AgentProviderClient {
    val id: String
    val capabilities: ProviderCapabilities

    fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit = {}
    ): ProviderResponse
}

internal data class ProviderCapabilities(
    val endpoint: EndpointKind,
    val streamingText: Boolean,
    val streamingToolCalls: Boolean,
    val imageInput: Boolean,
    val toolResultImages: Boolean,
    val strictTools: Boolean,
    val parallelToolCalls: Boolean
)

internal enum class EndpointKind {
    CHAT_COMPLETIONS,
    RESPONSES,
    ANTHROPIC_MESSAGES
}

internal data class ProviderRequest(
    val config: AgentModelClient.ModelConfig,
    val messages: JSONArray,
    val tools: JSONArray,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val usageConversationId: String = sessionId,
    // Request-scoped compatibility after a confirmed pre-delivery envelope rejection.
    val singleToolCall: Boolean = false,
    // Local-only diagnostic handles. Request builders never serialize these fields.
    val toolDiagnostics: AgentToolCallDiagnostics? = null,
    val toolDiagnosticAttempt: AgentToolCallDiagnostics.Attempt? = null,
    // A transport recovery must never reissue an uncertain tool operation.
    val reconnectTextOnly: Boolean = false,
    // Keep local function calls usable without reissuing unknown hosted operations.
    val reconnectLocalToolsOnly: Boolean = false,
    /**
     * 摘要请求：沿用主会话前缀与工具目录以命中服务商前缀缓存，但显式禁止模型发起工具调用。
     *
     * 取证：上游 Mangi-11/Eta 提交 01d2148 —— Chat Completions / Responses / Gemini
     * 显式下发 `tool_choice=none`；Anthropic 保留原 tool_choice（即不下发该字段），
     * 因为改动它会让 messages 前缀缓存失效，改由提示词与 fail-close 兜底。
     * 本仓 ProviderRequest 无 purpose 字段，故用此标志表达；只在摘要请求且 tools 非空时为 true。
     * 非摘要请求一律 false，不改变既有协议行为。
     */
    val toolChoiceNone: Boolean = false,
)

internal val ProviderRequest.requiresCompleteStream: Boolean
    get() = ErrorReconnectPolicy.fromPersistedValue(config.errorReconnectPolicy) != ErrorReconnectPolicy.NONE

/** Apply after custom-body merging so overrides cannot re-enable tools on recovery. */
internal fun ProviderRequest.restrictReconnectPayload(
    body: JSONObject,
    endpoint: EndpointKind = EndpointKind.CHAT_COMPLETIONS,
): JSONObject {
    if (reconnectTextOnly || reconnectLocalToolsOnly) {
        listOf("tools", "tool_choice", "parallel_tool_calls", "functions", "function_call", "web_search_options",
            "mcp_servers", "previous_response_id", "conversation").forEach(body::remove)
        if (!reconnectTextOnly) {
            // Recreate the authoritative catalog AFTER custom-body merging. A custom
            // payload cannot inject hosted/MCP tools or turn tool_choice into "none".
            val functions = JSONArray()
            for (index in 0 until tools.length()) {
                val item = tools.optJSONObject(index) ?: continue
                val function = item.optJSONObject("function") ?: continue
                if (item.optString("type") == "function" && function.optString("name").isNotBlank()) {
                    functions.put(JSONObject(item.toString()))
                }
            }
            val localTools = when (endpoint) {
                EndpointKind.CHAT_COMPLETIONS -> functions
                EndpointKind.RESPONSES -> ResponsesRequestBuilder.buildTools(functions, false)
                EndpointKind.ANTHROPIC_MESSAGES -> AnthropicMessagesProvider.convertTools(functions) ?: JSONArray()
            }
            if (localTools.length() > 0) {
                body.put("tools", localTools)
                if (endpoint == EndpointKind.RESPONSES && singleToolCall) body.put("parallel_tool_calls", false)
            }
        }
    }
    return body
}

internal data class ProviderResponse(
    val assistantMessage: JSONObject
) {
    val stopReason: AssistantStopReason
        get() = AssistantStopReason.fromWireValue(assistantMessage.optString("finish_reason"))
}

/** An intentionally interrupted response is a text draft, never an executable tool batch.
 * Construct a fresh message so neither partial tool_calls nor opaque Responses items leak into replay.
 */
internal fun interruptedAssistantMessage(text: String, reasoning: String): JSONObject =
    JSONObject()
        .put("role", "assistant")
        .put("content", text)
        .put("reasoning_content", reasoning)
        .put("finish_reason", "eta_interrupted")

internal enum class AssistantStopReason {
    END_TURN,
    TOOL_USE,
    OUTPUT_LIMIT,
    INTERRUPTED,
    CONTENT_FILTER,
    UNKNOWN;

    companion object {
        fun fromWireValue(value: String?): AssistantStopReason =
            when (value?.trim()?.lowercase()) {
                "stop", "end_turn" -> END_TURN
                "tool_calls", "tool_use" -> TOOL_USE
                "length", "max_tokens" -> OUTPUT_LIMIT
                "eta_interrupted" -> INTERRUPTED
                "content_filter", "refusal" -> CONTENT_FILTER
                else -> UNKNOWN
            }
    }
}

internal enum class AssistantBlockKind {
    TEXT,
    THINKING,
    TOOL_CALL,
}

internal sealed interface ProviderEvent {
    data object RequestStarted : ProviderEvent

    // Final HTTP body estimate, deliberately distinct from a cloud Usage receipt.
    data class RequestEstimate(val tokens: Int) : ProviderEvent

    data class ResponseHeaders(
        val httpCode: Int
    ) : ProviderEvent

    data class BlockStart(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
    ) : ProviderEvent

    data class BlockDelta(
        val kind: AssistantBlockKind,
        val index: Int,
        val delta: String,
    ) : ProviderEvent

    data class BlockEnd(
        val kind: AssistantBlockKind,
        val index: Int,
        val blockId: String? = null,
        val name: String? = null,
        val content: String = "",
        val replaceContent: Boolean = false,
    ) : ProviderEvent

    data class Usage(
        val usage: AgentTokenUsage
    ) : ProviderEvent

    data class HostedToolStarted(
        val id: String,
        val name: String,
    ) : ProviderEvent

    data class HostedToolFinished(
        val id: String,
        val name: String,
        val success: Boolean,
    ) : ProviderEvent

    data class Completed(
        val reason: String?
    ) : ProviderEvent
}
