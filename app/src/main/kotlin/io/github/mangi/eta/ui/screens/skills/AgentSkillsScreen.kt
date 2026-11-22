package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.ToggleOff
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaLoadingSlot
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGap
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItem
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSelectionAction
import io.github.mangi.eta.ui.adaptive.EtaSelectionBar
import io.github.mangi.eta.ui.adaptive.EtaSelectionBarOverlay
import io.github.mangi.eta.ui.adaptive.EtaSelectionBottomSpacer
import io.github.mangi.eta.ui.adaptive.EtaSelectionTopBarAction
import io.github.mangi.eta.ui.adaptive.rememberEtaLoadingShown
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.EtaStatusPage
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import androidx.compose.material3.Button as M3Button
import androidx.compose.material3.ButtonDefaults as M3ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    // 多选态与勾选集合：入口在顶栏（Checklist→Close 原位切换），长按行也可进入。
    // 批量启停/删除逐条走既有单条 API（state 层循环），不新建批量 API。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    val operationPending = state.isImporting || state.busySkillId != null || state.isBatchBusy
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

    // 可勾选全集 = 内置 + 用户已安装技能；已移除组只能重装，不参与多选（多选态整组隐藏）。
    val installed = state.skills.filter { it.installed }
    val selectableSkills = remember(installed) { installed }
    val allSelected = selectableSkills.isNotEmpty() &&
        selectedIds.containsAll(selectableSkills.map { it.id })
    val selectedSkills = remember(selectableSkills, selectedIds) {
        selectableSkills.filter { it.id in selectedIds }
    }
    // 批量启停的目标状态按勾选集算（勾集中有停用 → 全部启用），启停图标口径与之一致。
    val selectedAllEnabled = selectedSkills.isNotEmpty() && selectedSkills.all { it.enabled }
    // 删除资格分离：勾选集混入内置技能（只能移除重装、不可删）时删除禁用。
    val selectedAllDeletable = selectedSkills.all { it.canDeleteUserSkill }

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
        title = stringResource(R.string.route_skills),
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
        listState = listState,
        // 安装入口从「安装」卡片换成 MCP 服务器页同款 FAB：滚动收起、回顶展开。
        // 多选态隐藏：批量操作栏占住底部，FAB 让位。
        floatingActionButton = if (!selectionMode) {
            {
                EtaExtendedFab(
                    text = stringResource(R.string.skills_import_zip),
                    icon = Icons.Rounded.Add,
                    expanded = fabExpanded,
                    onClick = { if (!operationPending) openZipPicker() },
                )
            }
        } else {
            null
        },
        actions = {
            // 多选入口原位切换：Checklist→Close，退出并清空勾选。无技能可管时禁用。
            EtaSelectionTopBarAction(
                selectionMode = selectionMode,
                onToggle = {
                    TouchHaptics.click(view)
                    if (selectionMode) exitSelection() else selectionMode = true
                },
                enabled = selectableSkills.isNotEmpty() || selectionMode,
            )
        },
    ) {
        val builtinInstalled = installed.filter { it.source == "builtin" }
        val userInstalled = installed.filter { it.canDeleteUserSkill }
        val removed = state.skills.filter { !it.installed }

        if (builtinInstalled.isNotEmpty()) {
            // 技能行升格为顶层 LazyColumn item（会话管理页/取码管理页同款）：官方
            // animateItem 承载删除淡出与重装跨组换位弹簧；分段外观由 EtaSegmentedItem
            // 逐行注入。组标题独立成 item（多选态把「已选 n 项」并入标题，不另立计数行）。
            item(key = "builtin-card") {
                M3Text(
                    text = if (selectionMode) {
                        stringResource(R.string.ui_built_in_skills_1ceedf) + " · " +
                            stringResource(R.string.media_selected_count, selectedIds.size)
                    } else {
                        stringResource(R.string.ui_built_in_skills_1ceedf)
                    },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        bottom = EtaSegmentedTitleSpacing,
                    ),
                )
            }
            itemsIndexed(
                items = builtinInstalled,
                key = { _, skill -> skill.id },
                contentType = { _, _ -> "skill" },
            ) { index, skill ->
                EtaSegmentedItem(index = index, count = builtinInstalled.size) {
                    Column(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            )
                            .fillMaxWidth()
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = if (index == builtinInstalled.lastIndex) {
                                    EtaSegmentedGroupSpacing
                                } else {
                                    EtaSegmentedGap
                                },
                            ),
                    ) {
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            selectionMode = selectionMode,
                            checked = skill.id in selectedIds,
                            onCheckedChange = {
                                selectedIds = if (skill.id in selectedIds) {
                                    selectedIds - skill.id
                                } else {
                                    selectedIds + skill.id
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(skill.id)
                                }
                            },
                        )
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user-card") {
                M3Text(
                    text = if (selectionMode) {
                        stringResource(R.string.ui_user_skills_748e7f) + " · " +
                            stringResource(R.string.media_selected_count, selectedIds.size)
                    } else {
                        stringResource(R.string.ui_user_skills_748e7f)
                    },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        top = EtaSegmentedGroupSpacing,
                        bottom = EtaSegmentedTitleSpacing,
                    ),
                )
            }
            itemsIndexed(
                items = userInstalled,
                key = { _, skill -> skill.id },
                contentType = { _, _ -> "skill" },
            ) { index, skill ->
                EtaSegmentedItem(index = index, count = userInstalled.size) {
                    Column(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            )
                            .fillMaxWidth()
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = if (index == userInstalled.lastIndex) {
                                    EtaSegmentedGroupSpacing
                                } else {
                                    EtaSegmentedGap
                                },
                            ),
                    ) {
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            onDelete = { deleteTarget = skill },
                            selectionMode = selectionMode,
                            checked = skill.id in selectedIds,
                            onCheckedChange = {
                                selectedIds = if (skill.id in selectedIds) {
                                    selectedIds - skill.id
                                } else {
                                    selectedIds + skill.id
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedIds = setOf(skill.id)
                                }
                            },
                        )
                    }
                }
            }
        }

        // 多选态不管理已移除组：它只能整组重装，不可勾选也不可启停/删除（对齐分类页
        // 多选态隐藏远程设备组的先例）。
        if (removed.isNotEmpty() && !selectionMode) {
            item(key = "removed-card") {
                M3Text(
                    text = stringResource(R.string.ui_removed_4e5c49),
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = EtaGroupHorizontalPadding,
                        end = EtaGroupHorizontalPadding,
                        top = EtaSegmentedGroupSpacing,
                        bottom = EtaSegmentedTitleSpacing,
                    ),
                )
            }
            itemsIndexed(
                items = removed,
                key = { _, skill -> skill.id },
                contentType = { _, _ -> "skill-removed" },
            ) { index, skill ->
                EtaSegmentedItem(index = index, count = removed.size) {
                    Column(
                        modifier = Modifier
                            .animateItem(
                                fadeInSpec = null,
                                fadeOutSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                                placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                            )
                            .fillMaxWidth()
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = if (index == removed.lastIndex) {
                                    EtaSegmentedGroupSpacing
                                } else {
                                    EtaSegmentedGap
                                },
                            ),
                    ) {
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

        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                // 空态升格为整页状态页（与搜索页官方 Empty state 同观感）；安装入口
                // 交给右下角 FAB，避免重复按钮。removed 组以 skills 为全集，零态时必空。
                EtaStatusPage(
                    icon = Icons.Rounded.Extension,
                    title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                    summary = stringResource(R.string.skills_choose_package),
                    modifier = Modifier.fillParentMaxSize(),
                )
            }
        }

        item(key = "bottom_spacer", contentType = "spacer") {
            // 多选操作栏悬浮在底部时预留高度，避免遮挡最后一个列表项。
            EtaSelectionBottomSpacer(isSelecting = selectionMode)
        }
    }

    // 批量操作栏：共享多选套件的官方 M3E 浮动药丸，底部居中悬浮。
    // 动作序：全选（全不选）→ 批量启停 → 删除（error 色标破坏性，执行前另有确认弹窗）。
    Box(modifier = Modifier.fillMaxSize()) {
        EtaSelectionBarOverlay(visible = selectionMode) {
            EtaSelectionBar(
                selectedCount = selectedIds.size,
                allSelected = allSelected,
                enabled = !operationPending,
                onSelectAll = {
                    TouchHaptics.click(view)
                    selectedIds = if (allSelected) {
                        emptySet()
                    } else {
                        selectableSkills.map { it.id }.toSet()
                    }
                },
                actions = listOf(
                    // 批量启停：按当前勾选集合的状态自适应图标（有停用 → 全部启用）。
                    EtaSelectionAction(
                        icon = if (selectedAllEnabled) Icons.Rounded.ToggleOff else Icons.Rounded.ToggleOn,
                        contentDescription = stringResource(
                            if (selectedAllEnabled) R.string.recipe_disable else R.string.recipe_enable,
                        ),
                        onClick = {
                            TouchHaptics.click(view)
                            onAction(AgentSkillsAction.ToggleSkills(selectedIds, !selectedAllEnabled))
                        },
                    ),
                    // 删除是破坏性操作：error 色图标区分；勾选集混入内置技能（不可删，
                    // 只能移除重装）时禁用——删除资格与启停分离。
                    EtaSelectionAction(
                        icon = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        onClick = {
                            TouchHaptics.click(view)
                            showBatchDeleteDialog = true
                        },
                        enabled = selectedAllDeletable,
                        destructive = true,
                    ),
                ),
            )
        }
    }

    // 批量删除二次确认：逐条走既有 deleteSkill（整目录备份 + journal 回滚），不新建批量 API。
    if (showBatchDeleteDialog) {
        EtaDialog(
            show = true,
            title = stringResource(R.string.ui_delete_user_skills_f319a9),
            summary = stringResource(R.string.skills_batch_delete_message, selectedIds.size),
            onDismissRequest = { showBatchDeleteDialog = false },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.ui_delete_3755f5),
                    destructive = true,
                    confirmEnabled = !operationPending,
                    onCancel = { showBatchDeleteDialog = false },
                    onConfirm = {
                        showBatchDeleteDialog = false
                        onAction(AgentSkillsAction.DeleteSkills(selectedIds))
                        exitSelection()
                    },
                )
            },
        ) {
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
 * 加载态经 [EtaLoadingSlot] 统一封装。
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
            // 技能行的加载态常一闪而过：门控 120ms 后再显示，显示后至少保留 400ms。
            if (rememberEtaLoadingShown(iconLoading)) {
                EtaLoadingSlot(
                    progress = null,
                    size = EtaDimens.RowLeadingLoadingSize,
                )
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
