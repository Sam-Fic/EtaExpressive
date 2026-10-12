package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import io.github.mangi.eta.ui.model.canContinuePausedGeneration
import io.github.mangi.eta.ui.model.errorReconnectMessageId
import io.github.mangi.eta.ui.model.formatReconnectElapsed
import org.junit.Assert.*
import org.junit.Test

class AgentErrorReconnectProjectionTest {
    private fun event(id: String = "one", status: String = "running", elapsed: Long = 0L) =
        AgentEvent.ErrorReconnectChanged(1, id, status, elapsed, "MODEL_TIMEOUT", "request failed\nfull diagnostic")

    @Test fun elapsedFormatHasStableSecondsAndSupportsHoursAndLongValues() {
        assertEquals("0:00", formatReconnectElapsed(-1L))
        assertEquals("0:00", formatReconnectElapsed(999L))
        assertEquals("0:01", formatReconnectElapsed(1_000L))
        assertEquals("1:00", formatReconnectElapsed(60_000L))
        assertEquals("59:59", formatReconnectElapsed(3_599_999L))
        assertEquals("1:00:00", formatReconnectElapsed(3_600_000L))
        assertEquals("25:01:01", formatReconnectElapsed(90_061_000L))
        assertTrue(formatReconnectElapsed(Long.MAX_VALUE).contains(':'))
    }

    @Test fun progressReplacesInPlaceAndElapsedNeverRegresses() {
        val projector = AgentRunMessageProjector { 0L }
        val partial = AgentMessageUi("assistant-run-1-0", "partial \n", isStreaming = true)
        var messages: List<AgentChatMessageUi> = listOf(partial)
        messages = projector.reconnectChanged("run", event(elapsed = 2_000), messages)
        val ids = messages.map { it.id }
        val tail = UserMessageUi("user-other", "another turn")
        messages = messages + tail
        messages = projector.reconnectChanged("run", event(elapsed = 1_000), messages)
        assertEquals(ids + tail.id, messages.map { it.id })
        assertEquals(2_000L, messages.filterIsInstance<ErrorReconnectMessageUi>().single().elapsedMs)
        assertEquals("partial \n", (messages.first() as AgentMessageUi).content)
        assertFalse((messages.first() as AgentMessageUi).isStreaming)
        assertEquals(tail, messages.last())
        messages = projector.reconnectChanged("run", event(status = "succeeded", elapsed = 3_000), messages)
        val terminal = messages
        messages = projector.reconnectChanged("run", event(elapsed = 4_000), messages)
        assertEquals(terminal, messages)
        assertEquals(ErrorReconnectStatus.Succeeded, messages.filterIsInstance<ErrorReconnectMessageUi>().single().status)
    }

    @Test fun distinctDisconnectsAndRunsHaveDistinctStableIdsAndPreserveTools() {
        val projector = AgentRunMessageProjector { 0L }
        val tool = ToolActivityMessageUi("run-tool-1-call", "read_file", ToolActivityStatusUi.Success, "{}")
        var messages: List<AgentChatMessageUi> = listOf(tool)
        messages = projector.reconnectChanged("run", event(), messages)
        messages = projector.reconnectChanged("run", event(status = "succeeded"), messages)
        messages = projector.reconnectChanged("run", event("two"), messages)
        messages = projector.reconnectChanged("another", event("one"), messages)
        assertEquals(tool, messages.first())
        assertEquals(3, messages.filterIsInstance<ErrorReconnectMessageUi>().size)
        assertEquals(messages.size, messages.map { it.id }.toSet().size)
        assertNotEquals(errorReconnectMessageId("a", "b:c"), errorReconnectMessageId("a:b", "c"))
        assertNotEquals(errorReconnectMessageId("run", "one"), errorReconnectMessageId("another", "one"))
    }

    @Test fun sameBlockContinuationDoesNotMoveOrOverwritePartialText() {
        val projector = AgentRunMessageProjector { 0L }
        var messages = projector.appendTextDelta("run", 1, 0, "before ", emptyList())
        messages = projector.reconnectChanged("run", event(), messages)
        messages = projector.reconnectChanged("run", event(status = "succeeded"), messages)
        assertEquals(-1, AgentRunMessageProjector.resultTargetIndex("run", messages))
        assertNotEquals(messages.first().id, AgentRunMessageProjector.resultFallbackId("run", messages))
        projector.beginProviderRequest("run", 1)
        messages = projector.appendTextDelta("run", 1, 0, "after", messages)
        assertEquals(listOf("before ", "after"), messages.filterIsInstance<AgentMessageUi>().map { it.content })
        assertTrue(messages[1] is ErrorReconnectMessageUi)
        assertNotEquals(messages.first().id, messages.last().id)
        messages = projector.finalizeTextBlock("run", 1, 0, "after completed", messages)
        assertEquals("before ", (messages.first() as AgentMessageUi).content)
        assertEquals("after completed", (messages.last() as AgentMessageUi).content)
    }

