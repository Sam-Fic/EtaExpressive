package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text

private val CardHorizontalPadding = 12.dp
private val CardBottomPadding = 12.dp

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    val operationPending = state.isImporting || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)

    EtaScaffoldPage(
        title = stringResource(R.string.route_skills),
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
        listState = listState,
        // 安装入口从「安装」卡片换成 MCP 服务器页同款 FAB：滚动收起、回顶展开。
        floatingActionButton = {
            EtaExtendedFab(
                text = stringResource(R.string.skills_import_zip),
                icon = Icons.Rounded.Add,
                expanded = fabExpanded,
                onClick = { if (!operationPending) openZipPicker() },
            )
        },
    ) {
        val installed = state.skills.filter { it.installed }
        val builtinInstalled = installed.filter { it.source == "builtin" }
        val userInstalled = installed.filter { it.canDeleteUserSkill }
        val removed = state.skills.filter { !it.installed }

        if (builtinInstalled.isNotEmpty()) {
            item(key = "builtin-card") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.ui_built_in_skills_1ceedf),
                ) {
                    builtinInstalled.forEach { skill ->
                        item {
                            SkillSwitchRow(
                                skill = skill,
                                enabled = !operationPending,
                                onToggle = { enabled ->
                                    onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                },
                            )
                        }
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user-card") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.ui_user_skills_748e7f),
                ) {
                    userInstalled.forEach { skill ->
                        item {
                            SkillSwitchRow(
                                skill = skill,
                                enabled = !operationPending,
                                onToggle = { enabled ->
                                    onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                },
                                onDelete = { deleteTarget = skill },
                            )
                        }
                    }
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-card") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                    title = stringResource(R.string.ui_removed_4e5c49),
                ) {
                    removed.forEach { skill ->
                        item {
                            SkillActionRow(
                                title = skill.name,
                                summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                                icon = iconForSkill(skill.id),
                                enabled = !operationPending,
                                onClick = { onAction(AgentSkillsAction.ReinstallBuiltin(skill.id)) },
                            )
                        }
                    }
                }
            }
        }

        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                // 空态的安装入口交给右下角 FAB，避免重复按钮。
                ListEmptyState(
                    title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                    summary = stringResource(R.string.skills_choose_package),
                )
            }
        }
    }

    state.replacement?.let { replacement ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.ui_replace_user_skills_250e98),
            summary = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
            onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.skills_replace),
                    confirmEnabled = !operationPending,
                    onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                    onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
                )
            },
        ) {
        }
    }

    deleteTarget?.let { skill ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.ui_delete_user_skills_f319a9),
            summary = stringResource(R.string.skills_delete_summary, skill.name),
            onDismissRequest = { deleteTarget = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.ui_delete_3755f5),
                    destructive = true,
                    confirmEnabled = !operationPending,
                    onCancel = { deleteTarget = null },
                    onConfirm = {
                        deleteTarget = null
                        onAction(AgentSkillsAction.DeleteSkill(skill.id))
                    },
                )
            },
        ) {
        }
    }

    state.notice?.let { notice ->
        EtaDialog(
            show = true,
            title = notice.title,
            summary = notice.message,
            onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
            actions = {
                // 官方按钮：错误提示用 error 配色，正常提示用默认主色。
                M3Button(
                    onClick = { onAction(AgentSkillsAction.DismissNotice) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (notice.isError) {
                        M3ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        M3ButtonDefaults.buttonColors()
                    },
                ) {
                    M3Text(text = stringResource(R.string.ui_knew_cb63c6))
                }
            },
        ) {
        }
    }
}

/**
 * 可点击的技能行。
 *
 * 走官方分段行 [EtaSegmentedRow]（自带涟漪与语义），加载态用官方 M3E
 * [LoadingIndicator]（morph 变形，尺寸收敛进图标槽）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SkillActionRow(
    title: String,
    summary: String,
    icon: ImageVector,
    enabled: Boolean,
    iconLoading: Boolean = false,
    onClick: () -> Unit,
) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = {
            if (iconLoading) {
                LoadingIndicator(modifier = Modifier.size(24.dp))
            } else {
                Icon(imageVector = icon, contentDescription = null)
            }
        },
        content = {
            M3Text(text = title)
        },
        supportingContent = {
            M3Text(text = summary)
        },
    )
}
