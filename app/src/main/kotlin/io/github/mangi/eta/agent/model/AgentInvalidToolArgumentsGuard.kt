package io.github.mangi.eta.agent.model

/**
 * One instance per AgentLoop/run. Counts rejected calls, not provider rounds or call IDs.
 * Unknown tool names share one bucket; neither arguments nor arbitrary names create keys.
 * This guard never executes, rewrites or replays a call. The caller must append the whole
 * tool batch before acting on stopMessage, including valid calls after exhaustion.
 * Declared delegate_task calls retain their separate AgentDelegationArgumentRepair policy.
 */
internal class AgentInvalidToolArgumentsGuard {
    // A null key cannot collide with any declared tool name.
    private val failures = mutableMapOf<String?, Int>()
    /** 延迟组未打开时的调用按组分桶，与下面的参数校验预算完全隔离。 */
    private val groupHints = mutableMapOf<String, Int>()
    var stopMessage: String? = null
        private set

    data class Rejection(val code: String, val message: String)

    /**
     * 调用了一个尚未打开的延迟组工具：这是**时序**问题，不是参数错误。
     *
     * 必须与 [reject] 的 3 次硬停预算隔离——否则模型按索引尝试两三个组就能耗尽预算、
     * 让整轮失败（「第一次调用成功，后面全都调不了」）。这里只按组分桶计数，永不设置
     * [stopMessage]，纠正文案本身已给出「先 tools_use_group 再下一轮调用」的补救。
     */
    fun rejectGroupHint(toolName: String, groupId: String, validationError: String): Rejection {
        val key = "group:$groupId"
        val count = ((groupHints[key] ?: 0) + 1).coerceAtMost(MAX_GROUP_HINTS)
        groupHints[key] = count
        val message = if (count >= MAX_GROUP_HINTS) {
            "工具组 $groupId 的调用已连续 $count 次未先打开（本次未执行）：" +
                "不要再直接调用组内工具。先调用 tools_use_group(group=$groupId)，下一轮再用；" +
                "若组已打开仍报未声明，说明该能力在本设备上不可用，请改用其它方式。"
        } else {
            "工具 ${toolName.trim()} 属于尚未打开的延迟工具组 $groupId（$count/$MAX_GROUP_HINTS）：" +
                "$validationError。本次调用未执行；请先调用 tools_use_group(group=$groupId)，" +
                "下一轮再调用它，不要重放已成功的工具。"
        }
        return Rejection(TOOL_GROUP_HINT_CODE, message)
    }

    fun reject(toolName: String, declared: Boolean, validationError: String): Rejection {
        val key = if (declared) toolName.trim() else null
        val count = ((failures[key] ?: 0) + 1).coerceAtMost(MAX_FAILURES)
        failures[key] = count
        val subject = if (key == null) "未声明工具（所有未知名称共用预算）" else "工具 $key"
        if (count >= MAX_FAILURES) {
            val message = "$STOP_CODE：$subject 的参数校验失败已达 $MAX_FAILURES 次，纠错预算耗尽。" +
                "本次调用未执行；本批工具结果完整配对后按错误重连策略处理，不重放已完成工具。" +
                "不会自动切换环境或重放调用。校验原因：$validationError"
            if (stopMessage == null) stopMessage = message
            return Rejection(STOP_CODE, message)
        }
        return Rejection(
            FAILURE_CODE,
            "$subject 的参数校验失败（$count/$MAX_FAILURES）：$validationError。" +
                "本次调用未执行；请仅修正这个调用，不要重放已成功的工具。" +
                "再次无效达到 $MAX_FAILURES 次将按错误重连策略处理；更换调用 ID、空白或其它工具成功均不会重置此预算。",
        )
    }

    fun validated(toolName: String) {
        // Exhaustion is sticky even if the same batch later contains a corrected call.
        if (stopMessage != null) return
        failures.remove(toolName.trim())
    }

    fun clearExhaustion() {
        stopMessage = null
        failures.clear()
        groupHints.clear()
    }

    companion object {
        const val MAX_FAILURES = 3
        /** 同一延迟组最多提醒几次；只影响文案强度，不终止 run。 */
        const val MAX_GROUP_HINTS = 2
        const val FAILURE_CODE = "INVALID_TOOL_ARGUMENTS"
        const val STOP_CODE = "INVALID_TOOL_ARGUMENTS_REPAIR_EXHAUSTED"
        const val TOOL_GROUP_HINT_CODE = "TOOL_GROUP_NOT_OPENED"
    }
}
