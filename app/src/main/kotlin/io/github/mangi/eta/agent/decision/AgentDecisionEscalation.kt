package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentToolGroups
import java.util.Locale

/**
 * 升级模型（escalation）的触发判定。
 *
 * **纯函数**：只依赖入参，不读 Prefs、不发请求、不看时间，便于直接单测。
 * 设计目标是「只在必要时多打一次模型」：
 * - 命中敏感组（device / calendar / extensions）：错判的代价高（设备操作、日程写入、
 *   扩展安装），值得用更大的模型复判一次；
 * - 输入包含高风险动作词（删除 / 卸载 / 转账 等）：规则无法覆盖所有说法，交给升级模型；
 * - `tool_groups` 置信度低于**该组自己的阈值**（M4）：主模型自己不确定时才升级；
 * - 历史漏判率高时下调阈值（升级更积极），长期零漏判时上调阈值（少付延迟）。
 *
 * 升级是 **expand 语义**：升级结果只会让 [AgentDecisionPlan.allowedGroups] 更大或更明确，
 * 不会放宽用户开关（gate 在 Router 里始终是上限），因此「多判一次」不改变正确性，
 * 只增加一次可选延迟（通常 1–3 秒，且仅命中条件时发生）。
 */
internal object AgentDecisionEscalation {

    /** 升级模型的实际调用参数；[modelId] 与主模型相同则不升级。 */
    data class Config(
        /** provider-model 记录 id，仅用于判重与日志。 */
        val modelId: String,
        val baseUrl: String,
        val apiKey: String,
        /** 0 = 不按置信度触发。 */
        val confidenceThresholdPct: Int,
        /** M4：本会话的历史样本数（真实决策次数）。 */
        val historySamples: Int = 0,
        /** M4：历史漏判率（0..1，见 [AgentDecisionTelemetry.Snapshot.missRate]）。 */
        val historyMissRate: Double = 0.0,
        /** 真正发往 `/v1/systemone` 的模型名；留空时退回 [modelId]。 */
        val modelName: String = "",
    )

    /**
     * [escalate] = false 时 [reason] 说明未升级的原因，仅用于日志。
     * [score] 是判据构成（阈值/组/历史样本），用于事后调参，不参与业务判断。
     */
    data class Decision(
        val escalate: Boolean,
        val reason: String,
        val score: String = "",
    )

    const val REASON_DISABLED = "disabled"
    const val REASON_NOT_CONFIGURED = "not_configured"
    const val REASON_SENSITIVE_GROUP = "sensitive_group"
    const val REASON_HIGH_RISK_HINT = "high_risk_hint"
    const val REASON_LOW_CONFIDENCE = "low_confidence"
    const val REASON_NOT_NEEDED = "not_needed"

    /** 历史样本少于这个数时不做加权，只记 insufficient_samples（避免小样本带偏阈值）。 */
    const val MIN_HISTORY_SAMPLES = 5
    const val HISTORY_MISS_RATE_HIGH = 0.30
    const val HISTORY_MISS_RATE_LOW = 0.05
    const val HISTORY_ADJUST_DOWN_PCT = 10
    const val HISTORY_ADJUST_UP_PCT = 10
    const val MIN_EFFECTIVE_THRESHOLD_PCT = 20
    const val MAX_EFFECTIVE_THRESHOLD_PCT = 95

    /**
     * 按组的置信度阈值（集中常量表）。
     *
     * 理由：
     * - device 85：重启/卸载/改系统状态不可逆，宁可用大模型多判一次；
     * - calendar 80：写日程、改提醒会直接影响用户真实安排；
     * - extensions 75：安装/启用扩展会改变能力边界；
     * - terminal 65：命令可能改动文件系统，但用户通常有明确意图；
     * - device_state 60 / personal 55：读写设备状态与个人数据，中等风险；
     * - screen 50：读屏/点击，可回退；
     * - web 40：只读网页、最便宜，过度升级的延迟最不划算。
     * 表里没有的组用设置页的全局阈值（默认 60）。
     */
    val GROUP_THRESHOLDS: Map<String, Int> = linkedMapOf(
        AgentToolGroups.DEVICE_GROUP.id to 85,
        AgentToolGroups.CALENDAR_GROUP.id to 80,
        AgentToolGroups.EXTENSIONS_GROUP.id to 75,
        AgentToolGroups.TERMINAL_GROUP.id to 65,
        AgentToolGroups.DEVICE_STATE_GROUP.id to 60,
        AgentToolGroups.PERSONAL_GROUP.id to 55,
        AgentToolGroups.SCREEN_GROUP.id to 50,
        AgentToolGroups.WEB_GROUP.id to 40,
    )

    /** 这些组的写操作/安装操作不可逆，错判代价高于一次额外请求。 */
    private val SENSITIVE_GROUPS: Set<String> = setOf(
        AgentToolGroups.DEVICE_GROUP.id,
        AgentToolGroups.CALENDAR_GROUP.id,
        AgentToolGroups.EXTENSIONS_GROUP.id,
    )

