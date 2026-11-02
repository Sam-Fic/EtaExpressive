package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.ConversationSubAgentConfig
import io.github.mangi.eta.agent.delegation.ConversationSubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentConfigKey
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaNoticeText
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ConversationSubAgentEditor
import io.github.mangi.eta.ui.components.LocalConversationSubAgentEditor
import io.github.mangi.eta.ui.components.SubAgentDropdownMenu
import io.github.mangi.eta.ui.components.SubAgentEditorState
import io.github.mangi.eta.ui.components.SubAgentPresetDirectory
import io.github.mangi.eta.ui.components.SubAgentPresetNameDialog
import io.github.mangi.eta.ui.components.SubAgentProfileConfigDialog
import io.github.mangi.eta.ui.components.SubAgentProfileDraftSession
import io.github.mangi.eta.ui.components.SubAgentProfileRow
import io.github.mangi.eta.ui.components.WithoutPressRipple
import io.github.mangi.eta.ui.components.etaRippleConfiguration
import io.github.mangi.eta.ui.components.rememberSubAgentPresetDirectory
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 子代理预设组详情：组内子代理列表。
 *
 * 这是独立的导航目标，而不是列表页内部的 `selectedId` 分支：入栈/出栈交给 NavHost，
 * 因此拥有和 MCP 详情、模型供应商详情一致的滑入淡出转场与预测性返回。
 */
@Composable
internal fun SubAgentPresetDetailScreen(
    presetId: String,
    onBack: () -> Unit,
    repositoryFactory: () -> ConversationSubAgentPreferences = { ConversationSubAgentPreferences() },
    isCurrentRoute: Boolean = true,
) {
    val repository = remember(presetId) { repositoryFactory() }
    val directory = rememberSubAgentPresetDirectory(repository)
    // 先取 App 级会话编辑器，再向下提供预设作用域的编辑器：子组件读到的仍是预设副本，
    // 而「应用此组到本会话」要写的是会话作用域，两者互不越界。
    val conversationEditor = LocalConversationSubAgentEditor.current
    val preset = directory.entries.firstOrNull { it.id == presetId }
    if (preset == null) {
        PresetDetailUnavailable(directory, onBack)
        return
    }
    key(preset.id) {
        val owner = remember(preset.id) { SubAgentConfigKey.Preset(preset.id) }
        val editor = remember(owner) {
            ConversationSubAgentEditor(owner, repository) {
                // 路由只按 id 入栈；写回前仍以目录为准，组被删除后编辑自然失效。
                try {
                    repository.presetExists(owner.value)
                } catch (_: Exception) {
                    false
                }
            }
        }
        DisposableEffect(editor) { onDispose { editor.dispose() } }
        CompositionLocalProvider(LocalConversationSubAgentEditor provides editor) {
            SubAgentPresetDetail(editor, preset.id, preset.name, onBack, isCurrentRoute, conversationEditor)
        }
    }
}

/** 目录尚在读取、读取失败或组已被删除时的占位页；保持可返回与显式重试。 */
@Composable
private fun PresetDetailUnavailable(directory: SubAgentPresetDirectory, onBack: () -> Unit) {
    val error = directory.error
    EtaScaffoldPage(
        title = stringResource(R.string.sas_groups_title),
        onBack = onBack,
    ) {
        item(key = "status") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding),
            ) {
                EtaNoticeText(
                    title = when {
                        error != null -> stringResource(R.string.sas_read_error, error)
                        directory.loading -> stringResource(R.string.sas_loading)
                        else -> stringResource(R.string.sas_group_missing)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    TextButton(onClick = { directory.retry() }) {
                        Text(stringResource(R.string.sas_retry_restore))
                    }
                }
            }
        }
    }
}

/**
 * 预设组详情的正文。
 *
 * 每个子代理是一组连续的分段条目：标题行（开关/更多操作）与模型、职责、任务分工、
 * 推理强度、并行上限同处一个 [io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn]，
 * 只有组首/组末接管 16dp 外圆角，中间行则是 4dp 内圆角 + 2dp 缝隙，和配置弹窗、
 * MCP / 技能等兄弟页面保持同一套分段列表内核。
 */
