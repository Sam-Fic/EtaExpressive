"""三管理页（聊天/回收站/分类）多选改造的结构守卫：对齐模型管理页与媒体语义搜索页同款装配。

锁的用户可感知语义：
- 顶栏只剩「搜索 + 多选」两个图标入口；多选入口原位切换 Checklist→Close，退出并清空勾选；
  「清空」按钮由「多选 → 全选 → 删除」替代，顶栏不再有清空/删除图标；
- 长按行进入多选并勾中该行；多选态点按/长按都是勾选切换，行尾换官方 Checkbox；
- 「已选 n 项」计数并入标题行（点号隔开），不另立计数行；
- 批量操作栏是官方 M3E HorizontalFloatingToolbar 纯图标药丸（底部居中悬浮）：
  管理聊天 = 全选 + 置顶/取消置顶 + 删除；回收站 = 全选 + 恢复 + 彻底删除；
  分类 = 全选 + 删除；删除执行前另有确认弹窗；「取消」不落底栏；
- 全选以可操作列表为全集：管理聊天不含调度台系统条目；
- 分类页多选态隐藏远程设备组与新建分类 FAB（多选不管理远程设备）；
- 返回键退出多选不退页面。
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
CHAT = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/screens/chat'
RES = ROOT / 'app/src/main/res'


class ChatManageMultiselectContractTest(unittest.TestCase):
    def setUp(self):
        self.chats = (CHAT / 'ManageChatsScreen.kt').read_text(encoding='utf-8')
        self.trash = (CHAT / 'TrashScreen.kt').read_text(encoding='utf-8')
        self.folders = (CHAT / 'ManageFoldersScreen.kt').read_text(encoding='utf-8')

    # ---- 顶栏入口：三页统一 ----

    def test_top_bar_has_search_and_multiselect_only(self):
        # 管理聊天 / 回收站顶栏 = 搜索 + 多选；分类页原本没有搜索入口，只有多选。
        for name, source in (('管理聊天', self.chats), ('回收站', self.trash)):
            with self.subTest(page=name):
                actions = source.split('actions = {', 1)[1].split('},', 1)[0]
                self.assertIn('Icons.Rounded.Search', actions)
                self.assertIn(
                    'icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,',
                    source,
                )
                self.assertIn(
                    'if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,',
                    source,
                )
                # 旧清空按钮退场：顶栏不再有 DeleteSweep 图标。
                self.assertNotIn('Icons.Rounded.DeleteSweep', source)
        folders_actions = self.folders.split('actions = {', 1)[1].split('},', 1)[0]
        self.assertNotIn('Icons.Rounded.Search', folders_actions)
        self.assertIn('Icons.Rounded.Checklist', folders_actions)
        self.assertNotIn('Icons.Rounded.DeleteSweep', self.folders)

    def test_delete_all_wiring_removed(self):
        # 管理聊天不再接 onDeleteAll；回收站不再接 onEmptyTrash（「清空」由全选删除替代）。
        root = (ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppRoot.kt').read_text(encoding='utf-8')
        entry = root.split('entry<AppRoute.ManageChats>', 1)[1].split('entry<AppRoute.ManageFolders>', 1)[0]
        self.assertNotIn('onDeleteAll', entry)
        trash_entry = root.split('entry<AppRoute.ConversationTrash>', 1)[1].split('entry<AppRoute.ConversationTrashSearch>', 1)[0]
        self.assertNotIn('onEmptyTrash', trash_entry)
        shell = (ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppShell.kt').read_text(encoding='utf-8')
        self.assertNotIn('onEmptyTrash', shell)

    # ---- 多选交互：三页统一 ----

    def test_row_selection_semantics(self):
        for name, source in (('管理聊天', self.chats), ('回收站', self.trash), ('分类', self.folders)):
            with self.subTest(page=name):
                # 长按进多选并勾中该行。
                self.assertIn('onLongClickLabel = stringResource(R.string.action_enter_selection)', source)
                self.assertIn('selectionMode = true', source)
                # 多选态行尾官方 Checkbox。
                self.assertIn('Checkbox(', source)

    def test_selected_count_merges_into_title(self):
        for name, source in (('管理聊天', self.chats), ('回收站', self.trash), ('分类', self.folders)):
            with self.subTest(page=name):
                self.assertIn('stringResource(R.string.media_selected_count, selectedIds.size)', source)

    # ---- 批量操作栏：官方浮动工具条 ----

    def test_selection_bar_is_official_floating_toolbar(self):
        bars = (
            ('ChatSelectionBar', self.chats),
            ('TrashSelectionBar', self.trash),
            ('FolderSelectionBar', self.folders),
        )
        for bar_name, source in bars:
            with self.subTest(bar=bar_name):
                self.assertIn('HorizontalFloatingToolbar(', source)
                bar = source.split(f'private fun {bar_name}(', 1)[1]
                self.assertIn('expanded = true,', bar)
                self.assertIn('leadingContent = {},', bar)
                self.assertIn('EtaFloatingToolbarConcentricPadding(', bar)
                self.assertIn(
                    'if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll', bar)

    def test_bar_actions_per_page(self):
        # 管理聊天：全选 + 置顶/取消置顶 + 删除。
        bar = self.chats.split('private fun ChatSelectionBar(', 1)[1]
        self.assertIn('if (allPinned) Icons.Rounded.LabelOff else Icons.Rounded.PushPin', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        # 回收站：全选 + 恢复 + 彻底删除。
        bar = self.trash.split('private fun TrashSelectionBar(', 1)[1]
        self.assertIn('Icons.Rounded.RestoreFromTrash', bar)
        self.assertIn('Icons.Rounded.DeleteForever', bar)
        # 分类：全选 + 删除（没有置顶/恢复）。
        bar = self.folders.split('private fun FolderSelectionBar(', 1)[1]
        self.assertNotIn('Icons.Rounded.PushPin', bar)
        self.assertNotIn('Icons.Rounded.RestoreFromTrash', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        # 删除都走确认弹窗。
        self.assertIn('showBatchDeleteDialog = true', self.chats)
        self.assertIn('showBatchPurgeDialog = true', self.trash)
        self.assertIn('showBatchDeleteDialog = true', self.folders)

    def test_select_all_scoped_correctly(self):
        # 管理聊天全选不含调度台系统条目。
        self.assertIn('ordered.filter { !it.isDispatchConsole }', self.chats)
        # 回收站 / 分类全选以整表为全集。
        self.assertIn('trash.map { it.id }.toSet()', self.trash)
        self.assertIn('folders.map { it.id }.toSet()', self.folders)

    # ---- 分类页：多选不管理远程设备 ----

    def test_folders_multiselect_hides_remote_devices_and_fab(self):
        # 远程设备组在多选态整组隐藏。
        guarded = self.folders.split('// 多选态不管理远程设备', 1)[1]
        self.assertIn('if (!selectionMode) {', guarded)
        self.assertIn('item(key = "remote-servers")', guarded)
        # 新建分类 FAB 多选态隐藏。
        fab = self.folders.split('floatingActionButton =', 1)[1].split('actions = {', 1)[0]
        self.assertIn('if (!selectionMode)', fab)

    # ---- 返回键退出多选：三页统一 ----

    def test_back_exits_selection_not_page(self):
        for name, source in (('管理聊天', self.chats), ('回收站', self.trash), ('分类', self.folders)):
            with self.subTest(page=name):
                self.assertIn('isBackEnabled = selectionMode', source)
                self.assertIn('rememberNavigationEventState(NavigationEventInfo.None)', source)

    # ---- 字符串三 locale 齐全 ----

    def test_selection_strings_complete_in_all_locales(self):
        keys = (
            'media_selected_count', 'media_action_multiselect', 'media_action_select_all',
            'media_action_deselect_all', 'action_enter_selection', 'action_cancel',
            'drawer_trash_batch_purge_message', 'folders_page_batch_delete_message',
        )
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            text = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
            for key in keys:
                self.assertIn(f'name="{key}"', text, f'{locale} 缺 {key}')


if __name__ == '__main__':
    unittest.main()
