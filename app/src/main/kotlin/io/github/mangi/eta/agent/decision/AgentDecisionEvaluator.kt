package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.model.AgentToolGroup
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 判断模型请求的共享执行器。
 *
 * 影子模式（M1，[AgentDecisionShadow]）与接管模式（M2，[AgentDecisionRouter]）都要走
 * 同一套 state 组织、端点归一、请求与解析，避免两条链路对契约的理解漂移。
 *
 * 所有失败都在这里收敛成 [AgentDecisionEvaluation] 字段，不抛到主流程。
 */
internal object AgentDecisionEvaluator {

    private const val MAX_INPUT_CHARS = 4000
    private const val MAX_TURN_CHARS = 300
    private const val MAX_HISTORY_CHARS = 4000

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** 一次判断的完整结果；`skipReason` / `failureReason` 为 null 才代表拿到有效答案。 */
    data class AgentDecisionEvaluation(
        val modelId: String,
        val endpoint: String?,
        val endpointFailure: String?,
        val skipReason: String?,
        val failureReason: String?,
        val detail: String,
        val httpStatus: Int?,
        val errorCode: Int?,
        val errorDesc: String,
        val latencyMs: Long,
        val needsTools: Double,
        val selectedGroups: Set<String>,
        /**
         * 唤起即屏幕上下文（助手弹窗 run 专属问题）：回答是否依赖看到用户当前手机屏幕。
         * 非弹窗 run 恒为 false（问题根本不发问——硬边界）。
         */
        val needsScreenContext: Boolean = false,
        val layers: Int,
        val usageIn: Int?,
        val usageOut: Int?,
        val stateChars: Int,
        val historyTurnCount: Int,
        /** `tool_groups` 的原始置信度（0..1 或 0..100，仅用于触发判定与日志）。 */
        val toolGroupsConfidence: Double = 0.0,
        /** 是否真的用升级模型复判过（主模型失败时不会升级）。 */
        val escalated: Boolean = false,
        /** 升级触发/未触发原因，见 [AgentDecisionEscalation]。 */
        val escalationReason: String? = null,
        val escalationModelId: String? = null,
        val escalationLatencyMs: Long = 0,
        /** 升级失败原因；此时 [AgentDecisionEvaluation] 回退到主模型结果，不整体失败。 */
        val escalationFailure: String? = null,
        /** M4：触发判据构成（阈值/组/历史样本），仅用于调参日志。 */
        val escalationScore: String? = null,
    ) {
        val succeeded: Boolean get() = skipReason == null && failureReason == null
    }

