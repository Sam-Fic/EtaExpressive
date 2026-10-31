package io.github.mangi.eta.ui.screens.tools

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.ExpandMore
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
import io.github.mangi.eta.ui.model.ToolCapabilities
import io.github.mangi.eta.ui.model.ToolCapabilityUi
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.projectToolCapabilities
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
    val cards = projectToolCapabilities(
        state.capabilities,
        showAll,
        capabilities.root.isGranted,
        capabilities.tools.colorOs,
    )
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
        cards.forEach { capability ->
            item(key = "${capability.id}-card") {
                ToolCapabilityCard(
                    capability = capability,
                    rootGranted = capabilities.root.isGranted,
                    capabilities = capabilities.tools,
                    onAction = onAction,
                )
            }
        }
    }
}

/**
 * 一张卡 = 一个界面能力（[ToolCapabilityUi]）。这里的「卡」与模型侧的工具组不是一一对应：
 * 个人数据卡里就装着个人内容、设备状态、日程三个模型组，因为它们共用一个开关。
 *
 * - 父条目是卡片标题行（官方 [EtaSegmentedRow]）：整行点击展开/收起，行尾是能力开关，
 *   开关就地可及，不用展开也能整卡收放；标题下带一行摘要，说明这组装了什么。
 * - 子条目是工具行，经 [EtaSegmentedColumn] 的 `visible` 机制折叠，末段圆角让给父行
 *   （M3 的 parent-child transition）。设置页里的执行策略开关（如命令执行）在这里
 *   只表现为置灰与指路说明，不产生开关行。
 *
 * 空卡不渲染，避免留下孤立标题。
 */
@Composable
private fun ToolCapabilityCard(
    capability: ToolCapabilityUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
) {
    if (capability.tools.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(true) }
    EtaSegmentedColumn(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
    ) {
        item(key = "header") {
            ToolCapabilityHeaderRow(
                capability = capability,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                onAction = onAction,
            )
        }
        capability.tools.forEach { tool ->
            item(key = tool.id, visible = expanded) {
                ToolListRow(
                    tool = tool,
                    // 能力关闭、或被设置页的外部闸门（命令执行等）挡住时，该行进入禁用态。
                    groupEnabled = toolRowEnabled(capability, tool),
                    rootGranted = rootGranted,
                    capabilities = capabilities,
                    onAction = onAction,
                )
            }
        }
    }
}

/** 工具行是否可操作：卡片开关放行、且没有被设置页的外部闸门挡住。 */
private fun toolRowEnabled(capability: ToolCapabilityUi, tool: ToolItemUi): Boolean =
    capability.switchEnabled && tool.blockedReason == null

/**
 * 卡片父行：M3 expand & collapse 的父条目。
 *
 * 前导图标与设置页同名条目同源（[ToolCapabilities]）；标题下挂摘要——设置页不再
 * 露组开关、也不再写组摘要，这里是用户唯一能看到「这组装了什么」的地方，因此
 * 不截断（摘要换行占多少就给多少，与工具行的说明文字同一待遇）。
 *
 * 整行点击切换展开/收起；行尾开关只切换能力启停，不触发展开（开关自己消费点击）。
 * 无障碍：行播报展开/收起状态（stateDescription），开关带独立的 contentDescription。
 */
@Composable
private fun ToolCapabilityHeaderRow(
    capability: ToolCapabilityUi,
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
            Icon(imageVector = toolCapabilityIcon(capability.id), contentDescription = null)
        },
        supportingContent = {
            // 摘要完整显示，不省略：卡片头是「这组工具是什么」的唯一说明处。
            M3Text(
                text = capability.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        content = { M3Text(text = capability.title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EtaSwitch(
                    checked = capability.switchEnabled,
                    onCheckedChange = { enabled ->
                        TouchHaptics.toggle(view, enabled)
                        onAction(AgentToolsAction.ToggleGroup(capability.switchKey, enabled))
                    },
                    modifier = Modifier.semantics { contentDescription = switchLabel },
                )
                Spacer(modifier = Modifier.width(8.dp))
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
 * 卡片前导图标：设置页、搜索目录、工具页共用 [ToolCapabilities] 的那一份，
 * 不再各自画一套。未知 id 兜底用工具能力入口的 Dashboard。
 */
private fun toolCapabilityIcon(capabilityId: String): ImageVector =
    ToolCapabilities.all.firstOrNull { it.id == capabilityId }?.icon ?: Icons.Rounded.Dashboard

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
