package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.LabelOff
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExpandChevron
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaMenuEntry
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSelectionAction
import io.github.mangi.eta.ui.adaptive.EtaSelectionBar
import io.github.mangi.eta.ui.adaptive.EtaSelectionBarOverlay
import io.github.mangi.eta.ui.adaptive.EtaSelectionBottomSpacer
import io.github.mangi.eta.ui.adaptive.EtaSelectionTopBarAction
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTextMenu
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.EtaStatusPage
import io.github.mangi.eta.ui.components.FolderNameDialog
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.RemoteStatusDot
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.remote.RemoteCoordinator

/** 管理页列表顺序。已有会话保持原位；新会话按当前列表里的相对顺序插到最前。置顶变化才整表重排。 */
internal fun stableManageChatOrder(
    previous: List<String>,
    current: List<String>,
    reorder: Boolean = false,
): List<String> {
    if (previous.isEmpty() || reorder) return current
    val present = current.toSet()
    val kept = previous.filter { it in present }
    val keptSet = kept.toSet()
    return current.filter { it !in keptSet } + kept
}

/** 多选目标：会话（跨折叠组勾选）或分类（父行勾选、批量删除）。两组互斥进入。 */
private enum class ManageSelectionTarget { Chats, Folders }

/**
 * 管理页折叠卡渲染模型：[folder] 为 null 表示「无分类」卡，[members] 组内行序已钉住。
 */
private data class ManageChatSection(
    val key: String,
    val folder: ConversationFolderUi?,
    val members: List<ConversationSummaryUi>,
)

/**
 * 管理页（原「管理聊天」+「管理分类」合并）：会话库与分类的统一整理中枢。
 *
 * 结构自上而下：
 * - 调度台：系统条目独立置顶行，不折叠、不可删、不参与多选；
 * - 每分类一张折叠卡（判例 = 工具页 ToolCapabilityCard / 统计页 ProviderUsageGroup）：
 *   父行整行点击展开/收起（[EtaExpandChevron] 连续旋转 + `EtaSegmentedColumn` 的
 *   `item(visible = …)` spring 显隐），行尾三点菜单（重命名 / 删除）；展开后组内是会话行，
 *   组内排序「置顶优先 + 最后活动倒序」，页面打开期间按 [stableManageChatOrder] 钉住；
 * - 「无分类」折叠卡：未归入任何分类的会话，无成员时整卡隐藏；
 * - 远程设备摘要行：整行跳转设置中的远程智能体管理页（多选态隐藏）；
 * - 新建分类：右下角 [EtaExtendedFab]（多选态隐藏）。
 *
 * 会话行尾一律三点菜单（[EtaTextMenu]，判例 = 侧栏会话长按菜单 / 技能行溢出菜单），
 * 条目与侧栏 `Material3ConversationRow` 长按菜单完全对齐：重命名 / 置顶·取消置顶 /
 * 导出 / 导出分析 / 移动到分类 / 移入回收站；调度台只保留前三项。菜单只从三点按钮开，
 * 长按行固定进入多选；置顶状态用行尾静态 [PushPin] 指示（同抽屉行尾）。
 *
 * 多选：顶栏入口（Checklist→Close 原位切换）或长按行进入；`selectionTarget` 区分
 * 会话/分类两组勾选（互斥进入，进入时清空旧勾选）。会话组跨折叠组勾选（调度台除外），
 * 分类组勾选父行批量删除。多选期间折叠仍可操作，收起组内已勾选的行保持勾选、批量照常生效。
 * 返回键优先退出多选。
 *
 * 空态：会话、分类与远程设备全空时才显示整页状态页（附直达远程智能体管理页的动作）；
 * 否则各卡自隐藏 / 保留。
 */