    /**
     * 组织 state 并请求 `/v1/systemone`。
     *
     * [groups] 是本轮 gate 允许的延迟组，既是 `available_groups` 的唯一来源，
     * 也是 `multi-choice` 的合法标签集；为空时不发请求（没有合法选项）。
     */
    fun evaluate(
        request: AgentRuntimeWire.RunRequest,
        /** provider-model 数据库记录 id，仅用于诊断与升级判重，不发到服务端。 */
        modelId: String,
        providerBaseUrl: String,
        apiKey: String,
        timeoutMs: Int,
        historyTurns: Int,
        endpointOverride: String,
        groups: List<AgentToolGroup>,
        stickyGroups: Set<String>,
        /** 工具回填后的续跑轮次；只影响 state 字段，便于判断模型理解上下文。 */
        isContinuation: Boolean = false,
        /**
         * 助手弹窗 run：true 时才追加 `needs_screen_context` 问题（唤起即屏幕上下文）。
         * 主程序 run 硬边界——这个问题根本不发问（用户定稿，非"问了不消费"）。
         */
        popupRun: Boolean = false,
        /** 非 null 且主模型成功时才可能升级；升级失败会回退主模型结果。 */
        escalation: AgentDecisionEscalation.Config? = null,
        /** 真正发往 `/v1/systemone` 的模型名（provider 记录里的 API 名）；留空时退回 [modelId]。 */
        modelName: String = "",
    ): AgentDecisionEvaluation {
        val requestModel = modelName.ifBlank { modelId }
        val resolution = AgentDecisionEndpoint.normalize(providerBaseUrl, endpointOverride)
        val endpoint = (resolution as? AgentDecisionEndpoint.Resolution.Success)?.url
        if (endpoint == null) {
            val reason = (resolution as? AgentDecisionEndpoint.Resolution.Invalid)?.reason
                ?: AgentDecisionEndpoint.REASON_MALFORMED
            return blank(modelId, skipReason = "invalid_endpoint:$reason", endpointFailure = reason)
        }
        if (apiKey.isBlank()) {
            return blank(modelId, skipReason = "missing_api_key", endpoint = endpoint)
        }
        if (groups.isEmpty()) {
            return blank(modelId, skipReason = "no_available_groups", endpoint = endpoint)
        }

        val turns = trimHistory(recentTurns(request.history, historyTurns), MAX_HISTORY_CHARS)
        val state = JSONObject()
            .put("user_input", truncate(request.prompt, MAX_INPUT_CHARS))
            .put("current_time", ZonedDateTime.now().format(timeFormatter))
            .put("recent_turns", JSONArray().also { array ->
                turns.forEach { message ->
                    array.put(
                        JSONObject()
                            .put("role", message.role)
                            .put("text", truncate(message.content, MAX_TURN_CHARS)),
                    )
                }
            })
            .put("available_groups", JSONArray().also { array ->
                groups.forEach { group ->
                    array.put(JSONObject().put("id", group.id).put("description", group.summary))
                }
            })
            .put("sticky_groups", JSONArray().also { array ->
                stickyGroups.sorted().forEach { group -> array.put(group) }
            })
            .put("is_tool_loop_continuation", isContinuation)
            .put("has_attached_image", request.images.isNotEmpty())
        val stateChars = state.toString().length

        val questions = buildList {
            add(
                AgentDecisionQuestion.Noul(
                    id = "needs_tools",
                    instructions = "Decide whether the user's request needs at least one tool call to be completed.",
                    // 执行类意图是最高频的误判源（2026-11-14 日志取证：「运行一个空的等待程序」
                    // 被判闲聊，主模型只能谎称没有终端工具）。这里把「要运行/执行某个程序、
                    // 脚本或命令」显式写进 true 判据，并要求含糊时偏向 true——漏判的代价
                    // （模型被锁死在无工具状态）远高于误判（多开一组 schema）。
                    trueCriterion = "The request requires executing an action, reading device/app data, " +
                        "running commands, browsing, or any other tool to answer correctly. " +
                        "Requests that ask to run, execute, or test a program, script, or command — " +
                        "even without naming a specific tool — always count as true.",
                    falseCriterion = "The request can be answered from general knowledge or the " +
                        "conversation itself, with plain text only. Judge false only when the request " +
                        "is clearly a question, explanation, or casual chat; when ambiguous, lean true.",
                ),
            )
            add(
                AgentDecisionQuestion.MultiChoice(
                    id = "tool_groups",
                    instructions = "Select every tool group that must be available to complete the request. " +
                        "Select no group when the request is pure chat or general knowledge; " +
                        "when unsure, prefer selecting the most likely group over selecting none.",
                    criteria = groups.associate { it.id to it.summary },
                ),
            )
            if (popupRun) {
                // 唤起即屏幕上下文（助手弹窗 run 专属）：入口截图未被手动选中时，由判断模型
                // 决定是否依赖「看到当前屏幕」。命中后运行侧预开屏幕组并注入 observe_screen
                // 首轮引导；主程序 run 不发问（硬边界）。
                add(
                    AgentDecisionQuestion.Noul(
                        id = "needs_screen_context",
                        instructions = "Decide whether answering the user's request requires SEEING the " +
                            "current content of the user's phone screen.",
                        trueCriterion = "The request asks about, reads, translates, summarizes, or acts " +
                            "on what is currently shown on the phone screen (visible app content, " +
                            "messages, schedules, etc.), and no screenshot is attached to the message.",
                        falseCriterion = "The request can be answered without seeing the screen, or a " +
                            "screenshot is already attached to the message.",
                    ),
                )
            }
        }

        // 升级模型走同一端点覆盖规则；provider 不同时用升级模型自己的 baseUrl。
        // 自定义端点是按**主 provider** 配的：升级模型若来自另一个 provider，
        // 覆盖地址很可能不服务该模型，因此只对同 baseUrl 的升级配置生效。
        val escalationOverride = if (escalation?.baseUrl == providerBaseUrl) endpointOverride else ""
        val escalationEndpoint = escalation?.let {
            (AgentDecisionEndpoint.normalize(it.baseUrl, escalationOverride)
                as? AgentDecisionEndpoint.Resolution.Success)?.url
        } ?: endpoint

        var outcome: AgentDecisionOutcome = call(endpoint, apiKey, requestModel, timeoutMs, state, questions)
        var escalated = false
        var escalationReason: String? = null
        var escalationLatencyMs = 0L
        var escalationFailure: String? = null
        var escalationScore: String? = null
        var confidence = 0.0
        // 主调用的耗时单独保留：升级成功后 outcome 会被替换，不能把 escalation 的耗时当成整体耗时。
        var primaryLatencyMs = 0L
        if (outcome is AgentDecisionOutcome.Success) {
            primaryLatencyMs = outcome.response.latencyMs
            confidence = (outcome.response.answers["tool_groups"] as? AgentDecisionAnswer.MultiChoice)
                ?.confidence ?: 0.0
            if (escalation != null) {
                val answer = outcome.response.answers["tool_groups"] as? AgentDecisionAnswer.MultiChoice
                val needsTools = (outcome.response.answers["needs_tools"] as? AgentDecisionAnswer.Noul)
                    ?.probability ?: 0.0
                val primarySelected = if (needsTools >= NEEDS_TOOLS_THRESHOLD) {
                    answer?.choices.orEmpty()
                } else {
                    emptySet()
                }
                val decision = AgentDecisionEscalation.decide(
                    enabled = true,
                    escalationModelId = escalation.modelId,
                    primaryModelId = modelId,
                    needsTools = needsTools,
                    selectedGroups = primarySelected,
                    confidence = confidence,
                    input = request.prompt,
                    confidenceThresholdPct = escalation.confidenceThresholdPct,
                    historySamples = escalation.historySamples,
                    historyMissRate = escalation.historyMissRate,
                )
                escalationReason = decision.reason
                escalationScore = decision.score.ifBlank { null }
                if (decision.escalate) {
                    escalated = true
                    val escalatedOutcome = call(
                        escalationEndpoint,
                        escalation.apiKey,
                        escalation.modelName.ifBlank { escalation.modelId },
                        timeoutMs,
                        state,
                        questions,
                    )
                    escalationLatencyMs = when (escalatedOutcome) {
                        is AgentDecisionOutcome.Success -> escalatedOutcome.response.latencyMs
                        is AgentDecisionOutcome.Failure -> escalatedOutcome.latencyMs
                    }
                    // expand 语义：升级成功才替换答案；失败保留主模型结果，整体仍然成功。
                    if (escalatedOutcome is AgentDecisionOutcome.Success) {
                        outcome = escalatedOutcome
                        confidence = (escalatedOutcome.response.answers["tool_groups"]
                            as? AgentDecisionAnswer.MultiChoice)?.confidence ?: confidence
                    } else {
                        escalationFailure = (escalatedOutcome as? AgentDecisionOutcome.Failure)?.reason
                    }
                }
            }
        }

        return when (val finalOutcome = outcome) {
            is AgentDecisionOutcome.Failure -> AgentDecisionEvaluation(
                modelId = modelId,
                endpoint = endpoint,
                endpointFailure = null,
                skipReason = null,
                failureReason = finalOutcome.reason,
                detail = finalOutcome.detail,
                httpStatus = finalOutcome.httpStatus,
                errorCode = finalOutcome.errorCode,
                errorDesc = finalOutcome.errorDesc,
                latencyMs = finalOutcome.latencyMs,
                needsTools = 0.0,
                selectedGroups = emptySet(),
                layers = 0,
                usageIn = null,
                usageOut = null,
                stateChars = stateChars,
                historyTurnCount = turns.size,
                toolGroupsConfidence = confidence,
                escalated = escalated,
                escalationReason = escalationReason,
                escalationModelId = escalation?.modelId,
                escalationLatencyMs = escalationLatencyMs,
                escalationFailure = escalationFailure,
                escalationScore = escalationScore,
            )

            is AgentDecisionOutcome.Success -> {
                val response = finalOutcome.response
                val needsTools = (response.answers["needs_tools"] as? AgentDecisionAnswer.Noul)
                    ?.probability ?: 0.0
                val selected = if (needsTools >= NEEDS_TOOLS_THRESHOLD) {
                    (response.answers["tool_groups"] as? AgentDecisionAnswer.MultiChoice)
                        ?.choices.orEmpty()
                } else {
                    emptySet()
                }
                val needsScreenContext = popupRun && (
                    (response.answers["needs_screen_context"] as? AgentDecisionAnswer.Noul)
                        ?.probability ?: 0.0
                    ) >= NEEDS_TOOLS_THRESHOLD
                AgentDecisionEvaluation(
                    modelId = modelId,
                    endpoint = endpoint,
                    endpointFailure = null,
                    skipReason = null,
                    failureReason = null,
                    detail = "",
                    httpStatus = response.httpStatus,
                    errorCode = 0,
                    errorDesc = "",
                    latencyMs = primaryLatencyMs,
                    needsTools = needsTools,
                    selectedGroups = selected,
                    needsScreenContext = needsScreenContext,
                    layers = response.layers.size,
                    usageIn = response.usage?.inputTokens,
                    usageOut = response.usage?.outputTokens,
                    stateChars = stateChars,
                    historyTurnCount = turns.size,
                    toolGroupsConfidence = confidence,
                    escalated = escalated,
                    escalationReason = escalationReason,
                    escalationModelId = escalation?.modelId,
                    escalationLatencyMs = escalationLatencyMs,
                    escalationFailure = escalationFailure,
                    escalationScore = escalationScore,
                )
            }
        }
    }

