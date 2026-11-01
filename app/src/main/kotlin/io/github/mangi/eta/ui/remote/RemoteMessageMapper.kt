package io.github.mangi.eta.ui.remote

import io.github.mangi.eta.agent.remote.RemoteAttachment
import io.github.mangi.eta.agent.remote.RemoteLiveTurn
import io.github.mangi.eta.agent.remote.RemotePart
import io.github.mangi.eta.agent.remote.RemoteTurn
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.RemoteNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure projection from the protocol-agnostic remote model into Eta's chat UI model.
 *
 * Two entry points mirror pi's snapshot shape: [mapTurns] renders committed history
 * (oldest→newest) and [mapLive] renders the in-flight assistant turn. The state holder
 * seeds its live list from [mapLive] on a snapshot, then folds streaming deltas into
 * that list in place; committed turns are only ever produced here.
 *
 * Id rules (all `rmsg-` prefixed to stay clear of `isSteerSupplement()`'s `-supplement-`
 * match):
 * - user/system/error/assistant-segment ids embed `ts` + turn index + part index so a
 *   rebuilt snapshot after the 20-turn window slides still keys stably in the LazyColumn;
 * - tool ids embed the server's `toolCallId` so a `tool_end` delta replaces its
 *   `tool_start` card in place across a reconnect.
 */
internal object RemoteMessageMapper {

    private const val SUMMARY_MAX = 120
    private const val ARG_DETAIL_MAX = 4_000
    private const val RESULT_DETAIL_MAX = 8_000

    fun mapTurns(turns: List<RemoteTurn>): List<AgentChatMessageUi> {
        val out = ArrayList<AgentChatMessageUi>(turns.size)
        turns.forEachIndexed { index, turn ->
            val ts = turn.ts ?: 0L
            when (turn.role) {
                "user" -> out += UserMessageUi(
                    id = "rmsg-u-$ts-$index",
                    content = userContent(turn.text.orEmpty(), turn.attachments),
                    images = emptyList(),
                )
                "system" -> out += RemoteNoticeMessageUi(
                    id = "rmsg-s-$ts-$index",
                    text = turn.text.orEmpty(),
                    isError = false,
                )
                "assistant" -> {
                    if (turn.error) {
                        out += RemoteNoticeMessageUi(
                            id = "rmsg-e-$ts-$index",
                            text = turn.text ?: "远程运行出错",
                            isError = true,
                        )
                    } else {
                        turn.parts.forEachIndexed { partIndex, part ->
                            mapPart(part, ts, index, partIndex, streaming = false)?.let { out += it }
                        }
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    fun mapLive(live: RemoteLiveTurn?, running: Boolean): List<AgentChatMessageUi> {
        if (live == null) return emptyList()
        val out = ArrayList<AgentChatMessageUi>(live.parts.size)
        val lastIndex = live.parts.lastIndex
        live.parts.forEachIndexed { partIndex, part ->
            // Only the trailing text segment keeps accumulating while textOpen; a
            // trailing thinking part streams until its own done flag.
            val isTrailing = partIndex == lastIndex
            val streaming = running && isTrailing &&
                ((part is RemotePart.Text && live.textOpen) || (part is RemotePart.Thinking && !part.done))
            mapPart(part, ts = 0L, turnIndex = -1, partIndex = partIndex, streaming = streaming, live = true)
                ?.let { out += it }
        }
        return out
    }

    private fun mapPart(
        part: RemotePart,
        ts: Long,
        turnIndex: Int,
        partIndex: Int,
        streaming: Boolean,
        live: Boolean = false,
    ): AgentChatMessageUi? = when (part) {
        is RemotePart.Text -> AgentMessageUi(
            id = if (live) "rmsg-la-$partIndex" else "rmsg-a-$ts-$turnIndex-$partIndex",
            content = part.text,
            isStreaming = streaming,
        )
        is RemotePart.Thinking -> ThinkingMessageUi(
            id = if (live) "rmsg-lth-$partIndex" else "rmsg-th-$ts-$turnIndex-$partIndex",
            content = part.text,
            isStreaming = streaming || !part.done,
        )
        is RemotePart.Tool -> ToolActivityMessageUi(
            id = "rmsg-t-${part.toolCallId.ifBlank { "$ts-$turnIndex-$partIndex" }}",
            toolName = part.toolName,
            status = when {
                !part.done -> ToolActivityStatusUi.Running
                part.isError -> ToolActivityStatusUi.Failed
                else -> ToolActivityStatusUi.Success
            },
            argumentsSummary = summary(part.argsJson),
            command = null,
            resultSummary = if (part.done) summary(part.resultJson) else null,
            imageCount = 0,
            argumentsDetail = pretty(part.argsJson)?.let { truncate(it, ARG_DETAIL_MAX) },
            resultDetail = if (part.done) pretty(part.resultJson)?.let { truncate(it, RESULT_DETAIL_MAX) } else null,
            mediaPreviews = emptyList(),
            detailsWithheld = false,
        )
    }

    /**
     * A single committed user turn with a caller-supplied id — used for the live
     * `user_message` echo, which arrives after the optimistic pending bubble and needs
     * a stable, collision-free id of its own.
     */
    fun mapUserTurn(id: String, text: String, attachments: List<RemoteAttachment>): UserMessageUi =
        UserMessageUi(id = id, content = userContent(text, attachments), images = emptyList())

    /**
     * v1 attachment affordance: committed user turns list attachment names inline so
     * nothing is silently dropped. Stage 2 upgrades image attachments to inline
     * thumbnails (async fetch → [UserMessageUi.images]) and drops them from this marker.
     */
    fun userContent(text: String, attachments: List<RemoteAttachment>): String {
        if (attachments.isEmpty()) return text
        val names = attachments.joinToString("、") { it.name.ifBlank { "附件" } }
        return if (text.isBlank()) "📎 $names" else "$text\n\n📎 $names"
    }

    private fun summary(raw: String?): String {
        val oneLine = raw?.replace(WHITESPACE, " ")?.trim().orEmpty()
        return if (oneLine.length <= SUMMARY_MAX) oneLine else oneLine.take(SUMMARY_MAX) + "…"
    }

    private fun pretty(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return runCatching { JSONObject(trimmed).toString(2) }
            .recoverCatching { JSONArray(trimmed).toString(2) }
            .getOrDefault(trimmed)
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "\n…（已截断，共 ${text.length} 字符）"

    private val WHITESPACE = Regex("\\s+")
}
