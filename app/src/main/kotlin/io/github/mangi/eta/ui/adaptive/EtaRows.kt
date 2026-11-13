package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 开关行的内容内边距：直接用官方 [ListItemDefaults.ContentPadding]
 * （左右 16dp、上下取自官方 ItemTopSpace/ItemBottomSpace），不再手写 20/14。
 *
 * 开关行属于「勾选 / 开关」类，不参与 [EtaActionRowPadding] 的纵向补偿。
 */
private val DefaultRowPadding get() = ListItemDefaults.ContentPadding

/** 展开项内部的紧凑内边距：只保留水平对齐，垂直方向交给内部行自身。 */
private val DenseRowPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp)

/**
 * 带开关的设置行。
 *
 * @param leading 前导内容（图标等）；走分段行的 `leadingContent`。
 * @param dense 位于可展开项内部时使用更紧凑的内边距。
 * @param contentPadding 覆盖默认内边距。
 *
 * 轻触反馈由本行自己触发，调用方**不要**在 `onCheckedChange` 里再写
 * `TouchHaptics.click`，否则会叠成两次。
 */
@Composable
fun EtaSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    // 关闭态摘要：仅在该开关关闭且提供了此值时显示，用以取代 summary（开启态主描述）。
    // 这样开/关两态的 supporting text 互斥显示，文案不必把两种状态写进同一条。
    summaryOff: String? = null,
    enabled: Boolean = true,
    dense: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues? = null,
) {
    // 官方可交互 SegmentedListItem：自带涟漪、语义、长按与按压形变，
    // 形状与容器色取外层分段列注入的 segmentedShapes/segmentedColors（KernelSU 参考实现）。
    val view = LocalView.current
    SegmentedListItem(
        verticalAlignment = Alignment.CenterVertically,
        onClick = {
            TouchHaptics.toggle(view, !checked)
            onCheckedChange(!checked)
        },
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        shapes = etaSegmentedRowShapes(),
        colors = etaSegmentedRowColors(),
        leadingContent = leading,
        // 标题 / 摘要不再显式覆盖排版，直接用官方 SegmentedListItem 令牌
        // （ItemLabelTextFont = bodyLarge、ItemSupportingTextFont = bodyMedium + onSurfaceVariant）。
        content = { Text(text = title) },
        supportingContent = when {
            !checked && summaryOff != null -> ({ Text(text = summaryOff) })
            summary != null -> ({ Text(text = summary) })
            else -> null
        },
        trailingContent = {
            EtaSwitch(
                checked = checked,
                onCheckedChange = { value ->
                    TouchHaptics.toggle(view, value)
                    onCheckedChange(value)
                },
                enabled = enabled,
            )
        },
        contentPadding = contentPadding ?: if (dense) DenseRowPadding else DefaultRowPadding,
    )
}

/**
 * 可点击 / 可展开的设置行。
 *
 * 默认内边距走官方 [ListItemDefaults.ContentPadding]，与勾选 / 开关行一致；
 * 需要纵向补偿的动作行显式传 [EtaActionRowPadding]（仅对话流工作气泡）。
 *
 * @param showChevron 是否在尾部显示箭头（展开型条目通常自带尾部图标，可置 false）。
 * @param leading 前导内容（图标等）；走分段行的 `leadingContent`。
 * @param contentPadding 覆盖默认内边距（如展开项内部的紧凑行）。
 * @param highlight 命中词（如搜索关键词）；标题与摘要里的命中片段会被标色加粗，
 *        没有命中时按原文渲染。
 * @param destructive 破坏性动作（清理、清除、删除、移除、重置等）：标题取官方
 *        `error` 角色色强调，行首图标请同时传 `PreferenceIcon(destructive = true)`。
 *
 * 轻触反馈由本行自己触发，调用方**不要**在 `onClick` 里再写
 * `TouchHaptics.click`，否则会叠成两次。`endActions` / `bottomAction`
 * 里的 `IconButton`、`TextButton` 不是本行的一部分，仍需自行触发。
 */
