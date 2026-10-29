package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.etaOnSurfaceVariant

/**
 * 思考 / 工具调用等「工作过程」行统一的可展开气泡。
 *
 * 结构是 Material 3 Expressive 官方 `SegmentedListItemWithExpansionSample` 的标准写法：
 *
 * - 父项用官方 [SegmentedListItem]（`onClick` 切换展开/收起），形状取官方
 *   [ListItemDefaults.segmentedShapes] 并按官方 Sample 的方式动态计算
 *   `itemCount = 1 + 展开后出现的子条目数`；
 * - 子内容用官方 [AnimatedVisibility] + `expandVertically` / `shrinkVertically`
 *   配合 `MaterialTheme.motionScheme.fastSpatialSpec()` 显隐；
 * - 展开后的详情是**普通子条目**（不再嵌套任何可展开组件）；
 * - 无障碍按官方 Sample 注入 `stateDescription`。
 *
 * [onToggle] 或 [details] 为 null 时退化为不可展开的单条目（无展开箭头）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentWorkSegmentedBubble(
    leading: @Composable () -> Unit,
    title: String,
    titleColor: Color,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingColor: Color? = null,
    /** 展开箭头之外的状态标记（如工具的 LoadingIndicator / 对勾），由调用方提供。 */
    trailingStatus: (@Composable () -> Unit)? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    /**
     * 展开后的详情子条目内容。在官方 [AnimatedVisibility] 的内容作用域里调用，
     * 调用方可以用 `AnimatedVisibilityScope` 的扩展（如离屏纹理保留）。
     */
    details: (@Composable AnimatedVisibilityScope.() -> Unit)? = null,
    /** 挂在展开动画节点上的修饰符（诊断探针等），不影响标准结构。 */
    expandAnimationModifier: Modifier = Modifier,
) {
    val expandedStateDescription = stringResource(R.string.work_state_expanded)
    val collapsedStateDescription = stringResource(R.string.work_state_collapsed)
    // 官方 Sample：父项与子条目共用同一组 colors，容器色统一 surfaceContainer。
    val colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val expandable = onToggle != null && details != null
    val detailsContent = details
    // 官方 Sample 的 itemCount：父项 1 + 展开后出现的子条目数，供 segmentedShapes 使用。
    val itemCount = 1 + if (expandable && expanded && detailsContent != null) 1 else 0

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        val trailing: @Composable () -> Unit = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                trailingStatus?.invoke()
                if (expandable) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(EtaDimens.DenseIconSize),
                        tint = etaOnSurfaceVariant().copy(alpha = 0.7f),
                    )
                }
            }
        }
        val supportingSlot: (@Composable () -> Unit)? = supporting?.let { text ->
            {
                Text(
                    text = text,
                    style = chatTertiaryStyle(),
                    color = supportingColor ?: etaOnSurfaceVariant().copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val parentModifier = if (expandable) {
            Modifier
                .fillMaxWidth()
                .semantics {
                    stateDescription = if (expanded) expandedStateDescription else collapsedStateDescription
                }
        } else {
            Modifier.fillMaxWidth()
        }
        if (onToggle != null && expandable) {
            SegmentedListItem(
                onClick = onToggle,
                modifier = parentModifier,
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = itemCount),
                leadingContent = leading,
                trailingContent = trailing,
                supportingContent = supportingSlot,
                content = {
                    Text(
                        text = title,
                        style = chatSecondaryStyle(),
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        } else {
            SegmentedListItem(
                modifier = parentModifier,
                colors = colors,
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = itemCount),
                leadingContent = leading,
                trailingContent = trailing,
                supportingContent = supportingSlot,
                content = {
                    Text(
                        text = title,
                        style = chatSecondaryStyle(),
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }

        // 官方 Sample：子条目在 AnimatedVisibility 里带动画展开，间距取官方 SegmentedGap。
        AnimatedVisibility(
            visible = expandable && expanded && detailsContent != null,
            enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()),
            modifier = expandAnimationModifier,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = colors,
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = itemCount),
                    content = { Column { detailsContent() } },
                )
            }
        }
    }
}
