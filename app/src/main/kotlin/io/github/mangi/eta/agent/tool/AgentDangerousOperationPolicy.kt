package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.config.Prefs

/**
 * 工具调用审查模式（对齐 ZCode 的权限模式光谱，映射到 Eta 的风险阶梯）。
 * 字符串值与 [io.github.mangi.eta.config.Prefs] 的 Keys 常量一一对应。
 */
internal enum class AgentToolReviewMode {
    /** 只读：非低风险调用直接拒绝，不询问（对齐 ZCode plan）。 */
    READONLY,

    /** 标准（默认）：中风险本会话内确认一次，高/致命每次确认。 */
    STANDARD,

    /** 智能审查：中风险以上调用交给独立模型逐次判定 allow/deny，失败回退标准档规则确认。 */
    SMART,

    /** 完全放行：任何调用都不确认（含高/致命）——用户在设置里显式交出的底线豁免，其余档位不受影响。 */
    OFF,
    ;

    companion object {
        /** 退役值（strict / auto）已并入标准档：[Prefs.toolReviewMode] 读时已映射，这里对裸字符串兜底收编。 */
        fun from(prefsValue: String): AgentToolReviewMode = when (prefsValue) {
            "readonly" -> READONLY
            "smart" -> SMART
            "off" -> OFF
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
 * - 标准（默认）：中风险本会话内确认一次，高/致命每次确认；
 * - 智能审查：中风险以上交 [AgentToolReviewCoordinator] 逐次裁决（LLM 或 decision 模型），
 *   模型 allow 则放行、deny 则拦下、失败/超时回退标准档的规则确认（绝不直接放行）；
 * - 完全放行：不做任何确认——包括高/致命。这是用户在设置里显式交出的底线豁免，
 *   其余档位「高/致命始终确认」的约定不变。
 *
 * 确认交互由**模型自己用 ask_user 问**，复用 [ToolExecutionDecision.Reject]：
 * 命中确认条件时拒绝本次调用并回 [CONFIRM_CODE]，文案要求模型「先用 ask_user 取得同意、
 * 再重发同一个调用」。一次性/会话授权由 [AgentDangerousConfirmationGate] 记录。
 */
internal object AgentDangerousOperationPolicy {
    const val CONFIRM_CODE = "USER_CONFIRMATION_REQUIRED"
    const val READ_ONLY_CODE = "READ_ONLY_REVIEW_MODE"
    const val SMART_DENY_CODE = "SMART_REVIEW_DENIED"

    /** 判定一次调用是否危险（兼容旧调用方，语义等价于「需要确认」）。 */
    fun isDangerous(toolName: String, argumentsJson: String): Boolean =
        AgentRiskPolicy.assess(toolName, argumentsJson).requiresConfirmation

    /**
     * 按当前审查模式返回拒绝决策；放行时返回 null（交回调用方继续原有的准入检查）。
     * 低风险与纯读类动作在任何档位都不打扰（只读档同样放行低风险）。
     *
     * [runId]/[conversationId] 进入确认闸门的槽位与授权键（按 run 等确认、按会话授权）；
     * [review] 是智能审查档的模型裁决闭包（由调用方按 run 捕获上下文传入——曾用进程级
     * var 桥注入，多 run 并发时以最后一次注入为准，别的 run 会拿错裁决上下文）。
     * 返回 null 表示「没拿到模型结论」，策略据此回退标准档规则确认，绝不直接放行。
     */
    fun decision(
        toolName: String,
        argumentsJson: String,
        mode: AgentToolReviewMode = AgentToolReviewMode.from(Prefs.toolReviewMode()),
        runId: String = "",
        conversationId: String = "",
        review: ((toolName: String, argumentsJson: String, assessment: AgentRiskAssessment) -> AgentToolReviewVerdict?)? = null,
    ): ToolExecutionDecision.Reject? =
        decide(toolName, argumentsJson, AgentRiskPolicy.assess(toolName, argumentsJson), mode, runId, conversationId, review)

    /**
     * 配方调用的判定：配方里的 Root shell 步骤也要过一遍命令启发式，
     * 否则模型可以写一个 `recipe_x` 把 `rm -rf` 包装成「无害工具」绕过确认。
     */
    fun decisionForRecipe(
        toolName: String,
        argumentsJson: String,
        shellCommands: List<String>,
        mode: AgentToolReviewMode = AgentToolReviewMode.from(Prefs.toolReviewMode()),
        runId: String = "",
        conversationId: String = "",
        review: ((toolName: String, argumentsJson: String, assessment: AgentRiskAssessment) -> AgentToolReviewVerdict?)? = null,
    ): ToolExecutionDecision.Reject? =
        decide(
            toolName,
            argumentsJson,
            AgentRiskPolicy.assessRecipe(toolName, argumentsJson, shellCommands),
            mode,
            runId,
            conversationId,
            review,
        )

    private fun decide(
        toolName: String,
        argumentsJson: String,
        assessment: AgentRiskAssessment,
        mode: AgentToolReviewMode,
        runId: String,
        conversationId: String,
        review: ((toolName: String, argumentsJson: String, assessment: AgentRiskAssessment) -> AgentToolReviewVerdict?)?,
    ): ToolExecutionDecision.Reject? {
        // 只读档：非低风险一律拒绝且不询问——它表达的是「这次只让我查」，不是「多问几次」。
        if (mode == AgentToolReviewMode.READONLY && assessment.level != AgentRiskLevel.LOW) {
            return ToolExecutionDecision.Reject(
                code = READ_ONLY_CODE,
                message = readOnlyMessage(toolName, assessment),
            )
        }
        // 完全放行档：不做任何确认（含高/致命）。这是用户显式选择越过的安全底线，
        // 其余档位的高/致命确认不受影响；工具能力开关等准入检查在调用方照常生效。
        if (mode == AgentToolReviewMode.OFF) return null
        if (!assessment.requiresConfirmation) return null
        // 智能审查档：中风险以上（这里正是 requiresConfirmation 的范围）交模型逐次裁决。
        // 失败/超时/未注入裁决闭包都回退到下面的标准档规则确认，绝不直接放行。
        if (mode == AgentToolReviewMode.SMART) {
            val verdict = try {
                review?.invoke(toolName, argumentsJson, assessment)
            } catch (_: Throwable) {
                null
            }
            when (verdict) {
                is AgentToolReviewVerdict.Allow -> return null
                is AgentToolReviewVerdict.Deny -> return ToolExecutionDecision.Reject(
                    code = SMART_DENY_CODE,
                    message = smartDenyMessage(toolName, assessment, verdict),
                )
                else -> Unit // Fallback / null：落到标准档规则确认
            }
        }
        val signature = AgentDangerousConfirmationGate.signature(toolName, argumentsJson)
        val granted = AgentDangerousConfirmationGate.consume(conversationId, runId, signature, assessment.scope)
        if (granted) {
            AgentDangerousConfirmationGate.pruneExpired()
            return null
        }
        AgentDangerousConfirmationGate.expectConfirmation(runId, conversationId, signature, assessment)
        AgentDangerousConfirmationGate.pruneExpired()
        return ToolExecutionDecision.Reject(
            code = CONFIRM_CODE,
            message = confirmationMessage(toolName, assessment),
        )
    }

    /** 智能审查档的模型否决文案：与规则确认不同，这里不提供 ask_user 授权路径。 */
    private fun smartDenyMessage(
        toolName: String,
        assessment: AgentRiskAssessment,
        verdict: AgentToolReviewVerdict.Deny,
    ): String {
        val consequences = assessment.reasons.joinToString("；").ifBlank { "该操作可能影响系统状态" }
        return "智能审查（${verdict.engine}）判定「$toolName」本次不应执行，已拦下，**未做任何改动**。" +
            "规则风险：$consequences。模型理由：${verdict.reason}。" +
            "不要重试同一个调用，也不要换其它工具变相执行；" +
            "请如实告知用户该调用被审查拦下，必要时请用户调整请求或切换审查模式。"
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
