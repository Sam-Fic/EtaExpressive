package io.github.mangi.eta.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 消息操作条里的单个动作声明；[tooltip] 同时充当官方 Tooltip 气泡与溢出菜单项文字。
 * [checked] 为官方双状态：`true` 时按钮呈官方选中态（「胶囊 → 8dp 圆角」形变 +
 * 选中容器色），如朗读中 / 编辑中。
 */
internal class MessageActionItem(
    val tooltip: String,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
    val icon: @Composable () -> Unit,
)

/** 宽松窗口下单个动作按钮的最大宽度档：自然宽（约 44dp）再放一档，宽窗口不会拉成空长条。 */
private val MessageGroupButtonMaxWidth: Dp = 56.dp

/**
 * 消息操作条：官方 M3 Expressive 标准按钮组（`ButtonGroup` + `customItem`，官方
 * `ButtonGroupWithCustomItemSample` 同款骨架）。
 *
 * 官方「flexible button group」自适应宽度（M3 指南：compact 窗口用更窄的按钮让整组
 * 放得下，大窗口用更宽的按钮填充空间）：每个动作挂官方 `ButtonGroupScope.weight(1f)`
 * 均分宽度，组总宽封顶在正文宽的 2/3（compact 窗口相应压缩）与单钮 56dp 档的自然
 * 宽之间取小。`Modifier.animateWidth` 提供按压时的宽度联动（`expandedRatio` 走官方
 * 默认 0.15，压缩上限按官方语义取按钮自身内容内边距）；内容里的图标用
 * `requiredSize` 锁死官方 20dp 图标槽，压缩时不缩小。
 *
 * 每个动作一个官方 `ToggleButton`，尺寸 / 形状 / 内边距全部走官方默认：
 * `ToggleButtonSize.ExtraSmall`（32dp 高）、静止 `CornerFull` 胶囊、按下官方形变到
 * 方角、选中 `CornerMedium`（8dp）——即官方「圆 ↔ 方」三态形变。可声明
 * `MessageActionItem.checked` 进官方双状态（朗读中 / 编辑中）：官方选中形变
 * 「胶囊 → 8dp 圆角」+ 选中容器色 primary。配色未选中沿用套件统一档
 * secondaryContainer（与页面其他 FilledTonal 系组件同色阶）。
 *
 * 空间不足时由官方溢出机制接管：`ButtonGroupDefaults.OverflowIndicator` +
 * `DropdownMenu`（每项的 `menuContent` 提供同名菜单项，点击后手动收起菜单）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionButtonGroup(
    actions: List<MessageActionItem>,
    modifier: Modifier = Modifier,
) {
    // 官方 ButtonGroup 内容 lambda 不是 @Composable，interactionSource 需在此先行备好
    // （官方 ButtonGroupWithCustomItemSample 同款做法）。
    val interactionSources = remember(actions.size) {
        List(actions.size) { MutableInteractionSource() }
    }
    BoxWithConstraints(modifier = modifier) {
        val spacing = ButtonGroupDefaults.HorizontalArrangement.spacing
        val groupWidth = minOf(
            maxWidth * 2f / 3f,
            (MessageGroupButtonMaxWidth * actions.size) + (spacing * (actions.size - 1)),
        )
        ButtonGroup(
            overflowIndicator = { menuState ->
                ButtonGroupDefaults.OverflowIndicator(menuState = menuState)
            },
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(groupWidth),
        ) {
        actions.forEachIndexed { index, action ->
            val source = interactionSources[index]
            customItem(
                buttonGroupContent = {
                    // 用户指定偏离官方档：内容内边距收到 8dp（官方 ExtraSmall 档为
                    // 12dp），纯图标按钮更窄；垂直仍取官方 6dp 档。按压压缩上限沿用
                    // 官方语义 = 该内容内边距（邻钮压进这段留白）。
                    val contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    val layoutDirection = LocalLayoutDirection.current
                    // 官方约束：animateWidth 的 parentData 必须挂在 ButtonGroup 的直接子项
                    // 上（组测量策略只读直接子项）；而官方 TooltipBox 会把外部传入的
                    // modifier 落在内部 WrappedAnchor（根 Box 无 modifier，隔了一层），组
                    // 读不到。因此按钮本体当直接子项，TooltipBox 包进按钮内容里——锚点即
                    // 图标，长按气泡照常，且手势检测不消费点击/按压，动效互不干扰。
                    ToggleButton(
                        checked = action.checked,
                        onCheckedChange = { action.onClick() },
                        enabled = action.enabled,
                        buttonSize = ToggleButtonSize.ExtraSmall,
                        contentPadding = contentPadding,
                        // 双状态配色：未选中沿用套件统一 secondaryContainer 档；选中取
                        // 官方 ToggleButton 选中 token（FilledButtonTokens.Selected* =
                        // primary / onPrimary），配合官方「胶囊 → 8dp 圆角」选中形变。
                        colors = ToggleButtonDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                            checkedContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        interactionSource = source,
                        // 官方 flexible 机制：权重均分组宽（总宽已封顶，见上），实现
                        // compact 压缩 / 宽窗口放宽；animateWidth 的按压联动叠加其上。
                        modifier = Modifier
                            .weight(1f)
                            .animateWidth(
                                interactionSource = source,
                                compressionLimit = contentPadding.calculateEndPadding(layoutDirection),
                            ),
                    ) {
                        FooterTooltipBox(action.tooltip, action.enabled) {
                            action.icon()
                        }
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(action.tooltip) },
                        onClick = {
                            menuState.dismiss()
                            action.onClick()
                        },
                        enabled = action.enabled,
                        interactionSource = source,
                    )
                },
            )
        }
        }
    }
}
