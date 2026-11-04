package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaExpandChevron
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
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
                    modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = 8.dp),
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
        item(key = "tool-loading-settings") {
            ToolLoadingSettings()
        }
    }
}

/**
 * 渐进加载设置（对齐 ZCode「工具搜索」设置项，三档）：
 * - 按工具：延迟组 + tools_search 命中即解锁所属组；
 * - 按组：只能用 tools_use_group 整组打开（最初行为）；
 * - 关闭：发送完整工具目录。
 * 新建会话生效，当前会话保持原设置（Prefs.resolveToolLoadingMode 的会话锁定）。
 * 另有 MCP 暴露开关：按 server 粒度延迟加载，或全量常驻（旧行为）。
 *
 * 直接读写 Prefs：与 DecisionModelSettingsScreen 的既有模式一致，
 * 生效时机由 run 侧读取决定，这里只改偏好。
 */
@Composable
private fun ToolLoadingSettings() {
    val view = LocalView.current
    var mode by rememberSaveable { mutableStateOf(Prefs.toolLoadingMode()) }
    var mcpDeferred by rememberSaveable { mutableStateOf(Prefs.mcpToolExposureDeferred()) }
    val modeOptions = listOf(
        Prefs.Keys.TOOL_LOADING_MODE_SEARCH,
        Prefs.Keys.TOOL_LOADING_MODE_GROUP,
        Prefs.Keys.TOOL_LOADING_MODE_OFF,
    )
    EtaSegmentedColumn(
        modifier = Modifier.padding(
            start = EtaGroupHorizontalPadding,
            end = EtaGroupHorizontalPadding,
            bottom = EtaSegmentedGroupSpacing,
        ),
    ) {
        item(key = "loading-mode") {
            Column(modifier = Modifier.fillMaxWidth()) {
                ToolsSegmentTabs(
                    tabs = listOf(
                        stringResource(R.string.tools_loading_mode_search),
                        stringResource(R.string.tools_loading_mode_group),
                        stringResource(R.string.tools_loading_mode_off),
                    ),
                    selectedIndex = modeOptions.indexOf(mode).coerceAtLeast(0),
                    onSelect = { index ->
                        val next = modeOptions[index]
                        if (next != mode) {
                            TouchHaptics.toggle(view, next != Prefs.Keys.TOOL_LOADING_MODE_OFF)
                            mode = next
                            Prefs.setToolLoadingMode(next)
                        }
                    },
                    modifier = Modifier.padding(16.dp),
                )
                M3Text(
                    text = stringResource(R.string.tools_loading_mode_title),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                M3Text(
                    text = stringResource(R.string.tools_loading_mode_summary),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }
        item(key = "mcp-exposure") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    M3Text(text = stringResource(R.string.tools_mcp_deferred_title))
                    M3Text(text = stringResource(R.string.tools_mcp_deferred_summary))
                }
                Spacer(modifier = Modifier.width(EtaDimens.TrailingElementGap))
                EtaSwitch(
                    checked = mcpDeferred,
                    onCheckedChange = { enabled ->
                        TouchHaptics.toggle(view, enabled)
                        mcpDeferred = enabled
                        Prefs.setMcpToolExposureDeferred(enabled)
                    },
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
        modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
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
            )
        },
        content = { M3Text(text = capability.title) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 尾槽顺序统一：开关永远最右，箭头在其左侧。
                // 组头开关与下方各工具行的开关同列，避免同卡片内出现 36dp 错位。
                EtaExpandChevron(expanded = expanded)
                Spacer(modifier = Modifier.width(EtaDimens.TrailingElementGap))
                EtaSwitch(
                    checked = capability.switchEnabled,
                    onCheckedChange = { enabled ->
                        TouchHaptics.toggle(view, enabled)
                        onAction(AgentToolsAction.ToggleGroup(capability.switchKey, enabled))
                    },
                    modifier = Modifier.semantics { contentDescription = switchLabel },
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
