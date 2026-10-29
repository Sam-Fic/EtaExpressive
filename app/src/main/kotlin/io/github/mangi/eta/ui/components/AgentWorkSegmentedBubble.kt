package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaActionRowPadding
import io.github.mangi.eta.ui.adaptive.etaSegmentedRowColors

/** 展开 / 收起的标准动效：无过冲的标准弹簧（expressive spatial 弹簧带轻微回弹）。 */
private val StandardExpandSpec = spring<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntSize.VisibilityThreshold,
)

/**
 * 本行在一组连续分段列表（设置页「同组项」样式）里的位置：决定上/下沿用外圆（组首/组末）
 * 还是内圆（与相邻条目相接）。单独一条为 [Whole]。
 */
internal enum class SegmentedRowPosition { Whole, First, Middle, Last }

/**
 * 思考 / 工具调用等「工作过程」行统一的可展开气泡。
 *
 * 结构是 Material 3 Expressive 官方 `SegmentedListItemWithExpansionSample` 的标准写法：
 *
 * - 父项用官方 [SegmentedListItem]（`onClick` 切换展开/收起），形状取官方
 *   [ListItemDefaults.segmentedShapes] 并按官方 Sample 的方式动态计算
 *   `itemCount = 1 + 展开后出现的子条目数`；
 * - 子内容用官方 [AnimatedVisibility] + `expandVertically` / `shrinkVertically`
 *   配合无过冲的标准弹簧显隐；
 * - 展开后的详情是**普通子条目**（不再嵌套任何可展开组件）；
 * - 无障碍按官方 Sample 注入 `stateDescription`。
 *
 * 外观与「设置」页的分段列表同一套：容器色 `surfaceBright` 从页面背景浮出一档，
 * 单条目组整体 `MaterialTheme.shapes.large`，多条目（父项 + 展开子条目）按官方
 * `segmentedShapes(index, count)` 计算外圆内方，条目间 `SegmentedGap` 露出背景缝隙。
 * 内容按官方槽位安排——[leading] 放前导图标、[title] 是 Label text、
 * [supporting] 是 Supporting text、[trailingStatus] 与展开箭头放在行尾。
 * [onToggle] 或 [details] 为 null 时退化为不可展开的单条目（无展开箭头）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentWorkSegmentedBubble(
    leading: @Composable () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    /**
     * 特殊状态的 Label 着色（如正在运行）；null 走官方默认 contentColor。
     */
    titleColor: Color? = null,
    /**
     * 特殊状态的 Supporting 着色（如失败原因的 error 色）；null 走官方默认
     * supportingContentColor。
     */
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
    /** 本行在连续分段列表里的位置（连续工具/思考调用为一组，同设置页同组项）。 */
    position: SegmentedRowPosition = SegmentedRowPosition.Whole,
) {
    val expandedStateDescription = stringResource(R.string.work_state_expanded)
    val collapsedStateDescription = stringResource(R.string.work_state_collapsed)
    // 与设置页同款配色：surfaceBright 容器从 surfaceContainer 背景浮出一档，
    // 禁用态同样交出底色，保证分段条目在任何状态下表面连续。
    val colors = etaSegmentedRowColors()
    // 可展开只要求 onToggle；details 为 null 时（如「已完成 N 个步骤」折叠摘要，
    // 步骤是列表里的兄弟行）只有箭头与语义，没有详情子条目。
    val expandable = onToggle != null
    val detailsContent = details
    // 官方 Sample 的 itemCount：父项 1 + 展开后出现的子条目数。
    val itemCount = 1 + if (expandable && expanded && detailsContent != null) 1 else 0
    // 分段几何与设置页 EtaSegmentedList 同值：组首/末项外圆 16dp、相接处内圆 4dp。
    val outerCorner = 16.dp
    val innerCorner = 4.dp
    val topCorner =
        if (position == SegmentedRowPosition.Whole || position == SegmentedRowPosition.First) {
            outerCorner
        } else {
            innerCorner
        }
    val bottomBase =
        if (position == SegmentedRowPosition.Whole || position == SegmentedRowPosition.Last) {
            outerCorner
        } else {
            innerCorner
        }
    // 圆角 morph：自身展开出详情子条目时下沿让位（内圆，子条目接着本组），收起时
    // 回到组位置的原值；随弹簧过渡，不整块换形状硬切。
    val detailsChildVisible = expandable && expanded && detailsContent != null
    val rowBottomRadius by animateDpAsState(
        targetValue = if (detailsChildVisible) innerCorner else bottomBase,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
            visibilityThreshold = Dp.VisibilityThreshold,
        ),
        label = "segmented_corner_morph",
    )
    val parentShapes = ListItemDefaults.segmentedShapes(index = 0, count = 1).copy(
        shape = RoundedCornerShape(
            topStart = topCorner,
            topEnd = topCorner,
            bottomStart = rowBottomRadius,
            bottomEnd = rowBottomRadius,
        ),
    )

    Column(
        // 与旧卡片切片、正文块同拍：左右各 16dp，不贴屏幕边。
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        val trailing: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailingStatus?.invoke()
                if (expandable) {
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                    )
                }
            }
        }
        val supportingSlot: (@Composable () -> Unit)? = supporting?.let { text ->
            { Text(text = text, color = supportingColor ?: Color.Unspecified) }
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
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = EtaActionRowPadding,
                colors = colors,
                shapes = parentShapes,
                leadingContent = leading,
                trailingContent = trailing,
                supportingContent = supportingSlot,
                content = { Text(text = title, color = titleColor ?: Color.Unspecified) },
            )
        } else {
            SegmentedListItem(
                modifier = parentModifier,
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = EtaActionRowPadding,
                colors = colors,
                shapes = parentShapes,
                leadingContent = leading,
                trailingContent = trailing,
                supportingContent = supportingSlot,
                content = { Text(text = title, color = titleColor ?: Color.Unspecified) },
            )
        }

        // 官方 Sample：子条目在 AnimatedVisibility 里带动画展开，间距取官方 SegmentedGap。
        // 动画规格用无过冲的标准弹簧（expressive 的 spatial 弹簧带轻微回弹，不适合展开面板）。
        AnimatedVisibility(
            visible = expandable && expanded && detailsContent != null,
            enter = expandVertically(StandardExpandSpec),
            exit = shrinkVertically(StandardExpandSpec),
            modifier = expandAnimationModifier,
        ) {
            // 显式捕获动画作用域：内层 Column 的 ColumnScope 会遮蔽隐式接收者。
            val animationScope = this
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SegmentedListItem(
                    modifier = Modifier.fillMaxWidth(),
                    colors = colors,
                    // 与父项同一套分段几何：上沿接父项（内圆），下沿用本行组位置的原值
                    // （组末外圆 / 组中内圆，接下一行）。
                    shapes = ListItemDefaults.segmentedShapes(index = 1, count = 1).copy(
                        shape = RoundedCornerShape(
                            topStart = innerCorner,
                            topEnd = innerCorner,
                            bottomStart = bottomBase,
                            bottomEnd = bottomBase,
                        ),
                    ),
                    content = { Column { detailsContent?.invoke(animationScope) } },
                )
            }
        }
    }
}
