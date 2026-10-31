package io.github.mangi.eta.agent.voice

import android.app.Application
import io.github.mangi.eta.agent.voice.tts.SpeechSpeakableText
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.config.ReplyReadAloudPreference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReplyReadAloudPolicyTest {

    @Before fun reset() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @Test fun autoReadDefaultsOffAndStreamSynthesisDefaultsOn() {
        assertFalse(ReplyReadAloudPreference.autoRead)
        assertTrue(ReplyReadAloudPreference.streamSynthesis)
    }

    @Test fun switchesRoundTripThroughLocalAgentPreferences() {
        ReplyReadAloudPreference.setAutoRead(true)
        ReplyReadAloudPreference.setStreamSynthesis(false)
        assertTrue(ReplyReadAloudPreference.autoRead)
        assertFalse(ReplyReadAloudPreference.streamSynthesis)
        // 总开关关闭不影响细分开关的持久值，重新打开后细分选择仍然保留。
        ReplyReadAloudPreference.setAutoRead(false)
        assertFalse(ReplyReadAloudPreference.autoRead)
        assertFalse(ReplyReadAloudPreference.streamSynthesis)
        ReplyReadAloudPreference.setAutoRead(true)
        assertFalse(ReplyReadAloudPreference.streamSynthesis)
    }

    @Test fun openingHistoryNeverReadsOldReplies() {
        val tracker = ReplyReadAloudTracker()
        assertTrue(tracker.reads(snapshot("old", "上一段的回复。", streaming = false)).isEmpty())
        assertFalse(tracker.armed)
    }

    @Test fun streamingReadsOnlyCommittedSentencesAndNeverRepeatsThem() {
        val tracker = ReplyReadAloudTracker()
        // 新运行开始：上一条回复不会被重读。
        assertTrue(tracker.reads(snapshot("old", "上一段的回复。", streaming = true)).isEmpty())
        assertEquals(listOf("第一句。"), tracker.reads(snapshot("m1", "第一句。第二句", streaming = true)))
        // 重复快照（丢帧合并 / 浮窗与应用同时喂同一状态）不重复朗读。
        assertTrue(tracker.reads(snapshot("m1", "第一句。第二句", streaming = true)).isEmpty())
        // 回复结束：读剩余未朗读部分。
        assertEquals(listOf("第二句"), tracker.reads(snapshot("m1", "第一句。第二句", streaming = false)))
        assertEquals("m1", tracker.finalizedSegment)
        // 结束后的重复快照（重新组合）不重读。
        assertTrue(tracker.reads(snapshot("m1", "第一句。第二句", streaming = false)).isEmpty())
        assertFalse(tracker.armed)
    }

    @Test fun streamSynthesisOffWaitsForTheWholeReply() {
        val tracker = ReplyReadAloudTracker()
        // 运行开始：基线是运行前就存在的旧回复。
        tracker.onSnapshot(snapshot("m0", "旧回复。", streaming = true), streamSynthesis = false)
        assertTrue(tracker.armed)
        // 新回复流式中不开口。
        assertTrue(tracker.reads(snapshot("m1", "第一句。第二句", streaming = true), streamSynthesis = false).isEmpty())
        // 回复全部结束后一次读完整段。
        assertEquals(
            listOf("第一句。", "第二句"),
            tracker.reads(snapshot("m1", "第一句。第二句", streaming = false), streamSynthesis = false),
        )
    }

    @Test fun turningStreamSynthesisOffMidReplyStillReadsTheUnspokenTail() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("m0", "旧回复。", streaming = true))
        tracker.reads(snapshot("m1", "第一句。第二句", streaming = true))
        // 中途关闭流式合成：不再逐句抢读，但已读过的句子不会被重复。
        assertTrue(
            tracker.reads(snapshot("m1", "第一句。第二句", streaming = true), streamSynthesis = false).isEmpty(),
        )
        assertEquals(
            listOf("第二句"),
            tracker.reads(snapshot("m1", "第一句。第二句", streaming = false), streamSynthesis = false),
        )
    }

    @Test fun nextRunOnlyReadsTheNewReply() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("m0", "旧回复。", streaming = true))
        tracker.reads(snapshot("m1", "第一句。第二句", streaming = true))
        tracker.reads(snapshot("m1", "第一句。第二句", streaming = false))
        // 新一轮运行开始：流式期间仍盯着上一条回复，不重读。
        assertTrue(tracker.reads(snapshot("m1", "第一句。第二句", streaming = true)).isEmpty())
        assertEquals(listOf("新的回复。"), tracker.reads(snapshot("m2", "新的回复。", streaming = true)))
    }

    @Test fun switchingConversationsAfterAFinishedRunDoesNotRead() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("m0", "旧回复。", streaming = true))
        tracker.reads(snapshot("m1", "第一句。", streaming = true))
        tracker.reads(snapshot("m1", "第一句。", streaming = false))
        assertFalse(tracker.armed)
        // 切换到另一段会话：最后一条是别人的历史回复，不读。
        assertTrue(tracker.reads(snapshot("other", "另一段会话的回复。", streaming = false)).isEmpty())
        assertFalse(tracker.armed)
    }

    @Test fun emptyReplyDisarmsAndNeverLeaksIntoTheNextConversation() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("m0", "旧回复。", streaming = true))
        tracker.reads(snapshot("m1", "", streaming = true))
        assertTrue(tracker.armed)
        assertTrue(tracker.reads(snapshot("m1", "", streaming = false)).isEmpty())
        assertEquals("m1", tracker.finalizedSegment)
        assertFalse(tracker.armed)
        assertTrue(tracker.reads(snapshot("other", "别处的回复。", streaming = false)).isEmpty())
    }

    @Test fun overLongReplyIsNotAutoReadAndIsMarkedFinalized() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("m0", "旧回复。", streaming = true))
        tracker.reads(snapshot("m1", "开头。", streaming = true))
        val tooLong = "啊".repeat(SpeechSpeakableText.MAX_SOURCE_CHARS + 1)
        assertTrue(tracker.reads(snapshot("m1", tooLong, streaming = false)).isEmpty())
        assertEquals("m1", tracker.finalizedSegment)
    }

    @Test fun resetClearsEveryPieceOfProgress() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("m1", "第一句。", streaming = true))
        tracker.reset()
        assertFalse(tracker.armed)
        assertNull(tracker.baselineSegment)
        assertNull(tracker.currentId)
        assertEquals(0, tracker.spokenCount)
        assertNull(tracker.finalizedSegment)
        assertNull(tracker.conversationId)
        assertNull(tracker.runId)
    }

    @Test fun runStartTreatsThePreRunReplyAsBaseline() {
        val tracker = ReplyReadAloudTracker()
        // 已有回复的会话里发新消息：运行刚开始时最后一条还是旧回复，不能读。
        val oldReply = snapshot("m1", "旧回复。", streaming = true)
        assertTrue(tracker.reads(oldReply).isEmpty())
        assertEquals("m1", tracker.baselineSegment)
        // 基线在流式期间持续被跳过，直到真正的新回复出现。
        assertTrue(tracker.reads(oldReply).isEmpty())
        assertEquals(listOf("新回复。"), tracker.reads(snapshot("m2", "新回复。", streaming = true)))
    }

    @Test fun userStopMarksTheCurrentReplyFinalized() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("m0", "旧回复。", streaming = true))
        tracker.reads(snapshot("m1", "第一句。", streaming = true))
        // 用户点朗读按钮停止：当前回复立刻定稿，流式继续推进也不再读出剩余句子。
        tracker.markCurrentFinalized()
        assertEquals("m1", tracker.finalizedSegment)
        assertTrue(tracker.reads(snapshot("m1", "第一句。第二句", streaming = true)).isEmpty())
        assertTrue(tracker.reads(snapshot("m1", "第一句。第二句", streaming = false)).isEmpty())
        // 下一轮新回复不受影响：新运行开始时第一条快照仍盯着上一条回复（记为基线），
        // 随后新回复出现，照常自动朗读。
        assertTrue(tracker.reads(snapshot("m1", "第一句。第二句", streaming = true)).isEmpty())
        assertEquals(listOf("新回复。"), tracker.reads(snapshot("m2", "新回复。", streaming = true)))
    }

    @Test fun userStopWithoutCurrentReplyIsANoOp() {
        val tracker = ReplyReadAloudTracker()
        // 还没有任何回复在处理时点停止（比如刚切进会话），不能把历史回复定稿。
        tracker.markCurrentFinalized()
        assertNull(tracker.finalizedSegment)
    }

    @Test fun userStopSilencesEveryLaterSegmentOfTheSameRun() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("assistant-r0-1-0", "上一轮回复。", streaming = true, runId = "r1"))
        tracker.reads(snapshot("assistant-r1-1-0", "第一句。", streaming = true, runId = "r1"))
        tracker.markCurrentFinalized()
        // 本轮剩下的新段（工具调用之后的第二段）同样静默，而不是换个气泡又开口。
        val plan = tracker.plan(snapshot("assistant-r1-2-0", "第二段第一句。", streaming = true, runId = "r1"))
        assertTrue(plan.utterances.isEmpty())
        assertTrue(plan.stop)
        // 本轮结束也不补读尾巴。
        assertTrue(
            tracker.reads(snapshot("assistant-r1-2-0", "第二段第一句。第二段第二句。", streaming = false)).isEmpty(),
        )
        // 下一轮恢复正常。
        tracker.reads(snapshot("assistant-r1-2-0", "第二段。", streaming = true, runId = "r2"))
        assertEquals(
            listOf("新轮回复。"),
            tracker.reads(snapshot("assistant-r2-1-0", "新轮回复。", streaming = true, runId = "r2")),
        )
    }

    @Test fun leavingAnActiveConversationStopsAndFlushes() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("old", "旧回复。", streaming = true, conversationId = "A", runId = "r1"))
        val first = tracker.plan(snapshot("m1", "第一句。", streaming = true, conversationId = "A", runId = "r1"))
        assertEquals(listOf("第一句。"), first.utterances.map { it.text })
        val switched = tracker.plan(snapshot("b1", "目标会话的历史回复。", streaming = false, conversationId = "B"))
        assertTrue(switched.stop)
        assertTrue(switched.flush)
        assertTrue(switched.utterances.isEmpty())
        assertFalse(tracker.armed)
        // 离开时那一轮被就地放弃，标记为已读完。
        assertEquals("m1", tracker.finalizedSegment)
    }

    @Test fun enteringAnIdleConversationNeverReadsItsHistory() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("old", "旧回复。", streaming = true, conversationId = "A", runId = "r1"))
        tracker.reads(snapshot("m1", "第一句。", streaming = true, conversationId = "A", runId = "r1"))
        // 第一份 B 快照：收声并放弃 A。
        tracker.plan(snapshot("b1", "B 的历史回复。", streaming = false, conversationId = "B"))
        // 第二份 B 快照：认领新会话，历史回复依旧不读。
        val second = tracker.plan(snapshot("b2", "B 的另一条历史回复。", streaming = false, conversationId = "B"))
        assertTrue(second.utterances.isEmpty())
        assertEquals("B", tracker.conversationId)
        assertFalse(tracker.armed)
    }

    @Test fun switchingToAConversationThatIsStillGeneratingStaysSilent() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("old", "旧回复。", streaming = true, conversationId = "A", runId = "r1"))
        tracker.reads(snapshot("m1", "第一句。", streaming = true, conversationId = "A", runId = "r1"))
        tracker.plan(snapshot("b1", "B 的回复。", streaming = false, conversationId = "B"))
        // 切到一个正在后台生成的会话：中途接入，整轮不朗读（含已有的半截与结束后的尾巴）。
        val joined = tracker.plan(
            snapshot("assistant-r2-1-0", "B 已经生成了一半。", streaming = true, conversationId = "B", runId = "r2"),
        )
        assertTrue(joined.utterances.isEmpty())
        assertTrue(
            tracker.reads(
                snapshot("assistant-r2-1-0", "B 已经生成了一半。继续生成。", streaming = true, conversationId = "B", runId = "r2"),
            ).isEmpty(),
        )
        assertTrue(
            tracker.reads(
                snapshot("assistant-r2-1-1", "B 这一轮的结尾。", streaming = false, conversationId = "B"),
            ).isEmpty(),
        )
    }

    @Test fun returningToTheAbandonedRunNeitherResumesNorReadsTheTail() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("assistant-r0-1-0", "上一轮回复。", streaming = true, conversationId = "A", runId = "r1"))
        tracker.reads(snapshot("assistant-r1-1-0", "第一句。", streaming = true, conversationId = "A", runId = "r1"))
        // 浮窗接管：A 的这一轮被放弃，但会话标识仍指向 A（浮窗只来了一份快照）。
        tracker.plan(snapshot("b1", "浮窗的回复。", streaming = false, conversationId = "B"))
        assertEquals("A", tracker.conversationId)
        // 关掉浮窗回到 A：剩余句子不续读。
        assertTrue(
            tracker.reads(snapshot("assistant-r1-1-0", "第一句。第二句。", streaming = true, conversationId = "A", runId = "r1")).isEmpty(),
        )
        // 这一轮结束时的尾巴也不补读。
        val tail = tracker.plan(snapshot("assistant-r1-1-1", "第二句。第三句。", streaming = false, conversationId = "A"))
        assertTrue(tail.utterances.isEmpty())
        // 下一轮照常。
        tracker.reads(snapshot("assistant-r1-1-1", "第二句。第三句。", streaming = true, conversationId = "A", runId = "r2"))
        assertEquals(
            listOf("新轮回复。"),
            tracker.reads(snapshot("assistant-r2-1-0", "新轮回复。", streaming = true, conversationId = "A", runId = "r2")),
        )
    }

    @Test fun draftConversationAdoptingTheRealIdKeepsReadingProgress() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("old", "旧回复。", streaming = true))
        assertEquals(listOf("第一句。"), tracker.reads(snapshot("m1", "第一句。", streaming = true)))
        // 草稿会话在首条回复发出后才拿到真实 id：认领新标识，但已读句数不清零。
        assertEquals(
            listOf("第二句。"),
            tracker.reads(snapshot("m1", "第一句。第二句。", streaming = true, conversationId = "c-1")),
        )
        assertEquals("c-1", tracker.conversationId)
    }

    @Test fun newSegmentFlushesThePreviousOneAndJumpsToTheLatest() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("assistant-r0-1-0", "上一轮回复。", streaming = true, runId = "r1"))
        val first = tracker.plan(snapshot("assistant-r1-1-0", "第一句。第二句", streaming = true, runId = "r1"))
        assertEquals(listOf("第一句。"), first.utterances.map { it.text })
        assertFalse(first.flush)
        // 工具调用之后模型另起一段：上一段没读的句子整批作废，直接读新段。
        val second = tracker.plan(snapshot("assistant-r1-2-0", "新段第一句。", streaming = true, runId = "r1"))
        assertTrue(second.segmentChanged)
        assertTrue(second.flush)
        assertEquals(listOf("新段第一句。"), second.utterances.map { it.text })
        assertEquals("assistant-r1-2-0", second.messageId)
        // 新段继续推进时不再换段，也不会回头读旧段。
        val third = tracker.plan(snapshot("assistant-r1-2-0", "新段第一句。第二句。", streaming = true, runId = "r1"))
        assertFalse(third.segmentChanged)
        assertEquals(listOf("第二句。"), third.utterances.map { it.text })
    }

    @Test fun reconnectSuffixKeepsTheSegmentIdentity() {
        val tracker = ReplyReadAloudTracker()
        tracker.reads(snapshot("assistant-r0-1-0", "上一轮回复。", streaming = true, runId = "r1"))
        tracker.reads(snapshot("assistant-r1-1-0", "第一句。", streaming = true, runId = "r1"))
        // 断流续接会给同一条回复换 id：归一化后仍算同一段，已读句子不重读。
        val plan = tracker.plan(
            snapshot("assistant-r1-1-0-reconnect-3:abc", "第一句。第二句。", streaming = true, runId = "r1"),
        )
        assertFalse(plan.segmentChanged)
        assertEquals(listOf("第二句。"), plan.utterances.map { it.text })
        assertEquals("assistant-r1-1-0-reconnect-3:abc", plan.messageId)
    }

    @Test fun identicalSnapshotSkipsTheSentenceParser() {
        var parses = 0
        val tracker = ReplyReadAloudTracker(sentencesOf = { _, _ -> parses++; emptyList() })
        tracker.reads(snapshot("assistant-r0-1-0", "上一轮回复。", streaming = true, runId = "r1"))
        val reply = snapshot("assistant-r1-1-0", "第一句。", streaming = true, runId = "r1")
        tracker.onSnapshot(reply, streamSynthesis = true)
        tracker.onSnapshot(reply, streamSynthesis = true)
        assertEquals(1, parses)
        assertEquals(1, tracker.sentenceParseCount)
    }

    @Test fun blankAndOverLongTextNeverInvokeTheParser() {
        var parses = 0
        val tracker = ReplyReadAloudTracker(sentencesOf = { _, _ -> parses++; emptyList() })
        tracker.reads(snapshot("old", "旧回复。", streaming = true))
        tracker.reads(snapshot("m1", "", streaming = true))
        tracker.reads(snapshot("m1", "啊".repeat(SpeechSpeakableText.MAX_SOURCE_CHARS + 1), streaming = true))
        assertEquals(0, parses)
    }

    /** 真实分句器下取「本批要读的句子」，与生产路径一致。 */
    private fun ReplyReadAloudTracker.reads(
        snapshot: VoiceChatSnapshot,
        streamSynthesis: Boolean = true,
    ): List<String> = plan(snapshot, streamSynthesis).utterances.map { it.text }

    private fun ReplyReadAloudTracker.plan(
        snapshot: VoiceChatSnapshot,
        streamSynthesis: Boolean = true,
    ): ReadAloudPlan = onSnapshot(snapshot, streamSynthesis)

    private fun snapshot(
        id: String?,
        text: String,
        streaming: Boolean,
        conversationId: String? = null,
        runId: String? = null,
    ) = VoiceChatSnapshot(
        isStreaming = streaming,
        lastAgentId = id,
        lastAgentText = text,
        conversationId = conversationId,
        runId = runId,
    )
}
