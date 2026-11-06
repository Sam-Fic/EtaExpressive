package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
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
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTextButton
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.FolderNameDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.RemoteStatusDot
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
    // 计数只取正常历史（回收站成员不在其中），与抽屉筛选口径一致。
    val countsByFolder = remember(conversations) {
        conversations.groupingBy { it.folderId }.eachCount()
    }
    val remoteServers = remoteCoordinator.servers
    val foldersEmpty = folders.isEmpty()
    val remoteServersEmpty = remoteServers.isEmpty()
    val groupModifier = Modifier.padding(
        start = EtaGroupHorizontalPadding,
        end = EtaGroupHorizontalPadding,
        bottom = EtaSegmentedGroupSpacing,
    )

    EtaScaffoldPage(
        title = stringResource(R.string.folders_page_title),
        onBack = onBack,
        listState = listState,
        // 新建入口从顶栏图标钮换成 MCP 服务器页同款 FAB：滚动收起、回顶展开。
        floatingActionButton = {
            EtaExtendedFab(
                text = stringResource(R.string.drawer_create_folder_title),
                icon = Icons.Rounded.Add,
                expanded = fabExpanded,
                onClick = {
                    TouchHaptics.click(view)
                    showCreate = true
                },
            )
        },
    ) {
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
                    title = stringResource(R.string.folders_page_group_categories, folders.size),
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
                                    onRename = { renameTarget = folder },
                                    onDelete = { deleteTarget = folder },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
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

/** 文件夹分类单行：名称 + 会话数量，尾部重命名 / 删除。 */
@Composable
private fun ManageFolderRow(
    folder: ConversationFolderUi,
    conversationCount: Int,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                M3IconButton(
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
                M3IconButton(
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
