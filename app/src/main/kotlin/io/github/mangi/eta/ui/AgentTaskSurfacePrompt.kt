package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskPrompt
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.ui.adaptive.EtaExpressiveDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics

/**
 * 应用内兜底：独立弹窗 Activity 没能拉起来（例如后台启动受限）或已退到后台时，
 * 回到Eta界面也能看到同一个待决选择。
 */
@Composable
internal fun AgentTaskSurfacePrompt() {
    val pending by AgentTaskPrompt.pending.collectAsState()
    val hostVisible by AgentTaskPrompt.hostVisible.collectAsState()
    val request = pending ?: return
    // 先给独立弹窗一点时间出现，避免两个弹窗前后闪一下。
    var fallbackReady by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) {
        kotlinx.coroutines.delay(FALLBACK_DELAY_MS)
        fallbackReady = true
    }
    if (hostVisible || !fallbackReady) return
    // 换成下一个请求时重置单选状态。
    key(request.id) {
        AgentTaskSurfaceChoiceDialog(
            onChoose = { mode -> AgentTaskPrompt.answer(request.id, mode) },
            onCancel = { AgentTaskPrompt.answer(request.id, null) },
        )
    }
}

private const val FALLBACK_DELAY_MS = 800L

/**
 * Material 3 单选弹窗：先选前台或后台，再点“继续”。
 * 默认选中前台；点“取消”或返回会让本次界面操作失败，模型改用别的方式。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AgentTaskSurfaceChoiceDialog(
    onChoose: (AgentTaskSurfaceMode) -> Unit,
    onCancel: () -> Unit,
) {
    val view = LocalView.current
    var selected by rememberSaveable { mutableStateOf(AgentTaskSurfaceMode.FOREGROUND) }
    EtaExpressiveDialog(
        onDismissRequest = onCancel,
        // 点到弹窗外不算取消，避免误触让这次操作失败；返回键仍可取消。
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Rounded.TouchApp, contentDescription = null) },
        title = { Text(stringResource(R.string.agent_task_surface_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.agent_task_surface_prompt_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 选项行自身就是官方 ListItem（自带容器形状、内边距与单选语义），
                // 不再手写 Surface + RoundedCornerShape(16.dp) + selectable 拼装。
                Column(modifier = Modifier.selectableGroup()) {
                    SurfaceChoiceRow(
                        icon = Icons.Rounded.PhoneAndroid,
                        title = stringResource(R.string.agent_task_surface_foreground),
                        summary = stringResource(R.string.agent_task_preference_mode_hint_foreground),
                        selected = selected == AgentTaskSurfaceMode.FOREGROUND,
                        onClick = {
                            TouchHaptics.click(view)
                            selected = AgentTaskSurfaceMode.FOREGROUND
                        },
                    )
                    HorizontalDivider()
                    SurfaceChoiceRow(
                        icon = Icons.Rounded.Layers,
                        title = stringResource(R.string.agent_task_surface_background),
                        summary = stringResource(R.string.agent_task_preference_mode_hint_background),
                        selected = selected == AgentTaskSurfaceMode.BACKGROUND,
                        onClick = {
                            TouchHaptics.click(view)
                            selected = AgentTaskSurfaceMode.BACKGROUND
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    TouchHaptics.click(view)
                    onChoose(selected)
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(R.string.agent_task_surface_prompt_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = {
                TouchHaptics.click(view)
                onCancel()
            }) {
                Text(stringResource(R.string.agent_task_surface_prompt_cancel))
            }
        },
    )
}

@Composable
private fun SurfaceChoiceRow(
    icon: ImageVector,
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 官方单选版 ListItem：selected + onClick 提供选中容器色、Role.RadioButton 语义、
    // 按压形变与涟漪；正文槽位参数名是 content（headlineContent 只属于无 onClick 的展示重载）。
    ListItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        leadingContent = {
            // 图标尺寸走官方 Icon 默认值，不再手写 24.dp。
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        content = { Text(title) },
        supportingContent = {
            Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
}
