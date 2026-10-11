package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.normalizeTerminalRunMessages

/**
 * Synchronous UI-dispatcher replay boundary. A Compose mutable snapshot batches
 * publication, not computation: sorting a whole conversation for every restored
 * event still blocks input and allocates temporary histories. Defer that derived
 * work until finish, while applying metadata/usage/compaction events in order.
 *
 * Outside replay, the common streaming case is one same-sized assistant payload
 * growing in place. That case cannot affect terminal ordering, so retain the
 * previous normalized order and patch only the changed slot. Existing reconnect
 * marker updates also leave ordering unchanged, including with terminal notices.
 * Structural changes and assistant state transitions use the full order function.
 */
internal class AgentRunReplayBatch private constructor(
    private val order: (String, List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    private val incrementalOrderIsPayloadIndependent: Boolean,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    constructor() : this(::normalizeTerminalRunMessages, true, Unit)

    constructor(order: (String, List<AgentChatMessageUi>) -> List<AgentChatMessageUi>) :
        this(order, false, Unit)

    private var activeRunId: String? = null
    private var cachedRunId: String? = null
    private var cachedMessages: List<AgentChatMessageUi>? = null
    private var cachedHasNotice = false

    internal var incrementalFastPathHits: Int = 0
        private set

    val isActive: Boolean get() = activeRunId != null

    fun normalize(runId: String, messages: List<AgentChatMessageUi>): List<AgentChatMessageUi> {
        if (activeRunId == runId) return messages
        if (incrementalOrderIsPayloadIndependent && canPatchCachedPayload(runId, messages)) {
            incrementalFastPathHits++
            cachedMessages = if (messages is AgentIncrementalList<AgentChatMessageUi>) messages else messages.toList()
            return messages
        }
        val normalized = order(runId, messages)
        cachedRunId = runId
        cachedMessages = if (normalized is AgentIncrementalList<AgentChatMessageUi>) normalized else normalized.toList()
        cachedHasNotice = normalized.any { it is SystemNoticeMessageUi }
        return normalized
    }

    private fun canPatchCachedPayload(runId: String, messages: List<AgentChatMessageUi>): Boolean {
        if (cachedRunId != runId) return false
        val previous = cachedMessages ?: return false
        if (previous.size != messages.size) return false
        val certified = (messages as? AgentIncrementalList<AgentChatMessageUi>)?.singleReplacementFrom(previous)
        if (certified != null) {
            return isOrderIndependentReplacement(previous[certified], messages[certified])
        }
        var changedIndex = -1
        for (index in messages.indices) {
            val old = previous[index]
            val current = messages[index]
            if (old === current) continue
            if (changedIndex >= 0 || !isOrderIndependentReplacement(old, current)) return false
            changedIndex = index
        }
        return changedIndex >= 0
    }

    private fun isOrderIndependentReplacement(old: AgentChatMessageUi, current: AgentChatMessageUi): Boolean =
        when {
            old is ErrorReconnectMessageUi && current is ErrorReconnectMessageUi ->
                old.id == current.id && old.runId == current.runId && old.reconnectId == current.reconnectId
            old is AgentMessageUi && current is AgentMessageUi ->
                !cachedHasNotice && old.id == current.id && old.isStreaming && current.isStreaming &&
                    current.content.startsWith(old.content)
            else -> false
        }

    fun replay(
        runId: String,
        events: List<AgentEvent>,
        reset: () -> Unit,
        apply: (AgentEvent) -> Unit,
        finish: () -> Unit,
    ) {
        check(!isActive) { "Run replay must not be nested" }
        activeRunId = runId
        try {
            reset()
            events.forEach(apply)
        } finally {
            activeRunId = null
        }
        // Normalization is enabled again. The caller publishes the ordered state
        // and refreshes conversation summaries once, in the same Compose snapshot.
        finish()
    }
}
