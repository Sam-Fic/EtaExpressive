"""Preset UI/source contracts; these do not substitute for running Compose tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class SubAgentPresetUiContractTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_list_shell_owns_catalog_while_detail_screen_owns_the_preset_editor(self):
        shell = self.source('ui/SubAgentSettingsScreen.kt')
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        # 列表页新增「本会话」区块：只消费 App 级会话编辑器（默认参数取 CompositionLocal），
        # 仍不自行创建任何编辑器；预设 editor 依旧只归详情页。
        self.assertIn('conversationEditor: ConversationSubAgentEditor? = LocalConversationSubAgentEditor.current', shell)
        self.assertIn('conversationEditor?.scoped(', shell)
        for needle in ('ConversationSubAgentPreferences()', 'remember { repositoryFactory() }',
                       'rememberSubAgentPresetDirectory(repository)', 'onOpenPreset(preset.id)'):
            self.assertIn(needle, shell)
        # 详情是独立路由：列表页不再自己持有预设 editor，也不再靠 selectedId/BackHandler 内部切换。
        self.assertNotIn('ConversationSubAgentEditor(', shell)
        self.assertNotIn('BackHandler(', shell)
        self.assertNotIn('selectedId', shell)
        for needle in ('SubAgentConfigKey.Preset(preset.id)', 'ConversationSubAgentEditor(owner, repository)',
                       'presetExists(owner.value)', 'LocalConversationSubAgentEditor provides editor',
                       'DisposableEffect(editor)', 'onDispose { editor.dispose() }'):
            self.assertIn(needle, detail)
        for needle in ('R.string.sap_add_profile', 'SubAgentProfileRow(', 'settings = true',
                       'header = {', 'updateProfile(profile.id)', 'editor.remove(profile.id)'):
            self.assertIn(needle, detail)
        # Preset profile editing still writes through the real editor, but neither switch belongs here.
        for forbidden in ('R.string.ccd_auto_delegation', 'setEnabled(', 'setDiagnosticsEnabled', 'diagnosticsEnabled', '子代理诊断日志'):
            self.assertNotIn(forbidden, detail)
        editor = self.source('ui/components/ConversationSubAgentEditor.kt')
        self.assertNotIn('setDiagnosticsEnabled', editor)
        self.assertIn('fun setEnabled(value: Boolean) = update { it.copy(enabled = value) }', editor)
        self.assertNotIn('taskRunning', shell)
        self.assertNotIn('editor.add()', detail)
        self.assertIn('SubAgentProfileDraftSession.open(editor)', detail)
        self.assertIn('SubAgentProfileConfigDialog(session, editor, providers', detail)

    def test_catalog_supports_named_empty_add_rename_delete_and_explicit_retry(self):
        settings = self.source('ui/SubAgentSettingsScreen.kt')
        # 组列表已改为 KernelSU 式分段列表（EtaScaffoldPage + EtaSegmentedColumn + FAB），
        # 预设目录的读取/重试状态由共享的 SubAgentPresetDirectory 承载。
        for needle in ('repository.addPreset(name.trim())', 'repository.renamePreset(', 'repository.removePreset(',
                       'EtaScaffoldPage(', 'EtaSegmentedColumn(', 'SubAgentPresetDirectoryStatus(directory)'):
            self.assertIn(needle, settings)
        directory = self.source('ui/components/SubAgentPresetDirectory.kt')
        for needle in ('repository.presets()', 'repository.presetsFlow().collect', 'recoverDurability()',
                       'if (error != null) return@LaunchedEffect', 'TouchHaptics.click(view); directory.retry()'):
            self.assertIn(needle, directory)
        # 目录状态不手写圆角/叠透明度：读取失败保持可点重试，不伪装成空目录。
        code = '\n'.join(line.split('//')[0] for line in directory.splitlines())
        for gone in ('RoundedCornerShape', 'primaryContainer.copy(alpha', 'indication = null'):
            self.assertNotIn(gone, code)
        for path in ('ui/SubAgentSettingsScreen.kt', 'ui/SubAgentPresetDetailScreen.kt'):
            self.assertIn('WithoutPressRipple', self.source(path))
            self.assertIn('LocalRippleConfiguration provides etaRippleConfiguration()', self.source(path))
            self.assertNotIn('LocalRippleConfiguration provides null', self.source(path))

    def test_composer_long_press_reuses_settings_subagent_pages(self):
        # 子代理配置只保留设置页一套页面（列表 + 详情两条独立路由）：
        # 输入栏长按「助手与模型」入口改为派发导航动作，不再单独承载一套会话协作页。
        actions = self.source('ui/model/AgentUiActions.kt')
        self.assertIn('data object OpenSubAgentSettings : AgentHomeAction', actions)
        self.assertIn('data object OpenSubAgentSettings : AgentChatAction', actions)
        root = self.source('ui/app/AgentAppRoot.kt')
        self.assertIn('AgentHomeAction.OpenSubAgentSettings -> pushRoute(AppRoute.SubAgents)', root)
        composer = self.source('ui/components/AgentChatComposerM3.kt')
        self.assertIn('onOpenSubAgentSettings: () -> Unit = {}', composer)
        self.assertIn('onOpenSubAgentSettings = onOpenSubAgentSettings', composer)
        self.assertIn('onLongClick = onOpenSubAgentSettings', composer)
        home = self.source('ui/screens/home/AgentHomeScreen.kt')
        self.assertIn('onOpenSubAgentSettings = { onAction(AgentHomeAction.OpenSubAgentSettings) }', home)
        # 独立页面已删除：主源码里不得再出现协作页或其宿主弹窗围栏。
        for path in ROOT.rglob('*.kt'):
            text = path.read_text()
            self.assertNotIn('ConversationCollaborationDialog', text, path)
            self.assertNotIn('OwnerBoundPopup', text, path)
        # 长按入口的行为由 Compose 测试守住。
        tests = Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/ui/components'
        toolbar = (tests / 'M3ComposerToolbarLayoutTest.kt').read_text()
        self.assertIn('longPressOnModelPickerEntryOpensSubAgentSettingsPage', toolbar)
        self.assertIn('onOpenSubAgentSettings = { opened++ }', toolbar)

    def test_editor_rows_capture_application_and_dispose_instead_of_trusting_root_state(self):
        editor = self.source('ui/components/ConversationSubAgentEditor.kt')
        for needle in ('fun dispose() { disposed = true }', '!disposed && state is SubAgentEditorState.Loaded',
                       'root.enabled && gate() && root.applicationMatches(applicationToken)',
                       'old.presetApplicationToken != observedToken',
                       'repository.applyPreset(owner, id) { enabled && canApply() }'):
            self.assertIn(needle, editor)
        speed = editor.split('suspend fun cycleGptSpeed(', 1)[1].split('fun saveModel(', 1)[0]
        self.assertLess(speed.index('val capturedToken = capturedConfig.presetApplicationToken'), speed.index('providerLookup('))
        self.assertIn('(state as? SubAgentEditorState.Loaded)?.config?.presetApplicationToken != capturedToken', speed)
        self.assertIn('confirmedUpdate(id, binding)', speed)
        self.assertIn('if (config.presetApplicationToken != capturedToken) throw LostOwner()', speed)
        self.assertLess(speed.index('providerLookup('), speed.index('confirmedUpdate(id, binding)'))
        self.assertLess(speed.index('confirmedUpdate(id, binding)'),
                        speed.index('if (config.presetApplicationToken != capturedToken) throw LostOwner()'))

    def test_catalog_actions_use_anchored_menu_and_do_not_replace_profile_config(self):
        shell = self.source('ui/SubAgentSettingsScreen.kt')
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        # 组溢出操作改走就地弹出菜单：与详情页子代理标题行同一套 DropdownMenu，不再拉起底部面板。
        # 删除确认走 EtaDialog（自适应弹窗），断言只锁 onDismissRequest 语义不锁弹窗形态。
        for needle in ('SubAgentDropdownMenu(', 'DropdownMenuItem(',
                       'name = preset.name; rename = preset', 'if (editable) delete = preset',
                       'repository.renamePreset(', 'repository.removePreset(', 'onDismissRequest = { delete = null }'):
            self.assertIn(needle, shell)
        for gone in ('actionsPreset', 'SubAgentPresetActionsSheet', 'ModalBottomSheet('):
            self.assertNotIn(gone, shell)
        self.assertIn('SubAgentProfileConfigDialog(session, editor, providers', detail)
        row = self.source('ui/components/SubAgentProfileRow.kt')
        for needle in ('Icon(Icons.Rounded.MoreVert, stringResource(R.string.spr_config_for, profile.name)',
                       'SubAgentProfileDraftSession.open(editor, profile)',
                       'SubAgentProfileConfigDialog(session, editor, providers',
                       'onLongClickLabel = stringResource(R.string.spr_adjust_thinking_for, profile.name)',
                       'onLongClick = { if (currentUsable && canThink) { thinkingPicker = true } }'):
            self.assertIn(needle, row)
        # 可访问名走资源，点击/长按由官方 ListItem 槽位承载：不再手写 onClickLabel，也不再补长按触感。
        self.assertNotIn('onClickLabel =', row)
        self.assertNotIn('TouchHaptics.longPress', row)
        self.assertNotIn('SubAgentPresetActionsSheet(', row)

    def test_small_viewport_alignment_scroll_and_retained_callback_tests_are_present(self):
        tests = Path(__file__).resolve().parents[1] / 'kotlin/io/github/mangi/eta/ui/components'
        toolbar = (tests / 'M3ComposerToolbarLayoutTest.kt').read_text()
        # 长按入口改用导航后，输入栏仍保留一套覆盖工具栏布局与长按手势的回归测试。
        for needle in ('@Config(application = Application::class, sdk = [34], qualifiers = "w480dp-h900dp-mdpi")',
                       'longPressOnModelPickerEntryOpensSubAgentSettingsPage'):
            self.assertIn(needle, toolbar)
        settings = (tests / 'SubAgentPresetSettingsTest.kt').read_text()
        # 该测试断言中文资源字符串，需固定中文 locale，否则 Robolectric 默认 en-rUS 会让
        # stringResource 解析成英文而断言失败。
        for needle in ('@Config(qualifiers = "zh-rCN-w320dp-h480dp")',
                       'presetMoreOpensActionMenuOnSmallScreenWithoutEditingOrCopyAction',
                       'presetDetailHidesGlobalSwitchesAndProfileEditsPreserveAutomaticDelegation',
                       'conversationScopeAppliesFromDetailAndTogglesDelegationWithoutTouchingPreset',
                       '重命名组', 'assertFalse("removing the switch must not replace the stored enabled value", saved.enabled)'):
            self.assertIn(needle, settings)

    def test_detail_is_a_real_route_and_presets_cannot_run(self):
        shell = self.source('ui/SubAgentSettingsScreen.kt')
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        route = self.source('ui/navigation/AppRoute.kt')
        table = self.source('ui/navigation/AgentNavigator.kt')
        root = self.source('ui/app/AgentAppRoot.kt')
        # 详情页是独立导航目标，才能复用导航容器的滑动转场与预测性返回。
        self.assertIn('data class SubAgentPresetDetail(val presetId: String) : AppRoute', route)
        self.assertIn('AppRoute.SubAgentPresetDetail::class', table)
        for needle in ('onOpenPreset = { presetId -> pushRoute(AppRoute.SubAgentPresetDetail(presetId)) }',
                       'entry<AppRoute.SubAgentPresetDetail>(swipeDismiss = navSwipeDismiss) { route ->',
                       'SubAgentPresetDetailScreen(',
                       'isCurrentRoute = currentRoute == route'):
            self.assertIn(needle, root)
        self.assertNotIn('selectedId', shell)
        self.assertNotIn('BackHandler', shell)
        self.assertIn('isCurrentRoute: Boolean = true', detail)
        self.assertIn('enabled = isCurrentRoute', detail)
        state = self.source('ui/app/AgentAppState.kt')
        self.assertIn('is SubAgentConfigKey.Preset -> error("预设不能作为会话运行配置")', state)

    def test_parallel_limit_wording_fits_preset_and_conversation_without_layout_change(self):
        text = self.source('ui/components/SubAgentParallelLimitRow.kt')
        # 文案已外置到资源；范围说明按「本配置」表述，不再出现会话级措辞。
        self.assertIn('R.string.sap_parallel_shared_note', text)
        self.assertIn('R.string.sap_parallel_set_for', text)
        self.assertNotIn('同一会话下', text)
        strings = (ROOT.parents[4] / 'res/values/strings.xml').read_text()
        note = next(line for line in strings.splitlines() if 'name="sap_parallel_shared_note"' in line)
        self.assertIn('within this config', note)
        self.assertNotIn('conversation', note)

    def test_conversation_scope_is_editable_from_the_settings_pages(self):
        # 会话级配置（应用预设组、自动委派开关、会话级代理行）收进设置页两跳：
        # 列表页「本会话」区块 + 详情页「应用此组到本会话」，不再有独立协作页。
        shell = self.source('ui/SubAgentSettingsScreen.kt')
        detail = self.source('ui/SubAgentPresetDetailScreen.kt')
        for needle in ('LocalConversationSubAgentEditor.current', 'conversationEditor?.scoped(',
                       'R.string.sas_conversation_title', 'R.string.sas_applied_prefix',
                       'R.string.sas_conversation_unapplied', 'R.string.sas_conversation_unapplied_hint',
                       'panelEditor?.setEnabled(', 'SubAgentProfileRow(',
                       'SubAgentProfileConfigDialog(', 'R.string.sas_conversation_tap_hint',
                       'R.string.sas_applied_marker'):
            self.assertIn(needle, shell)
        for needle in ('conversationEditor = LocalConversationSubAgentEditor.current',
                       'applyPreset(presetId)', 'R.string.sas_apply_to_conversation',
                       'R.string.sas_applied_to_conversation', 'R.string.sas_apply_failed'):
            self.assertIn(needle, detail)
        # 自动委派开关只属于列表页的本会话区块：详情页仍然不承载任何开关。
        self.assertNotIn('setEnabled(', detail)
        # 会话级编辑不新建编辑器：写入口只有 App 级编辑器及其应用绑定副本。
        self.assertNotIn('ConversationSubAgentEditor(', shell)
        strings = (ROOT.parents[4] / 'res/values/strings.xml').read_text()
        for key in ('sas_conversation_title', 'sas_applied_prefix', 'sas_applied_marker',
                    'sas_conversation_unapplied', 'sas_conversation_unapplied_hint',
                    'sas_conversation_readonly', 'sas_auto_delegation', 'sas_auto_delegation_desc',
                    'sas_conversation_tap_hint', 'sas_apply_to_conversation',
                    'sas_applied_to_conversation', 'sas_apply_failed'):
            self.assertIn('name="%s"' % key, strings)


if __name__ == '__main__':
    unittest.main()