    @Test fun completionAcrossRetryRoundsAddsOnlyUnseenTail() {
        val projector = AgentRunMessageProjector { 0L }
        var messages: List<AgentChatMessageUi> = listOf(
            AgentMessageUi("assistant-run-1-0", "older tool round"),
            AgentMessageUi("assistant-other-2-0", "other run"),
            AgentMessageUi("assistant-run-2-0", "before "))
        val reconnect = event().copy(round = 2)
        messages = projector.reconnectChanged("run", reconnect, messages)
        messages = projector.reconnectChanged("run", reconnect.copy(status = "succeeded"), messages)
        assertEquals("after", AgentRunMessageProjector.completedResultTail(
            "run", messages, -1, "before after"))
        messages = projector.appendTextDelta("run", 3, 0, "after", messages)
        val index = AgentRunMessageProjector.resultTargetIndex("run", messages)
        assertEquals("after", AgentRunMessageProjector.completedResultTail(
            "run", messages, index, "before after"))
        assertEquals("different", AgentRunMessageProjector.completedResultTail(
            "run", messages, index, "different"))
    }

    @Test fun terminalFailureAndStopAreIdempotentAndDoNotInventSuccess() {
        val projector = AgentRunMessageProjector { 0L }
        val partial = AgentMessageUi("assistant-run-1-0", "partial", isStreaming = true)
        val waiting = projector.reconnectChanged("run", event(), listOf(partial))
        val failed = projector.terminalFailure("run", "failure", waiting)
        assertEquals(2, failed.size)
        assertEquals(ErrorReconnectStatus.Failed, (failed.last() as ErrorReconnectMessageUi).status)
        assertEquals(failed, projector.terminalFailure("run", "failure", failed))
        val other = AgentRunMessageProjector { 0L }
        val beforeStop = other.reconnectChanged("run", event(), listOf(partial))
        other.seal("run")
        val stopped = other.runStopped("run", beforeStop)
        assertEquals(ErrorReconnectStatus.Stopped, (stopped.last() as ErrorReconnectMessageUi).status)
        assertEquals(stopped, other.runStopped("run", stopped))
        val resumed = other.reconnectChanged("run", event(elapsed = 6_000), stopped)
        assertEquals(stopped.size, resumed.size)
        assertEquals(ErrorReconnectStatus.Running, (resumed.last() as ErrorReconnectMessageUi).status)
        assertEquals(6_000L, (resumed.last() as ErrorReconnectMessageUi).elapsedMs)
        assertEquals(stopped.dropLast(1), resumed.dropLast(1))
        val continuedRun = AgentRunMessageProjector { 0L }
        val stoppedMarker = stopped.last() as ErrorReconnectMessageUi
        continuedRun.claimStoppedReconnect("run-next", stoppedMarker.id)
        continuedRun.seal("run-next")
        val continued = continuedRun.reconnectChanged("run-next", event("next", elapsed = 1_000), stopped)
        val continuedMarker = continued.filterIsInstance<ErrorReconnectMessageUi>().single()
        assertEquals(stopped.size, continued.size)
        assertEquals(stoppedMarker.id, continuedMarker.id)
        assertEquals(stoppedMarker.runId, continuedMarker.runId)
        assertEquals(ErrorReconnectStatus.Running, continuedMarker.status)
        assertEquals(1_000L, continuedMarker.elapsedMs)
        assertEquals(stopped.dropLast(1), continued.dropLast(1))
        val progressed = continuedRun.reconnectChanged("run-next", event("later", elapsed = 2_000), continued)
        val progressedMarker = progressed.filterIsInstance<ErrorReconnectMessageUi>().single()
        assertEquals(stoppedMarker.id, progressedMarker.id)
        assertEquals(ErrorReconnectStatus.Running, progressedMarker.status)
        assertEquals(2_000L, progressedMarker.elapsedMs)
        val stoppedAgain = continuedRun.runStopped("run-next", progressed)
        assertEquals(progressed.size, stoppedAgain.size)
        assertEquals(stoppedMarker.id, (stoppedAgain.last() as ErrorReconnectMessageUi).id)
        assertEquals(ErrorReconnectStatus.Stopped, (stoppedAgain.last() as ErrorReconnectMessageUi).status)
        val failedContinued = continuedRun.terminalFailure("run-next", "continued failed", progressed)
        assertEquals(progressed.size, failedContinued.size)
        assertEquals(stoppedMarker.id, (failedContinued.last() as ErrorReconnectMessageUi).id)
        assertEquals(ErrorReconnectStatus.Failed, (failedContinued.last() as ErrorReconnectMessageUi).status)
        val sealed = AgentRunMessageProjector { 0L }
        sealed.seal("run-next")
        assertEquals(stopped, sealed.reconnectChanged("run-next", event("other"), stopped))
        val appended = AgentRunMessageProjector { 0L }.reconnectChanged("run-next", event("other"), stopped)
        assertEquals(stopped.size + 1, appended.size)
        assertEquals(ErrorReconnectStatus.Stopped, (appended[appended.lastIndex - 1] as ErrorReconnectMessageUi).status)
        assertEquals(ErrorReconnectStatus.Running, (appended.last() as ErrorReconnectMessageUi).status)
        val unrelated = AgentMessageUi("assistant-other", "kept")
        val failedOnly = listOf(unrelated, (stopped.last() as ErrorReconnectMessageUi).copy(status = ErrorReconnectStatus.Failed))
        val notAdopted = AgentRunMessageProjector { 0L }.reconnectChanged("run-next", event(), failedOnly)
        assertEquals(3, notAdopted.size)
        assertEquals(ErrorReconnectStatus.Failed, (notAdopted[1] as ErrorReconnectMessageUi).status)
        val finishing = AgentRunMessageProjector { 0L }
        val unfinished = finishing.finalizeRun("run", finishing.reconnectChanged("run", event(), emptyList()))
        assertEquals(ErrorReconnectStatus.Running, (unfinished.single() as ErrorReconnectMessageUi).status)
        val noRetry = AgentRunMessageProjector { 0L }.terminalFailure("run", "no retry detail", listOf(partial))
        assertFalse((noRetry.last() as ErrorReconnectMessageUi).isReconnect)
        assertEquals("partial", (noRetry.first() as AgentMessageUi).content)
    }

