"""Static integration guards only: not Kotlin execution or rendered-UI proof."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'

def source(path):
    return (ROOT / path).read_text(encoding='utf-8')

class ChildGptSpeedContractTest(unittest.TestCase):
    def test_both_layouts_use_the_same_speed_button(self):
        row = source('ui/components/SubAgentProfileRow.kt')
        self.assertEqual(row.count('SubAgentGptSpeedButton('), 2)
        self.assertIn('trailing = { SubAgentGptSpeedButton', row)
        card = row[row.rindex('SubAgentGptSpeedButton('):]
        # 「配置」入口改用官方 IconButton + stringResource 文案（尺寸交给 IconButtonDefaults）。
        self.assertIn('IconButton(enabled = usable, onClick = {', card)
        self.assertIn('Icon(Icons.Rounded.MoreVert, stringResource(R.string.spr_config_for, profile.name)', card)
        self.assertIn('SubAgentProfileDraftSession.open(editor, profile)', card)
        self.assertNotIn('SubAgentTaskTierButton(', row)
        # 紧凑行改由官方 ListItem 承载，任务分工只出现在设置态（这里仍不可见）。
        compact = row.split('Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically)', 1)[1].split('profileDraft?.let', 1)[0]
        self.assertNotIn('R.string.sap_task_tier', compact)
        settings = source('ui/components/SubAgentProfileRow.kt')
        # 尾部统一走共享箭头组件（EtaTrailingChevron）：badge、trailing 槽、箭头依次排布。
        self.assertLess(settings.index('trailing?.invoke()'), settings.index('EtaTrailingChevron()'))

    def test_speed_content_is_the_official_assist_chip(self):
        button = source('ui/components/SubAgentGptSpeedButton.kt')
        # 官方 AssistChip 承担容器高度、形状、描边、涟漪、标签排版与禁用态；触摸下限交给
        # 官方 minimumInteractiveComponentSize()（默认 48.dp），不再手写尺寸或压制按压反馈。
        for check in ('AssistChip(',
                      'onClick = cycleSpeed',
                      'modifier = semanticsModifier.minimumInteractiveComponentSize()',
                      'enabled = usable',
                      'colors = AssistChipDefaults.assistChipColors(labelColor = displayedColor)',
                      'size(AssistChipDefaults.IconSize)'):
            self.assertIn(check, button)
        self.assertNotIn('SubAgentGptSpeedMinTouchSize', button)
        self.assertNotIn('.defaultMinSize(', button)
        self.assertNotIn('indication = null', button)
        self.assertNotIn('hapticFeedbackEnabled = false', button)
        self.assertNotIn('.offset(', button)
        self.assertNotIn('.size(48.dp)', button)

    def test_button_filters_actual_binding(self):
        button = source('ui/components/SubAgentGptSpeedButton.kt')
        for check in ('it.id == profile.providerId', 'it.id == profile.modelId',
                      'supportsGptSpeedBinding(provider, model)', 'if (!eligible) return'):
            self.assertIn(check, button)

    def test_animation_reuses_chat_policy_and_does_not_queue(self):
        button = source('ui/components/SubAgentGptSpeedButton.kt')
        for shared in ('gptSpeedRotationDegrees(progress.value)',
                       'gptSpeedScale(progress.value)',
                       'etaGptSpeedColor(mode, normalColor)',
                       'GptSpeedAnimationGate()', 'gate.request()', 'gate.finish(token)',
                       # 时长不再由本地常量直接使用，改读官方 motionScheme 的空间规格。
                       'MaterialTheme.motionScheme.defaultSpatialSpec()'):
            self.assertIn(shared, button)
        # 入口是官方 AssistChip 的单击；刻意不引入长按语义。
        self.assertIn('onClick = cycleSpeed', button)
        self.assertNotIn('onLongClick', button)
        self.assertIn('if (token != 0)', button)
        self.assertNotIn('delay(', button)

    def test_failed_save_never_animates_or_claims_success(self):
        button = source('ui/components/SubAgentGptSpeedButton.kt')
        saved = button.index('result !is ConversationSubAgentPreferences.WriteResult.Saved')
        self.assertLess(button.index('editor?.cycleGptSpeed('), saved)
        self.assertLess(saved, button.index('Toast.makeText'))
        self.assertLess(saved, button.index('progress.animateTo('))
        self.assertIn('expected = profile', button)

    def test_each_profile_remembers_modes_by_provider_and_model(self):
        profile = source('agent/delegation/SubAgentProfile.kt')
        self.assertIn('gptSpeedByModel: Map<String, GptSpeedMode>', profile)
        self.assertIn('fun gptSpeedForModel(', profile)
        self.assertIn('modelReasoningKey(providerId, modelId)', profile)
        self.assertIn('gpt_speed_memory', profile)
        self.assertIn('GptSpeedMode.NORMAL', profile)

    def test_owner_snapshots_detach_speed_memory(self):
        store = source('agent/delegation/ConversationSubAgentPreferences.kt')
        resolver = source('agent/runtime/ChildWorkerConfigResolver.kt')
        self.assertIn('gptSpeedByModel = it.gptSpeedByModel.toMap()', store)
        self.assertIn('gptSpeedByModel = it.gptSpeedByModel.toMap()', resolver)
        self.assertIn('gpt_speed_memory', store)

    def test_editor_writes_under_owner_and_validates_live_binding(self):
        editor = source('ui/components/ConversationSubAgentEditor.kt')
        body = editor.split('fun cycleGptSpeed(', 1)[1].split('fun saveModel(', 1)[0]
        for check in ('val capturedConfig = repository.snapshot(owner)',
                      'val capturedToken = capturedConfig.presetApplicationToken',
                      'captured.providerId != providerId', 'captured.modelId != modelId',
                      'expected != null && captured != expected',
                      'val binding = SubAgentParallelModel(providerId, model.modelId)',
                      'confirmedUpdate(id, binding)', 'config.presetApplicationToken != capturedToken',
                      'old != (expected ?: captured)', 'old.providerId != providerId', 'old.modelId != modelId',
                      'supportsGptSpeedBinding(provider, model)', 'gptSpeedByModel',
                      'SubAgentProfile.modelReasoningKey(providerId, modelId)'):
            self.assertIn(check, body)
        confirmed = editor.split('private fun confirmedUpdate(', 1)[1].split('fun updateProfile(', 1)[0]
        self.assertIn('repository.updateConfirmedProfile(owner, id, binding, { enabled })', confirmed)
        self.assertIn('old.presetApplicationToken != token', confirmed)
        self.assertIn('WriteResult.Saved', confirmed)
        self.assertNotIn('SubAgentPreferences.update(', body)
        self.assertNotIn('reasoning =', body)

    def test_runtime_applies_own_speed_and_checks_eligibility(self):
        resolver = source('agent/runtime/ChildWorkerConfigResolver.kt')
        for check in ('supportsGptSpeedBinding(provider, model)', 'supportsGptSpeedBinding(currentProvider, currentModel)',
                      'gptSpeedForModel(', 'gptSpeedMode =', 'isGptSpeedModel('):
            self.assertIn(check, resolver)
        self.assertNotIn('request.config.gptSpeedMode', resolver)

    def test_provider_lookup_is_suspend_and_outside_owner_transaction(self):
        editor = source('ui/components/ConversationSubAgentEditor.kt')
        self.assertNotIn('runBlocking', editor)
        self.assertIn('suspend fun cycleGptSpeed(', editor)
        body = editor.split('suspend fun cycleGptSpeed(', 1)[1].split('fun saveModel(', 1)[0]
        lookup = body.index('providerLookup(')
        transaction = body.index('confirmedUpdate(id, binding)')
        self.assertLess(body.index('val capturedConfig = repository.snapshot(owner)'), lookup)
        self.assertLess(body.index('val capturedToken = capturedConfig.presetApplicationToken'), lookup)
        self.assertLess(body.index('expected != null && captured != expected'), lookup)
        self.assertLess(lookup, transaction)
        self.assertLess(body.index('currentCoroutineContext().ensureActive()'), transaction)
        self.assertIn('resolvedProvider?.takeIf { it.id == providerId }', body[:transaction])
        self.assertIn('provider.models.singleOrNull { it.id == modelId }', body[:transaction])
        self.assertIn('old != (expected ?: captured)', body[transaction:])
        self.assertIn('config.presetApplicationToken != capturedToken', body[transaction:])
        confirmed = editor.split('private fun confirmedUpdate(', 1)[1].split('fun updateProfile(', 1)[0]
        self.assertNotIn('providerLookup(', confirmed)
        self.assertIn('CancellationException', editor)

    def test_existing_request_tier_mapping_is_unchanged(self):
        tier = source('agent/model/GptServiceTier.kt')
        for mode, value in [('NORMAL','default'), ('FAST','fast'), ('ULTRA_FAST','ultrafast')]:
            self.assertIn(f'GptSpeedMode.{mode} -> "{value}"', tier)
        self.assertIn('GptSpeedMode.FAST -> "priority"', tier)
        self.assertIn('ProviderConnectionProfile.forAuthMode(config.authMode)', tier)
        for name in ('OpenAiChatCompletionsProvider.kt','ResponsesRequestBuilder.kt'):
            self.assertIn('GptServiceTier.apply(', source('agent/model/'+name))

if __name__ == '__main__':
    unittest.main()
