package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.incrementalSnapshot
import org.junit.Assert.*
import org.junit.Test

class AgentTimelineRowsCacheTest {
    private class Fixture {
        var builds = 0
        val cache = AgentTimelineRowsCache { entries ->
            builds++
            entries.toLazyTimelineRows()
        }
        fun project(entries: List<AgentTimelineEntry>): List<AgentTimelineRow> {
            val before = entries.toList()
            return cache.project(entries).also { rows ->
                val expected = before.toLazyTimelineRows()
                assertEquals(expected, rows)
                assertEquals(expected.lazyUserMessageIndices(), rows.lazyUserMessageIndices())
                assertEquals(expected.turnFooters(), rows.turnFooters())
                assertEquals(before, entries)
            }
        }
    }

    private fun input(): List<AgentChatMessageUi> = listOf(
        UserMessageUi("user-run", "task"),
        ThinkingMessageUi("run-thinking-1", "work", false),
        ThinkingMessageUi("run-thinking-2", "more work", false),
        SystemNoticeMessageUi("interrupted-run", SystemNoticeCode.Completed),
        AgentMessageUi("assistant-run-1", "answer", true),
    )

    @Test fun workPayloadUpdatePreservesUnchangedSiblingRows() {
        val fixture = Fixture()
        val first = ThinkingMessageUi("first", "stable", false)
        val second = ThinkingMessageUi("second", "growing", true)
        val group = AgentTimelineEntry.WorkProcess("work-first", listOf(first, second))
        val entries = listOf<AgentTimelineEntry>(group).incrementalSnapshot()
        val before = fixture.project(entries)
        val changed = second.copy(content = "growing more")
        val after = fixture.project(entries.replacing(0, group.copy(messages = listOf(first, changed))))
        assertSame(before[0], after[0])
        assertNotSame(before[1], after[1])
        assertSame(changed, (after[1] as AgentTimelineRow.WorkStep).message)
        assertEquals(1, fixture.builds)
    }

    @Test fun sameSlotAssistantDeltaPatchesOnlyItsRowAndKeepsLatestFooterOwner() {
        val fixture = Fixture()
        val projection = AgentTimelineProjectionCache()
        val source = input()
        val entries = projection.project(source)
        val old = fixture.project(entries)
        val latest = (source.last() as AgentMessageUi).copy(content = "answer appended", isStreaming = false)
        val next = projection.project(source.dropLast(1) + latest)
        val rows = fixture.project(next)
        assertEquals(1, fixture.builds)
        assertNotSame(old, rows)
        rows.zip(old).forEach { (current, previous) ->
            if (current is AgentTimelineRow.Message && current.message is AgentMessageUi) {
                assertSame(latest, current.message)
            } else assertSame(previous, current)
        }
        // Do not cache footer owners with the row skeleton. The notice anchor
        // must still dispatch actions to the NEW assistant payload.
        assertSame(latest, rows.turnFooters().values.single())
        assertEquals(source.toTimelineEntries().toLazyTimelineRows(), old)
    }

    @Test fun unchangedReferencesReuseOutputAndMultipleAssistantSlotsPatch() {
        val fixture = Fixture()
        val entries: List<AgentTimelineEntry> = listOf(
            AgentTimelineEntry.Message(AgentMessageUi("a", "one")),
            AgentTimelineEntry.Message(AgentMessageUi("b", "two")),
        )
        val old = fixture.project(entries)
        assertSame(old, fixture.project(entries.toList()))
        val current = entries.map { entry ->
            AgentTimelineEntry.Message(((entry as AgentTimelineEntry.Message).message as AgentMessageUi).copy(content = "new"))
        }
        fixture.project(current)
        assertEquals(1, fixture.builds)
    }

