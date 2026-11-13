"""Source wiring guards only: no Kotlin compile, real-device rendering or ripple capture."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
SOURCE = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ProviderModelsTab.kt'
RES = ROOT / 'app/src/main/res'


class ProviderModelsMultiselectContractTest(unittest.TestCase):
    """Provider 模型 tab 多选交互的结构守卫（对齐自然语言搜索文件页同款装配，用户拍板）。

    锁的用户可感知语义：
    - 多选入口原位切换：标题行右侧图标 Checklist→Close 原位变「取消」，退出并清空勾选；
      「取消」不落底栏（与参考页一致，勿改回底栏取消）；
    - 长按模型行进入多选并勾中该行；多选态点按/长按都是勾选切换，非多选态点按=设当前；
    - 「已选 n 项」计数并入标题行（点号隔开），不另立计数行；
    - 批量操作栏是官方 M3E HorizontalFloatingToolbar 纯图标药丸（全选/删除），底部居中悬浮；
    - 全选/全不选以当前搜索过滤后的列表为全集；
    - 返回键退出多选不退页面。"""
    def setUp(self):
        self.source = SOURCE.read_text(encoding='utf-8')

    def test_selection_state_and_in_place_entry_toggle(self):
        self.assertIn('var selectionMode by remember(provider.id) { mutableStateOf(false) }', self.source)
        self.assertIn('var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }', self.source)
        # 入口原位切换：Checklist→Close，取消即退出并清空勾选（不落底栏）。
        self.assertIn(
            'icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,', self.source)
        self.assertIn(
            'if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,', self.source)
        entry = self.source.split('icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,', 1)[1]
        exit_branch = entry.split('if (selectionMode) {', 1)[1].split('} else {', 1)[0]
        self.assertIn('selectionMode = false', exit_branch)
        self.assertIn('selectedModelIds = emptySet()', exit_branch)
        # 取消不落底栏：操作栏没有取消动作（KDoc 明示 + 栏内无 Close 图标）。
        self.assertIn('「取消」不落\n * 底栏', self.source)

    def test_long_press_enters_selection_and_row_toggles(self):
        # 官方 SegmentedListItem 一次覆盖点按、长按与多选。
        self.assertIn('onLongClick = onLongClick,', self.source)
        self.assertIn('onLongClickLabel = stringResource(R.string.action_enter_selection)', self.source)
        # 长按进多选并勾中该行；多选态下点按/长按都是勾选切换。
        enter = self.source.split('onEnterSelection = {', 1)[1].split('}', 1)[0]
        self.assertIn('selectionMode = true', enter)
        self.assertIn('selectedModelIds = setOf(model.id)', enter)
        toggle = self.source.split('onToggleChecked = {', 1)[1].split('}()', 1)[0]
        self.assertIn('selectedModelIds - model.id', toggle)
        self.assertIn('selectedModelIds + model.id', toggle)
        # 同一行构建器里：点按=切换勾选/设当前，长按=切换勾选/进多选。
        click = self.source.split('if (selectionMode) onToggleChecked() else onSetCurrent()', 1)[1].split('SegmentedListItem(', 1)[0]
        self.assertIn('if (selectionMode) onToggleChecked() else onEnterSelection()', click)

    def test_selected_count_merges_into_section_title(self):
        title = self.source.split('item(key = "models_title"', 1)[1].split('EtaIconButton(', 1)[0]
        self.assertIn('if (selectionMode)', title)
        self.assertIn('modelListTitle + " · " +', title)
        self.assertIn('stringResource(R.string.media_selected_count, selectedModelIds.size)', title)

    def test_selection_bar_is_official_floating_toolbar(self):
        # 官方 M3E HorizontalFloatingToolbar 纯图标药丸，底部居中悬浮（与搜索页同款装配）。
        self.assertIn('HorizontalFloatingToolbar(', self.source)
        bar = self.source.split('private fun ModelSelectionBar(', 1)[1]
        self.assertIn('expanded = true,', bar)
        self.assertIn('leadingContent = {},', bar)
        self.assertIn('EtaFloatingToolbarConcentricPadding(', bar)
        # 全选（全不选）与删除两枚动作；删除执行前另有确认弹窗。
        self.assertIn('if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll', bar)
        self.assertIn('showBatchDeleteDialog = true', self.source)

    def test_select_all_scoped_to_filtered_list(self):
        # 「全选/全不选」以当前搜索过滤后的列表为全集，不清掉被搜索滤掉的勾选。
        self.assertIn(
            'val allModelsSelected = filteredModels.isNotEmpty() &&\n'
            '        selectedModelIds.containsAll(filteredModelIds)', self.source)
        self.assertIn(
            'val filteredModelIds = remember(filteredModels) '
            '{ filteredModels.mapTo(mutableSetOf()) { it.id } }', self.source)
        select_all = self.source.split('onSelectAll = {', 1)[1].split('onSelectAll,', 1)[0]
        self.assertIn('filteredModels.mapTo(mutableSetOf()) { it.id }', select_all)

    def test_back_exits_selection_not_page(self):
        self.assertIn('isBackEnabled = selectionMode', self.source)
        back = self.source.split('onBackCompleted = {', 1)[1].split('},', 1)[0]
        self.assertIn('selectionMode = false', back)
        self.assertIn('selectedModelIds = emptySet()', back)

    def test_selection_strings_complete_in_all_locales(self):
        keys = ('media_selected_count', 'media_action_multiselect', 'media_action_select_all',
                'media_action_deselect_all', 'action_enter_selection', 'action_cancel')
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            text = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
            for key in keys:
                self.assertIn(f'name="{key}"', text, f'{locale} 缺 {key}')


if __name__ == '__main__':
    unittest.main()
