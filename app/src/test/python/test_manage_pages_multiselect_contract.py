"""四个管理页（工具配方 / MCP 服务器 / 豆包音色 / 远程设备）多选改造的结构守卫。

锁的用户可感知语义（与三聊天管理页同一套范式）：
- 顶栏多选入口原位切换 Checklist→Close，退出并清空勾选；「取消」不落底栏；
- 长按行进入多选；多选态行尾换官方 Checkbox；「已选 n 项」并入分组标题；
- 批量操作栏是官方 M3E HorizontalFloatingToolbar 纯图标药丸（底部居中悬浮）：
  工具配方 = 全选 + 批量启停 + 删除；MCP = 全选 + 删除；
  豆包音色 = 全选 + 移除本地记录（多选态播放按钮让位）；
  远程设备 = 全选 + 删除（多选态状态点与删除按钮让位）；
- 删除执行前另有确认弹窗；
- FAB 在多选态隐藏（配方导入 / MCP 新增 / 远程设备新增）；
- 返回键退出多选不退页面。
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
        }

    def _bar(self, source, bar_name):
        return source.split(f'private fun {bar_name}(', 1)[1]

    def test_entry_toggle_in_place(self):
        for name, source in self.sources.items():
            with self.subTest(page=name):
                self.assertIn(
                    'icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,',
                    source,
                )
                self.assertIn(
                    'if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,',
                    source,
                )

    def test_long_press_and_checkbox(self):
        for name, source in self.sources.items():
            with self.subTest(page=name):
                self.assertIn('onLongClickLabel = stringResource(R.string.action_enter_selection)', source)
                self.assertIn('Checkbox(', source)

    def test_floating_toolbar_official(self):
        bars = (
            ('recipes', 'RecipeSelectionBar'),
            ('mcp', 'McpSelectionBar'),
            ('doubao', 'VoiceSelectionBar'),
            ('remote', 'RemoteAgentSelectionBar'),
        )
        for name, bar_name in bars:
            with self.subTest(page=name):
                source = self.sources[name]
                self.assertIn('HorizontalFloatingToolbar(', source)
                bar = self._bar(source, bar_name)
                self.assertIn('expanded = true,', bar)
                self.assertIn('leadingContent = {},', bar)
                self.assertIn('EtaFloatingToolbarConcentricPadding(', bar)
                self.assertIn(
                    'if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll', bar)

    def test_bar_actions_per_page(self):
        # 工具配方：全选 + 批量启停（ToggleOn/ToggleOff 自适应）+ 删除。
        bar = self._bar(self.sources['recipes'], 'RecipeSelectionBar')
        self.assertIn('if (allEnabled) Icons.Rounded.ToggleOff else Icons.Rounded.ToggleOn', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        self.assertIn('showBatchDeleteDialog = true', self.sources['recipes'])
        # MCP：全选 + 删除（无启停）。
        bar = self._bar(self.sources['mcp'], 'McpSelectionBar')
        self.assertNotIn('ToggleOn', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        self.assertIn('showBatchDeleteDialog = true', self.sources['mcp'])
        # 豆包音色：全选 + 移除本地记录（无启停、无彻底删除）。
        bar = self._bar(self.sources['doubao'], 'VoiceSelectionBar')
        self.assertNotIn('ToggleOn', bar)
        self.assertNotIn('Icons.Rounded.DeleteForever', bar)
        self.assertIn('showBatchRemoveDialog = true', self.sources['doubao'])
        # 远程设备：全选 + 删除。
        bar = self._bar(self.sources['remote'], 'RemoteAgentSelectionBar')
        self.assertNotIn('ToggleOn', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        self.assertIn('showBatchDeleteDialog = true', self.sources['remote'])

    def test_select_all_scope(self):
        # 全选以当前列表全集为口径。
        self.assertIn('sortedRecipes.map { it.name }.toSet()', self.sources['recipes'])
        self.assertIn('servers.map { it.id }.toSet()', self.sources['mcp'])
        self.assertIn('batchVoices.map { voiceKey(it) }.toSet()', self.sources['doubao'])
        self.assertIn('servers.map { it.id }.toSet()', self.sources['remote'])

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

    def test_fab_hidden_in_selection_mode(self):
        # 配方导入 / MCP 新增 / 远程设备新增三个 FAB 多选态隐藏。
        self.assertIn('floatingActionButton = if (!selectionMode)', self.sources['recipes'])
        self.assertIn('floatingActionButton = if (!selectionMode)', self.sources['mcp'])
        self.assertIn('floatingActionButton = if (!selectionMode)', self.sources['remote'])

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
        for name, source in self.sources.items():
            with self.subTest(page=name):
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
        )
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            text = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
            for key in keys:
                self.assertIn(f'name="{key}"', text, f'{locale} 缺 {key}')


if __name__ == '__main__':
    unittest.main()
