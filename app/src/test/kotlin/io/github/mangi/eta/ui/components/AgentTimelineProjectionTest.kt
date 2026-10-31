package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionOption
import io.github.mangi.eta.agent.question.AgentQuestionRequest
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.ConversationMention
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.canContinuePausedGeneration
import io.github.mangi.eta.ui.model.stoppedDuringModelRetry
import io.github.mangi.eta.ui.model.canContinueDisconnectedRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTimelineProjectionTest {
    @Test
    fun consecutiveWorkIsBoundedWithoutEmptyGroupsOrLostMessages() {
        for (count in listOf(0, 1, 31, 32, 33, 64, 65, 1_000)) {
            val messages = List(count, ::workMessage)
            val entries = messages.toTimelineEntries()
            val groups = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()

            assertEquals(entries.size, groups.size)
            assertEquals(messages.chunked(32).map { it.size }, groups.map { it.messages.size })
            assertTrue(groups.all { it.messages.size in 1..32 })
            assertEquals(messages, entries.flattenMessages())
            assertEquals(entries.size, entries.map { it.key }.toSet().size)
            groups.forEach { group ->
                assertEquals("work-${group.messages.first().id}", group.key)
            }
            messages.zip(entries.flattenMessages()).forEach { (original, projected) ->
                assertSame(original, projected)
            }
        }
    }

    @Test
    fun initialTailIndexUsesProjectedRowsAndAddsAtMostOneCompressionFooter() {
        val retry = SystemNoticeMessageUi("retry", SystemNoticeCode.ModelRetry)
        val hiddenResume = UserMessageUi("user-supplement-resume", "hidden resume")
        val entries = listOf(
            UserMessageUi("user", "task"),
            workMessage(0),
            retry,
            hiddenResume,
        ).toTimelineEntries()

        // The initial anchor follows rendered timeline rows, not raw messages:
        // retry and resume records do not create LazyColumn items.
        assertEquals(2, entries.size)
        assertEquals(2, initialTimelineItemIndex(entries, false, false, false))
        assertEquals(3, initialTimelineItemIndex(entries, true, false, false))
        assertEquals(3, initialTimelineItemIndex(entries, false, true, false))
        assertEquals(3, initialTimelineItemIndex(entries, false, false, true))
        assertEquals(3, initialTimelineItemIndex(entries, true, true, true))
    }

    @Test
    fun appendAndStreamingUpdatesKeepExistingGroupKeysAndProjectionSnapshots() {
        val messages = List(97, ::workMessage)
        var previous = emptyList<AgentTimelineEntry>()
        for (count in listOf(1, 31, 32, 33, 63, 64, 65, 96, 97)) {
            val previousMessages = previous.flattenMessages()
            val entries = messages.take(count).toTimelineEntries()

            assertEquals(previous.map { it.key }, entries.take(previous.size).map { it.key })
            assertEquals(previousMessages, previous.flattenMessages())
            assertEquals(messages.take(count), entries.flattenMessages())
            previous = entries
        }

        val updatedMessages = messages.map { message ->
            when (message) {
                is ThinkingMessageUi -> message.copy(content = message.content + " appended", isStreaming = false)
                is ToolActivityMessageUi -> message.copy(status = ToolActivityStatusUi.Success, resultSummary = "done")
                else -> message
            }
        }
        val updated = updatedMessages.toTimelineEntries()
        assertEquals(previous.map { it.key }, updated.map { it.key })
        assertEquals(messages, previous.flattenMessages())
        assertEquals(updatedMessages, updated.flattenMessages())
    }

    @Test
    fun batchesRespectMessageBoundariesAndPreserveFullTranscriptAndNavigation() {
        val firstRun = List(65, ::workMessage)
        val secondRun = List(33) { workMessage(65 + it) }
        val resume = UserMessageUi("user-1-supplement-resume", "hidden resume")
        val messages = buildList<AgentChatMessageUi> {
            add(UserMessageUi("user-1", "first task"))
            addAll(firstRun.take(31))
            add(resume)
            addAll(firstRun.drop(31))
            add(AgentMessageUi("answer-1", "intermediate answer"))
            addAll(secondRun)
            add(SystemNoticeMessageUi("notice", SystemNoticeCode.Stopped))
            add(workMessage(98))
            add(ContextCompactedMessageUi("compacted", 1, "saved summary"))
            add(workMessage(99))
            add(UserMessageUi("user-2", "second task"))
            add(AgentMessageUi("answer-2", "final answer"))
        }
        val originalMessages = messages.toList()
        val originalTranscript = ConversationMention.transcript(messages)

        val entries = messages.toTimelineEntries()
        val projectedMessages = entries.flattenMessages()

        assertEquals(
            listOf(
                "user-1", "work-step-0", "work-step-32", "work-step-64", "answer-1",
                "work-step-65", "work-step-97", "notice", "work-step-98", "compacted",
                "work-step-99", "user-2", "answer-2",
            ),
            entries.map { it.key },
        )
        assertEquals(listOf(32, 32, 1, 32, 1, 1, 1),
            entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().map { it.messages.size })
        // Keep the existing hidden-resume policy; pagination must not drop anything else.
        assertEquals(messages.filterNot { it === resume }, projectedMessages)
        assertEquals(originalMessages, messages)
        assertEquals(listOf(0, 11), entries.userMessageIndices())
        assertEquals(originalTranscript, ConversationMention.transcript(projectedMessages))
        assertEquals(originalTranscript, ConversationMention.transcript(messages))
        assertFalse(originalTranscript.contains(ConversationMention.OMISSION_MARKER))
        for (index in 0..99) {
            assertTrue("Missing work step $index from transcript", originalTranscript.contains("payload-$index-end"))
        }
    }

    @Test
    fun modelRetryStaysInHistoryButNotInTheChatTimeline() {
        val retry = SystemNoticeMessageUi("retry", SystemNoticeCode.ModelRetry, "retry detail")
        val stopped = SystemNoticeMessageUi("stopped", SystemNoticeCode.Stopped)
        val messages = listOf(
            UserMessageUi("user-run", "task"),
            workMessage(0),
            retry,
            workMessage(1),
            stopped,
        )

        val entries = messages.toTimelineEntries()
        assertEquals(listOf("user-run", "work-step-0", "stopped"), entries.map { it.key })
        assertEquals(listOf(workMessage(0), workMessage(1)),
            (entries[1] as AgentTimelineEntry.WorkProcess).messages)
        assertEquals(messages.filterNot { it == retry }, entries.flattenMessages())
        assertEquals(listOf(messages[0], workMessage(0), retry, workMessage(1), stopped), messages)
        assertTrue(messages.stoppedDuringModelRetry())
        assertTrue(canContinuePausedGeneration(messages))
        assertTrue(canContinueDisconnectedRun(messages))
        assertTrue(listOf(retry).toTimelineEntries().isEmpty())
    }

    @Test
    fun reorderedTerminalWorkStillUses32MessageGroupsAndFiltersHiddenRecords() {
        val runId = "late-work"
        val user = UserMessageUi("user-$runId", "task")
        val notice = SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped, "detail")
        val work = List(65) { index ->
            ThinkingMessageUi("$runId-thinking-$index-0", "payload $index", false)
        }
        val retry = SystemNoticeMessageUi("assistant-$runId-1-usage", SystemNoticeCode.ModelRetry, "retry")
        val resume = UserMessageUi("user-$runId-supplement-resume", "hidden resume")
        val input = listOf(user, notice) + work.take(31) + listOf(retry, resume) + work.drop(31)

        val entries = input.toTimelineEntries()
        val groups = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()
        assertEquals(listOf(32, 32, 1), groups.map { it.messages.size })
        assertEquals(listOf(user.id, "work-${work[0].id}", "work-${work[32].id}",
            "work-${work[64].id}", notice.id), entries.map { it.key })
        assertEquals(listOf(user) + work + notice, entries.flattenMessages())
        assertEquals((listOf(user) + work + notice).toTimelineEntries(), entries)
        work.zip(groups.flatMap { it.messages }).forEach { (original, projected) ->
            assertSame(original, projected)
        }
        assertSame(notice, input[1])
    }

    @Test
    fun hidingRetryPreservesOtherNoticesAndBoundedWorkGroups() {
        val notices = SystemNoticeCode.entries.filterNot { it == SystemNoticeCode.ModelRetry }
            .map { SystemNoticeMessageUi("notice-${it.wireValue}", it, "detail") }
        val work = List(65, ::workMessage)
        val retry = SystemNoticeMessageUi("retry", SystemNoticeCode.ModelRetry)
        val messages = work.take(31) + retry + work.drop(31) + notices
        val entries = messages.toTimelineEntries()
        assertEquals(listOf(32, 32, 1),
            entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().map { it.messages.size })
        assertEquals(work + notices, entries.flattenMessages())
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
        val rows = entries.toLazyTimelineRows()
        assertFalse(rows.any { it is AgentTimelineRow.Message && it.message == retry })
    }

    @Test
    fun textReplyInsideWorkGroupBreaksContinuousSegmentRun() {
        val group = AgentTimelineEntry.WorkProcess(
            key = "work-turn",
            messages = listOf(
                workMessage(1),                                    // 工具
                AgentMessageUi("mid", "中间回复"),                  // 纯文本：列表在此断开
                workMessage(0),                                    // 思考
                workMessage(1),                                    // 工具
            ),
        )
        val steps = listOf(group).toLazyTimelineRows()
            .filterIsInstance<AgentTimelineRow.WorkStep>()
        // 文本前的工具组末收外圆；文本后重新起段。
        assertEquals(listOf(true, false, true, false), steps.map { it.isFirst })
        assertEquals(listOf(true, false, false, true), steps.map { it.isLast })

        // 折叠组展开后：摘要与首个工具同段（首步不担组首），文本处再断、之后重新起段。
        val folded = listOf<AgentTimelineEntry>(group).toLazyTimelineRows(
            foldedGroups = setOf(group.key),
            expandedGroups = setOf(group.key),
        )
        assertTrue((folded.first() as AgentTimelineRow.WorkSummary).expanded)
        assertTrue(group.summaryContinuesIntoSteps())
        val foldedSteps = folded.filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(listOf(false, false, true, false), foldedSteps.map { it.isFirst })
        assertEquals(listOf(true, false, false, true), foldedSteps.map { it.isLast })

        // 空文本不算列表成员、不断开；首步是纯文本时摘要不接进列表。
        val headBreak = AgentTimelineEntry.WorkProcess(
            key = "work-head",
            messages = listOf(
                AgentMessageUi("lead", "引子"),
                workMessage(1),
                AgentMessageUi("blank", "   "),
                workMessage(0),
            ),
        )
        assertFalse(headBreak.summaryContinuesIntoSteps())
        val headSteps = listOf(headBreak).toLazyTimelineRows()
            .filterIsInstance<AgentTimelineRow.WorkStep>()
        assertEquals(listOf(true, true, false, false), headSteps.map { it.isFirst })
        assertEquals(listOf(false, false, false, true), headSteps.map { it.isLast })
    }

    @Test
    fun foldedBatchedWorkGroupsRenderOneSummary() {
        // 超过单批上限的一轮会被切成多个相邻工作组；折叠后它们是同一张卡片，
        // 只应留一条「已完成 N 个步骤」摘要，而不是每批一条。
        val messages = List(65, ::workMessage)
        val entries = messages.toTimelineEntries()
        assertEquals(listOf(32, 32, 1),
            entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().map { it.messages.size })
        val foldedKeys = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>()
            .mapTo(linkedSetOf()) { it.key }

        val rows = entries.toLazyTimelineRows(foldedGroups = foldedKeys)
        assertEquals(1, rows.count { it is AgentTimelineRow.WorkSummary })
        val summary = rows.single() as AgentTimelineRow.WorkSummary
        assertEquals(65, summary.group.messages.size)
        assertFalse(summary.expanded)

        // 展开仍是同一张摘要 + 整轮全部步骤。
        val expandedRows = entries.toLazyTimelineRows(
            foldedGroups = foldedKeys,
            expandedGroups = setOf(summary.key),
        )
        assertEquals(1, expandedRows.count { it is AgentTimelineRow.WorkSummary })
        assertEquals(65, expandedRows.count { it is AgentTimelineRow.WorkStep })
    }

    @Test
    fun autoFoldKeepsOlderTurnsFoldedWhileGeneratingAndFoldsLatestTurnOnlyWhenSettled() {
        val olderTurn = listOf(
            UserMessageUi("user-1", "first task"),
            workMessage(0),
            workMessage(1),
            AgentMessageUi("answer-1", "first answer"),
        )
        val latestTurn = listOf(
            UserMessageUi("user-2", "second task"),
            workMessage(2),
            workMessage(3),
            AgentMessageUi("answer-2", "second answer"),
        )
        val entries = (olderTurn + latestTurn).toTimelineEntries()
        // 连续工作消息并成同组，组键取首条消息 id。
        val olderKeys = setOf("work-step-0")
        val latestKeys = setOf("work-step-2")

        // 生成中（foldLatestTurn=false）：旧轮保持收起，最新一轮摊开看进度。
        assertEquals(olderKeys, entries.autoFoldedWorkKeys(enabled = true, foldLatestTurn = false))
        // 整轮停下：最新一轮一并收起。
        assertEquals(olderKeys + latestKeys, entries.autoFoldedWorkKeys(enabled = true, foldLatestTurn = true))
        // 不折叠档：完全不收。
        assertTrue(entries.autoFoldedWorkKeys(enabled = false, foldLatestTurn = true).isEmpty())
    }

    @Test
    fun autoFoldTreatsSteerSupplementAsSameTurnAndUnansweredTurnStaysOpen() {
        // steer 补充不算新轮：它之后的工作组仍属于最新一轮，生成中不收。
        val withSteer = listOf(
            UserMessageUi("user-1", "task"),
            workMessage(0),
            AgentMessageUi("answer-1", "answer"),
            UserMessageUi("user-1-supplement-steer", "补充一点"),
            workMessage(1),
            AgentMessageUi("answer-2", "second answer"),
        ).toTimelineEntries()
        // steer 补充不算新轮：它之前的工作组也属于最新一轮，生成中同样不收
        // （若 steer 开了新轮，这里会收到 work-step-0）。
        assertEquals(emptySet<String>(), withSteer.autoFoldedWorkKeys(true, foldLatestTurn = false))
        assertEquals(setOf("work-step-0", "work-step-1"), withSteer.autoFoldedWorkKeys(true, foldLatestTurn = true))

        // 没有正文回复的整轮不收（停下也不收）；有回复的轮正常收。
        val unanswered = listOf(
            UserMessageUi("user-1", "task"),
            workMessage(0),
            UserMessageUi("user-2", "next task"),
            workMessage(1),
            AgentMessageUi("answer-2", "second answer"),
        ).toTimelineEntries()
        val folded = unanswered.autoFoldedWorkKeys(true, foldLatestTurn = true)
        assertFalse("无回复的整轮不该被收起", folded.contains("work-step-0"))
        assertTrue("有回复的轮该被收起", folded.contains("work-step-1"))

        // 没有任何真实用户消息时整表视为最新一轮：生成中不收，停下后收已出现回复之前的组。
        val headless = listOf(
            workMessage(0),
            AgentMessageUi("answer-1", "answer"),
            workMessage(1),
        ).toTimelineEntries()
        assertTrue(headless.autoFoldedWorkKeys(true, foldLatestTurn = false).isEmpty())
        assertEquals(setOf("work-step-0"), headless.autoFoldedWorkKeys(true, foldLatestTurn = true))
    }

    @Test
    fun questionCardIsAWorkProcessMemberInEveryFoldLevel() {
        // 提问卡（无论等待还是已回答）并入工作组：上方紧挨工具/思考步骤时圆角按
        // 分段列表相接规则取内圆，不再单独成卡夹在前后两张卡片之间。
        val turn = listOf(
            UserMessageUi("user-1", "task"),
            workMessage(0),
            questionMessage("q-1", AgentQuestionStatus.Waiting),
            workMessage(1),
        )
        for (aggressive in listOf(false, true)) {
            val entries = turn.toTimelineEntries(aggressive)
            // 用户气泡一条 + 工作组一条（三步同组）。
            assertEquals(2, entries.size)
            val group = entries.filterIsInstance<AgentTimelineEntry.WorkProcess>().single()
            assertEquals(listOf("step-0", "q-1", "step-1"), group.messages.map { it.id })
        }

        // 组首/组末也能是提问卡：独立成卡（Whole 圆角）由 toLazyTimelineRows 的位置标记负责。
        val questionOnly = listOf(UserMessageUi("user-1", "task"), questionMessage("q-2", AgentQuestionStatus.Answered))
        val group = questionOnly.toTimelineEntries().filterIsInstance<AgentTimelineEntry.WorkProcess>().single()
        assertEquals(listOf("q-2"), group.messages.map { it.id })
    }

    @Test
    fun autoFoldNeverCollapsesAGroupStillHoldingAWaitingQuestion() {
        // 等待作答的提问卡必须留在卡片外可见：含 Waiting 提问的组即使整轮已停下也不折叠；
        // 一旦作答，同一张表恢复按轮收起。
        val waiting = listOf(
            UserMessageUi("user-1", "task"),
            workMessage(0),
            questionMessage("q-1", AgentQuestionStatus.Waiting),
            AgentMessageUi("answer-1", "answer"),
        ).toTimelineEntries()
        val groupKey = waiting.filterIsInstance<AgentTimelineEntry.WorkProcess>().single().key
        assertFalse(waiting.autoFoldedWorkKeys(true, foldLatestTurn = true).contains(groupKey))

        val answered = listOf(
            UserMessageUi("user-1", "task"),
            workMessage(0),
            questionMessage("q-1", AgentQuestionStatus.Answered),
            AgentMessageUi("answer-1", "answer"),
        ).toTimelineEntries()
        assertEquals(setOf(groupKey), answered.autoFoldedWorkKeys(true, foldLatestTurn = true))

        // 旧轮里还挂着 Waiting 提问时同样不收（恢复/停跑等极端时序的兜底）。
        val olderTurnWaiting = listOf(
            UserMessageUi("user-1", "task"),
            workMessage(0),
            questionMessage("q-1", AgentQuestionStatus.Waiting),
            AgentMessageUi("answer-1", "answer"),
            UserMessageUi("user-2", "next task"),
            workMessage(1),
            AgentMessageUi("answer-2", "second answer"),
        ).toTimelineEntries()
        val folded = olderTurnWaiting.autoFoldedWorkKeys(true, foldLatestTurn = true)
        assertFalse(folded.contains("work-step-0"))
        assertTrue(folded.contains("work-step-1"))
    }

    private fun questionMessage(id: String, status: AgentQuestionStatus): AgentChatMessageUi =
        AgentQuestionMessageUi(
            id,
            AgentQuestionRequest(
                questionId = "question-$id", conversationId = "conversation", runId = "run", toolCallId = "call-$id",
                title = "Decision title", question = "Choose the next step.",
                options = listOf(AgentQuestionOption("a", "First", ""), AgentQuestionOption("b", "Second", "")),
            ),
            status = status,
            answer = if (status == AgentQuestionStatus.Answered) AgentQuestionAnswer("option", "a") else null,
        )

    private fun workMessage(index: Int): AgentChatMessageUi = when (index % 3) {
        0 -> ThinkingMessageUi(
            id = "step-$index",
            content = "payload-$index-end",
            isStreaming = true,
        )
        1 -> ToolActivityMessageUi(
            id = "step-$index",
            toolName = "read_file",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = "payload-$index-end",
            command = "cat step-$index.txt",
            resultSummary = "result-$index-end",
        )
        else -> ToolSummaryMessageUi(
            id = "step-$index",
            tools = listOf("payload-$index-end"),
        )
    }

    private fun List<AgentTimelineEntry>.flattenMessages(): List<AgentChatMessageUi> = flatMap { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> listOf(entry.message)
            is AgentTimelineEntry.WorkProcess -> entry.messages
        }
    }
}
