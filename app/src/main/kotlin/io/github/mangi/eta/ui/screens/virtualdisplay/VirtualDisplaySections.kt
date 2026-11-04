package io.github.mangi.eta.ui.screens.virtualdisplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.VirtualDisplayPreviewLifecycle
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.agent.device.VirtualDisplayWebPreview
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedGroupSpacing
import io.github.mangi.eta.ui.adaptive.EtaSegmentedItemContainer
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.adaptive.EtaStatusDot
import io.github.mangi.eta.ui.adaptive.EtaTrailingChevron
import io.github.mangi.eta.ui.adaptive.EtaSwitchRow
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.rememberTaskBackendInstalled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 页面的轻量状态：只保留会改变分组的字段，刻意不含 `frame` / `touchEffect` / `frameSequence`。
 * 页面据此做去重订阅，实时画面以 30fps 更新时整页不会跟着重组。
 */
@Immutable
internal data class VirtualDisplayStatus(
    val active: Boolean = false,
    val appName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val displayId: Int = 0,
    val controlling: Boolean = false,
) {
    companion object {
        fun from(snapshot: VirtualDisplaySnapshot): VirtualDisplayStatus = VirtualDisplayStatus(
            active = snapshot.active,
            appName = if (snapshot.active) snapshot.appName else "",
            width = if (snapshot.active) snapshot.width else 0,
            height = if (snapshot.active) snapshot.height else 0,
            displayId = if (snapshot.active) snapshot.displayId else 0,
            controlling = snapshot.active && snapshot.userControlling,
        )
    }
}

/** 状态分组：应用 / 分辨率 / 显示 ID / 画面。未运行时统一显示破折号。 */
@Composable
internal fun VirtualDisplayStatusSection(
    status: VirtualDisplayStatus,
    released: Boolean,
    modifier: Modifier = Modifier,
) {
    val none = stringResource(R.string.vd_screen_value_none)
    val picture = when {
        status.active -> stringResource(
            if (status.controlling) R.string.vd_screen_state_controlling else R.string.vd_screen_state_live,
        )
        released -> stringResource(R.string.vd_screen_state_released)
        else -> none
    }
    EtaSegmentedColumn(
        modifier = modifier,
        title = stringResource(R.string.vd_screen_info_title),
    ) {
        item(key = "status-app") {
            StatusValueRow(
                label = stringResource(R.string.vd_screen_info_app),
                value = status.appName.ifBlank { none },
            )
        }
        item(key = "status-resolution") {
            StatusValueRow(
                label = stringResource(R.string.vd_screen_info_resolution),
                value = if (status.width > 0 && status.height > 0) {
                    "${status.width} × ${status.height}"
                } else {
                    none
                },
            )
        }
        item(key = "status-display-id") {
            StatusValueRow(
                label = stringResource(R.string.vd_screen_info_display_id),
                value = if (status.active) status.displayId.toString() else none,
            )
        }
        item(key = "status-picture") {
            StatusValueRow(
                label = stringResource(R.string.vd_screen_info_picture),
                value = picture,
                activeDot = status.active,
                showDot = true,
            )
        }
    }
}

