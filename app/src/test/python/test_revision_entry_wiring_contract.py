"""No-build source contracts for the staged conversation-revision integration."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def method(text, name):
    match = re.search(r"^    (?:private |override )?(?:suspend )?fun " + re.escape(name) + r"\(", text, re.M)
    assert match, name
    end = re.search(r"^    (?:private |override )?(?:suspend )?fun ", text[match.end():], re.M)
    return text[match.start():match.end() + end.start()] if end else text[match.start():]


class RevisionEntryWiringContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = (SRC / "ui/app/AgentAppState.kt").read_text()
        cls.reducer = (SRC / "ui/app/AgentConversationRevisionReducer.kt").read_text()

    def test_all_three_entries_share_scoped_archive_preparation(self):
        for name in ("beginMessageEdit", "deleteMessageTurn", "branchConversation"):
            with self.subTest(name=name):
                self.assertIn("launchConversationRevision(messageId", method(self.app, name))
        body = method(self.app, "launchConversationRevision")
        self.assertIn("runInterruptible(Dispatchers.IO)", body)
        self.assertIn("archive::restoreHistory", body)
        self.assertIn("AgentConversationRevisionReducer.prepareForRevision", body)
        self.assertIn("rejectConversationArchiveMutation()", body)
        self.assertIn("homeState.isStreaming || homeState.isPaused", body)

    def test_stale_snapshots_and_cancellation_cannot_publish(self):
        body = method(self.app, "launchConversationRevision")
        for check in ("conversationSelectionVersion == selection", "homeState == snapshot",
                      "modelBindingGeneration == modelGeneration", "AssistantRepository.active().id == assistant",
                      "currentDraftField().text.toString() == draft"):
            self.assertIn(check, body)
        self.assertLess(body.index("coroutineContext.ensureActive()"), body.index("publish(conversationId"))
        self.assertLess(body.index("if (!stillCurrent())"), body.index("publish(conversationId"))
        self.assertIn("throw cancelled", body)
        self.assertIn("finally", body)
        self.assertIn("conversationRevisionBusy = false", body)
        self.assertIn("NonCancellable + Dispatchers.Main.immediate", body)

    def test_prepared_edit_history_stays_within_internal_ui_state(self):
        ui = (SRC / "ui/model/AgentChatUiState.kt").read_text()
        client = (SRC / "agent/model/AgentModelClient.kt").read_text()
        self.assertRegex(ui, r"(?m)^internal data class MessageEditUiState\(")
        self.assertRegex(client, r"(?m)^internal object AgentModelClient\s*\{")
        edit = ui.split("internal data class MessageEditUiState(", 1)[1].split("\n)", 1)[0]
        for field in ("preparedHistory", "preparedFromHistory"):
            self.assertIn(f"val {field}: List<AgentModelClient.ConversationMessage>? = null", edit)

    def test_edit_stages_history_instead_of_mutating_original_context(self):
        body = method(self.app, "applyPreparedMessageEdit")
        self.assertIn("snapshot.copy(", body)
        self.assertIn("preparedHistory = prepared.history", body)
        self.assertNotRegex(body, r"(?m)^\s+history\s*=\s*prepared.history")
        self.assertIn("edit.preparedHistory", method(self.reducer, "outboundHistory"))
        self.assertIn("it.preparedHistory", method(self.app, "sendCurrentMessage"))

    def test_branch_assets_are_copied_before_publication_with_failure_cleanup(self):
        body = method(self.app, "branchConversation")
        publish = body.index("publishPreparedBranch(")
        self.assertLess(body.index("AgentCompactionArchiveFork.copyReferenced("), publish)
        self.assertLess(body.index("chatImageCache.copyConversation("), publish)
        self.assertLess(body.index("if (!stillCurrent())"), publish)
        self.assertIn("if (!published)", body)
        self.assertIn("AgentCompactionArchive(appContext.filesDir, newId).delete()", body)
        self.assertIn("chatImageCache.deleteConversation(newId)", body)
        publish_body = method(self.app, "publishPreparedBranch")
        self.assertIn("conversationCreatedAt = conversationCreatedAt + (newId", publish_body)
        self.assertLess(publish_body.index("conversationSubAgentPreferences.createConversation("),
                        publish_body.index("conversationsById ="))
        cache = (SRC / "agent/media/AgentChatImageCache.kt").read_text()
        self.assertIn("check(source.copyRecursively(target, overwrite = true))", cache)
        self.assertIn("Thread.currentThread().isInterrupted", method(cache, "copyConversation"))

    def test_branch_archive_and_live_history_share_structured_attachment_relocation(self):
        branch = method(self.app, "branchConversation")
        self.assertIn("rewriteAttachmentPath = { value -> chatImageCache.rewriteCachedPath(value, sourceId, newId) }", branch)
        publish = method(self.app, "publishPreparedBranch")
        self.assertIn("history = prefix.history.map { it.rewritePaths(rewrite) }", publish)
        self.assertIn("AgentConversationAttachmentRelocator.rewrite(this, rewrite)", self.reducer)
        paths = self.reducer[self.reducer.index("internal fun AgentChatMessageUi.rewritePaths"):]
        self.assertNotIn("content = rewrite(content)", paths)
        self.assertNotIn("contentJson = rewrite(contentJson)", paths)
        self.assertNotIn("argumentsSummary = rewrite", paths)
        fork = (SRC / "agent/model/AgentCompactionArchiveFork.kt").read_text()
        self.assertIn("rewriteAttachmentPath: ((String) -> String)? = null", fork)
        self.assertLess(fork.index("roots.forEach { visit(it, 1) }"),
                        fork.index("AgentConversationAttachmentRelocator.rewriteJsonMessage"))
        self.assertIn("hash(relocated)", fork)
        self.assertIn("byteLimit - targetBytes", fork)

    def test_legacy_dependency_recovery_is_branch_only_and_opt_in(self):
        branch = method(self.app, "branchConversation")
        self.assertIn("recoverLegacyDependencies = true", branch)
        self.assertEqual(1, self.app.count("recoverLegacyDependencies = true"))
        self.assertNotIn("pendingBranchRecovery", self.app)
        self.assertNotIn("LegacyDependencyApprovalRequired", self.app)
        prepare = method(self.app, "launchConversationRevision")
        self.assertIn("archive::restoreHistory", prepare)
        self.assertNotIn("recoverLegacyDependencies", prepare)
        fork = (SRC / "agent/model/AgentCompactionArchiveFork.kt").read_text()
        self.assertIn("recoverLegacyDependencies: Boolean = false", fork)
        self.assertIn("absent && depth > 1 && legacy != null", fork)
        self.assertIn("Files.notExists(json.toPath(), LinkOption.NOFOLLOW_LINKS)", fork)
        self.assertLess(fork.index("if (depth == 1)"), fork.index("if (id in completed)"))
        self.assertLess(fork.index("legacy?.recheckScopes()"), fork.index("Files.move("))
        helper = (SRC / "agent/model/AgentLegacyArchiveDependency.kt").read_text()
        for guard in ("MAX_ENTRIES", "MAX_VERIFY_BYTES", "selected.contentEquals(bytes)",
                      "AgentCompactionArchiveIntegrity.verifiedBytes", "Thread.currentThread().isInterrupted"):
            self.assertIn(guard, helper)
        archive = (SRC / "agent/model/AgentCompactionArchive.kt").read_text()
        self.assertNotIn("AgentLegacyArchiveDependency", archive)
        self.assertIn("fun delete() = synchronized(AgentCompactionArchiveFork)", archive)

    def test_branch_copy_error_is_not_misreported_as_anchor_failure(self):
        branch = method(self.app, "branchConversation")
        self.assertIn("throw BranchArchiveCopyException(failure)", branch)
        self.assertIn("throw cancelled", branch)
        self.assertIn("throw interrupted", branch)
        self.assertIn("if (!published)", branch)
        prepare = method(self.app, "launchConversationRevision")
        self.assertIn("failure is BranchArchiveCopyException", prepare)
        self.assertIn("branch_archive_copy", prepare)
        # 复制失败提示收敛为本地化资源（原硬编码文案已迁入 strings.xml）。
        self.assertIn("R.string.appst_branch_archive_copy_failed", prepare)
        self.assertIn("else showRevisionHistoryUnavailableNotice()", prepare)

    def test_runtime_suffix_matching_is_only_within_verified_turn(self):
        body = method(self.reducer, "ownedHistoryMessageLocation")
        self.assertIn("runId.isNotBlank() && history.turnId == runId", body)
        self.assertIn("AgentRevisionRuntimeSuffix.matches(text, expectedText)", body)
        self.assertIn("scoped.size != peers.size", body)
        self.assertNotIn("AgentRevisionRuntimeSuffix", method(self.reducer, "mismatchedOwnerLocation"))
        self.assertNotIn("AgentRevisionRuntimeSuffix", method(self.reducer, "historyText"))

    def test_runtime_suffix_templates_stay_aligned_with_producers(self):
        codec = (SRC / "ui/app/AgentRevisionRuntimeSuffix.kt").read_text()
        availability = (SRC / "agent/runtime/AgentChildWorkerAvailability.kt").read_text()
        for line in availability.splitlines():
            match = re.match(r'\s*"([^"\\]+)"\s*\+', line)
            if match:
                self.assertIn(match.group(1), codec)
        handoff = (SRC / "agent/runtime/AgentChildTaskHandoff.kt").read_text()
        for line in handoff.splitlines():
            if any(line.strip().startswith(prefix) for prefix in ("下面是", "本轮开始", "健康或暂停", "主代理停止", "若摘要")):
                self.assertIn(line.strip(), codec)
        self.assertIn("json.length == 12_000", codec)
        self.assertIn("remaining.isNotEmpty()", codec)
        self.assertIn("if (!seen.add(name)) return false", codec)

    def test_stop_controls_and_history_protection_both_survive_integration(self):
        guard = method(self.app, "rejectConversationArchiveMutation")
        self.assertIn("protectStoppingRun: Boolean = true", guard)
        self.assertIn("conversationRevisionBusy", guard)
        self.assertIn("protectStoppingRun && stoppingRuns", guard)
        self.assertIn("backupMaintenance", guard)
        self.assertIn("protectStoppingRun = false", method(self.app, "selectModel"))
        history_cut = method(self.reducer, "historyBefore")
        self.assertIn("validateRemoved && cut < state.messages.size", history_cut)
        self.assertIn("historyMessageLocation(state, keptAnchor) as?", history_cut)
        self.assertNotIn("as AgentConversationRevisionArchive.Location.Found", history_cut)

    def test_edit_gate_does_not_reuse_receipt_for_uncommitted_history(self):
        start = self.app.index("val measuredContextTokens: Int?")
        end = self.app.index("var billedOverheadTokens", start)
        self.assertIn("if (homeState.messageEdit == null)", self.app[start:end])
        self.assertIn("else null", self.app[start:end])
        self.assertIn("it.preparedFromHistory != homeState.history", method(self.app, "sendCurrentMessage"))

    def test_prune_only_paths_preserve_receipts_and_summary_epoch(self):
        for name in ("applyCompressedHistoryToConversation", "applyManualCompressedHistory"):
            with self.subTest(name=name):
                body = method(self.app, name)
                self.assertIn("summaryCommitted: Boolean = false", body)
                self.assertIn("if (!summaryCommitted)", body)
                branch = body.split("if (!summaryCommitted)", 1)[1].split("persistConversations()", 1)[0]
                for forbidden in ("livePromptTokens = null", "cloudRouteSignature = null", "contextAwaitingReceipt = true"):
                    self.assertNotIn(forbidden, branch)
        self.assertGreaterEqual(self.app.count("onSummaryCommitted = { summaryCommitted = true }"), 3)
        auto = method(self.app, "compressSubmittedHistory")
        self.assertLess(auto.index("if (!summaryCommitted)"), auto.index("livePromptTokens = null"))


if __name__ == "__main__":
    unittest.main()
