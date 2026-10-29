package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTurnFooterProjectionTest {
    @Test fun trailingWorkMovesTheFooterNotTheAnswerOrCallbacks() {
        val answer = AgentMessageUi("answer", "original answer")
        val thinking = ThinkingMessageUi("thinking", "all reasoning", false)
        val tool = tool("tool")
        val messages = listOf(UserMessageUi("user", "question"), answer, thinking, tool)
        for (expanded in listOf(false, true)) {
            val rows = rows(messages, expanded)
            val footers = rows.turnFooters()
            assertEquals(listOf(rows.last().key), footers.keys.toList())
            assertSame(answer, footers.values.single())
            assertEquals(messages, rows.originalMessages())
            assertFalse(footers.containsKey(answer.id))
        }
    }

    @Test fun stoppedAndRuntimeFailedNeverRenderActionsBeforeLegacyTrailingWork() {
        for (code in listOf(SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed)) {
            val notice = SystemNoticeMessageUi("notice", code, "original detail")
            val messages = listOf(
                UserMessageUi("user", "question"),
                AgentMessageUi("answer", "partial"),
                notice,
                ThinkingMessageUi("thinking", "reasoning", false),
                tool("tool"),
                ToolSummaryMessageUi("summary", listOf("terminal")),
            )
            for (expanded in listOf(false, true)) {
                val rows = rows(messages, expanded)
                val footers = rows.turnFooters()
                assertEquals(listOf(rows.last().key), footers.keys.toList())
                assertSame(notice, footers.values.single())
                assertEquals(messages, rows.originalMessages())
            }
        }
    }

    @Test fun completedDividerKeepsOriginalAnswerAsActionOwnerAfterAllWork() {
        val answer = AgentMessageUi("answer", "copy and speak this")
        val messages = listOf(
            UserMessageUi("user", "question"), answer,
            SystemNoticeMessageUi("completed", SystemNoticeCode.Completed),
            ThinkingMessageUi("thinking", "reasoning", false), tool("tool"),
        )
        for (expanded in listOf(false, true)) {
            val rows = rows(messages, expanded)
            val footers = rows.turnFooters()
            assertEquals(listOf(rows.last().key), footers.keys.toList())
            assertSame(answer, footers.values.single())
            assertEquals(messages, rows.originalMessages())
        }
    }

    @Test fun completedWithoutTextStillHasAnActionOwner() {
        val completed = SystemNoticeMessageUi("completed", SystemNoticeCode.Completed)
        val rows = rows(listOf(UserMessageUi("user", "question"), tool("tool"), completed))
        assertEquals(mapOf(completed.id to completed), rows.turnFooters())
    }

    @Test fun completedFooterIgnoresEmptyUsageAndEmptyPlaceholders() {
        val answer = AgentMessageUi("answer", "visible answer")
        val completed = SystemNoticeMessageUi("completed", SystemNoticeCode.Completed)
        val usage = AgentMessageUi("usage", "", usage = TokenUsageUi(outputTokens = 17))
        for (messages in listOf(
            listOf(answer, usage, completed),
            listOf(answer, completed, usage),
            listOf(answer, AgentMessageUi("empty", "  "), completed),
        )) {
            val rows = rows(messages)
            assertSame(answer, rows.turnFooters().values.single())
            assertEquals(rows.last().key, rows.turnFooters().keys.single())
            assertEquals(messages, rows.originalMessages())
        }
    }

    @Test fun normalizedLateWorkRemainsBeforeItsTerminalFooter() {
        for (code in listOf(SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed, SystemNoticeCode.Completed)) {
            val answer = AgentMessageUi("assistant-run-1", "answer")
            val notice = SystemNoticeMessageUi("interrupted-run", code)
            val thinking = ThinkingMessageUi("run-thinking-1", "late reasoning", false)
            val tool = tool("run-tool-1-call")
            // normalizeTerminalRunMessages is intentionally owned by the parent
            // module; this fixture represents its output.
            val messages = listOf(UserMessageUi("user-run", "question"), answer, thinking, tool, notice)
            for (expanded in listOf(false, true)) {
                val rows = rows(messages, expanded)
                val normalized = rows.originalMessages()
                assertEquals(messages.map { it.id }.toSet(), normalized.map { it.id }.toSet())
                assertTrue(normalized.indexOf(thinking) < normalized.indexOf(notice))
                assertTrue(normalized.indexOf(tool) < normalized.indexOf(notice))
                val footers = rows.turnFooters()
                assertEquals(notice.id, footers.keys.single())
                assertEquals(if (code == SystemNoticeCode.Completed) answer.id else notice.id,
                    footers.values.single().id)
            }
        }
    }

    @Test fun lateSnapshotAndAppendRecomputeTheAnchorWithoutChangingTheOwner() {
        val answer = AgentMessageUi("answer", "answer")
        val notice = SystemNoticeMessageUi("notice", SystemNoticeCode.Stopped)
        val before = listOf(answer, notice, tool("one"))
        val after = before + ThinkingMessageUi("late-thinking", "late reasoning", false) + tool("two")
        val firstRows = rows(before, expanded = true)
        val nextRows = rows(after, expanded = true)
        assertSame(notice, firstRows.turnFooters().values.single())
        assertSame(notice, nextRows.turnFooters().values.single())
        assertEquals("work-step:one", firstRows.turnFooters().keys.single())
        assertEquals("work-step:two", nextRows.turnFooters().keys.single())
        assertEquals(after, nextRows.originalMessages())
        val updated = after.map { if (it.id == "two") tool("two").copy(resultSummary = "late result") else it }
        val updatedRows = rows(updated, expanded = true)
        assertEquals(nextRows.turnFooters(), updatedRows.turnFooters())
        assertEquals(updated, updatedRows.originalMessages())
    }

    @Test fun workBatchesPlaceExactlyOneFooterAfterTheLastStep() {
        val answer = AgentMessageUi("answer", "answer")
        val messages = listOf(answer) + List(65) { tool("tool-$it") }
        val rows = rows(messages)
        assertEquals(65, rows.filterIsInstance<AgentTimelineRow.WorkStep>().size)
        assertEquals(rows.last().key, rows.turnFooters().keys.single())
        assertEquals("work-step:tool-64", rows.turnFooters().keys.single())
        assertSame(answer, rows.turnFooters().values.single())
        assertEquals(messages, rows.originalMessages())
    }

    @Test fun activeOrPausedTurnKeepsPreviousFooterButNeverExposesCurrentText() {
        val oldAnswer = AgentMessageUi("old-answer", "completed answer")
        for (partialStreaming in listOf(false, true)) {
            val messages = listOf(UserMessageUi("old-user", "old question"), oldAnswer,
                UserMessageUi("user", "question"),
                AgentMessageUi("answer", "partial", isStreaming = partialStreaming), tool("tool"))
            for (expanded in listOf(false, true)) {
                val rows = rows(messages, expanded)
                for ((streaming, paused) in listOf(true to false, false to true, true to true)) {
                    val footers = rows.turnFooters(isStreaming = streaming, isPaused = paused)
                    assertSame(oldAnswer, footers.values.single())
                    assertEquals(oldAnswer.id, footers.keys.single())
                }
                assertEquals(2, rows.turnFooters().size)
            }
        }
        assertTrue(rows(listOf(AgentMessageUi("empty", "", isStreaming = true)))
            .turnFooters(isStreaming = true).isEmpty())
    }

    @Test fun activeStreamingOrCompressionDoesNotExposeAnIntermediateFooter() {
        val rows = rows(listOf(
            UserMessageUi("user", "question"), AgentMessageUi("answer", "intermediate"), tool("tool"),
        ))
        assertTrue(rows.turnFooters(isStreaming = true).isEmpty())
        assertTrue(rows.turnFooters(isPaused = true).isEmpty())
        assertTrue(rows.turnFooters(isCompressingContext = true).isEmpty())
        assertEquals(setOf("answer"), rows.turnFooters().values.map { it.id }.toSet())
    }

    @Test fun closedNoticeKeepsItsFooterWhileTheRetryStreams() {
        for (code in listOf(SystemNoticeCode.Stopped, SystemNoticeCode.RuntimeFailed, SystemNoticeCode.Completed)) {
            val answer = AgentMessageUi("old-answer", "old answer")
            val notice = SystemNoticeMessageUi("notice", code)
            val messages = listOf(
                UserMessageUi("user-old", "question"), answer, notice,
                AgentMessageUi("new-answer", "retry answer", isStreaming = true), tool("new-tool"),
            )
            val rows = rows(messages)
            val active = rows.turnFooters(isStreaming = true)
            assertEquals(setOf(notice.id), active.keys)
            assertSame(if (code == SystemNoticeCode.Completed) answer else notice, active.values.single())
            assertEquals(2, rows.turnFooters().size)
        }
    }

    @Test fun retryStartingWithKnownWorkDoesNotAbsorbPreviousRunFooter() {
        val notice = SystemNoticeMessageUi("interrupted-old", SystemNoticeCode.RuntimeFailed)
        val messages = listOf(
            UserMessageUi("user-old", "question"), AgentMessageUi("assistant-old-1", "partial"), notice,
            ThinkingMessageUi("new-thinking-1", "new reasoning", true), tool("new-tool-1-call"),
        )
        val rows = rows(messages, expanded = true)
        assertEquals(mapOf(notice.id to notice), rows.turnFooters(isStreaming = true))
        assertTrue(rows.indexOfFirst { it.key == notice.id } <
            rows.indexOfFirst { it.key == "work-step:new-thinking-1" })
    }

    @Test fun knownSameRunWorkIsNotMistakenForARetryWithoutNormalization() {
        for (id in listOf("assistant-run", "assistant-run-1", "assistant-run-round-usage", "virtual-completed-run")) {
            val notice = SystemNoticeMessageUi(id, SystemNoticeCode.Stopped)
            val rows = listOf(
                AgentTimelineRow.Message(notice),
                AgentTimelineRow.WorkStep("work", tool("run-tool-1-call"), isFirst = true, isLast = true),
            )
            assertEquals(mapOf("work-step:run-tool-1-call" to notice), rows.turnFooters())
        }
    }

    @Test fun multipleUsersKeepSeparateFootersAndTheirOriginalNavigationIndices() {
        val firstAnswer = AgentMessageUi("answer-1", "first")
        val secondAnswer = AgentMessageUi("answer-2", "second")
        val messages = listOf(
            UserMessageUi("user-1", "first question"), firstAnswer, tool("one"),
            UserMessageUi("user-2", "second question"), secondAnswer, tool("two"),
        )
        val rows = rows(messages, expanded = true)
        val keys = rows.map { it.key }
        val indices = rows.lazyUserMessageIndices()
        val footers = rows.turnFooters()
        assertEquals(listOf("work-step:one", "work-step:two"), footers.keys.toList())
        assertEquals(listOf(firstAnswer, secondAnswer), footers.values.toList())
        assertEquals(keys, rows.map { it.key })
        assertEquals(indices, rows.lazyUserMessageIndices())
        assertEquals(listOf(firstAnswer), rows.turnFooters(isStreaming = true).values.toList())
        assertEquals(messages, rows.originalMessages())
    }

    @Test fun visibleSteerKeepsItsPlaceButDoesNotCloseTheReply() {
        val first = AgentMessageUi("first", "intermediate")
        val last = AgentMessageUi("last", "final answer")
        val steer = UserMessageUi("user-run-supplement-1", "extra instruction")
        val messages = listOf(UserMessageUi("user-run", "question"), first, tool("one"), steer, last, tool("two"))
        val rows = rows(messages, expanded = true)
        assertEquals(messages, rows.originalMessages())
        assertEquals(listOf(last), rows.turnFooters().values.toList())
        assertEquals("work-step:two", rows.turnFooters().keys.single())
        assertTrue(rows.turnFooters(isStreaming = true).isEmpty())
        assertEquals(2, rows.lazyUserMessageIndices().size)
    }

    @Test fun noAnswerAndNoTerminalNoticeInventsNoFooter() {
        assertTrue(emptyList<AgentTimelineRow>().turnFooters().isEmpty())
        assertTrue(rows(listOf(UserMessageUi("user", "question"), tool("tool"))).turnFooters().isEmpty())
        assertTrue(rows(listOf(AgentMessageUi("usage", "", usage = TokenUsageUi(outputTokens = 7))))
            .turnFooters().isEmpty())
    }

    @Test fun modelRetryIsNotAnActionOwnerOrAClosedTurn() {
        val answer = AgentMessageUi("answer", "partial")
        val retry = SystemNoticeMessageUi("retry", SystemNoticeCode.ModelRetry)
        val rows = rows(listOf(answer, retry, tool("tool")))
        assertTrue(rows.turnFooters(isStreaming = true).isEmpty())
        assertSame(answer, rows.turnFooters().values.single())
        assertFalse(rows.turnFooters().containsKey(retry.id))
    }

    private fun tool(id: String) = ToolActivityMessageUi(
        id = id, toolName = "terminal", status = ToolActivityStatusUi.Success,
        argumentsSummary = "full arguments", resultSummary = "full result",
    )

    private fun rows(messages: List<AgentChatMessageUi>, expanded: Boolean = false): List<AgentTimelineRow> {
        val entries = messages.toTimelineEntries()
        return entries.toLazyTimelineRows()
    }

    // Flat step rows carry their original payloads; read each original exactly once.
    private fun List<AgentTimelineRow>.originalMessages(): List<AgentChatMessageUi> = flatMap { row ->
        when (row) {
            is AgentTimelineRow.Message -> listOf(row.message)
            is AgentTimelineRow.WorkSummary -> row.group.messages
            is AgentTimelineRow.WorkStep -> listOf(row.message)
        }
    }
}
