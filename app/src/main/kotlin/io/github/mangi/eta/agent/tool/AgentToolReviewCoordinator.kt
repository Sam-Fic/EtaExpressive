package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.decision.AgentDecisionAnswer
import io.github.mangi.eta.agent.decision.AgentDecisionClientConfig
import io.github.mangi.eta.agent.decision.AgentDecisionEndpoint
import io.github.mangi.eta.agent.decision.AgentDecisionModelClient
import io.github.mangi.eta.agent.decision.AgentDecisionOutcome
import io.github.mangi.eta.agent.decision.AgentDecisionQuestion
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ModelFeatureCompletion
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一次模型审查的结论。
 *
 * [Fallback] 不是模型给出的判定，而是「没拿到可信判定」：超时、网络错、
 * 配置缺失、解析失败都收敛到这里。调用方据此回退标准档的规则确认，
 * 绝不能把失败当成放行。
 */
internal sealed interface AgentToolReviewVerdict {
    data class Allow(val reason: String, val engine: String, val latencyMs: Long) : AgentToolReviewVerdict

    data class Deny(val reason: String, val engine: String, val latencyMs: Long) : AgentToolReviewVerdict

    data class Fallback(val detail: String, val latencyMs: Long) : AgentToolReviewVerdict
}

/**
 * 智能审查档的模型裁决入口（对齐 ZCode「auto 模式」留给模型判断的设想，但落到
 * Eta 的现有设施上）。两种引擎二选一、各自配置：
 *
 * - [Engine.LLM]：普通对话模型经 [ModelFeatureCompletion] 输出 JSON
 *   `{decision:"allow"|"deny", reason:"..."}`；模型缺省时回退到当前对话模型。
 * - [Engine.DECISION]：复用判断模型的 `/v1/systemone` 客户端
 *   （[AgentDecisionModelClient] + [AgentDecisionQuestion.Choice]），
 *   provider/model/端点全部沿用判断模型设置，不另配。
 *
 * 约束（与 decision 子系统一致）：
 * - 工具执行在普通 `Thread` 上且只读工具可能并发执行，所以这里用 [runBlocking]
 *   桥接 suspend 客户端、缓存用并发结构、整体过程无共享可变状态；
 * - 每次调用自带超时（[Prefs.toolReviewTimeoutMs]），失败一律 [AgentToolReviewVerdict.Fallback]；
 * - 只对中风险以上调用使用（由 [AgentDangerousOperationPolicy] 决定），低风险/纯读不花模型往返。
 */
internal object AgentToolReviewCoordinator {
    private const val ENGINE_LLM = "llm"
    private const val ENGINE_DECISION = "decision"
    private const val DECISION_QUESTION_ID = "tool_call_review"
    private const val CACHE_MAX = 64

