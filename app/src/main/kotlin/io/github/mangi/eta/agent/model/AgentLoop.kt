package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.AgentToolMediaPreviews
import io.github.mangi.eta.agent.question.AgentQuestionCoordinator
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个请求 round，不等于压缩的用户逻辑 turn。
 * 暂停、追加、终止保留同一 turnId；请求计数和 UI 文本块标识不参与划分用户轮次。
 * 流式正文中途的 steering 会打断当前模型请求、保留已写出的内容，再注入补充指令发起后续请求；
 * 工具批次仍跑完，不取消正在执行的工具。循环不设置本地轮次上限，由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: ((Int) -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val reconnectTiming: ReconnectTiming = SystemReconnectTiming,
    private val waitForReconnect: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val compactPolicy: CompactPolicy = CompactPolicy.Disabled,
    private val systemCount: Int = 0,
    private val compactionArchive: AgentCompactionArchive? = null,
    private val turnId: String = java.util.UUID.randomUUID().toString(),
    private val toolDiagnostics: AgentToolCallDiagnostics = AgentToolCallDiagnostics(),
    /** 会话上一张可信云端回执折算到本次请求的输入量；只作为第一个锚点，本轮回执到达后被替换。 */
    private val calibratedInputTokens: Int? = null,
    private val onHistoryCompacted: () -> Unit = {},
    private val onHistorySnapshot: ((Int, List<AgentModelClient.ConversationMessage>) -> String)? = null,
    private val compactHistory: ((
        List<AgentModelClient.ConversationMessage>,
        CompactPolicy,
    ) -> List<AgentModelClient.ConversationMessage>)? = null,
    /** Explicit permission to send an unknown context; never a usage receipt or calibration. */
    allowUnmeasuredContextSend: Boolean = false,
) {
    data class CompactPolicy(
        val enabled: Boolean,
        val contextWindow: Int,
        val keepRecentMessages: Int,
        val compressModelConfig: AgentModelClient.ModelConfig?,
        val keepStartOverride: Int? = null,
    ) {
        companion object {
            val Disabled = CompactPolicy(
                enabled = false,
                contextWindow = 128_000,
                keepRecentMessages = AgentContextCompactor.DEFAULT_KEEP_RECENT,
                compressModelConfig = null,
            )
        }
    }
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var auxiliaryRound = 1
    private val auxiliaryVision = AuxiliaryVision.create(config, runController, sessionId) {
        onEvent(it.copy(round = auxiliaryRound))
    }

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val delegationArgumentRepair = AgentDelegationArgumentRepair()
    private val invalidToolArgumentsGuard = AgentInvalidToolArgumentsGuard()
    private var shellFailureState = AgentShellFailureGuard.State()
    private var toolDiagnosticAttempt: AgentToolCallDiagnostics.Attempt? = null
    private var shellFailureStopMessage: String? = null
    private var delegationRepairNotifiedRound: Int? = null
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null
    private val incompleteText = sortedMapOf<Int, String>()
    private var responseStored = false

    private fun rememberIncompleteText(event: ProviderEvent) {
        when (event) {
            is ProviderEvent.BlockDelta -> if (event.kind == AssistantBlockKind.TEXT) {
                incompleteText[event.index] = incompleteText[event.index].orEmpty() + event.delta
            }
            is ProviderEvent.BlockEnd -> if (event.kind == AssistantBlockKind.TEXT && event.replaceContent) {
                incompleteText[event.index] = event.content
            }
            else -> Unit
        }
    }

    fun preserveIncompleteResponse() {
        if (responseStored) return
        val text = incompleteText.values.joinToString("").trim()
        if (text.isNotBlank()) {
            messages.put(JSONObject().put("role", "assistant").put("content", text)
                .put(AgentTurnIdentity.JSON_KEY, turnId))
            responseStored = true
        }
    }
    private var lastUsage: AgentTokenUsage? = null
    private var hasDisplayCloudReceipt = false
    private val requestBudget = AgentRequestBudgetPolicy()
    private val silentBudget = AgentSilentContextBudget()
    // A committed summary starts a new unknown context epoch. Pruning or failed summaries do not.
    private var unmeasuredContextSendAllowed = allowUnmeasuredContextSend
    private var suppressThinkingForNextRequest = false
    private val continuationBlocks = AgentContinuationBlocks()
    private val continuationReasoning = AgentContinuationReasoning()
    private val interruptedTextPrefix = StringBuilder()
    private var continuingInterruptedRequest = false
    private var supplementStartsNewBlock = false
    private var compactionFailure = ""
    private var currentRoundTools = tools
    private var currentRoundToolsTokens = AgentRequestTokenEstimate.tools(tools)
    private var manualBudgetAttempt = false
    private var budgetKeepRecent = compactPolicy.keepRecentMessages
    private var budgetCompressModelConfig = compactPolicy.compressModelConfig
    private var overflowPending = false
    private var overflowRecoveryAttempts = 0
    private var lastFailedCompaction: Pair<String, Int>? = null
    private var skipIneffectiveAutoCompact = false
    private var skippedAutoChars: Long = -1L
    private var skippedAutoCloud: Int = Int.MIN_VALUE
    private var lastAutoLogReason: String = ""
    private var lastAutoLogNanos: Long = 0L
    /** 圆环上的云端实测曾到过 80%。后面的回执即使变小，也在下次请求前压缩。 */
    private var autoCompactLatched = false
    private var latchedCloudTokens = 0
    /** 上游在输出上限处截断且正文为空时，同一 round 内的自动重发预算，不跨 round 累积。 */
    private var emptyOutputLimitRetries = 0
    private var emptyOutputLimitRound = 0
    private var invalidArgumentsInBatch = 0
    private var shellFailuresInBatch = 0
    private var postToolReconnect: ModelErrorReconnect? = null
    private var postToolReconnectBinding: AutoCloseable? = null
    private var postToolRetryCount = 0
    private var localOnlyRecovery = AgentRecoveryContext.isActive(messages)

    private fun finishPostToolReconnect(status: String) {
        val current = postToolReconnect ?: return
        try {
            current.finish(status)
        } finally {
            try { postToolReconnectBinding?.close() }
            finally {
                postToolReconnectBinding = null
                postToolReconnect = null
                postToolRetryCount = 0
            }
        }
    }

    private fun retryPostToolFailure(round: Int, code: String, message: String) {
        val failure = AgentModelFailure(code, false, message)
        val policy = ErrorReconnectPolicy.fromPersistedValue(config.errorReconnectPolicy)
        if (policy == ErrorReconnectPolicy.NONE) throw failure
        val state = postToolReconnect ?: ModelErrorReconnect(round, policy.windowMillis,
            reconnectTiming, onEvent, failure, listOf(config.apiKey)).also {
            postToolReconnect = it
            postToolReconnectBinding = it.bind(runController)
            it.start()
        }
        state.updateReason(failure)
        state.check()
        postToolRetryCount = postToolRetryCount % 3 + 1
        val backoff = 2_000L shl (postToolRetryCount - 1)
        val delay = minOf(backoff, state.remainingMs() ?: backoff)
        onEvent(AgentEvent.ModelRetryScheduled(round, postToolRetryCount, 3, delay.toInt(), code,
            AgentHttpFailureDiagnostics.safe(message, listOf(config.apiKey), 600)))
        waitForReconnect(runController, delay)
        runController.throwIfCancelled()
        state.check()
    }

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    /**
     * 一轮请求在发出前就已经确定的只读量。
     *
     * 这些值必须在回调可能改动历史之前取定：重试复用同一个请求，只有输出或部分用量
     * 的回执不能移动这个锚点。集中成一处，避免在 480 行的 run() 里靠位置记忆它们的关系。
     */
    private data class RoundContext(
        /** 静默预算使用的本地边界词元数。 */
        val requestLocal: Int,
        /** 快照时刻的原始历史条数（仅用于有界的请求上下文诊断）。 */
        val requestMessageCount: Int,
        /** 与整表 transcript + countMessage 等价的历史词元数。 */
        val requestHistoryTokens: Int,
        /** UI 校准使用的固定开销（system 消息 + 工具目录）。 */
        val requestFixedTokens: Int,
        /** 恰好一份已水合/已裁剪的历史快照，供投影与传输共用。 */
        val filteredMessages: org.json.JSONArray,
        /** 本轮是否到了可以发布本地估算的显示边界。 */
        val publishLocalEstimate: Boolean,
    )

    private fun snapshotRound(roundTools: JSONArray): RoundContext {
        // Snapshot BEFORE callbacks can append this response to messages.
        val requestLocal = localRequestTokens()
        val requestMessageCount = messages.length()
        val requestHistoryTokens = historyChars.historyTokens()
        // UI history calibration retains its original raw DTO basis, including old receipts.
        val requestFixedTokens = AgentRequestTokenEstimate.fixed(
            messages, systemCount, roundTools, toolTokens = currentRoundToolsTokens)
        // Finish the raw metadata pass before retaining the hydrated copy (lower peak memory).
        if (localOnlyRecovery) AgentRecoveryContext.append(messages)
        val filteredMessages = AgentRequestMediaPolicy.filter(messages, config.supportsVision, config.supportsVideo)
        val publishLocalEstimate = requestBudget.consumeLocalBoundary()
        // 两个预算锚点都在同一个边界上前进，避免显示与静默口径互相污染。
        silentBudget.requestStarted(requestLocal)
        requestBudget.requestStarted()
        return RoundContext(
            requestLocal = requestLocal,
            requestMessageCount = requestMessageCount,
            requestHistoryTokens = requestHistoryTokens,
            requestFixedTokens = requestFixedTokens,
            filteredMessages = filteredMessages,
            publishLocalEstimate = publishLocalEstimate,
        )
    }

    fun run(): Result {
        // Only annotate messages created by this run. The current user entry is initially last.
        messages.optJSONObject(messages.length() - 1)?.put(AgentTurnIdentity.JSON_KEY, turnId)
        // UI 手里有上一张可信回执时，用它折算到本次请求的值校准发送上限。
        // 自动压缩不看它：80% 只看本次 run 收到的真实回执（圆环上的数）。
        silentBudget.seed(localRequestTokens(), calibratedInputTokens,
            config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow)
        var round = 1

        try {
        roundLoop@ while (true) {
            runController.throwIfCancelled()
            postToolReconnect?.check()
            appendPendingSteeringMessage()
            selectRoundTools(toolsForRound?.invoke(round) ?: tools)
            try {
                auxiliaryRound = round
                auxiliaryVision.prepare(messages)
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (runController.hasPausedInterrupt || runController.hasPendingImmediateSteering) {
                    runController.consumePausedInterrupt()
                    continue
                }
                throw failure
            }
            maybeCompactBeforeRound(round)
            var reductions = 0
            while (requestOverBudget() || overflowPending) {
                if (reductions++ < 2 && overflowRecoveryAttempts <= 1 && tryBudgetCompaction(round)) {
                    overflowPending = false
                    continue
                }
                runController.pause()
                onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                    blocked = true, reason = compactionFailure.ifBlank {
                        "上下文空间不足；受保护历史未删除。可保持暂停、仅本次允许压缩较早步骤，或停止后选择更大窗口模型。"
                    }))
                runController.throwIfCancelled()
                reductions = 0
                appendPendingSteeringMessage()
                selectRoundTools(toolsForRound?.invoke(round) ?: tools)
                try {
                    auxiliaryRound = round
                    auxiliaryVision.prepare(messages)
                } catch (failure: Exception) {
                    runController.throwIfCancelled()
                    if (runController.hasPausedInterrupt || runController.hasPendingImmediateSteering) {
                        runController.consumePausedInterrupt()
                        continue@roundLoop
                    }
                    throw failure
                }
                maybeCompactBeforeRound(round)
                if (manualBudgetAttempt) overflowRecoveryAttempts = 0
            }

            // The manual override covers this preparation boundary, including its bounded
            // hard-pressure reductions above. Later rounds/overflow must use the automatic
            // policy again, including a null compressor (not the previous manual model).
            manualBudgetAttempt = false
            budgetKeepRecent = compactPolicy.keepRecentMessages
            budgetCompressModelConfig = compactPolicy.compressModelConfig
            val roundTools = currentRoundTools
            toolCallValidator = AgentToolCallValidator(roundTools)
            incompleteText.clear()
            responseStored = false
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val continuationText = AgentContinuationTextEvents(
                if (continuingInterruptedRequest) interruptedTextPrefix.toString() else "")
            continuationReasoning.beginRequest(continuingInterruptedRequest)
            continuationBlocks.beginRequest(continuingInterruptedRequest && !supplementStartsNewBlock)
            supplementStartsNewBlock = false
            continuingInterruptedRequest = false
            val snapshot = snapshotRound(roundTools)
            // Display estimates arrive from the SAME final body serialized by the provider.
            // Keep the silent/local boundary and cloud calibration on their original basis.
            var preparedRequestTokens: Int? = null
            lastUsage = null // A new request must not inherit missing fields from the preceding bill.
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(requestConfigForRound(),
                        snapshot.filteredMessages, roundTools, sessionId, toolDiagnostics = toolDiagnostics,
                        reconnectLocalToolsOnly = localOnlyRecovery),
                    provider = provider,
                    controller = runController,
                    onEvent = onEvent,
                    onAttemptStarted = { attemptRound, requestMessages ->
                        runCatching { onHistorySnapshot?.let { publish ->
                            // Preserve durable media from the unfiltered history. Reconnect adds only
                            // committed TEXT and its continue prompt to the fixed request prefix.
                            val full = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds)
                            val suffixMessages = JSONArray()
                            for (index in snapshot.filteredMessages.length() until requestMessages.length()) {
                                val message = JSONObject(requestMessages.getJSONObject(index).toString())
                                if (message.optString(AgentTurnIdentity.JSON_KEY).isBlank()) {
                                    message.put(AgentTurnIdentity.JSON_KEY, turnId)
                                }
                                suffixMessages.put(message)
                            }
                            val suffix = AgentConversationCodec.transcript(suffixMessages, 0, sensitiveToolCallIds)
                            publish(attemptRound, full + suffix)
                        }.orEmpty() }.getOrDefault("")
                    },
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (providerEvent is ProviderEvent.RequestStarted) lastUsage = null
                        if (providerEvent is ProviderEvent.RequestEstimate) {
                            preparedRequestTokens = providerEvent.tokens
                            // Existing UI reducer keeps cloud receipts authoritative, including
                            // receipts from previous runs. Never feed this into silentBudget.
                            if (snapshot.publishLocalEstimate && !hasDisplayCloudReceipt && providerEvent.tokens > 0) {
                                onEvent(AgentEvent.UsageReceived(attemptRound,
                                    AgentTokenUsage(inputTokens = providerEvent.tokens), projected = true,
                                    requestHistoryTokens = snapshot.requestHistoryTokens,
                                    requestOverheadTokens = snapshot.requestFixedTokens))
                            }
                        }
                        if (providerEvent is ProviderEvent.Usage) {
                            val previous = lastUsage
                            val incoming = providerEvent.usage
                            lastUsage = AgentTokenUsage(
                                contextTokens = incoming.contextTokens ?: previous?.contextTokens,
                                inputTokens = incoming.inputTokens ?: previous?.inputTokens,
                                outputTokens = incoming.outputTokens ?: previous?.outputTokens,
                                reasoningTokens = incoming.reasoningTokens ?: previous?.reasoningTokens,
                                cachedTokens = incoming.cachedTokens ?: previous?.cachedTokens,
                                cacheCreationTokens = incoming.cacheCreationTokens ?: previous?.cacheCreationTokens,
                            )
                            if ((lastUsage?.inputTokens ?: 0) > 0) hasDisplayCloudReceipt = true
                            // Partial fields merge only within this request. A plausible input
                            // immediately replaces the cloud anchor; queue pressure in this callback,
                            // never send a confirmation request or compact an open output/tool batch.
                            // The separate silent anchor survives a later usage-less request.
                            silentBudget.measured(lastUsage?.inputTokens,
                                config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow,
                                cachedTokens = lastUsage?.cachedTokens)
                            noteRingPressure(attemptRound)
                        }
                        continuationReasoning.visibleEvent(if (providerEvent is ProviderEvent.Usage) ProviderEvent.Usage(requireNotNull(lastUsage)) else providerEvent)?.let { visibleEvent ->
                            if (visibleEvent is ProviderEvent.BlockDelta &&
                                visibleEvent.kind == AssistantBlockKind.THINKING
                            ) {
                                accumulatedReasoning.append(visibleEvent.delta)
                            }
                            continuationText.map(visibleEvent).forEach { textEvent ->
                                rememberIncompleteText(textEvent)
                                continuationBlocks.map(attemptRound, textEvent).toAgentEvent(attemptRound)?.let { event ->
                                    onEvent(if (event is AgentEvent.UsageReceived && !event.projected) {
                                        event.copy(requestHistoryTokens = snapshot.requestHistoryTokens, requestOverheadTokens = snapshot.requestFixedTokens)
                                    } else event)
                                }
                            }
                        }
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                    requestReconnect = postToolReconnect,
                    onRecoveryActivated = {
                        localOnlyRecovery = true
                        AgentRecoveryContext.append(messages)
                    },
                    onCancelledResponse = { response ->
                        val calls = AgentConversationCodec.parseToolCalls(response.assistantMessage)
                        val sensitiveCalls = calls.filter { AgentSensitiveToolPolicy.isSensitive(it.name) }
                        sensitiveCalls.forEach { sensitiveToolCallIds += it.id }
                        if (sensitiveCalls.isNotEmpty()) {
                            messages.put(AgentConversationCodec.assistantHistoryMessage(
                                source = response.assistantMessage,
                                toolCalls = calls,
                            ).put(AgentTurnIdentity.JSON_KEY, turnId))
                            responseStored = true
                        }
                    },
                ).also { postToolReconnect?.check() }
            } catch (failure: AgentModelFailure) {
                if (failure.code != "CONTEXT_WINDOW_EXCEEDED") throw failure
                overflowPending = true
                overflowRecoveryAttempts++
                compactionFailure = "提供方确认上下文超限。只在缩减成功后有限重试；否则保持暂停，不删除受保护历史。"
                continue
            }
            localOnlyRecovery = localOnlyRecovery || completedRound.reconnectLocalToolsOnly
            if (localOnlyRecovery) AgentRecoveryContext.append(messages)
            // Failed/overflowed requests keep their image observation until a successful request.
            discardPendingToolImageMessage()
            overflowRecoveryAttempts = 0
            // Transport retries use a distinct display round; its fallback must not
            // duplicate text already retained in the previous display block.
            if (completedRound.round != round) interruptedTextPrefix.setLength(0)
            round = completedRound.round
            val providerResponse = completedRound.response
            toolDiagnosticAttempt = completedRound.toolDiagnosticAttempt
            // One bounded record per successful request round. Local estimates stay labeled as
            // estimates and sit next to the same round's cloud receipt so the gap is attributable.
            emitRequestContext(
                attempt = toolDiagnosticAttempt,
                requestMessageCount = snapshot.requestMessageCount,
                requestLocal = snapshot.requestLocal,
                requestHistoryTokens = snapshot.requestHistoryTokens,
                requestFixedTokens = snapshot.requestFixedTokens,
                preparedRequestTokens = preparedRequestTokens,
                filteredMessages = snapshot.filteredMessages,
                roundTools = roundTools,
                usage = lastUsage,
            )

            // Keep a fully returned response before observing a concurrent user stop.
            continuationText.finish().forEach { textEvent ->
                continuationBlocks.map(round, textEvent).toAgentEvent(round)?.let(onEvent)
            }
            // 拒答 / 过滤会在流中途截断回复：半截正文、思考块与工具调用都不能进入上下文。
            // 带着被截断的签名 thinking 块继续请求会被上游判定为改动 thinking 块，同一会话随后每轮都会 400；
            // 因此不写入 history、不执行工具，直接结束本次运行，由用户改写请求或换模型。
            if (providerResponse.stopReason == AssistantStopReason.CONTENT_FILTER) {
                val explanation = providerResponse.assistantMessage.optJSONObject("stop_details")
                    ?.let { details -> details.optString("explanation").takeUnless { details.isNull("explanation") } }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                throw AgentModelFailure(
                    "MODEL_CONTENT_FILTER",
                    false,
                    buildString {
                        append("模型服务商拦截了本次回复，未完成的内容已丢弃。")
                        explanation?.let { append("服务商说明：").append(it) }
                        append("请修改或删除触发拦截的请求后重试，或换用其他模型。")
                    },
                )
            }
            val assistantMessage = providerResponse.assistantMessage
            val originalContent = assistantMessage.opt("content")
            if (originalContent is String && originalContent != "null") {
                assistantMessage.put("content", continuationText.normalize(originalContent))
            }
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            toolCalls.forEachIndexed { index, call -> toolDiagnosticAttempt?.parsed(call, index) }
            toolCalls.filter { AgentSensitiveToolPolicy.isSensitive(it.name) }
                .forEach { sensitiveToolCallIds += it.id }
            val assistantReasoning = continuationReasoning.visibleCompletedReasoning(
                assistantMessage.optString("reasoning_content"))
            val content = assistantMessage.optString("content").trim()
            // 已写出的正文会结束或推进本轮；空正文截断的重发预算只在同一轮内累计。
            if (content.isNotBlank() && content != "null") emptyOutputLimitRetries = 0
            val hasAssistantPayload = (content.isNotBlank() && content != "null") ||
                assistantReasoning.isNotBlank() ||
                toolCalls.isNotEmpty()
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            val pausedInterrupt = runController.consumePausedInterrupt()
            val completeToolCallsOnResume = pausedInterrupt &&
                toolCalls.isNotEmpty() &&
                providerResponse.stopReason == AssistantStopReason.TOOL_USE
            if (providerResponse.stopReason == AssistantStopReason.INTERRUPTED ||
                (pausedInterrupt && !completeToolCallsOnResume)) {
                continuingInterruptedRequest = true
                // Resume keeps the user's reasoning configuration. Only the first
                // reasoning block's UI projection is hidden, not the model's thinking.
                // 半截正文留在当前轮次历史里，继续时模型才能接着写。
                // 未完成的工具调用不执行；已经完整给出的 TOOL_USE 在恢复后走正常批次。
                val interruptedText = assistantMessage.optString("content")
                    .takeIf { it != "null" }.orEmpty()
                if (hasAssistantPayload) {
                    interruptedTextPrefix.append(interruptedText)
                    messages.put(
                        AgentConversationCodec.assistantHistoryMessage(
                            source = assistantMessage,
                            toolCalls = emptyList(),
                        ).put(AgentTurnIdentity.JSON_KEY, turnId),
                    )
                    responseStored = true
                }
                // 思考期暂停没有可续写的正文：这一轮的 reasoning_content 不会回到模型手里，
                // 只给一条通用续写指令会让它重新读整轮对话，所以换成接续思考的指令。
                // 完全没有产出时不追加任何指令，恢复后就是原请求重发。
                if (responseStored && !runController.isCancelled) {
                    appendContinuePromptAfterInterrupt(thinkingOnly = interruptedText.isBlank())
                }
                runController.throwIfCancelled()
                appendPendingSteeringMessage()
                continue
            }
            // 空正文截断重发时要按实例定位这条刚写入的消息，避免删掉别的历史。
            var storedAssistantMessage: JSONObject? = null
            if (hasAssistantPayload) {
                val historyMessage = AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                ).put(AgentTurnIdentity.JSON_KEY, turnId)
                storedAssistantMessage = historyMessage
                messages.put(historyMessage)
                responseStored = true
                onEvent(
                    AgentEvent.AssistantReceived(
                        round = round,
                        contentChars = assistantMessage.optString("content").length,
                        reasoningContent = assistantReasoning,
                        toolNames = toolCalls.map { it.name },
                    )
                )
            } else if (runController.hasPendingSteering) {
                appendPendingSteeringMessage()
                interruptedTextPrefix.setLength(0)
                round += 1
                continue
            }

            runController.throwIfCancelled()
            if (toolCalls.isNotEmpty()) {
                invalidArgumentsInBatch = 0
                shellFailuresInBatch = 0
                val finishedContent = assistantMessage.optString("content").trim()
                val finishedNaturally = providerResponse.stopReason != AssistantStopReason.TOOL_USE &&
                    providerResponse.stopReason != AssistantStopReason.OUTPUT_LIMIT &&
                    finishedContent.isNotBlank() &&
                    finishedContent != "null"
                if (finishedNaturally) {
                    // No tool was executed: remove the batch before maintenance can inspect history.
                    val historyAssistantIndex = messages.length() - 1
                    messages.put(historyAssistantIndex, AgentConversationCodec.assistantHistoryMessage(
                        source = assistantMessage, toolCalls = emptyList(),
                    ).put(AgentTurnIdentity.JSON_KEY, turnId))
                } else {
                val outcomes = mutableListOf<ToolOutcome>()
                try {
                    val askUserCalls = toolCalls.count { it.name == AgentQuestionCoordinator.TOOL_NAME }
                    val askUserBatchBlocked = askUserCalls > 1 ||
                        (askUserCalls == 1 && toolCalls.any { it.name != AgentQuestionCoordinator.TOOL_NAME })
                    if (askUserBatchBlocked) {
                        // ask_user 只能单独出现。混合批次或多个 ask_user 全部配对拒绝，零执行。
                        toolCalls.forEach { call ->
                            outcomes += rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "ASK_USER_BATCH_BARRIER",
                                message = "ask_user 必须是本批唯一的工具调用。本批未执行任何工具，请只保留一个 ask_user 后重试。",
                            )
                        }
                    } else when (providerResponse.stopReason) {
                        AssistantStopReason.TOOL_USE ->
                            executeToolBatch(round, toolCalls, outcomes)
                        AssistantStopReason.OUTPUT_LIMIT ->
                            toolCalls.forEach { call ->
                                outcomes += rejectedToolOutcome(
                                    round = round,
                                    toolCall = call,
                                    code = "TRUNCATED_TOOL_CALL",
                                    message = "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                                )
                            }
                        else ->
                            toolCalls.forEach { call ->
                                outcomes += rejectedToolOutcome(
                                    round = round,
                                    toolCall = call,
                                    code = "UNEXPECTED_TOOL_CALL",
                                    message = "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；" +
                                        "本批调用未执行，请重新规划。",
                                )
                            }
                    }
                } finally {
                    appendToolOutcomes(round, outcomes)
                }
                // Rebuild a model request only after the entire tool batch is paired.
                val invalidStop = invalidToolArgumentsGuard.stopMessage
                val shellStop = shellFailureStopMessage
                if (invalidStop != null && invalidArgumentsInBatch > 0) {
                    retryPostToolFailure(round, AgentInvalidToolArgumentsGuard.STOP_CODE, invalidStop)
                } else if (shellStop != null && shellFailuresInBatch > 0) {
                    retryPostToolFailure(round, AgentShellFailureGuard.STOP_CODE, shellStop)
                } else if (postToolReconnect != null && invalidArgumentsInBatch == 0 && shellFailuresInBatch == 0) {
                    finishPostToolReconnect("succeeded")
                    invalidToolArgumentsGuard.clearExhaustion()
                    shellFailureStopMessage = null
                }
                interruptedTextPrefix.setLength(0)
                round += 1
                continue
                }
            }

            // 上游在输出上限处截断、只回了思考没有正文：本轮没有任何可续写的内容。
            // 必须在 steering 封口和自然结束边界之前重发，否则重试期间用户无法再插话。
            // 同一 round 内重发次数有界；重发是一次全新响应，不走中断续写的前缀投影。
            val truncatedWithoutBody = (content.isBlank() || content == "null") &&
                providerResponse.stopReason == AssistantStopReason.OUTPUT_LIMIT &&
                toolCalls.isEmpty()
            if (truncatedWithoutBody) {
                if (emptyOutputLimitRound != round) {
                    emptyOutputLimitRound = round
                    emptyOutputLimitRetries = 0
                }
                if (emptyOutputLimitRetries < MAX_EMPTY_OUTPUT_LIMIT_RETRIES) {
                    emptyOutputLimitRetries += 1
                    // 只回思考的空响应不进历史，也不进累积推理。
                    discardEmptyOutputLimitAttempt(storedAssistantMessage)
                    accumulatedReasoning.setLength(reasoningLengthBeforeRound)
                    continue
                }
            }

            // Natural completion is not an interrupted reply. Drain queued maintenance
            // at this safe boundary; never insert a continuation or make an extra call.
            maybeCompactBeforeRound(round)

            // 正文回合结束后再注入 steering：已写出的内容留在历史里，下一轮带上补充指令。
            var hasSupplement: Boolean
            do {
                while (runController.hasPendingCompact) maybeCompactBeforeRound(round)
                hasSupplement = appendPendingSteeringOrSeal()
            } while (!hasSupplement && runController.hasPendingCompact)
            if (hasSupplement) {
                interruptedTextPrefix.setLength(0)
                round += 1
                continue
            }

            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                if (truncatedWithoutBody) {
                    val outputTokens = lastUsage?.outputTokens
                    throw AgentOutputLimitException(
                        "模型接口第 $round 轮在输出上限处截断且未返回正文" +
                            "（finish_reason=${finishReason.ifBlank { "unknown" }}" +
                            "，模型=${config.providerType}/${config.modelDisplayName.trim().ifBlank { config.model }}" +
                            (outputTokens?.let { "，输出 token=$it" } ?: "") +
                            "），已自动重试 $emptyOutputLimitRetries 次仍为空",
                    )
                }
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            finishPostToolReconnect("succeeded")
            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length, generatedAtMillis = System.currentTimeMillis()))
            return Result(
                content = (interruptedTextPrefix.toString() + assistantMessage.optString("content")).trim(),
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
        } catch (failure: Throwable) {
            try { finishPostToolReconnect(if (runController.isCancelled) "stopped" else "failed") }
            catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        } finally {
            finishPostToolReconnect(if (runController.isCancelled) "stopped" else "failed")
        }
    }

    /**
     * 下一次模型请求前压缩。
     * 自动压缩在每个请求前及自然结束边界按阈值判断；手动压缩在同一边界消费队列。
     * 工具批次跑完后才会回到这里，因此不会拆掉当前工具循环。
     */
    private fun historyForCompaction() = (systemCount.coerceIn(0, messages.length()) until messages.length())
        .map { AgentConversationCodec.fromJsonObject(messages.getJSONObject(it)) }

    private fun selectRoundTools(candidate: JSONArray) {
        currentRoundTools = delegationArgumentRepair.availableTools(candidate)
        currentRoundToolsTokens = AgentRequestTokenEstimate.tools(currentRoundTools)
    }

    private fun localRequestTokens(): Int =
        AgentRequestTokenEstimate.boundary(
            messages, currentRoundTools, config.supportsVision, config.supportsVideo,
            toolTokens = currentRoundToolsTokens,
        )

    // Compression and send limits use this silent budget, not the ring display.
    private fun requestBudgetTokens(): Int = silentBudget.tokens(localRequestTokens())

    /**
     * Bounded request-shape evidence, emitted only when [AgentToolCallDiagnostics] is already
     * active. The pre-protocol value remains diagnostic-only, alongside the final-body estimate.
     * Neither calculation adds a second hydration or changes the wire body.
     */
    private fun emitRequestContext(
        attempt: AgentToolCallDiagnostics.Attempt?,
        requestMessageCount: Int,
        requestLocal: Int,
        requestHistoryTokens: Int,
        requestFixedTokens: Int,
        preparedRequestTokens: Int?,
        filteredMessages: JSONArray,
        roundTools: JSONArray,
        usage: AgentTokenUsage?,
    ) {
        val active = attempt ?: return
        runCatching {
            val filteredTokens = AgentRequestTokenEstimate.filtered(filteredMessages, roundTools)
            val opaque = AgentRequestContextDiagnostics.replayedOpaque(filteredMessages, config)
            active.emit(
                "request_context",
                AgentRequestContextDiagnostics.localRequestFields(
                    AgentRequestContextDiagnostics.LocalRequest(
                        sourceMessages = requestMessageCount,
                        systemCount = systemCount,
                        boundaryTokens = requestLocal,
                        rawHistoryTokens = requestHistoryTokens,
                        fixedTokens = requestFixedTokens,
                        filteredTokens = filteredTokens,
                        filteredBasis = "pre_protocol_diagnostic",
                        requestBodyTokens = preparedRequestTokens,
                        cloudInput = usage?.inputTokens,
                        cloudCached = usage?.cachedTokens,
                        cloudCacheCreation = usage?.cacheCreationTokens,
                        cloudOutput = usage?.outputTokens,
                        opaqueReplayItems = opaque.first,
                        opaqueEncryptedChars = opaque.second,
                    ),
                ),
            )
        }
    }

    /**
     * 历史在持久化 transcript 尺寸下的字符数。
     *
     * 原先每次调用都要「全量构造 DTO → 逐条 toString → 求和」，而 run() 在一次准备边界内
     * 会为预算判断、自动压缩判定、无效压缩去重等分别调用好几次；长会话里这就是每轮数百 KB
     * 的重复序列化，全部落在 agent-runtime 线程上，直接表现为首字延迟。
     *
     * 这里改为按消息对象身份缓存单条长度：消息一旦写入就不再原地修改（所有变更都是
     * `put` 替换整条），所以用对象身份做键是安全的；增删替换只更新对应的差分。
     * 脱敏会改变长度且 `sensitiveToolCallIds` 只增不减，集合一旦增长就整表失效重算。
     */
    private inner class HistoryCharMeter {
        private val lengths = HashMap<JSONObject, Long>()
        private val tokens = HashMap<JSONObject, Long>()
        private var sensitiveCount = sensitiveToolCallIds.size

        /** 脱敏集合原地增长会让已缓存的消息长度/词元数失效，整表丢弃重算。 */
        private fun syncSensitiveState() {
            if (sensitiveToolCallIds.size == sensitiveCount) return
            lengths.clear()
            tokens.clear()
            sensitiveCount = sensitiveToolCallIds.size
        }

        private fun ensureCacheSane() {
            // 已不在历史里的对象留在缓存里没有意义，长会话会累积；超过两倍规模时回收一次。
            val live = (messages.length() - systemCount).coerceAtLeast(0)
            if (lengths.size <= live * 2 + 16 && tokens.size <= live * 2 + 16) return
            val keep = HashSet<JSONObject>(messages.length())
            for (index in systemCount until messages.length()) {
                messages.optJSONObject(index)?.let(keep::add)
            }
            lengths.keys.retainAll(keep)
            tokens.keys.retainAll(keep)
        }

        /** 持久化 transcript 的字符数，与「全量构造 transcript 再逐条 toString 求和」等价。 */
        fun total(): Long {
            syncSensitiveState()
            if (systemCount >= messages.length()) return 0L
            var total = 0L
            for (index in systemCount until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                total += lengths[message]
                    ?: AgentConversationCodec.durableMessageLength(message, sensitiveToolCallIds)
                        .also { lengths[message] = it }
            }
            ensureCacheSane()
            return total
        }

        /**
         * 本轮请求的历史词元数，与「全量构造 transcript 再逐条 countMessage 求和」等价。
         * 之前每个请求 boundary 都要为它整表重算一次，只用于 UI 校准与诊断。
         */
        fun historyTokens(): Int {
            syncSensitiveState()
            var total = 0L
            for (index in systemCount until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                total += tokens[message] ?: run {
                    val count = AgentConversationCodec.durableMessage(message, sensitiveToolCallIds)
                    AgentContextBudget.countMessage(count).toLong().also { tokens[message] = it }
                }
            }
            ensureCacheSane()
            return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }

    private val historyChars = HistoryCharMeter()

    private fun storedHistoryChars(): Long = historyChars.total()

    private fun persistenceCharLimit(): Long {
        val window = (config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow).toLong()
        val roomBudget = AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS.toLong() - 128_000L
        // 按窗口放大：1MB Room 上限大约只相当于 20 万拉丁 token，500k 窗口会在一半就误暂停。
        return maxOf(roomBudget, window * 6)
    }

    private fun requestOverBudget(): Boolean {
        val storedChars = storedHistoryChars()
        val charLimit = persistenceCharLimit()
        if (storedChars > charLimit) {
            compactionFailure = compactionFailure.ifBlank {
                "本轮历史接近本机持久化容量上限；已暂停，未截断受保护原文。可允许压缩较早步骤或停止任务。"
            }
            return true
        }
        // Hard send limit only, except explicitly permitted unknown contexts. No target
        // receipt keeps the extra 12% reserve; that reserve
        // is not an exact guarantee, and a provider CONTEXT_WINDOW_EXCEEDED still retries once.
        // Automatic 80% scheduling reads same-model cloud input only. Do not open the window to 100%.
        return overHardInputLimit()
    }

    /** Frozen target window, output reserve, and whether this run has a target receipt. */
    private fun hardInputLimit(): Int {
        val window = config.contextWindow?.takeIf { it > 0 } ?: return 0
        return AgentCompressionBoundary.inputLimit(
            window,
            AgentCompressionBoundary.outputReserve(config),
            calibrated = silentBudget.hasTargetReceipt(),
        )
    }

    private fun overHardInputLimit(): Boolean {
        if ((config.contextWindow ?: 0) <= 0) return false
        // Seeds and accepted receipts both retain the original hard guard. A stale cloud
        // display still has an anchor; only an explicitly permitted unknown context is exempt.
        if (unmeasuredContextSendAllowed && !silentBudget.isCalibrated()) return false
        val local = localRequestTokens()
        val calibrated = silentBudget.sendLimitTokens(local)
        // A previous-run seed has no independently verified target receipt. It may tighten,
        // but must never relax, the complete target-model request's conservative local bound.
        val guarded = if (silentBudget.hasTargetReceipt()) calibrated else maxOf(local, calibrated)
        return guarded > hardInputLimit()
    }

    /** Same unchanged history and same cloud receipt. Growth or a new receipt clears it. */
    private fun ineffectiveAutoSameContext(): Boolean {
        if (!skipIneffectiveAutoCompact) return false
        val cloud = silentBudget.cloudTokens() ?: Int.MIN_VALUE
        if (storedHistoryChars() == skippedAutoChars && cloud == skippedAutoCloud) return true
        skipIneffectiveAutoCompact = false
        return false
    }

    private fun markIneffectiveAutoCompact() {
        skipIneffectiveAutoCompact = true
        skippedAutoChars = storedHistoryChars()
        skippedAutoCloud = silentBudget.cloudTokens() ?: Int.MIN_VALUE
    }

    private fun logAutoDecision(round: Int, reason: String, cloud: Int, cut: Int) {
        val now = System.nanoTime()
        if (reason == lastAutoLogReason && now - lastAutoLogNanos < 2_000_000_000L) return
        lastAutoLogReason = reason
        lastAutoLogNanos = now
        val window = config.contextWindow ?: compactPolicy.contextWindow
        runCatching {
            io.github.mangi.eta.core.AndroidAgentLogger.info(
                "auto_compact round=$round reason=$reason cloud=$cloud cut=$cut window=$window",
            )
        }
    }

    private fun noteRingPressure(round: Int) {
        if (ineffectiveAutoSameContext() || !compactPolicy.enabled || overflowPending) return
        val window = config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow
        val cloud = silentBudget.cloudTokens() ?: return
        val hot = cloud >= AgentContextCompactor.autoPressureTokens(window)
        if (!hot) {
            // Once this run has crossed the boundary, a later partial/corrected receipt
            // must not lose the queued maintenance before the round reaches its safe edge.
            return
        }
        if (autoCompactLatched) {
            latchedCloudTokens = maxOf(latchedCloudTokens, cloud)
            return
        }
        latchedCloudTokens = cloud
        autoCompactLatched = true
        onEvent(AgentEvent.AutoCompactWaiting(round))
    }

    private fun releaseAutoCompactWait(round: Int) {
        if (!autoCompactLatched) return
        autoCompactLatched = false
        onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length()))
    }

    private fun maybeCompactBeforeRound(round: Int) {
        val override = runController.takePendingCompact()
        val forced = override != null
        if (forced) { manualBudgetAttempt = true; lastFailedCompaction = null }
        if (!forced && (!compactPolicy.enabled || overflowPending)) {
            logAutoDecision(round, if (!compactPolicy.enabled) "disabled" else "overflow_pending", silentBudget.cloudTokens() ?: -1, -1)
            releaseAutoCompactWait(round)
            return
        }
        val window = config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow
        if (!forced && ineffectiveAutoSameContext() && !requestOverBudget()) {
            logAutoDecision(round, "skip_unchanged", silentBudget.cloudTokens() ?: -1, -1)
            releaseAutoCompactWait(round)
            return
        }
        // 80% 只看圆环上的云端实测。一旦到过线，就等这次输出结束、下次请求之前再压，
        // 不因为后面一张更小的回执取消。估算不排队。硬限制仍走 tryBudgetCompaction。
        if (!forced && storedHistoryChars() > persistenceCharLimit()) {
            logAutoDecision(round, "storage", silentBudget.cloudTokens() ?: -1, -1)
            releaseAutoCompactWait(round)
            return
        }
        val cloudTokens = silentBudget.cloudTokens()
        val dueToRing = autoCompactLatched &&
            latchedCloudTokens >= AgentContextCompactor.autoPressureTokens(window)
        if (!forced && !dueToRing && (cloudTokens == null || cloudTokens < AgentContextCompactor.autoPressureTokens(window))) {
            logAutoDecision(round, if (cloudTokens == null) "no_cloud" else "below", cloudTokens ?: -1, -1)
            return
        }
        if (dueToRing) autoCompactLatched = false
        var decisionTokens = when {
            forced -> requestBudgetTokens()
            dueToRing -> latchedCloudTokens
            else -> requireNotNull(cloudTokens)
        }
        budgetCompressModelConfig = override?.compressModelConfig ?: compactPolicy.compressModelConfig
        val keep = AgentContextCompactor.coerceKeepRecent(
            override?.keepRecentMessages ?: compactPolicy.keepRecentMessages,
        )
        budgetKeepRecent = keep
        var history = historyForCompaction()
        var plan = compactionPlan(history)
        if (plan.stopReason != null) {
            // A missing boundary is temporary. New history/receipts must be retried.
            compactionFailure = plan.stopReason
            onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(), reason = plan.stopReason))
            return
        }
        var cut = plan.cut
        if (cut <= 0) {
            if (!forced) logAutoDecision(round, "no_cut", cloudTokens ?: latchedCloudTokens, cut)
            if (forced) onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                reason = "当前保留范围内没有可压缩的完整历史单元。"))
            else if (dueToRing) onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length()))
            return
        }
        val pruned = pruneOversizedToolResults(round, systemCount + cut)
        if (pruned) {
            if (!forced && !dueToRing) {
                // Pruning changed the context, so the receipt no longer describes the next
                // request. Send it and let the next receipt decide whether to summarize.
                silentBudget.cloudStale()
                overflowPending = false
                skipIneffectiveAutoCompact = false
                return
            }
            // Both the DTO and same-model JSON replay must come from this new snapshot.
            history = historyForCompaction()
            plan = compactionPlan(history)
            if (plan.stopReason != null) {
                // Do not permanently suppress 80% checks while a batch is closing.
                compactionFailure = plan.stopReason
                onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(), reason = plan.stopReason))
                return
            }
            cut = plan.cut
            if (!dueToRing) decisionTokens = requestBudgetTokens()
        }
        if (forced && cut <= 0) {
            onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                reason = "当前保留范围内没有可压缩的完整历史单元。"))
        }
        if (!forced) logAutoDecision(round, "accepted", decisionTokens, cut)
        val reduced = cut > 0 && applyCompaction(round, history, cut, decisionTokens)
        if (reduced || pruned) {
            overflowPending = false
            skipIneffectiveAutoCompact = false
            // A committed summary clears calibration and permits the new unknown context;
            // the next accepted receipt restores the hard guard and decides further maintenance.
        } else if (!forced) {
            markIneffectiveAutoCompact()
        }
    }

    private fun compactionStart(history: List<AgentModelClient.ConversationMessage>): Int = compactionPlan(history).cut

    private fun compactionPlan(history: List<AgentModelClient.ConversationMessage>): AgentCompressionBoundary.RetentionPlan {
        return runCatching {
            AgentCompressionBoundary.planRetention(history,
                config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow,
                overflowPending,
                // Only a target-model receipt converts units. A seed from another model does not.
                billedTokens = if (silentBudget.hasTargetReceipt()) requestBudgetTokens() else null,
                localTokens = if (silentBudget.hasTargetReceipt()) localRequestTokens() else null,
                opaqueItems = ::scopedOpaqueItems)
        }.getOrDefault(AgentCompressionBoundary.RetentionPlan(0))
    }

    /** Responses transport only. Scope hash ignores endpoint mode, so Chat Completions must not opt in. */
    private fun scopedOpaqueItems(message: AgentModelClient.ConversationMessage): Int =
        AgentCompressionBoundary.replayedOpaqueItemCount(message, config)

    private fun tryBudgetCompaction(round: Int): Boolean {
        if (!compactPolicy.enabled && !manualBudgetAttempt) return false
        // Storage pressure alone is not a server context measurement.
        if (!manualBudgetAttempt && !overflowPending && !overHardInputLimit()) return false
        val history = historyForCompaction()
        val plan = compactionPlan(history)
        if (plan.stopReason != null) {
            compactionFailure = plan.stopReason
            return false
        }
        val cut = plan.cut
        if (cut <= 0) return false
        // Commit a pruning-only reduction first. The caller remeasures and takes a
        // fresh snapshot before attempting a summary if pressure is still high.
        if (pruneOversizedToolResults(round, systemCount + cut)) return true
        // Only reached from the hard input/storage or confirmed-provider-overflow guard.
        return applyCompaction(round, history, cut, hardPressure = true)
    }

    /** Only prune the selected prefix; the retained tail is always verbatim. */
    private fun pruneOversizedToolResults(round: Int, endExclusive: Int): Boolean {
        val archive = compactionArchive ?: return false
        val replacements = mutableListOf<Pair<Int, JSONObject>>()
        val checkpoints = mutableListOf<String>()
        try {
            for (index in systemCount until endExclusive) {
                runController.throwIfCancelled()
                if (Thread.currentThread().isInterrupted) throw io.github.mangi.eta.agent.runtime.AgentRunCancelledException()
                val original = messages.getJSONObject(index)
                if (original.optString("role") != "tool" || original.optString("tool_call_id") in sensitiveToolCallIds) continue
                val text = original.opt("content") as? String ?: continue
                if (text.codePointCount(0, text.length) <= 8192 || text.contains("[Eta tool output pruned;")) continue
                val id = archive.save(listOf(AgentConversationCodec.fromJsonObject(original)))
                archive.record(id, "started")
                val head = text.offsetByCodePoints(0, 4096)
                val tail = text.offsetByCodePoints(text.length, -1024)
                val shorter = text.substring(0, head) + "\n[Eta tool output pruned; original: context-checkpoint:$id; read_compacted_history]\n" + text.substring(tail)
                val copy = JSONObject(original.toString()).put("content", shorter)
                if (AgentContextBudget.countTokens(shorter) >= AgentContextBudget.countTokens(text)) continue
                archive.record(id, "ready")
                replacements += index to copy
                checkpoints += id
            }
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            if (Thread.currentThread().isInterrupted || failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) throw failure
            compactionFailure = failure.message ?: "工具原文存档失败，未应用修剪"
            return false
        }
        if (replacements.isEmpty()) return false
        runController.throwIfCancelled()
        replacements.forEach { (index, message) -> messages.put(index, message) }
        // A tool-body edit is not a new context epoch. Keep the latest cloud bill
        // and its local anchor; the silent budget accounts for the trimmed delta.
        // Only a real summary replacement reopens the local display boundary.
        onHistoryCompacted()
        onEvent(AgentEvent.ContextCompacted(round, true, messages.length(), messages.length(),
            history = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds),
            compressorLabel = AgentEvent.PRUNING_ONLY_COMPRESSOR_LABEL, pruningOnly = true))
        checkpoints.forEach { runCatching { archive.record(it, "committed") } }
        return true
    }

    private fun applyCompaction(
        round: Int,
        history: List<AgentModelClient.ConversationMessage>,
        cut: Int,
        decisionTokens: Int = requestBudgetTokens(),
        hardPressure: Boolean = false,
    ): Boolean {
        val window = config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow
        // 80% schedules ordinary automatic summaries, not recovery from a hard limit.
        // A soft no-op must not replace the real archive/summary failure on a later pause.
        if (!manualBudgetAttempt && !hardPressure && decisionTokens < AgentContextCompactor.autoPressureTokens(window)) {
            return false
        }
        val compressConfig = budgetCompressModelConfig?.let { model ->
            val window = model.contextWindow?.takeIf { it > 0 }
                ?: config.contextWindow?.takeIf { it > 0 }
                ?: compactPolicy.contextWindow.takeIf { it > 0 }
            if (window == null) model else model.copy(contextWindow = window)
        } ?: return false
        val original = messages.toString()
        val originalCount = messages.length()
        if (lastFailedCompaction == (original to cut)) return false
        // The silent budget is not a provider bill; display projections remain boundary-only.
        onEvent(AgentEvent.ContextCompactionStarted(round, config.modelDisplayName.ifBlank { config.model }))
        var savedCheckpoint: String? = null
        var compactionStage = "archive"
        runCatching { io.github.mangi.eta.core.AndroidAgentLogger.info(
            "运行中压缩开始：round=$round，选中=$cut，保留=${history.size - cut}，" +
                "保留估算=${AgentCompressionBoundary.retainedTokens(history, cut)}/${AgentCompressionBoundary.retainedTokens(history, 0)}，" +
                "不透明回放单元=${history.drop(cut).count { scopedOpaqueItems(it) > 0 }}（不可折算为输入 token），" +
                "保留上限=${AgentCompressionBoundary.continuationRetentionBudget(window, overflowPending)}，决策=$decisionTokens，本地=${localRequestTokens()}") }
        val rewritten = try {
            val prefix = history.take(cut)
            val tail = history.drop(cut)
            val durablePrefix = AgentConversationCodec.redactSensitiveMessages(prefix, sensitiveToolCallIds)
            savedCheckpoint = compactionArchive?.save(durablePrefix)
            savedCheckpoint?.let { compactionArchive?.record(it, "started") }
            savedCheckpoint?.let { compactionArchive?.canAttach(it, prefix.size.coerceAtLeast(tail.size + 1), tail.size) }
            compactionStage = "summary"
            val summarySource = durablePrefix + tail
            val compressed = if (compactHistory != null) {
                compactHistory.invoke(summarySource, compactPolicy.copy(keepRecentMessages = budgetKeepRecent, compressModelConfig = compressConfig, keepStartOverride = cut))
            } else AgentContextCompactor.compress(
                summarySource, AgentContextCompactor.Config(
                    budgetKeepRecent,
                    compressConfig,
                    compactionArchive = compactionArchive,
                    usageConversationId = sessionId,
                    sourceModelConfig = config.copy(contextWindow = window),
                    summaryTokenBudget = AgentCompressionBoundary.summaryProgressBudget(window),
                    onErrorReconnect = { onEvent(it.copy(round = round)) },
                ),
                keepStartOverride = cut, controller = runController,
                replay = if (compressConfig.providerType == config.providerType && compressConfig.baseUrl == config.baseUrl &&
                    compressConfig.model == config.model && compressConfig.openAiEndpointMode == config.openAiEndpointMode)
                    AgentContextCompactor.ReplayContext(
                        JSONArray().also { a -> for (i in 0 until systemCount) a.put(messages.getJSONObject(i)) },
                        JSONArray().also { a -> durablePrefix.forEach { a.put(AgentConversationCodec.toJsonObject(it)) } },
                        currentRoundTools, sessionId,
                    ) else null,
            )
            compactionStage = "validate"
            require(compressed.size >= tail.size && compressed.takeLast(tail.size) == tail) { "摘要后受保护尾部发生变化" }
            require(compressed != history) { "没有可压缩历史" }
            runController.throwIfCancelled()
            require(messages.toString() == original) { "摘要生成期间上下文已变化，未应用摘要" }
            val withPointers = savedCheckpoint?.let {
                requireNotNull(compactionArchive).attachReferences(durablePrefix, it, compressed, tail.size)
            } ?: compressed
            require(AgentCompressionBoundary.compactionReduced(
                history, withPointers, tail.size,
                AgentCompressionBoundary.summaryProgressBudget(window),
                countsOpaque = { scopedOpaqueItems(it) > 0 },
            )) {
                "摘要及索引未减少上下文，已保留原文"
            }
            savedCheckpoint?.let { compactionArchive?.record(it, "ready") }
            withPointers
        } catch (failure: Exception) {
            savedCheckpoint?.let { runCatching { compactionArchive?.record(it, "failed") } }
            if (runController.isCancelled || Thread.currentThread().isInterrupted || failure is InterruptedException ||
                failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) throw failure
            lastFailedCompaction = original to cut
            compactionFailure = failure.message ?: "压缩失败，原文保留"
            runCatching { io.github.mangi.eta.core.AndroidAgentLogger.warn(
                "运行中压缩失败：stage=$compactionStage，checkpoint=$savedCheckpoint，round=$round，${failure.javaClass.simpleName}: $compactionFailure") }
            onEvent(AgentEvent.ContextCompacted(round, false, originalCount, originalCount, reason = compactionFailure))
            return false
        }
        // Never reserialize the kept live tail through the persistence DTO. That would strip
        // provider-only response items, images, and long tool bodies even in protected mode.
        val keptJson = (systemCount + cut until messages.length()).map { messages.getJSONObject(it) }
        val prefixJson = (0 until systemCount).map { messages.getJSONObject(it) }
        val newPrefix = rewritten.dropLast(history.size - cut)
        while (messages.length() > 0) messages.remove(messages.length() - 1)
        prefixJson.forEach(messages::put)
        newPrefix.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        keptJson.forEach(messages::put)
        lastUsage = null
        requestBudget.contextReplaced()
        hasDisplayCloudReceipt = false
        silentBudget.contextReplaced()
        unmeasuredContextSendAllowed = true
        autoCompactLatched = false
        compactionFailure = ""
        onHistoryCompacted()
        onEvent(AgentEvent.ContextCompacted(round, true, originalCount, messages.length(),
            history = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds),
            compressorLabel = compressorLabel(compressConfig)))
        savedCheckpoint?.let { runCatching { compactionArchive?.record(it, "committed") } }
        runCatching { io.github.mangi.eta.core.AndroidAgentLogger.info(
            "运行中压缩已提交：checkpoint=$savedCheckpoint，round=$round，消息=$originalCount->${messages.length()}") }
        return true
    }

    private fun requestConfigForRound(): AgentModelClient.ModelConfig {
        val next = if (suppressThinkingForNextRequest) {
            suppressThinkingForNextRequest = false
            AgentRuntimePolicy.withoutOptionalThinking(config)
        } else config
        return if (localOnlyRecovery) next.copy(hostedWebSearchEnabled = false) else next
    }

    private fun compressorLabel(config: AgentModelClient.ModelConfig): String {
        val provider = config.providerName.trim()
        val model = config.modelDisplayName.trim().ifBlank { config.model.trim() }
        return when {
            provider.isNotBlank() && model.isNotBlank() -> "$provider · $model"
            model.isNotBlank() -> model
            else -> provider
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        var appended = false
        // Drain at a safe boundary, before opening another model request.
        while (true) {
            val supplement = runController.pollSteeringInput() ?: break
            supplementStartsNewBlock = true
            messages.put(AgentSupplementMedia.userMessage(steeringPrompt(supplement.text), supplement.imagesJson).put(AgentTurnIdentity.JSON_KEY, turnId))
            appended = true
        }
        return appended
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringInputOrSeal() ?: return false
        supplementStartsNewBlock = true
        messages.put(AgentSupplementMedia.userMessage(steeringPrompt(supplement.text), supplement.imagesJson).put(AgentTurnIdentity.JSON_KEY, turnId))
        appendPendingSteeringMessage()
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        AgentContextCompactor.steeringUserContent(supplement)

    /**
     * 中断后追加续写指令。正文被截断时接在 assistant 之后续写；暂停发生在思考阶段时
     * 换成接续思考的指令，否则恢复的请求只多了一条通用续写要求，而模型看不到自己的
     * 思考，只能从头重新分析整轮对话。两种情况都保留用户配置的思考开关。
     */
    private fun appendContinuePromptAfterInterrupt(thinkingOnly: Boolean) {
        if (!thinkingOnly) {
            appendCompactContinueIfNeeded(suppressOptionalThinking = false)
            return
        }
        val last = messages.optJSONObject(messages.length() - 1) ?: return
        if (!last.optString("role").equals("assistant", ignoreCase = true)) return
        messages.put(
            AgentConversationCodec.userTextMessage(
                AgentContextCompactor.SEAMLESS_CONTINUE_THINKING_PROMPT,
            ).put(AgentTurnIdentity.JSON_KEY, turnId),
        )
    }

    private fun appendCompactContinueIfNeeded(suppressOptionalThinking: Boolean = true) {
        val last = messages.optJSONObject(messages.length() - 1) ?: return
        if (!last.optString("role").equals("assistant", ignoreCase = true)) return
        messages.put(
            AgentConversationCodec.userTextMessage(
                AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT,
            ).put(AgentTurnIdentity.JSON_KEY, turnId),
        )
        suppressThinkingForNextRequest = suppressOptionalThinking
    }

    /**
     * 结果直接追加进 [outcomes]，而不是返回一个新列表：中途取消时，
     * 已经完成的调用仍要留在历史里（调用方在 finally 里配对写入）。
     */
    private fun executeToolBatch(
        round: Int,
        toolCalls: List<AgentModelClient.ToolCall>,
        outcomes: MutableList<ToolOutcome>,
    ) {
        val parallel = AgentPureReadTools.parallelIndices(toolCalls.map { it.name })
        if (parallel.isEmpty()) {
            toolCalls.forEachIndexed { index, call -> outcomes += executeTool(round, call, index) }
            return
        }
        val outcomesArray = arrayOfNulls<ToolOutcome>(toolCalls.size)
        val buffers = toolCalls.indices.map { mutableListOf<AgentEvent>() }
        val workers = pureReadWorkers(toolCalls.size, parallel.size)
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            toolCalls.forEachIndexed { index, call ->
                if (index !in parallel) return@forEachIndexed
                workers.execute {
                    try {
                        // 只读工具不碰 shellFailureState / sensitiveToolCallIds：
                        // 这些共享状态的合并留在下方的串行回放里做。
                        outcomesArray[index] = executeToolBody(round, call, index, buffers[index])
                    } catch (failure: Throwable) {
                        errors += failure
                    }
                }
            }
        } finally {
            workers.shutdown()
            // 取消会中断在途请求；worker 自行观察 runController 的取消信号。
            while (!workers.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                runController.throwIfCancelled()
            }
        }
        // 按模型给出的 index 顺序回填已完成的结果，再决定是否继续。
        toolCalls.indices.forEach { index ->
            outcomesArray[index]?.let(outcomes::add)
        }
        buffers.forEach { buffer -> buffer.forEach(onEvent) }
        runController.throwIfCancelled()
        errors.firstOrNull()?.let { failure ->
            throw failure as? Exception ?: IllegalStateException(failure)
        }
        check(outcomesArray.none { it == null }) { "并行工具批次缺少结果" }
    }

    private fun pureReadWorkers(toolCount: Int, parallelCount: Int): java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newFixedThreadPool(
            minOf(MAX_PARALLEL_READ_TOOLS, parallelCount, toolCount),
        ) { runnable ->
            Thread(runnable, "agent-pure-read").apply { isDaemon = true }
        }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        toolIndex: Int,
    ): ToolOutcome = executeToolBatchEntry(round, toolCall, toolIndex, events = null)

    /**
     * 同一批次里的幂等只读工具可以并行执行（网络/IO 等待重叠），其余仍串行。
     *
     * 事件顺序必须与模型给出的 index 一致，所以并行分支把每个调用的事件按 [BufferedEvents]
     * 暂存，待全部完成后按 index 顺序回放；共享可变状态（shellFailureState、
     * sensitiveToolCallIds、invalidArgumentsInBatch、诊断尝试）只在主线程按同样顺序合并，
     * 因此模型看到的历史与串行执行时完全一致。
     */
    private fun executeToolBatchEntry(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        toolIndex: Int,
        events: MutableList<AgentEvent>?,
    ): ToolOutcome = executeToolBody(round, toolCall, toolIndex, events)

    private fun executeToolBody(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        toolIndex: Int,
        events: MutableList<AgentEvent>?,
    ): ToolOutcome {
        runController.throwIfCancelled()
        if (toolCall.name == AgentDelegationArgumentRepair.TOOL && delegationArgumentRepair.disabled) {
            // Exhaustion was already reported. Complete protocol pairing without another failed card.
            return ToolOutcome(toolCall, delegationArgumentRepair.reject("本轮委派已停用", round))
        }
        val validationError = toolCallValidator.validate(toolCall)
        toolDiagnosticAttempt?.validation(toolCall, validationError == null, toolIndex)
        validationError?.let { validationError ->
            if (toolCall.name == AgentDelegationArgumentRepair.TOOL && toolCallValidator.declares(toolCall.name)) {
                val repair = delegationArgumentRepair.reject(validationError, round)
                sensitiveToolCallIds += toolCall.id
                if (!delegationArgumentRepair.disabled) {
                    if (delegationRepairNotifiedRound != round) {
                        delegationRepairNotifiedRound = round
                        onEvent(AgentEvent.ModelRetryScheduled(
                            round = round, attempt = delegationArgumentRepair.attempts,
                            maxAttempts = AgentDelegationArgumentRepair.MAX_REPAIRS, delayMs = 0,
                            reasonCode = AgentDelegationArgumentRepair.REPAIR_CODE,
                            reasonDetail = validationError,
                        ))
                    }
                    // The next regular model round receives this tool result and regenerates ONLY the rejected call.
                    return ToolOutcome(toolCall, repair)
                }
                return rejectedToolOutcome(round, toolCall, "DELEGATION_ARGUMENT_REPAIR_EXHAUSTED",
                    "委派参数补全失败，本轮已停用新委派；未创建子任务，已有子任务不受影响。请主代理接手。")
            }
            val rejection = invalidToolArgumentsGuard.reject(
                toolName = toolCall.name,
                declared = toolCallValidator.declares(toolCall.name),
                validationError = validationError,
            )
            invalidArgumentsInBatch++
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = rejection.code,
                message = rejection.message,
            )
        }
        if (toolCall.name != AgentDelegationArgumentRepair.TOOL) {
            invalidToolArgumentsGuard.validated(toolCall.name)
        }
        emit(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
                argumentsDetail = traceFormatter.detailArguments(toolCall),
            ),
            events,
        )

        toolDiagnosticAttempt?.dispatch(toolCall, toolIndex)
        val rawResult = try {
            if (toolCall.name == AgentCompactionArchive.TOOL && compactionArchive != null) {
                compactionArchive.read(toolCall.argumentsJson)
            } else toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            if (throwable is io.github.mangi.eta.agent.question.AgentQuestionInterruptedException ||
                throwable is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) throw throwable
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        val shellDecision = AgentShellFailureGuard.observe(shellFailureState, toolCall, rawResult)
        shellFailureState = shellDecision.state
        if (shellFailureStopMessage == null) shellFailureStopMessage = shellDecision.stopMessage
        if (shellDecision.stopMessage != null) shellFailuresInBatch++
        val result = shellDecision.result
        // Record what the model actually receives, plus the pre-guard code for comparison.
        toolDiagnosticAttempt?.result(toolCall, result, toolIndex, rawResult)
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        // Once returned, this result remains evidence even if stop arrived concurrently.
        emitToolFinished(round, toolCall, result, events)
        return ToolOutcome(toolCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
                argumentsDetail = traceFormatter.detailArguments(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    /** 并行分支把事件暂存，稍后按模型给出的 index 顺序回放；串行分支直接投递。 */
    private fun emit(event: AgentEvent, buffer: MutableList<AgentEvent>?) {
        if (buffer == null) onEvent(event) else buffer += event
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
        events: MutableList<AgentEvent>? = null,
    ) {
        emit(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
                resultDetail = traceFormatter.detailResult(toolCall.name, result),
                mediaPreviewsJson = AgentToolMediaPreviews.encode(result.images),
            ),
            events,
        )
    }

    private fun appendToolOutcomes(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // Provider 要求同一 assistant 批次的全部 tool result 连续出现；图片观察统一放在批次之后。
        outcomes.forEach { outcome ->
            val message = AgentConversationCodec.toolResultMessage(outcome.call, outcome.result).put(AgentTurnIdentity.JSON_KEY, turnId)
            messages.put(message)
            toolDiagnosticAttempt?.historyResult(outcome.call, message)
        }

        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) return

        // 工具截图是瞬时观察，不是会话资产。下一次推理消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.\n" +
                imageOutcomes.joinToString("\n") { AuxiliaryVision.observationMetadata(it.result.content) } +
                images.mapIndexed { index, image -> "image ${index + 1}: ${image.width ?: "unknown"} x ${image.height ?: "unknown"} px" }.joinToString("\n", prefix = "\n"),
            images = images,
        ).put(AgentTurnIdentity.JSON_KEY, turnId).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    /** 空正文截断重发前丢弃本轮刚写入的 assistant 消息：历史里不留只回思考的空回合。 */
    private fun discardEmptyOutputLimitAttempt(storedAssistant: JSONObject?) {
        val stored = storedAssistant ?: return
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === stored) {
                messages.remove(index)
                responseStored = false
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.RequestEstimate -> null // Published above, never a cloud receipt.
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

    private companion object {
        /** 上游在输出上限处截断且正文为空时，同一 round 内最多自动重发的次数。 */
        private const val MAX_EMPTY_OUTPUT_LIMIT_RETRIES = 2

        /** 一个只读批次里同时执行的工具数上限。 */
        private const val MAX_PARALLEL_READ_TOOLS = 4
    }
}
