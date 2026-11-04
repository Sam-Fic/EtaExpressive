package io.github.mangi.eta.ui.adaptive

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * KernelSU 式 Expressive 按钮组件：
 *
 * - [EtaTopBarBackButton]：顶栏返回钮带 surfaceContainerHighest 底色圆形，返回键从
 *   「裸图标」升级为「可感知的可点目标」；
 * - [EtaExpressiveSwitch]：选中显示 Check、关闭显示 Close 的拇指图标 + 完整禁用态配色；
 * - [EtaExpressiveToggleButton]：选中 primary / 未选 surfaceContainerHighest 的切换钮。
 */

/** 顶栏返回钮：surfaceContainerHighest 底色圆形 + onSurfaceVariant 图标（KernelSU TopBarBackButton）。 */
@Composable
fun EtaTopBarBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    // 纯图标按钮：有标签时同时提供长按提示，没有标签则保持原样（由调用方自查）。
    val button: @Composable () -> Unit = {
        IconButton(
            modifier = modifier.padding(horizontal = 10.dp),
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = contentDescription,
            )
        }
    }
    if (contentDescription != null) {
        EtaTooltip(text = contentDescription, content = button)
    } else {
        button()
    }
}

/**
 * Expressive 开关：拇指内常驻状态图标（选中 Check / 关闭 Close），配色取 KernelSU 的
 * expressiveSwitchColors —— 选中图标 primary、未选图标 surfaceContainerHighest，
 * 禁用态全部由官方角色降透明表达。
 */
@Composable
fun EtaExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showThumbIcon: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = if (showThumbIcon && (checked || enabled)) {
            {
                Icon(
                    imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                    contentDescription = null,
                    modifier = Modifier.size(SwitchDefaults.IconSize),
                )
            }
        } else {
            null
        },
        enabled = enabled,
        colors = etaExpressiveSwitchColors(),
    )
}

@Composable
private fun etaExpressiveSwitchColors(): SwitchColors {
    val scheme = MaterialTheme.colorScheme
    return SwitchDefaults.colors(
        checkedIconColor = scheme.primary,
        uncheckedIconColor = scheme.surfaceContainerHighest,
        disabledCheckedThumbColor = scheme.surface.copy(alpha = EtaAlphas.DisabledContent),
        disabledCheckedTrackColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledCheckedIconColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledUncheckedThumbColor = scheme.outline.copy(alpha = EtaAlphas.DisabledContent),
        disabledUncheckedTrackColor = scheme.surfaceContainerHighest.copy(alpha = 0.12f),
        disabledUncheckedBorderColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledUncheckedIconColor = scheme.surfaceContainerHighest,
    )
}

/**
 * Expressive 切换钮：选中 primary/onPrimary，未选 surfaceContainerHighest/onSurface。
 *
 * [shapes] 为连接按钮组位置形状（`ButtonGroupDefaults.connected*Shapes()`），与 KernelSU
 * `ExpressiveToggleButton` 一致为必填；单体使用请传 `ToggleButtonDefaults.shapesFor(height)`。
 *
 * 注意：选中态的静止形状由 [shapes] 里的 `checkedShape` 决定，官方
 * `ButtonGroupDefaults.connected*ButtonShapes()` 已把它设为 `connectedButtonCheckedShape`
 * （四角全部大圆角）。若自行拼 `ToggleButtonShapes`，必须一并给对 `checkedShape`，
 * 否则选中项会一直停在连体位置形状、拿不到该有的四角大圆角。
 */
@Composable
fun EtaExpressiveToggleButton(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ToggleButtonShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ToggleButton(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = ToggleButtonDefaults.colors(
            checkedContainerColor = scheme.primary,
            checkedContentColor = scheme.onPrimary,
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
        ),
        shapes = shapes,
        content = content,
    )
}

/**
 * 自适应扩展 FAB：官方 [SmallExtendedFloatingActionButton]（KernelSU `TemplateMaterial` 同款），
 * 形状 / 容器色 / 图标尺寸全部取 `FloatingActionButtonDefaults` 默认值；[expanded] 由调用方
 * 的滚动状态机驱动，收起时只留图标，与 KernelSU 的 fabExpanded 一致。
 *
 * 位置与底部安全区由宿主骨架（`EtaScaffoldPage`）负责，本组件只负责外观。
 */
@Composable
fun EtaExtendedFab(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
) {
    SmallExtendedFloatingActionButton(
        expanded = expanded,
        onClick = onClick,
        // 折叠态文字隐藏后图标必须仍有标签；展开态由 text 播报，避免重复。
        icon = { Icon(imageVector = icon, contentDescription = if (expanded) null else text) },
        text = { Text(text = text) },
        modifier = modifier,
    )
}

/** 预留：与 KernelSU expressiveToggleButtonColors 等价的取色入口（供需要覆盖默认色的调用方）。 */
@Composable
fun etaExpressiveToggleButtonColors(
    checkedContainerColor: Color = MaterialTheme.colorScheme.primary,
    checkedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
): ToggleButtonColors = ToggleButtonDefaults.colors(
    checkedContainerColor = checkedContainerColor,
    checkedContentColor = checkedContentColor,
    containerColor = containerColor,
    contentColor = contentColor,
)
