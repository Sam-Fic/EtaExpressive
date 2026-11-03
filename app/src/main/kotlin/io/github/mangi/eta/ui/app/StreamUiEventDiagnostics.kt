package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.ui.components.StreamPerformanceDiagnostics

/**
 * Fixed labels only: never runtime class names, content, IDs, tool names or arguments.
 * Timings are nested (not additive): ui.runEvent > event type > ui.messages.apply
 * > transform / normalize / publish > conversation stages. Summary refresh may be
 * outside ui.messages.apply. Owner view and roster publication straddle the question
 * scan deliberately, preserving the existing projection callback order.
 */
internal object StreamUiEventDiagnostics {
    // Inline the disabled path: no new timing, label selection or capturing lambda.
    // A null stage leaves flushes outside the live enqueue/timer paths unclassified.
    inline fun <T> measure(stage: String?, value: Long = 0, crossinline block: () -> T): T {
        if (stage == null || !StreamPerformanceDiagnostics.enabled) return block()
        return StreamPerformanceDiagnostics.measure(stage, value) { block() }
    }

    inline fun <T> measureEvent(event: AgentEvent, crossinline block: () -> T): T {
        if (!StreamPerformanceDiagnostics.enabled) return block()
        return StreamPerformanceDiagnostics.measure(eventStage(event)) { block() }
    }

    inline fun <T> withEvent(runId: String, conversationId: String?, selected: Boolean?, event: AgentEvent,
        replay: Boolean = false, crossinline block: () -> T): T {
        if (!StreamPerformanceDiagnostics.enabled) return block()
        val attribution = StreamPerformanceDiagnostics.eventAttribution(event, runId, conversationId, selected,
            eventStage(event).removePrefix("ui.event."), replay)
        return StreamPerformanceDiagnostics.withAttribution(attribution) { block() }
    }

    // Exhaustive sealed-event mapping: new event kinds require an explicit, bounded label.
    fun eventStage(event: AgentEvent): String = when (event) {
        is AgentEvent.AssistantBlockStart -> when (event.kind) {
            AgentEvent.AssistantBlockKind.TEXT -> "ui.event.start.text"
            AgentEvent.AssistantBlockKind.THINKING -> "ui.event.start.thinking"
            AgentEvent.AssistantBlockKind.TOOL_CALL -> "ui.event.start.toolCall"
        }
        is AgentEvent.AssistantBlockDelta -> when (event.kind) {
            AgentEvent.AssistantBlockKind.TEXT -> "ui.event.delta.text"
            AgentEvent.AssistantBlockKind.THINKING -> "ui.event.delta.thinking"
            AgentEvent.AssistantBlockKind.TOOL_CALL -> "ui.event.delta.toolCall"
        }
        is AgentEvent.AssistantBlockEnd -> when (event.kind) {
            AgentEvent.AssistantBlockKind.TEXT -> "ui.event.end.text"
            AgentEvent.AssistantBlockKind.THINKING -> "ui.event.end.thinking"
            AgentEvent.AssistantBlockKind.TOOL_CALL -> "ui.event.end.toolCall"
        }
        is AgentEvent.RunStarted -> "ui.event.runStarted"
        is AgentEvent.GuiControlChanged -> "ui.event.guiControl"
        is AgentEvent.DecisionFallback -> "ui.event.decisionFallback"
        is AgentEvent.RoundStarted -> "ui.event.roundStarted"
        is AgentEvent.ModelRetryScheduled -> "ui.event.modelRetry"
        is AgentEvent.ErrorReconnectChanged -> "ui.event.reconnect"
        is AgentEvent.ProviderRequestStarted -> "ui.event.providerRequest"
        is AgentEvent.ProviderResponseStarted -> "ui.event.providerResponse"
        is AgentEvent.AssistantReceived -> "ui.event.assistantReceived"
        is AgentEvent.ChildContextUpdated -> "ui.event.childContext"
        is AgentEvent.UsageReceived -> if (event.projected) "ui.event.usage.projected" else "ui.event.usage.receipt"
        is AgentEvent.UserSupplementReceived -> "ui.event.supplement"
        is AgentEvent.ToolStarted -> "ui.event.toolStarted"
        is AgentEvent.ToolFinished -> "ui.event.toolFinished"
        is AgentEvent.HostedToolStarted -> "ui.event.hostedToolStarted"
        is AgentEvent.HostedToolFinished -> "ui.event.hostedToolFinished"
        is AgentEvent.ToolImagesAttached -> "ui.event.toolImages"
        is AgentEvent.AutoCompactWaiting -> "ui.event.compactWaiting"
        is AgentEvent.ContextCompactionStarted -> "ui.event.compactStarted"
        is AgentEvent.ContextCompacted -> "ui.event.compacted"
        is AgentEvent.RunFinished -> "ui.event.runFinished"
        is AgentEvent.RunFailed -> "ui.event.runFailed"
        is AgentEvent.QuestionRequested -> "ui.event.questionRequested"
        is AgentEvent.QuestionResolved -> "ui.event.questionResolved"
    }
}