@Composable
fun EtaClickableRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    onClick: (() -> Unit)? = null,
    // 官方 SegmentedListItem 原生带 onLongClick 槽：多选等「长按是唯一入口」的功能
    // 必须给出标签，否则 TalkBack 读不到、普通用户也不知道长按可用。
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    endActions: (@Composable RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues? = null,
    highlight: String? = null,
    destructive: Boolean = false,
) {
    val view = LocalView.current
    // 显式类型：let 返回嵌套 lambda 时推断器会落到 Nothing?。
    val hapticClick: (() -> Unit)? = onClick?.let { click -> { TouchHaptics.click(view); click() } }
    val titleText = highlightedText(title, highlight)
    val summaryText = summary?.let { highlightedText(it, highlight) }
    // 破坏性行的标题取官方 error 角色色；普通行交给官方 ListItem 的 contentColor。
    val titleColor = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified
    val trailingContent: (@Composable () -> Unit)? = if (endActions != null || showChevron) {
        {
            Row(verticalAlignment = Alignment.CenterVertically) {
                endActions?.invoke(this)
                if (showChevron) {
                    // 行尾多元素时统一留 12dp：尾槽内部间距不归官方 ListItem 管。
                    if (endActions != null) {
                        Spacer(modifier = Modifier.width(EtaDimens.TrailingElementGap))
                    }
                    EtaTrailingChevron()
                }
            }
        }
    } else {
        null
    }
    Column(modifier = modifier.fillMaxWidth()) {
        // onClick 为空但给了长按的行（多选列表里点行切勾选由 Checkbox 承载、行本体只留长按入口）
        // 同样走官方可点分支：只有「既无点击也无长按」才落到不可点分支。
        if (hapticClick != null || onLongClick != null) {
            SegmentedListItem(
                verticalAlignment = Alignment.CenterVertically,
                onClick = hapticClick ?: {},
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                shapes = etaSegmentedRowShapes(),
                colors = etaSegmentedRowColors(),
                leadingContent = leading,
                // 同 EtaSwitchRow：标题/摘要交给官方 ListItem 令牌，不再显式覆盖。
                content = { Text(text = titleText, color = titleColor) },
                supportingContent = summaryText?.let { { Text(text = it) } },
                trailingContent = trailingContent,
                contentPadding = contentPadding ?: ListItemDefaults.ContentPadding,
            )
        } else {
            SegmentedListItem(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                shapes = etaSegmentedRowShapes(),
                colors = etaSegmentedRowColors(),
                leadingContent = leading,
                // 同 EtaSwitchRow：标题/摘要交给官方 ListItem 令牌，不再显式覆盖。
                content = { Text(text = titleText, color = titleColor) },
                supportingContent = summaryText?.let { { Text(text = it) } },
                trailingContent = trailingContent,
                contentPadding = contentPadding ?: ListItemDefaults.ContentPadding,
            )
        }
        bottomAction?.invoke()
    }
}

/**
 * KernelSU `SegmentedDropdownItem` 对应物：行尾以 primary 色显示当前选中文案，
 * 菜单锚定在按下位置展开（`EtaOffsetAnchoredMenu`），选中项带 Check 前导图标。
 *
 * @param description 行的无障碍描述（TalkBack 播报与测试定位用）；挂在行本体的合并语义节点上，
 *        与 [SubAgentSettingsRow] 等兄弟行的 contentDescription 约定一致。
 * @param itemSummaries 菜单条目的第二行副标题（与 [items] 一一对应，null 表示该条不显示副标题）。
 *        走官方 [SelectableDropdownMenuItem.supportingText] 槽，不另造两行菜单。
 *
 * 轻触反馈（展开菜单、选中条目）由本行自己触发，调用方**不要**在 `onItemSelected`
 * 或 `onClick` 里再写 `TouchHaptics.click`。
 */
@Composable
fun EtaSegmentedDropdownRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onItemSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    description: String? = null,
    itemSummaries: List<String?>? = null,
) {
    val view = LocalView.current
    var expanded by remember { mutableStateOf(false) }
    var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }
    // 禁用即收起：与旧 SubAgentSettingsRow 的 tierPicker/rolePicker 一致，也防止
    // 保留的菜单项回调在禁用后仍能被触发。
    LaunchedEffect(enabled) { if (!enabled) expanded = false }

    val hasItems = items.isNotEmpty()
    val safeIndex = if (hasItems) selectedIndex.coerceIn(0, items.lastIndex) else -1

    // 显式类型：if 分支返回 lambda/null 时推断器会落到 Nothing?。
    // 展开的轻触反馈由内层 [EtaSegmentedRow] 自己触发，这里不要重复。
    val rowClick: (() -> Unit)? = if (enabled) {
        {
            onClick?.invoke()
            expanded = true
        }
    } else {
        null
    }

    Box(modifier = modifier.trackPressPosition { anchorOffset = it.round() }) {
        EtaSegmentedRow(
            modifier = if (description != null) {
                Modifier.semantics { contentDescription = description }
            } else {
                Modifier
            },
            // 单选型行：菜单里带勾选项，属于「勾选类」，不参与动作行的纵向补偿。
            choiceRow = true,
            onClick = rowClick,
            enabled = enabled,
            // 官方默认几何：裸 Icon（24dp + ItemBetweenSpace 12dp）；着色统一设置页 onSurfaceVariant。
            leadingContent = icon?.let {
                {
                    Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            supportingContent = summary?.let { { Text(it) } },
            trailingContent = {
                Text(
                    text = if (hasItems && safeIndex >= 0) items[safeIndex] else "",
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(0.3f),
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            content = { Text(text = title) },
        )
        EtaOffsetAnchoredMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchorOffset = anchorOffset,
            // 条目带 Check 的单选弹层：保持官方紧凑纵向边距。
            selectableMenu = true,
        ) {
            items.forEachIndexed { index, text ->
                SelectableDropdownMenuItem(
                    text = { Text(text) },
                    // 官方支持文本槽：菜单项第二行短说明，未给副标题时保持单行。
                    supportingText = itemSummaries?.getOrNull(index)?.let { subtitle ->
                        { Text(text = subtitle) }
                    },
                    selected = index == safeIndex,
                    onClick = {
                        if (index in items.indices) {
                            TouchHaptics.click(view)
                            onItemSelected(index)
                        }
                        expanded = false
                    },
                    shapes = EtaMenuItemShapes(index = index, count = items.size),
                    selectedLeadingIcon = {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                        )
                    },
                )
            }
        }
    }
}
