package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import org.junit.Assert.*
import org.junit.Test

class ErrorReconnectTimelineTest {
    @Test fun progressDoesNotChangeLazyKeysOrWorkGroupsAndFooterKeepsOriginalAnswer() {
        val projector = AgentRunMessageProjector { 0L }
        val partial = AgentMessageUi("assistant-run-1-0", "partial")
        val tool = ToolActivityMessageUi("run-tool-1-call", "read_file", ToolActivityStatusUi.Success, "{}")
        var messages = projector.reconnectChanged("run", AgentEvent.ErrorReconnectChanged(1, "one", "running", 0), listOf(partial, tool))
        val first = messages.toTimelineEntries().toLazyTimelineRows()
        messages = projector.reconnectChanged("run", AgentEvent.ErrorReconnectChanged(1, "one", "running", 4_000), messages)
        val updated = messages.toTimelineEntries().toLazyTimelineRows()
        assertEquals(first.map { it.key }, updated.map { it.key })
        assertTrue((updated.last() as AgentTimelineRow.Message).message is ErrorReconnectMessageUi)
        assertTrue(updated.any { it is AgentTimelineRow.WorkStep })
        messages = projector.terminalFailure("run", "full diagnostic", messages)
        val terminal = messages.toTimelineEntries().toLazyTimelineRows()
        assertEquals(first.map { it.key }, terminal.map { it.key })
        assertEquals(partial.id, terminal.turnFooters()[terminal.last().key]?.id)
        assertEquals("partial", (terminal.turnFooters()[terminal.last().key] as AgentMessageUi).content)
        assertEquals(ErrorReconnectStatus.Failed, (messages.last() as ErrorReconnectMessageUi).status)
    }
}
