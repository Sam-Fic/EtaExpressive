package io.github.mangi.eta.ui.screens.enhance

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.app.description
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text as M3Text
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow

@Composable
fun SystemEnhanceScreen(
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberDeviceCapabilities()
    val canRequestRoot = capabilities.root.suPresent && !capabilities.root.isGranted
    EtaScaffoldPage(
        title = stringResource(R.string.capability_enhancements),
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "access") {
            EtaSegmentedColumn(modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing)) {
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_root),
                        summary = capabilities.root.description(context),
                        icon = Icons.Rounded.Key,
                    ) {
                        EnhanceActionButton(
                            text = stringResource(
                                if (canRequestRoot) R.string.capability_root_request
                                else R.string.capability_root_refresh,
                            ),
                            enabled = !capabilities.root.isChecking,
                            onClick = {
                                onAction(
                                    if (canRequestRoot) AgentSystemEnhanceAction.RequestRoot
                                    else AgentSystemEnhanceAction.RefreshRoot,
                                )
                            },
                        )
                    }
                }
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_xposed_service),
                        summary = stringResource(
                            if (capabilities.xposedConnected) R.string.capability_xposed_connected
                            else R.string.capability_xposed_disconnected,
                        ),
                        icon = Icons.Rounded.AccountTree,
                    )
                }
            }
        }
        item(key = "framework-help") {
            EtaSegmentedColumn(modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing)) {
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_xposed_help),
                        summary = stringResource(R.string.capability_xposed_help_summary),
                        icon = Icons.Rounded.Info,
                    )
                }
            }
        }
        item(key = "root-features") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.capability_root_features),
            ) {
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_root_device),
                        summary = stringResource(R.string.capability_root_device_summary),
                        icon = Icons.Rounded.AdminPanelSettings,
                    )
                }
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_root_data),
                        summary = stringResource(R.string.capability_root_data_summary),
                        icon = Icons.Rounded.Lock,
                    )
                }
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_root_linux),
                        summary = stringResource(R.string.capability_root_linux_summary),
                        icon = Icons.Rounded.Terminal,
                    )
                }
            }
        }
        item(key = "hook-features") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(start = EtaGroupHorizontalPadding, end = EtaGroupHorizontalPadding, bottom = EtaSegmentedGroupSpacing),
                title = stringResource(R.string.capability_system_features),
            ) {
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_hook_assistants),
                        summary = stringResource(R.string.capability_hook_assistants_summary),
                        icon = Icons.Rounded.AutoAwesome,
                    )
                }
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_hook_google),
                        summary = stringResource(R.string.capability_hook_google_summary),
                        icon = Icons.AutoMirrored.Rounded.ManageSearch,
                    )
                }
                item {
                    EnhanceRow(
                        title = stringResource(R.string.capability_hook_accessibility),
                        summary = stringResource(R.string.capability_hook_accessibility_summary),
                        icon = Icons.Rounded.VerifiedUser,
                    )
                }
            }
        }
    }
}

/**
 * 系统增强页的设置行。
 *
 * 走官方分段行 [EtaSegmentedRow]（自带涟漪、语义与按压形变）。
 */
@Composable
private fun EnhanceRow(
    title: String,
    summary: String,
    icon: ImageVector,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val leading: @Composable () -> Unit = {
        Icon(imageVector = icon, contentDescription = null)
    }
    val supporting: @Composable () -> Unit = {
        M3Text(text = summary)
    }
    val headline: @Composable () -> Unit = {
        M3Text(text = title)
    }
    // KernelSU 式分段行：形状/容器色取自外层分段组，独立使用时退化为单条目形状。
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        leadingContent = leading,
        content = headline,
        supportingContent = supporting,
        trailingContent = trailing?.let { slot -> { Row { slot() } } },
    )
}

/** 行尾主操作按钮：官方 [Button] + 官方 `shapes`（按压 morph，AGENTS §2.5）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EnhanceActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shapes = ButtonDefaults.shapes()) {
        M3Text(text = text)
    }
}
