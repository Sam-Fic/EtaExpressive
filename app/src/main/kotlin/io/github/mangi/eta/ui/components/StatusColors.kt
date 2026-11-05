package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaStatus
import io.github.mangi.eta.ui.adaptive.foregroundColor
import io.github.mangi.eta.ui.model.PermissionStatusUi
import io.github.mangi.eta.ui.model.RunStatusUi

/**
 * 业务状态模型（[RunStatusUi] / [PermissionStatusUi]）到 L1 状态令牌与文案的映射。
 *
 * 颜色一律经 [EtaStatus.foregroundColor] 取得——Success 是固定的绿色成功令牌而非
 * 动态 primary（动态色会随壁纸漂移，承担不了「成功/在线」语义，见 EtaStatus KDoc），
 * Warning → `tertiary`。本文件不再自持任何 状态→角色 映射；唯一出处是
 * ui/adaptive/EtaStatus.kt，改语义去那里。
 */
@Composable
fun RunStatusUi.color() = toEtaStatus().foregroundColor()

/** 业务运行状态 → L1 语义档位。 */
fun RunStatusUi.toEtaStatus(): EtaStatus = when (this) {
    RunStatusUi.Running -> EtaStatus.Running
    RunStatusUi.Success -> EtaStatus.Success
    RunStatusUi.Failed -> EtaStatus.Failed
    RunStatusUi.Cancelled -> EtaStatus.Unknown
}

@Composable
fun RunStatusUi.label(): String = stringResource(when (this) {
    RunStatusUi.Running -> R.string.tool_status_running
    RunStatusUi.Success -> R.string.tool_status_success
    RunStatusUi.Failed -> R.string.tool_status_failed
    RunStatusUi.Cancelled -> R.string.status_cancelled
})

@Composable
fun PermissionStatusUi.color() = toEtaStatus().foregroundColor()

/** 业务权限状态 → L1 语义档位。 */
fun PermissionStatusUi.toEtaStatus(): EtaStatus = when (this) {
    PermissionStatusUi.Available -> EtaStatus.Success
    PermissionStatusUi.Warning -> EtaStatus.Warning
    PermissionStatusUi.Missing -> EtaStatus.Failed
    PermissionStatusUi.Disabled -> EtaStatus.Unknown
}

@Composable
fun PermissionStatusUi.label(): String = stringResource(when (this) {
    PermissionStatusUi.Available -> R.string.status_ready
    PermissionStatusUi.Warning -> R.string.status_needs_attention
    PermissionStatusUi.Missing -> R.string.status_unauthorized
    PermissionStatusUi.Disabled -> R.string.status_disabled
})
