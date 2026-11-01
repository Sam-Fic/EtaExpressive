package io.github.mangi.eta.agent.remote

import kotlinx.serialization.Serializable

/**
 * One remote agent server the user configured. Protocol-agnostic: [protocolId]
 * selects the [RemoteAgentProtocolAdapter] that knows how to speak to it.
 */
@Serializable
data class RemoteServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** Shared secret; empty means the server runs with auth off. */
    val authToken: String = "",
    val protocolId: String = "pi",
)

/** Connection lifecycle as the UI sees it. Reconnecting carries the attempt for the banner. */
sealed interface RemoteConnectionStatus {
    data object Disconnected : RemoteConnectionStatus
    data object Connecting : RemoteConnectionStatus
    data object Connected : RemoteConnectionStatus

    /**
     * @param attempt 第几次重试（1 起）
     * @param kind 最近一次失败的性质；可重连的错误在这里给出性质，
     *   免得用户只看到一个永远转圈的「重连中」。
     * @param detail 附带信息（主机名、HTTP 状态码等），可为空。
     *
     * 这里存的是**结构化的性质**而不是一句成品文案：文案由界面按语言取，
     * 见 `ui/remote/RemoteStatusText.kt`。把中文直接塞进状态里会让三种语言
     * 都显示中文，也会让每个界面各写一份说法。
     */
    data class Reconnecting(
        val attempt: Int,
        val kind: RemoteFailure.Kind? = null,
        val detail: String? = null,
    ) : RemoteConnectionStatus

    /**
     * 终态失败。[kind] 面向用户说明重试也没用时错在哪，[detail] 是它的补充信息。
     */
    data class Failed(
        val kind: RemoteFailure.Kind,
        val detail: String? = null,
    ) : RemoteConnectionStatus
}

/** What a protocol can express; the UI hides controls the adapter cannot back. */
data class RemoteCapabilities(
    val supportsPrompt: Boolean,
    val supportsSessions: Boolean,
    val supportsModelSwitch: Boolean,
    val supportsAttachments: Boolean,
    val supportsInterrupt: Boolean,
    val supportsHistoryPaging: Boolean,
)

// ─── Normalized content ──────────────────────────────────────────────────────
//
// Protocol adapters fold their own wire shapes into these, so the UI bridge
// (ui/remote) never sees a pi-specific frame.

data class RemoteAttachment(
    val id: String,
    val name: String,
    /** "image" renders inline; "file" renders as a chip. */
    val kind: String,
    val mimeType: String,
    val size: Long,
    val url: String,
)

/** One ordered assistant content segment within a turn. */
sealed interface RemotePart {
    data class Text(val text: String) : RemotePart
    data class Thinking(val text: String, val done: Boolean) : RemotePart
    data class Tool(
        val toolCallId: String,
        val toolName: String,
        val argsJson: String?,
        val resultJson: String?,
        val isError: Boolean,
        val done: Boolean,
        val elapsedMs: Long?,
    ) : RemotePart
}

data class RemoteContextUsage(
    val tokens: Int? = null,
    val contextWindow: Int? = null,
    val percent: Double? = null,
)

/** A committed conversation turn. Assistant content lives in [parts]; the rest in [text]. */
data class RemoteTurn(
    val role: String,
    val text: String? = null,
    val attachments: List<RemoteAttachment> = emptyList(),
    val parts: List<RemotePart> = emptyList(),
    val error: Boolean = false,
    val ts: Long? = null,
)

/** The in-flight assistant turn: ordered parts plus whether the trailing text is still open. */
data class RemoteLiveTurn(
    val parts: List<RemotePart> = emptyList(),
    val textOpen: Boolean = false,
)

data class RemotePromptAction(val label: String, val value: String)

data class RemotePrompt(
    val id: String,
    val question: String,
    val recommended: String? = null,
    val recommended2: String? = null,
    val actions: List<RemotePromptAction> = emptyList(),
    val dismissOnly: Boolean = false,
    val allowSkip: Boolean = false,
)

data class RemoteModelOption(val spec: String, val name: String)

data class RemoteSessionInfo(
    val path: String,
    val name: String? = null,
    val firstMessage: String = "",
    val modified: String = "",
    val messageCount: Int = 0,
    val unsaved: Boolean = false,
)

/**
 * Normalized downstream event. Every adapter emits these; the UI bridge folds
 * them into an [io.github.mangi.eta.ui.model.AgentChatUiState].
 */
sealed interface RemoteAgentEvent {
    data class Snapshot(
        val turns: List<RemoteTurn>,
        val live: RemoteLiveTurn?,
        val running: Boolean,
        val prompt: RemotePrompt?,
        val contextUsage: RemoteContextUsage?,
        val modelLabel: String?,
        val held: List<String>,
        val heldRunActive: Boolean,
        val taskWidget: List<String>?,
    ) : RemoteAgentEvent

    data class RunStarted(val modelLabel: String?) : RemoteAgentEvent
    data class TextDelta(val delta: String) : RemoteAgentEvent
    data object TextEnd : RemoteAgentEvent
    data class ThinkingDelta(val delta: String) : RemoteAgentEvent
    data object ThinkingEnd : RemoteAgentEvent
    data class ToolStart(val toolCallId: String, val toolName: String, val argsJson: String?) : RemoteAgentEvent
    data class ToolEnd(
        val toolCallId: String,
        val toolName: String,
        val resultJson: String?,
        val isError: Boolean,
        val elapsedMs: Long?,
    ) : RemoteAgentEvent
    data class RunFinished(val contextUsage: RemoteContextUsage?, val modelLabel: String?) : RemoteAgentEvent
    data class RunFailed(val message: String) : RemoteAgentEvent
    data class UserMessageEcho(val text: String, val attachments: List<RemoteAttachment>) : RemoteAgentEvent
    data class SystemNote(val text: String) : RemoteAgentEvent
    data object Reset : RemoteAgentEvent
    data class PromptPosted(val prompt: RemotePrompt) : RemoteAgentEvent
    data class PromptResolved(val id: String) : RemoteAgentEvent
    data class ModelsUpdated(val current: String?, val models: List<RemoteModelOption>) : RemoteAgentEvent
    data class SessionsUpdated(val current: String?, val sessions: List<RemoteSessionInfo>) : RemoteAgentEvent
    data class HeldUpdated(val texts: List<String>, val runActive: Boolean) : RemoteAgentEvent
    data class Notify(val level: String, val message: String) : RemoteAgentEvent
    data class HistoryPage(val turns: List<RemoteTurn>, val before: String?, val hasMore: Boolean) : RemoteAgentEvent
}
