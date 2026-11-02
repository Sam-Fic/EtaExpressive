package io.github.mangi.eta.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AssistantProfile
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.ProviderBalanceState
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.ModelPickerCheckRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.pages.providers.ProviderBalanceIndicator
import io.github.mangi.eta.ui.pages.providers.hasBalanceIndicatorContent
import io.github.mangi.eta.ui.screens.assistants.AssistantDeleteConfirmPanel
import io.github.mangi.eta.ui.screens.assistants.AssistantRow
import io.github.mangi.eta.ui.screens.assistants.AssistantSearchField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「模型与助手」二合一选择弹窗（官方 `ModalBottomSheet`，仅 Hidden↔Expanded 两态）。
 *
 * 结构：
 * 1. 标题行：标题 + 行尾配置图标按钮（随页签切换图标与目标：模型 / 助手）。
 *    （关闭走 ModalBottomSheet 的下拉 / 点遮罩，不放关闭按钮。）
 * 2. 官方单选分段按钮的页签：模型 / 助手。
 *    - 模型页签：官方搜索框 + 服务商分类标题 + 分组内的官方分段单选行（全部平铺，不折叠）。
 *    - 助手页签：官方搜索框 + KernelSU 式分段列表（行尾三点菜单提供复制 / 编辑 / 删除）。
 * 3. 底部固定两行「思考档位」「上下文用量」，复用输入区原有的两个控件（含各自弹窗 /
 *    长按语义），替代旧布局里的两个独立按钮。
 *
 * 生成中同样可以改选：新选择由 owner 登记为「下一轮生效」的待生效绑定（本轮仍用发起时冻结的
 * 配置），此时底部多出一行待生效说明，列表里当前行标注「本轮」、目标行标注「下一轮」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun M3ComposerPickerSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    assistantId: String,
    onEditAssistant: (String) -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenAssistants: () -> Unit,
    onAssistantSelected: (String) -> Unit,
    modelPickerState: AgentModelPickerUiState,
    isStreaming: Boolean,
    isPaused: Boolean,
    isCompressingContext: Boolean,
    onModelSelected: (String, String) -> Unit,
    usage: AgentContextUsageUi,
    contextSendBlocked: Boolean,
    popupMaxHeight: () -> Dp,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    gptSpeedMode: GptSpeedMode,
    gptSpeedSupported: Boolean,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCycleGptSpeedMode: () -> Unit,
) {
    if (!show) return
    var tabIndex by remember { mutableIntStateOf(0) }
    val remoteAgentName = io.github.mangi.eta.ui.remote.LocalRemoteAgentName.current
    val assistantProfiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.composer_picker_title),
                    style = etaTextStyle(EtaType.Title),
                    modifier = Modifier.weight(1f),
                )
                // 配置入口：随页签切换图标与目标（模型 → 模型配置，助手 → 助手管理）。
                // 远程会话下助手页签没有可配置的东西（助手在服务器上，本机也改不了），
                // 留着这个入口只会把人带进一个和当前会话无关的页面。
                if (!(remoteAgentName != null && tabIndex == 1)) {
                    val configureDescription = stringResource(
                        if (tabIndex == 0) {
                            R.string.composer_configure_models
                        } else {
                            R.string.composer_configure_assistants
                        },
                    )
                    EtaTooltip(text = configureDescription) {
                        IconButton(
                            onClick = {
                                onDismiss()
                                if (tabIndex == 0) onOpenModelProviders() else onOpenAssistants()
                            },
                        ) {
                            Icon(
                                imageVector = if (tabIndex == 0) {
                                    Icons.Rounded.Memory
                                } else {
                                    Icons.Rounded.SmartToy
                                },
                                contentDescription = configureDescription,
                            )
                        }
                    }
                }
            }
            val tabs = listOf(
                stringResource(R.string.conversation_dock_models),
                stringResource(R.string.assistant_list_title),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, title ->
                    SegmentedButton(
                        selected = index == tabIndex,
                        onClick = { tabIndex = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                    ) {
                        Text(text = title)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (tabIndex == 0) {
                    M3ModelPickerTab(
                        state = modelPickerState,
                        onModelSelected = onModelSelected,
                    )
                } else if (remoteAgentName != null) {
                    // 远程会话：助手只有服务器上那一个，本机没有可换的目标。
                    RemoteAssistantTab(name = remoteAgentName)
                } else {
                    M3AssistantPickerTab(
                        assistantId = assistantId,
                        onOpen = { id ->
                            onDismiss()
                            onAssistantSelected(id)
                        },
                        onEdit = { id ->
                            onDismiss()
                            onEditAssistant(id)
                        },
                    )
                }
            }
            HorizontalDivider()
            // 思考档位：沿用输入区原来的芯片（长按切 GPT 速度档位的语义保持不变）。
            if (availableReasoningEfforts.isNotEmpty() || gptSpeedSupported) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.composer_thinking_effort_label),
                        style = etaTextStyle(EtaType.Body),
                        modifier = Modifier.weight(1f),
                    )
                    ThinkingEffortChip(
                        effort = reasoningEffort,
                        options = availableReasoningEfforts,
                        // 模型切换（isChanging）期间 owner 会 no-op，禁用以免出现“假动画”。
                        // 压缩中同样禁用：档位不进 pending，此时改档位会与压缩写回的会话状态相互覆盖。
                        enabled = (!isStreaming || isPaused) && !isCompressingContext &&
                            !modelPickerState.isChanging,
                        gptSpeedMode = gptSpeedMode,
                        gptSpeedSupported = gptSpeedSupported,
                        onCycleGptSpeedMode = onCycleGptSpeedMode,
                        onEffortChange = onReasoningEffortChange,
                    )
                }
            }
            // 待生效提示：生成中改选只登记下一轮的目标，本轮仍按发起时冻结的配置跑完。
            val boundModel = modelPickerState.selectedModel
            val pendingModel = modelPickerState.pendingModel
            if (boundModel != null && pendingModel != null &&
                (pendingModel.id != boundModel.id || pendingModel.providerId != boundModel.providerId)) {
                PendingSelectionHint(currentName = boundModel.displayName, nextName = pendingModel.displayName)
            }
            val currentAssistantId = assistantId.ifBlank { repositoryActiveId }
            val pendingAssistantId = modelPickerState.pendingAssistantId
            if (pendingAssistantId != null && pendingAssistantId != currentAssistantId) {
                PendingSelectionHint(
                    currentName = assistantNameOf(assistantProfiles, currentAssistantId),
                    nextName = assistantNameOf(assistantProfiles, pendingAssistantId),
                )
            }
            // 上下文用量：直接写在页面上，不再用「点击弹窗」承载详情。
            ContextUsageRow(
                usage = usage,
                sendBlocked = contextSendBlocked,
                popupMaxHeight = popupMaxHeight,
            )
        }
    }
}

