package io.github.mangi.eta.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.FolderNameDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.RemoteStatusDot
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.remote.RemoteCoordinator

/**
 * 分类管理页：同一页内管理「文件夹分类」与「远程设备」两组内容。
 *
 * 文件夹分类：新建 / 重命名 / 删除；行样式与「管理聊天」「回收站」同款分段列表，
 * 新建入口在右下角 FAB，行尾是重命名与删除两枚图标按钮。删除走二次确认，
 * 分类里的会话会移回「聊天」，不会一并删除。
 *
 * 远程设备：只保留一行「远程设备 · n」+ 已连接数量摘要，整行点击跳转设置中的
 * 远程智能体管理页；设备切换由抽屉胶囊负责，本页不复制增删改与内联编辑逻辑。
 * **多选态不管理远程设备**：顶栏进入多选后远程设备整组隐藏（批量操作只面向分类），
 * 新建分类 FAB 也一并隐藏，退出多选即恢复。
 *
 * 多选：顶栏入口（Checklist→Close 原位切换）或长按分类行进入；底部浮出批量操作栏
 * （全选 / 删除），已选数量并入标题行；返回键优先退出多选。
 *
 * 空态分层：两类都为空时才显示整页空态，并额外提供直达远程智能体管理页的动作；
 * 只有一类为空时，在对应分组内给出提示。
 */
@Composable
internal fun ManageFoldersScreen(
    folders: List<ConversationFolderUi>,
    conversations: List<ConversationSummaryUi>,
    remoteCoordinator: RemoteCoordinator,
    onBack: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    onOpenRemoteAgents: () -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ConversationFolderUi?>(null) }
    var deleteTarget by remember { mutableStateOf<ConversationFolderUi?>(null) }
    val view = LocalView.current
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)
    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换），长按分类行也可进入。
    // 多选只面向分类，不管理远程设备。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 批量删除确认弹窗。
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    // 计数只取正常历史（回收站成员不在其中），与抽屉筛选口径一致。
    val countsByFolder = remember(conversations) {
        conversations.groupingBy { it.folderId }.eachCount()
    }
    val remoteServers = remoteCoordinator.servers
    val foldersEmpty = folders.isEmpty()
    val remoteServersEmpty = remoteServers.isEmpty()
    val allSelected = folders.isNotEmpty() && selectedIds.containsAll(folders.map { it.id })
    val groupModifier = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    // 返回键优先退出多选，不退页面。
    val selectionBackState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = selectionBackState,
        isBackEnabled = selectionMode,
        onBackCompleted = {
            selectionMode = false
            selectedIds = emptySet()
        },
    )

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    EtaScaffoldPage(
        title = stringResource(R.string.folders_page_title),
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
        actions = {
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。无分类可管时禁用。
            TopBarIconButton(
                icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                contentDescription = stringResource(
                    if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,
                ),
                onClick = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else selectionMode = true
                },
                enabled = folders.isNotEmpty() || selectionMode,
                shapes = IconButtonDefaults.shapes(),
            )
        },
        titleContent = {
            // 多选态把「已选 n 项」并入标题行（点号隔开），不另立计数行。
            M3Text(
                text = if (selectionMode) {
                    stringResource(R.string.folders_page_title) + " · " +
                        stringResource(R.string.media_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.folders_page_title)
                },
            )
        },
        content = {
            if (foldersEmpty && remoteServersEmpty) {
                item(key = "empty") {
                    ListEmptyState(
                        title = stringResource(R.string.folders_page_empty),
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.folders_page_empty_open_remote_agents),
                                onClick = onOpenRemoteAgents,
                            )
                        },
                    )
                }
            } else {
                item(key = "folders") {
                    EtaSegmentedColumn(
                        modifier = groupModifier,
                        title = if (selectionMode) {
                            stringResource(R.string.folders_page_group_categories, folders.size) + " · " +
                                stringResource(R.string.media_selected_count, selectedIds.size)
                        } else {
                            stringResource(R.string.folders_page_group_categories, folders.size)
                        },
                    ) {
                        if (foldersEmpty) {
                            item(key = "folders-empty") {
                                EtaSegmentedItemContainer {
                                    ListEmptyState(
                                        title = stringResource(R.string.folders_page_empty),
                                    )
                                }
                            }
                        } else {
                            folders.forEach { folder ->
                                item(key = folder.id) {
                                    ManageFolderRow(
                                        folder = folder,
                                        conversationCount = countsByFolder[folder.id] ?: 0,
                                        selectionMode = selectionMode,
                                        checked = folder.id in selectedIds,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedIds = if (folder.id in selectedIds) {
                                                    selectedIds - folder.id
                                                } else {
                                                    selectedIds + folder.id
                                                }
                                            }
                                            // 非多选态整行不响应：重命名/删除仍走行尾按钮（与改造前一致）。
                                        },
                                        onLongClick = {
                                            if (!selectionMode) {
                                                selectionMode = true
                                                selectedIds = setOf(folder.id)
                                            }
                                        },
                                        onRename = { renameTarget = folder },
                                        onDelete = { deleteTarget = folder },
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
                    Spacer(
                        modifier = Modifier
                            .height(if (selectionMode) 88.dp else 24.dp)
                            .navigationBarsPadding(),
                    )
                }
            }
        },
    )

    // 批量操作栏：官方 M3E HorizontalFloatingToolbar 纯图标药丸，底部居中悬浮。
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            // 批量选择栏的进退场：位移走 M3E Spatial（轻微过冲），透明度走 Effects（无过冲）。
            enter = slideInVertically(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ) { it } + fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            ) { it } + fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            FolderSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        folders.map { it.id }.toSet()
                    }
                },
                onDelete = {
                    TouchHaptics.click(view)
                    showBatchDeleteDialog = true
                },
            )
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

    // 批量删除二次确认：口径与单条删除一致（分类里的会话移回「聊天」）。
    if (showBatchDeleteDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_delete_folder_title),
            summary = stringResource(R.string.folders_page_batch_delete_message, selectedIds.size),
            onDismissRequest = { showBatchDeleteDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showBatchDeleteDialog = false },
                    onConfirm = {
                        selectedIds.forEach(onDeleteFolder)
                        showBatchDeleteDialog = false
                        exitSelection()
                    },
                )
            },
        ) {
        }
    }
}

