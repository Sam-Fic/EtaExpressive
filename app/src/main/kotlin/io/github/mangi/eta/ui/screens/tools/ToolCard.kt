package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.RootRequirement
import io.github.mangi.eta.agent.tool.ToolPresentation
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.ToolItemUi
import io.github.mangi.eta.ui.model.actualToolName
import io.github.mangi.eta.ui.model.toolCardAction
import io.github.mangi.eta.ui.model.toolCardRequirement
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButtonDefaults

/**
 * 工具列表的分段行（Material 3）：KernelSU `SegmentedListItem` 式条目——
 * 前导工具图标、标题、摘要与能力要求说明，行尾为动作标签与箭头。
 * 需要跳转动作时额外提供信息入口：点整行执行动作，点信息按钮查看完整说明。
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
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val view = LocalView.current
    val description = toolDescription(tool, rootGranted)
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = toolActionText(action)
    val accentColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val infoDescription = stringResource(R.string.ui_description_named, tool.title)

    EtaSegmentedRow(
        modifier = modifier.fillMaxWidth(),
        onClick = {
            if (action != null) onAction(action) else showDescription = true
        },
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
                if (action != null) {
                    M3IconButton(
                        onClick = {
                            TouchHaptics.click(view)
                            showDescription = true
                        },
                    ) {
                        Icon(imageVector = Icons.Rounded.Info, contentDescription = infoDescription)
                    }
                }
                M3Text(
                    text = actionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (action != null) accentColor else secondaryColor,
                )
                if (action != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(EtaDimens.StatusIconSize),
                        tint = accentColor,
                    )
                }
                // 单工具开关：组开关之下的细粒度取舍。关掉后该工具不再下发给模型，
                // 但仍在清单里可见，用户随时能恢复。说明卡（browser_read 等）按实际工具归一。
                Spacer(modifier = Modifier.width(8.dp))
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

    if (showDescription) {
        ItemDescriptionDialog(
            title = tool.title,
            description = listOfNotNull(description, requirementText).joinToString("\n\n"),
            onDismiss = { showDescription = false },
        )
    }
}

/**
 * 工具卡片（网格卡）：图标行 + 标题 + 摘要 + 能力要求 + 动作行。
 *
 * Material 3 不再使用卡片网格，改由 [ToolListRow] 以分段列表行呈现。
 */
@Composable
internal fun ToolCard(
    tool: ToolItemUi,
    rootGranted: Boolean,
    capabilities: AgentToolCapabilities,
    onAction: (AgentToolsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDescription by remember(tool.id) { mutableStateOf(false) }
    val description = toolDescription(tool, rootGranted)
    val requirementText = toolRequirementText(tool.id, rootGranted, capabilities)
    val action = toolCardAction(tool.id, capabilities)
    val actionText = toolActionText(action)
    val accentColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.onSurfaceVariant
    val titleText = tool.title
    val bodyText = description
    val requirement = requirementText
    val actionLabel = actionText
    val infoDescription = stringResource(R.string.ui_description_named, tool.title)
    val openInfo = { showDescription = true }
    val cardBody: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ToolPresentation.icon(tool.id),
                contentDescription = null,
                modifier = Modifier.size(IconButtonDefaults.smallIconSize),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (action != null) {
                M3IconButton(onClick = openInfo) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = infoDescription,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        M3Text(
            text = titleText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        M3Text(
            text = bodyText,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        requirement?.let {
            Spacer(modifier = Modifier.height(8.dp))
            M3Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryColor,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            M3Text(
                text = actionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (action != null) accentColor else secondaryColor,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(EtaDimens.StatusIconSize),
                    tint = accentColor,
                )
            }
        }
    }

    Card(
        onClick = {
            if (action != null) onAction(action) else showDescription = true
        },
        modifier = modifier.heightIn(min = 136.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            cardBody()
        }
    }
    if (showDescription) {
        ItemDescriptionDialog(
            title = tool.title,
            description = listOfNotNull(description, requirementText).joinToString("\n\n"),
            onDismiss = { showDescription = false },
        )
    }
}

/** 卡片/行内展示的工具说明：未授权 ROOT 的终端会额外提示降级能力。 */
@Composable
private fun toolDescription(tool: ToolItemUi, rootGranted: Boolean): String =
    if (!rootGranted && tool.id == "terminal") {
        stringResource(R.string.capability_terminal_ordinary_summary)
    } else {
        tool.summary
    }

/** 行尾/卡尾动作标签：无跳转动作时退化为「查看说明」。 */
@Composable
private fun toolActionText(action: AgentToolsAction?): String = when (action) {
    AgentToolsAction.OpenBrowser -> stringResource(R.string.action_open_browser)
    AgentToolsAction.OpenWebToolsSettings -> stringResource(R.string.web_tools_configure_action)
    AgentToolsAction.OpenPermissions -> stringResource(R.string.tools_manage_permissions)
    AgentToolsAction.OpenEnhancements -> stringResource(R.string.tools_view_enhancements)
    else -> stringResource(R.string.ui_view_description)
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
