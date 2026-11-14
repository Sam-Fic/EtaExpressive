"""Read-only live branch wiring: opening branch must not cancel the source run."""
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[4] / "app/src/main/kotlin/io/github/mangi/eta"


class RunningBranchSnapshotContract(unittest.TestCase):
    def text(self, path):
        return (SRC / path).read_text()

    def test_only_branch_opts_in_to_running_source(self):
        app = self.text("ui/app/AgentAppState.kt")
        self.assertIn("launchConversationRevision(messageId, allowActiveSource = true)", app)
        self.assertIn("allowActiveSource: Boolean = false", app)
        body = app.split("fun branchConversation(messageId:", 1)[1].split("private fun publishPreparedBranch", 1)[0]
        for mutation in ("stopRun(", "cancelRun(", ".pause(", ".resume("):
            self.assertNotIn(mutation, body)
        self.assertIn("(runningBranch || (homeState == snapshot", app)
        self.assertIn("branchHistorySnapshotLoader(conversationId, branchBoundary.runId, branchBoundary.snapshotId)", app)

    def test_unfinished_reply_guard_precedes_the_branch_transaction(self):
        app = self.text("ui/app/AgentAppState.kt")
        branch = app.split("fun branchConversation(messageId:", 1)[1].split("private fun publishPreparedBranch", 1)[0]
        self.assertLess(branch.index("if (isUnfinishedAssistantBranchTarget(messageId)) return"),
                        branch.index("launchConversationRevision(messageId, allowActiveSource = true)"))
        guard = app.split("private fun isUnfinishedAssistantBranchTarget", 1)[1].split("fun branchConversation", 1)[0]
        self.assertIn("if (target.isStreaming) return true", guard)
        self.assertIn("conversation == owner && run in runJobs", guard)
        self.assertIn("!homeState.isStreaming && !homeState.isPaused && activeRuns.isEmpty()", guard)
        self.assertIn("target.id.substringAfterLast(':')", guard)
        self.assertIn('id == "assistant-$run" || id.startsWith("assistant-$run-")', guard)
        self.assertNotIn("isTextForRound", guard)

    def test_query_is_owned_correlated_read_only_and_explicitly_released(self):
        service = self.text("agent/runtime/AgentRuntimeService.kt")
        query = service.split("AgentRuntimeWire.MSG_QUERY_HISTORY ->", 1)[1].split("AgentRuntimeWire.MSG_QUERY_QUESTION ->", 1)[0]
        for check in ("owner == conversationId", "sessions.contains(it)", "historySnapshot(snapshotId)",
                      "pendingHistorySnapshots.putIfAbsent", "pendingHistorySnapshots.remove(queryId, transfer)"):
            self.assertIn(check, query)
        for forbidden in ("attachRun(", "requestStop(", "signalStop(", "sealTerminal(", "drainCompleted"):
            self.assertNotIn(forbidden, query)
        client = self.text("agent/runtime/AgentRuntimeClient.kt")
        query = client.split("fun queryHistory(", 1)[1].split("fun ackResult", 1)[0]
        for correlation in ("== runId", "== snapshotId", "== conversationId", "== queryId"):
            self.assertIn(correlation, query)
        self.assertIn("MSG_RELEASE_HISTORY", query)
        self.assertIn("release()", query)
        self.assertIn("Looper.myLooper() == Looper.getMainLooper()", query)

    def test_snapshot_is_frozen_bounded_and_exact_not_truncated(self):
        session = self.text("agent/runtime/AgentRuntimeSession.kt")
        self.assertIn("Collections.unmodifiableList(ArrayList(history))", session)
        self.assertIn("historySnapshots.size > 2", session)
        self.assertIn("state != State.RUNNING || stopSignalled", session)
        self.assertIn("historySnapshots.clear()", session)
        codec = self.text("agent/model/AgentConversationCodec.kt")
        encoder = codec.split("fun encodeHistorySnapshot", 1)[1].split("fun decodeHistorySnapshot", 1)[0]
        self.assertNotIn("encodeBounded", encoder)
        self.assertNotIn("take(", encoder)

    def test_retry_suffix_is_owned_before_sanitization(self):
        loop = self.text("agent/model/AgentLoop.kt")
        hook = loop.split("onAttemptStarted =", 1)[1].split("onProviderEvent =", 1)[0]
        self.assertLess(hook.index("message.put(AgentTurnIdentity.JSON_KEY, turnId)"),
                        hook.index("transcript(suffixMessages"))
        self.assertIn("transcript(messages, systemCount, sensitiveToolCallIds)", hook)
        self.assertIn("getOrDefault(\"\")", hook)
        retry = self.text("agent/model/AgentModelRetry.kt")
        self.assertLess(retry.index("val historySnapshotId = onAttemptStarted("),
                        retry.index("onEvent(AgentEvent.RoundStarted("))

    def test_active_text_is_the_only_ui_history_addition(self):
        snapshot = self.text("ui/app/AgentRunningBranchSnapshot.kt")
        self.assertIn("allowUnconsumedSupplement = false", snapshot)
        self.assertIn(".filter { isTextForRound(it.id, runId, round) }", snapshot)
        self.assertIn("allRoundText.joinToString", snapshot)
        self.assertIn('ConversationMessage("assistant", it.content, turnId = boundary.logicalTurnId)', snapshot)
        self.assertNotIn('ConversationMessage("tool"', snapshot)
        self.assertNotIn("argumentsSummary", snapshot)
        self.assertIn("modelHistory.take(end) + additions", snapshot)

    def test_only_completed_turns_show_actions_during_generation(self):
        body = self.text("ui/components/AgentChatBody.kt")
        projection_call = body.split("val timelineDerived = timelineDerivedCache.project(", 1)[1].split("\n    )", 1)[0]
        self.assertIn("isStreaming = isStreaming", projection_call)
        self.assertIn("isPaused = isPaused", projection_call)
        self.assertIn("isCompressingContext = isCompressingContext", projection_call)
        self.assertNotIn("branchEnabled", projection_call)
        self.assertNotIn("includeOpenTurnForBranch", body)
        # 页脚 owner 与最终回复 id 仍由同一份 turnFooters 投影推导，只是搬进了派生缓存。
        cache = self.text("ui/components/AgentTimelineDerivedCache.kt")
        self.assertIn("val turnFooters = rows.turnFooters(", cache)
        self.assertIn("val finalResultMessageIds = turnFooters.values.mapTo(mutableSetOf()) { it.id }", cache)
        self.assertIn("rows.receiptCardSeams(footerKeys = turnFooters.keys)", cache)
        projection = self.text("ui/components/AgentTurnFooterProjection.kt")
        self.assertIn("isPaused: Boolean = false", projection)
        self.assertIn("!isStreaming && !isPaused && !isCompressingContext", projection)
        self.assertNotIn("includeOpenTurnForBranch", projection)
        footer = self.text("ui/components/AgentTurnFooter.kt")
        self.assertIn("if (revealPending || isRunActive) return", footer)
        self.assertIn("if (message.isStreaming || message.content.isBlank()) return", footer)
        self.assertIn("branchEnabled = branchEnabled", footer)
        self.assertIn("messageActionsEnabled = messageActionsEnabled", footer)


if __name__ == "__main__":
    unittest.main()