/**
 * 上下文统计行：用量直接写在页面上。
 *
 * 长按这串数字可切换统计目标（主代理 / 子任务上下文），与旧进度环的长按语义一致。
 */
@Composable
private fun ContextUsageRow(
    usage: AgentContextUsageUi,
    sendBlocked: Boolean,
    popupMaxHeight: () -> Dp,
) {
    val telemetry = LocalAgentContextTelemetry.current
    val display = contextUsageDisplay(usage = usage, sendBlocked = sendBlocked)
    val targetLabel = stringResource(R.string.acm_select_context_target)
    val blockedText = stringResource(R.string.context_window_send_blocked)
    var selectorOpen by remember { mutableStateOf(false) }
    val text = buildString {
        // 主代理的用量就是当前模型的用量，不再重复写模型名；子任务才带目标标识。
        display.targetLabel?.takeIf { it.isNotBlank() }?.let {
            append(it)
            append(" · ")
        }
        append(display.summary)
        if (display.blocked) {
            append(" · ")
            append(blockedText)
        }
    }
    val longPressModifier = if (telemetry.children.isEmpty()) {
        Modifier
    } else {
        Modifier
            .pointerInput(Unit) { detectTapGestures(onLongPress = { selectorOpen = true }) }
            .semantics {
                onLongClick(targetLabel) {
                    selectorOpen = true
                    true
                }
            }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.acm_context_stats),
            style = etaTextStyle(EtaType.Body),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                text = text,
                style = etaTextStyle(EtaType.Secondary),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = longPressModifier,
            )
            ContextTargetSelectorMenu(
                telemetry = telemetry,
                expanded = selectorOpen,
                onDismiss = { selectorOpen = false },
                popupMaxHeight = popupMaxHeight,
            )
        }
    }
}

/** 页签内的配置入口已上移到标题行尾的图标按钮，本组件不再单独提供。 */

/**
 * 助手页签：官方搜索框 + KernelSU 式分段列表；行尾三点菜单提供复制 / 编辑 / 删除，
 * 与独立助手选择弹窗的 Material 3 分支保持同一套交互。
 */
/**
 * 远程会话下的助手页签：只有一项，就是用户给这台远程起的名字，且不可点。
 *
 * 远程的助手在服务器那一侧，本机既拿不到列表也没有切换通道——照本机的列表摆一屏
 * 能点的行，点下去什么都不会变（请求仍由服务器的助手处理），比不给列表更糟。
 * 所以这里只如实显示「这个会话用的是谁」，并说明为什么换不了。
 */
