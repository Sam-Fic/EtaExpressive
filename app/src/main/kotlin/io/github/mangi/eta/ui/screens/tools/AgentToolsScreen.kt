package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import io.github.mangi.eta.ui.model.ToolGroupUi
import io.github.mangi.eta.ui.model.projectToolGroups
import androidx.compose.material3.Text as M3Text

@Composable
fun AgentToolsScreen(
    state: AgentToolsUiState,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val capabilities = rememberDeviceCapabilities()
    var showAll by rememberSaveable { mutableStateOf(false) }
    val currentListState = rememberLazyListState()
    val allListState = rememberLazyListState()
    val groups = projectToolGroups(state.groups, showAll, capabilities.root.isGranted, capabilities.tools.colorOs)
    EtaScaffoldPage(
        title = stringResource(R.string.ui_tool_ability_9f0f80),
        onBack = { onAction(AgentToolsAction.NavigateBack) },
        modifier = modifier,
        listState = if (showAll) allListState else currentListState,
    ) {
        item(key = "capability-view") {
            ToolsSegmentTabs(
                tabs = listOf(
                    stringResource(R.string.capability_current_device),
                    stringResource(R.string.capability_all),
                ),
                selectedIndex = if (showAll) 1 else 0,
                onSelect = { showAll = it == 1 },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        groups.forEach { group ->
            item(key = "${group.id}-list") {
                ToolGroupSegmentedList(
                    group = group,
                    rootGranted = capabilities.root.isGranted,
                    capabilities = capabilities.tools,
                    onAction = onAction,
                )
            }
        }
    }
}

/**
 * 一组工具的分段列表（Material 3）：组标题 + 每个工具一条分段行，
 * 与 KernelSU 设置页的分段列表结构一致。空组不渲染，避免留下孤立组标题。
 */
@Composable
private fun ToolGroupSegmentedList(
    group: ToolGroupUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
) {
    if (group.tools.isEmpty()) return
    if (group.switchKey != null) {
        // 分组级内联开关：用户在这里就能收紧整个能力域，不必再去找全局设置。
        ToolGroupSwitchRow(
            group = group,
            onAction = onAction,
        )
    }
    EtaSegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = group.title,
    ) {
        group.tools.forEach { tool ->
            item(key = tool.id) {
                ToolListRow(
                    tool = tool,
                    rootGranted = rootGranted,
                    capabilities = capabilities,
                    onAction = onAction,
                )
            }
        }
    }
}

/**
 * 工具页顶部分段切换。
 *
 * Material 3 走官方单选分段按钮 [SingleChoiceSegmentedButtonRow] + [SegmentedButton]
 * （形状取官方 `SegmentedButtonDefaults.itemShape`，配色/行高/字阶全部使用官方默认值）。
 */
@Composable
private fun ToolsSegmentTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        tabs.forEachIndexed { index, title ->
            SegmentedButton(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
            ) {
                M3Text(text = title)
            }
        }
    }
}

/**
 * 分组标题旁的内联开关。
 *
 * 关闭后该组工具不再下发给模型；已打开的工具域也会随之失效。
 * 这里只做「用户」的开关——模型侧仍然只能收紧不能放宽（见 AgentSelfSettingsTools）。
 */
@Composable
private fun ToolGroupSwitchRow(
    group: ToolGroupUi,
    onAction: (AgentToolsAction) -> Unit,
) {
    val key = group.switchKey ?: return
    EtaSegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
        title = group.title,
    ) {
        item(key = "switch-${group.id}") {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                M3Text(
                    text = stringResource(R.string.tools_group_switch_title),
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Switch(
                    checked = group.switchEnabled,
                    onCheckedChange = { enabled -> onAction(AgentToolsAction.ToggleGroup(key, enabled)) },
                )
            }
        }
    }
}
