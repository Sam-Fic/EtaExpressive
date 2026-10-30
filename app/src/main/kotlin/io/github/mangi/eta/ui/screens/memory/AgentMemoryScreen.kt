package io.github.mangi.eta.ui.screens.memory
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.layout.horizontalCutoutPadding
import io.github.mangi.eta.ui.model.AgentMemoryAction
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text
import androidx.compose.material3.TextButton as M3TextButton
import java.text.NumberFormat
import io.github.mangi.eta.ui.adaptive.EtaScaffold
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn

@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }
    // 编辑页只改助手自己的开关；全局总开关关闭时单独提示，避免把两者混为一谈。
    val enabledFlow = remember { AgentMemoryRepository.enabledFlow() }
    val globalEnabled by enabledFlow.collectAsState(
        initial = AgentMemoryRepository.isGloballyEnabled(),
    )

    EtaScaffold(
        title = if (state.assistantName.isBlank()) {
            stringResource(R.string.ui_memory_b55ff5)
        } else {
            stringResource(R.string.memory_editor_title, state.assistantName)
        },
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
    ) { paddingValues, scrollConnection, sidePadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .horizontalCutoutPadding()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            // 状态区与编辑器滚动分离。weight fill=false：内容少时只占自身高度，避免中部空档；
            // 空间不足（键盘弹出、横屏）时压缩为可滚动区域，编辑器保持完整可见
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .nestedScroll(scrollConnection),
                contentPadding = PaddingValues(
                    start = sidePadding,
                    end = sidePadding,
                ),
                overscrollEffect = null,
            ) {
                item(key = "status-card") {
                    EtaSegmentedColumn(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                        title = stringResource(R.string.ui_memory_b55ff5),
                    ) {
                        if (!globalEnabled) {
                            item {
                                EtaSegmentedRow(
                                    leadingContent = {
                                        // 严格官方默认尺寸/间距（裸 Icon），仅保留特殊状态着色。
                                        Icon(
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    supportingContent = {
                                        M3Text(
                                            text = stringResource(R.string.memory_global_off_notice_summary),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    content = {
                                        M3Text(
                                            text = stringResource(R.string.memory_global_off_notice_title),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                )
                            }
                        }
                        item {
                            EtaSwitchRow(
                                title = stringResource(R.string.ui_enable_memory_4b69b7),
                                summary = stringResource(R.string.assistant_memory_summary),
                                checked = state.enabled,
                                enabled = !state.isLoading,
                                onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
                            )
                        }
                        item {
                            EtaSegmentedRow(
                                supportingContent = {
                                    M3Text(
                                        text = stringResource(R.string.memory_budget_summary, formatNumber(state.coreBudgetChars)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                content = {
                                    M3Text(
                                        text = stringResource(R.string.ui_core_memory_injection_budget_48b5d5),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = sidePadding)
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = "MEMORY.md",
                ) {
                    item {
                        EtaSegmentedItemContainer {
                        Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = state.draft,
                            onValueChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                            label = { M3Text(stringResource(R.string.ui_core_memory_user_name_long_term_preferences_aa6ff9)) },
                            enabled = !state.isLoading && !state.isSaving,
                            minLines = 6,
                            maxLines = 12,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val overLimit = state.draftBytes > state.maxBytes
                            val statusColor = if (overLimit) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            M3Text(
                                text = when {
                                    overLimit -> stringResource(R.string.memory_over_limit)
                                    state.hasUnsavedChanges -> stringResource(R.string.memory_unsaved_changes)
                                    else -> ""
                                },
                                color = statusColor,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            M3Text(
                                text = "${formatBytes(state.draftBytes)} / 1 MiB",
                                color = statusColor,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            M3TextButton(
                                onClick = { showClearDialog = true },
                                enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                M3Text(stringResource(R.string.ui_clear_84fcd7))
                            }
                            Button(
                                onClick = { onAction(AgentMemoryAction.Save) },
                                enabled = state.canSave,
                                modifier = Modifier.weight(1f),
                            ) {
                                M3Text(if (state.isSaving) stringResource(R.string.memory_saving) else stringResource(R.string.memory_save))
                            }
                        }
                        }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.ui_clear_all_memory_a43bd3),
            summary = stringResource(R.string.ui_the_entire_contents_of_memory_md_will_be_deleted_and_83a8ac),
            onDismissRequest = { showClearDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.memory_clear),
                    destructive = true,
                    confirmEnabled = !state.isSaving,
                    onCancel = { showClearDialog = false },
                    onConfirm = {
                        showClearDialog = false
                        onAction(AgentMemoryAction.Clear)
                    },
                )
            },
        ) {
        }
    }

    state.notice?.let { notice ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.ui_memory_b55ff5),
            summary = notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
            actions = {
                // 官方文字按钮：形状、行高与配色均取官方默认值。
                M3TextButton(
                    onClick = { onAction(AgentMemoryAction.DismissNotice) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    M3Text(text = stringResource(R.string.ui_knew_cb63c6))
                }
            },
        ) {
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = NumberFormat.getIntegerInstance().format(value)