    @Test fun replayRebuildsSameIdentityAndContentWithoutAccumulation() {
        val projector = AgentRunMessageProjector { 0L }
        val user = UserMessageUi("user-run", "task")
        val otherRun = AgentRunMessageProjector { 0L }.terminalFailure("other", "old error", emptyList()).single()
        var messages: List<AgentChatMessageUi> = listOf(otherRun, user)
        var expected: List<AgentChatMessageUi>? = null
        repeat(3) {
            messages = projector.resetForReplay("run", messages)
            messages = projector.appendTextDelta("run", 1, 0, "partial", messages)
            messages = projector.reconnectChanged("run", event(), messages)
            messages = projector.reconnectChanged("run", event(status = "failed", elapsed = 5_000), messages)
            messages = projector.terminalFailure("run", "final reason", messages)
            if (expected == null) expected = messages else assertEquals(expected, messages)
            assertEquals(otherRun, messages.first())
            assertEquals("partial", messages.filterIsInstance<AgentMessageUi>().single().content)
        }
    }

    @Test fun failedMarkersAndManualStopRetainContinueWithoutBreakingPauseAndModelRetry() {
        val projector = AgentRunMessageProjector { 0L }
        val failed = projector.terminalFailure("run", "error", emptyList())
        assertTrue(canContinueDisconnectedRun(failed))
        val running = AgentRunMessageProjector { 0L }.reconnectChanged("other", event(), emptyList())
        assertFalse(canContinueDisconnectedRun(running))
        val stopped = AgentRunMessageProjector { 0L }.runStopped("other", running)
        assertTrue(canContinueDisconnectedRun(stopped))
        val withControl = stopped + SystemNoticeMessageUi("assistant-other", SystemNoticeCode.Stopped)
        assertTrue(canContinueDisconnectedRun(withControl))
        assertTrue(canContinuePausedGeneration(withControl))
        assertFalse(canContinueDisconnectedRun(failed + UserMessageUi("new-user", "new task")))
        val oldRetry = listOf(SystemNoticeMessageUi("retry", SystemNoticeCode.ModelRetry),
            SystemNoticeMessageUi("stop", SystemNoticeCode.Stopped))
        assertTrue(canContinueDisconnectedRun(oldRetry))
        val state = AgentRunRetryState()
        state.accept("run", event())
        assertTrue(state.isReconnecting("run"))
        assertFalse(state.isWaiting("run"))
        state.accept("run", event(status = "stopped"))
        assertFalse(state.isReconnecting("run"))
    }
}
