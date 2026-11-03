package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * 判断模型的影子接入。
 *
 * 与 M2 的 [AgentDecisionRouter] 共用 [AgentDecisionEvaluator]：同一个 state、同一个端点
 * 归一、同一个解析，唯一差别是这里**只写日志**，不返回任何 plan，也不修改工具目录、
 * 提示词或历史。
 *
 * 失败（缺配置 / 端点非法 / 超时 / 网络 / HTTP 非 200 / `errorCode != 0` / 答案校验失败）
 * 都在这里收敛成一条日志，绝不抛到主流程。
 */
internal object AgentDecisionShadow {

    /** 缺配置 / 影子关闭 / 协议不支持按会话去重，避免每轮刷屏。 */
    private val configuredSkipLogged = ConcurrentHashMap.newKeySet<String>()
    private val warnedShadowOff = ConcurrentHashMap.newKeySet<String>()
    private val skippedProtocol = ConcurrentHashMap.newKeySet<String>()

    fun run(
        request: AgentRuntimeWire.RunRequest,
        config: AgentModelClient.ModelConfig,
        logger: AgentLogger,
    ) {
        val conversationId = request.effectiveModelSessionId
        val mode = readMode()
        val shadow = Prefs.isEnabled(Prefs.Keys.AGENT_DECISION_SHADOW)
        val failurePolicy = Prefs.getString(
            Prefs.Keys.AGENT_DECISION_FAILURE_POLICY,
            Prefs.Keys.AGENT_DECISION_FAILURE_OPEN,
        )

        try {
            if (!shadow) {
                // 非影子模式由 AgentDecisionRouter 接管；走到这里说明调用方误用，只提示一次。
                logOnce(warnedShadowOff, conversationId) {
                    logger.warn(
                        "decision.shadow skipped: shadow=false, routing handled by router " +
                            "run_id=${request.runId} conversation_id=$conversationId mode=$mode",
                    )
                }
                return
            }
            val protocol = Prefs.getString(
                Prefs.Keys.AGENT_DECISION_PROTOCOL,
                Prefs.Keys.AGENT_DECISION_PROTOCOL_SYSTEMONE,
            )
            if (protocol != Prefs.Keys.AGENT_DECISION_PROTOCOL_SYSTEMONE) {
                logOnce(skippedProtocol, conversationId) {
                    logger.warn(
                        "decision.shadow skip: unsupported protocol=$protocol " +
                            "run_id=${request.runId} conversation_id=$conversationId",
                    )
                }
                return
            }
            val providerId = Prefs.getString(Prefs.Keys.AGENT_DECISION_PROVIDER_ID).trim()
            val modelId = Prefs.getString(Prefs.Keys.AGENT_DECISION_MODEL_ID).trim()
            if (providerId.isBlank() || modelId.isBlank()) {
                logOnce(configuredSkipLogged, conversationId) {
                    logger.info(
                        "decision.shadow skip: provider/model 未配置 " +
                            "run_id=${request.runId} conversation_id=$conversationId",
                    )
                }
                return
            }
            val timeoutMs = Prefs.getInt(
                Prefs.Keys.AGENT_DECISION_TIMEOUT_MS,
                Prefs.Keys.AGENT_DECISION_TIMEOUT_DEFAULT_MS,
            ).takeIf { it > 0 }
                ?.coerceIn(500, Prefs.Keys.AGENT_DECISION_TIMEOUT_MAX_MS)
                ?: Prefs.Keys.AGENT_DECISION_TIMEOUT_DEFAULT_MS
            val historyTurns = Prefs.getInt(
                Prefs.Keys.AGENT_DECISION_HISTORY_TURNS,
                Prefs.Keys.AGENT_DECISION_HISTORY_TURNS_DEFAULT,
            ).coerceIn(0, Prefs.Keys.AGENT_DECISION_HISTORY_TURNS_MAX)

            val gateGroups = AgentDecisionGate.allowedGroups(config)
            val gateIds = gateGroups.mapTo(mutableSetOf()) { it.id }
            val sticky = AgentDecisionStickyStore.current(conversationId, gateIds)

            val runtimeConfig = runBlocking {
                RuntimeConfigRepository.configForProviderAndModel(providerId, modelId)
            }
            if (runtimeConfig == null) {
                logOnce(configuredSkipLogged, conversationId) {
                    logger.warn(
                        "decision.shadow skip: provider 或模型不可用 provider=$providerId model=$modelId " +
                            "run_id=${request.runId} conversation_id=$conversationId",
                    )
                }
                return
            }

            val evaluation = AgentDecisionEvaluator.evaluate(
                request = request,
                modelId = modelId,
                providerBaseUrl = runtimeConfig.baseUrl,
                apiKey = runtimeConfig.apiKey,
                timeoutMs = timeoutMs,
                historyTurns = historyTurns,
                endpointOverride = Prefs.getString(Prefs.Keys.AGENT_DECISION_ENDPOINT_OVERRIDE),
                groups = gateGroups,
                stickyGroups = sticky,
                // 影子与接管共用判定；升级在影子阶段不触发（避免多打一次请求污染观测数据）。
                isContinuation = AgentDecisionContinuation.detect(request.history, request.prompt),
                escalation = null,
            )

            val endpoint = evaluation.endpoint ?: "invalid(${evaluation.endpointFailure ?: "unknown"})"
            if (evaluation.skipReason != null) {
                logOnce(configuredSkipLogged, conversationId) {
                    logger.warn(
                        "decision.shadow skip=${evaluation.skipReason} " +
                            "run_id=${request.runId} conversation_id=$conversationId " +
                            "mode=$mode shadow=true model=$modelId endpoint=$endpoint " +
                            "allowed=${gateIds.sorted()} sticky=${sticky.sorted()} " +
                            "failure_policy=$failurePolicy",
                    )
                }
                return
            }
            if (evaluation.failureReason != null) {
                logger.warn(
                    "decision.shadow run_id=${request.runId} conversation_id=$conversationId " +
                        "mode=$mode shadow=true model=$modelId escalated=false endpoint=$endpoint " +
                        "state_chars=${evaluation.stateChars} history_turns=${evaluation.historyTurnCount} " +
                        "http_status=${evaluation.httpStatus ?: "-"} " +
                        "error_code=${evaluation.errorCode ?: "-"} " +
                        "error_desc=${AgentDecisionEvaluator.sanitize(evaluation.errorDesc)} " +
                        "latency_ms=${evaluation.latencyMs} selected_groups=[] " +
                        "allowed=${gateIds.sorted()} sticky=${sticky.sorted()} opened_after=[] " +
                        "fallback_reason=${evaluation.failureReason} failure_policy=$failurePolicy " +
                        "detail=${AgentDecisionEvaluator.sanitize(evaluation.detail)}",
                )
                return
            }

            val selected = evaluation.selectedGroups intersect gateIds
            val wouldBe = selected + AgentToolGroups.BASIC_GROUP.id
            logger.info(
                "decision.shadow run_id=${request.runId} conversation_id=$conversationId " +
                    "mode=$mode shadow=true model=$modelId escalated=false endpoint=$endpoint " +
                    "state_chars=${evaluation.stateChars} history_turns=${evaluation.historyTurnCount} " +
                    "http_status=${evaluation.httpStatus ?: "-"} error_code=${evaluation.errorCode ?: "-"} " +
                    "latency_ms=${evaluation.latencyMs} " +
                    "needs_tools=${AgentDecisionEvaluator.ratio(evaluation.needsTools)} " +
                    "selected_groups=${selected.sorted()} allowed=${gateIds.sorted()} " +
                    "sticky=${sticky.sorted()} opened_after=${selected.sorted()} " +
                    "shadow_would_be=${wouldBe.sorted()} fallback_reason=none " +
                    "failure_policy=$failurePolicy layers=${evaluation.layers} " +
                    "usage_in=${evaluation.usageIn ?: "-"} usage_out=${evaluation.usageOut ?: "-"}",
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // 影子阶段任何意外都不能影响主流程。
            logger.warn(
                "decision.shadow error run_id=${request.runId} conversation_id=$conversationId " +
                    "fallback_reason=exception detail=${error.javaClass.simpleName}: " +
                    AgentDecisionEvaluator.sanitize(error.message.orEmpty()),
            )
        }
    }

    private fun readMode(): String = Prefs.getString(
        Prefs.Keys.AGENT_DECISION_MODE,
        Prefs.Keys.AGENT_DECISION_MODE_CONTROL,
    ).takeIf { it == Prefs.Keys.AGENT_DECISION_MODE_CONTROL || it == Prefs.Keys.AGENT_DECISION_MODE_ADVISE }
        ?: Prefs.Keys.AGENT_DECISION_MODE_CONTROL

    private fun logOnce(seen: MutableSet<String>, key: String, block: () -> Unit) {
        if (seen.size > 512) seen.clear()
        if (seen.add(key)) block()
    }
}
