package io.github.mangi.eta.ui.screens.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Troubleshoot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.haptics.TouchHaptics
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
            androidx.compose.foundation.layout.Column {
                ToolsSegmentTabs(
                    tabs = listOf(
                        stringResource(R.string.capability_current_device),
                        stringResource(R.string.capability_all),
                    ),
                    selectedIndex = if (showAll) 1 else 0,
                    onSelect = { showAll = it == 1 },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                EtaText(
                    text = stringResource(R.string.tools_per_tool_switch_hint),
                    style = EtaTextStyle.Secondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
        groups.forEach { group ->
            item(key = "${group.id}-list") {
                ToolGroupCard(
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
 * 一个工具分组的可展开分段卡（Material 3 Lists 的 expand & collapse）：
 *
 * - 父条目是组标题行（官方 [EtaSegmentedRow]，即 `SegmentedListItem`）：整行点击
 *   展开/收起，行尾是随展开状态旋转的官方 `ExpandMore` 箭头（曲线取官方
 *   `motionScheme`），与 Provider 页的展开行同一套官方写法；带开关的组，
 *   开关就放在父行行尾——域级开关就地可及，不用展开也能整组收放。
 * - 子条目是组内工具行，经 [EtaSegmentedColumn] 的 `visible` 机制折叠：
 *   收起时以分段 spring 收缩高度、淡出，并把末段圆角让给父行——即 M3 规范的
 *   parent-child transition（folder-like，竖向展开露内容）。
 *
 * 空组不渲染，避免留下孤立组标题。折叠态按组记忆（条目键稳定），滚出屏幕、
 * 切页签后不丢。
 */
@Composable
private fun ToolGroupCard(
    group: ToolGroupUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
) {
    if (group.tools.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    EtaSegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
    ) {
        item(key = "header") {
            ToolGroupHeaderRow(
                group = group,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onAction = onAction,
            )
        }
        group.tools.forEach { tool ->
            item(key = tool.id, visible = expanded) {
                ToolListRow(
                    tool = tool,
                    // 分组关闭时工具开关进入禁用态：该组能力已整体失效，再逐个开关没有意义。
                    groupEnabled = group.switchKey == null || group.switchEnabled,
                    rootGranted = rootGranted,
                    capabilities = capabilities,
                    onAction = onAction,
                )
            }
        }
    }
}

/**
 * 分组父行：M3 expand & collapse 的父条目。
 *
 * 前导图标与设置首页同名分组入口保持一致（见 [toolGroupIcon]）；标题走官方
 * [EtaSegmentedRow]（`SegmentedListItem`）内容槽默认令牌，与 Google 官方示例
 * 一致，不做字阶/配色的特殊区分。整行点击切换展开/收起；行尾开关是域级控制——
 * 点击开关只切换分组启停，不触发展开（开关自己消费点击）。
 * 无障碍：行播报展开/收起状态（stateDescription），开关带独立的
 * contentDescription。
 */
@Composable
private fun ToolGroupHeaderRow(
    group: ToolGroupUi,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onAction: (AgentToolsAction) -> Unit,
) {
    val view = LocalView.current
    val switchLabel = stringResource(R.string.tools_group_switch_title)
    val stateText = stringResource(
        if (expanded) R.string.tools_group_state_expanded else R.string.tools_group_state_collapsed,
    )
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>(),
        label = "tool-group-expand-chevron",
    )
    EtaSegmentedRow(
        onClick = { onExpandedChange(!expanded) },
        modifier = Modifier.semantics { this.stateDescription = stateText },
        leadingContent = {
            Icon(imageVector = toolGroupIcon(group.id), contentDescription = null)
        },
        content = { M3Text(text = group.title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                group.switchKey?.let { switchKey ->
                    EtaSwitch(
                        checked = group.switchEnabled,
                        onCheckedChange = { enabled ->
                            TouchHaptics.toggle(view, enabled)
                            onAction(AgentToolsAction.ToggleGroup(switchKey, enabled))
                        },
                        modifier = Modifier.semantics { contentDescription = switchLabel },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(rotation),
                )
            }
        },
    )
}

/**
 * 分组父行的前导图标：设置首页同名分组入口用什么，这里就用什么
 * （见 `SettingsCatalog` 的 groupToolCapabilities 条目），两处不各画一套。
 * calendar 与 device_state 是设置首页还没有条目的新组，按组内工具的语义取
 * 官方 rounded 图标；未知组兜底用工具能力入口的 Dashboard。
 */
private fun toolGroupIcon(groupId: String): ImageVector = when (groupId) {
    "basic" -> Icons.Rounded.Forum
    "screen" -> Icons.Rounded.TouchApp
    "web" -> Icons.Rounded.Language
    "terminal" -> Icons.Rounded.Terminal
    "device" -> Icons.Rounded.Smartphone
    "calendar" -> Icons.Rounded.Event
    "personal" -> Icons.Rounded.Badge
    "device_state" -> Icons.Rounded.Troubleshoot
    "extensions" -> Icons.Rounded.Extension
    else -> Icons.Rounded.Dashboard
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