@Composable
private fun SubAgentPresetDetail(
    editor: ConversationSubAgentEditor,
    presetId: String,
    groupName: String,
    onBack: () -> Unit,
    isCurrentRoute: Boolean,
    conversationEditor: ConversationSubAgentEditor?,
) {
    val state = editor.observe()
    val lastLoadedConfig = remember(editor) { arrayOfNulls<ConversationSubAgentConfig>(1) }
    val loadedConfig = (state as? SubAgentEditorState.Loaded)?.config
    if (loadedConfig != null) lastLoadedConfig[0] = loadedConfig
    val config = loadedConfig ?: lastLoadedConfig[0]
    val profiles = config?.profiles.orEmpty()
    val editable = editor.enabled
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    var addedDraft by remember(editor) { mutableStateOf<SubAgentProfileDraftSession?>(null) }
    var rename by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember(editor) { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember(editor) { mutableStateOf("") }
    val view = LocalView.current
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)
    LaunchedEffect(editor, editable) { if (!editable) { rename = null; delete = null } }
    CompositionLocalProvider(LocalRippleConfiguration provides etaRippleConfiguration()) {
        WithoutPressRipple {
            EtaScaffoldPage(
                title = groupName,
                onBack = onBack,
                listState = listState,
                floatingActionButton = if (editable) {
                    {
                        EtaExtendedFab(
                            text = stringResource(R.string.sap_add_profile),
                            icon = Icons.Rounded.Add,
                            expanded = fabExpanded,
                            onClick = {
                                if (editor.enabled) SubAgentProfileDraftSession.open(editor)?.let {
                                    TouchHaptics.click(view)
                                    addedDraft = it
                                }
                            },
                        )
                    }
                } else {
                    null
                },
            ) {
                item(key = "hint") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = EtaGroupHorizontalPadding,
                                end = EtaGroupHorizontalPadding,
                                bottom = EtaSegmentedTitleSpacing,
                            ),
                    ) {
                        Text(
                            text = when (state) {
                                is SubAgentEditorState.Error -> stringResource(R.string.sas_read_error, state.reason)
                                SubAgentEditorState.Loading -> stringResource(R.string.sas_loading)
                                else -> if (!editable) stringResource(R.string.sas_readonly)
                                else stringResource(R.string.sas_config_note)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state is SubAgentEditorState.Error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (state is SubAgentEditorState.Error) TextButton(onClick = { editor.retry() }) { Text(stringResource(R.string.sas_retry_restore)) }
                    }
                }
                item(key = "apply") {
                    SubAgentPresetApplySection(
                        presetId = presetId,
                        conversationEditor = conversationEditor,
                    )
                }
                if (config != null) {
                    item(key = "profiles") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = EtaGroupHorizontalPadding,
                                    end = EtaGroupHorizontalPadding,
                                    bottom = EtaSegmentedGroupSpacing,
                                ),
                            verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
                        ) {
                            profiles.forEach { profile ->
                                key(profile.id) {
                                    SubAgentProfileRow(
                                        profile = profile,
                                        providers = providers,
                                        enabled = editable,
                                        settings = true,
                                        draftAllowed = true,
                                        header = {
                                            SubAgentProfileHeaderRow(
                                                profile = profile,
                                                editable = editable,
                                                onEnabledChange = { active ->
                                                    if (editor.enabled) {
                                                        TouchHaptics.click(view)
                                                        editor.updateProfile(profile.id) { it.copy(enabled = active) }
                                                    }
                                                },
                                                onConfigure = {
                                                    if (editor.enabled) SubAgentProfileDraftSession.open(editor, profile)?.let {
                                                        TouchHaptics.click(view)
                                                        addedDraft = it
                                                    }
                                                },
                                                onRename = { name = profile.name; rename = profile },
                                                onDelete = { delete = profile },
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            addedDraft?.let { session ->
                SubAgentProfileConfigDialog(session, editor, providers, enabled = isCurrentRoute, onDismiss = {
                    session.dismiss()
                    if (addedDraft === session) addedDraft = null
                })
            }
            rename?.let { profile ->
                if (editable) SubAgentPresetNameDialog(
                    title = stringResource(R.string.sas_rename_agent),
                    fieldHint = stringResource(R.string.sap_name),
                    name = name,
                    onNameChange = { if (editor.enabled) name = it },
                    saveEnabled = name.trim().isNotBlank(),
                    onDismiss = { rename = null },
                    onSave = {
                        if (editor.enabled && editor.updateProfile(profile.id) { it.copy(name = name.trim()) } is ConversationSubAgentPreferences.WriteResult.Saved)
                            rename = null
                    },
                )
            }
            delete?.let { profile ->
                if (editable) EtaDialog(
                    show = true,
                    title = stringResource(R.string.sas_delete_agent_title),
                    onDismissRequest = { delete = null },
                    summary = stringResource(R.string.sas_delete_agent_note, profile.name),
                    actions = {
                        // 全项目确认弹窗统一动作行：右对齐 TextButton，破坏性确认 error 色文案。
                        EtaDialogActions(
                            confirmText = stringResource(R.string.action_delete),
                            destructive = true,
                            onCancel = { delete = null },
                            onConfirm = {
                                if (editor.enabled && editor.remove(profile.id) is ConversationSubAgentPreferences.WriteResult.Saved)
                                    delete = null
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * 「应用此组到本会话」：把预设组复制进当前会话作用域——写会话，不写预设。
 *
 * 应用结果（已应用哪个组、自动委派开关、会话级代理行）由列表页的「本会话」区块呈现，
 * 两边经 App 级会话编辑器（owner=当前会话/草稿）读写同一份配置；重新应用会以预设
 * 覆盖会话级改动，与旧选组入口的行为一致。
 */
@Composable
private fun SubAgentPresetApplySection(
    presetId: String,
    conversationEditor: ConversationSubAgentEditor?,
) {
    if (conversationEditor == null) return
    val context = LocalContext.current
    val state = conversationEditor.observe()
    val applied = (state as? SubAgentEditorState.Loaded)?.config?.appliedPresetId == presetId
    var error by remember(conversationEditor, presetId) { mutableStateOf<String?>(null) }
    val editable = conversationEditor.enabled
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
    ) {
        if (applied) {
            Text(
                text = stringResource(R.string.sas_applied_to_conversation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!editable) {
            Text(
                text = stringResource(R.string.sas_conversation_readonly),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = {
                val result = conversationEditor.applyPreset(presetId)
                error = if (result is ConversationSubAgentPreferences.WriteResult.Saved) null
                else context.getString(R.string.sas_apply_failed)
            },
            enabled = editable,
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.sas_apply_to_conversation))
        }
    }
}

/**
 * 单个子代理的标题行：角色图标 + 名称 + 更多操作 + 启用开关。
 *
 * 形状与容器色取自外层分段组（KernelSU 式 `SegmentedListItem`）；开关和溢出菜单是行内
 * 独立可点目标。尾部顺序与技能页一致：先溢出菜单，再开关，避免开关被挤到行中间。
 */
@Composable
private fun SubAgentProfileHeaderRow(
    profile: SubAgentProfile,
    editable: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    var menuExpanded by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(editable) { if (!editable) menuExpanded = false }
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        enabled = editable,
        // 行尾带启用开关：按「开关类」保持官方内边距。
        choiceRow = true,
        leadingContent = {
            // 不显式 tint：让官方行内容色在禁用态把图标一并压暗。
            Icon(
                imageVector = when (profile.role) {
                    "review" -> Icons.AutoMirrored.Rounded.FactCheck
                    "image_generation" -> Icons.Rounded.Image
                    "video_generation" -> Icons.Rounded.Videocam
                    else -> Icons.Rounded.AccountTree
                },
                contentDescription = null,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    val moreDescription = stringResource(R.string.sas_more_for, profile.name)
                    EtaTooltip(text = moreDescription) {
                        IconButton(enabled = editable, onClick = {
                            if (editable) { TouchHaptics.click(view); menuExpanded = true }
                        }) { Icon(Icons.Rounded.MoreVert, moreDescription) }
                    }
                    if (editable) SubAgentDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        itemCount = 3,
                    ) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.sas_configure)) }, leadingIcon = { Icon(Icons.Rounded.Tune, null) },
                            shape = EtaMenuItemShape(index = 0, count = 3),
                            onClick = { menuExpanded = false; onConfigure() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            shape = EtaMenuItemShape(index = 1, count = 3),
                            onClick = { menuExpanded = false; onRename() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            shape = EtaMenuItemShape(index = 2, count = 3),
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
                // 开关尺寸交给官方 EtaSwitch（不再用 requiredSize 截断再溢出绘制）。
                // 名称挂在开关自身的语义节点上：ListItem 的命中测试不会把外层包裹盒的点击
                // 透传给内部开关，因此可访问名必须落在真正可切换的节点上。
                EtaSwitch(
                    checked = profile.enabled,
                    enabled = editable,
                    modifier = Modifier.semantics { contentDescription = context.getString(R.string.sas_enable_for, profile.name) },
                    onCheckedChange = { active -> if (editable) onEnabledChange(active) },
                )
            }
        },
    ) {
        Text(
            text = profile.name,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
