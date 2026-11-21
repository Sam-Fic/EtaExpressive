package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
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
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
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
import io.github.mangi.eta.ui.adaptive.EtaSelectionAction
import io.github.mangi.eta.ui.adaptive.EtaSelectionBar
import io.github.mangi.eta.ui.adaptive.EtaSelectionBarOverlay
import io.github.mangi.eta.ui.adaptive.EtaSelectionBottomSpacer
import io.github.mangi.eta.ui.adaptive.EtaSelectionTopBarAction
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationSummaryUi

/**
 * 回收站页面：已删除会话按删除时间由新到旧排列，行尾支持恢复与彻底删除。
 * 顶栏提供多选入口（Checklist→Close 原位切换）：多选态底部浮出批量操作栏
 * （全选 / 恢复 / 彻底删除），已选数量并入标题行；返回键优先退出多选。
 * 旧「清空回收站」由「多选 → 全选 → 彻底删除」替代。进入页面时先按留存期自动清理一次过期会话。
 */
@Composable
internal fun TrashScreen(
    trash: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onDeleteForever: (String) -> Unit,
    onPurgeExpired: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    val view = LocalView.current
    // 进入即清一次过期回收站：与工具列回收站、启动水合共用同一门面。
    LaunchedEffect(Unit) { onPurgeExpired() }
    // 批量彻底删除确认弹窗。
    var showBatchPurgeDialog by remember { mutableStateOf(false) }
    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换），长按行也可进入。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 单条彻底删除仍保留行尾按钮路径（非多选态）：先记下目标，弹官方对话框二次确认。
    var pendingDeleteForever by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    val currentOnDeleteForever by rememberUpdatedState(onDeleteForever)
    val currentOnRestore by rememberUpdatedState(onRestore)
    val allSelected = trash.isNotEmpty() && selectedIds.containsAll(trash.map { it.id })

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
        title = stringResource(R.string.drawer_trash_title),
        onBack = onBack,
        actions = {
            TopBarIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.trash_search_title),
                onClick = onOpenSearch,
                enabled = trash.isNotEmpty(),
                shapes = IconButtonDefaults.shapes(),
            )
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。多选态保持可用（对齐其他管理页）。
            EtaSelectionTopBarAction(
                selectionMode = selectionMode,
                onToggle = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else selectionMode = true
                },
                enabled = trash.isNotEmpty() || selectionMode,
            )
        },
        titleContent = {
            // 多选态把「已选 n 项」并入标题行（点号隔开），不另立计数行。
            M3Text(
                text = if (selectionMode) {
                    stringResource(R.string.drawer_trash_title) + " · " +
                        stringResource(R.string.media_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.drawer_trash_title)
                },
            )
        },
        content = {
            if (trash.isEmpty()) {
                item(key = "empty") {
                    ListEmptyState(
                        title = stringResource(R.string.drawer_trash_empty),
                    )
                }
            } else {
                itemsIndexed(
                    items = trash,
                    key = { _, conversation -> conversation.id },
                    contentType = { _, _ -> "trash-conversation" },
                ) { index, conversation ->
                    Box(
                        // 官方 animateItem（会话管理页同款口径）：恢复/彻底删除淡出，下方行弹簧补位；
                        // 入场不播动画，避免首屏全列表闪淡入。
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            )
                            .padding(horizontal = EtaGroupHorizontalPadding),
                    ) {
                        TrashRow(
                            conversation = conversation,
                            segmentIndex = index,
                            segmentCount = trash.size,
                            selectionMode = selectionMode,
                            checked = conversation.id in selectedIds,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (conversation.id in selectedIds) {
                                        selectedIds - conversation.id
                                    } else {
                                        selectedIds + conversation.id
                                    }
                                }
                                // 非多选态整行不响应：恢复/彻底删除仍走行尾按钮（与改造前一致）。
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(conversation.id)
                                }
                            },
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
                item(key = "bottom_spacer", contentType = "spacer") {
                    // 多选操作栏悬浮在底部时预留高度，避免遮挡最后一个列表项。
                    EtaSelectionBottomSpacer(isSelecting = selectionMode)
                }
            }
        },
    )

    // 批量操作栏：共享多选套件的官方 M3E 浮动药丸，底部居中悬浮。
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
                        trash.map { it.id }.toSet()
                    }
                },
                actions = listOf(
                    // 恢复免确认：执行后直接退出多选（回收站语义，条目回到原会话列表）。
                    EtaSelectionAction(
                        icon = Icons.Rounded.RestoreFromTrash,
                        contentDescription = stringResource(R.string.drawer_trash_restore),
                        onClick = {
                            TouchHaptics.click(view)
                            selectedIds.forEach(currentOnRestore)
                            exitSelection()
                        },
                    ),
                    // 彻底删除是破坏性操作：error 色图标区分，执行前另有确认弹窗。
                    EtaSelectionAction(
                        icon = Icons.Rounded.DeleteForever,
                        contentDescription = stringResource(R.string.drawer_trash_delete_forever),
                        onClick = {
                            TouchHaptics.click(view)
                            showBatchPurgeDialog = true
                        },
                        destructive = true,
                    ),
                ),
            )
        }
    }

    // 批量彻底删除二次确认：不可逆，文案口径与单条彻底删除一致。
    if (showBatchPurgeDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.drawer_trash_delete_forever_title),
            summary = stringResource(R.string.drawer_trash_batch_purge_message, selectedIds.size),
            onDismissRequest = { showBatchPurgeDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showBatchPurgeDialog = false },
                    onConfirm = {
                        selectedIds.forEach(currentOnDeleteForever)
                        showBatchPurgeDialog = false
                        exitSelection()
                    },
                )
            },
        ) {
        }
    }

    // 删除单条的二次确认：点按行尾按钮时记下目标行，确认后才真正删除。
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

/** 回收站单行：标题 + 删除时间预览。常规态尾部「恢复」「彻底删除」两枚图标按钮；多选态尾部换官方 Checkbox。 */
@Composable
private fun TrashRow(
    conversation: ConversationSummaryUi,
    segmentIndex: Int,
    segmentCount: Int,
    selectionMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.action_enter_selection),
                modifier = Modifier.fillMaxWidth(),
                content = {
                    M3Text(
                        text = conversation.title,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    M3Text(
                        text = conversation.timeLabel,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.IconButton(
                                onClick = onRestore,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.RestoreFromTrash,
                                    contentDescription = restoreDescription,
                                )
                            }
                            androidx.compose.material3.IconButton(
                                onClick = onDeleteForever,
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteForever,
                                    contentDescription = deleteDescription,
                                )
                            }
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
                ListEmptyState(
                    title = stringResource(R.string.trash_search_empty),
                )
            }
        } else {
            itemsIndexed(
                items = hits,
                key = { _, conversation -> conversation.id },
                contentType = { _, _ -> "trash-search-hit" },
            ) { index, conversation ->
                Box(
                    // 恢复/彻底删除淡出 + 补位弹簧（主回收站列表同款口径）。
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        )
                        .padding(horizontal = EtaGroupHorizontalPadding),
                ) {
                    // 搜索命中行不进多选：仍用常规行尾按钮（与改造前一致）。
                    TrashRow(
                        conversation = conversation,
                        segmentIndex = index,
                        segmentCount = hits.size,
                        selectionMode = false,
                        checked = false,
                        onClick = {},
                        onLongClick = {},
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
