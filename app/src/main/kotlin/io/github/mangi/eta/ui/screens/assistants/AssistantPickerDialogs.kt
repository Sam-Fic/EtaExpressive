package io.github.mangi.eta.ui.screens.assistants

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.AssistantPrompt
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.adaptive.EtaAlphas
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.rememberEtaSheetState
import io.github.mangi.eta.ui.components.AssistantAvatar
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text as M3Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantPickerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    selectedAssistantId: String? = null,
) {
    val scope = rememberCoroutineScope()
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = selectedAssistantId ?: repositoryActiveId
    var searchQuery by remember { mutableStateOf("") }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }

    LaunchedEffect(show) {
        if (!show) {
            searchQuery = ""
            deleteProfile = null
        }
    }

    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }

    // Material 3：官方 ModalBottomSheet（布局复刻 KernelSU MultiSelectDialog：
    // 标题行 + 关闭按钮、搜索框、可滚动分段列表；仅 Hidden↔Expanded 两态，不进半展开）。
    if (show) {
        ModalBottomSheet(
            onDismissRequest = {
                if (deleteProfile == null) onDismiss()
            },
            sheetState = rememberEtaSheetState(),
        ) {
            val deleteTarget = deleteProfile
            if (deleteTarget != null) {
                // 确认在原面板内完成：不再在面板之上叠一层弹窗。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                ) {
                    AssistantDeleteConfirmPanel(
                        profile = deleteTarget,
                        canDelete = profiles.size > 1,
                        onCancel = { deleteProfile = null },
                        onConfirm = {
                            deleteProfile = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    AssistantRepository.delete(deleteTarget.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                                }
                            }
                        },
                    )
                }
            } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 关闭走面板下拉 / 点遮罩 / 返回键，不放右上角关闭按钮。
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    M3Text(
                        text = stringResource(R.string.assistant_list_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                AssistantSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = stringResource(R.string.assistant_search),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    // 助手合并为一个 KernelSU 式分段组，行尾三点菜单提供「复制/编辑/删除」。
                    EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                        filtered.forEach { profile ->
                            item(key = profile.id) {
                                AssistantRow(
                                    profile = profile,
                                    selected = profile.id == activeId,
                                    onOpen = {
                                        onSelect(profile.id)
                                        onDismiss()
                                    },
                                    onCopy = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                AssistantRepository.duplicate(profile.id)
                                            }
                                        }
                                    },
                                    onEdit = {
                                        onDismiss()
                                        onEdit(profile.id)
                                    },
                                    onDelete = { deleteProfile = profile },
                                )
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        M3Text(
                            text = stringResource(R.string.assistant_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * 面板内的删除确认：与历史清理一致，在底部面板原位完成，不在面板上再叠弹窗。
 */
@Composable
internal fun AssistantDeleteConfirmPanel(
    profile: AssistantProfile,
    canDelete: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val view = LocalView.current
    // 数据层已硬拦内置助手；确认按钮再兜一道，避免任何调用点绕过行内菜单直达本面板。
    val deletable = canDelete && !AssistantPrompt.isBuiltIn(profile.id)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        M3Text(
            text = stringResource(R.string.assistant_delete_title),
            style = MaterialTheme.typography.titleLarge,
        )
        M3Text(
            text = stringResource(R.string.assistant_delete_body, profile.name),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { TouchHaptics.click(view); onCancel() }) {
                M3Text(stringResource(R.string.action_cancel))
            }
            TextButton(
                enabled = deletable,
                onClick = { TouchHaptics.click(view); onConfirm() },
            ) {
                M3Text(
                    text = stringResource(R.string.action_delete),
                    color = if (deletable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = EtaAlphas.DisabledContent)
                    },
                )
            }
        }
    }
}

/**
 * 助手条目：[Card] + `combinedClickable`（长按弹操作对话框）。
 */
@Composable
internal fun AssistantProfileCard(
    profile: AssistantProfile,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = {
                        TouchHaptics.click(view)
                        onClick()
                    },
                    onLongClick = {
                        TouchHaptics.longPress(view)
                        onLongClick()
                    },
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(assistant = profile, size = 32.dp)
            M3Text(
                text = profile.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp),
            )
            if (selected) {
                M3Text(
                    text = stringResource(R.string.assistant_current),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 助手行：KernelSU 式分段行 + 行尾官方三点菜单（与 Skills 页的技能行同一套交互）。
 *
 * 「复制 / 编辑 / 删除」收进官方 M3E 下拉菜单，行本身只负责打开助手；删除仍需二次确认。
 */
@Composable
internal fun AssistantRow(
    profile: AssistantProfile,
    selected: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // 内置助手是系统身份：只藏掉删除项，复制/编辑/选中照旧可用（与调度台会话「不显示删除入口」同款）。
    val builtIn = AssistantPrompt.isBuiltIn(profile.id)
    val actionCount = if (builtIn) 2 else 3
    EtaSegmentedRow(
        selected = selected,
        choiceRow = true,
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        leadingContent = { AssistantAvatar(assistant = profile, size = 32.dp) },
        content = {
            M3Text(
                text = profile.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 选中指示从「Current」文案改为行尾对勾（与模型列表同款，不再用文字标记）。
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                    )
                }
                Box {
                    val moreDescription = stringResource(R.string.assistant_more_named, profile.name)
                    EtaTooltip(text = moreDescription) {
                        IconButton(onClick = { menuExpanded = true }, shapes = IconButtonDefaults.shapes(), ) {
                            Icon(
                                imageVector = Icons.Rounded.MoreHoriz,
                                contentDescription = moreDescription,
                            )
                        }
                    }
                    EtaMenuPopup(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        EtaMenuGroup(index = 0, count = 1) {
                            DropdownMenuItem(
                                text = { M3Text(text = stringResource(R.string.ui_copy_4edd1d)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                    )
                                },
                                shape = EtaMenuItemShape(index = 0, count = actionCount),
                                onClick = {
                                    menuExpanded = false
                                    onCopy()
                                },
                            )
                            DropdownMenuItem(
                                text = { M3Text(text = stringResource(R.string.ui_edit_a7f814)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = null,
                                    )
                                },
                                shape = EtaMenuItemShape(index = 1, count = actionCount),
                                onClick = {
                                    menuExpanded = false
                                    onEdit()
                                },
                            )
                            if (!builtIn) {
                                DropdownMenuItem(
                                    text = {
                                        M3Text(
                                            text = stringResource(R.string.action_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    shape = EtaMenuItemShape(index = 2, count = actionCount),
                                    onClick = {
                                        menuExpanded = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun AssistantDeleteDialog(
    profile: AssistantProfile,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    EtaDialog(
        show = true,
        title = stringResource(R.string.assistant_delete_title),
        summary = stringResource(R.string.assistant_delete_body, profile.name),
        onDismissRequest = onDismiss,
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                confirmEnabled = canDelete && !AssistantPrompt.isBuiltIn(profile.id),
                onCancel = onDismiss,
                onConfirm = onConfirm,
            )
        },
    ) {
    }
}
