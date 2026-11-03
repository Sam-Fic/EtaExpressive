package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentToolGroupCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 决策与执行闭环：记录「判断模型没选、但实际执行了」的延迟工具组（decision_mismatch）。
 *
 * 真实路径（按 M2/M3 的收窄语义）：
 * - **control 模式**：`tools_use_group` 的枚举被收窄到 `plan.allowedGroups`，模型物理上
 *   打不开新组；能被执行、但不在 `selected/sticky` 里的组只有两种来源——
 *   上一轮的 sticky，或历史里已打开过的组（history 让工具在目录里继续可见）。
 *   这里把后者算作漏判（它确实说明「本轮决策认为不需要，但模型用了」）。
 * - **advise 模式**：`allowed = gate`，更常见；模型可以打开任何 gate 允许的组，
 *   执行成功即记为漏判。
 * - **fail-open 降级**：`failureReason != null` 时没有真正的决策语义，不统计。
 * - **续跑复用**：`skippedForContinuation` 的 overlay 来自上一个用户回合，
 *   本轮没有新的决策，不统计（否则会虚增漏判率）。
 *
 * 纯观测：只读事件与 plan，不改变任何执行行为。事件由 AgentLoop 串行回放，
 * 因此 [pending] 用普通 Map 即可（无跨线程并发写）。
 */
internal object AgentDecisionMismatch {

    private const val TOOLS_USE_GROUP = AgentToolGroupCatalog.USE_GROUP

    /**
     * 每次 run 一个实例：`ToolStarted` 记下候选，`ToolFinished` 拿到最终 success 再落一条日志。
     * 日志字段与 [AgentDecisionPlan] 的 run 级语义一致，便于事后回放。
     */
    class Tracker(
        private val plan: AgentDecisionPlan,
        private val conversationId: String,
        private val runId: String,
    ) {
        private data class Pending(val groupId: String, val round: Int, val recovered: Boolean)

        private val pending = mutableMapOf<String, Pending>()

        /** 本 run 是否调用过 `tools_use_group`：用来标注「已靠它回捞」。 */
        private var sawUseGroup = false

        fun accept(event: AgentEvent) {
            when (event) {
                is AgentEvent.ToolStarted -> onStarted(event)
                is AgentEvent.ToolFinished -> onFinished(event)
                else -> Unit
            }
        }

        private fun onStarted(event: AgentEvent.ToolStarted) {
            if (event.name == TOOLS_USE_GROUP) {
                sawUseGroup = true
                return
            }
            // 只看延迟组：基础组（记忆/待办等）永远可见，不属于决策范围。
            if (!AgentToolGroups.isDeferredTool(event.name)) return
            val groupId = AgentToolGroups.groupOfTool(event.name) ?: return
            if (plan.failureReason != null || plan.skippedForContinuation) return
            if (groupId in plan.selectedGroups || groupId in plan.stickyGroups) return
            pending[event.toolCallId] = Pending(
                groupId = groupId,
                round = event.round,
                // 组不在决策预开集合里，却已经能调用 → 只能是本 run 里 tools_use_group 打开的。
                recovered = sawUseGroup && groupId !in plan.openedGroups,
            )
        }

        private fun onFinished(event: AgentEvent.ToolFinished) {
            val call = pending.remove(event.toolCallId) ?: return
            // 同进程 Runtime 一定带 success；缺省时按成功处理，避免把旧版本误记成失败。
            val success = event.success ?: true
            AndroidAgentLogger.info(
                "decision_mismatch run_id=$runId conversation_id=$conversationId " +
                    "group=${call.groupId} tool=${event.name} mode=${plan.mode} " +
                    "selected=${plan.selectedGroups.sorted()} allowed=${plan.allowedGroups.sorted()} " +
                    "sticky=${plan.stickyGroups.sorted()} recovered=${call.recovered} " +
                    "success=$success round=${call.round}",
            )
            AgentDecisionTelemetry.recordMismatch(conversationId)
        }
    }
}