    @Test fun unhintedThinkingDeltaPatchesOnlyItsContiguousGroup() {
        val fixture = Fixture()
        val visible = AgentVisibleMessagesCache()
        val projection = AgentTimelineProjectionCache()
        var messages: List<AgentChatMessageUi> = buildList {
            repeat(40) { run ->
                add(UserMessageUi("user-$run", "task"))
                add(ThinkingMessageUi("thinking-$run", "completed", false))
                add(AgentMessageUi("answer-$run", "answer"))
            }
            add(UserMessageUi("live-user", "new task"))
            add(ThinkingMessageUi("live-thinking", "work", true))
            add(AgentMessageUi("live-answer", "intermediate"))
        }
        val key = "work-live-thinking"
        val old = fixture.project(projection.project(visible.project(messages, null)))
        val groupStart = old.indexOfFirst { it.key == "work-step:live-thinking" }
        repeat(8) {
            val latest = (messages[messages.lastIndex - 1] as ThinkingMessageUi).copy(content = "work $it")
            messages = messages.toMutableList().apply { this[lastIndex - 1] = latest }
            // No singleReplacementFrom hint; every entry wrapper is rebuilt.
            val next = fixture.project(projection.project(visible.project(messages, null)))
            assertSame(latest, next.filterIsInstance<AgentTimelineRow.WorkStep>().last().message)
            old.indices.forEach { row ->
                val belongsToGroup = row == groupStart || (old[row] as? AgentTimelineRow.WorkStep)?.groupKey == key
                if (!belongsToGroup) assertSame(old[row], next[row])
            }
        }
        assertEquals(1, fixture.builds)
        assertEquals(
            "work",
            (old.filterIsInstance<AgentTimelineRow.WorkStep>().last().message as ThinkingMessageUi).content,
        )
    }

    @Test fun wrapperOnlySnapshotReuseKeepsTheNextReplacementCertificate() {
        val fixture = Fixture()
        val entries = input().toTimelineEntries().incrementalSnapshot()
        val old = fixture.project(entries)
        val wrappers = entries.map { entry ->
            when (entry) {
                is AgentTimelineEntry.Message -> entry.copy()
                is AgentTimelineEntry.WorkProcess -> entry.copy(messages = entry.messages.toList())
            }
        }.incrementalSnapshot()
        assertSame(old, fixture.project(wrappers))
        val groupIndex = wrappers.indexOfFirst { it is AgentTimelineEntry.WorkProcess }
        val group = wrappers[groupIndex] as AgentTimelineEntry.WorkProcess
        val current = group.copy(messages = group.messages.map { (it as ThinkingMessageUi).copy(content = "latest") })
        val after = fixture.project(wrappers.replacing(groupIndex, current))
        assertEquals(1, fixture.builds)
        after.filterIsInstance<AgentTimelineRow.WorkStep>().forEach { step ->
            assertSame(current.messages.first { it.id == step.message.id }, step.message)
        }
        assertEquals(entries.toLazyTimelineRows(), old)
    }

    @Test fun hintedToolCompletionPatchesItsGroup() {
        val fixture = Fixture()
        val running = ToolActivityMessageUi("tool", "terminal", ToolActivityStatusUi.Running, argumentsSummary = "command")
        val group = AgentTimelineEntry.WorkProcess("work-tool", listOf(running))
        val entries = listOf<AgentTimelineEntry>(group, AgentTimelineEntry.Message(AgentMessageUi("a", "answer"))).incrementalSnapshot()
        val before = fixture.project(entries)
        val done = running.copy(status = ToolActivityStatusUi.Success, resultSummary = "result")
        val current = entries.replacing(0, group.copy(messages = listOf(done)))
        val after = fixture.project(current)
        assertEquals(1, fixture.builds)
        assertSame(before.last(), after.last())
        assertSame(done, (after.first() as AgentTimelineRow.WorkStep).message)
        assertEquals(
            ToolActivityStatusUi.Running,
            ((before.first() as AgentTimelineRow.WorkStep).message as ToolActivityMessageUi).status,
        )
    }

    @Test fun hintedAssistantGrowthPatchesTheRowAndKeepsTheFooterOwner() {
        val fixture = Fixture()
        val live = AgentMessageUi("a", "partial", true)
        val entries = listOf<AgentTimelineEntry>(AgentTimelineEntry.Message(live)).incrementalSnapshot()
        val before = fixture.project(entries)
        val grown = live.copy(content = "partial more")
        val after = fixture.project(entries.replacing(0, AgentTimelineEntry.Message(grown)))
        assertEquals(1, fixture.builds)
        assertSame(grown, (after.single() as AgentTimelineRow.Message).message)
        assertSame(grown, after.turnFooters().values.single())
        assertSame(live, (before.single() as AgentTimelineRow.Message).message)
    }

