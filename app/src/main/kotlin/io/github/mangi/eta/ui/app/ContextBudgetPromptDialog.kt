package io.github.mangi.eta.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults as M3CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaDialog
import io.github.mangi.eta.ui.adaptive.EtaText
import io.github.mangi.eta.ui.adaptive.EtaTextStyle
import io.github.mangi.eta.ui.haptics.TouchHaptics
import androidx.compose.material3.Card as M3Card
import androidx.compose.material3.Text as M3Text

@Composable
internal fun ContextBudgetPromptDialog(
    reason: String,
    onDismiss: () -> Unit,
    onCompactThisRun: () -> Unit,
    onStop: () -> Unit,
) {
    val view = LocalView.current
    // 正文说明走官方 AlertDialog 的 summary 槽。
    EtaDialog(
        show = true,
        title = stringResource(R.string.context_budget_title),
        summary = stringResource(R.string.context_budget_body),
        onDismissRequest = onDismiss,
        actions = {
            ContextBudgetChoices(
                onCompact = {
                    TouchHaptics.click(view)
                    onCompactThisRun()
                },
                onPause = {
                    TouchHaptics.click(view)
                    onDismiss()
                },
                onStop = {
                    TouchHaptics.click(view)
                    onStop()
                },
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (reason.isNotBlank()) {
                ContextBudgetReasonCard(reason = reason.trim())
            }
            EtaText(
                text = stringResource(R.string.context_budget_hint),
                style = EtaTextStyle.Secondary,
            )
        }
    }
}

/** 三个处置选项；颜色只取当前主题族的角色色。 */
@Composable
private fun ContextBudgetChoices(
    onCompact: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ContextBudgetChoice(
            title = stringResource(R.string.context_budget_compact),
            summary = stringResource(R.string.context_budget_compact_summary),
            container = MaterialTheme.colorScheme.primary,
            content = MaterialTheme.colorScheme.onPrimary,
            onClick = onCompact,
        )
        ContextBudgetChoice(
            title = stringResource(R.string.context_budget_pause),
            summary = stringResource(R.string.context_budget_pause_summary),
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
            onClick = onPause,
        )
        ContextBudgetChoice(
            title = stringResource(R.string.context_budget_stop),
            summary = stringResource(R.string.context_budget_stop_summary),
            container = MaterialTheme.colorScheme.error,
            content = MaterialTheme.colorScheme.onError,
            onClick = onStop,
        )
    }
}

/** 触发原因的高层级容器：官方 [M3Card]。 */
@Composable
private fun ContextBudgetReasonCard(reason: String) {
    M3Card(modifier = Modifier.fillMaxWidth()) {
        M3Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** 可点选项：官方 [M3Card] 的 onClick 重载（自带涟漪与按压形变）。 */
@Composable
private fun ContextBudgetChoice(
    title: String,
    summary: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    M3Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = M3CardDefaults.cardColors(
            containerColor = container,
            contentColor = content,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            M3Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = content,
            )
            M3Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.78f),
            )
        }
    }
}
