package io.github.mangi.eta.ui.screens.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as M3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.app.DaemonTaskUi
import io.github.mangi.eta.ui.haptics.TouchHaptics
import kotlinx.coroutines.launch

/**
 * 守护任务列表页；终端块视图与控制台视图共用入口跳转到这里。
 *
 * 数据由路由页实时投影（终端会话仍在常驻运行），所以停在页面上任务状态也会继续变化。
 */
@Composable
internal fun DaemonTasksScreen(
    tasks: List<DaemonTaskUi>,
    onBack: () -> Unit,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    EtaScaffoldPage(
        title = stringResource(R.string.terminal_daemon_tasks),
        onBack = onBack,
    ) {
        if (tasks.isEmpty()) {
            item(key = "empty") {
                TerminalBodyText(
                    text = stringResource(R.string.terminal_daemon_empty),
                    color = terminalMutedForeground(),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        } else {
            items(items = tasks, key = { it.id }) { task ->
                // 行与它展开的日志面板同属一个条目，展开时不会把后续行挤散。
                Column(modifier = Modifier.fillMaxWidth()) {
                    DaemonTaskRow(task = task, onStop = onStop, onLoadLogs = onLoadLogs)
                }
            }
        }
    }
}

@Composable
private fun DaemonTaskRow(
    task: DaemonTaskUi,
    onStop: (String) -> Unit,
    onLoadLogs: suspend (String) -> String,
) {
    val view = LocalView.current
    var logsExpanded by remember(task.id) { mutableStateOf(false) }
    var logs by remember(task.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun toggleLogs() {
        TouchHaptics.click(view)
        logsExpanded = !logsExpanded
        if (logsExpanded && logs == null) {
            scope.launch { logs = onLoadLogs(task.id) }
        }
    }

    // 官方 ListItem：点按行展开日志，自带涟漪、语义与按压形变；停止动作用官方图标按钮。
    ListItem(
        onClick = ::toggleLogs,
        modifier = Modifier.fillMaxWidth(),
        content = {
            M3Text(
                text = task.command,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            M3Text(
                text = daemonMeta(task),
                style = MaterialTheme.typography.bodyMedium,
                color = if (task.running) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = {
            TerminalIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.terminal_stop),
                onClick = { onStop(task.id) },
                shapes = IconButtonDefaults.shapes(),
            )
        },
    )
    DaemonLogsPanel(logsExpanded = logsExpanded, logs = logs)
}

/**
 * 日志面板。走官方 `Card`（容器 `surfaceContainerHighest`、形状 `shapes.medium` 均取默认值）。
 */
@Composable
private fun DaemonLogsPanel(logsExpanded: Boolean, logs: String?) {
    if (!logsExpanded) return
    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            if (logs == null) {
                TerminalBusyIndicator(size = 16.dp)
            } else {
                TerminalMonoText(text = AnnotatedString(logs))
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun daemonMeta(task: DaemonTaskUi): String {
    val environmentLabel = when (task.environment) {
        TerminalEnvironment.ANDROID -> "Android"
        TerminalEnvironment.ALPINE -> "Alpine"
        TerminalEnvironment.DEBIAN -> "Debian"
    }
    val stateLabel = stringResource(
        if (task.running) R.string.terminal_daemon_running else R.string.terminal_daemon_exited
    )
    return "$environmentLabel · ${task.identity} · $stateLabel"
}
