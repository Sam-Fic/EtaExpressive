package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.config.Prefs

/**
 * 危险操作的逐次确认。
 *
 * 此前只有两种状态：整类能力开着，模型就直接执行；或者整类关掉。像重启设备、卸载应用、
 * 清空数据、删除用户、递归删除文件这类**不可逆**操作，中间没有「问问我再做」这一档。
 *
 * 风险判定集中在 [AgentRiskPolicy]（工具/动作映射表）与 [AgentShellCommandRisk]（命令启发式），
 * 本对象只负责把它接到确认闸门上：
 * - 中风险：本会话内同一个调用确认一次（受设置总开关控制，关闭时不打扰）；
 * - 高风险：每次都要确认，且不受总开关影响；
 * - 致命：强制确认、只允许「本次允许」，同样不受总开关影响。
 *
 * 确认交互由**模型自己用 ask_user 问**，所以复用已有的
 * [ToolExecutionDecision.Reject]：命中危险清单时，拒绝本次调用并回
 * [CONFIRM_CODE]，错误文案明确要求模型「先用 ask_user 取得同意、再重发同一个调用」。
 * 用户同意后的一次性授权由 [AgentDangerousConfirmationGate] 记录，
 * 重发同一调用时消费它放行；换参数/换工具则仍需重新确认。
 *
 * 覆盖范围是**可能不可逆或涉及凭据的操作**：纯读类工具（列表、查询、截图）仍然直接执行，
 * 只有 `read_sms_code`、`wifi_credentials` 这类凭据读取被当作中风险例外。
 */
internal object AgentDangerousOperationPolicy {
    const val CONFIRM_CODE = "USER_CONFIRMATION_REQUIRED"

    /** 判定一次调用是否危险（兼容旧调用方，语义等价于「需要确认」）。 */
    fun isDangerous(toolName: String, argumentsJson: String): Boolean =
        AgentRiskPolicy.assess(toolName, argumentsJson).requiresConfirmation

    /**
     * 需要用户确认时返回拒绝决策；否则返回 null（交回调用方继续原有的准入检查）。
     *
     * [confirmationEnabled]（设置里的总开关）只控制**中风险**是否询问：
     * 高风险与致命操作即使开关关闭也照样确认——用户关掉的是「什么都问」，
     * 不是「可以不问就删库/重启/卸载」。低风险永远不打扰。
     *
     * 已获同意的同一个调用（工具名 + 参数完全一致）直接放行：一次性授权消费掉，
     * 本会话授权按粒度保留；否则登记待确认并拒绝，让模型去问用户。
     */
    fun decision(
        toolName: String,
        argumentsJson: String,
        confirmationEnabled: Boolean = Prefs.isEnabled(Prefs.Keys.AGENT_CONFIRM_DANGEROUS_TOOLS),
    ): ToolExecutionDecision.Reject? =
        decide(toolName, argumentsJson, AgentRiskPolicy.assess(toolName, argumentsJson), confirmationEnabled)

    /**
     * 配方调用的判定：配方里的 Root shell 步骤也要过一遍命令启发式，
     * 否则模型可以写一个 `recipe_x` 把 `rm -rf` 包装成「无害工具」绕过确认。
     */
    fun decisionForRecipe(
        toolName: String,
        argumentsJson: String,
        shellCommands: List<String>,
        confirmationEnabled: Boolean = Prefs.isEnabled(Prefs.Keys.AGENT_CONFIRM_DANGEROUS_TOOLS),
    ): ToolExecutionDecision.Reject? =
        decide(
            toolName,
            argumentsJson,
            AgentRiskPolicy.assessRecipe(toolName, argumentsJson, shellCommands),
            confirmationEnabled,
        )

    private fun decide(
        toolName: String,
        argumentsJson: String,
        assessment: AgentRiskAssessment,
        confirmationEnabled: Boolean,
    ): ToolExecutionDecision.Reject? {
        if (!assessment.requiresConfirmation) return null
        // 总开关只决定「中风险要不要问」；高风险与致命操作不可撤销，始终询问。
        if (!confirmationEnabled && assessment.level == AgentRiskLevel.MEDIUM) return null
        val signature = AgentDangerousConfirmationGate.signature(toolName, argumentsJson)
        if (AgentDangerousConfirmationGate.consume(signature, assessment.scope)) {
            AgentDangerousConfirmationGate.pruneExpired()
            return null
        }
        AgentDangerousConfirmationGate.expectConfirmation(signature, assessment)
        AgentDangerousConfirmationGate.pruneExpired()
        return ToolExecutionDecision.Reject(
            code = CONFIRM_CODE,
            message = confirmationMessage(toolName, assessment),
        )
    }

    /** 兼容旧调用方：不带风险明细的通用文案。 */
    fun confirmationMessage(toolName: String): String =
        confirmationMessage(
            toolName,
            AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("该操作可能不可逆或影响范围较大")),
        )

    /**
     * 拒绝时给模型的指令。必须同时说清四件事，否则模型会把它当成权限错误去换工具绕过：
     * 1. 没有做任何改动；2. 会发生什么、影响范围与是否可撤销；
     * 3. 要求用 ask_user 并指定同意选项 id；4. 取得同意后重发**完全相同**的调用。
     */
    fun confirmationMessage(toolName: String, assessment: AgentRiskAssessment): String {
        val levelLabel = when (assessment.level) {
            AgentRiskLevel.MEDIUM -> "中风险"
            AgentRiskLevel.HIGH -> "高风险"
            AgentRiskLevel.FATAL -> "致命风险（不可撤销）"
            AgentRiskLevel.LOW -> "低风险"
        }
        val consequences = assessment.reasons.joinToString("；").ifBlank { "该操作可能不可逆" }
        val sessionHint = if (assessment.scope == AgentConfirmationScope.SESSION) {
            "本会话允许同一调用：同意选项的 id 必须是 \"${AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID}\"；"
        } else {
            "本次操作不允许「本会话一直允许」，只能逐次同意；"
        }
        return "「$toolName」被判定为$levelLabel，已拦下，**未做任何改动**。" +
            "将要发生：$consequences。" +
            "请先用 ask_user 向用户说明会发生什么、影响范围以及能否撤销，并取得明确同意：" +
            "本次允许的同意选项 id 必须是 \"${AgentDangerousConfirmationGate.CONFIRM_OPTION_ID}\"；" +
            sessionHint +
            "取消选项的 id 用 \"${AgentDangerousConfirmationGate.CANCEL_OPTION_ID}\"。" +
            "用户同意后再重新发起**参数完全相同**的同一个调用；参数变了要重新确认。" +
            "用户拒绝或选择其它选项时不要执行；不要换其他工具绕过确认，也不要重复提问同一件事。"
    }
}