/**
 * 分类多选批量操作栏：官方 M3E [HorizontalFloatingToolbar]（纯图标药丸），底部居中悬浮，
 * 与模型管理页（ProviderModelsTab.ModelSelectionBar）同款装配。
 * 动作序：全选（全不选）→ 删除（error 色标破坏性，执行前另有确认弹窗）；
 * 「取消」不落底栏，由顶栏多选入口原位切换（Checklist→Close）承载，已选数量并入标题行文案。
 * 组件结论同 ChatSelectionBar 的 KDoc（同版本依赖既有调用核证）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        HorizontalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = 12.dp),
            leadingContent = {},
            // 端盖同心：首末两枚 40dp 圆钮与药丸端头弧心对齐（环隙与上下等宽）。
            contentPadding = EtaFloatingToolbarConcentricPadding(
                IconButtonDefaults.smallContainerSize().width,
            ),
        ) {
            // 动作组：固定 8dp 间距的一行图标按钮（EtaIconButton 的 `Standard` 档裸图标）。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EtaIconButton(
                    icon = if (allSelected) Icons.Rounded.Deselect else Icons.Rounded.SelectAll,
                    contentDescription = stringResource(
                        if (allSelected) R.string.media_action_deselect_all else R.string.media_action_select_all,
                    ),
                    onClick = onSelectAll,
                    size = EtaIconSize.S,
                    style = EtaButtonStyle.Standard,
                )
                // 删除是破坏性操作：图标用 error 色区分（官方裸 IconButton，容器/图标尺寸
                // 与 EtaIconButton 的 `Standard` + S 档一致），执行前另有确认弹窗。
                val deleteDescription = stringResource(R.string.action_delete)
                EtaTooltip(text = deleteDescription) {
                    androidx.compose.material3.IconButton(
                        onClick = onDelete,
                        enabled = selectedCount > 0,
                        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = deleteDescription,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 文件夹分类单行：名称 + 会话数量。常规态尾部重命名 / 删除两枚图标按钮；
 * 多选态尾部换官方 Checkbox，点按/长按行都是勾选切换。
 */
@Composable
private fun ManageFolderRow(
    folder: ConversationFolderUi,
    conversationCount: Int,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val renameDescription = stringResource(R.string.action_rename)
    val deleteDescription = stringResource(R.string.action_delete)
    val countLabel = pluralStringResource(
        R.plurals.folders_page_conversation_count,
        conversationCount,
        conversationCount,
    )

    EtaSegmentedRow(
        modifier = modifier,
        onClick = onClick,
        onLongClick = onLongClick,
        onLongClickLabel = stringResource(R.string.action_enter_selection),
        leadingContent = { PreferenceIcon(Icons.Rounded.Folder) },
        content = {
            M3Text(
                text = folder.name,
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
            if (selectionMode) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onClick() },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(
                        onClick = {
                            TouchHaptics.click(view)
                            onRename()
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = renameDescription,
                        )
                    }
                    // 删除：破坏性动作取官方 error 角色色。
                    androidx.compose.material3.IconButton(
                        onClick = {
                            TouchHaptics.click(view)
                            onDelete()
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = deleteDescription,
                        )
                    }
                }
            }
        },
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
