package io.github.mangi.eta.agent.remote.pi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * pi-remote downstream wire frames, decoded leniently.
 *
 * The server emits many frame types through one socket and adds fields over
 * time, so this is a single flat record with every field optional plus a [type]
 * discriminator — never a sealed polymorphic class. A polymorphic decode would
 * throw on any type or field this client hasn't seen, and the protocol contract
 * is that an unknown frame must be logged and dropped, never break the socket.
 * Decoding uses `ignoreUnknownKeys = true` for the same reason.
 *
 * Truth source: `D:\pi-remote\src\remote\protocol.ts` + `session-state.ts` +
 * `history.ts`. `args`/`result` are arbitrary JSON, captured as [JsonElement]
 * and stringified by the adapter.
 */
@Serializable
internal data class PiFrame(
    val type: String = "",

    // snapshot
    val turns: List<PiTurn>? = null,
    val live: PiLiveTurn? = null,
    val agentRunning: Boolean? = null,
    val taskWidget: List<String>? = null,
    val prompt: PiPrompt? = null,
    val context: PiContextUsage? = null,
    val model: String? = null,
    val held: List<String>? = null,
    val heldRunActive: Boolean? = null,

    // streaming deltas
    val delta: String? = null,
    val text: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val args: JsonElement? = null,
    val result: JsonElement? = null,
    val isError: Boolean? = null,
    val elapsedMs: Long? = null,
    val message: String? = null,
    /** agent_end / context frames carry usage under this key; snapshot uses [context]. */
    val contextUsage: PiContextUsage? = null,
    val attachments: List<PiAttachment>? = null,

    // prompt (top-level prompt frame carries these directly)
    val id: String? = null,
    val question: String? = null,
    val recommended: String? = null,
    val recommended2: String? = null,
    val actions: List<PiPromptAction>? = null,
    val dismissOnly: Boolean? = null,
    val allowSkip: Boolean? = null,

    // models
    val current: String? = null,
    val models: List<PiModel>? = null,

    // sessions
    val sessions: List<PiSession>? = null,

    // notify
    val level: String? = null,

    // held
    val texts: List<String>? = null,
    val runActive: Boolean? = null,

    // history_page
    val before: String? = null,
    val hasMore: Boolean? = null,
)

@Serializable
internal data class PiTurn(
    val role: String = "",
    val text: String? = null,
    val attachments: List<PiAttachment>? = null,
    val parts: List<PiPart>? = null,
    val error: Boolean? = null,
    val ts: Long? = null,
)

@Serializable
internal data class PiPart(
    val kind: String = "",
    val text: String? = null,
    val done: Boolean? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val args: JsonElement? = null,
    val result: JsonElement? = null,
    val isError: Boolean? = null,
    val elapsedMs: Long? = null,
)

@Serializable
internal data class PiLiveTurn(
    val parts: List<PiPart>? = null,
    val textOpen: Boolean = false,
)

@Serializable
internal data class PiAttachment(
    val id: String = "",
    val name: String = "",
    val kind: String = "",
    val mimeType: String = "",
    val size: Long = 0,
    val url: String = "",
)

@Serializable
internal data class PiContextUsage(
    val tokens: Int? = null,
    val contextWindow: Int? = null,
    val percent: Double? = null,
)

@Serializable
internal data class PiPrompt(
    val id: String = "",
    val question: String = "",
    val recommended: String? = null,
    val recommended2: String? = null,
    val actions: List<PiPromptAction>? = null,
    val dismissOnly: Boolean? = null,
    val allowSkip: Boolean = false,
)

@Serializable
internal data class PiPromptAction(
    val label: String = "",
    val value: String = "",
)

@Serializable
internal data class PiModel(
    val spec: String = "",
    val name: String = "",
)

@Serializable
internal data class PiSession(
    val path: String = "",
    val name: String? = null,
    val firstMessage: String = "",
    val modified: String = "",
    val messageCount: Int = 0,
    val unsaved: Boolean? = null,
)