    /** 单次调用 + 超时兜底；超时/异常都收敛成 [AgentDecisionOutcome.Failure]。 */
    private fun call(
        endpoint: String,
        apiKey: String,
        modelId: String,
        timeoutMs: Int,
        state: JSONObject,
        questions: List<AgentDecisionQuestion>,
    ): AgentDecisionOutcome = runBlocking {
        withTimeoutOrNull(timeoutMs.toLong() + 1000L) {
            AgentDecisionModelClient.complete(
                config = AgentDecisionClientConfig(
                    baseUrl = endpoint,
                    apiKey = apiKey,
                    model = modelId,
                    timeoutMs = timeoutMs,
                ),
                state = state,
                questions = questions,
                dagEdges = mapOf("tool_groups" to listOf("needs_tools")),
            )
        }
    } ?: AgentDecisionOutcome.Failure(
        reason = "timeout",
        latencyMs = timeoutMs.toLong(),
        detail = "等待判断模型超过 ${timeoutMs}ms",
    )

    private fun blank(
        modelId: String,
        skipReason: String,
        endpointFailure: String? = null,
        endpoint: String? = null,
    ) = AgentDecisionEvaluation(
        modelId = modelId,
        endpoint = endpoint,
        endpointFailure = endpointFailure,
        skipReason = skipReason,
        failureReason = null,
        detail = "",
        httpStatus = null,
        errorCode = null,
        errorDesc = "",
        latencyMs = 0,
        needsTools = 0.0,
        selectedGroups = emptySet(),
        layers = 0,
        usageIn = null,
        usageOut = null,
        stateChars = 0,
        historyTurnCount = 0,
    )

