package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import org.json.JSONArray
import org.json.JSONObject

/** Matches actual tool call IDs, never tool order or a similar command/summary. */
internal class ConversationToolEvidence(messages: List<AgentChatMessageUi>) {
    data class Original(val arguments: String, val result: String, val source: String) {
        fun details(): String = """Evidence: stored tool response (not a UI summary)
Source: $source
Completeness: exported verbatim from stored history; the tool itself may have limited/truncated its output.
Arguments (raw JSON):
$arguments
Result (raw):
$result"""
    }
    private data class Identity(val run: String, val call: String, val name: String)
    // 工具调用 id 边界 Pattern 提为常量：构造函数对全量工具消息逐条 find，现场编译纯浪费。
    private val ToolIdBoundaryPattern = Regex("-tool-[0-9]+-")
    private val identities = messages.filterIsInstance<ToolActivityMessageUi>().mapNotNull { message ->
        val boundary = ToolIdBoundaryPattern.find(message.id) ?: return@mapNotNull null
        message.id to Identity(message.id.substring(0, boundary.range.first).substringAfterLast(':'),
            message.id.substring(boundary.range.last + 1), message.toolName)
    }.toMap()
    private data class Candidate(val turn: String, val original: Original)
    private val candidates = mutableMapOf<String, MutableList<Candidate>>()
    private val redacted = mutableSetOf<String>()
    private val wanted = identities.values.map { it.call }.toSet()

    fun add(history: List<AgentModelClient.ConversationMessage>, source: String) {
        val calls = mutableMapOf<Pair<String, String>, AgentModelClient.ToolCall>()
        history.forEach { message ->
            if (message.toolCallsJson.isNotBlank()) {
                runCatching { JSONArray(message.toolCallsJson) }.getOrNull()?.let { array ->
                    for (i in 0 until array.length()) {
                        val call = array.optJSONObject(i) ?: continue
                        val id = call.optString("id")
                        if (id !in wanted) continue
                        val fn = call.optJSONObject("function") ?: continue
                        calls[message.turnId to id] = AgentModelClient.ToolCall(id, fn.optString("name"), fn.optString("arguments"))
                    }
                }
            }
            if (message.role != "tool" || message.toolCallId !in wanted) return@forEach
            val call = calls[message.turnId to message.toolCallId] ?: return@forEach
            val sensitive = AgentSensitiveToolPolicy.isSensitive(call.name) ||
                message.content.contains("敏感工具参数与原始结果") ||
                runCatching { JSONObject(call.argumentsJson).optBoolean("redacted") }.getOrDefault(false)
            identities.forEach identityLoop@ { (id, identity) ->
                if (identity.call != call.id || identity.name != call.name) return@identityLoop
                if (sensitive) { redacted += id; return@identityLoop }
                // A compacted placeholder is not original evidence; look in the verified archive instead.
                if (message.content.contains("[Eta tool output pruned;") || message.content.isEmpty()) return@identityLoop
                val original = Original(call.argumentsJson, message.content, source)
                val list = candidates.getOrPut(id) { mutableListOf() }
                if (list.none { it.turn == message.turnId && it.original.arguments == original.arguments && it.original.result == original.result }) {
                    // Ambiguous legacy IDs are never guessed; bound retained alternatives.
                    if (list.size < 8) list += Candidate(message.turnId, original)
                }
            }
        }
    }

    fun original(messageId: String): Original? {
        if (messageId in redacted) return null
        val identity = identities[messageId] ?: return null
        val all = candidates[messageId].orEmpty()
        val exact = all.filter { it.turn == identity.run }
        // Modern turn metadata is authoritative. Only truly legacy (untagged) history
        // may use the unique-ID fallback; never substitute another run's result.
        return (if (exact.isNotEmpty()) exact else all.filter { it.turn.isBlank() }).singleOrNull()?.original
    }
}
