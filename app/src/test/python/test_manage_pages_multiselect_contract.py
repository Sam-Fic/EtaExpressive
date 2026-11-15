"""五个管理页（工具配方 / MCP 服务器 / 豆包音色 / 远程设备 / 技能）多选改造的结构守卫。

锁的用户可感知语义（与三聊天管理页同一套范式）：
- 底部操作栏与入口均由共享套件 ui/adaptive/EtaSelectionKit.kt 装配（药丸装配、
  Checklist→Close 原位切换、进退场等由 test_selection_kit_contract.py 锁死）；
  页面只负责：引用共享件 + 传入动作组 + 确认弹窗 + 全选口径；
- 长按行进入多选；多选态行尾换官方 Checkbox；「已选 n 项」并入分组标题；
- 动作组：工具配方 = 全选 + 批量启停 + 删除；MCP = 全选 + 删除；
  豆包音色 = 全选 + 移除本地记录（多选态播放按钮让位）；
  远程设备 = 全选 + 删除（多选态状态点与删除按钮让位）；
  技能 = 全选 + 批量启停 + 删除（勾选集混入内置技能时删除禁用——内置只能移除重装）；
- 删除执行前另有确认弹窗；批量执行走既有单条 API 循环，不新建批量 API；
- FAB 在多选态隐藏（配方导入 / MCP 新增 / 远程设备新增 / 技能导入）；
- 返回键退出多选不退页面（豆包只在 home 态接管）。
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
UI = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui'
RES = ROOT / 'app/src/main/res'


class MultiSelectPagesContractTest(unittest.TestCase):
    def setUp(self):
        self.sources = {
            'recipes': (UI / 'screens/tools/ToolRecipesScreen.kt').read_text(encoding='utf-8'),
            'mcp': (UI / 'screens/mcp/McpServersScreen.kt').read_text(encoding='utf-8'),
            'doubao': (UI / 'DoubaoVoiceSettings.kt').read_text(encoding='utf-8'),
            'remote': (UI / 'screens/settings/RemoteAgentsScreen.kt').read_text(encoding='utf-8'),
            'skills': (UI / 'screens/skills/AgentSkillsScreen.kt').read_text(encoding='utf-8'),
            'skill_row': (UI / 'screens/skills/SkillSwitchRow.kt').read_text(encoding='utf-8'),
            'skills_state': (UI / 'app/AgentAppState.kt').read_text(encoding='utf-8'),
        }

    def _bar(self, source):
        # 每页至多一处 EtaSelectionBar( 调用；取调用点之后的动作组切片。
        return source.split('EtaSelectionBar(', 1)[1]

    def test_entry_toggle_delegates_to_shared_top_bar_action(self):
        # 顶栏入口统一走共享件（Checklist→Close 原位切换由套件契约锁死）。
        for name in ('recipes', 'mcp', 'doubao', 'remote', 'skills'):
            with self.subTest(page=name):
                self.assertIn('EtaSelectionTopBarAction(', self.sources[name])

    def test_long_press_and_checkbox(self):
        rows = {
            'recipes': self.sources['recipes'],
            'mcp': self.sources['mcp'],
            'doubao': self.sources['doubao'],
            'remote': self.sources['remote'],
            'skill_row': self.sources['skill_row'],
        }
        for name, source in rows.items():
            with self.subTest(page=name):
                self.assertIn('onLongClickLabel = stringResource(R.string.action_enter_selection)', source)
        # 多选态行尾换官方 Checkbox（技能在行组件 SkillSwitchRow 内）。
        for name in ('recipes', 'mcp', 'doubao', 'remote', 'skill_row'):
            self.assertIn('Checkbox(', rows[name])

    def test_bar_is_shared_kit_pill(self):
        bars = ('recipes', 'mcp', 'doubao', 'remote', 'skills')
        for name in bars:
            with self.subTest(page=name):
                self.assertIn('EtaSelectionBar(', self.sources[name])
                self.assertIn('EtaSelectionBarOverlay(', self.sources[name])

    def test_bar_actions_per_page(self):
        # 工具配方：全选 + 批量启停（ToggleOn/ToggleOff 自适应，口径=勾选集）+ 删除。
        bar = self._bar(self.sources['recipes'])
        self.assertIn('if (allEnabled) Icons.Rounded.ToggleOff else Icons.Rounded.ToggleOn', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        self.assertIn('showBatchDeleteDialog = true', self.sources['recipes'])
        # MCP：全选 + 删除（无启停）。
        bar = self._bar(self.sources['mcp'])
        self.assertNotIn('ToggleOn', self.sources['mcp'])
        self.assertIn('Icons.Rounded.Delete', bar)
        self.assertIn('showBatchDeleteDialog = true', self.sources['mcp'])
        # 豆包音色：全选 + 移除本地记录（无启停、无彻底删除）。
        bar = self._bar(self.sources['doubao'])
        self.assertNotIn('ToggleOn', self.sources['doubao'])
        self.assertNotIn('Icons.Rounded.DeleteForever', self.sources['doubao'])
        self.assertIn('showBatchRemoveDialog = true', self.sources['doubao'])
        # 远程设备：全选 + 删除。
        bar = self._bar(self.sources['remote'])
        self.assertNotIn('ToggleOn', self.sources['remote'])
        self.assertIn('Icons.Rounded.Delete', bar)
        self.assertIn('showBatchDeleteDialog = true', self.sources['remote'])
        # 技能：全选 + 批量启停 + 删除；混选内置技能时删除禁用（内置只能移除重装）。
        bar = self._bar(self.sources['skills'])
        self.assertIn(
            'if (selectedAllEnabled) Icons.Rounded.ToggleOff else Icons.Rounded.ToggleOn', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        self.assertIn('selectedAllDeletable', bar)
        self.assertIn('showBatchDeleteDialog = true', self.sources['skills'])

    def test_skills_delete_eligibility_separates_builtin(self):
        # 删除资格：选中项必须全部满足 canDeleteUserSkill（user 源且已安装）。
        source = self.sources['skills']
        self.assertIn('selectedAllDeletable = selectedSkills.all { it.canDeleteUserSkill }', source)
        # 已移除组不参与多选：多选态整组隐藏（对齐分类页隐藏远程设备组的先例）。
        self.assertIn('if (!selectionMode)', source)
        self.assertIn('item(key = "removed-card")', source)

    def test_select_all_scope(self):
        # 全选以当前列表全集为口径。
        self.assertIn('sortedRecipes.map { it.name }.toSet()', self.sources['recipes'])
        self.assertIn('servers.map { it.id }.toSet()', self.sources['mcp'])
        self.assertIn('batchVoices.map { voiceKey(it) }.toSet()', self.sources['doubao'])
        self.assertIn('servers.map { it.id }.toSet()', self.sources['remote'])
        self.assertIn('selectableSkills.map { it.id }.toSet()', self.sources['skills'])

    def test_batch_delete_goes_through_existing_apis(self):
        # MCP 批量删除逐条走既有 delete()（保留 secret 清理与失败回滚），不新建批量 API。
        confirm = self.sources['mcp'].split('if (showBatchDeleteDialog) {', 1)[1]
        self.assertIn('McpServerRepository.delete(id)', confirm)
        # 远程设备批量删除走整表覆写。
        confirm = self.sources['remote'].split('if (showBatchDeleteDialog) {', 1)[1]
        self.assertIn('persist(servers.filterNot { it.id in selectedIds })', confirm)
        # 配方批量删除走 runtime.delete 循环。
        confirm = self.sources['recipes'].split('if (showBatchDeleteDialog) {', 1)[1]
        self.assertIn('selectedIds.forEach { name -> runtime.delete(name) }', confirm)
        # 豆包批量移除走 PersonalVoices.removeLocal 循环。
        confirm = self.sources['doubao'].split('if (showBatchRemoveDialog) {', 1)[1]
        self.assertIn('PersonalVoices.removeLocal(voice)', confirm)
        # 技能批量删除/启停走 state 层批量 Action（内部逐条 runCatching，不新建批量 API）。
        self.assertIn('AgentSkillsAction.DeleteSkills(selectedIds)', self.sources['skills'])
        self.assertIn('AgentSkillsAction.ToggleSkills(', self.sources['skills'])
        state = self.sources['skills_state']
        self.assertIn('fun deleteSkills(skillIds: Set<String>)', state)
        self.assertIn('fun toggleSkills(skillIds: Set<String>, enabled: Boolean)', state)
        self.assertIn('isBatchBusy', state)

    def test_fab_hidden_in_selection_mode(self):
        # 配方导入 / MCP 新增 / 远程设备新增 / 技能导入四个 FAB 多选态隐藏。
        self.assertIn('floatingActionButton = if (!selectionMode)', self.sources['recipes'])
        self.assertIn('floatingActionButton = if (!selectionMode)', self.sources['mcp'])
        self.assertIn('floatingActionButton = if (!selectionMode)', self.sources['remote'])
        self.assertIn('floatingActionButton = if (!selectionMode)', self.sources['skills'])

    def test_doubao_selection_confined_to_home(self):
        # 多选只属于首页「我的音色」列表：离开首页自动退出；返回键只在 home 态接管。
        self.assertIn('isBackEnabled = home && selectionMode', self.sources['doubao'])
        self.assertIn('if (!home && selectionMode) exitSelection()', self.sources['doubao'])
        # 多选态播放按钮让位，只剩 Checkbox。
        row = self.sources['doubao'].split('private fun PersonalVoiceRow(', 1)[1]
        self.assertIn('if (selectionMode) {', row)
        self.assertIn('// 多选态：播放按钮让位，行尾只剩官方 Checkbox。', row)

    def test_back_exits_selection_not_page(self):
        # 豆包页是特例：返回键只在 home 态接管多选（其余返回路径是内部页层级导航）。
        for name in ('recipes', 'mcp', 'doubao', 'remote', 'skills'):
            with self.subTest(page=name):
                source = self.sources[name]
                if name == 'doubao':
                    self.assertIn('isBackEnabled = home && selectionMode', source)
                else:
                    self.assertIn('isBackEnabled = selectionMode', source)
                self.assertIn('rememberNavigationEventState(NavigationEventInfo.None)', source)

    def test_selection_strings_complete_in_all_locales(self):
        keys = (
            'media_selected_count', 'media_action_multiselect', 'media_action_select_all',
            'media_action_deselect_all', 'action_enter_selection', 'action_cancel',
            'recipe_disable', 'recipe_batch_delete_message', 'mcp_batch_delete_message',
            'dv_batch_target', 'dv_batch_removed', 'remote_agents_batch_delete_message',
            'skills_batch_delete_message',
        )
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            text = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
            for key in keys:
                self.assertIn(f'name="{key}"', text, f'{locale} 缺 {key}')


if __name__ == '__main__':
    unittest.main()
