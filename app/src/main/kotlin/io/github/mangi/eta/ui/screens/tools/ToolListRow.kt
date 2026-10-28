package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.ToolPresentation
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import androidx.compose.material3.Text as M3Text

/**
 * 工具列表的分段行（Material 3）：KernelSU `SegmentedListItem` 式条目——
 * 前导工具图标、标题、完整摘要与能力要求说明，行尾是跳转动作（若有）与单工具开关。
 *
 * 摘要不截断，也没有「查看说明」弹窗：说明本来就在行里，弹窗只是把同一段文字再念一遍。
 * 因此只有真需要跳转的行才可点击并显示动作标签，其余行只留开关。
 *
 * 形状与容器色由外层 `EtaSegmentedColumn` 注入，行本身不画容器。
 */
@Composable
internal fun ToolListRow(
    tool: ToolItemUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val description = toolDescription(tool, rootGranted)
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = toolActionText(action)
    val accentColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 显式类型：let 返回嵌套 lambda 时推断器会落到 Nothing?。
    val onClick: (() -> Unit)? = action?.let { navigate -> { onAction(navigate) } }

    EtaSegmentedRow(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        leadingContent = {
            Icon(imageVector = ToolPresentation.icon(tool.id), contentDescription = null)
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                M3Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryColor,
                )
                requirementText?.let {
                    M3Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                    )
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                actionText?.let { label ->
                    M3Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = accentColor,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(EtaDimens.StatusIconSize),
                        tint = accentColor,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // 单工具开关：组开关之下的细粒度取舍。关掉后该工具不再下发给模型，
                // 但仍在清单里可见，用户随时能恢复。说明卡（browser_read 等）按实际工具归一。
                EtaSwitch(
                    checked = tool.enabled,
                    onCheckedChange = { enabled ->
                        TouchHaptics.click(view)
                        onAction(AgentToolsAction.ToggleTool(actualToolName(tool.id), enabled))
                    },
                )
            }
        },
        content = {
            M3Text(text = tool.title, style = MaterialTheme.typography.bodyLarge)
        },
    )
}

/** 行内展示的工具说明：未授权 ROOT 的终端会额外提示降级能力。 */
@Composable
private fun toolDescription(tool: ToolItemUi, rootGranted: Boolean): String =
    if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }

/** 行尾跳转动作的标签；没有跳转动作就没有标签（说明已在行内，无需入口）。 */
@Composable
private fun toolActionText(action: AgentToolsAction?): String? = when (action) {
    AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
    AgentToolsAction.OpenWebToolsSettings -> stringResource(R.string.web_tools_configure_action)
    AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
    AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
    else -> null
}

@Composable
private fun toolRequirementText(id: String, rootGranted: Boolean, capabilities: AgentToolCapabilities): String? {
    val requirement = toolCardRequirement(id)
    val unavailableCode = capabilities.unavailableCode(actualToolName(id))
    return when {
        !rootGranted && requirement.rootRequirement == RootRequirement.REQUIRED -> stringResource(R.string.capability_root_required)
        !capabilities.accessibilityAvailable && requirement.accessibility -> stringResource(R.string.capability_accessibility_required)
        unavailableCode == "NOTIFICATION_ACCESS_REQUIRED" -> stringResource(R.string.capability_notification_access_required)
        unavailableCode == "APP_USAGE_ACCESS_REQUIRED" -> stringResource(R.string.capability_usage_access_required)
        unavailableCode == "LOCATION_PERMISSION_REQUIRED" -> stringResource(R.string.capability_location_access_required)
        unavailableCode == "CALENDAR_PERMISSION_REQUIRED" -> stringResource(R.string.capability_calendar_access_required)
        requirement.colorOs -> stringResource(R.string.capability_coloros_required)
        !rootGranted && requirement.rootRequirement == RootRequirement.PARTIAL -> stringResource(R.string.capability_root_partial)
        else -> null
    }
}
