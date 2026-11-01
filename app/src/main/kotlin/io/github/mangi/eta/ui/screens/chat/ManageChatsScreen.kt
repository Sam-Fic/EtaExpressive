package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.components.ListEmptyState
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
    onDeleteAll: () -> Unit,
    onOpenSearchHistory: () -> Unit = {},
) {
    var showDeleteAll by remember { mutableStateOf(false) }
    // 单条删除不再走左滑手势：尾部删除按钮先记下目标，弹官方对话框二次确认。
    var pendingDelete by remember { mutableStateOf<ConversationSummaryUi?>(null) }
    // 对话框存活期间父级回调可能重建，确认时始终取最新的一份。
    val currentOnDelete by rememberUpdatedState(onDeleteConversation)
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

    EtaScaffoldPage(
        title = stringResource(R.string.history_page_title),
        onBack = onBack,
        listModifier = Modifier.streamDiagnosticMeasure("manage.lazy.measure").streamDiagnosticPlacement("manage.lazy.place").streamDiagnosticDraw("manage.lazy.draw"),
        actions = {
            TopBarIconButton(
                icon = Icons.Rounded.Search,
                contentDescription = stringResource(R.string.action_search_history),
                onClick = { onOpenSearchHistory() },
            )
            TopBarIconButton(
                icon = Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.history_page_delete_all),
                destructive = true,
                enabled = conversations.isNotEmpty(),
                onClick = { if (conversations.isNotEmpty()) showDeleteAll = true },
            )
        },
    ) {
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
                    onClick = { onOpenConversation(conversation.id) },
                    onTogglePin = { onTogglePin(conversation.id) },
                    onDelete = { pendingDelete = conversation },
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                            placementSpec = null,
                        )
                        .padding(horizontal = EtaGroupHorizontalPadding),
                )
            }
        }
    }

    if (showDeleteAll) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.history_page_delete_all_title),
            summary = stringResource(R.string.history_page_delete_all_message),
            onDismissRequest = { showDeleteAll = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.action_delete),
                    destructive = true,
                    onCancel = { showDeleteAll = false },
                    onConfirm = {
                        onDeleteAll()
                        showDeleteAll = false
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
 * 顶栏图标按钮。M3 用官方图标按钮（容器尺寸取 `IconButtonDefaults` 官方令牌，
 * 破坏性动作用 `IconButtonDefaults.iconButtonColors` 搭配 error 角色色）。
 */
@Composable
internal fun TopBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    val colors = if (destructive) {
        IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        )
    } else {
        IconButtonDefaults.iconButtonColors()
    }
    M3IconButton(
        onClick = {
            TouchHaptics.click(view)
            onClick()
        },
        enabled = enabled,
        colors = colors,
    ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/**
 * 管理页单行。
 *
 * KernelSU 式分段行（[EtaSegmentedItem] + [EtaSegmentedRow]，与抽屉会话列表同款）；
 * 尾部是两枚官方图标按钮——置顶用 [IconToggleButton]、删除用 error 色的 [M3IconButton]。
 * 删除**不再**依赖左滑手势，改为可见的尾部按钮。
 */
@Composable
private fun ManageChatRow(
    conversation: ConversationSummaryUi,
    segmentIndex: Int,
    segmentCount: Int,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val title = conversation.title.ifBlank { conversation.preview }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 置顶开关：选中态给官方 secondaryContainer 容器，一眼可辨。
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
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = pinDescription,
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