    @Test fun workKeyStepIdsAndMultipleChangedGroupsFallBack() {
        val first = AgentTimelineEntry.WorkProcess("work-one", listOf(ThinkingMessageUi("one", "work", true)))
        val second = AgentTimelineEntry.WorkProcess("work-two", listOf(ThinkingMessageUi("two", "work", true)))
        val entries = listOf<AgentTimelineEntry>(first, AgentTimelineEntry.Message(AgentMessageUi("a", "answer")), second)
        val variants = listOf(
            entries.toMutableList().apply { this[0] = first.copy(key = "new-work-key") },
            entries.toMutableList().apply { this[0] = first.copy(messages = listOf(ThinkingMessageUi("different", "work", true))) },
            entries.toMutableList().apply {
                this[0] = first.copy(messages = listOf(ThinkingMessageUi("one", "new", true)))
                this[2] = second.copy(messages = listOf(ThinkingMessageUi("two", "new", true)))
            },
            entries.toMutableList().apply {
                this[0] = first.copy(messages = listOf(ThinkingMessageUi("one", "new", true)))
                this[1] = AgentTimelineEntry.Message(AgentMessageUi("a", "new answer"))
            },
        )
        variants.forEach { current ->
            val fixture = Fixture()
            val before = fixture.project(entries)
            fixture.project(current)
            assertEquals(2, fixture.builds)
            assertEquals(entries.toLazyTimelineRows(), before)
        }
    }

    @Test fun nonContiguousRowsDisableAssistantAndWorkFastPaths() {
        for (hinted in listOf(false, true)) {
            for (changeWork in listOf(false, true)) {
                var builds = 0
                val cache = AgentTimelineRowsCache { entries ->
                    builds++
                    val result = entries.toLazyTimelineRows()
                    listOf(result[1], result[0]) // assistant, detached step
                }
                val group = AgentTimelineEntry.WorkProcess("work-t", listOf(ThinkingMessageUi("t", "work", true)))
                val answer = AgentTimelineEntry.Message(AgentMessageUi("a", "answer", true))
                val entries = listOf<AgentTimelineEntry>(group, answer).incrementalSnapshot()
                cache.project(entries)
                val replacement = if (changeWork) group.copy(messages = listOf(ThinkingMessageUi("t", "more", true)))
                    else AgentTimelineEntry.Message(AgentMessageUi("a", "answer more", true))
                val index = if (changeWork) 0 else 1
                val current = entries.replacing(index, replacement)
                cache.project(if (hinted) current else current.withoutReplacementHint())
                assertEquals(2, builds)
            }
        }
    }

    @Test fun insertDeleteReorderBoundaryAndWorkChangesUseFullProjection() {
        val entries = input().toTimelineEntries()
        val variants = listOf(
            entries + AgentTimelineEntry.Message(AgentMessageUi("new", "body")),
            entries.toMutableList().apply { add(1, AgentTimelineEntry.Message(AgentMessageUi("new", "body"))) },
            entries.dropLast(1),
            entries.toMutableList().apply { removeAt(1) },
            entries.reversed(),
            entries.toMutableList().apply { this[0] = AgentTimelineEntry.Message(UserMessageUi("user-run-supplement-resume", "task")) },
            entries.toMutableList().apply { this[0] = AgentTimelineEntry.Message(UserMessageUi("user-run", "edited")) },
            entries.toMutableList().apply { this[1] = (entries[1] as AgentTimelineEntry.WorkProcess).copy(
                messages = listOf(ThinkingMessageUi("run-thinking-1", "work", true))) },
            entries.toMutableList().apply { this[2] = AgentTimelineEntry.Message(AgentMessageUi("different-id", "body")) },
        )
        variants.forEach { current ->
            val fixture = Fixture()
            val old = fixture.project(entries)
            fixture.project(current)
            assertEquals(2, fixture.builds)
            assertEquals(entries.toLazyTimelineRows(), old)
        }
    }

    @Test fun duplicateIdsDisableFastPath() {
        val assistant = AgentMessageUi("same", "body", true)
        for (duplicate in listOf<AgentTimelineEntry>(
            AgentTimelineEntry.Message(assistant),
            AgentTimelineEntry.Message(UserMessageUi("same", "task")),
            AgentTimelineEntry.WorkProcess("work-same", listOf(ThinkingMessageUi("same", "work", false))),
        )) {
            val fixture = Fixture()
            val entries = listOf(duplicate, AgentTimelineEntry.Message(assistant))
            fixture.project(entries)
            fixture.project(entries.dropLast(1) + AgentTimelineEntry.Message(assistant.copy(content = "body more")))
            assertEquals(2, fixture.builds)
        }
    }

    @Test fun unexpectedRowMappingFailsClosed() {
        var builds = 0
        val cache = AgentTimelineRowsCache { _ -> builds++; emptyList() }
        cache.project(listOf(AgentTimelineEntry.Message(AgentMessageUi("a", "old"))))
        cache.project(listOf(AgentTimelineEntry.Message(AgentMessageUi("a", "new"))))
        assertEquals(2, builds)
    }
}
