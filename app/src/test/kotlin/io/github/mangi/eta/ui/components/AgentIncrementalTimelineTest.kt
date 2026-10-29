package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.app.AgentRunReplayBatch
import io.github.mangi.eta.ui.model.*
import org.junit.Assert.*
import org.junit.Test

class AgentIncrementalTimelineTest {
    @Test fun producerFilteringProjectionAndRowsPreserveSingleSlotEvidenceAndOldSnapshots() {
        val projector = AgentRunMessageProjector { 1_000L }
        val replayBatch = AgentRunReplayBatch()
        val filter = AgentVisibleMessagesCache()
        var fullBuilds = 0
        val timeline = AgentTimelineProjectionCache { fullBuilds++; it.toTimelineEntries() }
        val rows = AgentTimelineRowsCache()
        var messages: List<AgentChatMessageUi> = projector.appendTextDelta(
            "live", 1, 0, "start", List(4096) { UserMessageUi("user-$it", "old") },
        )
        messages = replayBatch.normalize("live", messages)
        val original = messages
        var visible = filter.project(messages, null)
        var entries = timeline.project(visible)
        var projectedRows = rows.project(entries)
        val originalRows = projectedRows
        repeat(50) { iteration ->
            val previousVisible = visible
            val previousEntries = entries
            val previousRows = projectedRows
            val previousMessages = messages
            messages = projector.appendTextDelta("live", 1, 0, "-$iteration", messages)
            messages = replayBatch.normalize("live", messages)
            assertEquals(messages.lastIndex,
                (messages as AgentIncrementalList<*>).singleReplacementFrom(previousMessages))
            visible = filter.project(messages, null)
            assertEquals(visible.lastIndex,
                (visible as AgentIncrementalList<*>).singleReplacementFrom(previousVisible))
            entries = timeline.project(visible)
            assertNotNull((entries as AgentIncrementalList<*>).singleReplacementFrom(previousEntries))
            projectedRows = rows.project(entries)
            assertNotNull((projectedRows as AgentIncrementalList<*>).singleReplacementFrom(previousRows))
            assertEquals(visible.toTimelineEntries(), entries)
            assertEquals(entries.toLazyTimelineRows(), projectedRows)
        }
        assertEquals(1, fullBuilds)
        assertEquals(50, replayBatch.incrementalFastPathHits)
        assertEquals("start", (original.last() as AgentMessageUi).content)
        assertEquals("start", ((originalRows.last() as AgentTimelineRow.Message).message as AgentMessageUi).content)
    }

    @Test fun blankVisibilityEditingSkippedSnapshotsAndMutableInputsUseSafeFallbacks() {
        val cache = AgentVisibleMessagesCache()
        var source = listOf<AgentChatMessageUi>(UserMessageUi("user-1", "task"),
            AgentMessageUi("assistant-1", "", true)).incrementalSnapshot()
        val before = cache.project(source, null)
        assertEquals(1, before.size)
        source = source.replacing(1, (source[1] as AgentMessageUi).copy(content = "now visible"))
        val after = cache.project(source, null)
        assertEquals(2, after.size)
        assertEquals(1, before.size)
        val skipped = source.replacing(1, (source[1] as AgentMessageUi).copy(content = "one"))
            .replacing(1, (source[1] as AgentMessageUi).copy(content = "two"))
        assertEquals("two", (cache.project(skipped, null).last() as AgentMessageUi).content)
        assertEquals(listOf(source[0]), cache.project(source, source[0].id))
        assertEquals(source, cache.project(source, null))
        val mutable = source.toMutableList()
        val frozen = cache.project(mutable, null)
        mutable[1] = (mutable[1] as AgentMessageUi).copy(content = "edited")
        assertEquals("now visible", (frozen.last() as AgentMessageUi).content)
        assertEquals("edited", (cache.project(mutable, null).last() as AgentMessageUi).content)
    }
    @Test fun certifiedTerminalAndHistoricalEditFallBackToAuthoritativeProjection() {
        var builds = 0
        val cache = AgentTimelineProjectionCache { builds++; it.toTimelineEntries() }
        val original = listOf<AgentChatMessageUi>(AgentMessageUi("assistant-live-1", "start", true)).incrementalSnapshot()
        cache.project(original)
        val terminal = original.replacing(0, (original[0] as AgentMessageUi).copy(isStreaming = false))
        assertEquals(terminal.toTimelineEntries(), cache.project(terminal))
        assertEquals(2, builds)
        val edited = terminal.replacing(0, (terminal[0] as AgentMessageUi).copy(content = "edited"))
        assertEquals(edited.toTimelineEntries(), cache.project(edited))
        assertEquals(3, builds)
    }

}
