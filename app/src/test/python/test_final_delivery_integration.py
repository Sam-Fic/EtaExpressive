"""Merged-source contracts only; Kotlin/Android execution is checked separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class FinalDeliveryIntegrationTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_stopped_main_reply_and_pruning_signal_coexist(self):
        recovery = self.source('ui/app/AgentPendingResultRecovery.kt')
        self.assertIn('result.error == "已停止") SystemNoticeCode.Stopped', recovery)
        self.assertIn('messages[assistantIndex] = partial.copy(isStreaming = false)', recovery)
        self.assertIn('VirtualCompletionNotice.append(messagesWithResult, runId, result)', recovery)
        event = self.source('agent/runtime/AgentEvent.kt')
        wire = self.source('agent/runtime/AgentRuntimeWire.kt')
        self.assertIn('val pruningOnly: Boolean', event)
        self.assertIn('putBoolean("pruning_only", event.pruningOnly)', wire)
        self.assertIn('pruningOnly = bundle.getBoolean("pruning_only",', wire)
        app = self.source('ui/app/AgentAppState.kt')
        handler = app.split('private fun applyRuntimeCompactedHistory(', 1)[1].split('private fun scheduleAutoCompress(', 1)[0]
        prune = handler.split('if (event.pruningOnly)', 1)[1].split('runCompressedDuringRun.add(runId)', 1)[0]
        self.assertIn('current.copy(history = event.history)', prune)
        self.assertNotIn('livePromptTokens = null', prune)
        self.assertNotIn('cloudHistoryTokens = null', prune)
        self.assertIn('AgentContextCompactionUi.applyMarker(', handler)

    def test_inline_actions_only_preview_uses_full_preview_entry(self):
        controls = self.source('ui/screens/virtualdisplay/VirtualDisplaySections.kt')
        actions = controls.split('FlowRow(', 1)[1]
        # 唯一的 FlowRow 里是三个自带触感的按钮（打开预览 / 撤销预览 / 刷新）；
        # 恢复与强制重置改走 EtaSegmentedRow 行，触感由行自带、不再单独打点。
        self.assertEqual(3, actions.count('TouchHaptics.click(view)'))
        self.assertEqual(1, actions.count('VirtualDisplayWebPreview.openWithManualClose(context)'))
        self.assertNotIn('VirtualDisplayWebPreview.open(context)', actions)
        self.assertIn('VirtualDisplayWebPreview.revoke(context)', actions)
        self.assertNotIn('VirtualDisplayWebPreview.stop()', actions)
        self.assertRegex(actions, r'TouchHaptics\.click\(view\)\s+refresh\(\)')
        self.assertIn('recover(context.applicationContext)', actions)

    def test_dynamic_configuration_error_has_explicit_recovery(self):
        editor = self.source('ui/components/ConversationSubAgentEditor.kt')
        self.assertIn('get() = lifecycleFailure?.invoke()?.let { SubAgentEditorState.Error(it) } ?: storedState', editor)
        retry = editor.split('fun retry() {', 1)[1].split('@Composable', 1)[0]
        self.assertIn('repository.recoverDurability()', retry)
        self.assertIn('lifecycleRecovery?.invoke() == true', retry)
        self.assertLess(retry.index('lifecycleRecovery?.invoke() == true'), retry.index('storedState = SubAgentEditorState.Loading'))
        self.assertIn('val enabled: Boolean get() = !disposed && state is SubAgentEditorState.Loaded && canEdit()', editor)

    def test_failed_initial_draft_pointer_read_cannot_be_seeded_by_new_draft(self):
        app = self.source('ui/app/AgentAppState.kt')
        begin = app.split('private fun beginNewSubAgentDraft(', 1)[1].split('private fun ensureSubAgentConfigurationReady()', 1)[0]
        ensure = app.split('private fun ensureSubAgentConfigurationReady()', 1)[1].split('private fun ', 1)[0]
        guard = begin.split('return try {', 1)[0]
        self.assertIn('source is SubAgentConfigKey.Draft && !subAgentDraftReady', guard)
        self.assertIn('(!recoveringPending || subAgentDraftPointerReloadPending)', guard)
        self.assertIn('recoveringPending && source == null', guard)  # No bootstrap during pending recovery.
        create = begin.split('return try {', 1)[1]
        self.assertLess(create.index('existingDraftOrNull(source) != null'), create.index('createDraft(source)'))
        self.assertLess(create.index('createDraft(source)'), create.index('subAgentDraftPointerReloadPending = false'))
        self.assertIn('beginNewSubAgentDraft(pendingSubAgentDraftSource, recoveringPending = true)', ensure)
        self.assertIn('source: SubAgentConfigKey? = subAgentConfigOwner', begin)
        self.assertIn('val next = conversationSubAgentPreferences.createDraft(source)', create)


if __name__ == '__main__':
    unittest.main()
