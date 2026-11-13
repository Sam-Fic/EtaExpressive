package io.github.mangi.eta.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Deselect
import androidx.compose.material.icons.rounded.LabelOff
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaButtonStyle
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaFloatingToolbarConcentricPadding
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaIconButton
import io.github.mangi.eta.ui.adaptive.EtaIconSize
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.TopBarIconButton
import io.github.mangi.eta.ui.components.streamDiagnosticDraw
import io.github.mangi.eta.ui.components.streamDiagnosticMeasure
import io.github.mangi.eta.ui.components.streamDiagnosticPlacement
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ConversationSummaryUi

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

@Composable
internal fun ManageChatsScreen(
    conversations: List<ConversationSummaryUi>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onDeleteConversation: (ConversationSummaryUi) -> Unit,
    onDeleteAll: () -> Unit = {},
    onOpenSearchHistory: () -> Unit = {},
) {
    // 单条删除仍保留行尾按钮路径（非多选态）：先记下目标，弹官方对话框二次确认。
    var pendingDelete by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    // 对话框存活期间父级回调可能重建，确认时始终取最新的一份。
    val view = LocalView.current
    val currentOnDelete by rememberUpdatedState(onDeleteConversation)
    // 批量删除确认弹窗。
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换），长按行也可进入。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 多个会话同时在跑时，更新时间会不断把它们换到前面。管理页按打开时的顺序钉住，
    // 只在原地刷新标题和状态，行尾的置顶 / 删除按钮才点得中。新出现的会话插到最前，
    // 关掉页面再打开才重排。
    // The producer replaces immutable summary snapshots; row data always comes from the latest one.
    val currentIds = remember(conversations) { conversations.map { it.id } }
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
    // 可批量操作的行 = 全部行去掉调度台系统条目（与旧「清空全部」同一口径）。
    val selectable = remember(ordered) { ordered.filter { !it.isDispatchConsole } }
    val selectableIds = remember(selectable) { selectable.map { it.id }.toSet() }
    val allSelected = selectableIds.isNotEmpty() && selectedIds.containsAll(selectableIds)

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
        title = stringResource(R.string.history_page_title),
        onBack = onBack,
        listModifier = Modifier.streamDiagnosticMeasure("manage.lazy.measure").streamDiagnosticPlacement("manage.lazy.place").streamDiagnosticDraw("manage.lazy.draw"),
        actions = {
            TopBarIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.action_search_history),
                onClick = { onOpenSearchHistory() },
                shapes = IconButtonDefaults.shapes(),
            )
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。
            TopBarIconButton(
                icon = if (selectionMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                contentDescription = stringResource(
                    if (selectionMode) R.string.action_cancel else R.string.media_action_multiselect,
                ),
                onClick = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else selectionMode = true
                },
                shapes = IconButtonDefaults.shapes(),
            )
        },
        titleContent = {
            // 多选态把「已选 n 项」并入标题行（点号隔开），不另立计数行。
            M3Text(
                text = if (selectionMode) {
                    stringResource(R.string.history_page_title) + " · " +
                        stringResource(R.string.media_selected_count, selectedIds.size)
                } else {
                    stringResource(R.string.history_page_title)
                },
            )
        },
        content = {
            if (ordered.isEmpty()) {
                item(key = "empty") {
                    ListEmptyState(
                        title = stringResource(R.string.history_page_empty),
                    )
                }
            } else {
                itemsIndexed(
                    items = ordered,
                    key = { _, conversation -> conversation.id },
                    contentType = { _, _ -> "conversation" },
                ) { index, conversation ->
                    ManageChatRow(
                        conversation = conversation,
                        segmentIndex = index,
                        segmentCount = ordered.size,
                        selectionMode = selectionMode,
                        checked = conversation.id in selectedIds,
                        // 调度台是系统条目：不参与批量勾选（勾选集合与全选都不含它）。
                        checkEnabled = !conversation.isDispatchConsole,
                        onClick = {
                            if (selectionMode) {
                                if (!conversation.isDispatchConsole) {
                                    selectedIds = if (conversation.id in selectedIds) {
                                        selectedIds - conversation.id
                                    } else {
                                        selectedIds + conversation.id
                                    }
                                }
                            } else {
                                onOpenConversation(conversation.id)
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                selectionMode = true
                                if (!conversation.isDispatchConsole) {
                                    selectedIds = setOf(conversation.id)
                                }
                            }
                        },
                        onTogglePin = { onTogglePin(conversation.id) },
                        onDelete = { pendingDelete = conversation },
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            )
                            .padding(horizontal = EtaGroupHorizontalPadding),
                    )
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
            ChatSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                allPinned = selectedIds.isNotEmpty() &&
                    selectedIds.all { id -> byId[id]?.isPinned == true },
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        selectableIds.toSet()
                    }
                },
                onTogglePin = {
                    TouchHaptics.click(view)
                    // 以第一个选中项的目标状态为准：有未置顶 → 全部置顶；否则全部取消置顶。
                    val pin = selectedIds.any { id -> byId[id]?.isPinned == false }
                    selectedIds.forEach { id ->
                        val current = byId[id]?.isPinned ?: return@forEach
                        if (current != pin) onTogglePin(id)
                    }
                },
                onDelete = {
                    TouchHaptics.click(view)
                    showBatchDeleteDialog = true
                },
            )
        }
    }

    // 批量删除二次确认：文案与旧「清空全部」同口径（移入回收站，可恢复）。
    if (showBatchDeleteDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.history_page_delete_all_title),
            summary = stringResource(R.string.history_page_delete_all_message),
            onDismissRequest = { showBatchDeleteDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showBatchDeleteDialog = false },
                    onConfirm = {
                        selectedIds.forEach { id -> byId[id]?.let(currentOnDelete) }
                        showBatchDeleteDialog = false
                        exitSelection()
                    },
                )
            },
        ) {
        }
    }

    // 删除单条会话的二次确认：点按尾部删除按钮时记下目标行，确认后才真正删除。
    pendingDelete?.let { conversation ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.conversation_delete_title),
            summary = stringResource(R.string.conversation_delete_message),
            onDismissRequest = { pendingDelete = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.conversation_move_to_trash),
                    destructive = true,
                    onCancel = { pendingDelete = null },
                    onConfirm = {
                        currentOnDelete(conversation)
                        pendingDelete = null
                    },
                )
            },
        ) {
        }
    }
}