    /**
     * 取最近 [turns] 个「用户发言」及其后的助手文本。只读 user/assistant，
     * 不带工具结果，避免把大块工具输出送进判断模型。
     */
    private fun recentTurns(
        history: List<ConversationMessage>,
        turns: Int,
    ): List<ConversationMessage> {
        if (turns <= 0) return emptyList()
        val textMessages = history.filter { message ->
            (message.role == ROLE_USER || message.role == ROLE_ASSISTANT) && message.content.isNotBlank()
        }
        if (textMessages.isEmpty()) return emptyList()
        val userIndexes = textMessages.indices.filter { textMessages[it].role == ROLE_USER }
        if (userIndexes.isEmpty()) return emptyList()
        val start = userIndexes.getOrElse(userIndexes.size - turns) { 0 }
        return textMessages.subList(start, textMessages.size)
    }

    /** 从最新往回装，超出字符预算就丢弃更早的轮次。 */
    private fun trimHistory(
        messages: List<ConversationMessage>,
        maxChars: Int,
    ): List<ConversationMessage> {
        var total = 0
        val kept = mutableListOf<ConversationMessage>()
        for (message in messages.asReversed()) {
            val cost = message.content.length.coerceAtMost(MAX_TURN_CHARS) + message.role.length + 20
            if (total + cost > maxChars && kept.isNotEmpty()) break
            kept.add(0, message)
            total += cost
        }
        return kept
    }

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars) + "…"

    fun sanitize(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').trim().take(120)

    fun ratio(value: Double): String = "%.3f".format(Locale.US, value)

    /** 判定「需要工具」的阈值；与影子日志里的 needs_tools 展示共用。 */
    const val NEEDS_TOOLS_THRESHOLD = 0.5

    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
}
