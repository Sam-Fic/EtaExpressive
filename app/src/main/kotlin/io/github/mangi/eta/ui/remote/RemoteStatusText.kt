package io.github.mangi.eta.ui.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.agent.remote.RemoteFailure

/**
 * 连接状态的本地化文案——**唯一**的取文案入口。
 *
 * 之前状态里直接存一句中文成品文案，于是三个界面（设置页行、聊天横幅、侧栏）
 * 各写一份拼接逻辑，三种语言全都显示中文，而且三份说法还不一样。
 *
 * 现在状态里存的是**结构化的失败性质**（[RemoteFailure.Kind]），
 * 文案统一在这里按语言取：同一种失败，在任何界面、任何语言下都是同一句话。
 */
@Composable
internal fun remoteStatusLabel(status: RemoteConnectionStatus): String = when (status) {
    RemoteConnectionStatus.Connected -> stringResource(R.string.remote_agents_status_connected)
    RemoteConnectionStatus.Connecting -> stringResource(R.string.remote_agents_status_connecting)
    is RemoteConnectionStatus.Reconnecting -> joinStatus(
        base = stringResource(R.string.remote_agents_status_reconnecting, status.attempt),
        kind = status.kind,
        detail = status.detail,
    )
    is RemoteConnectionStatus.Failed -> joinStatus(
        base = stringResource(R.string.remote_agents_status_failed),
        kind = status.kind,
        detail = status.detail,
    )
    RemoteConnectionStatus.Disconnected -> stringResource(R.string.remote_agents_status_disconnected)
}

/**
 * 失败原因接在状态后面。
 *
 * 光有「连接失败」四个字等于没说——用户分不清是令牌错了、地址写错了，
 * 还是包根本没送到服务器。失败性质由连接层判定，这里只负责把它讲清楚。
 */
@Composable
internal fun joinStatus(
    base: String,
    kind: RemoteFailure.Kind?,
    detail: String?,
): String {
    if (kind == null) return base
    val reason = remoteFailureText(kind, detail)
    return if (reason.isBlank()) base else "$base · $reason"
}

/**
 * 失败性质 → 用户看得懂的说明。
 *
 * 规矩是**只陈述已经观测到的事实**：断了哪一层、是什么性质，
 * 可能的成因用「可能」列出，绝不替用户断言原因。
 */
@Composable
internal fun remoteFailureText(kind: RemoteFailure.Kind, detail: String? = null): String {
    val extra = detail?.trim().orEmpty().ifEmpty { null }
    return when (kind) {
        RemoteFailure.Kind.ConnectTimeout ->
            stringResource(R.string.remote_failure_connect_timeout)
        RemoteFailure.Kind.ReadTimeout ->
            stringResource(R.string.remote_failure_read_timeout)
        RemoteFailure.Kind.Refused ->
            stringResource(R.string.remote_failure_refused)
        RemoteFailure.Kind.Unreachable ->
            stringResource(R.string.remote_failure_unreachable)
        RemoteFailure.Kind.UnknownHost ->
            stringResource(R.string.remote_failure_unknown_host, extra ?: "—")
        RemoteFailure.Kind.MalformedAddress ->
            stringResource(R.string.remote_failure_malformed_address, extra ?: "—")
        RemoteFailure.Kind.PermissionDenied ->
            stringResource(R.string.remote_failure_permission)
        RemoteFailure.Kind.CleartextBlocked ->
            stringResource(R.string.remote_failure_cleartext)
        RemoteFailure.Kind.Unauthorized ->
            stringResource(R.string.remote_failure_unauthorized, extra ?: "401")
        RemoteFailure.Kind.NotFound ->
            stringResource(R.string.remote_failure_not_found, extra ?: "404")
        RemoteFailure.Kind.UnsupportedProtocol ->
            stringResource(R.string.remote_failure_unsupported_protocol, extra ?: "—")
        RemoteFailure.Kind.BindFailed ->
            stringResource(R.string.remote_failure_bind_failed)
        // 重试救不回来，所以文案必须直接给出「去哪放行」，否则用户只能一直重连。
        RemoteFailure.Kind.LocalNetworkBlocked ->
            stringResource(R.string.remote_failure_local_network_blocked)
        RemoteFailure.Kind.Unknown ->
            stringResource(R.string.remote_failure_unknown, extra ?: "—")
    }
}
