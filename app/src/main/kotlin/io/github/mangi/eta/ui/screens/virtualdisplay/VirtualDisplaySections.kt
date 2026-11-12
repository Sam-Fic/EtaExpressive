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
import androidx.compose.runtime.collectAsState
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
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.VirtualDisplayPreviewLifecycle
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.agent.device.VirtualDisplayWebPreview
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.ui.adaptive.EtaAlphas
import io.github.mangi.eta.ui.adaptive.EtaDimens
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaDialogActions
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
            val closeColor = MaterialTheme.colorScheme.error.copy(alpha = if (closeEnabled) 1f else EtaAlphas.DisabledContent)
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
 * 恢复组用「状态卡 + 行式动作」结构（同页控制分组的既有语言）：状态、说明与刷新
 * 收在一张 [EtaSegmentedItemContainer] 卡里，两个动作各占一行——主操作走普通
 * [EtaSegmentedRow]，危险动作「强制重置」走 error 色文案行，确认交给
 * [EtaDialog] + [EtaDialogActions]（`destructive = true`，同 VirtualDisplayScreen
 * 关闭确认模式），不再用按钮内联两段确认。
 *
 * 动作行点击由 [EtaSegmentedRow] 自带触感，调用方不要重复触发。
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
    reset: (Context) -> JSONObject = VirtualDisplaySession::forceReset,
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    val installed = rememberTaskBackendInstalled()
    val root by RootAccess.state.collectAsState()
    // 免模块形态下 owner 由 root 拉起；归档的模块式形态仍走 installed。任一成立即可管理恢复。
    val backendReady = installed == true || root.isGranted
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var working by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var result by remember { mutableStateOf<JSONObject?>(null) }
    var webPaired by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

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
                                    shapes = ButtonDefaults.shapes(),
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
                                    shapes = ButtonDefaults.shapes(),
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
                            // 状态行：加载槽 / 状态点 + 状态主文案占满中段，行尾是「副屏 N」值——
                            // 沿用本组 StatusValueRow 的「左标签右值」语言，不再单独占一行。
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
                                    text = stringResource(recoveryStatusRes(working, readable, busy, present, phase)),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (present) {
                                    Text(
                                        text = stringResource(
                                            R.string.agent_task_preference_recovery_display_id,
                                            snapshot.optInt("displayId", -1),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
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
                                val forceResetDone = receipt.optBoolean("ok") && receipt.optBoolean("forcedReset")
                                if (receipt.optBoolean("ok") && (receipt.optBoolean("released") || forceResetDone)) {
                                    Text(
                                        text = stringResource(
                                            if (forceResetDone) {
                                                R.string.vd_recovery_force_reset_done
                                            } else {
                                                R.string.vd_recovery_success
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    // 强制重置的分级结果与滞留说明照实展示。
                                    val note = receipt.optString("message")
                                    if (forceResetDone && note.isNotBlank()) {
                                        Text(
                                            text = note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
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
                            // 刷新状态：状态卡里唯一的轻量动作；状态/回执都属可读内容，
                            // 排在次级文案之后贴卡片底部。
                            TextButton(
                                enabled = backendReady && !working,
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
                        }
                    }
                }
                item(key = "recovery-action") {
                    // 主操作行：同控制分组的动作行语言（标题 + summary + chevron）。
                    // 点击触感由 EtaSegmentedRow 自带，不要重复触发。
                    val recoverEnabled = backendReady && !working && snapshot != null &&
                        snapshot.optBoolean("ok") && snapshot.optBoolean("present") &&
                        snapshot.optBoolean("recoverable")
                    EtaSegmentedRow(
                        enabled = recoverEnabled,
                        onClick = {
                            setWorking(true)
                            result = null
                            showResetConfirm = false
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
                        },
                        content = { Text(stringResource(R.string.vd_recovery_action)) },
                        supportingContent = { Text(stringResource(R.string.vd_recovery_action_summary)) },
                        trailingContent = { EtaTrailingChevron() },
                    )
                }
                item(key = "recovery-reset") {
                    // 危险动作行：error 是显式覆盖色，不吃 EtaSegmentedRow 的禁用处理；
                    // 禁用时压到 38%（EtaAlphas.DisabledContent），与可点击态区分——
                    // 同 VirtualDisplayControlSection「关闭虚拟屏」行的禁用约定。
                    val resetEnabled = backendReady && !working && readable && present
                    val resetColor = MaterialTheme.colorScheme.error.copy(alpha = if (resetEnabled) 1f else EtaAlphas.DisabledContent)
                    EtaSegmentedRow(
                        enabled = resetEnabled,
                        onClick = { showResetConfirm = true },
                        content = {
                            Text(
                                text = stringResource(R.string.vd_recovery_force_reset),
                                color = resetColor,
                            )
                        },
                        supportingContent = { Text(stringResource(R.string.vd_recovery_force_reset_summary)) },
                        trailingContent = { EtaTrailingChevron(tint = resetColor) },
                    )
                }
            }
        }
    }

    // 强制重置确认：危险动作不走按钮内联两段确认，交给标准对话框（同页关闭确认模式）；
    // 确认钮以 error 色文案区分（官方 AlertDialog 动作形态，无按钮底色）。
    EtaDialog(
        show = showResetConfirm,
        title = stringResource(R.string.vd_recovery_force_reset),
        onDismissRequest = { if (!working) showResetConfirm = false },
        actions = {
            EtaDialogActions(
                confirmText = stringResource(R.string.vd_recovery_force_reset),
                onCancel = { if (!working) showResetConfirm = false },
                onConfirm = {
                    showResetConfirm = false
                    if (!working) {
                        setWorking(true)
                        result = null
                        scope.launch {
                            try {
                                result = withContext(Dispatchers.IO) {
                                    reset(context.applicationContext)
                                }
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
                confirmEnabled = !working,
                destructive = true,
            )
        },
    ) {
        Text(
            text = stringResource(R.string.vd_recovery_force_reset_confirm),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val RECOVERY_PHASE_PENDING = "recovery_pending"

/**
 * 恢复状态主文案的取值；[working] 优先——首次读取期间 snapshot 还没回来，
 * 不能显示「无法读取恢复状态」与加载指示器自相矛盾。
 *
 * 只有「等待恢复」是已确认可读的状态码；其余码显示为状态未知，原始码留在次级文字里。
 */
private fun recoveryStatusRes(
    working: Boolean,
    readable: Boolean,
    busy: Boolean,
    present: Boolean,
    phase: String,
): Int = when {
    working -> R.string.vd_recovery_working
    !readable -> R.string.vd_recovery_unknown
    busy -> R.string.vd_recovery_busy
    // 恢复完成后记录已清空：phase 缺省值不是“等待恢复”。
    !present -> R.string.vd_recovery_empty
    phase == RECOVERY_PHASE_PENDING -> R.string.agent_task_preference_recovery_phase_waiting
    else -> R.string.agent_task_preference_recovery_phase_unknown
}