@Composable
private fun RemoteAssistantTab(name: String) {
    EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
        item(key = "remote-agent") {
            EtaSegmentedRow(
                selected = true,
                choiceRow = true,
                // 不可切换：没有 onClick，行不响应点击也不出涟漪。
                onClick = null,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Rounded.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                },
                content = {
                    Text(
                        // 名字为空（服务器记录被删过）时用通用称呼，不要留一行空白。
                        text = name.ifBlank { stringResource(R.string.route_remote_agents) },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
    Text(
        text = stringResource(R.string.assistant_remote_locked),
        style = etaTextStyle(EtaType.Caption),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun M3AssistantPickerTab(
    assistantId: String,
    onOpen: (String) -> Unit,
    onEdit: (String) -> Unit,
) {
    val profiles by AssistantRepository.profiles.collectAsState()
    val repositoryActiveId by AssistantRepository.activeId.collectAsState()
    val activeId = assistantId.ifBlank { repositoryActiveId }
    var searchQuery by remember { mutableStateOf("") }
    var deleteProfile by remember { mutableStateOf<AssistantProfile?>(null) }
    val scope = rememberCoroutineScope()
    val filtered = remember(profiles, searchQuery) {
        val query = searchQuery.trim()
        profiles.filter { profile ->
            query.isBlank() || profile.name.contains(query, ignoreCase = true)
        }
    }
    val deleteTarget = deleteProfile
    if (deleteTarget != null) {
        // 页签内原位确认，不覆盖在面板之上再开弹窗。
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
        return
    }
    AssistantSearchField(
        query = searchQuery,
        onQueryChange = { searchQuery = it },
        placeholder = stringResource(R.string.assistant_search),
    )
    Spacer(modifier = Modifier.height(12.dp))
    EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
        filtered.forEach { profile ->
            item(key = profile.id) {
                AssistantRow(
                    profile = profile,
                    selected = profile.id == activeId,
                    onOpen = { onOpen(profile.id) },
                    onCopy = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                AssistantRepository.duplicate(profile.id)
                            }
                        }
                    },
                    onEdit = { onEdit(profile.id) },
                    onDelete = { deleteProfile = profile },
                )
            }
        }
    }
    if (filtered.isEmpty()) {
        Text(
            text = stringResource(R.string.assistant_empty),
            style = etaTextStyle(EtaType.Secondary),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

/**
 * 模型页签：官方搜索框 + 按服务商分类平铺的分段单选列表（与辅助视觉模型等弹窗同款）。
 *
 * 不再按服务商折叠：服务商只作为分类标题，所有模型直接列在下方。
 * 生成中同样可点：owner 只会登记「下一轮生效」的待生效绑定，本轮仍用发起时冻结的配置；
 * 压缩中的拒绝也归 owner（弹提示），这里不再自建门禁。
 */
@Composable
private fun M3ModelPickerTab(
    state: AgentModelPickerUiState,
    onModelSelected: (String, String) -> Unit,
) {
    var modelQuery by remember { mutableStateOf("") }
    val pickerAvailable = state.providerGroups.isNotEmpty()
    val balanceStates = rememberProviderBalanceStates()
    val needle = modelQuery.trim()
    val groups = remember(state.providerGroups, needle) {
        filterModelProviderGroups(state.providerGroups, needle)
    }
    val selected = state.selectedModel
    val pending = state.pendingModel
    val hasPending = pending != null && (selected == null ||
        pending.id != selected.id || pending.providerId != selected.providerId)
    val pendingTarget = pending?.takeIf { hasPending }
    val currentTurnBadge = if (hasPending) stringResource(R.string.composer_current_turn_badge) else null
    val nextTurnBadge = if (hasPending) stringResource(R.string.composer_next_turn_badge) else null
    ModelSearchField(query = modelQuery, onQueryChange = { modelQuery = it })
    if (groups.isEmpty()) {
        Text(
            text = stringResource(R.string.model_search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = EtaSegmentedGroupSpacing),
        verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
    ) {
        groups.forEach { group ->
            Column(modifier = Modifier.fillMaxWidth()) {
                ModelProviderGroupHeader(
                    name = group.providerName,
                    balance = balanceStates[group.providerId],
                )
                EtaSegmentedColumn(modifier = Modifier.fillMaxWidth()) {
                    group.models.forEach { model ->
                        item(key = "${model.providerId}/${model.id}") {
                            val isCurrent = selected != null &&
                                model.id == selected.id &&
                                model.providerId == selected.providerId
                            ModelPickerCheckRow(
                                title = model.displayName,
                                selected = isCurrent,
                                badge = when {
                                    pendingTarget != null && model.id == pendingTarget.id &&
                                        model.providerId == pendingTarget.providerId -> nextTurnBadge
                                    isCurrent && pendingTarget != null -> currentTurnBadge
                                    else -> null
                                },
                                onClick = {
                                    if (!state.isChanging && pickerAvailable) {
                                        onModelSelected(model.providerId, model.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 服务商分类标题：与 [EtaSegmentedColumn] 的组标题同款字阶与配色
 * （官方 `titleSmallEmphasized` + primary），右侧保留余额指示器。
 */
@Composable
private fun ModelProviderGroupHeader(name: String, balance: ProviderBalanceState?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = EtaSegmentedTitleSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasBalanceIndicatorContent(balance)) {
            ProviderBalanceIndicator(state = balance)
        }
    }
}

/** 待生效说明行：次级字阶 + 弱化配色，与「上下文用量」同一套；固定在弹窗底部，不随列表滚动。 */
@Composable
private fun PendingSelectionHint(currentName: String, nextName: String) {
    Text(
        text = stringResource(R.string.composer_pending_selection_hint, currentName, nextName),
        style = etaTextStyle(EtaType.Secondary),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun assistantNameOf(profiles: List<AssistantProfile>, id: String): String =
    profiles.firstOrNull { it.id == id }?.name.orEmpty()
