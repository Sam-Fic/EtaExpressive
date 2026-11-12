package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
import io.github.mangi.eta.ui.adaptive.EtaExtendedFab
import io.github.mangi.eta.ui.adaptive.EtaGroupHorizontalPadding
import io.github.mangi.eta.ui.adaptive.EtaNoticeText
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.adaptive.EtaTrailingChevron
import io.github.mangi.eta.ui.adaptive.rememberFabExpandedState
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as M3IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as M3Text


@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val view = LocalView.current
    val listState = rememberLazyListState()
    val fabExpanded by rememberFabExpandedState(listState)

    EtaScaffoldPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        listState = listState,
        floatingActionButton = {
            EtaExtendedFab(
                text = stringResource(R.string.mcp_add_server),
                icon = Icons.Rounded.Add,
                expanded = fabExpanded,
                onClick = {
                    TouchHaptics.click(view)
                    // 添加服务器是整屏表单子页：走路由（RULES §3.3），进出场交给 NavDisplay。
                    onNavigate(AppRoute.McpServerAdd)
                },
            )
        },
    ) {
        item(key = "servers") {
            EtaSegmentedColumn(
                modifier = Modifier.padding(
                    start = EtaGroupHorizontalPadding,
                    end = EtaGroupHorizontalPadding,
                    bottom = EtaSegmentedGroupSpacing,
                ),
                title = stringResource(R.string.mcp_configured_servers, servers.size),
            ) {
                if (servers.isEmpty()) {
                    // 空态是「非行」内容，用分段容器承载；新增入口交给右下角 FAB，避免重复按钮。
                    item {
                        EtaSegmentedItemContainer {
                            ListEmptyState(title = stringResource(R.string.mcp_empty_title))
                        }
                    }
                } else {
                    servers.forEach { server ->
                        item(key = server.id) {
                            McpClickableRow(
                                title = server.name,
                                summary = stringResource(
                                    R.string.mcp_server_row_summary,
                                    server.activeTools.size,
                                    server.tools.size,
                                ),
                                leading = { McpLeadingIcon(Icons.Rounded.Dns) },
                                onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                            )
                        }
                    }
                }
            }
        }
    }

}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }

    EtaScaffoldPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            McpActionIcon(
                icon = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.mcp_refresh_tools),
                enabled = server != null && !working,
                onClick = {
                    scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { McpServerManager.refresh(serverId) }
                        }
                        working = false
                        Toast.makeText(
                            context,
                            result.fold(
                                onSuccess = { resources.getString(R.string.mcp_refreshed, it.tools.size) },
                                onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                // 纯说明文字不带容器，直接排在页面背景上。
                EtaNoticeText(
                    title = stringResource(R.string.mcp_server_missing),
                    modifier = mcpGroupModifier(),
                )
            }
            return@EtaScaffoldPage
        }
        item(key = "server") {
            EtaSegmentedColumn(
                modifier = mcpGroupModifier(),
                title = stringResource(R.string.mcp_server_settings),
            ) {
                item {
                    EtaSwitchRow(
                        title = stringResource(R.string.mcp_enable_server),
                        summary = server.url,
                        checked = server.enabled,
                        leading = { McpLeadingIcon(Icons.Rounded.Dns) },
                        onCheckedChange = { enabled ->
                            scope.launch(Dispatchers.IO) {
                                McpServerRepository.update(server.copy(enabled = enabled))
                            }
                        },
                    )
                }
                item {
                    McpClickableRow(
                        title = stringResource(R.string.mcp_update_token),
                        summary = stringResource(
                            if (server.authorizationType == McpAuthorizationType.BEARER) {
                                R.string.mcp_token_configured
                            } else {
                                R.string.mcp_no_authentication
                            }
                        ),
                        leading = { McpLeadingIcon(Icons.Rounded.Key) },
                        onClick = { showToken = true },
                    )
                }
            }
        }
        item(key = "tools") {
            EtaSegmentedColumn(
                modifier = mcpGroupModifier(),
                title = stringResource(R.string.mcp_tools_count, server.tools.size),
            ) {
                if (server.tools.isEmpty()) {
                    item {
                        EtaSegmentedItemContainer {
                            ListEmptyState(
                                title = stringResource(R.string.mcp_no_tools),
                                summary = stringResource(R.string.mcp_refresh_tools_hint),
                            )
                        }
                    }
                } else {
                    // 每个工具一条独立分段行，首末项自动接管外圆角，符合 KernelSU 分段列表约定。
                    server.tools.forEach { tool ->
                        item(key = tool.name) {
                            val checked = tool.name in server.enabledToolNames
                            EtaSwitchRow(
                                title = tool.title.ifBlank { tool.name },
                                summary = toolSummary(tool),
                                checked = checked,
                                onCheckedChange = { enabled ->
                                    if (enabled && tool.readOnlyHint != true) {
                                        pendingRiskyTool = tool
                                    } else {
                                        scope.launch(Dispatchers.IO) {
                                            McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        item(key = "delete") {
            EtaSegmentedColumn(modifier = mcpGroupModifier()) {
                item {
                    McpDestructiveRow(
                        title = stringResource(R.string.mcp_delete_server),
                        summary = stringResource(R.string.mcp_delete_server_summary),
                        onClick = { showDelete = true },
                    )
                }
            }
        }
    }

    pendingRiskyTool?.let { tool ->
        EtaDialog(
            show = true,
            title = stringResource(R.string.mcp_enable_risky_tool),
            summary = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
            onDismissRequest = { pendingRiskyTool = null },
            actions = {
                EtaDialogActions(
                    confirmText = stringResource(R.string.mcp_enable_tool),
                    destructive = true,
                    onCancel = { pendingRiskyTool = null },
                    onConfirm = {
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.setToolEnabled(serverId, tool.name, true)
                        }
                        pendingRiskyTool = null
                    },
                )
            },
        ) {
        }
    }

    EtaDialog(
        show = showToken,
        title = stringResource(R.string.mcp_update_token),
        summary = stringResource(R.string.mcp_update_token_summary),
        onDismissRequest = { showToken = false },
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { showToken = false },
                onConfirm = {
                    server?.let { current ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(
                                current.copy(
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                ),
                                bearerToken = token,
                            )
                        }
                        token = ""
                        showToken = false
                    }
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            McpTextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_token),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
    }

    EtaDialog(
        show = showDelete,
        title = stringResource(R.string.mcp_delete_server),
        summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
        onDismissRequest = { showDelete = false },
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                onCancel = { showDelete = false },
                onConfirm = {
                    scope.launch {
                        withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                        onBack()
                    }
                },
            )
        },
    ) {
    }
}

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}

/** 分段列表分组的统一页边距与组间距（引用项目自适应令牌，不手写数值）。 */
private fun mcpGroupModifier(): Modifier = Modifier.padding(
    start = EtaGroupHorizontalPadding,
    end = EtaGroupHorizontalPadding,
    bottom = EtaSegmentedGroupSpacing,
)

/** 分段行前导图标：统一 primary 色。 */
@Composable
private fun McpLeadingIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** 顶栏动作：官方 [M3IconButton]。 */
@Composable
private fun McpActionIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    M3IconButton(onClick = onClick, enabled = enabled, shapes = IconButtonDefaults.shapes(), ) {
        Icon(imageVector = icon, contentDescription = contentDescription)
    }
}

/** 可点击设置行：官方分段行（onClick 重载自带涟漪与语义）。 */
@Composable
private fun McpClickableRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = leading,
        content = { M3Text(text = title) },
        supportingContent = {
            M3Text(text = summary)
        },
        trailingContent = {
            EtaTrailingChevron()
        },
    )
}

/** 破坏性设置行（删除）：用 error 色强调破坏性。 */
@Composable
private fun McpDestructiveRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    EtaSegmentedRow(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        content = {
            M3Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        },
        supportingContent = {
            M3Text(
                text = summary,
            )
        },
    )
}

/** 输入框：官方 [OutlinedTextField]。internal：本文件与 McpServerAddScreen 共用。 */
@Composable
internal fun McpTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        label = { M3Text(text = label) },
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
    )
}
