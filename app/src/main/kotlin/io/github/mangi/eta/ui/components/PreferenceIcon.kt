package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.adaptive.EtaAlphas
import io.github.mangi.eta.ui.adaptive.LocalEtaSegmentedRowContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme

/**
 * 设置页行首图标：官方默认几何——裸 [Icon]（24dp，间距由官方
 * `ListItem`/`SegmentedListItem` 的 `ItemBetweenSpace` 12dp 施加，不加自定义内边距）。
 *
 * 着色统一取 `onSurfaceVariant`（与工具行「成功」态同款）；禁用态按 Material 惯例
 * 压到 38% 不透明度。特殊状态（运行中 primary、失败 error 等）由调用方直接用
 * [Icon] 指定 tint。所在分段行被临时换成亮底（宽屏设置左栏选中行）时，改吃
 * [LocalEtaSegmentedRowContentColor] 给出的官方配套前景色，保证对比度。
 *
 * [destructive] 用于破坏性设置行（清理、清除、删除、移除、重置等）：启用态取官方
 * `error` 角色色，与 [io.github.mangi.eta.ui.adaptive.EtaClickableRow] 的
 * `destructive` 配套使用，保证各页面强调色一致。
 */
@Composable
internal fun PreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val selectedContentColor = LocalEtaSegmentedRowContentColor.current
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier,
        tint = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = EtaAlphas.DisabledContent)
            destructive -> MaterialTheme.colorScheme.error
            selectedContentColor != null -> selectedContentColor
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
