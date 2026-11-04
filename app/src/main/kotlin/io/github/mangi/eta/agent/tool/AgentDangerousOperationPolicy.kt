package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.config.Prefs

/**
 * 工具调用审查模式（对齐 ZCode 的权限模式光谱，映射到 Eta 的风险阶梯）。
 * 字符串值与 [io.github.mangi.eta.config.Prefs] 的 Keys 常量一一对应。
 */
internal enum class AgentToolReviewMode {
    /** 只读：非低风险调用直接拒绝，不询问（对齐 ZCode plan）。 */
    READONLY,

    /** 严格：中风险也逐次确认，不享受「本会话允许一次」。 */
    STRICT,

    /** 标准（默认）：中风险本会话内确认一次，高/致命每次确认（原开关开启的行为）。 */
    STANDARD,

    /** 自动：中风险免确认直接执行；高/致命仍确认（对齐 ZCode yolo 的打折版——手机上有 Root，致命操作的确认是底线）。 */
    AUTO,
    ;

    companion object {
        fun from(prefsValue: String): AgentToolReviewMode = when (prefsValue) {
            "readonly" -> READONLY
            "strict" -> STRICT
            "auto" -> AUTO
            else -> STANDARD
        }
    }
}

/**
 * 工具调用的审查与确认。
 *
 * 风险判定集中在 [AgentRiskPolicy]（工具/动作映射表）与 [AgentShellCommandRisk]（命令启发式），
 * 本对象负责按**审查模式**（[AgentToolReviewMode]，设置项实时读取，改完立即生效）把它接到确认闸门上：
 * - 只读：非低风险直接拒绝（回 [READ_ONLY_CODE]），不提供 ask_user 授权路径；
 * - 严格：中风险逐次确认（不消费会话授权），高/致命每次确认；
 * - 标准（默认）：中风险本会话内确认一次，高/致命每次确认；
 * - 自动：中风险直接执行；高/致命**仍然确认**——用户切到自动档免掉的是「什么都问」，
 *   不是「可以不问就删库/重启/卸载」。
 *
 * 确认交互由**模型自己用 ask_user 问**，复用 [ToolExecutionDecision.Reject]：
 * 命中确认条件时拒绝本次调用并回 [CONFIRM_CODE]，文案要求模型「先用 ask_user 取得同意、
 * 再重发同一个调用」。一次性/会话授权由 [AgentDangerousConfirmationGate] 记录。
 */
internal object AgentDangerousOperationPolicy {
    const val CONFIRM_CODE = "USER_CONFIRMATION_REQUIRED"
    const val READ_ONLY_CODE = "READ_ONLY_REVIEW_MODE"

    /** 判定一次调用是否危险（兼容旧调用方，语义等价于「需要确认」）。 */
    fun isDangerous(toolName: String, argumentsJson: String): Boolean =
        AgentRiskPolicy.assess(toolName, argumentsJson).requiresConfirmation

    /**
     * 按当前审查模式返回拒绝决策；放行时返回 null（交回调用方继续原有的准入检查）。
     * 低风险与纯读类动作在任何档位都不打扰（只读档同样放行低风险）。
     */
    fun decision(
        toolName: String,
        argumentsJson: String,
        mode: AgentToolReviewMode = AgentToolReviewMode.from(Prefs.toolReviewMode()),
    ): ToolExecutionDecision.Reject? =
        decide(toolName, argumentsJson, AgentRiskPolicy.assess(toolName, argumentsJson), mode)

    /**
     * 配方调用的判定：配方里的 Root shell 步骤也要过一遍命令启发式，
     * 否则模型可以写一个 `recipe_x` 把 `rm -rf` 包装成「无害工具」绕过确认。
     */
    fun decisionForRecipe(
        toolName: String,
        argumentsJson: String,
        shellCommands: List<String>,
        mode: AgentToolReviewMode = AgentToolReviewMode.from(Prefs.toolReviewMode()),
    ): ToolExecutionDecision.Reject? =
        decide(
            toolName,
            argumentsJson,
            AgentRiskPolicy.assessRecipe(toolName, argumentsJson, shellCommands),
            mode,
        )

    private fun decide(
        toolName: String,
        argumentsJson: String,
        assessment: AgentRiskAssessment,
        mode: AgentToolReviewMode,
    ): ToolExecutionDecision.Reject? {
        // 只读档：非低风险一律拒绝且不询问——它表达的是「这次只让我查」，不是「多问几次」。
        if (mode == AgentToolReviewMode.READONLY && assessment.level != AgentRiskLevel.LOW) {
            return ToolExecutionDecision.Reject(
                code = READ_ONLY_CODE,
                message = readOnlyMessage(toolName, assessment),
            )
        }
        if (!assessment.requiresConfirmation) return null
        // 自动档只免掉中风险；高风险与致命操作不可撤销，任何档位都始终确认。
        if (mode == AgentToolReviewMode.AUTO && assessment.level == AgentRiskLevel.MEDIUM) return null
        // 严格档：中风险逐次确认——不认「本会话允许」，也不把既有的会话授权顺手消费掉。
        val strictOnce = mode == AgentToolReviewMode.STRICT && assessment.level == AgentRiskLevel.MEDIUM
        val signature = AgentDangerousConfirmationGate.signature(toolName, argumentsJson)
        val granted = if (strictOnce) {
            AgentDangerousConfirmationGate.consumeOnceOnly(signature)
        } else {
            AgentDangerousConfirmationGate.consume(signature, assessment.scope)
        }
        if (granted) {
            AgentDangerousConfirmationGate.pruneExpired()
            return null
        }
        // 严格档的中风险按一次性授权走：待确认槽与文案都不能提供「本会话允许」选项。
        val effective = if (strictOnce) assessment.copy(scopeOverride = AgentConfirmationScope.ONCE) else assessment
        AgentDangerousConfirmationGate.expectConfirmation(signature, effective)
        AgentDangerousConfirmationGate.pruneExpired()
        return ToolExecutionDecision.Reject(
            code = CONFIRM_CODE,
            message = confirmationMessage(toolName, effective),
        )
    }

    /**
     * 只读档的拒绝文案。与确认文案的关键差异：不提供 ask_user 授权路径——
     * 只读表达的是「这次别做」，不是「问过我就可以做」。
     */
    private fun readOnlyMessage(toolName: String, assessment: AgentRiskAssessment): String {
        val consequences = assessment.reasons.joinToString("；").ifBlank { "该操作可能影响系统状态" }
        return "当前处于只读审查模式：「$toolName」不是只读操作（$consequences），已直接拦下，**未做任何改动**。" +
            "此模式下不会发起确认，也不要用 ask_user 请求授权、不要重试或换其它工具变相执行。" +
            "如实告知用户：需要执行该操作时，请在设置里把工具调用审查模式从「只读」切换到其它档位。"
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
