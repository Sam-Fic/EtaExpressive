package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.isResumeAfterCompress
import io.github.mangi.eta.ui.model.isSteerSupplement
import io.github.mangi.eta.ui.model.withTerminalBodiesInOrder

// Bound each eagerly rendered work-process Column without dropping message data.
private const val WORK_PROCESS_UI_BATCH_LIMIT = 32

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry
}

/**
 * 把消息流投影为时间线条目。
 *
 * [aggressive]（激进折叠档）改变的是**归组**而非显隐：每一轮只保留最后一条非空正文回复，
 * 它之前的中间文字回复、提示/重连等、已作答/已取消的提问卡与思考/工具步骤并进同一工作组，
 * 由 [toLazyTimelineRows] 展开为逐条步骤行（每条一个可展开的 SegmentedListItem 气泡，无嵌套）。
 * 普通/不折叠两档传 false，条目结构与旧版逐条投影完全一致。
 */
internal fun List<AgentChatMessageUi>.toTimelineEntries(aggressive: Boolean = false): List<AgentTimelineEntry> = buildList(size) {
    val workMessages = ArrayList<AgentChatMessageUi>(WORK_PROCESS_UI_BATCH_LIMIT)

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        add(
            AgentTimelineEntry.WorkProcess(
                key = "work-${workMessages.first().id}",
                messages = workMessages.toList(),
            )
        )
        workMessages.clear()
    }

    val ordered = this@toTimelineEntries.withTerminalBodiesInOrder()
    // 第一遍：算出每一轮唯一保留的回复（没有回复的轮为 null，整轮不并入、照常展示）。
    val turnFinalIds: List<String?> = if (aggressive) ordered.aggressiveTurnFinalReplyIds() else emptyList()
    var turnIndex = 0
    var turnFinalId: String? = turnFinalIds.firstOrNull()
    var passedFinal = false

    ordered.forEach { message ->
        if (message is UserMessageUi && message.isResumeAfterCompress()) {
            return@forEach
        }
        // Keep retry records for continuation logic, but never render a retry bubble.
        if (message is SystemNoticeMessageUi && message.code == SystemNoticeCode.ModelRetry) {
            return@forEach
        }
        // 真实用户消息开新轮（steer 补充只是续跑）：先落掉进行中的卡片，再放气泡。
        if (message is UserMessageUi && !message.isSteerSupplement()) {
            flushWorkProcess()
            add(AgentTimelineEntry.Message(message))
            turnIndex++
            turnFinalId = turnFinalIds.getOrNull(turnIndex)
            passedFinal = false
            return@forEach
        }
        // 本轮唯一保留的正文回复：作为气泡行放行，并宣告本轮「只剩它」的中间区到此为止。
        if (message is AgentMessageUi && message.content.isNotBlank() && message.id == turnFinalId) {
            flushWorkProcess()
            add(AgentTimelineEntry.Message(message))
            passedFinal = true
            return@forEach
        }
        if (message.isWorkProcessMessage()) {
            workMessages += message
            if (workMessages.size >= WORK_PROCESS_UI_BATCH_LIMIT) flushWorkProcess()
            return@forEach
        }
        // 激进档：最终回复之前的中间回复、提示与已作答提问卡一并并入卡片；回复后的尾巴保持原样。
        if (aggressive && !passedFinal && turnFinalId != null && message.isAggressiveFoldable()) {
            workMessages += message
            if (workMessages.size >= WORK_PROCESS_UI_BATCH_LIMIT) flushWorkProcess()
            return@forEach
        }
        flushWorkProcess()
        add(AgentTimelineEntry.Message(message))
    }
    flushWorkProcess()
}

/** Use projected list indices, not raw message indices (work steps are grouped). */
internal fun initialTimelineItemIndex(
    timelineEntries: List<AgentTimelineEntry>,
    isCompressingContext: Boolean,
    isWaitingForCompression: Boolean,
    hasCompactingChildContext: Boolean,
): Int = timelineEntries.size +
    if (isCompressingContext || isWaitingForCompression || hasCompactingChildContext) 1 else 0

internal fun List<AgentTimelineEntry>.userMessageIndices(): List<Int> = mapIndexedNotNull { index, entry ->
    val user = (entry as? AgentTimelineEntry.Message)?.message as? UserMessageUi
    index.takeIf { user != null && !user.isResumeAfterCompress() }
}

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

/**
 * 激进档每轮唯一保留的回复：该轮最后一条非空 [AgentMessageUi]；整轮没有正文则记 null。
 * 轮次边界与折叠策略一致 —— 真实用户消息开新轮，steer 补充与压缩后的 resume 不算。
 */
private fun List<AgentChatMessageUi>.aggressiveTurnFinalReplyIds(): List<String?> = buildList {
    var lastReplyId: String? = null
    fun endTurn() {
        add(lastReplyId)
        lastReplyId = null
    }
    // 注意 receiver：buildList 内裸 forEach 会解析到 builder（元素是 String?），必须点名外层列表。
    this@aggressiveTurnFinalReplyIds.forEach { message ->
        when {
            message is UserMessageUi && !message.isSteerSupplement() && !message.isResumeAfterCompress() ->
                endTurn()
            message is AgentMessageUi && message.content.isNotBlank() -> lastReplyId = message.id
            else -> Unit
        }
    }
    endTurn()
}

/**
 * 激进档允许并入本轮卡片的消息。建议芯片等仍需点击的交互件与未知类型一律保持直接可见，
 * 避免把可点的东西藏进卡片；流式中的回复也照常显示，保住「等待响应」的行内指示。
 *
 * 提问卡只有还在等人回答（[AgentQuestionStatus.Waiting]）时才必须留在卡片外；一旦已回答/取消/中断，
 * 它就是只读回执，与本轮其余步骤一起收进卡片 —— 否则它会夹在前后两张卡片之间，整轮折叠不完整。
 */
private fun AgentChatMessageUi.isAggressiveFoldable(): Boolean = when (this) {
    is ThinkingMessageUi,
    is ToolActivityMessageUi,
    is ToolSummaryMessageUi,
    is SystemNoticeMessageUi,
    is ErrorReconnectMessageUi,
    is RunTraceMessageUi,
    is ContextCompactedMessageUi,
    -> true
    is AgentQuestionMessageUi -> status != AgentQuestionStatus.Waiting
    is AgentMessageUi -> !isStreaming
    else -> false
}
