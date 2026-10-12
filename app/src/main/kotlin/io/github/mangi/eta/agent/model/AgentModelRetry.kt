package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CancellationException

/** Retries only a failed model request, never a run, committed history, or local tools. */
internal class AgentModelRetry(
    private val timing: ReconnectTiming = SystemReconnectTiming,
    /**
     * 退避抖动比例，取值 0..1。多台设备共用一个账号时，确定性的 2s/4s/8s 会让它们
     * 在同一时刻重发，互相延长限流恢复时间；测试传 0 以获得确定的退避序列。
     */
    private val jitterFraction: Double = ModelRetryBackoff.DEFAULT_JITTER_FRACTION,
    private val jitterSource: () -> Double = { Math.random() },
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    /**
     * 同一失败原因下的永久错误重发上限。永久错误只允许这一次：之后直接把原因交给用户，
     * 不再无限烧配额，也不把「需要改配置」伪装成「网络在抖」。
     */
    private fun permanentResendLimit(policy: ErrorReconnectPolicy): Int =
        ModelRetryBackoff.permanentResendBudget(policy.windowMillis)

    private fun backoffMs(
        attempt: Int,
        failure: AgentModelFailure,
        state: ModelErrorReconnect?,
    ): Long = ModelRetryBackoff.delayMs(
        attempt = attempt,
        retryAfterSec = failure.retryAfterSec,
        remainingMs = state?.remainingMs(),
        jitterFraction = jitterFraction,
        jitterSource = jitterSource,
    )

    /**
     * 永久错误是否真的不可恢复：OAuth 每次尝试都重新取 token，用户在窗口内重新登录可以救活；
     * API Key 与额度/路径类错误重发同一个请求永远不可能变好。
     */
    private fun isUnrecoverable(failure: AgentModelFailure, config: AgentModelClient.ModelConfig): Boolean =
        failure.retryMode == AgentModelFailure.RetryMode.PERMANENT &&
            AgentModelFailure.permanentForAuthMode(failure.permanentReason, config.authMode)

    data class Result(
        val round: Int,
        val response: ProviderResponse,
        val toolDiagnosticAttempt: AgentToolCallDiagnostics.Attempt? = null,
        val reconnectLocalToolsOnly: Boolean = false,
    )

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
        onCancelledResponse: (ProviderResponse) -> Unit = {},
        onAttemptStarted: (Int, JSONArray) -> String = { _, _ -> "" },
        onRecoveryActivated: () -> Unit = {},
        requestReconnect: ModelErrorReconnect? = null,
    ): Result {
        var round = initialRound
        var envelopeRetries = 0
        var retriesInCycle = 0
        var attemptRequest = request
        var localOnlyRecovery = request.reconnectLocalToolsOnly
        val reconnectPolicy = ErrorReconnectPolicy.fromPersistedValue(request.config.errorReconnectPolicy)
        val reconnectEnabled = reconnectPolicy != ErrorReconnectPolicy.NONE
        var reconnect: ModelErrorReconnect? = null
        var reconnectBinding: AutoCloseable? = null
        // 永久错误的重发次数按 reasonKey 归组：换一种错误（例如用户改了 Key 后又 403→404）
        // 重新给一次机会，但同一原因绝不会进入无限重连。
        var permanentResends = 0
        var permanentReasonKey: String? = null
        val prefix = StringBuilder()
        fun beginReconnect(reason: AgentModelFailure): ModelErrorReconnect {
            val current = reconnect
            if (current != null) {
                current.updateReason(reason)
                return current
            }
            return ModelErrorReconnect(initialRound,
                reconnectPolicy.windowMillis,
                timing, onEvent, reason, listOf(request.config.apiKey)).also {
                reconnect = it
                // Stop emits its terminal marker immediately, including while waiting/in flight.
                reconnectBinding = it.bind(controller)
                it.start()
            }
        }
        fun finishReconnect(status: String) {
            val current = reconnect ?: return
            current.finish(status)
            reconnectBinding?.close()
            reconnectBinding = null
            reconnect = null
            retriesInCycle = 0
            // 恢复成功后，同一原因的下次永久错误属于一次新的断连，重新给一次有界重发。
            if (status == "succeeded") {
                permanentResends = 0
                permanentReasonKey = null
            }
        }
        try {
            while (true) {
                controller.throwIfCancelled()
                reconnect?.check()
                requestReconnect?.check()
                val historySnapshotId = onAttemptStarted(round, attemptRequest.messages)
                onEvent(AgentEvent.RoundStarted(round, attemptRequest.messages.length(), historySnapshotId))
                var toolDeliveryPossible = false
                var callbackFailure: Throwable? = null
                var attemptModelFailure: AgentModelFailure? = null
                var sawCompleted = false
                val textBlocks = linkedMapOf<Int, StringBuilder>()
                val textFilter = AgentContinuationTextEvents(prefix.toString())
                val deliveryGate = ProviderEventDeliveryGate()
                val repetitionGuard = ReasoningRepetitionGuard()
                val textRepetitionGuard = ReasoningRepetitionGuard()
                val scope = controller.newTransportScope()
                reconnect?.attach(scope)
                requestReconnect?.attach(scope)
                val toolAttempt = request.toolDiagnostics?.beginAttempt(round, provider.id)
                    ?: request.toolDiagnosticAttempt
                val diagnosticRequest = if (toolAttempt == null) attemptRequest
                    else attemptRequest.copy(toolDiagnosticAttempt = toolAttempt)
                fun repetitive(surface: String) = AgentModelFailure("MODEL_REPETITIVE_REASONING", false,
                    "检测到模型${surface}持续高度重复，已中止本次请求；按所选重连策略处理，此前工具结果已保留。")
                fun deliver(event: ProviderEvent) {
                    if (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TEXT) {
                        textBlocks.getOrPut(event.index) { StringBuilder() }.append(event.delta)
                    }
                    if (event is ProviderEvent.BlockEnd && event.kind == AssistantBlockKind.TEXT &&
                        (event.replaceContent || textBlocks[event.index].isNullOrEmpty())) {
                        textBlocks[event.index] = StringBuilder(event.content)
                    }
                    try {
                        onProviderEvent(round, event)
                    } catch (failure: Throwable) {
                        callbackFailure = failure
                        throw failure
                    }
                }
                try {
                    val response = try {
                        scope.run {
                            provider.complete(diagnosticRequest, controller) { event ->
                                deliveryGate.deliver {
                                    if (scope.isExpired || controller.isCancelled) return@deliver
                                    if (when (event) {
                                            is ProviderEvent.HostedToolStarted, is ProviderEvent.HostedToolFinished -> true
                                            is ProviderEvent.BlockStart -> event.kind == AssistantBlockKind.TOOL_CALL
                                            is ProviderEvent.BlockDelta -> event.kind == AssistantBlockKind.TOOL_CALL
                                            is ProviderEvent.BlockEnd -> event.kind == AssistantBlockKind.TOOL_CALL
                                            else -> false
                                        }) toolDeliveryPossible = true
                                    if (event is ProviderEvent.Completed) sawCompleted = true
                                    callbackFailure?.let { throw it }
                                    attemptModelFailure?.let { throw it }
                                    reconnect?.check()
                                    requestReconnect?.check()
                                    when {
                                        event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.THINKING -> {
                                            if (repetitionGuard.append(event.delta)) {
                                                val failure = repetitive("思考")
                                                attemptModelFailure = failure
                                                throw failure
                                            }
                                        }
                                        event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TEXT -> {
                                            if (event.delta.isNotBlank()) repetitionGuard.reset()
                                            if (textRepetitionGuard.append(event.delta)) {
                                                val failure = repetitive("正文")
                                                attemptModelFailure = failure
                                                throw failure
                                            }
                                        }
                                        event is ProviderEvent.HostedToolStarted || event is ProviderEvent.HostedToolFinished ||
                                            (event is ProviderEvent.BlockStart && event.kind == AssistantBlockKind.TOOL_CALL) ||
                                            (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL && event.delta.isNotBlank()) -> {
                                            repetitionGuard.reset()
                                            textRepetitionGuard.reset()
                                        }
                                    }
                                    // Connection recovery is not completion of the whole response.
                                    // Headers/usage/empty blocks are insufficient; executable local
                                    // calls still require a complete, validated provider response.
                                    val recovered = when (event) {
                                        is ProviderEvent.BlockDelta -> event.delta.isNotBlank()
                                        is ProviderEvent.BlockEnd -> event.content.isNotBlank()
                                        else -> false
                                    }
                                    if (recovered && !(diagnosticRequest.reconnectTextOnly &&
                                            (event is ProviderEvent.BlockDelta && event.kind == AssistantBlockKind.TOOL_CALL ||
                                                event is ProviderEvent.BlockEnd && event.kind == AssistantBlockKind.TOOL_CALL))) {
                                        try {
                                            finishReconnect("succeeded")
                                        } catch (failure: Throwable) {
                                            callbackFailure = failure
                                            throw failure
                                        }
                                    }
                                    textFilter.map(event).forEach(::deliver)
                                }
                            }
                        }
                    } finally {
                        deliveryGate.close()
                        reconnect?.detach(scope)
                        requestReconnect?.detach(scope)
                    }
                    callbackFailure?.let { throw it }
                    attemptModelFailure?.let { throw it }
                    reconnect?.check()
                    requestReconnect?.check()
                    try {
                        controller.throwIfCancelled()
                    } catch (cancelled: AgentRunCancelledException) {
                        // Preserve redaction evidence, never a successful response or a tool
                        // execution. Observer failures must not become transport retries.
                        try {
                            onCancelledResponse(response)
                        } catch (failure: Throwable) {
                            callbackFailure = failure
                            throw failure
                        }
                        throw cancelled
                    }
                    if ((reconnect != null || requestReconnect != null) && scope.isExpired) {
                        // The cancelled attempt may return after a fast resume. Never accept
                        // its body/tools as a fresh recovery response or reset the budget.
                        throw AgentModelFailure("MODEL_RECONNECT_PAUSED", false,
                            "重连请求因暂停中断；继续后使用剩余重连时限。")
                    }
                    if (attemptRequest.reconnectTextOnly && (toolDeliveryPossible ||
                        (response.assistantMessage.optJSONArray("tool_calls")?.length() ?: 0) > 0)) {
                        throw AgentModelFailure("RECONNECT_TOOL_CALL_BLOCKED", false,
                            "重连请求只允许续写正文，未执行返回的工具调用；将继续请求直到恢复或期限结束。")
                    }
                    if (!controller.isCancelled) textFilter.finish().forEach(::deliver)
                    if (prefix.isNotEmpty()) {
                        val tail = textFilter.normalize(response.assistantMessage.optString("content").takeUnless { it == "null" }.orEmpty())
                        response.assistantMessage.put("content", prefix.toString() + tail)
                    }
                    toolAttempt?.providerParsed(response.assistantMessage)
                    // An intentional steering/pause draft is NOT a recovered complete response.
                    finishReconnect(if (controller.isCancelled || controller.hasPausedInterrupt ||
                        response.stopReason == AssistantStopReason.INTERRUPTED) "stopped" else "succeeded")
                    return Result(round, response, toolAttempt, localOnlyRecovery)
                } catch (failure: Exception) {
                    toolAttempt?.failed((failure as? AgentModelFailure)?.code ?: "PROVIDER_EXCEPTION")
                    callbackFailure?.let { throw it }
                    reconnect?.check()
                    requestReconnect?.check()
                    controller.throwIfCancelled()
                    val pausedRecovery = (reconnect != null || requestReconnect != null) && scope.isExpired &&
                        reconnect?.expired != true && requestReconnect?.expired != true &&
                        !controller.hasPendingImmediateSteering && !controller.isCancelled
                    if (pausedRecovery) controller.consumePausedInterrupt()
                    if (failure is AgentRunCancelledException || failure is InterruptedException ||
                        Thread.currentThread().isInterrupted ||
                        (failure is CancellationException && !pausedRecovery)) throw failure
                    val partial = textBlocks.values.joinToString("") { it.toString() }
                    if ((controller.hasPendingImmediateSteering || controller.hasPausedInterrupt) &&
                        !toolDeliveryPossible && !sawCompleted && failure !is AgentModelFailure) {
                        reconnect?.finish("stopped")
                        return Result(round, ProviderResponse(interruptedAssistantMessage(prefix.toString() + partial, "")),
                            reconnectLocalToolsOnly = localOnlyRecovery)
                    }
                    val classified = if (pausedRecovery) AgentModelFailure("MODEL_RECONNECT_PAUSED", false,
                        "重连请求因暂停中断；继续后使用剩余重连时限。", failure)
                        else attemptModelFailure ?: AgentModelFailure.transport(failure)
                        ?: if (reconnectEnabled) AgentModelFailure("PROVIDER_EXCEPTION", false,
                            "模型请求发生异常；保留已有结果并按所选重连策略继续请求。", failure)
                        else throw failure
                    // A local unsupported tier cannot recover by resending the same configuration.
                    if (classified.code == GptServiceTier.UNSUPPORTED_CODEX_TIER) throw classified
                    if (classified.code == "CONTEXT_WINDOW_EXCEEDED") throw classified
                    val envelopeRejected = classified.code == ResponsesToolEnvelopeRecovery.CODE
                    val correctionAllowed = envelopeRejected && classified.envelopeCorrectionAllowed &&
                        provider.capabilities.endpoint == EndpointKind.RESPONSES
                    // A gateway HTTP error cannot prove a hosted operation never ran.
                    val unsafeHostedReplay = request.config.hostedWebSearchEnabled && !correctionAllowed
                    val guarded = classified.code in setOf("MODEL_REPETITIVE_REASONING",
                        "RESPONSES_TOOL_CALL_INCOMPLETE", "RESPONSES_TOOL_ARGUMENTS_INCOMPLETE")
                    if (!reconnectEnabled && (toolDeliveryPossible || sawCompleted || unsafeHostedReplay || guarded ||
                        (envelopeRejected && !correctionAllowed))) {
                        val protected = if (!guarded && (toolDeliveryPossible || unsafeHostedReplay)) AgentModelFailure(
                            "UNSAFE_TOOL_REPLAY", false,
                            "请求中已有工具证据或远端工具执行状态未知，未重发以免重复副作用；已保留此前正文和工具证据。", classified)
                        else classified
                        beginReconnect(protected).finish("failed")
                        throw protected
                    }
                    if (envelopeRejected && !reconnectEnabled) {
                        if (envelopeRetries >= ResponsesToolEnvelopeRecovery.MAX_RETRIES) throw AgentModelFailure(
                            classified.code, false,
                            "工具封装 JSON 校验连续失败，停止纠错；未执行被拒绝的工具调用。", classified)
                        envelopeRetries++
                        attemptRequest = ResponsesToolEnvelopeRecovery.corrected(request)
                        val delay = backoffMs(envelopeRetries, classified, reconnect)
                        onEvent(AgentEvent.ModelRetryScheduled(round, envelopeRetries,
                            ResponsesToolEnvelopeRecovery.MAX_RETRIES, delay.toInt(), classified.code,
                            AgentHttpFailureDiagnostics.safe(classified.message.orEmpty(), listOf(request.config.apiKey), 600)))
                        waitBeforeRetry(controller, delay)
                    } else {
                        val state = beginReconnect(classified)
                        state.check()
                        if (!reconnectEnabled) throw classified
                        // 永久性错误（API Key 失效、额度/计费受限、模型或路径不存在）重发同一个请求
                        // 不会变好。此前它们和网络抖动共用无限重连窗口，结果是直到用户手动停止都在
                        // 烧配额，并把「需要改配置」伪装成「网络在抖」。这里只保留一次立即重发，
                        // 用于容忍误判或刚好落地的配置写入；同一原因再次出现就直接结束。
                        val unrecoverable = isUnrecoverable(classified, request.config)
                        if (unrecoverable) {
                            val reasonKey = "${classified.code}:${classified.permanentReason}"
                            if (permanentReasonKey != reasonKey) {
                                permanentReasonKey = reasonKey
                                permanentResends = 0
                            }
                            if (permanentResends >= permanentResendLimit(reconnectPolicy)) throw classified
                            permanentResends++
                        }
                        if (!localOnlyRecovery) {
                            localOnlyRecovery = true
                            // Persist the restriction before any retry/overflow can escape this call.
                            onRecoveryActivated()
                        }
                        // Local tool fragments are not executions: AgentLoop runs only a
                        // validated returned batch. Discard failed fragments, not the catalog.
                        // Hosted operations and opaque remote continuation remain disabled.
                        if (envelopeRejected && correctionAllowed &&
                            envelopeRetries < ResponsesToolEnvelopeRecovery.MAX_RETRIES) {
                            envelopeRetries++
                        }
                        // 暂时性错误沿用三次退避周期（2s、4s、8s），周期用尽不消耗用户的重连窗口；
                        // 永久错误的那一次重发用第一档，并且 maxAttempts 显示为 1，避免界面把它
                        // 读成「还有两次机会」。两者都尊重提供方的 Retry-After 下限。
                        retriesInCycle = if (unrecoverable) 1 else retriesInCycle % MAX_RETRIES + 1
                        val maxAttempts = if (unrecoverable) 1 else MAX_RETRIES
                        val delay = backoffMs(retriesInCycle, classified, state)
                        onEvent(AgentEvent.ModelRetryScheduled(round, retriesInCycle,
                            maxAttempts, delay.toInt(), classified.code,
                            AgentHttpFailureDiagnostics.safe(classified.message.orEmpty(), listOf(request.config.apiKey), 600),
                            permanent = unrecoverable))
                        waitBeforeRetry(controller, delay)
                        if (controller.isCancelled) throw AgentRunCancelledException()
                        // Pause is a checkpoint inside this disconnect, not completion of it.
                        // Only explicit steering hands a draft back to the outer loop.
                        controller.throwIfCancelled()
                        if (controller.hasPendingImmediateSteering &&
                            !toolDeliveryPossible && !sawCompleted && !unsafeHostedReplay) {
                            textFilter.finish().forEach(::deliver)
                            val partial = textBlocks.values.joinToString("") { it.toString() }
                            finishReconnect("stopped")
                            return Result(round, ProviderResponse(interruptedAssistantMessage(prefix.toString() + partial, "")),
                                reconnectLocalToolsOnly = localOnlyRecovery)
                        }
                        controller.throwIfCancelled()
                        state.check()
                        textFilter.finish().forEach(::deliver)
                        prefix.append(textBlocks.values.joinToString("") { it.toString() })
                        // Rebuild only from committed history and delivered text. Never replay
                        // partial call arguments, opaque failed output, or fabricated tool results.
                        localOnlyRecovery = true
                        val messages = JSONArray(request.messages.toString())
                        AgentRecoveryContext.append(messages)
                        if (prefix.isNotEmpty()) messages.put(JSONObject()
                            .put("role", "assistant").put("content", prefix.toString()))
                        messages.put(JSONObject().put("role", "user").put("content",
                            "Continue the interrupted task from where it stopped without repeating delivered text. " +
                                "Preserve completed tools and their results; do not repeat completed operations. " +
                                "Unfinished local tool calls from the interrupted request were NOT executed; discard their fragments. " +
                                "For further actions, issue new complete calls through the available tool channel. " +
                                "Never print tool-call syntax or JSON as a substitute for calling a tool. " +
                                "Do not repeat or locally substitute remote operations whose outcome is unknown. " +
                                "Use new complete local calls only for independent unfinished work or read-only verification."))
                        attemptRequest = attemptRequest.copy(
                            messages = messages,
                            config = attemptRequest.config.copy(hostedWebSearchEnabled = false),
                            tools = request.tools,
                            reconnectLocalToolsOnly = true,
                        )
                        // Rebuilding history must not erase the guarded serialization hint.
                        if (envelopeRetries > 0) attemptRequest = ResponsesToolEnvelopeRecovery.corrected(attemptRequest)
                    }
                    controller.throwIfCancelled()
                    reconnect?.check()
                    discardAttemptReasoning()
                    round++
                }
            }
        } catch (failure: Throwable) {
            val stopped = controller.isCancelled || failure is AgentRunCancelledException ||
                failure is CancellationException || failure is InterruptedException
            try {
                if (!stopped && reconnect == null && failure is AgentModelFailure &&
                    failure.code != "CONTEXT_WINDOW_EXCEEDED") beginReconnect(failure)
                reconnect?.finish(if (stopped) "stopped" else "failed")
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        } finally {
            reconnectBinding?.close()
        }
    }
    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
