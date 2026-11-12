"""Source boundary contracts only; Compose/runtime tests remain necessary."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentProfileDialogContractTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_draft_form_is_not_an_immediate_editor_form(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for forbidden in ('LocalConversationSubAgentEditor', 'SubAgentProfileRow(',
                          '.updateProfile(', '.saveModel(', '.saveParallelLimit(', '.cycleGptSpeed(', '.add()', '.remove('):
            self.assertNotIn(forbidden, text)
        for required in ('editor.newProfileDraft()', 'editor.changeProfileModel(it, selection)',
                         'profile.withRole(requireNotNull(value))', 'profileCompatibleModels(providers, draft)',
                         'EtaFormTextField(',
                         'if (draft.supportsTaskTier)', 'if (draft.role == "image_generation")'):
            self.assertIn(required, text)
        # 模型投影过滤统一走 SubAgentProfileCapabilities（与摘要行同一份 acceptsModel 过滤链）。
        caps = self.source('ui/components/SubAgentProfileCapabilities.kt')
        self.assertIn('AgentModelPickerProjector.project(', caps)
        self.assertIn('resolveSubAgentProfileConfig(profile: SubAgentProfile', caps)
        # 表单字段统一用 EtaFormTextField；模型选择改走共用的底部面板，弹窗内不再有搜索框与二级页。
        self.assertEqual(text.count('OutlinedTextField('), 0)
        self.assertIn('TtsModelPickerDialog(', text)
        self.assertEqual(text.count('TtsModelPickerDialog('), 1)

    def test_gpt_speed_edit_is_local_and_keyed_by_current_provider_selection(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        caps = self.source('ui/components/SubAgentProfileCapabilities.kt')
        # provider 查重的 singleOrNull 口径统一在 SubAgentProfileCapabilities.resolveSubAgentProfileConfig。
        self.assertIn('providers.singleOrNull { it.id == profile.providerId && it.isEnabled }', caps)
        for required in ('speedProvider?.models?.singleOrNull { it.id == draft.modelId && it.isEnabled }',
                         'val canEditSpeed = boundConfig != null && supportsGptSpeedBinding(speedProvider, speedModel)',
                         'if (canEditSpeed) cardItem(key = "speed")',
                         'SubAgentSettingsRow(stringResource(R.string.sap_gpt_speed)',
                         'gptSpeedDisplayName(draft.gptSpeedForModel())'):
            self.assertIn(required, text)
        # 速度行与职责行之间只允许速度行自身的提交逻辑；职责/任务分工已改为行内下拉菜单。
        edit = text.split('if (canEditSpeed) cardItem(key = "speed")', 1)[1].split('cardItem(key = "role")', 1)[0]
        for required in ('session.edit { profile ->',
                         'profile.providerId != draft.providerId', 'profile.modelId != draft.modelId',
                         'profile.role != draft.role',
                         'profile.copy(gptSpeedByModel = profile.gptSpeedByModel +',
                         'SubAgentProfile.modelReasoningKey(profile.providerId, profile.modelId)',
                         'profile.gptSpeedForModel().next()'):
            self.assertIn(required, edit)
        for forbidden in ('editor.', 'boundConfig.model', 'reasoning =', 'tier =', 'Toast', 'scope.launch'):
            self.assertNotIn(forbidden, edit)

    def test_user_dismiss_cannot_abandon_submitting_but_saved_and_owner_invalidation_close(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        self.assertIn('val dismiss = { session.dismiss(); latestDismiss() }', text)
        self.assertIn('val userDismiss = { if (!session.submitting) dismiss() }', text)
        self.assertIn('onDismissRequest = userDismiss', text)
        self.assertIn('EtaDialogActions(', text)
        saved = text.split('if (result is ConversationSubAgentPreferences.WriteResult.Saved &&', 1)[1].split('else if', 1)[0]
        self.assertIn('session.isCurrent(latestEditor, latestEnabled)', saved)
        self.assertIn('dismiss()', saved)
        self.assertNotIn('userDismiss()', saved)
        self.assertNotIn('!session.submitting', saved)
        invalidated = text.split('LaunchedEffect(session, current)', 1)[1].split('if (!current) return', 1)[0]
        self.assertIn('if (!current) { session.dismiss(); latestDismiss() }', invalidated)
        self.assertNotIn('!session.submitting', invalidated)

    def test_every_open_is_owner_application_and_snapshot_bound(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('val openToken = Any()', 'editor.isDisposed', 'currentEditor !== editor',
                         'currentEditor?.owner != owner', 'current.presetApplicationToken == expectedApplicationToken',
                         'config.detached()', 'expectedProfile ?: editor.newProfileDraft()',
                         'DisposableEffect(session)', 'onDispose { session.dismiss() }',
                         'session.canCommit(latestEditor, latestEnabled, capturedTicket)'):
            self.assertIn(required, text)
        dismiss = text.split('fun dismiss()', 1)[1].split('fun isCurrent', 1)[0]
        self.assertNotIn('repository', dismiss)

    def test_suspend_submission_captures_and_only_saved_closes_and_haptics(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        submission = text.split('// Snapshot every argument before suspension;', 1)[1].split('if (usable && modelPicker)', 1)[0]
        for required in ('val capturedDraft', 'val capturedTicket', 'val capturedExpected',
                         'val capturedApplication', 'val capturedParallel', 'session.submitting = true',
                         'scope.launch', 'commitProfileDraft(', 'WriteResult.Saved',
                         'R.string.sap_not_saved', 'finally { session.submitting = false }'):
            self.assertIn(required, submission)
        self.assertLess(submission.index('val capturedDraft'), submission.index('scope.launch'))
        self.assertLess(submission.index('WriteResult.Saved'), submission.index('TouchHaptics.click(view)'))
        # 动作区改走兄弟弹窗统一的 EtaDialogActions（右对齐文字按钮；轻触反馈由组件自身提供）。
        self.assertIn('EtaDialogActions(', text)
        self.assertIn('onConfirm = {', text)
        self.assertNotIn('textButtonColorsPrimary', text)

    def test_parallel_uses_owner_actual_or_pending_confirmed_memory_and_api_pool(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        for required in ('SubAgentParallelModel(it.providerId, it.model)',
                         'expectedProfile != null && !bindingChanged',
                         'if (next != null && !originalBinding) editor.rememberedParallelLimit(draft)',
                         'parallelEdited = restored != null',
                         'restored ?: openedConfig.parallelLimit(next)',
                         'SubAgentParallelLimitChange(model, number, openedConfig.parallelLimit(model))',
                         'if (!parallelEdited) return null', 'bindingChanged = true', 'resetParallel()'):
            self.assertIn(required, text)
        self.assertNotIn('SubAgentParallelModel(draft.providerId, draft.modelId)', text)

    def test_entry_points_keep_compact_card_and_never_preadd(self):
        row = self.source('ui/components/SubAgentProfileRow.kt')
        self.assertNotIn('SubAgentTaskTierButton', row)
        # 紧凑行改由官方 Row + ListItem 承载（原 88dp 高度容器交给 ListItem 的高度策略）。
        compact = row.split('Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically)', 1)[1].split('profileDraft?.let', 1)[0]
        self.assertNotIn('R.string.sap_task_tier', compact)
        self.assertIn('Icon(Icons.Rounded.MoreVert, stringResource(R.string.spr_config_for, profile.name)', row)
        self.assertIn('SubAgentProfileDraftSession.open(editor, profile)', row)
        self.assertIn('onLongClickLabel = stringResource(R.string.spr_adjust_thinking_for, profile.name)', row)
        # 新增入口只在预设详情页（FAB）与行内溢出菜单；任何入口都不得预落一条代理。
        settings = self.source('ui/SubAgentPresetDetailScreen.kt')
        self.assertNotIn('editor.add()', settings)
        self.assertIn('SubAgentProfileDraftSession.open(editor)', settings)
        self.assertIn('R.string.action_rename', settings)
        self.assertIn('editor.remove(profile.id)', settings)

    def test_window_dialog_works_without_scaffold_and_scrolls_body_not_actions(self):
        text = self.source('ui/components/SubAgentProfileConfigDialog.kt')
        # 子代理配置改走整页：页面外壳 + 固定动作区，正文单独滚动。
        self.assertIn('import io.github.mangi.eta.ui.adaptive.EtaPageSurface', text)
        self.assertIn('import io.github.mangi.eta.ui.adaptive.EtaScaffold', text)
        self.assertIn('EtaPageSurface(onDismissRequest = userDismiss)', text)
        self.assertIn('.verticalScroll(formScroll)', text)
        self.assertIn('EtaDialogActions(', text)
        self.assertNotIn('EtaDialog(show = true', text)
        self.assertNotIn('WindowDialog(', text)
        self.assertNotIn('OverlayDialog(', text)
        self.assertNotIn('renderInRootScaffold', text)
        for required in ('EtaDialogActions(',
                         'CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration())',
                         'WithoutPressRipple'):
            self.assertIn(required, text)

    def test_session_shortcuts_reserve_real_48dp_layout_targets(self):
        row = self.source('ui/components/SubAgentProfileRow.kt')
        # 官方 IconButton 内部已 minimumInteractiveComponentSize()，48dp 触摸目标不再手写。
        self.assertIn('IconButton(enabled = usable, onClick =', row)
        self.assertNotIn('Modifier.size(48.dp)', row)
        # 详情页的新增 FAB 同样走官方容器尺寸，不手写 48dp 魔法数。
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        self.assertNotIn('Modifier.size(48.dp)', detail)

    def test_ui_fixture_uses_production_theme_without_overlay_host_workaround(self):
        fixture = (Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/ui/components/SubAgentUiFixture.kt').read_text()
        self.assertIn('AgentAppTheme(', fixture)
        self.assertIn('applyInterfaceScale = true', fixture)
        self.assertNotIn('Scaffold(', fixture)
        self.assertNotIn('OverlayHost(', fixture)


if __name__ == '__main__':
    unittest.main()
