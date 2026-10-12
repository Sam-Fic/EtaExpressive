package io.github.mangi.eta.ui.app

import android.os.SystemClock
import java.lang.ref.WeakReference
import io.github.mangi.eta.agent.media.AgentToolMediaPreviews
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.mapPreservingSnapshot
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.errorReconnectMessageId
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi

internal class AgentRunMessageProjector(
    private val nowElapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val thinkingStartedAt = mutableMapOf<String, Long>()
    /** 终态之后不再接受思考/正文增量，避免回答已经结束后又展开一轮推理。 */
    private val sealedRunIds = mutableSetOf<String>()

    private data class ThinkingBlockKey(val runId: String, val round: Int, val index: Int)

    private data class RoundEventKey(val runId: String, val round: Int)

    private data class RoundEventState(
        var sequence: Long = 0L,
        var lastTextSequence: Long? = null,
        var lastToolSequence: Long? = null,
    )

    private data class VisibleBlock(val kind: AgentEvent.AssistantBlockKind, val index: Int)

    private data class PendingThinkingBlock(
        val afterMessageId: String?,
        var message: ThinkingMessageUi,
    )

    // BlockStart can precede the first nonempty delta. Remember its identity and position,
    // without displaying an empty card or mistaking it for a new block after later text.
    private val thinkingBlockAnchors = mutableMapOf<ThinkingBlockKey, String?>()
    // Text alone does not tell us whether it is commentary or a final answer. New reasoning
    // after text stays private until a subsequent tool in the SAME run/round provides evidence.
    private val pendingThinkingBlocks = linkedMapOf<ThinkingBlockKey, PendingThinkingBlock>()
    // Message-list position is not event order: a resumed text block can stay before an old tool.
    // Keep the ordering evidence separately so an old tool cannot unlock a later late-thinking block.
    private val roundEventStates = mutableMapOf<RoundEventKey, RoundEventState>()
    // Once a block was projected by this instance, a tail delta can update it
    // without rechecking every historical row. Missing state deliberately falls
    // back to the original transition pass for restored or externally-built lists.
    private val visibleBlocks = mutableMapOf<RoundEventKey, VisibleBlock>()
    // Only an unchanged, already-validated snapshot is eligible for the tail shortcut.
    private var lastDeltaProjection: WeakReference<List<AgentChatMessageUi>>? = null
    private var lastDeltaKey: RoundEventKey? = null
    private val textSegments = mutableMapOf<RoundEventKey, String>()
    /** Continue opens a new run but must update the stopped divider instead of adding one. */
    private val claimedReconnectIds = mutableMapOf<String, String>()

    fun requestQuestion(conversationId: String, runId: String, event: AgentEvent.QuestionRequested,
        messages: List<AgentChatMessageUi>, replaying: Boolean = false): List<AgentChatMessageUi> =
        AgentQuestionProjection.requested(conversationId, runId, event.request, messages, !isSealed(runId), replaying)

    fun resolveQuestion(conversationId: String, runId: String, event: AgentEvent.QuestionResolved,
        messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        AgentQuestionProjection.resolved(conversationId, runId, event, messages)

    fun isSealed(runId: String): Boolean = runId in sealedRunIds

    fun seal(runId: String) {
        if (runId.isNotBlank()) sealedRunIds += runId
        discardPendingThinking(runId)
        thinkingBlockAnchors.keys.removeAll { it.runId == runId }
        roundEventStates.keys.removeAll { it.runId == runId }
        visibleBlocks.keys.removeAll { it.runId == runId }
        lastDeltaProjection = null
        lastDeltaKey = null
    }

    /** Bind a new continue run to the existing stopped divider before its first event arrives. */
    fun claimStoppedReconnect(runId: String, messageId: String) {
        if (runId.isNotBlank() && messageId.isNotBlank()) claimedReconnectIds[runId] = messageId
    }

    /** 回放从该 run 的空轨迹重建；仅重排有回放事件的补充输入，旧 handoff 独有的输入必须保留。 */
    fun resetForReplay(
        runId: String,
        messages: List<AgentChatMessageUi>,
        replaySupplementIndexes: Set<Int> = emptySet(),
    ): List<AgentChatMessageUi> {
        if (runId.isBlank()) return messages
        sealedRunIds.remove(runId)
        clearRun(runId)
        val replaySupplementIds = replaySupplementIndexes.mapTo(mutableSetOf()) { index ->
            AgentPendingResultRecovery.supplementMessageId(runId, index)
        }
        return messages.filterNot { message ->
            when (message) {
                is AgentMessageUi -> isAssistantMessageForRun(message.id, runId)
                is ThinkingMessageUi -> message.id.startsWith("$runId-thinking-")
                is ToolActivityMessageUi -> message.id.startsWith("$runId-tool-")
                is ErrorReconnectMessageUi -> message.runId == runId ||
                    (message.runId.isBlank() && (isAssistantMessageForRun(message.id, runId) || message.id == "interrupted-$runId"))
                is SystemNoticeMessageUi ->
                    isAssistantMessageForRun(message.id, runId) || message.id == "interrupted-$runId"
                is UserMessageUi -> message.id in replaySupplementIds
                else -> false
            }
        }
    }

    /** A continued HTTP request may share its UI round, but not its ordering evidence. */
    fun beginProviderRequest(runId: String, round: Int) {
        if (runId.isBlank() || isSealed(runId)) return
        // A later request's tool must not resurrect an unproven tail from the prior one.
        // Accepted blocks keep their identities; only directly continued TEXT may reuse
        // its mapped index across requests (see AgentContinuationBlocks).
        discardPendingThinking(runId, round)
        roundEventStates.remove(RoundEventKey(runId, round))
    }

    fun scheduleModelRetry(
        runId: String,
        event: AgentEvent.ModelRetryScheduled,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (isSealed(runId)) return messages
        discardPendingThinking(runId, event.round)
        roundEventStates.remove(RoundEventKey(runId, event.round))
        val finalized = finalizeTextRound(
            runId, event.round, finalizeThinkingRound(runId, event.round, messages),
        )
        val notice = SystemNoticeMessageUi(
            id = "assistant-$runId-retry-${event.round}",
            code = SystemNoticeCode.ModelRetry,
            detail = event.displayMessage,
        )
        return finalized.filterNot { it.id == notice.id } + notice
    }

    /** Progress updates replace one row in place; only a new disconnection creates a boundary. */
    fun reconnectChanged(
        runId: String,
        event: AgentEvent.ErrorReconnectChanged,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val status = ErrorReconnectStatus.fromWireValue(event.status) ?: return messages
        val id = errorReconnectMessageId(runId, event.reconnectId)
        val claimedId = claimedReconnectIds[runId]
        val existing = messages.filterIsInstance<ErrorReconnectMessageUi>().firstOrNull {
            it.id == id || (claimedId != null && it.id == claimedId)
        }
        // Continue claims the stopped divider before its first event. Later progress keeps that row.
        val target = existing
        // A stopped attempt resumes in place. Failed or succeeded markers stay terminal.
        if (target != null && target.status != ErrorReconnectStatus.Running &&
            target.status != ErrorReconnectStatus.Stopped && status == ErrorReconnectStatus.Running) {
            return messages
        }
        if (isSealed(runId) && target == null && status == ErrorReconnectStatus.Running) return messages
        val marker = ErrorReconnectMessageUi(
            id = target?.id ?: id,
            runId = if (target != null && target.id != id) target.runId else runId,
            reconnectId = if (target != null && target.id != id) target.reconnectId else event.reconnectId,
            round = if (target != null && target.id != id) target.round else event.round,
            status = target?.status?.takeIf {
                it != ErrorReconnectStatus.Running && it != ErrorReconnectStatus.Stopped
            } ?: status,
            elapsedMs = maxOf(target?.elapsedMs ?: 0L, event.elapsedMs.coerceAtLeast(0L)),
            reasonCode = event.reasonCode.ifBlank { target?.reasonCode.orEmpty() },
            reasonDetail = event.reasonDetail.ifBlank { target?.reasonDetail.orEmpty() },
        )
        if (marker.status != ErrorReconnectStatus.Running && marker.status != ErrorReconnectStatus.Stopped) {
            claimedReconnectIds.remove(runId)
        }
        if (target != null) return messages.map { if (it.id == target.id) marker else it }
        discardPendingThinking(runId, event.round)
        roundEventStates.remove(RoundEventKey(runId, event.round))
        // Continued provider blocks may reuse their index. Keep the partial bubble before
        // the marker and give the following segment its own deterministic replay identity.
        textSegments[RoundEventKey(runId, event.round)] = event.reconnectId
        return freezeAtDisconnection(runId, messages) + marker
    }

    /** Used both by live terminal handling and outbox recovery (no event replay required). */
    fun terminalFailure(
        runId: String,
        reason: String,
        messages: List<AgentChatMessageUi>,
        reasonCode: String = "",
        round: Int = 0,
    ): List<AgentChatMessageUi> {
        val finalized = finalizeRun(runId, messages).filterNot {
            it is AgentMessageUi && isAssistantMessageForRun(it.id, runId) && it.content.isBlank()
        }
        val claimedId = claimedReconnectIds[runId]
        val own = finalized.filterIsInstance<ErrorReconnectMessageUi>().lastOrNull { it.runId == runId }
        val claimed = finalized.filterIsInstance<ErrorReconnectMessageUi>().firstOrNull {
            claimedId != null && it.id == claimedId
        }
        val latest = own ?: claimed
        if (latest != null && latest.status != ErrorReconnectStatus.Succeeded) {
            val continuationFailure = own == null && claimed != null
            if (continuationFailure || latest.status != ErrorReconnectStatus.Stopped) claimedReconnectIds.remove(runId)
            return finalized.map { message ->
                if (message.id == latest.id) latest.copy(
                    status = if (latest.status == ErrorReconnectStatus.Stopped && !continuationFailure) {
                        latest.status
                    } else {
                        ErrorReconnectStatus.Failed
                    },
                    reasonCode = latest.reasonCode.ifBlank { reasonCode },
                    reasonDetail = latest.reasonDetail.ifBlank { reason },
                ) else message
            }
        }
        // Repeated terminal folding is idempotent. A later failed run never replaces
        // another run's marker, and a successful earlier reconnect remains intact.
        val reconnectId = "terminal-failure"
        val id = errorReconnectMessageId(runId, reconnectId)
        if (finalized.any { it.id == id }) return finalized
        return finalized + ErrorReconnectMessageUi(
            id = id, runId = runId, reconnectId = reconnectId, round = round,
            status = ErrorReconnectStatus.Failed, reasonCode = reasonCode,
            reasonDetail = reason, isReconnect = false,
        )
    }

    /** Manual cancellation is stopped, never succeeded. Pause alone must not call this. */
    fun runStopped(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        val claimedId = claimedReconnectIds[runId]
        return finalizeRun(runId, messages).map { message ->
            if (message is ErrorReconnectMessageUi && message.status == ErrorReconnectStatus.Running &&
                (message.runId == runId || message.id == claimedId)
            ) message.copy(status = ErrorReconnectStatus.Stopped) else message
        }
    }

    private fun freezeAtDisconnection(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> =
        finalizeThinking(runId, messages).map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRun(message.id, runId)) {
                // Do not trim a join boundary until the run really ends.
                message.copy(isStreaming = false, renderMarkdown = true)
            } else message
        }

    fun startAssistantBlock(
        runId: String,
        event: AgentEvent.AssistantBlockStart,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (isSealed(runId)) return messages
        if (event.kind == AgentEvent.AssistantBlockKind.TEXT) {
            recordTextEvent(runId, event.round)
        }
        if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
            val key = ThinkingBlockKey(runId, event.round, event.index)
            if (shouldDeferThinking(key, messages)) {
                pendingThinkingBlock(key, messages)
                return messages
            }
            rememberThinkingBlock(key, messages)
        }
        val transitioned = transitionVisibleBlock(
            runId = runId,
            round = event.round,
            kind = event.kind,
            index = event.index,
            messages = messages,
        )
        visibleBlocks[RoundEventKey(runId, event.round)] = VisibleBlock(event.kind, event.index)
        return transitioned
    }

    fun appendTextDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty() || isSealed(runId)) return messages
        recordTextEvent(runId, round)
        finishPendingThinking(runId, round)

        val eventKey = RoundEventKey(runId, round)
        val assistantId = assistantMessageId(runId, round, index)
        var updated = false
        // Keep the boundary transition and the delta append in one pass. Streaming
        // updates are frequent; two full-history maps here doubled the cost before
        // publication even when the active bubble was already at the tail.
        val last = messages.lastOrNull()
        if (last is AgentMessageUi && last.id == assistantId &&
            visibleBlocks[eventKey] == VisibleBlock(AgentEvent.AssistantBlockKind.TEXT, index) &&
            lastDeltaKey == eventKey && lastDeltaProjection?.get() === messages
        ) {
            return messages.replaceLast(
                last.copy(
                    content = last.content + delta,
                    isStreaming = true,
                    renderMarkdown = false,
                ),
            ).rememberDeltaProjection(eventKey)
        }
        val next = messages.map { message ->
            when {
                message is AgentMessageUi && message.id == assistantId -> {
                    updated = true
                    message.copy(
                        content = message.content + delta,
                        isStreaming = true,
                        renderMarkdown = false,
                    )
                }
                message is AgentMessageUi &&
                    message.isStreaming &&
                    isAssistantMessageForRound(message.id, runId, round) ->
                    message.copy(
                        content = message.content.trimEnd(),
                        isStreaming = false,
                        renderMarkdown = true,
                    )
                message is ThinkingMessageUi &&
                    message.isStreaming &&
                    isThinkingMessageForRound(message.id, runId, round) ->
                    message.finished()
                else -> message
            }
        }
        visibleBlocks[eventKey] = VisibleBlock(AgentEvent.AssistantBlockKind.TEXT, index)
        if (updated) return next.rememberDeltaProjection(eventKey)

        return (next + AgentMessageUi(
            id = assistantId,
            content = delta,
            isStreaming = true,
            renderMarkdown = false,
        )).rememberDeltaProjection(eventKey)
    }

    fun appendReasoningDelta(
        runId: String,
        round: Int,
        index: Int,
        delta: String,
        messages: List<AgentChatMessageUi>,
        visible: Boolean = true,
    ): List<AgentChatMessageUi> {
        if (delta.isEmpty() || isSealed(runId) || !visible) return messages
        val key = ThinkingBlockKey(runId, round, index)
        if (shouldDeferThinking(key, messages)) {
            val pending = pendingThinkingBlock(key, messages)
            pending.message = pending.message.copy(
                content = pending.message.content + delta,
                isStreaming = true,
                elapsedSeconds = elapsedSeconds(pending.message.id),
                collapsed = false,
            )
            return messages
        }
        rememberThinkingBlock(key, messages)

        finishPendingThinking(runId, round, except = key)
        val eventKey = RoundEventKey(runId, round)
        val thinkingId = thinkingMessageId(runId, round, index)
        val elapsedSeconds = elapsedSeconds(thinkingId)
        var updated = false
        // As with text, finish the previous visible block and append this delta in
        // one history pass. Pending blocks are handled above without touching history.
        val last = messages.lastOrNull()
        if (last is ThinkingMessageUi && last.id == thinkingId &&
            visibleBlocks[eventKey] == VisibleBlock(AgentEvent.AssistantBlockKind.THINKING, index) &&
            lastDeltaKey == eventKey && lastDeltaProjection?.get() === messages
        ) {
            return messages.replaceLast(
                last.copy(
                    content = last.content + delta,
                    isStreaming = true,
                    elapsedSeconds = elapsedSeconds,
                    collapsed = false,
                ),
            ).rememberDeltaProjection(eventKey)
        }
        val next = messages.map { message ->
            when {
                message is ThinkingMessageUi && message.id == thinkingId -> {
                    updated = true
                    message.copy(
                        content = message.content + delta,
                        isStreaming = true,
                        elapsedSeconds = elapsedSeconds,
                        collapsed = false,
                    )
                }
                message is AgentMessageUi &&
                    message.isStreaming &&
                    isAssistantMessageForRound(message.id, runId, round) ->
                    message.copy(
                        content = message.content.trimEnd(),
                        isStreaming = false,
                        renderMarkdown = true,
                    )
                message is ThinkingMessageUi &&
                    message.isStreaming &&
                    isThinkingMessageForRound(message.id, runId, round) ->
                    message.finished()
                else -> message
            }
        }
        visibleBlocks[eventKey] = VisibleBlock(AgentEvent.AssistantBlockKind.THINKING, index)
        if (updated) return next.rememberDeltaProjection(eventKey)

        return next.insertAfterThinkingAnchor(
            thinkingBlockAnchors[key],
            ThinkingMessageUi(
                id = thinkingId,
                content = delta,
                isStreaming = true,
                elapsedSeconds = elapsedSeconds,
                collapsed = false,
            ),
        ).rememberDeltaProjection(eventKey)
    }

    fun ensureCompletedThinking(
        runId: String,
        round: Int,
        content: String,
        messages: List<AgentChatMessageUi>,
        visible: Boolean = true,
    ): List<AgentChatMessageUi> {
        if (isSealed(runId) || !visible) return messages
        if (messages.any {
                it is ThinkingMessageUi && isThinkingMessageForRound(it.id, runId, round)
            } || pendingThinkingBlocks.any { (key, pending) ->
                key.runId == runId && key.round == round && pending.message.content.isNotEmpty()
            }
        ) {
            // A streamed block (even one awaiting tool evidence) owns its content/identity.
            // The response summary must not create a second fallback copy of that block.
            return finalizeThinkingRound(runId, round, messages)
        }

        val thinkingId = thinkingFallbackMessageId(runId, round)
        return messages.insertBeforeFirstAssistant(
            runId = runId,
            round = round,
            message = ThinkingMessageUi(
                id = thinkingId,
                content = content,
                isStreaming = false,
                elapsedSeconds = elapsedSeconds(thinkingId),
                collapsed = true,
            )
        )
    }

    fun finalizeThinking(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        finishPendingThinking(runId)
        return messages.mapPreservingSnapshot { message ->
            if (message is ThinkingMessageUi && message.id.startsWith("$runId-thinking-")) {
                message.finished()
            } else {
                message
            }
        }
    }

    /** 终态不依赖各块结束事件全部到齐；缺少工具结果时只能标为未知，不能推断执行成功。 */
    fun finalizeRun(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        seal(runId)
        val finalized = finalizeText(runId, finalizeThinking(runId, messages)).map { message ->
            if (
                message is ToolActivityMessageUi &&
                message.id.startsWith("$runId-tool-") &&
                message.status == ToolActivityStatusUi.Running
            ) {
                message.copy(status = ToolActivityStatusUi.Unknown)
            } else {
                message
            }
        }
        return AgentQuestionProjection.interruptWaiting(runId, finalized)
    }

    fun finalizeThinkingRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // HostedToolStarted is preceded by this call in AgentAppState. Finish, but do not
        // discard, deferred blocks: startHostedTool still needs to flush them in event order.
        finishPendingThinking(runId, round)
        return messages.map { message ->
            if (message is ThinkingMessageUi && isThinkingMessageForRound(message.id, runId, round)) {
                message.finished()
            } else {
                message
            }
        }
    }

    fun finalizeThinkingBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        if (isSealed(runId)) return messages
        val thinkingId = thinkingMessageId(runId, round, index)
        val pending = pendingThinkingBlocks[ThinkingBlockKey(runId, round, index)]
        if (pending != null) {
            if (!replacementContent.isNullOrEmpty()) elapsedSeconds(thinkingId)
            // BlockEnd is a full replacement, unlike a delta. Empty replacement clears it;
            // null only ends streaming. Neither changes the original insertion anchor.
            pending.message = pending.message.finished(replacementContent)
            return messages
        }
        return messages.map { message ->
            if (message is ThinkingMessageUi && message.id == thinkingId) {
                message.finished(replacementContent)
            } else {
                message
            }
        }
    }

    fun finalizeText(
        runId: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.mapPreservingSnapshot { message ->
            if (message is AgentMessageUi && isAssistantMessageForRun(message.id, runId)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }

    fun finalizeTextRound(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 定稿时裁掉尾部空白：模型输出常以换行收尾，Markdown 渲染会把每个尾部
        // 换行节点变成一段固定间距，在正文与后续工具卡片之间形成莫名的空行。
        return messages.map { message ->
            if (message is AgentMessageUi && isAssistantMessageForRound(message.id, runId, round)) {
                message.copy(
                    content = message.content.trimEnd(),
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun finalizeTextBlock(
        runId: String,
        round: Int,
        index: Int,
        replacementContent: String?,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val assistantId = assistantMessageId(runId, round, index)
        return messages.map { message ->
            if (message is AgentMessageUi && message.id == assistantId) {
                message.copy(
                    // A block may resume after a pause; preserve the exact join boundary.
                    content = replacementContent ?: message.content,
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                message
            }
        }
    }

    fun startTool(
        runId: String,
        event: AgentEvent.ToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        // 工具执行发生在对应 assistant 工具块完整返回之后；此时直接追加即可保留
        // 工具前说明、工具活动与下一轮结果的真实时间顺序。
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = event.argsPreview,
            command = event.command,
            argumentsDetail = event.argumentsDetail,
        )
        if (isSealed(runId) || messages.any { it.id == message.id }) return messages
        recordToolEvent(runId, event.round)
        return flushPendingThinking(runId, event.round, messages) + message
    }

    fun finishTool(
        runId: String,
        event: AgentEvent.ToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        // success 字段优先；旧版本 Runtime/归档事件缺省时回退到摘要文本判断
        val status = when (event.success) {
            true -> ToolActivityStatusUi.Success
            false -> ToolActivityStatusUi.Failed
            null -> if (event.resultSummary.contains("ok=false", ignoreCase = true)) {
                ToolActivityStatusUi.Failed
            } else {
                ToolActivityStatusUi.Success
            }
        }
        val targetIndex = messages.indexOfLast { it is ToolActivityMessageUi && it.id == targetId }
        if (targetIndex < 0) return messages

        return messages.mapIndexed { index, message ->
            if (index == targetIndex && message is ToolActivityMessageUi) {
                message.copy(
                    status = status,
                    resultSummary = event.resultSummary,
                    imageCount = event.imageCount,
                    resultDetail = event.resultDetail,
                    mediaPreviews = AgentToolMediaPreviews.decode(event.mediaPreviewsJson),
                    // 敏感工具的事件里本来就带着原文（供当次查看），是否落库由会话存储层决定；
                    // 记录里没有原文时才需要用一句话解释「为什么只剩一句完成」。
                    detailsWithheld = AgentSensitiveToolPolicy.isSensitive(event.name) &&
                        event.resultDetail == null,
                )
            } else {
                message
            }
        }
    }

    fun startHostedTool(
        runId: String,
        event: AgentEvent.HostedToolStarted,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val message = ToolActivityMessageUi(
            id = toolActivityMessageId(runId, event.round, event.toolCallId),
            toolName = event.name,
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "",
        )
        if (isSealed(runId) || messages.any { it.id == message.id }) return messages
        recordToolEvent(runId, event.round)
        return flushPendingThinking(runId, event.round, messages) + message
    }

    fun finishHostedTool(
        runId: String,
        event: AgentEvent.HostedToolFinished,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val targetId = toolActivityMessageId(runId, event.round, event.toolCallId)
        return messages.map { message ->
            if (message is ToolActivityMessageUi && message.id == targetId) {
                message.copy(
                    status = if (event.success) ToolActivityStatusUi.Success else ToolActivityStatusUi.Failed,
                    resultSummary = null,
                )
            } else {
                message
            }
        }
    }

    fun failRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Failed,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    fun interruptRunningTools(
        reason: String,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> =
        messages.map { message ->
            if (message is ToolActivityMessageUi && message.status == ToolActivityStatusUi.Running) {
                message.copy(
                    status = ToolActivityStatusUi.Unknown,
                    resultSummary = reason.take(MAX_TOOL_RESULT_PREVIEW_CHARS),
                )
            } else {
                message
            }
        }

    fun clearRun(runId: String) {
        discardPendingThinking(runId)
        thinkingBlockAnchors.keys.removeAll { it.runId == runId }
        roundEventStates.keys.removeAll { it.runId == runId }
        visibleBlocks.keys.removeAll { it.runId == runId }
        lastDeltaProjection = null
        lastDeltaKey = null
        thinkingStartedAt.keys.removeAll { it.startsWith("$runId-thinking-") }
        textSegments.keys.removeAll { it.runId == runId }
    }

    private fun List<AgentChatMessageUi>.rememberDeltaProjection(eventKey: RoundEventKey): List<AgentChatMessageUi> {
        val snapshot = incrementalSnapshot()
        lastDeltaKey = eventKey
        lastDeltaProjection = WeakReference(snapshot)
        return snapshot
    }

    private fun recordTextEvent(runId: String, round: Int) {
        val state = roundEventStates.getOrPut(RoundEventKey(runId, round)) { RoundEventState() }
        state.sequence += 1
        state.lastTextSequence = state.sequence
    }

    private fun recordToolEvent(runId: String, round: Int) {
        val state = roundEventStates.getOrPut(RoundEventKey(runId, round)) { RoundEventState() }
        state.sequence += 1
        state.lastToolSequence = state.sequence
    }

    /** Identity, not content prefixes, distinguishes a resumed delta from a new block. */
    private fun shouldDeferThinking(
        key: ThinkingBlockKey,
        messages: List<AgentChatMessageUi>,
    ): Boolean {
        val thinkingId = thinkingMessageId(key.runId, key.round, key.index)
        if (key in thinkingBlockAnchors || messages.any { it is ThinkingMessageUi && it.id == thinkingId }) {
            return false
        }
        if (key in pendingThinkingBlocks) return true
        val state = roundEventStates[RoundEventKey(key.runId, key.round)]
        val lastText = state?.lastTextSequence ?: return false
        val lastTool = state.lastToolSequence ?: return true
        return lastTool <= lastText
    }

    private fun rememberThinkingBlock(key: ThinkingBlockKey, messages: List<AgentChatMessageUi>) {
        if (key !in thinkingBlockAnchors) thinkingBlockAnchors[key] = messages.lastOrNull()?.id
    }

    private fun pendingThinkingBlock(
        key: ThinkingBlockKey,
        messages: List<AgentChatMessageUi>,
    ): PendingThinkingBlock {
        finishPendingThinking(key.runId, key.round, except = key)
        return pendingThinkingBlocks.getOrPut(key) {
            PendingThinkingBlock(
                afterMessageId = messages.lastOrNull()?.id,
                message = ThinkingMessageUi(
                    id = thinkingMessageId(key.runId, key.round, key.index),
                    content = "",
                    isStreaming = true,
                    elapsedSeconds = 0,
                    collapsed = false,
                ),
            )
        }
    }

    private fun finishPendingThinking(runId: String, round: Int? = null, except: ThinkingBlockKey? = null) {
        pendingThinkingBlocks.forEach { (key, pending) ->
            if (key.runId == runId && (round == null || key.round == round) && key != except &&
                pending.message.isStreaming
            ) {
                pending.message = pending.message.finished()
            }
        }
    }

    private fun discardPendingThinking(runId: String, round: Int? = null) {
        val keys = pendingThinkingBlocks.keys.filter { it.runId == runId && (round == null || it.round == round) }
        keys.forEach { key ->
            pendingThinkingBlocks.remove(key)?.let { thinkingStartedAt.remove(it.message.id) }
        }
    }

    private fun flushPendingThinking(
        runId: String,
        round: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        val pending = pendingThinkingBlocks.filterKeys { it.runId == runId && it.round == round }
        if (pending.isEmpty()) return messages
        finishPendingThinking(runId, round)
        var next = messages
        // Reverse insertion preserves event order when several blocks share the same anchor.
        // Using message IDs (not offsets or block indexes) also preserves interleaved text.
        pending.entries.toList().asReversed().forEach { (key, block) ->
            pendingThinkingBlocks.remove(key)
            if (block.message.content.isEmpty()) {
                thinkingStartedAt.remove(block.message.id)
            } else if (next.none { it.id == block.message.id }) {
                next = next.insertAfterThinkingAnchor(block.afterMessageId, block.message)
                thinkingBlockAnchors[key] = block.afterMessageId
            }
        }
        return next
    }

    private fun List<AgentChatMessageUi>.insertAfterThinkingAnchor(
        afterMessageId: String?,
        message: ThinkingMessageUi,
    ): List<AgentChatMessageUi> {
        val anchorIndex = if (afterMessageId == null) -1 else indexOfLast { it.id == afterMessageId }
        // A replaced/cleared trace must not resurrect a deferred card at an arbitrary tail.
        if (afterMessageId != null && anchorIndex < 0) return this
        return toMutableList().apply { add(anchorIndex + 1, message) }
    }

    private fun ThinkingMessageUi.finished(authoritativeContent: String? = null): ThinkingMessageUi =
        copy(
            content = authoritativeContent ?: content,
            isStreaming = false,
            elapsedSeconds = thinkingStartedAt[id]?.let { startedAt ->
                ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
            } ?: elapsedSeconds,
            collapsed = true,
        )

    private fun elapsedSeconds(thinkingId: String): Int {
        val startedAt = thinkingStartedAt.getOrPut(thinkingId, nowElapsedRealtime)
        return ((nowElapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(0)
    }

    private fun transitionVisibleBlock(
        runId: String,
        round: Int,
        kind: AgentEvent.AssistantBlockKind,
        index: Int,
        messages: List<AgentChatMessageUi>,
    ): List<AgentChatMessageUi> {
        finishPendingThinking(
            runId, round,
            except = if (kind == AgentEvent.AssistantBlockKind.THINKING) ThinkingBlockKey(runId, round, index) else null,
        )
        val activeAssistantId = if (kind == AgentEvent.AssistantBlockKind.TEXT) {
            assistantMessageId(runId, round, index)
        } else {
            null
        }
        val activeThinkingId = if (kind == AgentEvent.AssistantBlockKind.THINKING) {
            thinkingMessageId(runId, round, index)
        } else {
            null
        }
        return messages.map { message ->
            when {
                message is AgentMessageUi &&
                    message.isStreaming &&
                    isAssistantMessageForRound(message.id, runId, round) &&
                    message.id != activeAssistantId ->
                    message.copy(
                        content = message.content.trimEnd(),
                        isStreaming = false,
                        renderMarkdown = true,
                    )

                message is ThinkingMessageUi &&
                    message.isStreaming &&
                    isThinkingMessageForRound(message.id, runId, round) &&
                    message.id != activeThinkingId ->
                    message.finished()

                else -> message
            }
        }
    }

    private fun List<AgentChatMessageUi>.insertBeforeFirstAssistant(
        runId: String,
        round: Int,
        message: AgentChatMessageUi,
    ): List<AgentChatMessageUi> {
        val assistantIndex = indexOfFirst {
            it is AgentMessageUi && isAssistantMessageForRound(it.id, runId, round)
        }
        return if (assistantIndex >= 0) {
            toMutableList().apply { add(assistantIndex, message) }
        } else {
            this + message
        }
    }

    private fun assistantMessageId(runId: String, round: Int, index: Int): String {
        val segment = textSegments[RoundEventKey(runId, round)]
        val suffix = segment?.let { "-reconnect-${it.length}:$it" }.orEmpty()
        return "${assistantMessagePrefix(runId)}$round-$index$suffix"
    }

    private fun thinkingMessageId(runId: String, round: Int, index: Int): String =
        "$runId-thinking-$round-$index"

    private fun thinkingFallbackMessageId(runId: String, round: Int): String =
        "$runId-thinking-$round-fallback"

    private fun isAssistantMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val legacyId = "${assistantMessagePrefix(runId)}$round"
        return messageId == legacyId || messageId.startsWith("$legacyId-")
    }

    private fun isThinkingMessageForRound(messageId: String, runId: String, round: Int): Boolean {
        val prefix = "$runId-thinking-$round"
        return messageId == prefix || messageId.startsWith("$prefix-")
    }

    private fun toolActivityMessageId(runId: String, round: Int, toolCallId: String): String =
        "$runId-tool-$round-${toolCallId.ifBlank { "unknown" }}"

    companion object {
        /** 终态只能补全最后一次重试之后的回答，不能覆盖已标记失败的半截输出。 */
        fun resultTargetIndex(
            runId: String,
            messages: List<AgentChatMessageUi>,
            includeNotices: Boolean = false,
        ): Int {
            val retryIndex = lastRetryIndex(runId, messages)
            return messages.indices.lastOrNull { index ->
                val message = messages[index]
                index > retryIndex &&
                    (message is AgentMessageUi || includeNotices && message is SystemNoticeMessageUi) &&
                    isAssistantMessageForRun(message.id, runId)
            } ?: -1
        }

        /** Retry completion includes the interrupted prefix; only append its unseen tail.
         * Earlier completed tool rounds and other runs never participate in this seam. */
        fun completedResultTail(
            runId: String, messages: List<AgentChatMessageUi>, targetIndex: Int, result: String,
        ): String {
            val reconnect = messages.filterIsInstance<ErrorReconnectMessageUi>()
                .lastOrNull { it.runId == runId && it.status == ErrorReconnectStatus.Succeeded }
                ?: return result
            val end = if (targetIndex >= 0) targetIndex else messages.size
            val prefix = messages.take(end).filterIsInstance<AgentMessageUi>()
                .filter { message ->
                    isAssistantMessageForRun(message.id, runId) &&
                        (message.id.removePrefix("assistant-$runId-").substringBefore('-')
                            .toIntOrNull() ?: -1) >= reconnect.round
                }.joinToString("") { it.content }
            return if (prefix.isNotEmpty() && result.startsWith(prefix)) result.removePrefix(prefix) else result
        }

        fun resultFallbackId(runId: String, messages: List<AgentChatMessageUi>): String {
            val retry = messages.getOrNull(lastRetryIndex(runId, messages))
            if (retry == null) return "assistant-$runId-1"
            if (retry is ErrorReconnectMessageUi) {
                return "assistant-$runId-${retry.round.coerceAtLeast(1)}-result-reconnect-${retry.reconnectId.length}:${retry.reconnectId}"
            }
            val round = retry.id.substringAfterLast('-').toIntOrNull()?.plus(1) ?: 1
            return "assistant-$runId-$round-result"
        }

        private fun lastRetryIndex(runId: String, messages: List<AgentChatMessageUi>): Int =
            messages.indexOfLast {
                (it is SystemNoticeMessageUi && it.code == SystemNoticeCode.ModelRetry &&
                    it.id.startsWith("assistant-$runId-retry-")) ||
                    (it is ErrorReconnectMessageUi && it.runId == runId)
            }

        private fun assistantMessagePrefix(runId: String): String =
            "assistant-$runId-"

        private fun isAssistantMessageForRun(messageId: String, runId: String): Boolean =
            messageId == "assistant-$runId" ||
                messageId.startsWith(assistantMessagePrefix(runId))
    }

}

/** Replace a tail message without invoking a predicate for every historical row. */
private fun List<AgentChatMessageUi>.replaceLast(message: AgentChatMessageUi): List<AgentChatMessageUi> =
    incrementalSnapshot().replacing(lastIndex, message)

private const val MAX_TOOL_RESULT_PREVIEW_CHARS = 48

internal fun mergeCompletedAssistantContent(
    current: String,
    fallback: String,
    sameRoundBlocks: Int,
): String {
    val streaming = current.trimEnd()
    val finished = fallback.trimEnd()
    if (streaming.isBlank()) return fallback
    if (finished.isBlank() || streaming == finished) return streaming
    if (sameRoundBlocks > 1) return current.ifBlank { fallback }
    if (finished.startsWith(streaming) || streaming.startsWith(finished)) {
        return if (finished.length >= streaming.length) fallback else current
    }
    return current
}
