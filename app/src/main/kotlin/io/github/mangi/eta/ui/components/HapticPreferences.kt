package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.adaptive.EtaSwitch
import io.github.mangi.eta.ui.adaptive.etaSegmentedRowColors
import io.github.mangi.eta.ui.adaptive.etaSegmentedRowShapes
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** 列表行禁用态整体透明度。 */
private const val M3DisabledAlpha = 0.5f

/**
 * 列表行骨架：官方 SegmentedListItem（KernelSU 式分段列表行）。
 * 分段形状/容器色由 `EtaSegmentedList` 统一提供（外层分段列注入，独立使用时退化为
 * 单条目形状）；标题 + 可选摘要 + 前导图标 + 尾部插槽，禁用态整体半透明。
 */
@Composable
private fun M3PreferenceRow(
    title: String,
    summary: String?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    startAction: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shapes = etaSegmentedRowShapes()
    val colors = etaSegmentedRowColors()
    val rowModifier = modifier.alpha(if (enabled) 1f else M3DisabledAlpha)
    val supporting: (@Composable () -> Unit)? = summary?.let { text ->
        {
            Text(text = text)
        }
    }
    if (onClick != null) {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            onClick = onClick,
            shapes = shapes,
            colors = colors,
            modifier = rowModifier,
            enabled = enabled,
            leadingContent = startAction,
            trailingContent = trailing,
            supportingContent = supporting,
            content = { Text(text = title) },
        )
    } else {
        SegmentedListItem(
            verticalAlignment = Alignment.CenterVertically,
            shapes = shapes,
            colors = colors,
            modifier = rowModifier,
            enabled = false,
            leadingContent = startAction,
            trailingContent = trailing,
            supportingContent = supporting,
            content = { Text(text = title) },
        )
    }
}

/**
 * 箭头行原语（官方分段列表行）。
 *
 * **屏幕层请优先使用 [io.github.mangi.eta.ui.adaptive.EtaClickableRow]**：它在本原语之上补齐了
 * 「默认尾部箭头 / `showChevron` / `holdDownState` / 统一轻触反馈」等约定。
 *
 * 轻触反馈由本原语自己触发，调用方**不要**在 `onClick` 里再写
 * `TouchHaptics.click`，否则会叠成两次。
 */
@Composable
internal fun ArrowPreference(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable RowScope.() -> Unit = {},
    bottomAction: (@Composable () -> Unit)? = null,
    // M3 行内边距由官方 ListItem 令牌提供，此形参仅为保持既有调用签名。
    insideMargin: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    // 显式类型：let 返回嵌套 lambda 时推断器需要标注才能落到 (() -> Unit)?。
    val m3Click: (() -> Unit)? = onClick?.let { click -> { TouchHaptics.click(view); click() } }
    // Row 的 receiver 就是 RowScope，直接转发 endActions。
    val m3Trailing: @Composable () -> Unit = { Row { endActions() } }
    Column(modifier = modifier) {
        M3PreferenceRow(
            title = title,
            summary = summary,
            enabled = enabled,
            startAction = startAction,
            onClick = m3Click,
            trailing = m3Trailing,
        )
        bottomAction?.invoke()
    }
}

@Composable
internal fun SwitchPreference(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    insideMargin: PaddingValues = PaddingValues(16.dp),
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    M3PreferenceRow(
        title = title,
        summary = summary,
        enabled = enabled,
        modifier = modifier,
        startAction = startAction,
        onClick = if (enabled) {
            { TouchHaptics.toggle(view, !checked); onCheckedChange(!checked) }
        } else {
            null
        },
        trailing = {
            EtaSwitch(
                checked = checked,
                onCheckedChange = { value ->
                    TouchHaptics.toggle(view, value)
                    onCheckedChange(value)
                },
                enabled = enabled,
            )
        },
    )
}

@Composable
internal fun HapticBasicComponent(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: @Composable (() -> Unit)? = null,
    endActions: @Composable (RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
) {
    val view = LocalView.current
    Column(modifier = modifier) {
        M3PreferenceRow(
            title = title,
            summary = summary,
            enabled = enabled,
            startAction = startAction,
            onClick = onClick?.let { click -> { TouchHaptics.click(view); click() } },
            trailing = endActions?.let { actions ->
                val trailing: @Composable () -> Unit = { Row { actions() } }
                trailing
            },
        )
        bottomAction?.invoke()
    }
}
