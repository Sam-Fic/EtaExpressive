package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentIncrementalList
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.incrementalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AgentTimelineDerivedCacheTest {
    private fun rows(live: AgentMessageUi): AgentIncrementalList<AgentTimelineRow> = listOf(
        AgentTimelineRow.Message(UserMessageUi("user-1", "task")),
        AgentTimelineRow.Message(live),
        AgentTimelineRow.Message(SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Completed)),
    ).incrementalSnapshot()

    private fun project(cache: AgentTimelineDerivedCache, rows: List<AgentTimelineRow>) =
        cache.project(rows, isStreaming = true, isPaused = false, isCompressingContext = false)

    @Test fun assistantGrowthReusesStructureAndRefreshesMessageOwners() {
        val cache = AgentTimelineDerivedCache()
        val live = AgentMessageUi("assistant-1", "answer", true)
        val first = rows(live)
        val before = project(cache, first)
        assertEquals(1, before.turnFooters.size)
        assertSame(live, before.turnFooters.values.single())
        assertSame(live, before.footerRevealMessages.values.single().single())

        val grown = live.copy(content = "answer appended")
        val after = project(cache, first.replacing(1, AgentTimelineRow.Message(grown)))

        // Structure-only results are literally the same objects: no O(n) re-scan per token.
        assertSame(before.userBubblePositions, after.userBubblePositions)
        assertSame(before.workRowFollowsUser, after.workRowFollowsUser)
        assertSame(before.receiptSeams, after.receiptSeams)
        assertSame(before.userMessageTargets, after.userMessageTargets)
        assertSame(before.foldedReplyIds, after.foldedReplyIds)
        assertSame(before.finalResultMessageIds, after.finalResultMessageIds)
        // Owners must dispatch actions against the NEW payload, not the stale one.
        assertSame(grown, after.turnFooters.values.single())
        assertSame(grown, after.footerRevealMessages.values.single().single())
        assertEquals(before.turnFooters.keys, after.turnFooters.keys)
        assertSame(live, before.turnFooters.values.single())
    }

    @Test fun blankToNonBlankFallsBackToFullProjection() {
        val cache = AgentTimelineDerivedCache()
        val blank = AgentMessageUi("assistant-1", "", true)
        val first = rows(blank)
        val before = project(cache, first)
        val filled = blank.copy(content = "first token")
        val after = project(cache, first.replacing(1, AgentTimelineRow.Message(filled)))
        assertNotSame(before.userBubblePositions, after.userBubblePositions)
        assertNotSame(before.receiptSeams, after.receiptSeams)
        assertSame(filled, after.footerRevealMessages.values.single().single())
    }

    @Test fun runStateChangeFallsBackToFullProjection() {
        val cache = AgentTimelineDerivedCache()
        val live = AgentMessageUi("assistant-1", "answer", true)
        val first = rows(live)
        val before = project(cache, first)
        val grown = live.copy(content = "answer appended")
        val next = first.replacing(1, AgentTimelineRow.Message(grown))
        val after = cache.project(next, isStreaming = false, isPaused = false, isCompressingContext = false)
        assertNotSame(before.userBubblePositions, after.userBubblePositions)
        assertSame(grown, after.turnFooters.values.single())
    }

    @Test fun unhintedAndStructuralDeltasFallBackToFullProjection() {
        listOf(
            "different-id" to true,
            "assistant-1" to false,
        ).forEach { (id, hinted) ->
            val cache = AgentTimelineDerivedCache()
            val live = AgentMessageUi("assistant-1", "answer", true)
            val first = rows(live)
            val before = project(cache, first)
            val replacement = AgentMessageUi(id, "answer appended", true)
            var next = first.replacing(1, AgentTimelineRow.Message(replacement))
            if (!hinted) next = next.withoutReplacementHint()
            val after = project(cache, next)
            assertNotSame(before.userBubblePositions, after.userBubblePositions)
            assertNotSame(before.receiptSeams, after.receiptSeams)
        }
    }

    @Test fun insertionTriggersFullProjection() {
        val cache = AgentTimelineDerivedCache()
        val live = AgentMessageUi("assistant-1", "answer", true)
        val first = rows(live)
        val before = project(cache, first)
        val appended = first.toMutableList().apply {
            add(AgentTimelineRow.Message(AgentMessageUi("assistant-2", "more", true)))
        }.incrementalSnapshot()
        val after = project(cache, appended)
        assertNotSame(before.userBubblePositions, after.userBubblePositions)
        assertNotSame(before.userMessageTargets, after.userMessageTargets)
        assertEquals(listOf(0), after.userMessageTargets)
    }

    @Test fun foldedWorkKeysReuseCertificateUntilStructureChanges() {
        val cache = AgentFoldedWorkKeysCache()
        val live = AgentMessageUi("assistant-1", "answer", true)
        val entries = listOf<AgentTimelineEntry>(
            AgentTimelineEntry.Message(UserMessageUi("user-1", "task")),
            AgentTimelineEntry.WorkProcess("work-1", listOf(ThinkingMessageUi("t1", "work", false))),
            AgentTimelineEntry.Message(live),
        ).incrementalSnapshot()
        val before = cache.project(entries, enabled = true, foldLatestTurn = true)
        assertEquals(setOf("work-1"), before.folded)
        assertEquals(setOf("work-1"), before.activeGroupKeys)

        val grown = cache.project(
            entries.replacing(2, AgentTimelineEntry.Message(live.copy(content = "answer appended"))),
            enabled = true,
            foldLatestTurn = true,
        )
        assertSame(before, grown)

        val blank = cache.project(
            entries.replacing(2, AgentTimelineEntry.Message(live.copy(content = ""))),
            enabled = true,
            foldLatestTurn = true,
        )
        assertNotSame(before, blank)
        assertEquals(emptySet<String>(), blank.folded)
    }

    @Test fun foldModeChangeForcesFullProjection() {
        val cache = AgentFoldedWorkKeysCache()
        val entries = listOf<AgentTimelineEntry>(
            AgentTimelineEntry.Message(UserMessageUi("user-1", "task")),
            AgentTimelineEntry.WorkProcess("work-1", listOf(ThinkingMessageUi("t1", "work", false))),
            AgentTimelineEntry.Message(AgentMessageUi("assistant-1", "answer", true)),
        )
        val folded = cache.project(entries, enabled = true, foldLatestTurn = true)
        val disabled = cache.project(entries, enabled = false, foldLatestTurn = true)
        assertNotSame(folded, disabled)
        assertEquals(emptySet<String>(), disabled.folded)
    }
}
