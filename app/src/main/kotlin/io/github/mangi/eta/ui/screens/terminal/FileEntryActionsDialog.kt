package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton

@Immutable
internal data class FileEntryActions(
    val path: String,
    val name: String,
    val directory: Boolean,
    val canExport: Boolean,
    val canDelete: Boolean,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FileEntryActionsDialog(
    target: FileEntryActions,
    enabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    EtaDialog(
        show = true,
        title = target.name,
        onDismissRequest = { if (enabled) onDismiss() },
        actions = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 官方按钮：形状、行高与配色全部使用官方默认值。
                if (target.canExport) {
                    OutlinedButton(
                        onClick = onExport,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        shapes = M3ButtonDefaults.shapes(),
                    ) {
                        M3Text(text = stringResource(R.string.linux_files_export))
                    }
                }
                if (target.canDelete) {
                    Button(
                        onClick = onDelete,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                        colors = M3ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        shapes = M3ButtonDefaults.shapes(),
                    ) {
                        M3Text(text = stringResource(R.string.action_delete))
                    }
                }
                M3TextButton(
                    onClick = onDismiss,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    M3Text(text = stringResource(R.string.action_cancel))
                }
            }
        },
    ) {
    }
}

/**
 * 文件行尾「更多」按钮 + Material 3 Expressive 操作菜单。
 *
 * 结构照 KernelSU 顶栏 `IconButton + DropdownMenuPopup` 就地写法：菜单挂在按钮内容里，
 * 由官方弹出层自动锚定到该按钮；容器与条目形状全部取官方 Expressive 默认值
 * （与「上下文压缩 → 请求接口」那个下拉同款），破坏性的「删除」用 error 色强调。
 */
@Composable
internal fun EtaFileActionsButton(
    canExport: Boolean,
    canDelete: Boolean,
    enabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val itemCount = (if (canExport) 1 else 0) + (if (canDelete) 1 else 0)
    if (itemCount == 0) return

    val haptic = LocalHapticFeedback.current
    var expanded by remember { mutableStateOf(false) }

    val moreDescription = stringResource(R.string.action_more)
    EtaTooltip(text = moreDescription) {
        IconButton(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                expanded = true
            },
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = moreDescription,
            )
            EtaMenuPopup(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                EtaMenuGroup(index = 0, count = 1) {
                    if (canExport) {
                        DropdownMenuItem(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                expanded = false
                                onExport()
                            },
                            text = { M3Text(text = stringResource(R.string.linux_files_export)) },
                            shape = EtaMenuItemShape(index = 0, count = itemCount),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                )
                            },
                        )
                    }
                    if (canDelete) {
                        DropdownMenuItem(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                                expanded = false
                                onDelete()
                            },
                            text = {
                                M3Text(
                                    text = stringResource(R.string.action_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                            shape = EtaMenuItemShape(
                                index = if (canExport) 1 else 0,
                                count = itemCount,
                            ),
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}
