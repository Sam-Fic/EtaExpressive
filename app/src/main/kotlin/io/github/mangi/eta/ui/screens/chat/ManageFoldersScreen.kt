package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.FolderNameDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationFolderUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi

/**
 * 文件夹管理页（独立路由）：新建 / 重命名 / 删除。
 *
 * 行样式与「管理聊天」「回收站」同款分段列表；新建入口在右下角 FAB，
 * 行尾是重命名与删除两枚图标按钮。删除走二次确认，文件夹里的会话会
 * 移回「聊天」，不会一并删除。
 */
@Composable
internal fun ManageFoldersScreen(
    folders: List<ConversationFolderUi>,
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onDeleteFolder: (String) -> Unit,
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
        if (folders.isEmpty()) {
            item(key = "empty") {
                EtaText(
                    text = stringResource(R.string.folders_page_empty),
                    style = EtaTextStyle.Body,
                    secondary = true,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        } else {
            itemsIndexed(
                items = folders,
                key = { _, folder -> folder.id },
                contentType = { _, _ -> "folder" },
            ) { index, folder ->
                ManageFolderRow(
                    folder = folder,
                    conversationCount = countsByFolder[folder.id] ?: 0,
                    segmentIndex = index,
                    segmentCount = folders.size,
                    onRename = { renameTarget = folder },
                    onDelete = { deleteTarget = folder },
                    modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding),
                )
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

/** 文件夹单行：名称 + 会话数量，尾部重命名 / 删除。 */
@Composable
private fun ManageFolderRow(
    folder: ConversationFolderUi,
    conversationCount: Int,
    segmentIndex: Int,
    segmentCount: Int,
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

    EtaSegmentedItem(index = segmentIndex, count = segmentCount) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                // 组内行距走官方分段缝隙（2dp），末行不补，交给列表底部留白收尾。
                .padding(
                    bottom = if (segmentIndex == segmentCount - 1) 0.dp else EtaSegmentedGap,
                ),
        ) {
            EtaSegmentedRow(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    M3Text(
                        text = folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    M3Text(
                        text = countLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}
