package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShape
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaTooltip
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.components.ItemDescriptionDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.SkillItemUi

@Composable
internal fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit = {},
    onDelete: (() -> Unit)? = null,
    showSwitch: Boolean = true,
    showMenu: Boolean = true,
) {
    var showDescription by remember(skill.id) { mutableStateOf(false) }
    val description = skill.description.ifBlank { stringResource(R.string.skills_no_description) }
    // KernelSU 式分段行：形状/容器色取自外层分段组，独立使用时退化为单条目形状。
    EtaSegmentedRow(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        // 行尾带开关时属于「开关类」，保持官方内边距；没有开关（只有溢出菜单）时按动作行补留白。
        choiceRow = showSwitch,
        leadingContent = {
            Icon(
                imageVector = iconForSkill(skill.id),
                contentDescription = null,
                tint = if (enabled) {
                    // 行首类别图标统一 onSurfaceVariant（与设置页 PreferenceIcon 同一档）。
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        },
        content = {
            androidx.compose.material3.Text(
                text = skill.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        },
        supportingContent = {
            androidx.compose.material3.Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showMenu) {
                    SkillOverflowMenu(
                        contentDescription = stringResource(R.string.skills_more_named, skill.name),
                        enabled = enabled,
                        onViewDescription = { showDescription = true },
                        onDelete = onDelete,
                    )
                }
                if (showSwitch) {
                    EtaSwitch(
                        checked = skill.enabled,
                        onCheckedChange = onToggle,
                        enabled = enabled,
                    )
                }
            }
        },
    )
    if (showDescription) {
        ItemDescriptionDialog(
            title = skill.name,
            description = description,
            onDismiss = { showDescription = false },
        )
    }
}

/**
 * 技能行尾的官方溢出菜单：图标按钮 + 官方 M3E 弹出窗口，与「模型自建工具」配方行同款——
 * 圆角分组容器、内缩药丸条目、前导图标 + 文案，破坏性的「删除」图标与文案同取 error 角色色。
 */
@Composable
private fun SkillOverflowMenu(
    contentDescription: String,
    enabled: Boolean,
    onViewDescription: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    val view = LocalView.current
    val itemCount = if (onDelete != null) 2 else 1
    Box {
        EtaTooltip(text = contentDescription) {
            IconButton(
                onClick = {
                    TouchHaptics.click(view)
                    expanded = true
                },
                enabled = enabled,
            ) {
                Icon(imageVector = Icons.Rounded.MoreHoriz, contentDescription = contentDescription)
            }
        }
        EtaMenuPopup(expanded = expanded, onDismissRequest = { expanded = false }) {
            EtaMenuGroup(index = 0, count = 1) {
                // 与工具配方、助手行同一套菜单条目：前导图标 + 文案，破坏性项图标与文案同取 error 色。
                DropdownMenuItem(
                    text = {
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.ui_view_description),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                    shape = EtaMenuItemShape(index = 0, count = itemCount),
                    onClick = {
                        expanded = false
                        onViewDescription()
                    },
                )
                onDelete?.let { delete ->
                    DropdownMenuItem(
                        text = {
                            androidx.compose.material3.Text(
                                text = stringResource(R.string.ui_delete_3755f5),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        enabled = enabled,
                        shape = EtaMenuItemShape(index = 1, count = itemCount),
                        onClick = {
                            expanded = false
                            if (enabled) delete()
                        },
                    )
                }
            }
        }
    }
}

internal fun iconForSkill(skillId: String): ImageVector = when (skillId) {
    "self-improving-agent" -> Icons.Rounded.Refresh
    "skill-creator" -> Icons.Rounded.DesignServices
    else -> Icons.Rounded.Extension
}