    /**
     * 高风险动作词表（中英混排）。
     *
     * 只做「词面命中」，不试图理解语义：命中即升级，宁可多判一次，不擅自执行危险操作。
     * 词条要足够具体，避免「设置」这类高频词把所有设备问题都升级。
     */
    private val HIGH_RISK_KEYWORDS: List<String> = listOf(
        "删除", "删掉", "卸载", "重启", "关机", "格式化", "恢复出厂", "清空", "转账", "支付",
        "付款", "汇款", "发送短信", "拨号", "解除", "取消所有", "批量",
        "delete", "uninstall", "reboot", "shutdown", "format", "factory reset", "wipe",
        "transfer", "pay", "payment", "send sms", "dial", "bulk",
    )

    fun decide(
        enabled: Boolean,
        escalationModelId: String,
        primaryModelId: String,
        needsTools: Double,
        selectedGroups: Set<String>,
        confidence: Double,
        input: String,
        confidenceThresholdPct: Int,
        historySamples: Int = 0,
        historyMissRate: Double = 0.0,
    ): Decision {
        if (!enabled) return Decision(escalate = false, reason = REASON_DISABLED)
        if (escalationModelId.isBlank() || escalationModelId == primaryModelId) {
            return Decision(escalate = false, reason = REASON_NOT_CONFIGURED)
        }
        if (selectedGroups.any { it in SENSITIVE_GROUPS }) {
            return Decision(
                escalate = true,
                reason = REASON_SENSITIVE_GROUP,
                score = "sensitive=${selectedGroups.filter { it in SENSITIVE_GROUPS }.sorted()}",
            )
        }
        if (containsHighRiskKeyword(input)) {
            return Decision(escalate = true, reason = REASON_HIGH_RISK_HINT)
        }
        if (confidenceThresholdPct <= 0) {
            return Decision(escalate = false, reason = REASON_NOT_NEEDED)
        }
        val confidencePct = normalizedConfidence(confidence) * 100.0
        val threshold = effectiveThreshold(selectedGroups, confidenceThresholdPct, historySamples, historyMissRate)
        val score = buildString {
            append("group=")
            append(threshold.groupId ?: "none")
            append(" base=")
            append(confidenceThresholdPct)
            append(" threshold=")
            append(threshold.value)
            append(" confidence=")
            append(String.format(Locale.ROOT, "%.2f", confidencePct))
            append(" history=")
            append(threshold.historyNote)
        }
        val escalate = needsTools >= AgentDecisionEvaluator.NEEDS_TOOLS_THRESHOLD &&
            confidence.isFinite() &&
            confidencePct < threshold.value
        return Decision(
            escalate = escalate,
            reason = if (escalate) REASON_LOW_CONFIDENCE else REASON_NOT_NEEDED,
            score = score,
        )
    }

    /** 生效阈值：最敏感的已选组优先；历史样本足够时才 ±10 调整。 */
    private data class Threshold(val groupId: String?, val value: Int, val historyNote: String)

    private fun effectiveThreshold(
        selectedGroups: Set<String>,
        defaultPct: Int,
        historySamples: Int,
        historyMissRate: Double,
    ): Threshold {
        val groupId = selectedGroups.filter { it in GROUP_THRESHOLDS }.maxByOrNull { GROUP_THRESHOLDS.getValue(it) }
        val base = groupId?.let { GROUP_THRESHOLDS.getValue(it) } ?: defaultPct
        if (historySamples < MIN_HISTORY_SAMPLES) {
            return Threshold(groupId, base, "insufficient_samples(n=$historySamples)")
        }
        val delta = when {
            historyMissRate >= HISTORY_MISS_RATE_HIGH -> -HISTORY_ADJUST_DOWN_PCT
            historyMissRate <= HISTORY_MISS_RATE_LOW -> HISTORY_ADJUST_UP_PCT
            else -> 0
        }
        val value = (base + delta).coerceIn(MIN_EFFECTIVE_THRESHOLD_PCT, MAX_EFFECTIVE_THRESHOLD_PCT)
        val note = "samples=$historySamples,miss_rate=" +
            String.format(Locale.ROOT, "%.2f", historyMissRate) + ",delta=$delta"
        return Threshold(groupId, value, note)
    }

    /** API 的 `confidence` 可能是 0..1 也可能被写成 0..100；统一归一到 0..1 再比较。 */
    private fun normalizedConfidence(value: Double): Double =
        if (value > 1.0) (value / 100.0).coerceIn(0.0, 1.0) else value.coerceIn(0.0, 1.0)

    private fun containsHighRiskKeyword(input: String): Boolean {
        val lowered = input.lowercase(Locale.ROOT)
        return HIGH_RISK_KEYWORDS.any { keyword -> lowered.contains(keyword.lowercase(Locale.ROOT)) }
    }
}
