package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentTaskTier
import io.github.mangi.eta.ui.adaptive.EtaDownChevron
import io.github.mangi.eta.ui.adaptive.EtaMenuGroup
import io.github.mangi.eta.ui.adaptive.EtaMenuItemShapes
import io.github.mangi.eta.ui.adaptive.EtaMenuPopup
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** 统一风格的选择控件：官方 ListItem 承载容器、内外边距、排版、涟漪与禁用态。 */
@Composable
internal fun SubAgentChoiceField(
    label: String,
    value: String,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 官方 ListItem(onClick) 承担容器、内外边距（ListItemDefaults.ContentPadding）、
    // 主/辅助文本排版、涟漪与禁用态；形状/配色/海拔全部用官方默认值，不传 shape/colors。
    // 注意：可交互重载的正文槽位参数名是 `content`（不是 `headlineContent`，
    // 后者只存在于无 onClick 的纯展示重载）。
    // 不额外挂 semantics contentDescription：ListItem 内部已是 semantics(mergeDescendants = true)，
    // ContentDescription(父) 与 Text(子) 是不同 SemanticsPropertyKey，TalkBack 会两串都念。
    // description = "设置" + label，与子文本里 label 那一行重叠；去掉重叠段只剩一个「设置」，
    // 单独作为播报没有任何信息量，所以整条都不挂，档位以官方合并的正文为准。
    ListItem(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // 下拉菜单的箭头不随开合旋转，固定朝下。
        trailingContent = { EtaDownChevron() },
    )
}

/**
 * 子代理下拉菜单：官方 Expressive 弹层 + 官方分组。
 *
 * [itemCount] 供条目推导自身形状，调用方需与实际条目数一致。
 * [selectableMenu] 表示条目带单选勾选：保持官方紧凑纵向边距；动作菜单（重命名/删除）
 * 默认补到上下 4dp。
 */
@Composable
internal fun SubAgentDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    itemCount: Int,
    modifier: Modifier = Modifier,
    selectableMenu: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    EtaMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
    ) {
        EtaMenuGroup(index = 0, count = 1, selectableMenu = selectableMenu) {
            content()
        }
    }
}

/** 选中态是官方 SelectableDropdownMenuItem 的背景 + 无障碍单选语义，选中项带官方对勾前导图标。 */
@Composable
internal fun SubAgentSelectionItem(
    text: String,
    selected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    // 官方 SelectableDropdownMenuItem 承担选中背景、涟漪、Role.RadioButton 语义与按压形变；
    // 选中项的前导槽位放官方对勾图标（与「上下文压缩 → 请求接口」那个下拉同款）。
    SelectableDropdownMenuItem(
        selected = selected,
        onClick = { TouchHaptics.click(view); onClick() },
        shapes = EtaMenuItemShapes(index, count),
        selectedLeadingIcon = {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(MenuDefaults.LeadingIconSize),
            )
        },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
internal fun SubAgentTaskTierButton(
    label: String,
    tier: SubAgentTaskTier?,
    enabled: Boolean,
    onTierSelected: (SubAgentTaskTier) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 是否为「任务分工」行：决定辅助文案。用显式布尔而非比较 label 文本——label 是本地化字符串，跨语言不可比。 */
    isTaskTierRow: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val view = LocalView.current
    val latestEnabled by rememberUpdatedState(enabled)
    val latestSelection by rememberUpdatedState(onTierSelected)
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier) {
        if (compact) {
            // 官方 AssistChip 承担容器高度、形状、描边、涟漪、标签排版与禁用态；
            // 尾部 chevron 走官方 trailingIcon 槽位，图标尺寸用官方 AssistChipDefaults.IconSize。
            AssistChip(
                onClick = { if (latestEnabled) { TouchHaptics.click(view); expanded = !expanded } },
                modifier = Modifier
                    .widthIn(max = 108.dp)
                    // 触摸下限交给官方 Modifier.minimumInteractiveComponentSize()（读 LocalMinimumInteractiveComponentSize，默认 48.dp）。
                    .minimumInteractiveComponentSize()
                    .semantics { contentDescription = context.getString(R.string.stt_desc_set_for, label) },
                enabled = enabled,
                label = {
                    Text(
                        text = tier?.let { stringResource(it.labelRes) } ?: stringResource(R.string.stt_set_short),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                // 官方 AssistChip 的 trailingIcon 槽位固定 18dp，覆盖共享箭头的默认 24dp；
                // 下拉箭头保持常朝下，不随菜单开合旋转。
                trailingIcon = {
                    EtaDownChevron(
                        tint = LocalContentColor.current,
                        size = AssistChipDefaults.IconSize,
                    )
                },
            )
        } else SubAgentChoiceField(label, tier?.let { stringResource(it.labelRes) } ?: stringResource(R.string.stt_unset), enabled, if (isTaskTierRow) stringResource(R.string.stt_set_task_tier) else stringResource(R.string.stt_desc_set_for, label),
            onClick = { if (latestEnabled) { TouchHaptics.click(view); expanded = !expanded } })
        if (enabled) SubAgentDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            itemCount = SubAgentTaskTier.entries.size,
            modifier = Modifier.selectableGroup(),
            // 选中项带 Check：保持官方紧凑纵向边距。
            selectableMenu = true,
        ) {
            SubAgentTaskTier.entries.forEachIndexed { index, option ->
                SubAgentSelectionItem(
                    text = stringResource(option.labelRes),
                    selected = option == tier,
                    index = index,
                    count = SubAgentTaskTier.entries.size,
                ) {
                    if (latestEnabled) {
                        latestSelection(option)
                        expanded = false
                    }
                }
            }
        }
    }
}
