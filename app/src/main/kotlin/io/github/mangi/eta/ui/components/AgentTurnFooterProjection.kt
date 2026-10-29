package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.isRetryableFailure
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement

/**
 * The action owner and its visual position are deliberately separate. A final
 * answer may precede work, and Completed is a divider rather than an answer.
 * Keys are existing lazy-row keys; values are the ORIGINAL callback messages.
 * No body is moved, copied, hidden, or eagerly expanded by this projection.
 *
 * Feed this the same rows that the list renders (after terminal normalization).
 * Recompute on row updates, including expansion and late work snapshots. This
 * also keeps navigation indices and scroll/reveal anchors unchanged.
 */
internal fun List<AgentTimelineRow>.turnFooters(
    isStreaming: Boolean = false,
    isCompressingContext: Boolean = false,
    isPaused: Boolean = false,
): Map<String, AgentChatMessageUi> = buildMap {
    var actionMessage: AgentChatMessageUi? = null
    var afterRowKey: String? = null
    var terminal: AgentChatMessageUi? = null

    fun flush(includeOpenTurn: Boolean) {
        val owner = actionMessage
        val anchor = afterRowKey
        if (owner != null && anchor != null && (terminal != null || includeOpenTurn)) {
            put(anchor, owner)
        }
        actionMessage = null
        afterRowKey = null
        terminal = null
    }

    this@turnFooters.forEach { row ->
        when (row) {
            is AgentTimelineRow.Message -> when (val message = row.message) {
                is UserMessageUi -> {
                    if (!message.isSteerSupplement()) {
                        flush(includeOpenTurn = true)
                    } else {
                        // A visible steering bubble is still part of the current
                        // reply. Its own actions remain attached to that bubble.
                        afterRowKey = row.key
                    }
                }
                is AgentMessageUi -> {
                    // Usage-only/empty placeholders must not steal copy/speech
                    // ownership from the last actual answer (or a closed notice).
                    if (message.content.isNotBlank()) {
                        if (terminal != null) flush(includeOpenTurn = true)
                        actionMessage = message
                    }
                    afterRowKey = row.key
                }
                is ErrorReconnectMessageUi -> {
                    if (message.isRetryableFailure()) {
                        if (actionMessage == null) actionMessage = message
                        terminal = message
                    }
                    afterRowKey = row.key
                }
                is SystemNoticeMessageUi -> {
                    if (message.code == SystemNoticeCode.ModelRetry) {
                        if (terminal != null) flush(includeOpenTurn = true)
                    } else {
                        // Completed still closes the turn, but copy/speech and
                        // other callbacks should retain the answer's original ID.
                        if (message.code != SystemNoticeCode.Completed || actionMessage == null) {
                            actionMessage = message
                        }
                        terminal = message
                    }
                    afterRowKey = row.key
                }
                else -> afterRowKey = row.key
            }
            is AgentTimelineRow.WorkSummary -> {
                val notice = terminal
                // A retry/continue run can start with work and no new user
                // bubble. Do not place the previous run's footer on that work.
                if (notice != null && row.group.messages.any { it.hasDifferentKnownOwner(notice) }) {
                    flush(includeOpenTurn = true)
                }
                afterRowKey = row.key
            }
            is AgentTimelineRow.WorkStep -> {
                val notice = terminal
                // A retry/continue run can start with work and no new user
                // bubble. Do not place the previous run's footer on that work.
                if (notice != null && row.message.hasDifferentKnownOwner(notice)) {
                    flush(includeOpenTurn = true)
                }
                afterRowKey = row.key
            }
        }
    }
    // A finished text block is not a finished turn while its run is active or paused.
    flush(includeOpenTurn = !isStreaming && !isPaused && !isCompressingContext)
}

/**
 * Legacy IDs have no reliable ownership; trailing legacy work stays before the
 * footer until the next answer/user boundary. Stable runtime IDs let us close
 * a prior footer before a new run's first work row without guessing adjacency.
 */
private fun AgentChatMessageUi.hasDifferentKnownOwner(notice: AgentChatMessageUi): Boolean {
    val owner = when (this) {
        is ThinkingMessageUi -> FOOTER_THINKING_ID.matchEntire(id)?.groupValues?.get(1)
        is ToolActivityMessageUi -> FOOTER_TOOL_ID.matchEntire(id)?.groupValues?.get(1)
        else -> null
    } ?: return false
    if (notice is ErrorReconnectMessageUi) return notice.runId.isNotBlank() && notice.runId != owner
    val knownNotice = notice.id.startsWith("assistant-") ||
        notice.id.startsWith("interrupted-") || notice.id.startsWith("virtual-completed-")
    if (!knownNotice) return false
    if (notice.id == "interrupted-$owner" || notice.id == "virtual-completed-$owner" ||
        notice.id == "assistant-$owner") return false
    val suffix = notice.id.removePrefix("assistant-$owner-")
    return suffix == notice.id || !FOOTER_ASSISTANT_SUFFIX.matches(suffix)
}

private val FOOTER_ASSISTANT_SUFFIX = Regex("(?:[0-9]+(?:-(?:[0-9]+|result|usage))?|round-usage)")
private val FOOTER_THINKING_ID = Regex("(.+)-thinking-[0-9]+(?:-(?:[0-9]+|fallback))?")
private val FOOTER_TOOL_ID = Regex("(.+?)-tool-[0-9]+-.+")