/**
 * 多选批量操作栏：官方 M3E [HorizontalFloatingToolbar]（纯图标药丸），底部居中悬浮，
 * 与模型管理页（ProviderModelsTab.ModelSelectionBar）同款装配。
 * 动作序：全选（全不选）→ 置顶 / 取消置顶 → 删除（error 色标破坏性，执行前另有确认弹窗）；
 * 「取消」不落底栏，由顶栏多选入口原位切换（Checklist→Close）承载，已选数量并入标题行文案。
 *
 * 组件结论（随同仓 ProviderModelsTab / MediaSearchScreen 同版本依赖的既有调用核证，非凭记忆）：
 * [HorizontalFloatingToolbar] 为 ExperimentalMaterial3ExpressiveApi；`leadingContent = {}`
 * 显式传空以归零官方 minimumInteractiveBalancedPadding 的高度补偿（本栏布局全显式，
 * 官方参数语义非 hack）；端盖同心取 [EtaFloatingToolbarConcentricPadding]`(smallContainerSize().width)`；
 * 全选/全不选与置顶用 EtaIconButton（`Standard` 档 + `EtaIconSize.S` = 官方 smallContainerSize /
 * smallIconSize 令牌）；删除用官方裸 [androidx.compose.material3.IconButton]（同尺寸令牌 +
 * `IconButtonDefaults.shapes()` 按压 morph），图标 error 色——EtaIconButton 无 tint 参数，
 * 裸按钮才好只用官方 token 表达破坏性配色。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ChatSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    allPinned: Boolean,
    onSelectAll: () -> Unit,
    onTogglePin: () -> Unit,
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
            // 显式空 leadingContent：官方 minimumInteractiveBalancedPadding 只在两侧都没有
            // leading/trailing 内容时才生效，会按内容里的 MinimumInteractive 对齐线补偿
            // 内边距、把药丸撑高；本栏布局完全显式（固定间距 + 同心端盖），传空让补偿归零。
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
                // 置顶 / 取消置顶：按当前勾选集合的状态自适应图标（有未置顶 → 全部置顶）。
                EtaIconButton(
                    icon = if (allPinned) Icons.Rounded.LabelOff else Icons.Rounded.PushPin,
                    contentDescription = stringResource(
                        if (allPinned) R.string.conversation_unpin else R.string.conversation_pin,
                    ),
                    onClick = onTogglePin,
                    enabled = selectedCount > 0,
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
 * 管理页单行。
 *
 * KernelSU 式分段行（[EtaSegmentedItem] + [EtaSegmentedRow]，与抽屉会话列表同款）。
 * 常规态尾部是两枚官方图标按钮——置顶用 [IconToggleButton]、删除用 error 色的
 * [androidx.compose.material3.IconButton]；多选态尾部换官方 [Checkbox]，点按/长按行
 * 都是勾选切换，长按也用于从常规态直接进入多选（勾中该行）。
 */
@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    segmentIndex: Int,
    segmentCount: Int,
    selectionMode: Boolean,
    checked: Boolean,
    checkEnabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val title = conversation.displayTitle.ifBlank { conversation.preview }
    val pinDescription = stringResource(
        if (conversation.isPinned) R.string.conversation_unpin else R.string.conversation_pin,
    )
    val deleteDescription = stringResource(R.string.conversation_move_to_trash)
    val statusPresent = conversation.isActiveRun || conversation.hasCompletionMarker

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
                    if (selectionMode) {
                        // 多选态：官方 Checkbox。调度台是系统条目，不参与勾选。
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { onClick() },
                            enabled = checkEnabled,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (conversation.isDispatchConsole) {
                                // 系统条目：置顶强制成立，只显示静态标记，不提供取消置顶与删除。
                                Icon(
                                    imageVector = Icons.Rounded.PushPin,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(EtaDimens.DenseIconSize),
                                )
                            } else {
                                // 置顶开关：选中态给官方 secondaryContainer 容器，一眼可辨。
                                EtaTooltip(text = pinDescription) {
                                    IconToggleButton(
                                        checked = conversation.isPinned,
                                        onCheckedChange = { checked ->
                                            TouchHaptics.toggle(view, checked)
                                            onTogglePin()
                                        },
                                        colors = IconButtonDefaults.iconToggleButtonColors(
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            checkedContainerColor =
                                                MaterialTheme.colorScheme.secondaryContainer,
                                            checkedContentColor =
                                                MaterialTheme.colorScheme.onSecondaryContainer,
                                        ),
                                        shapes = IconButtonDefaults.toggleableShapes(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PushPin,
                                            contentDescription = pinDescription,
                                        )
                                    }
                                }
                                // 删除：破坏性动作取官方 error 角色色。
                                EtaTooltip(text = deleteDescription) {
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
                        }
                    }
                },
            )
        }
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
