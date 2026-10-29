package io.github.mangi.eta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.concurrent.atomic.AtomicLong

internal val LocalStreamDiagnosticRow = staticCompositionLocalOf<StreamDiagnosticAttribution?> { null }
private val streamDiagnosticLists = AtomicLong()
internal fun nextStreamDiagnosticListId(): Long = streamDiagnosticLists.incrementAndGet()
internal fun timelineDiagnosticRowType(row: AgentTimelineRow): String = when (row) {
    is AgentTimelineRow.Message -> when (row.message) {
        is UserMessageUi -> "user"
        is AgentMessageUi -> "agent"
        is ThinkingMessageUi -> "thinking"
        is ToolActivityMessageUi -> "tool"
        else -> "message"
    }
    is AgentTimelineRow.WorkStep -> when (row.message) {
        is ToolActivityMessageUi -> "work-tool"
        is ThinkingMessageUi -> "work-thinking"
        is AgentQuestionMessageUi -> "work-question"
        else -> "work-summary"
    }
}

internal fun diagnosticRenderFields(a: StreamDiagnosticAttribution?): String =
    "listToken=${a?.list ?: 0} rowToken=${a?.row ?: 0} rowType=${diagnosticRowType(a?.rowType ?: "unknown")} " +
        "blockIndex=${a?.block ?: -1} blockType=${diagnosticBlockTypeForOutput(a?.blockType)} blockChars=${a?.blockChars ?: 0} " +
        "component=${diagnosticComponentType(a?.component ?: "unknown")} renderIdentity=anonymousSessionLocalNotEventCausality"

private fun diagnosticBlockTypeForOutput(type: String?): String = when (type) {
    "paragraph", "heading", "list", "quote", "code", "table", "html", "image", "rule", "other" -> type
    else -> "unknown"
}

internal fun diagnosticComponentType(raw: String): String = when (raw) {
    "agent", "model_features", "context_extensions", "general", "tools", "runtime", "system_takeover",
    "diagnostics", "permissions" -> raw
    else -> "unknown"
}

internal fun androidx.compose.ui.Modifier.settingsSectionDiagnostics(section: String): androidx.compose.ui.Modifier {
    StreamPerformanceDiagnostics.sessionGeneration.longValue
    if (!StreamPerformanceDiagnostics.enabled) return this
    val attr = StreamPerformanceDiagnostics.componentAttribution(section)
    return streamDiagnosticMeasure("settings.section.measure", attr).streamDiagnosticDraw("settings.section.draw", attr)
}