    /** 进程级缓存：同一个（引擎、工具名、参数、风险级别）只问一次模型；参数变了重新问。 */
    private val cache = object : LinkedHashMap<String, AgentToolReviewVerdict>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AgentToolReviewVerdict>?): Boolean =
            size > CACHE_MAX
    }

    fun clearCache() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * 审查一次工具调用。[fallbackModelConfig] 是 LLM 引擎未单独配模型时使用的当前对话模型；
     * [controller] 用于响应任务取消/暂停；[sessionId] 供辅助模型请求记账。
     */
    fun review(
        toolName: String,
        argumentsJson: String,
        riskLevel: String,
        riskReasons: List<String>,
        conversationContext: String,
        fallbackModelConfig: AgentModelClient.ModelConfig,
        controller: AgentRunController,
        sessionId: String,
    ): AgentToolReviewVerdict {
        val engineValue = Prefs.toolReviewEngine()
        val engine = if (engineValue == Prefs.Keys.REVIEW_ENGINE_DECISION) ENGINE_DECISION else ENGINE_LLM
        val key = "$engine\u0000${toolName.trim().lowercase()}\u0000$argumentsJson\u0000$riskLevel"
        synchronized(cache) { cache[key] }?.let { return it }
        val startedAt = System.currentTimeMillis()
        val verdict = try {
            if (controller.isCancelled) {
                AgentToolReviewVerdict.Fallback("run_cancelled", 0)
            } else {
                val timeoutMs = Prefs.toolReviewTimeoutMs()
                runBlocking {
                    withTimeoutOrNull(timeoutMs.toLong()) {
                        if (engine == ENGINE_DECISION) {
                            reviewWithDecisionModel(
                                toolName, argumentsJson, riskLevel, riskReasons, conversationContext, timeoutMs,
                            )
                        } else {
                            reviewWithLlm(
                                toolName, argumentsJson, riskLevel, riskReasons, conversationContext,
                                fallbackModelConfig, controller, sessionId, timeoutMs,
                            )
                        }
                    } ?: AgentToolReviewVerdict.Fallback(
                        "timeout_${timeoutMs}ms",
                        System.currentTimeMillis() - startedAt,
                    )
                }
            }
        } catch (_: Throwable) {
            // 审查永远不能因为自身异常打断工具流程；异常一律收敛为回退。
            AgentToolReviewVerdict.Fallback("exception", System.currentTimeMillis() - startedAt)
        }
        // Fallback 不缓存：下次模型可用时应重新判定，不能把一次失败固化成永久回退。
        if (verdict !is AgentToolReviewVerdict.Fallback) {
            synchronized(cache) { cache[key] = verdict }
        }
        return verdict
    }

    // ── 引擎一：decision 模型（/v1/systemone） ────────────────────────────────

    private suspend fun reviewWithDecisionModel(
        toolName: String,
        argumentsJson: String,
        riskLevel: String,
        riskReasons: List<String>,
        conversationContext: String,
        timeoutMs: Int,
    ): AgentToolReviewVerdict {
        val providerId = Prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID)
        val modelId = Prefs.getString(Prefs.Keys.AGENT_DECISION_MODEL_ID)
        if (providerId.isBlank() || modelId.isBlank()) {
            return AgentToolReviewVerdict.Fallback("decision_model_not_configured", 0)
        }
        val protocol = Prefs.getString(
            Prefs.Keys.AGENT_DECISION_PROTOCOL,
            Prefs.Keys.AGENT_DECISION_PROTOCOL_SYSTEMONE,
        )
        if (protocol != Prefs.Keys.AGENT_DECISION_PROTOCOL_SYSTEMONE) {
            return AgentToolReviewVerdict.Fallback("decision_protocol_$protocol", 0)
        }
        val runtime = RuntimeConfigRepository.configForProviderAndModel(providerId, modelId)
            ?: return AgentToolReviewVerdict.Fallback("decision_config_unavailable", 0)
        val endpointOverride = Prefs.getString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE)
        val endpoint = when (val resolution = AgentDecisionEndpoint.normalize(runtime.baseUrl, endpointOverride)) {
            is AgentDecisionEndpoint.Resolution.Success -> resolution.url
            is AgentDecisionEndpoint.Resolution.Invalid ->
                return AgentToolReviewVerdict.Fallback("decision_endpoint_${resolution.reason}", 0)
        }
        val state = buildState(
            toolName, argumentsJson, riskLevel, riskReasons, conversationContext,
            includeCurrentTime = true,
        )
        val question = AgentDecisionQuestion.Choice(
            id = DECISION_QUESTION_ID,
            instructions = "Decide whether this single tool call is safe to execute right now. " +
                "Deny only when it is clearly irreversible, destructive beyond the user's request, " +
                "targets credentials/private data without justification, or contradicts the conversation. " +
                "Allow ordinary write operations that the user's request implies.",
            criteria = mapOf(
                "allow" to "The call is safe and consistent with the user's request.",
                "deny" to "The call is unsafe, out of scope, or needs explicit user confirmation first.",
            ),
        )
        val config = AgentDecisionClientConfig(
            baseUrl = endpoint,
            apiKey = runtime.apiKey,
            model = runtime.model,
            timeoutMs = timeoutMs.coerceIn(500, 10_000),
        )
        val startedAt = System.currentTimeMillis()
        return when (val outcome = AgentDecisionModelClient.complete(config, state, listOf(question))) {
            is AgentDecisionOutcome.Success -> {
                val answer = outcome.response.answers[DECISION_QUESTION_ID] as? AgentDecisionAnswer.Choice
                val choice = answer?.choice
                val reason = answer?.confidence?.let { "置信度=${"%.2f".format(it)}" } ?: "模型未给出理由"
                when (choice) {
                    "allow" -> AgentToolReviewVerdict.Allow(
                        reason, ENGINE_DECISION, System.currentTimeMillis() - startedAt,
                    )
                    "deny" -> AgentToolReviewVerdict.Deny(
                        reason, ENGINE_DECISION, System.currentTimeMillis() - startedAt,
                    )
                    else -> AgentToolReviewVerdict.Fallback(
                        "decision_answer_missing", System.currentTimeMillis() - startedAt,
                    )
                }
            }
            is AgentDecisionOutcome.Failure -> AgentToolReviewVerdict.Fallback(
                "decision_${outcome.reason}", System.currentTimeMillis() - startedAt,
            )
        }
    }

    // ── 引擎二：LLM 审查（普通对话模型 + JSON 输出） ───────────────────────────

    private fun reviewWithLlm(
        toolName: String,
        argumentsJson: String,
        riskLevel: String,
        riskReasons: List<String>,
        conversationContext: String,
        fallbackModelConfig: AgentModelClient.ModelConfig,
        controller: AgentRunController,
        sessionId: String,
        timeoutMs: Int,
    ): AgentToolReviewVerdict {
        val providerId = Prefs.toolReviewLlmProviderId()
        val modelId = Prefs.toolReviewLlmModelId()
        val modelConfig = if (providerId.isNotBlank() && modelId.isNotBlank()) {
            runBlocking {
                RuntimeConfigRepository.configForProviderAndModel(providerId, modelId)
            }
        } else {
            null
        } ?: fallbackModelConfig
        val state = buildState(
            toolName, argumentsJson, riskLevel, riskReasons, conversationContext,
            includeCurrentTime = false,
        )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", LLM_SYSTEM_PROMPT))
            .put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Tool call to review:\n" + state.toString(2),
                ),
            )
        val startedAt = System.currentTimeMillis()
        val raw = try {
            ModelFeatureCompletion.complete(
                config = modelConfig,
                messages = messages,
                controller = controller,
                sessionId = sessionId,
                timeoutMs = timeoutMs.toLong(),
                outputLimit = 1024,
            )
        } catch (_: Throwable) {
            return AgentToolReviewVerdict.Fallback(
                "llm_request_failed", System.currentTimeMillis() - startedAt,
            )
        }
        return parseLlmVerdict(raw, startedAt)
    }

    /** LLM 输出解析：容忍 ```json 围栏、前后解释文字，但只认一个严格 JSON 对象。 */
    internal fun parseLlmVerdict(raw: String, startedAt: Long = System.currentTimeMillis()): AgentToolReviewVerdict {
        val latency = System.currentTimeMillis() - startedAt
        val json = extractJsonObject(raw)
            ?: return AgentToolReviewVerdict.Fallback("llm_unparseable", latency)
        val decision = json.optString("decision").trim().lowercase(Locale.US)
        val reason = json.optString("reason").trim().take(300)
        return when (decision) {
            "allow" -> AgentToolReviewVerdict.Allow(reason.ifBlank { "model_allow" }, ENGINE_LLM, latency)
            "deny" -> AgentToolReviewVerdict.Deny(reason.ifBlank { "模型判定该调用不安全" }, ENGINE_LLM, latency)
            else -> AgentToolReviewVerdict.Fallback("llm_unknown_decision", latency)
        }
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
        // 优先整段解析；失败时截取第一个平衡的 {...} 段（模型常在 JSON 前后加解释）。
        runCatching { JSONObject(cleaned) }.getOrNull()?.let { return it }
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until cleaned.length) {
            val ch = cleaned[index]
            if (escaped) { escaped = false; continue }
            if (inString && ch == '\\') { escaped = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) {
                    return runCatching { JSONObject(cleaned.substring(start, index + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    private fun buildState(
        toolName: String,
        argumentsJson: String,
        riskLevel: String,
        riskReasons: List<String>,
        conversationContext: String,
        includeCurrentTime: Boolean,
    ): JSONObject = JSONObject()
        .put("tool_name", toolName)
        .put("arguments", argumentsJson.take(4000))
        .put("rule_risk_level", riskLevel)
        .put("rule_risk_reasons", JSONArray(riskReasons.take(6)))
        .put("recent_user_request", conversationContext.take(1500))
        .apply {
            if (includeCurrentTime) {
                put("current_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            }
        }

    private const val LLM_SYSTEM_PROMPT =
        "You are a tool-call safety reviewer for an on-device AI assistant. " +
            "You receive ONE tool call (name + arguments), a rule-based risk assessment, and the recent user request. " +
            "Decide whether executing this call right now is safe and consistent with what the user asked.\n" +
            "Reply with ONLY a JSON object, no markdown fences, no extra text:\n" +
            "{\"decision\":\"allow\"|\"deny\",\"reason\":\"short Chinese or English explanation\"}\n" +
            "Deny only when the call is clearly irreversible/destructive beyond the request, " +
            "touches credentials or private data without justification, or contradicts the user's intent. " +
            "Allow ordinary writes implied by the request. When uncertain, deny."
}
