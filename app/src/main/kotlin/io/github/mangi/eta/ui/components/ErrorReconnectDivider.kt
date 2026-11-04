package io.github.mangi.eta.ui.components

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.adaptive.EtaType
import io.github.mangi.eta.ui.adaptive.etaTextStyle
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.ErrorReconnectMessageUi
import io.github.mangi.eta.ui.model.ErrorReconnectStatus
import io.github.mangi.eta.ui.model.formatReconnectElapsed
import kotlinx.coroutines.delay
import androidx.compose.material3.Text as M3Text

/** Stable lazy-row identity and one-line height: clock updates never insert or resize rows. */
@Composable
internal fun ErrorReconnectDivider(message: ErrorReconnectMessageUi, modifier: Modifier = Modifier) {
    val view = LocalView.current
    var showDetails by remember(message.id) { mutableStateOf(false) }
    var elapsed by remember(message.id) { mutableLongStateOf(message.elapsedMs.coerceAtLeast(0L)) }
    LaunchedEffect(message.id, message.status, message.elapsedMs) {
        elapsed = maxOf(elapsed, message.elapsedMs.coerceAtLeast(0L))
        if (message.status == ErrorReconnectStatus.Running) {
            val base = elapsed
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000L)
                val delta = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                elapsed = maxOf(elapsed, base + delta.coerceAtMost(Long.MAX_VALUE - base))
            }
        }
    }
    val label = errorReconnectLabel(message, elapsed)
    val title = stringResource(R.string.reconnect_error_title)
    // 只在「实时观察到的状态翻转」上补一发结果触感：成功 confirm、失败 reject。
    // remember(message.id) 在 LazyColumn 回收后重置为当前状态，因此滚动历史记录不会误触。
    var lastStatus by remember(message.id) { mutableStateOf(message.status) }
    LaunchedEffect(message.id, message.status) {
        val previous = lastStatus
        if (previous == message.status) return@LaunchedEffect
        lastStatus = message.status
        when (message.status) {
            ErrorReconnectStatus.Succeeded -> TouchHaptics.confirm(view)
            ErrorReconnectStatus.Failed -> TouchHaptics.reject(view)
            else -> Unit
        }
    }
    // M3 Expressive：官方 HorizontalDivider 承担线条粗细与颜色（DividerDefaults），
    // 官方 Surface(onClick) 承担可点区域、形状（shapes.extraSmall）与官方涟漪，
    // 文本排版与配色全部取 MaterialTheme.typography / colorScheme 官方令牌。
    // contentDescription 只保留标题，不能带 label：官方 Surface(onClick) 链尾是
    // `childSemantics()`，会把 M3Text 的文本合并进同一节点；ContentDescription 与 Text 是
    // 两个不同的 SemanticsPropertyKey，分别落在 AccessibilityNodeInfo 的两个字段，
    // TalkBack 会两串都念而非互相顶替。把 label 抄进 cd 会让状态标签播报两遍。
    Row(
        modifier = modifier.fillMaxWidth().testTag("error-reconnect:${message.id}")
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Surface(
            onClick = { TouchHaptics.click(view); showDetails = true },
            modifier = Modifier.semantics { contentDescription = title },
            shape = MaterialTheme.shapes.extraSmall,
            color = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            // 状态文案切换用 fade-through；contentKey 取状态，秒表每秒刷新时不触发重播。
            val motionScheme = MaterialTheme.motionScheme
            AnimatedContent(
                targetState = message.status to label,
                contentKey = { it.first },
                transitionSpec = {
                    fadeIn(motionScheme.defaultEffectsSpec()) togetherWith
                        fadeOut(motionScheme.fastEffectsSpec())
                },
                label = "reconnectStatus",
            ) { (_, text) ->
                M3Text(
                    text = text,
                    style = etaTextStyle(EtaType.Caption),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
    if (showDetails) {
        // This existing dialog has a bounded-height, selectable, vertically scrollable
        // body. Full diagnostics stay here, not in answer/copy/speech text.
        ContextCompactedSummarySheet(
            title = title,
            summary = listOf(message.reasonCode, message.reasonDetail)
                .filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { label },
            onDismiss = { showDetails = false },
        )
    }
}

@Composable
internal fun errorReconnectLabel(message: ErrorReconnectMessageUi, elapsedMs: Long = message.elapsedMs): String =
    when (message.status) {
        ErrorReconnectStatus.Running -> stringResource(R.string.reconnect_running, formatReconnectElapsed(elapsedMs))
        ErrorReconnectStatus.Succeeded -> stringResource(R.string.reconnect_succeeded)
        ErrorReconnectStatus.Failed -> stringResource(
            if (message.isReconnect) R.string.reconnect_failed else R.string.system_notice_runtime_failed,
        )
        ErrorReconnectStatus.Stopped -> stringResource(R.string.reconnect_stopped)
    }
