package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.errorReconnectMessageId
import io.github.mangi.eta.ui.model.incrementalSnapshot
import io.github.mangi.eta.ui.model.normalizeTerminalRunMessages
import io.github.mangi.eta.ui.model.terminalOrderLargeHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AgentReconnectNormalizationTest {
    private val runId = "reconnect-live"

    @Test
    fun actualProjectorTicksReuseOrderWithLargeTerminalHistory() {
        val projector = AgentRunMessageProjector { 1_000L }
        val batch = AgentRunReplayBatch()
        val partial = AgentMessageUi("assistant-$runId-1-0", "partial  ", isStreaming = true)
        var messages = batch.normalize(runId, terminalOrderLargeHistory() +
            listOf(UserMessageUi("user-$runId", "task"), partial))
        val initial = messages
        val first = projector.reconnectChanged(runId, event(0), messages)
        messages = batch.normalize(runId, first)
        assertEquals(normalizeTerminalRunMessages(runId, first), messages)
        assertEquals("First disconnection freezes and inserts; use full normalization", 0, batch.incrementalFastPathHits)
        assertEquals(partial, initial.last())
        assertEquals("partial  ", (messages[messages.lastIndex - 1] as AgentMessageUi).content)
        repeat(30) { tick ->
            val previous = messages
            val oldMarker = previous.last() as ErrorReconnectMessageUi
            val input = projector.reconnectChanged(runId, event((tick + 1) * 1_000L), previous)
            messages = batch.normalize(runId, input)
            assertEquals(normalizeTerminalRunMessages(runId, input), messages)
            assertEquals(tick + 1, batch.incrementalFastPathHits)
            (0 until previous.lastIndex).forEach { assertSame(previous[it], messages[it]) }
            assertEquals(tick * 1_000L, oldMarker.elapsedMs)
            assertEquals((tick + 1) * 1_000L, (messages.last() as ErrorReconnectMessageUi).elapsedMs)
        }
    }

    @Test
    fun markerPayloadAndTerminalUpdatesPreserveCurrentRunTerminalOrder() {
        ErrorReconnectStatus.entries.forEach { status ->
            val batch = AgentRunReplayBatch()
            val original = marker()
            val messages = batch.normalize(runId, listOf(
                UserMessageUi("user-$runId", "task"),
                SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped),
                original,
                AgentMessageUi("assistant-$runId-1-0", "answer"),
            ))
            val next = original.copy(status = status, elapsedMs = 4_000L, reasonCode = "http_503", reasonDetail = "busy")
            val input = messages.map { if (it === original) next else it }
            assertSame(input, batch.normalize(runId, input))
            assertEquals(normalizeTerminalRunMessages(runId, input), input)
            assertEquals(1, batch.incrementalFastPathHits)
            assertEquals(ErrorReconnectStatus.Running, original.status)
        }
    }

    @Test
    fun certifiedMarkerReplacementUsesSameRulesAndRetainsOldSnapshot() {
        val batch = AgentRunReplayBatch()
        val old = marker()
        val messages = listOf<AgentChatMessageUi>(
            UserMessageUi("user-$runId", "task"),
            SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped), old,
        ).incrementalSnapshot()
        assertSame(messages, batch.normalize(runId, messages))
        val input = messages.replacing(2, old.copy(elapsedMs = 1_000L))
        assertSame(input, batch.normalize(runId, input))
        assertEquals(normalizeTerminalRunMessages(runId, input), input)
        assertEquals(1, batch.incrementalFastPathHits)
        assertSame(old, messages[2])
    }

    @Test
    fun identityStructureAndAdditionalPayloadChangesUseFullPath() {
        val old = marker()
        val user = UserMessageUi("user-$runId", "task")
        val body = AgentMessageUi("assistant-$runId-1-0", "a", isStreaming = true)
        val baseline = listOf<AgentChatMessageUi>(user, body, old)
        val changed = old.copy(elapsedMs = 1_000L)
        val variants = listOf(
            listOf(user, body, changed.copy(id = "different")),
            listOf(user, body, changed.copy(runId = "other")),
            listOf(user, body, changed.copy(reconnectId = "new")),
            listOf(user, body.copy(content = "ab"), changed),
            listOf(user, changed, body),
            listOf(user, body),
            baseline + marker("new"),
            listOf(user, SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped), body, changed),
        )
        variants.forEach { input ->
            val batch = AgentRunReplayBatch()
            batch.normalize(runId, baseline)
            assertEquals(normalizeTerminalRunMessages(runId, input), batch.normalize(runId, input))
            assertEquals(0, batch.incrementalFastPathHits)
        }
        val batch = AgentRunReplayBatch()
        batch.normalize(runId, baseline)
        batch.normalize("other", listOf(user, body, changed))
        assertEquals(0, batch.incrementalFastPathHits)
    }

    @Test
    fun unnormalizedLateBodyCannotBorrowMarkerFastPath() {
        val batch = AgentRunReplayBatch()
        val old = marker()
        val user = UserMessageUi("user-$runId", "task")
        val notice = SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped)
        val body = AgentMessageUi("assistant-$runId-1-0", "late")
        val unnormalized = listOf(user, notice, body, old)
        batch.normalize(runId, unnormalized)
        val input = unnormalized.map { if (it === old) old.copy(elapsedMs = 1_000L) else it }
        assertEquals(normalizeTerminalRunMessages(runId, input), batch.normalize(runId, input))
        assertEquals(0, batch.incrementalFastPathHits)
    }

    @Test
    fun replayDefersMarkerNormalizationAndCustomOrderIsNeverBypassed() {
        var sorts = 0
        val batch = AgentRunReplayBatch { owner, input ->
            sorts++
            normalizeTerminalRunMessages(owner, input)
        }
        var messages: List<AgentChatMessageUi> = listOf(UserMessageUi("user-$runId", "task"), marker())
        batch.normalize(runId, messages)
        val projector = AgentRunMessageProjector { 1_000L }
        batch.replay(runId, listOf(event(1_000), event(2_000)), reset = {}, apply = {
            messages = batch.normalize(runId, projector.reconnectChanged(runId, it as AgentEvent.ErrorReconnectChanged, messages))
            assertEquals(1, sorts)
        }, finish = { messages = batch.normalize(runId, messages) })
        assertEquals(2, sorts)
        batch.normalize(runId, projector.reconnectChanged(runId, event(3_000), messages))
        assertEquals(3, sorts)
        assertEquals(0, batch.incrementalFastPathHits)
    }

    private fun marker(reconnectId: String = "disconnect") = ErrorReconnectMessageUi(
        id = errorReconnectMessageId(runId, reconnectId), runId = runId,
        reconnectId = reconnectId, round = 1, status = ErrorReconnectStatus.Running,
    )

    private fun event(elapsedMs: Long) = AgentEvent.ErrorReconnectChanged(
        round = 1, reconnectId = "disconnect", status = "running", elapsedMs = elapsedMs,
        reasonCode = "http_503", reasonDetail = "busy",
    )
}
