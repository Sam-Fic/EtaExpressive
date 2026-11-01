package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.remote.RemoteConnectionStatus
import io.github.mangi.eta.ui.remote.remoteFailureText
import androidx.compose.material3.Text as M3Text

/**
 * Sticky banner over the remote chat's message area.
 *
 * Only [RemoteConnectionStatus.Reconnecting] and [RemoteConnectionStatus.Failed] render:
 * while reconnecting the transcript stays read-only (the supervisor rebuilds it from the
 * next snapshot), and on a terminal failure the user gets a retry plus a shortcut to the
 * server settings. Connected / Connecting / Disconnected render nothing.
 */
@Composable
internal fun RemoteConnectionBanner(
    serverName: String,
    status: RemoteConnectionStatus,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        is RemoteConnectionStatus.Reconnecting -> {
            val label = stringResource(R.string.remote_banner_reconnecting, serverName, status.attempt)
            BannerSurface(
                modifier = modifier,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        M3Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                    // 重连原因：光说「正在重连」用户无从下手，补一句错在哪。
                    val reason = status.kind?.let { remoteFailureText(it, status.detail) }.orEmpty()
                    if (reason.isNotEmpty()) {
                        M3Text(
                            text = reason,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        is RemoteConnectionStatus.Failed -> {
            val label = stringResource(R.string.remote_banner_failed, serverName)
            BannerSurface(
                modifier = modifier,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Column {
                    M3Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status.kind != null) {
                        M3Text(
                            text = remoteFailureText(status.kind, status.detail),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onOpenSettings) {
                            M3Text(text = stringResource(R.string.remote_banner_settings))
                        }
                        TextButton(onClick = onRetry) {
                            M3Text(text = stringResource(R.string.remote_banner_retry))
                        }
                    }
                }
            }
        }

        else -> Unit
    }
}

@Composable
private fun BannerSurface(
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            content()
        }
    }
}
