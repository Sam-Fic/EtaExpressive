package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButtonDefaults

@Composable
internal fun PreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /**
     * 特殊状态着色覆盖；null 走设置行惯例的 onSurfaceVariant（禁用态 38% onSurface）。
     */
    tint: Color? = null,
) {
    val resolvedTint = when {
        tint != null -> tint
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier.padding(end = 8.dp).size(IconButtonDefaults.smallIconSize),
        tint = resolvedTint,
    )
}
