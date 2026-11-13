"""Wiring checks supplement Kotlin behavior tests; they do not claim Android execution.

显示学习（三样本跨请求/会话比率）已删除：这里断言“没有生产学习通路”，
同时保留仍然有效的实测/预算/作用域安全契约。
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

class ContextLearningV2Contract(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_compaction_state_is_checkpointed_not_inferred_from_visible_pages(self):
        store = self.source('ui/app/AgentConversationStore.kt')
        self.assertIn('state.contextHasStarted, state.contextAwaitingReceipt, state.cloudRouteSignature', store)
        self.assertIn('CloudUsageReceiptCodec.decodeDisplayState(', store)
        ui = self.source('ui/screens/home/AgentHomeScreen.kt')
        self.assertIn('contextDisplayPolicy(state)', ui)
        self.assertNotIn('filterIsInstance<ContextCompactedMessageUi>', ui)
        policy = self.source('ui/model/RequestOverheadCalibration.kt').split('internal fun contextDisplayPolicy(', 1)[1]
        self.assertIn('awaitingReceipt = state.contextAwaitingReceipt', policy)
        self.assertIn('firstTurn = !state.contextHasStarted', policy)
        # 显示策略不再接收学习样本/回执预测。
        self.assertNotIn('receiptPredictionTokens', policy)
        self.assertNotIn('receiptEstimateTokens', policy)
        self.assertNotIn('history.isEmpty()', policy)
        self.assertNotIn('messages.isEmpty()', policy)
        app = self.source('ui/app/AgentAppState.kt')
        launch = app.split('private fun launchConversationRun(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('contextHasStarted = true', launch)
        terminal = app.split('private fun setConversationStreaming(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('contextHasStarted = state.contextHasStarted || !isStreaming', terminal)

    def test_retry_preserves_provenance_and_partial_usage_cannot_mix_requests(self):
        app = self.source('ui/app/AgentAppState.kt')
        launch = app.split('private fun launchConversationRun(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('contextStateForRequestHistory(state, history)', launch)
        self.assertIn('contextState.copy(', launch)
        self.assertNotIn('takeUnless { historyRewritten }', launch)
        history = app.split('private fun contextStateForRequestHistory(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('prefix[index].copy(turnId = "") == history[index].copy(turnId = "")', history)
        self.assertIn('if (matchesRequestPrefix(state.history)) return state', history)
        self.assertIn('AgentConversationRevisionReducer.commitVisibleAssistantIntoHistory(', history)
        self.assertIn('if (matchesRequestPrefix(committedHistory)) return state', history)
        self.assertNotIn('startsWith(', history)
        self.assertIn('validReceiptRoute && relatedHistory && !state.contextAwaitingReceipt', app)
        self.assertIn('billedContextTokens = receiptAnchorForDelta', app)
        # 真实回执仍然落地，但不再有学习采集调用。
        self.assertIn('updateLivePromptTokens(runId, measured, projected = false,', app)
        self.assertNotIn('recordContextEstimateReceipt', app)
        evidence = self.source('ui/model/ContextReceiptEvidence.kt')
        self.assertIn('it.requestId == requestId && it.input == input', evidence)
        for event in ('ProviderRequestStarted', 'ModelRetryScheduled'):
            boundary = app.split('is AgentEvent.' + event + ' ->', 1)[1].split('\n            is AgentEvent.', 1)[0]
            self.assertIn('revokeContextActual(runId)', boundary)
        revoke = app.split('private fun revokeContextActual(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('livePromptTokens = null', revoke)
        self.assertIn('contextReceiptEvidence = null', revoke)
        self.assertIn('cloudReceiptRequestId = null', revoke)
        # Boundaries keep the latest actual/evidence on the same model and route. Only an
        # invalidated route may reach the clearing branch; no boundary generates display deltas.
        self.assertIn('runUsageOwners[runId] == (state.providerId to state.modelId)', revoke)
        self.assertIn('runUsageRoutes[runId] == route', revoke)
        guard = '(state.cloudRouteSignature == null || state.cloudRouteSignature == route)) return'
        self.assertIn(guard, revoke)
        self.assertLess(revoke.index(guard), revoke.index('livePromptTokens = null'))
        self.assertIn('invalidatedUsageRuns.add(runId)', revoke)
        self.assertNotIn('receiptPredictionTokens = estimate', revoke)
        self.assertNotIn('RequestOverheadCalibration.receiptEstimate(', revoke)
        self.assertNotIn('RequestOverheadCalibration.receiptEstimate(', launch)
        terminal = app.split('private fun applyRunResult(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('current.cloudReceiptRequestId !=', terminal)
        self.assertIn('revokeContextActual(runId)', terminal)
        budget = app.split('private fun budgetReceiptTokens(', 1)[1].split('private fun ', 1)[0]
        self.assertNotIn('receiptPredictionTokens', budget)
        self.assertNotIn('overheadCalibrationTokens', budget)

    def test_no_production_display_learning_path(self):
        app = self.source('ui/app/AgentAppState.kt')
        # 生产代码不再读/写持久学习样本，也不再有任何学习采集/回放。
        for symbol in ('RequestOverheadCalibrationStore', 'recordContextEstimateReceipt',
                       'RequestOverheadCalibration.recordReceipt', 'RequestOverheadCalibration.learn',
                       'runCalibrationRounds', 'overheadCalibrationRevision', 'contextLearningRouteSignature'):
            self.assertNotIn(symbol, app)
        # 圆环只由真实回执驱动：没有 Sample/收据预测参数。
        bar = self.source('ui/components/AgentChatInputBar.kt')
        self.assertNotIn('overheadCalibrationTokens', bar)
        self.assertNotIn('receiptEstimateTokens', bar)
        self.assertIn('liveContextUsage(', bar)
        for path in ('ui/components/AgentChatBody.kt', 'ui/screens/home/AgentHomeScreen.kt',
                     'ui/app/AgentAppRoot.kt'):
            self.assertNotIn('overheadCalibrationTokens', self.source(path))
        ring = self.source('ui/model/AgentModelPickerUiState.kt').split('internal fun liveContextUsage(', 1)[1]
        self.assertNotIn('estimate(', ring.split('internal fun compressionContextUsage(', 1)[0])
        self.assertNotIn('RequestOverheadCalibration', ring.split('internal fun compressionContextUsage(', 1)[0])
        diag = self.source('ui/model/ContextEstimateDiagnostics.kt')
        self.assertNotIn('LEARNED_RATIO', diag)
        self.assertNotIn('calibration_learned', diag)
        self.assertNotIn('calibration_samples', diag)
        self.assertNotIn('calibration_ratio', diag)

    def test_custom_actual_scope_is_separate_from_fail_closed_learning_and_budget(self):
        app = self.source('ui/app/AgentAppState.kt')
        actual = app.split('private fun contextRouteSignature(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('actual-local-v1:', actual)
        for field in ('provider.baseUrl', 'provider.customHeaders', 'provider.customBody',
                      'provider.sessionGatewayJson', 'Model.serializer()'):
            self.assertIn(field, actual)
        self.assertIn('SHA-256', actual)
        # 未测豁免与预算 seed 共用同一个有效实际回执判定（custom gateway 也可作为锚点）。
        self.assertIn('private fun hasEffectiveContextReceipt(', app)
        self.assertIn('private fun validMeasuredContextTokens(', app)
        self.assertIn('val measuredContextTokens: Int?', app)
        self.assertIn('state.cloudRouteSignature == contextRouteSignature(state)', app)
        budget = app.split('private fun budgetReceiptTokens(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('takeIf { hasEffectiveContextReceipt(state) }', budget)
        self.assertIn('contextBudgetReceiptTokens', budget)
        self.assertNotIn('contextLearningRouteSignature', budget)
        live = app.split('private fun updateLivePromptTokens(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('val route = runUsageRoutes[runId] ?: return', live)
        self.assertIn('if (route != contextRouteSignature(state)) return', live)
        updates = app.split('private fun updateSelectionProviders(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('invalidatedUsageRuns.add(runId)', updates)
        self.assertIn('val next = state.withCurrentGptSpeedBinding()', updates)
        self.assertIn('state.cloudRouteSignature != contextRouteSignature(state)', updates)
        self.assertIn('updateConversation(id, next, updateTimestamp = false)', updates)
        observer = app.split('private fun observeRuntimeSelection(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('updateSelectionProviders(providers)', observer)
        diagnostic_actual = app.split('val uiActual = billedPromptTokens(contextState)', 1)[1].split('val uiTokens', 1)[0]
        self.assertIn('contextState.cloudRouteSignature == contextRouteSignature(contextState)', diagnostic_actual)
        self.assertNotIn('validReceiptRoute', diagnostic_actual)

    def test_send_exemption_uses_an_explicit_summary_commit_signal(self):
        app = self.source('ui/app/AgentAppState.kt')
        self.assertIn('onSummaryCommitted: (() -> Unit)? = null', app)
        self.assertIn('onSummaryCommitted?.invoke()', app)
        compaction = app.split('private suspend fun tryCompressHistory(', 1)[1].split(
            'private fun resolveCompressModelConfig(', 1)[0]
        # 只有 attachReferences + compactionReduced + ready 全部成功后才宣告摘要提交成功；
        # prune-only/摘要失败路径不触发，因此整段只有一次 invoke。
        self.assertEqual(1, compaction.count('onSummaryCommitted?.invoke()'))
        self.assertLess(compaction.index('boundArchive.attachReferences('),
                        compaction.index('onSummaryCommitted?.invoke()'))
        self.assertLess(compaction.index('compactionReduced('),
                        compaction.index('onSummaryCommitted?.invoke()'))
        self.assertLess(compaction.index('boundArchive.record(id, "ready")'),
                        compaction.index('onSummaryCommitted?.invoke()'))
        self.assertLess(compaction.index('return@runInterruptible working'),
                        compaction.index('onSummaryCommitted?.invoke()'))
        launch = app.split('private fun launchConversationRun(', 1)[1].split('private fun ', 1)[0]
        # 明确成功信号取代 history 不等：prune-only 不能冒充压缩成功。
        self.assertIn('onSummaryCommitted = { summaryCommitted = true }', launch)
        self.assertIn('val compactedBeforeSend = shouldCompress && summaryCommitted', launch)
        self.assertNotIn('historyToSend != history', launch)
        # 摘要失败/仅 prune 保留旧的有效锚点；只有成功压缩才换掉它。
        self.assertIn('!compactedBeforeSend && calibratedForCompression && it > 0', launch)
        self.assertNotIn('!shouldCompress && calibratedForCompression', launch)
        self.assertIn('allowUnmeasuredContextSend = unmeasuredContextSendAllowed(contextState, compactedBeforeSend)',
                      launch)
        exemption = app.split('private fun unmeasuredContextSendAllowed(', 1)[1].split('private fun ', 1)[0]
        self.assertIn('summaryCommitted || validMeasuredContextTokens(contextState) == null', exemption)

    def test_diagnostics_abandon_compression_or_retry_pairing(self):
        app = self.source('ui/app/AgentAppState.kt')
        compact = app.split('private fun applyRuntimeCompactedHistory(', 1)[1].split('private fun scheduleAutoCompress(', 1)[0]
        self.assertIn('contextEstimateDiagnostics.clear(runId)', compact)
        diag = self.source('ui/model/ContextEstimateDiagnostics.kt')
        self.assertIn('round == snapshot.round && contextEpoch == snapshot.contextEpoch', diag)
        self.assertIn('historyTokens == snapshot.historyTokensEst && overheadTokens == snapshot.overheadTokensEst', diag)
        self.assertIn('if (matched) put("estimate_error"', diag)
        self.assertNotIn('put("new_offset"', diag)

if __name__ == '__main__':
    unittest.main()
