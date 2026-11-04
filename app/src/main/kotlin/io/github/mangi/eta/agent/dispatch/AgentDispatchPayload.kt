package io.github.mangi.eta.agent.dispatch

import org.json.JSONObject

/**
 * 调度台派发 run 的 handoff 载荷（handoff source = "eta_dispatch"）。
 *
 * 字段即跨层契约：
 * - [conversationId] 派发目标会话，也是 run 的会话身份（见 AgentConversationIdentity 的 dispatch 分支）；
 * - [dispatchId] 本次派发的债权标识，结算与通知都按它幂等；
 * - [originText] 用户原话——工单同时保留「原话」与「下发 brief」，调度可审计；
 * - [brief] 实际下发给工作会话的任务指令；outbox 回收时用它补齐接续会话的用户轮。
 */
internal data class AgentDispatchPayload(
    val conversationId: String,
    val dispatchId: String,
    val originText: String,
    val brief: String = "",
) {
    fun toJson(): String = JSONObject()
        .put(KEY_CONVERSATION_ID, conversationId)
        .put(KEY_DISPATCH_ID, dispatchId)
        .put(KEY_ORIGIN_TEXT, originText)
        .put(KEY_BRIEF, brief)
        .toString()

    companion object {
        private const val KEY_CONVERSATION_ID = "conversation_id"
        private const val KEY_DISPATCH_ID = "dispatch_id"
        private const val KEY_ORIGIN_TEXT = "origin_text"
        private const val KEY_BRIEF = "brief"

        fun from(raw: String?): AgentDispatchPayload? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                val conversationId = json.optString(KEY_CONVERSATION_ID)
                val dispatchId = json.optString(KEY_DISPATCH_ID)
                if (conversationId.isBlank() || dispatchId.isBlank()) return null
                AgentDispatchPayload(
                    conversationId = conversationId,
                    dispatchId = dispatchId,
                    originText = json.optString(KEY_ORIGIN_TEXT),
                    brief = json.optString(KEY_BRIEF),
                )
            }.getOrNull()
        }
    }
}
