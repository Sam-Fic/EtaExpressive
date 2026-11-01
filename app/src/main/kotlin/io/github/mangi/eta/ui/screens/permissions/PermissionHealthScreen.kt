package io.github.mangi.eta.ui.screens.permissions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaClickableRow
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.components.color
import io.github.mangi.eta.ui.components.label
import io.github.mangi.eta.ui.model.PermissionHealthAction
import io.github.mangi.eta.ui.model.PermissionHealthItemUi
import io.github.mangi.eta.ui.model.PermissionHealthUiState

@Composable
fun PermissionHealthScreen(
    state: PermissionHealthUiState,
    onAction: (PermissionHealthAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    EtaScaffoldPage(
        title = stringResource(R.string.ui_permission_health_3048bb),
        onBack = { onAction(PermissionHealthAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "card") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.ui_permissions_and_status_35f368),
            ) {
                // 每行注册为独立分段条目，才能拿到参考实现的首/末圆角与 2dp 缝隙动画。
                state.items.forEach { item ->
                    item(key = item.id) {
                        PermissionItemRow(
                            item = item,
                            onActionClick = { onAction(PermissionHealthAction.OpenItemAction(item.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionItemRow(
    item: PermissionHealthItemUi,
    onActionClick: () -> Unit,
) {
    val icon = when (item.id) {
        "accessibility" -> Icons.Rounded.AccessibilityNew
        "overlay" -> Icons.Rounded.Layers
        "model" -> Icons.Rounded.Memory
        "terminal" -> Icons.Rounded.Terminal
        "notification" -> Icons.Rounded.Notifications
        "local_network" -> Icons.Rounded.Wifi
        "root" -> Icons.Rounded.Key
        "shizuku" -> Icons.Rounded.Memory
        "xposed" -> Icons.Rounded.AccountTree
        "background" -> Icons.Rounded.History
        "app_list" -> Icons.Rounded.Dashboard
        "location" -> Icons.Rounded.LocationOn
        else -> Icons.Rounded.Shield
    }

    EtaClickableRow(
        title = item.title,
        summary = item.summary.takeIf { it.isNotBlank() },
        leading = {
            PreferenceIcon(icon = icon)
        },
        endActions = {
            EtaText(
                text = item.status.label(),
                style = EtaTextStyle.Body,
                color = item.status.color(),
            )
        },
        onClick = onActionClick,
    )
}
