"""管理页（聊天+分类合并，树形折叠）与回收站多选改造的结构守卫：对齐模型管理页与媒体语义搜索页同款装配。

锁的用户可感知语义：
- 「管理聊天」与「管理分类」合并为单页「管理」（AppRoute.ManageChats），ManageFolders 路由整体移除；
- 页面 = 调度台独立置顶行 + 每分类一张折叠卡（EtaSegmentedColumn 的 item(visible) spring 显隐
  + EtaExpandChevron 连续旋转）+ 「未分类」折叠卡 + 远程设备摘要行 + 新建分类 FAB；
- 会话行尾一律三点菜单（EtaTextMenu），条目与侧栏长按菜单完全对齐：
  重命名 / 置顶·取消置顶 / 导出 / 导出分析 / 移动到分类 / 移入回收站（调度台只保留前三项）；
  菜单只从三点按钮开，长按行固定进入多选；置顶状态用行尾静态 PushPin 指示；
- 单条菜单动作走宿主共享弹窗/导出流程（与抽屉长按菜单同一条链路），页面不复制 busy 守卫；
- 顶栏只剩「搜索 + 多选」两个图标入口；多选入口原位切换 Checklist→Close，退出并清空勾选；
- 长按行进入多选并勾中该行；多选态点按/长按都是勾选切换，行尾换官方 Checkbox；
  selectionTarget 区分会话/分类两组互斥勾选（会话跨折叠组，分类勾父行批量删除）；
- 「已选 n 项」计数并入标题行（点号隔开），不另立计数行；
- 批量操作栏是共享多选套件（ui/adaptive/EtaSelectionKit.kt）的官方 M3E 浮动图标药丸
  （底部居中悬浮；套件装配由 test_selection_kit_contract.py 锁死）：
  管理页会话组 = 全选 + 置顶/取消置顶 + 删除；分类组 = 全选 + 删除；
  回收站 = 全选 + 恢复 + 彻底删除；删除执行前另有确认弹窗；
- 全选以可操作列表为全集：会话组不含调度台系统条目，分类组以全部分类为全集；
- 管理页多选态隐藏远程设备组与新建分类 FAB（多选不管理远程设备）；折叠期间仍可操作；
- 返回键退出多选不退页面。
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[4]
CHAT = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/screens/chat'
APP = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/app'
NAV = ROOT / 'app/src/main/kotlin/io/github/mangi/eta/ui/navigation'
RES = ROOT / 'app/src/main/res'


def compact(source: str) -> str:
    return ''.join(source.split())


class ChatManageMultiselectContractTest(unittest.TestCase):
    def setUp(self):
        self.chats = (CHAT / 'ManageChatsScreen.kt').read_text(encoding='utf-8')
        self.trash = (CHAT / 'TrashScreen.kt').read_text(encoding='utf-8')
        self.root = (APP / 'AgentAppRoot.kt').read_text(encoding='utf-8')
        self.shell = (APP / 'AgentAppShell.kt').read_text(encoding='utf-8')
        self.chats_compact = compact(self.chats)
        self.root_compact = compact(self.root)

    # ---- 路由合并：ManageFolders 整体移除 ----

    def test_manage_folders_route_removed(self):
        route = (NAV / 'AppRoute.kt').read_text(encoding='utf-8')
        navigator = (NAV / 'AgentNavigator.kt').read_text(encoding='utf-8')
        frame = (APP / 'FrameDiagnosticRoute.kt').read_text(encoding='utf-8')
        for name, source in (
            ('AppRoute', route), ('AgentNavigator', navigator), ('AgentAppRoot', self.root),
            ('AgentAppShell', self.shell), ('FrameDiagnosticRoute', frame),
        ):
            self.assertNotIn('ManageFolders', source, f'{name} 仍引用已删除的 ManageFolders 路由')

    def test_delete_all_wiring_removed(self):
        # 管理页不再接 onDeleteAll；回收站不再接 onEmptyTrash（「清空」由全选删除替代）。
        entry = self.root.split('entry<AppRoute.ManageChats>', 1)[1].split('entry<AppRoute.ConversationTrash>', 1)[0]
        self.assertNotIn('onDeleteAll', entry)
        trash_entry = self.root.split('entry<AppRoute.ConversationTrash>', 1)[1].split('entry<AppRoute.ConversationTrashSearch>', 1)[0]
        self.assertNotIn('onEmptyTrash', trash_entry)
        self.assertNotIn('onEmptyTrash', self.shell)

    def test_manage_entry_wires_folders_and_shared_actions(self):
        # 管理页 entry 扩参：分类增删改 + 远程智能体 + 会话菜单动作全部接线；
        # 单条菜单动作复用宿主共享弹窗/导出流程（与抽屉长按菜单同一条链路）。
        entry = self.root.split('entry<AppRoute.ManageChats>', 1)[1].split('entry<AppRoute.ConversationTrash>', 1)[0]
        for needle in (
            'folders = agentState.conversationPaneState.folders',
            'remoteCoordinator = appViewModel.remoteCoordinator',
            'onCreateFolder', 'onRenameFolder', 'onDeleteFolder', 'onOpenRemoteAgents',
            'onRequestDeleteConversation = requestConversationDelete',
            'onRenameConversation = requestConversationRename',
            'onExportConversation = exportConversation',
            'onExportConversationAnalysis = exportConversationAnalysis',
            'onMoveConversationToFolder = requestMoveConversationToFolder',
        ):
            self.assertIn(needle, entry, f'ManageChats entry 缺 {needle}')
        # 宿主共享动作流只有一份定义，抽屉与管理页两条入口链路指向同一份目标状态。
        for needle in (
            'requestConversationRename: (ConversationSummaryUi) -> Unit = { conversationRenameTarget = it }',
            'requestConversationDelete: (ConversationSummaryUi) -> Unit = { conversationDeleteTarget = it }',
            'requestMoveConversationToFolder: (ConversationSummaryUi) -> Unit = { conversationMoveTarget = it }',
        ):
            self.assertIn(needle, self.root, f'宿主缺共享动作流 {needle}')
        # 导出 busy 守卫不随入口复制：抽屉与管理页共用同一份 lambda。
        self.assertEqual(self.root_compact.count('conversationExportConfirmation=true'), 1)
        self.assertEqual(self.root_compact.count('conversationAnalysisExportOptions=true'), 1)
        self.assertIn('onConversationRename = requestConversationRename', self.root)
        self.assertIn('onConversationExport = exportConversation', self.root)

    # ---- 顶栏入口：两页统一 ----

    def test_top_bar_has_search_and_multiselect_only(self):
        for name, source in (('管理页', self.chats), ('回收站', self.trash)):
            with self.subTest(page=name):
                actions = source.split('actions = {', 1)[1].split('EtaSelectionTopBarAction(', 1)[0]
                self.assertIn('Icons.Rounded.Search', actions)
                self.assertIn('EtaSelectionTopBarAction(', source)
                # 旧清空按钮退场：顶栏不再有 DeleteSweep 图标。
                self.assertNotIn('Icons.Rounded.DeleteSweep', source)

    # ---- 树形折叠结构 ----

    def test_collapsible_folder_sections(self):
        # 调度台是系统条目：独立置顶行，不进分组与勾选集合。
        self.assertIn('conversations.firstOrNull { it.isDispatchConsole }', self.chats)
        # 折叠态：EtaExpandChevron 连续旋转 + item(visible) spring 显隐 + rememberSaveable 记住。
        self.assertIn('EtaExpandChevron(expanded = expanded)', self.chats)
        self.assertIn('item(key = conversation.id, visible = sectionExpanded)', self.chats)
        self.assertIn('expandedSectionKeys by rememberSaveable { mutableStateOf(setOf<String>()) }', self.chats)
        # 归属已删分类的会话落进「未分类」折叠卡（与抽屉胶囊同术语）。
        self.assertIn('manage_page_group_uncategorized', self.chats)
        # 父行播报展开/收起状态（复用工具页 stateDescription 文案）。
        self.assertIn('tools_group_state_expanded', self.chats)
        self.assertIn('tools_group_state_collapsed', self.chats)

    # ---- 行尾三点菜单：会话菜单与侧栏长按菜单完全对齐 ----

    def test_conversation_row_overflow_menu(self):
        # 三点按钮（MoreHoriz）+ EtaTextMenu；菜单只从三点按钮开。
        self.assertEqual(self.chats.count('Icons.Rounded.MoreHoriz'), 2)
        self.assertEqual(self.chats.count('EtaTextMenu('), 2)
        row = self.chats.split('private fun ManageChatRow', 1)[1]
        for needle in (
            'stringResource(R.string.action_rename)',
            'Icons.Rounded.Edit',
            'pinDescription,',  # 置顶/取消置顶自适应文案
            'Icons.Rounded.PushPin',
            'stringResource(R.string.conversation_export)',
            'Icons.Rounded.Download',
            'stringResource(R.string.conversation_export_analysis)',
            'Icons.Rounded.QueryStats',
            'stringResource(R.string.drawer_move_to_folder)',
            'Icons.Rounded.Folder,',
            'stringResource(R.string.conversation_move_to_trash)',
        ):
            self.assertIn(needle, row, f'会话菜单缺 {needle}')
        # 调度台是系统条目：菜单只保留 重命名 / 导出 / 导出分析（无置顶切换/移动/删除）。
        self.assertIn('if (!systemEntry) {', row)
        # 菜单删除走宿主共享确认弹窗，不是页内直删。
        self.assertIn('onDeleteRequest', row)
        # 置顶状态用行尾静态图标指示（同抽屉行尾），切换动作收进菜单。
        self.assertIn('if (conversation.isPinned) {', row)

    def test_folder_header_overflow_menu(self):
        header = self.chats.split('private fun ManageFolderHeaderRow', 1)[1].split('private fun ManageChatRow', 1)[0]
        for needle in (
            'stringResource(R.string.action_rename), Icons.Rounded.Edit',
            # 删除是破坏性操作：error 色图标与文案。
            'Icons.Rounded.DeleteOutline,destructive=true,',
        ):
            self.assertIn(needle, compact(header) if 'destructive' in needle else header,
                          f'分类父行菜单缺 {needle}')
        # 空分类不可展开、不挂箭头。
        self.assertIn('val expandable = memberCount > 0', header)

    # ---- 多选交互：两页统一 ----

    def test_row_selection_semantics(self):
        for name, source in (('管理页', self.chats), ('回收站', self.trash)):
            with self.subTest(page=name):
                # 长按进多选并勾中该行。
                self.assertIn('onLongClickLabel = stringResource(R.string.action_enter_selection)', source)
                self.assertIn('selectionMode = true', source)
                # 多选态行尾官方 Checkbox。
                self.assertIn('Checkbox(', source)

    def test_selected_count_merges_into_title(self):
        for name, source in (('管理页', self.chats), ('回收站', self.trash)):
            with self.subTest(page=name):
                self.assertIn('stringResource(R.string.media_selected_count, selectedIds.size)', source)

    # ---- 批量操作栏：官方浮动工具条 ----

    def test_selection_bar_is_shared_kit_pill(self):
        # 药丸装配（官方 HorizontalFloatingToolbar + vibrant 容器 + 端盖同心）由
        # test_selection_kit_contract.py 在共享套件内锁死；页面只负责引用共享件。
        for name, source in (('管理页', self.chats), ('回收站', self.trash)):
            with self.subTest(bar=name):
                self.assertIn('EtaSelectionBar(', source)
                self.assertIn('EtaSelectionBarOverlay(', source)

    def test_bar_actions_per_target(self):
        # 管理页会话组：全选 + 置顶/取消置顶 + 删除；分类组：全选 + 删除（没有置顶）。
        bar = self.chats.split('EtaSelectionBar(', 1)[1]
        self.assertIn('if (selectionTarget == ManageSelectionTarget.Chats) {', bar)
        self.assertIn('if (allPinned) Icons.Rounded.LabelOff else Icons.Rounded.PushPin', bar)
        self.assertIn('Icons.Rounded.Delete', bar)
        # 回收站：全选 + 恢复 + 彻底删除。
        trash_bar = self.trash.split('EtaSelectionBar(', 1)[1]
        self.assertIn('Icons.Rounded.RestoreFromTrash', trash_bar)
        self.assertIn('Icons.Rounded.DeleteForever', trash_bar)
        # 删除都走确认弹窗。
        self.assertIn('showBatchDeleteDialog = true', self.chats)
        self.assertIn('showBatchPurgeDialog = true', self.trash)

    def test_select_all_scoped_correctly(self):
        # 管理页会话组全选不含调度台系统条目；分类组以全部分类为全集。
        self.assertIn('conversations.filter { !it.isDispatchConsole }', self.chats)
        self.assertIn('ManageSelectionTarget.Chats -> selectableIds.toSet()', self.chats)
        self.assertIn('ManageSelectionTarget.Folders -> folders.map { it.id }.toSet()', self.chats)
        # 回收站全选以整表为全集。
        self.assertIn('trash.map { it.id }.toSet()', self.trash)

    # ---- 管理页：多选不管理远程设备 ----

    def test_manage_multiselect_hides_remote_devices_and_fab(self):
        # 远程设备组在多选态整组隐藏（两个目标组统一）。
        guarded = self.chats.split('// 多选态不管理远程设备', 1)[1]
        self.assertIn('if (!selectionMode) {', guarded)
        self.assertIn('item(key = "remote-servers")', guarded)
        # 新建分类 FAB 多选态隐藏。
        fab = self.chats.split('floatingActionButton =', 1)[1].split('actions = {', 1)[0]
        self.assertIn('if (!selectionMode)', fab)

    # ---- 返回键退出多选：两页统一 ----

    def test_back_exits_selection_not_page(self):
        for name, source in (('管理页', self.chats), ('回收站', self.trash)):
            with self.subTest(page=name):
                self.assertIn('isBackEnabled = selectionMode', source)
                self.assertIn('rememberNavigationEventState(NavigationEventInfo.None)', source)

    # ---- 字符串三 locale 齐全 ----

    def test_selection_strings_complete_in_all_locales(self):
        keys = (
            'media_selected_count', 'media_action_multiselect', 'media_action_select_all',
            'media_action_deselect_all', 'action_enter_selection', 'action_cancel',
            'drawer_trash_batch_purge_message', 'folders_page_batch_delete_message',
            'manage_page_title', 'manage_page_group_uncategorized',
        )
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            text = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
            for key in keys:
                self.assertIn(f'name="{key}"', text, f'{locale} 缺 {key}')

    def test_removed_strings_are_gone(self):
        # 合并后退场的字符串：三个 locale 都不再保留（避免死资源）。
        removed = (
            'drawer_manage_chats', 'drawer_manage_folders', 'folders_page_title',
            'folders_page_group_categories', 'folders_page_empty',
            'history_page_title', 'history_page_delete_all',
        )
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            text = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
            for key in removed:
                self.assertNotIn(f'name="{key}"', text, f'{locale} 仍保留已退场的 {key}')


if __name__ == '__main__':
    unittest.main()