@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    folders: List<ConversationFolderUi>,
    remoteCoordinator: RemoteCoordinator,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteConversation: (ConversationSummaryUi) -> Unit,
    // 单条删除经宿主共享确认弹窗（与侧栏长按菜单同一条删除链路）。
    onRequestDeleteConversation: (ConversationSummaryUi) -> Unit = {},
    onRenameConversation: (ConversationSummaryUi) -> Unit = {},
    onExportConversation: (ConversationSummaryUi) -> Unit = {},
    onExportConversationAnalysis: (ConversationSummaryUi) -> Unit = {},
    onMoveConversationToFolder: (ConversationSummaryUi) -> Unit = {},
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpenRemoteAgents: () -> Unit,
    onOpenSearchHistory: () -> Unit = {},
) {
    // 分类新建 / 重命名 / 删除的目标与弹窗开关。
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationFolderUi?>(null) }
    val view = LocalView.current
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)
    // 批量删除确认弹窗（会话与分类共用开关，按 selectionTarget 取不同文案与执行口径）。
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    // 对话框存活期间父级回调可能重建，确认时始终取最新的一份。
    val currentOnDelete by rememberUpdatedState(onDeleteConversation)
    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换）或长按行，
    // selectionTarget 区分会话/分类两组互斥勾选。
    var selectionMode by remember { mutableStateOf(false) }
    var selectionTarget by remember { mutableStateOf(ManageSelectionTarget.Chats) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 折叠态：默认全收起，rememberSaveable 记住（旋转 / 进程重建不丢）。
    var expandedSectionKeys by rememberSaveable { mutableStateOf(setOf<String>()) }

    val remoteServers = remoteCoordinator.servers
    val remoteServersEmpty = remoteServers.isEmpty()

    // 多个会话同时在跑时，更新时间会不断把它们换到前面。管理页按打开时的顺序钉住，
    // 只在原地刷新标题和状态，行尾菜单才点得中。新出现的会话插到最前，关掉页面再打开才重排。
    // The producer replaces immutable summary snapshots; row data always comes from the latest one.
    // 调度台是系统条目，独立置顶渲染，不进分组与勾选集合。
    val dispatchEntry = remember(conversations) { conversations.firstOrNull { it.isDispatchConsole } }
    val currentIds = remember(conversations) {
        conversations.filter { !it.isDispatchConsole }.map { it.id }
    }
    val pinnedIds = remember(conversations) {
        conversations.asSequence().filter { it.isPinned }.map { it.id }.toSet()
    }
    var orderIds by remember { mutableStateOf(currentIds) }
    var pinnedSnapshot by remember { mutableStateOf(pinnedIds) }
    val pinnedChanged = pinnedIds != pinnedSnapshot
    val nextOrder = remember(orderIds, currentIds, pinnedChanged) {
        stableManageChatOrder(orderIds, currentIds, pinnedChanged)
    }
    SideEffect {
        if (nextOrder != orderIds || pinnedChanged) {
            orderIds = nextOrder
            pinnedSnapshot = pinnedIds
        }
    }
    val byId = remember(conversations) { conversations.associateBy { it.id } }
    val ordered = remember(nextOrder, byId) { nextOrder.mapNotNull { byId[it] } }
    // 可批量操作的会话全集（不含调度台；跨折叠组勾选）。
    val selectableIds = remember(ordered) { ordered.map { it.id }.toSet() }
    // 折叠卡：分类卡按 folders 顺序（空分类保留、不可展开），归属已删分类的会话
    // （folderId 指向不存在的 id）落进「无分类」卡。
    val sections = remember(ordered, folders) {
        val folderIds = folders.map { it.id }.toSet()
        val buckets = ordered.groupBy { conversation -> conversation.folderId?.takeIf { it in folderIds } }
        folders.map { folder ->
            ManageChatSection("folder-${folder.id}", folder, buckets[folder.id].orEmpty())
        } + listOfNotNull(
            buckets[null]
                ?.takeIf { it.isNotEmpty() }
                ?.let { ManageChatSection("uncategorized", null, it) },
        )
    }
    val allSelected = when (selectionTarget) {
        ManageSelectionTarget.Chats ->
            selectableIds.isNotEmpty() && selectedIds.containsAll(selectableIds)
        ManageSelectionTarget.Folders ->
            folders.isNotEmpty() && selectedIds.containsAll(folders.map { it.id })
    }
    // 置顶动作口径=勾选集：全部已置顶 → 动作变「取消置顶」（仅会话组提供）。
    val allPinned = selectedIds.isNotEmpty() && selectedIds.all { id -> byId[id]?.isPinned == true }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun enterSelection(target: ManageSelectionTarget, id: String?) {
        // 互斥进入：切换目标即清空旧勾选。
        selectionMode = true
        selectionTarget = target
        selectedIds = if (id != null) setOf(id) else emptySet()
    }

    fun toggleExpand(sectionKey: String) {
        expandedSectionKeys = if (sectionKey in expandedSectionKeys) {
            expandedSectionKeys - sectionKey
        } else {
            expandedSectionKeys + sectionKey
        }
    }

    fun toggleChatSelection(conversation: ConversationSummaryUi) {
        selectedIds = if (conversation.id in selectedIds) {
            selectedIds - conversation.id
        } else {
            selectedIds + conversation.id
        }
    }

    // 返回键优先退出多选，不退页面。
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = { exitSelection() },
    )

    val groupModifier = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    EtaScaffoldPage(
        title = stringResource(R.string.manage_page_title),
        onBack = onBack,
        listState = listState,
        // 多选态隐藏新建入口：批量操作栏占住底部，FAB 让位。
        floatingActionButton = if (!selectionMode) {
            {
                EtaExtendedFab(
                    text = stringResource(R.string.drawer_create_folder_title),
                    icon = Icons.Rounded.Add,
                    expanded = fabExpanded,
                    onClick = {
                        TouchHaptics.click(view)
                        showCreate = true
                    },
                )
            }
        } else {
            null
        },
        listModifier = Modifier
            .streamDiagnosticMeasure("manage.lazy.measure")
            .streamDiagnosticPlacement("manage.lazy.place")
            .streamDiagnosticDraw("manage.lazy.draw"),
        actions = {
            TopBarIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.action_search_history),
                onClick = { onOpenSearchHistory() },
                shapes = IconButtonDefaults.shapes(),
            )
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。空闲时进入会话组。
            EtaSelectionTopBarAction(
                selectionMode = selectionMode,
                onToggle = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else enterSelection(ManageSelectionTarget.Chats, null)
                },
                enabled = selectionMode || ordered.isNotEmpty(),
            )
        },
        titleContent = {
            // 多选态把「已选 n 项」并入标题行（点号隔开），不另立计数行。
            M3Text(
                text = if (selectionMode) {
                    stringResource(R.string.manage_page_title) + " · " +
                        stringResource(R.string.media_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.manage_page_title)
                },
            )
        },
        content = {
            if (dispatchEntry == null && ordered.isEmpty() && folders.isEmpty() && remoteServersEmpty) {
                // 整页零态（会话、分类与远程设备全空）：状态页占满视口居中。
                item(key = "empty") {
                    EtaStatusPage(
                        icon = Icons.Rounded.Forum,
                        title = stringResource(R.string.history_page_empty),
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.folders_page_empty_open_remote_agents),
                                onClick = onOpenRemoteAgents,
                            )
                        },
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            } else {
                // 调度台独立置顶行：系统条目，不折叠、不可删、不参与多选勾选。
                dispatchEntry?.let { entry ->
                    item(key = "dispatch", contentType = "dispatch-card") {
                        EtaSegmentedColumn(modifier = groupModifier) {
                            item(key = entry.id) {
                                ManageChatRow(
                                    conversation = entry,
                                    selectionMode = selectionMode,
                                    checkable = selectionTarget == ManageSelectionTarget.Chats,
                                    checked = false,
                                    checkEnabled = false,
                                    menuEnabled = !selectionMode,
                                    onClick = {
                                        if (!selectionMode) onOpenConversation(entry.id)
                                    },
                                    // 调度台不参与勾选：长按只进入多选态、不勾中任何行（旧行为）。
                                    onLongClick = {
                                        if (!selectionMode) enterSelection(ManageSelectionTarget.Chats, null)
                                    },
                                    onTogglePin = { onTogglePin(entry.id) },
                                    onDeleteRequest = { onRequestDeleteConversation(entry) },
                                    onRename = { onRenameConversation(entry) },
                                    onExport = { onExportConversation(entry) },
                                    onExportAnalysis = { onExportConversationAnalysis(entry) },
                                    onMoveToFolder = { onMoveConversationToFolder(entry) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
                // 每分类一张折叠卡 + 「无分类」卡：组内会话行随 visible 参与 spring 显隐。
                sections.forEach { section ->
                    item(key = section.key, contentType = "folder-card") {
                        val sectionExpanded = section.key in expandedSectionKeys
                        EtaSegmentedColumn(modifier = groupModifier) {
                            item(key = "${section.key}-header") {
                                ManageFolderHeaderRow(
                                    title = section.folder?.name
                                        ?: stringResource(R.string.manage_page_group_uncategorized),
                                    memberCount = section.members.size,
                                    expanded = sectionExpanded,
                                    selectionMode = selectionMode,
                                    inFolderSelection = selectionTarget == ManageSelectionTarget.Folders,
                                    // 「未分类」不是真实分类：不参与分类多选（无勾选框）。
                                    checkable = section.folder != null,
                                    checked = section.folder?.let { it.id in selectedIds } == true,
                                    onToggleExpand = { toggleExpand(section.key) },
                                    // 长按进入分类多选并勾中该分类；「未分类」不可勾选，长按不响应。
                                    onEnterSelection = {
                                        section.folder?.let { folder ->
                                            enterSelection(ManageSelectionTarget.Folders, folder.id)
                                        }
                                    },
                                    onToggleSelected = {
                                        section.folder?.let { folder ->
                                            selectedIds = if (folder.id in selectedIds) {
                                                selectedIds - folder.id
                                            } else {
                                                selectedIds + folder.id
                                            }
                                        }
                                    },
                                    onRename = {
                                        section.folder?.let { folder -> renameTarget = folder }
                                    },
                                    onDelete = {
                                        section.folder?.let { folder -> deleteTarget = folder }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            section.members.forEach { conversation ->
                                item(key = conversation.id, visible = sectionExpanded) {
                                    ManageChatRow(
                                        conversation = conversation,
                                        selectionMode = selectionMode,
                                        checkable = selectionTarget == ManageSelectionTarget.Chats,
                                        checked = conversation.id in selectedIds,
                                        checkEnabled = !conversation.isDispatchConsole,
                                        menuEnabled = !selectionMode,
                                        onClick = {
                                            when {
                                                selectionMode &&
                                                    selectionTarget == ManageSelectionTarget.Chats ->
                                                    toggleChatSelection(conversation)
                                                selectionMode -> {}
                                                else -> onOpenConversation(conversation.id)
                                            }
                                        },
                                        onLongClick = {
                                            if (!selectionMode) {
                                                enterSelection(ManageSelectionTarget.Chats, conversation.id)
                                            }
                                        },
                                        onTogglePin = { onTogglePin(conversation.id) },
                                        onDeleteRequest = { onRequestDeleteConversation(conversation) },
                                        onRename = { onRenameConversation(conversation) },
                                        onExport = { onExportConversation(conversation) },
                                        onExportAnalysis = { onExportConversationAnalysis(conversation) },
                                        onMoveToFolder = { onMoveConversationToFolder(conversation) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
                // 多选态不管理远程设备：整组隐藏，退出多选即恢复。
                if (!selectionMode) {
                    item(key = "remote-servers") {
                        EtaSegmentedColumn(modifier = groupModifier) {
                            item(key = "remote-servers-entry") {
                                val statuses = remoteServers.map { remoteCoordinator.statusOf(it.id) }
                                val summary = if (remoteServersEmpty) {
                                    stringResource(R.string.remote_agents_empty)
                                } else {
                                    stringResource(
                                        R.string.folders_page_remote_summary,
                                        statuses.count { it == RemoteConnectionStatus.Connected },
                                        statuses.size,
                                    )
                                }
                                EtaClickableRow(
                                    title = stringResource(
                                        R.string.folders_page_group_remote_devices,
                                        remoteServers.size,
                                    ),
                                    summary = summary,
                                    leading = { RemoteStatusDot(aggregateRemoteStatus(statuses)) },
                                    onClick = onOpenRemoteAgents,
                                )
                            }
                        }
                    }
                }
                item(key = "bottom_spacer", contentType = "spacer") {
                    // 多选操作栏悬浮在底部时预留高度，避免遮挡最后一个列表项。
                    EtaSelectionBottomSpacer(isSelecting = selectionMode)
                }
            }
        },
    )

    // 批量操作栏：共享多选套件的官方 M3E 浮动药丸，底部居中悬浮；动作按目标组切换。
    Box(modifier = Modifier.fillMaxSize()) {
        EtaSelectionBarOverlay(visible = selectionMode) {
            EtaSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        when (selectionTarget) {
                            ManageSelectionTarget.Chats -> selectableIds.toSet()
                            ManageSelectionTarget.Folders -> folders.map { it.id }.toSet()
                        }
                    }
                },
                actions = buildList {
                    if (selectionTarget == ManageSelectionTarget.Chats) {
                        // 置顶 / 取消置顶：按当前勾选集合的状态自适应图标（有未置顶 → 全部置顶）。
                        add(
                            EtaSelectionAction(
                                icon = if (allPinned) Icons.Rounded.LabelOff else Icons.Rounded.PushPin,
                                contentDescription = stringResource(
                                    if (allPinned) R.string.conversation_unpin else R.string.conversation_pin,
                                ),
                                onClick = {
                                    TouchHaptics.click(view)
                                    // 以第一个选中项的目标状态为准：有未置顶 → 全部置顶；否则全部取消置顶。
                                    val pin = selectedIds.any { id -> byId[id]?.isPinned == false }
                                    selectedIds.forEach { id ->
                                        val current = byId[id]?.isPinned ?: return@forEach
                                        if (current != pin) onTogglePin(id)
                                    }
                                },
                            ),
                        )
                    }
                    // 删除是破坏性操作：error 色图标区分，执行前另有确认弹窗。
                    add(
                        EtaSelectionAction(
                            icon = Icons.Rounded.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            onClick = {
                                TouchHaptics.click(view)
                                showBatchDeleteDialog = true
                            },
                            destructive = true,
                        ),
                    )
                },
            )
        }
    }

    // 批量删除二次确认：会话组文案与旧「清空全部」同口径（移入回收站，可恢复）；
    // 分类组口径与单条删除一致（分类里的会话移回「聊天」）。
    if (showBatchDeleteDialog) {
        val chatsTarget = selectionTarget == ManageSelectionTarget.Chats
        EtaDialog(
            show = true,
            title = stringResource(
                if (chatsTarget) R.string.history_page_delete_all_title else R.string.drawer_delete_folder_title,
            ),
            summary = stringResource(
                if (chatsTarget) R.string.history_page_delete_all_message
                else R.string.folders_page_batch_delete_message,
                selectedIds.size,
            ),
            onDismissRequest = { showBatchDeleteDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showBatchDeleteDialog = false },
                    onConfirm = {
                        when (selectionTarget) {
                            ManageSelectionTarget.Chats ->
                                selectedIds.forEach { id -> byId[id]?.let(currentOnDelete) }
                            ManageSelectionTarget.Folders -> selectedIds.forEach(onDeleteFolder)
                        }
                        showBatchDeleteDialog = false
                        exitSelection()
                    },
                )
            },
        ) {
        }
    }

    if (showCreate) {
        FolderNameDialog(
            title = stringResource(R.string.drawer_create_folder_title),
            initialName = "",
            onDismiss = { showCreate = false },
            onConfirm = { name ->
                onCreateFolder(name)
                showCreate = false
            },
        )
    }

    renameTarget?.let { folder ->
        FolderNameDialog(
            title = stringResource(R.string.drawer_rename_folder_title),
            initialName = folder.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRenameFolder(folder.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { folder ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_delete_folder_title),
            summary = stringResource(R.string.folders_page_delete_message, folder.name),
            onDismissRequest = { deleteTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { deleteTarget = null },
                    onConfirm = {
                        onDeleteFolder(folder.id)
                        deleteTarget = null
                    },
                )
            },
        ) {
        }
    }
}

/**
 * 折叠卡父行：M3 expand & collapse 的父条目（判例 = 工具页 ToolCapabilityHeaderRow）。
 *
 * 整行点击切换展开/收起（分类多选态改为勾选切换）；行尾三点菜单承载重命名 / 删除，
 * 菜单按钮在箭头右侧（与工具页「箭头在左、功能尾槽在右」的尾槽顺序一致）。
 * 空分类（memberCount == 0）不可展开、不挂箭头，stateDescription 也不播报。
 * 无障碍：行播报展开/收起状态（stateDescription），三点按钮带独立 contentDescription。
 */
@Composable
private fun ManageFolderHeaderRow(
    title: String,
    memberCount: Int,
    expanded: Boolean,
    selectionMode: Boolean,
    inFolderSelection: Boolean,
    checkable: Boolean,
    checked: Boolean,
    onToggleExpand: () -> Unit,
    onEnterSelection: () -> Unit,
    onToggleSelected: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val expandable = memberCount > 0
    val stateText = stringResource(
        if (expanded) R.string.tools_group_state_expanded else R.string.tools_group_state_collapsed,
    )
    val countLabel = pluralStringResource(
        R.plurals.folders_page_conversation_count,
        memberCount,
        memberCount,
    )
    val menuLabel = stringResource(R.string.action_more)
    var menuVisible by remember { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth()) {
        EtaSegmentedRow(
            onClick = {
                when {
                    // 分类多选态只有真实分类可勾选；「未分类」卡点按仍是展开/收起。
                    selectionMode && inFolderSelection && checkable -> onToggleSelected()
                    else -> onToggleExpand()
                }
            },
            onLongClick = {
                if (!selectionMode) onEnterSelection()
            },
            onLongClickLabel = stringResource(R.string.action_enter_selection),
            modifier = if (expandable) {
                Modifier.semantics { stateDescription = stateText }
            } else {
                Modifier
            },
            leadingContent = { PreferenceIcon(Icons.Rounded.Folder) },
            content = {
                M3Text(
                    text = title,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            supportingContent = {
                M3Text(
                    text = countLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (selectionMode && inFolderSelection && checkable) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onToggleSelected() },
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EtaDimens.TrailingElementGap),
                    ) {
                        if (expandable) {
                            EtaExpandChevron(expanded = expanded)
                        }
                        Box {
                            EtaTooltip(text = menuLabel) {
                                IconButton(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        menuVisible = true
                                    },
                                    enabled = !selectionMode,
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreHoriz,
                                        contentDescription = menuLabel,
                                    )
                                }
                            }
                            EtaTextMenu(
                                expanded = menuVisible,
                                onDismissRequest = { menuVisible = false },
                                entries = listOf(
                                    EtaMenuEntry(stringResource(R.string.action_rename), Icons.Rounded.Edit) {
                                        menuVisible = false
                                        onRename()
                                    },
                                    // 删除是破坏性操作：error 色图标与文案，执行前另有确认弹窗。
                                    EtaMenuEntry(
                                        stringResource(R.string.action_delete),
                                        Icons.Rounded.DeleteOutline,
                                        destructive = true,
                                    ) {
                                        menuVisible = false
                                        onDelete()
                                    },
                                ),
                            )
                        }
                    }
                }
            },
        )
    }
}

/**
 * 管理页会话单行（折叠卡内 / 调度台独立行共用）。
 *
 * 行尾三点菜单（[EtaTextMenu]）条目与侧栏长按菜单完全对齐：重命名 / 置顶·取消置顶 /
 * 导出 / 导出分析 / 移动到分类 / 移入回收站；调度台是系统条目，只保留前三项。
 * 置顶状态用行尾静态 PushPin 指示（同抽屉行尾），切换动作收进菜单。
 * 多选态尾部换官方 Checkbox，点按/长按行都是勾选切换（非本组目标时保持常规尾槽、
 * 菜单禁用）；长按也用于从常规态直接进入多选（勾中该行）。
 */
@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    selectionMode: Boolean,
    checkable: Boolean,
    checked: Boolean,
    checkEnabled: Boolean,
    menuEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDeleteRequest: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onExportAnalysis: () -> Unit,
    onMoveToFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val title = conversation.displayTitle.ifBlank { conversation.preview }
    val systemEntry = conversation.isDispatchConsole
    val pinDescription = stringResource(
        if (conversation.isPinned) R.string.conversation_unpin else R.string.conversation_pin,
    )
    val menuLabel = stringResource(R.string.action_more)
    val statusPresent = conversation.isActiveRun || conversation.hasCompletionMarker
    var menuVisible by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        EtaSegmentedRow(
            onClick = onClick,
            onLongClick = onLongClick,
            onLongClickLabel = stringResource(R.string.action_enter_selection),
            modifier = Modifier.fillMaxWidth(),
            content = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    M3Text(
                        text = title,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (statusPresent) {
                        ManageChatStatusDot(
                            activeRun = conversation.isActiveRun,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            supportingContent = {
                M3Text(
                    text = conversation.timeLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                if (selectionMode && checkable) {
                    // 多选态：官方 Checkbox。调度台是系统条目，不参与勾选。
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onClick() },
                        enabled = checkEnabled,
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(EtaDimens.TrailingElementGap),
                    ) {
                        if (conversation.isPinned) {
                            // 置顶状态静态指示（同抽屉行尾），切换动作收进菜单。
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = pinDescription,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(EtaDimens.DenseIconSize),
                            )
                        }
                        Box {
                            EtaTooltip(text = menuLabel) {
                                IconButton(
                                    onClick = {
                                        TouchHaptics.click(view)
                                        menuVisible = true
                                    },
                                    enabled = menuEnabled,
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MoreHoriz,
                                        contentDescription = menuLabel,
                                    )
                                }
                            }
                            EtaTextMenu(
                                expanded = menuVisible,
                                onDismissRequest = { menuVisible = false },
                                entries = buildList {
                                    add(
                                        EtaMenuEntry(
                                            stringResource(R.string.action_rename),
                                            Icons.Rounded.Edit,
                                        ) {
                                            menuVisible = false
                                            onRename()
                                        },
                                    )
                                    if (!systemEntry) {
                                        add(
                                            EtaMenuEntry(
                                                pinDescription,
                                                Icons.Rounded.PushPin,
                                            ) {
                                                menuVisible = false
                                                onTogglePin()
                                            },
                                        )
                                    }
                                    add(
                                        EtaMenuEntry(
                                            stringResource(R.string.conversation_export),
                                            Icons.Rounded.Download,
                                        ) {
                                            menuVisible = false
                                            onExport()
                                        },
                                    )
                                    add(
                                        EtaMenuEntry(
                                            stringResource(R.string.conversation_export_analysis),
                                            Icons.Rounded.QueryStats,
                                        ) {
                                            menuVisible = false
                                            onExportAnalysis()
                                        },
                                    )
                                    if (!systemEntry) {
                                        add(
                                            EtaMenuEntry(
                                                stringResource(R.string.drawer_move_to_folder),
                                                Icons.Rounded.Folder,
                                            ) {
                                                menuVisible = false
                                                onMoveToFolder()
                                            },
                                        )
                                        add(
                                            EtaMenuEntry(
                                                stringResource(R.string.conversation_move_to_trash),
                                                Icons.Rounded.Delete,
                                                destructive = true,
                                            ) {
                                                menuVisible = false
                                                onDeleteRequest()
                                            },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

/** 运行中 / 已完成标记圆点：实心表示运行中，描边表示已完成。 */
@Composable
private fun ManageChatStatusDot(
    activeRun: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(EtaDimens.StatusDotSize)
            .clip(CircleShape)
            .then(
                if (activeRun) {
                    Modifier.background(color)
                } else {
                    Modifier.border(1.dp, color, CircleShape)
                },
            ),
    )
}

/**
 * 汇总远程设备状态，供单行入口的状态圆点使用：失败 > 重连中 > 连接中 > 未连接 > 全部已连接。
 * 任一设备异常都优先暴露；部分离线时保持灰色，避免用绿色误报「全部在线」。
 */
private fun aggregateRemoteStatus(
    statuses: List<RemoteConnectionStatus>,
): RemoteConnectionStatus = when {
    statuses.isEmpty() -> RemoteConnectionStatus.Disconnected
    statuses.any { it is RemoteConnectionStatus.Failed } ->
        statuses.first { it is RemoteConnectionStatus.Failed }
    statuses.any { it is RemoteConnectionStatus.Reconnecting } ->
        statuses.first { it is RemoteConnectionStatus.Reconnecting }
    RemoteConnectionStatus.Connecting in statuses -> RemoteConnectionStatus.Connecting
    RemoteConnectionStatus.Disconnected in statuses -> RemoteConnectionStatus.Disconnected
    else -> RemoteConnectionStatus.Connected
}
