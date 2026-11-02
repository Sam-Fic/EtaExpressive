package io.github.mangi.eta.ui.screens.terminal

import io.github.mangi.eta.R
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.app.displayName

@Immutable
internal data class SessionDialogRow(
    val id: String,
    val environment: TerminalEnvironment,
    /** 块式终端传 cwd；控制台传空串。 */
    val subtitle: String,
    val active: Boolean,
    val running: Boolean,
    val alive: Boolean,
)

/** 终端会话列表面板；块式终端与控制台共用。点按行切换会话，行内提供重启与关闭。 */
@Composable
internal fun SessionListDialog(
    rows: List<SessionDialogRow>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onRestart: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit,
) {
    EtaDialog(
        show = true,
        title = stringResource(R.string.terminal_sessions),
        onDismissRequest = onDismiss,
        actions = {
            TerminalTextButton(
                text = stringResource(R.string.terminal_new_session),
                onClick = onNew,
                modifier = Modifier.fillMaxWidth(),
            )
        },
    ) {
        if (rows.isEmpty()) {
            TerminalBodyText(
                text = stringResource(R.string.terminal_session_empty),
                color = terminalMutedForeground(),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(items = rows, key = { it.id }) { row ->
                    SessionRow(
                        row = row,
                        onSelect = { onSelect(row.id); onDismiss() },
                        onRestart = { onRestart(row.id) },
                        onClose = { onClose(row.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    row: SessionDialogRow,
    onSelect: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
) {
    val view = LocalView.current
    // 官方单选列表项：selected 语义、涟漪、按压形变与内边距全部由 ListItem 自带。
    ListItem(
        selected = row.active,
        onClick = {
            TouchHaptics.click(view)
            onSelect()
        },
        modifier = Modifier.fillMaxWidth(),
        content = {
            M3Text(
                text = row.environment.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        supportingContent = {
            Column {
                if (row.active) {
                    M3Text(
                        text = stringResource(R.string.terminal_session_current),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (row.subtitle.isNotEmpty()) {
                    M3Text(
                        text = row.subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // 尾槽内是「状态文字 + 两个 XS 图标按钮」：按钮 32dp 容器比 20dp 图标
                // 每侧各含 6dp 内缩，spacedBy(4.dp) 的视觉节奏约 12~16dp，
                // 与全局 EtaDimens.TrailingElementGap 一致，因此不再改成字面 12dp。
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                M3Text(
                    text = sessionStateLabel(row),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (row.running) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                TerminalIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.terminal_restart_session),
                    onClick = onRestart,
                )
                TerminalIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.terminal_close_session),
                    onClick = onClose,
                )
            }
        },
    )
}

@Composable
private fun sessionStateLabel(row: SessionDialogRow): String = stringResource(
    when {
        !row.alive -> R.string.terminal_session_exited
        row.running -> R.string.terminal_daemon_running
        else -> R.string.terminal_session_idle
    },
)
