package io.github.mangi.eta.ui.screens.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import io.github.mangi.eta.ui.adaptive.EtaScaffoldPage
import io.github.mangi.eta.ui.adaptive.EtaSegmentedColumn
import io.github.mangi.eta.ui.adaptive.EtaSegmentedRow
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.contextLabel
import io.github.mangi.eta.ui.components.contextStatusLabel

/**
 * 子助手名册页。
 *
 * 从顶栏溢出菜单的「子助手状态」跳入；数据取当前会话的实时投影，页面留在返回栈上
 * 也会随子助手起落而变化。排序与旧弹窗一致：先按状态优先级，再按会话标题。
 */
@Composable
internal fun SubAgentStatusScreen(
    agents: List<SubAgentContextStats>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val ordered = remember(agents) {
        agents.sortedWith(compareBy({ statusRank(it.status) }, { it.contextLabel(context) }))
    }

    EtaScaffoldPage(
        title = stringResource(R.string.action_sub_agent_status),
        onBack = onBack,
    ) {
        if (ordered.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.sub_agent_status_empty),
                    modifier = Modifier.padding(bottom = 13.dp),
                )
            }
        } else {
            item(key = "roster") {
                EtaSegmentedColumn(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp),
                ) {
                    ordered.forEachIndexed { index, agent ->
                        item(key = index) {
                            SubAgentStatusRow(agent = agent)
                        }
                    }
                }
            }
        }
    }
}

/** 单个子助手：左侧两行（会话标题 / 模型），右侧状态文案。 */
@Composable
private fun SubAgentStatusRow(agent: SubAgentContextStats) {
    val context = LocalContext.current
    EtaSegmentedRow(
        content = {
            Column {
                Text(
                    text = agent.contextLabel(context),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = agent.modelName.ifBlank { agent.model },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Text(
                text = agent.contextStatusLabel(context),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        },
    )
}

/** 状态优先级：在跑的排前面，终态排后面（沿用旧弹窗的次序）。 */
private fun statusRank(status: String): Int = when (status) {
    "running" -> 0
    "pausing" -> 1
    "queued" -> 2
    "awaiting_decision" -> 3
    "paused" -> 4
    "failed" -> 5
    "timed_out" -> 6
    "cancelled" -> 7
    "completed" -> 8
    else -> 9
}
