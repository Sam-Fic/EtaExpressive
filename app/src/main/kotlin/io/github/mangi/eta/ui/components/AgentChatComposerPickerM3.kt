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
import io.github.mangi.eta.ui.adaptive.EtaSegmentedTitleSpacing
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
                        contentDescription = stringResource(
                            if (tabIndex == 0) {
                                R.string.composer_configure_models
                            } else {
                                R.string.composer_configure_assistants
                            },
                        ),
                    )
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
                        isStreaming = isStreaming,
                        isPaused = isPaused,
                        onModelSelected = onModelSelected,
                    )
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
                        enabled = (!isStreaming || isPaused) && !modelPickerState.isChanging,
                        gptSpeedMode = gptSpeedMode,
                        gptSpeedSupported = gptSpeedSupported,
                        onCycleGptSpeedMode = onCycleGptSpeedMode,
                        onEffortChange = onReasoningEffortChange,
                    )
                }
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
 * 选中模型时的可用性判断与旧入口一致（切换中或列表为空时不派发）。
 */
@Composable
private fun M3ModelPickerTab(
    state: AgentModelPickerUiState,
    isStreaming: Boolean,
    isPaused: Boolean,
    onModelSelected: (String, String) -> Unit,
) {
    var modelQuery by remember { mutableStateOf("") }
    val pickerAvailable = (!isStreaming || isPaused) && state.providerGroups.isNotEmpty()
    val balanceStates = rememberProviderBalanceStates()
    val needle = modelQuery.trim()
    val groups = remember(state.providerGroups, needle) {
        filterModelProviderGroups(state.providerGroups, needle)
    }
    val selected = state.selectedModel
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
                            ModelPickerCheckRow(
                                title = model.displayName,
                                selected = selected != null &&
                                    model.id == selected.id &&
                                    model.providerId == selected.providerId,
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