@Composable
private fun StatusValueRow(
    label: String,
    value: String,
    activeDot: Boolean = false,
    showDot: Boolean = false,
) {
    EtaSegmentedRow(
        content = { Text(label) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
            ) {
                if (showDot) EtaStatusDot(active = activeDot)
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/**
 * 控制分组：手动接管、移交主屏、关闭虚拟屏。
 *
 * 接管开关的唯一样本是 `snapshot.userControlling`，这里只转发用户意图，不保存本地布尔。
 * 动作行点击由 [EtaSegmentedRow] 自带触感，调用方不要重复触发。
 */
@Composable
internal fun VirtualDisplayControlSection(
    status: VirtualDisplayStatus,
    onTakeoverChange: (Boolean) -> Unit,
    onHandoff: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    handoffEnabled: Boolean = true,
) {
    EtaSegmentedColumn(
        modifier = modifier,
        title = stringResource(R.string.vd_screen_control_title),
    ) {
        item(key = "control-takeover") {
            EtaSwitchRow(
                title = stringResource(R.string.vd_screen_takeover),
                summary = stringResource(R.string.vd_screen_takeover_summary),
                checked = status.controlling,
                onCheckedChange = onTakeoverChange,
                enabled = enabled && status.active,
            )
        }
        item(key = "control-handoff") {
            EtaSegmentedRow(
                enabled = enabled && handoffEnabled && status.active,
                onClick = onHandoff,
                content = { Text(stringResource(R.string.vd_screen_handoff)) },
                supportingContent = { Text(stringResource(R.string.vd_screen_handoff_summary)) },
                trailingContent = { EtaTrailingChevron() },
            )
        }
        item(key = "control-close") {
            val closeEnabled = enabled && status.active
            // error 是显式覆盖色，不吃 EtaSegmentedRow 的禁用处理；禁用时压到 38%，
            // 与可点击态区分（同 PreferenceIcon / PersonalVoicesScreen 的禁用约定）。
            val closeColor = MaterialTheme.colorScheme.error.copy(alpha = if (closeEnabled) 1f else 0.38f)
            EtaSegmentedRow(
                enabled = closeEnabled,
                onClick = onClose,
                content = {
                    Text(
                        text = stringResource(R.string.vd_screen_close),
                        color = closeColor,
                    )
                },
                supportingContent = { Text(stringResource(R.string.vd_screen_close_summary)) },
                trailingContent = { EtaTrailingChevron(tint = closeColor) },
            )
        }
    }
}

/**
 * 网页预览与副屏恢复分组。
 *
 * 恢复逻辑从旧的内联控件逐段搬移：读取状态不会改动副屏；网页授权独立于副屏是否存在；
 * 离开页面不撤销已配对浏览器；后端模块被卸载时撤销授权；恢复进行中由调用方吞掉返回。
 *
 * @param onWorkingChanged 任一只读/恢复动作进行中时为 true，页面据此启停返回守卫。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun VirtualDisplayRecoverySection(
    modifier: Modifier = Modifier,
    context: Context = LocalContext.current,
    readStatus: (Context) -> JSONObject = VirtualDisplaySession::recoveryStatus,
    recover: (Context) -> JSONObject = VirtualDisplaySession::recoverAndFinishManually,
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    val installed = rememberTaskBackendInstalled()
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var working by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var webPaired by remember { mutableStateOf(false) }

    fun setWorking(value: Boolean) {
        working = value
        onWorkingChanged(value)
    }

    LaunchedEffect(installed, working) {
        if (installed == false && !working) {
            try {
                withContext(NonCancellable + Dispatchers.IO) { VirtualDisplayWebPreview.revoke(context) }
                webPaired = false
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                result = JSONObject().put("ok", false).put("error", "WEB_PREVIEW_REVOKE_FAILED")
            }
        }
    }

    fun refresh() {
        if (working) return
        setWorking(true)
        scope.launch {
            try {
                webPaired = withContext(Dispatchers.IO) { VirtualDisplayWebPreview.hasPairing(context) }
                state = withContext(Dispatchers.IO) { readStatus(context.applicationContext) }
            } catch (ex: CancellationException) {
                throw ex
            } catch (_: Exception) {
                state = JSONObject().put("ok", false).put("error", "RECOVERY_STATE_UNREADABLE")
            } finally {
                setWorking(false)
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Navigation is not revocation. The explicit revoke action owns the pairing lifetime.
    DisposableEffect(Unit) {
        onDispose { onWorkingChanged(false) }
    }

    val snapshot = state
    val readable = snapshot?.optBoolean("ok") == true
    val present = snapshot?.optBoolean("present") == true
    val busy = snapshot?.optBoolean("busy") == true
    val phase = snapshot?.optString("phase", RECOVERY_PHASE_PENDING) ?: RECOVERY_PHASE_PENDING
    // 没有副屏也能首次授权网页；已有配对时始终可撤销。
    // 首次读取期间 working=true 会吞掉返回键，此时也显示分组（带进度），避免“无响应”。
    val showRecovery = result != null || working || (snapshot != null && (!readable || present))
    val showWeb = installed == true || webPaired
    if (!showRecovery && !showWeb) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EtaSegmentedGroupSpacing),
    ) {
        if (showWeb) {
            EtaSegmentedColumn(title = stringResource(R.string.vd_screen_web_title)) {
                item(key = "web-note", surface = false) {
                    Text(
                        text = stringResource(R.string.vd_screen_web_summary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = EtaDimens.SpaceSm),
                    )
                }
                item(key = "web-actions") {
                    EtaSegmentedItemContainer {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(EtaDimens.SpaceLg),
                            verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceMd),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                            ) {
                                EtaStatusDot(active = webPaired)
                                Text(
                                    text = stringResource(
                                        if (webPaired) {
                                            R.string.vd_screen_web_paired
                                        } else {
                                            R.string.vd_screen_web_not_paired
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                                verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                            ) {
                                OutlinedButton(
                                    enabled = installed == true && !working,
                                    onClick = {
                                        if (!working) {
                                            TouchHaptics.click(view)
                                            setWorking(true)
                                            scope.launch {
                                                try {
                                                    val uri = withContext(Dispatchers.IO) {
                                                        VirtualDisplayWebPreview.openWithManualClose(context)
                                                    }
                                                    val stillInstalled = withContext(Dispatchers.IO) {
                                                        AgentTaskSurface.moduleInstalled()
                                                    }
                                                    if (!stillInstalled) {
                                                        withContext(NonCancellable + Dispatchers.IO) {
                                                            VirtualDisplayWebPreview.revoke(context)
                                                        }
                                                        webPaired = false
                                                        error("Backend module removed")
                                                    }
                                                    webPaired = true
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                    )
                                                } catch (ex: CancellationException) {
                                                    throw ex
                                                } catch (ex: Exception) {
                                                    // A partial disk commit with failed cleanup is not an ordinary offline error.
                                                    webPaired = true // Always leave an explicit revoke action available after failure.
                                                    val error = if (ex is VirtualDisplayPreviewLifecycle.PersistenceCleanupException) {
                                                        "WEB_PREVIEW_REVOKE_FAILED"
                                                    } else {
                                                        "WEB_PREVIEW_OPEN_FAILED"
                                                    }
                                                    result = JSONObject().put("ok", false).put("error", error)
                                                } finally {
                                                    setWorking(false)
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.vd_preview_open),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(
                                    enabled = webPaired && !working,
                                    onClick = {
                                        if (!working) {
                                            TouchHaptics.click(view)
                                            setWorking(true)
                                            scope.launch {
                                                try {
                                                    withContext(NonCancellable + Dispatchers.IO) {
                                                        VirtualDisplayWebPreview.revoke(context)
                                                    }
                                                    webPaired = false
                                                    result = null
                                                } catch (ex: CancellationException) {
                                                    throw ex
                                                } catch (_: Exception) {
                                                    result = JSONObject().put("ok", false)
                                                        .put("error", "WEB_PREVIEW_REVOKE_FAILED")
                                                } finally {
                                                    setWorking(false)
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        text = stringResource(R.string.vd_preview_revoke),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showRecovery) {
            EtaSegmentedColumn(title = stringResource(R.string.agent_task_preference_recovery_title)) {
                item(key = "recovery-status") {
                    EtaSegmentedItemContainer {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(EtaDimens.SpaceLg),
                            verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                            ) {
                                if (working) {
                                    // M3E LoadingIndicator：正文行内 18dp 槽，配色显式取 primary。
                                    LoadingIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    EtaStatusDot(active = readable && present && !busy)
                                }
                                Text(
                                    text = stringResource(recoveryStatusRes(readable, busy, present, phase)),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (present) {
                                Text(
                                    text = stringResource(
                                        R.string.agent_task_preference_recovery_display_id,
                                        snapshot.optInt("displayId", -1),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (readable && present && !busy && phase != RECOVERY_PHASE_PENDING) {
                                Text(
                                    text = stringResource(
                                        R.string.agent_task_preference_recovery_raw_phase,
                                        phase,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val lastError = snapshot?.optString("lastError").orEmpty()
                            if (lastError.isNotBlank()) {
                                Text(
                                    text = lastError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val detail = snapshot?.optString("lastDetail").orEmpty()
                                if (detail.isNotBlank()) {
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            result?.let { receipt ->
                                if (receipt.optBoolean("ok") && receipt.optBoolean("released")) {
                                    Text(
                                        text = stringResource(R.string.vd_recovery_success),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(
                                            R.string.vd_recovery_failed,
                                            receipt.optString("error", "UNKNOWN"),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    val message = receipt.optString("message")
                                    if (message.isNotBlank()) {
                                        Text(
                                            text = message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                                verticalArrangement = Arrangement.spacedBy(EtaDimens.SpaceSm),
                            ) {
                                TextButton(
                                    enabled = installed == true && !working,
                                    onClick = {
                                        TouchHaptics.click(view)
                                        refresh()
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(ButtonDefaults.IconSize),
                                    )
                                    Spacer(Modifier.width(EtaDimens.SpaceSm))
                                    Text(
                                        text = stringResource(R.string.vd_recovery_refresh),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Button(
                                    enabled = installed == true && !working && snapshot != null &&
                                        snapshot.optBoolean("ok") && snapshot.optBoolean("present") &&
                                        snapshot.optBoolean("recoverable"),
                                    onClick = {
                                        if (!working) {
                                            TouchHaptics.click(view)
                                            setWorking(true)
                                            result = null
                                            scope.launch {
                                                try {
                                                    val receipt = withContext(Dispatchers.IO) {
                                                        recover(context.applicationContext)
                                                    }
                                                    result = receipt
                                                    state = withContext(Dispatchers.IO) {
                                                        readStatus(context.applicationContext)
                                                    }
                                                } catch (ex: CancellationException) {
                                                    throw ex
                                                } catch (_: Exception) {
                                                    result = JSONObject().put("ok", false)
                                                        .put("error", "RECOVERY_OPERATION_FAILED")
                                                } finally {
                                                    setWorking(false)
                                                }
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (working) {
                                                R.string.vd_recovery_working
                                            } else {
                                                R.string.vd_recovery_action
                                            },
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val RECOVERY_PHASE_PENDING = "recovery_pending"

/** 只有「等待恢复」是已确认可读的状态码；其余码显示为状态未知，原始码留在次级文字里。 */
private fun recoveryStatusRes(readable: Boolean, busy: Boolean, present: Boolean, phase: String): Int = when {
    !readable -> R.string.vd_recovery_unknown
    busy -> R.string.vd_recovery_busy
    // 恢复完成后记录已清空：phase 缺省值不是“等待恢复”。
    !present -> R.string.vd_recovery_empty
    phase == RECOVERY_PHASE_PENDING -> R.string.agent_task_preference_recovery_phase_waiting
    else -> R.string.agent_task_preference_recovery_phase_unknown
}
