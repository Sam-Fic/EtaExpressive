package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class AgentSpeechPrefaceCacheTest {
    private class Fixture {
        var builds = 0
        val cache = AgentSpeechPrefaceCache { messages, ids, hiddenIds ->
            builds++
            visibleTurnSpeechPrefaces(messages, ids, hiddenIds)
        }
        fun project(messages: List<AgentChatMessageUi>, ids: Set<String>): Map<String, String> {
            val before = messages.toList()
            return cache.project(messages, ids).also {
                assertEquals(visibleTurnSpeechPrefaces(before, ids), it)
                assertEquals(before, messages)
            }
        }
    }

    private val owner = AgentMessageUi("owner", "done")
    private val live = AgentMessageUi("live", "start", isStreaming = true)
    private fun base(): List<AgentChatMessageUi> = listOf(
        UserMessageUi("user-old", "task"), AgentMessageUi("preface", " earlier "), owner,
        UserMessageUi("user-live", "next"), live,
    )

    @Test fun sameSlotLiveAppendAfterOrdinaryUserBoundaryReusesStringsAndSnapshots() {
        val fixture = Fixture()
        var messages = base()
        val old = fixture.project(messages, setOf(owner.id))
        assertEquals("earlier", old[owner.id])
        repeat(12) { index ->
            val previous = messages.last() as AgentMessageUi
            messages = messages.dropLast(1) + previous.copy(content = previous.content + "-$index")
            assertSame(old, fixture.project(messages, setOf(owner.id)))
        }
        // A finishing update (isStreaming true -> false) is not an eligible delta slot.
        messages = messages.dropLast(1) + (messages.last() as AgentMessageUi).copy(isStreaming = false)
        assertNotSame(old, fixture.project(messages, setOf(owner.id)))
        assertEquals(2, fixture.builds)
        assertEquals("earlier", old[owner.id])
    }

    @Test fun noOwnersStillChecksSlotReferencesAndDoesNotOnlyKeyOnFinalIds() {
        val fixture = Fixture()
        val messages = base()
        val old = fixture.project(messages, emptySet())
        assertSame(old, fixture.project(messages.dropLast(1) + live.copy(content = "start more"), emptySet()))
        assertEquals(1, fixture.builds)
        fixture.project(messages.drop(1), emptySet())
        assertEquals(2, fixture.builds)
    }

    @Test fun changedSelectedOwnerAndEarlierBodyInItsTurnAlwaysFallBack() {
        val earlier = live.copy(id = "earlier")
        val messages: List<AgentChatMessageUi> = listOf(UserMessageUi("user", "task"), earlier, owner)
        for (current in listOf(
            listOf(messages[0], earlier.copy(content = "start more"), owner),
            listOf(messages[0], earlier, owner.copy(content = "edited owner")),
        )) {
            val fixture = Fixture()
            val old = fixture.project(messages, setOf(owner.id))
            fixture.project(current, setOf(owner.id))
            assertEquals(2, fixture.builds)
            assertEquals("start", old[owner.id])
        }
    }

    @Test fun selectedStreamingOwnerAppendAndCompletedHistoryAppendAreNotHits() {
        val selected = Fixture()
        selected.project(listOf(live), setOf(live.id))
        selected.project(listOf(live.copy(content = "start more")), setOf(live.id))
        assertEquals(2, selected.builds)

        val history = Fixture()
        val completed = live.copy(isStreaming = false)
        history.project(listOf(completed), emptySet())
        history.project(listOf(completed.copy(content = "start more")), emptySet())
        assertEquals(2, history.builds)
    }

    @Test fun selectedOwnerBehindTheDeltaWithoutAnOrdinaryBoundaryAlsoFallsBack() {
        val fixture = Fixture()
        val messages: List<AgentChatMessageUi> = listOf(UserMessageUi("user", "task"), owner, live)
        fixture.project(messages, setOf(owner.id))
        fixture.project(messages.dropLast(1) + live.copy(content = "start more"), setOf(owner.id))
        assertEquals(2, fixture.builds)
    }

    @Test fun steerAndResumeSupplementsDoNotBreakTheOwnerDependency() {
        for (id in listOf("user-run-supplement-1", "user-run-supplement-resume")) {
            val fixture = Fixture()
            val messages: List<AgentChatMessageUi> = listOf(UserMessageUi("user-run", "task"), live,
                UserMessageUi(id, "supplement"), owner)
            fixture.project(messages, setOf(owner.id))
            val current = messages.toMutableList().apply { this[1] = live.copy(content = "start appended") }
            assertEquals("start appended", fixture.project(current, setOf(owner.id))[owner.id])
            assertEquals(2, fixture.builds)
        }
    }

    @Test fun structuralBoundaryAndHistoricalEditCounterexamplesAllRebuild() {
        val messages = base()
        val variants = listOf(
            messages + AgentMessageUi("inserted", "body"),
            messages.toMutableList().apply { add(2, AgentMessageUi("inserted", "body")) },
            messages.dropLast(1),
            messages.toMutableList().apply { removeAt(1) },
            messages.reversed(),
            messages.toMutableList().apply { this[3] = UserMessageUi("user-live-supplement-resume", "next") },
            messages.toMutableList().apply { this[3] = (messages[3] as UserMessageUi).copy(content = "edit") },
            messages.toMutableList().apply { this[1] = (messages[1] as AgentMessageUi).copy(content = "edited history") },
            messages.dropLast(1) + live.copy(content = "not an append"),
            messages.dropLast(1) + live.copy(id = "new-live", content = "start more"),
        )
        variants.forEach { current ->
            val fixture = Fixture()
            val old = fixture.project(messages, setOf(owner.id))
            fixture.project(current, setOf(owner.id))
            assertEquals(2, fixture.builds)
            assertEquals(visibleTurnSpeechPrefaces(messages, setOf(owner.id)), old)
        }
    }

    @Test fun duplicateIdsAcrossAssistantAndOtherTypesDisableReuse() {
        for (duplicate in listOf<AgentChatMessageUi>(live, UserMessageUi(live.id, "task"))) {
            val fixture = Fixture()
            val messages = base() + duplicate
            fixture.project(messages, setOf(owner.id))
            val current = messages.toMutableList().apply { this[4] = live.copy(content = "start more") }
            fixture.project(current, setOf(owner.id))
            assertEquals(2, fixture.builds)
        }
    }

    @Test fun changedOwnerSetAndChangedNoticeReferenceCannotReuseOldResult() {
        val fixture = Fixture()
        val notice = SystemNoticeMessageUi("notice", SystemNoticeCode.Stopped)
        val messages = base() + notice
        fixture.project(messages, setOf(owner.id))
        fixture.project(messages, setOf(live.id, notice.id))
        assertEquals(2, fixture.builds)
        fixture.project(messages.dropLast(1) + notice.copy(code = SystemNoticeCode.Completed), setOf(live.id, notice.id))
        assertEquals(3, fixture.builds)
    }

    @Test fun mutableContainersCannotRewriteTheSavedDependencySnapshot() {
        val fixture = Fixture()
        val messages = base().toMutableList()
        val ids = mutableSetOf(owner.id)
        val old = fixture.project(messages, ids)
        messages[4] = live.copy(content = "start more")
        assertSame(old, fixture.project(messages, ids))
        ids.add(live.id)
        fixture.project(messages, ids)
        assertEquals(2, fixture.builds)
        assertEquals(setOf(owner.id), old.keys)
    }

    @Test fun anOwnerAtAUserSlotRetainsLegacyBeforeBoundarySemantics() {
        val fixture = Fixture()
        val messages: List<AgentChatMessageUi> = listOf(live, UserMessageUi("boundary-owner", "task"))
        fixture.project(messages, setOf("boundary-owner"))
        val current = listOf(live.copy(content = "start more"), messages[1])
        assertEquals("start more", fixture.project(current, setOf("boundary-owner"))["boundary-owner"])
        assertEquals(2, fixture.builds)
    }
}
