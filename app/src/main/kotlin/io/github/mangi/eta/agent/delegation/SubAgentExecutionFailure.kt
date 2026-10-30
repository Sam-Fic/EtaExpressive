package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentInvalidToolArgumentsGuard
import io.github.mangi.eta.agent.model.AgentModelFailure
import org.json.JSONObject

/** Classifies a known local safety stop, not a provider outage or a completed child result. */
internal object SubAgentExecutionFailure {
    data class Failure internal constructor(
        val code: String,
        val message: String,
        val nextStep: String,
    ) {
        fun toJson(): JSONObject = JSONObject().put("ok", false).put("code", code)
            .put("message", message).put("next_step", nextStep)
    }

    private val toolArgumentsExhausted = Failure(
        code = AgentInvalidToolArgumentsGuard.STOP_CODE,
        message = "子代理工具参数纠错预算已耗尽；已在本批工具结果完整配对后安全终止，任务未完成",
        nextStep = "检查本次工具 schema 允许的 action、字段、类型与范围；停止且不要重放已成功或结果不确定的调用。" +
            "如有工作区，先用 manage_agent_workspace 的 inspect 核验实际改动；不要据此替换子任务、恢复或自动重试。",
    )

    /**
     * Deliberately match the typed, direct loop failure only. Never infer from an obfuscated
     * class name, exception text, HTTP status or an arbitrary wrapper's cause. This does not
     * catch the failure, authorize replacement, change the repair budget or replay a call.
     */
    fun find(error: Throwable): Failure? = toolArgumentsExhausted.takeIf {
        error is AgentModelFailure && error.code == AgentInvalidToolArgumentsGuard.STOP_CODE
    }

    /** The stored runtime-owned code may supply a fixed recovery hint, never a new classification. */
    fun nextStep(code: String): String? = toolArgumentsExhausted.nextStep.takeIf {
        code == AgentInvalidToolArgumentsGuard.STOP_CODE
    }
}
