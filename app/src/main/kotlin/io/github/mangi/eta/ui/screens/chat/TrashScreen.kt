package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSearchHistory
import io.github.mangi.eta.ui.adaptive.EtaSearchPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationSummaryUi

/**
 * 回收站页面：已删除会话按删除时间由新到旧排列，行尾支持恢复与彻底删除，
 * 顶栏提供清空回收站。进入页面时先按留存期自动清理一次过期会话。
 */
@Composable
internal fun TrashScreen(
    trash: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    onPurgeExpired: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    val view = LocalView.current
    // 进入即清一次过期回收站：与工具列回收站、启动水合共用同一门面。
    LaunchedEffect(Unit) { onPurgeExpired() }
    var confirmEmpty by remember { mutableStateOf(false) }
    var pendingDeleteForever by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    val currentOnDeleteForever by rememberUpdatedState(onDeleteForever)
    val currentOnRestore by rememberUpdatedState(onRestore)

    EtaScaffoldPage(
        title = stringResource(R.string.drawer_trash_title),
        onBack = onBack,
        actions = {
            M3IconButton(
                onClick = onOpenSearch,
                enabled = trash.isNotEmpty(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.trash_search_title),
                )
            }
            M3IconButton(
                onClick = {
                    TouchHaptics.click(view)
                    confirmEmpty = true
                },
                enabled = trash.isNotEmpty(),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DeleteSweep,
                    contentDescription = stringResource(R.string.drawer_trash_empty_action),
                )
            }
        },
    ) {
        if (trash.isEmpty()) {
            item(key = "empty") {
                EtaText(
                    text = stringResource(R.string.drawer_trash_empty),
                    style = EtaTextStyle.Body,
                    secondary = true,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        } else {
            itemsIndexed(
                items = trash,
                key = { _, conversation -> conversation.id },
                contentType = { _, _ -> "trash-conversation" },
            ) { index, conversation ->
                Box(
                    modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding),
                ) {
                    TrashRow(
                        conversation = conversation,
                        segmentIndex = index,
                        segmentCount = trash.size,
                        onRestore = {
                            TouchHaptics.click(view)
                            currentOnRestore(conversation.id)
                        },
                        onDeleteForever = {
                            TouchHaptics.click(view)
                            pendingDeleteForever = conversation
                        },
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_trash_empty_action),
            summary = stringResource(R.string.drawer_trash_empty_message, trash.size),
            onDismissRequest = { confirmEmpty = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { confirmEmpty = false },
                    onConfirm = {
                        onEmptyTrash()
                        confirmEmpty = false
                    },
                )
            },
        ) {
        }
    }

    pendingDeleteForever?.let { conversation ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_trash_delete_forever_title),
            summary = stringResource(R.string.drawer_trash_delete_forever_message, conversation.title),
            onDismissRequest = { pendingDeleteForever = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { pendingDeleteForever = null },
                    onConfirm = {
                        currentOnDeleteForever(conversation.id)
                        pendingDeleteForever = null
                    },
                )
            },
        ) {
        }
    }
}

/** 回收站单行：标题 + 删除时间预览，尾部「恢复」「彻底删除」两枚图标按钮。 */
@Composable
private fun TrashRow(
    conversation: ConversationSummaryUi,
    segmentIndex: Int,
    segmentCount: Int,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val restoreDescription = stringResource(R.string.drawer_trash_restore)
    val deleteDescription = stringResource(R.string.drawer_trash_delete_forever)

    EtaSegmentedItem(index = segmentIndex, count = segmentCount) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(
                    bottom = if (segmentIndex == segmentCount - 1) 0.dp else EtaSegmentedGap,
                ),
        ) {
            EtaSegmentedRow(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    M3Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    M3Text(
                        text = conversation.timeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        M3IconButton(
                            onClick = onRestore,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.RestoreFromTrash,
                                contentDescription = restoreDescription,
                            )
                        }
                        M3IconButton(
                            onClick = onDeleteForever,
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteForever,
                                contentDescription = deleteDescription,
                            )
                        }
                    }
                },
            )
        }
    }
}

private const val TrashSearchHistoryKey = "trash"

/**
 * 回收站站内搜索：与消息搜索同一套 [EtaSearchPage] 骨架（返回 + 搜索框 + 查询历史），
 * 命中行复用回收站行样式，行内直接恢复或彻底删除。
 */
@Composable
internal fun TrashSearchScreen(
    onSearch: (String) -> List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var pendingDeleteForever by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    val currentOnDeleteForever by rememberUpdatedState(onDeleteForever)
    val currentOnRestore by rememberUpdatedState(onRestore)

    EtaSearchPage(
        searchPlaceholder = stringResource(R.string.conversation_search_hint),
        onBack = onBack,
        historyKey = TrashSearchHistoryKey,
    ) { query ->
        val hits = onSearch(query)
        if (hits.isEmpty()) {
            item(key = "empty") {
                EtaText(
                    text = stringResource(R.string.trash_search_empty),
                    style = EtaTextStyle.Body,
                    secondary = true,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }
        } else {
            itemsIndexed(
                items = hits,
                key = { _, conversation -> conversation.id },
                contentType = { _, _ -> "trash-search-hit" },
            ) { index, conversation ->
                Box(
                    modifier = Modifier.padding(horizontal = EtaGroupHorizontalPadding),
                ) {
                    TrashRow(
                        conversation = conversation,
                        segmentIndex = index,
                        segmentCount = hits.size,
                        onRestore = {
                            TouchHaptics.click(view)
                            // 恢复 / 彻底删除都算「这个词确实用上了」：与其它搜索页同口径落一条记录。
                            EtaSearchHistory.add(context, TrashSearchHistoryKey, query)
                            currentOnRestore(conversation.id)
                        },
                        onDeleteForever = {
                            TouchHaptics.click(view)
                            EtaSearchHistory.add(context, TrashSearchHistoryKey, query)
                            pendingDeleteForever = conversation
                        },
                    )
                }
            }
        }
    }

    pendingDeleteForever?.let { conversation ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_trash_delete_forever_title),
            summary = stringResource(R.string.drawer_trash_delete_forever_message, conversation.title),
            onDismissRequest = { pendingDeleteForever = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { pendingDeleteForever = null },
                    onConfirm = {
                        currentOnDeleteForever(conversation.id)
                        pendingDeleteForever = null
                    },
                )
            },
        ) {
        }
    }
}
