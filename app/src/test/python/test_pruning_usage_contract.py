"""Source wiring guards only. Does not compile or execute Kotlin/Android."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class PruningUsageContractTest(unittest.TestCase):
    def read(self, path):
        return (ROOT / path).read_text()

    def test_pruning_keeps_cloud_anchor_and_does_not_reopen_local_boundary(self):
        loop = self.read('agent/model/AgentLoop.kt')
        prune = loop.split('private fun pruneOversizedToolResults(', 1)[1].split('private fun applyCompaction(', 1)[0]
        for forbidden in ('lastUsage = null', 'requestBudget.contextReplaced()', 'silentBudget.contextReplaced()'):
            self.assertNotIn(forbidden, prune)
        self.assertIn('pruningOnly = true', prune)
        # 仅修剪路径传 true（见 onHistoryCompacted 的 KDoc），不重开本地展示边界。
        self.assertIn('onHistoryCompacted(true)', prune)
        self.assertIn('archive.save(', prune)
        self.assertIn('archive.record(it, "committed")', prune)
        self.assertIn('systemCount until endExclusive', prune)
        self.assertIn('sensitiveToolCallIds', prune)

    def test_real_summary_still_invalidates_calibration(self):
        loop = self.read('agent/model/AgentLoop.kt')
        summary = loop.split('private fun applyCompaction(', 1)[1]
        self.assertIn('lastUsage = null', summary)
        self.assertIn('requestBudget.contextReplaced()', summary)
        self.assertIn('silentBudget.contextReplaced()', summary)
        self.assertEqual(1, loop.count('silentBudget.contextReplaced()'))

    def test_ui_pruning_only_updates_history_for_both_manual_and_auto_paths(self):
        app = self.read('ui/app/AgentAppState.kt')
        handler = app.split('private fun applyRuntimeCompactedHistory(', 1)[1].split('private fun scheduleAutoCompress(', 1)[0]
        prune = handler.split('if (event.pruningOnly)', 1)[1].split('runCompressedDuringRun.add(runId)', 1)[0]
        self.assertIn('current.copy(history = event.history)', prune)
        self.assertIn('persistConversations()', prune)
        for forbidden in ('livePromptTokens =', 'cloudHistoryTokens =', 'cloudRequestOverheadTokens =',
                          'isCompressingContext = false', 'applyMarker(', 'pendingInRunCompactConversationIds.remove'):
            self.assertNotIn(forbidden, prune)
        self.assertIn('runUsageResumeRounds[runId] = event.round', handler)
        self.assertLess(handler.index('if (event.pruningOnly)'), handler.index('runCompressedDuringRun.add(runId)'))
        self.assertIn('livePromptTokens = null', handler)
        self.assertIn('AgentContextCompactionUi.applyMarker(', handler)

    def test_wire_has_explicit_kind_and_old_label_fallback(self):
        event = self.read('agent/runtime/AgentEvent.kt')
        self.assertIn('val pruningOnly: Boolean', event)
        # 协议标记收敛为常量（旧字面量判定被替换），展示文案仍由 UI 映射。
        self.assertIn('compressorLabel == PRUNING_ONLY_COMPRESSOR_LABEL', event)
        wire = self.read('agent/runtime/AgentRuntimeWire.kt')
        self.assertIn('putBoolean("pruning_only", event.pruningOnly)', wire)
        self.assertIn('pruningOnly = bundle.getBoolean("pruning_only",', wire)
        self.assertIn('bundle.getString("compressor_label") == AgentEvent.PRUNING_ONLY_COMPRESSOR_LABEL', wire)

    def test_subagent_does_not_count_pruning_as_summary_compaction(self):
        code = self.read('agent/delegation/SubAgentContextStats.kt')
        handler = code.split('is AgentEvent.ContextCompacted -> {', 1)[1].split('else -> return null', 1)[0]
        self.assertIn('event.pruningOnly', handler)
        self.assertLess(handler.index('event.pruningOnly'), handler.index('awaitingCompactedUsage = true'))
        self.assertIn('contextTokens = if (event.applied) null else value.contextTokens', handler)

    def test_cloud_readings_are_not_clamped_to_a_historical_maximum(self):
        app = self.read('ui/app/AgentAppState.kt')
        handler = app.split('private fun updateLivePromptTokens(', 1)[1].split('private fun insertSupplementMessage(', 1)[0]
        self.assertIn('state.copy(livePromptTokens = tokens, livePromptIsProjected = false', handler)
        self.assertNotIn('maxOf(', handler)
        self.assertIn('if (projected) return', handler)
        self.assertIn('ContextReceiptEvidence.merge(', handler)
        self.assertIn('usageRunByConversation[conversationId] != runId', handler)

if __name__ == '__main__':
    unittest.main()
