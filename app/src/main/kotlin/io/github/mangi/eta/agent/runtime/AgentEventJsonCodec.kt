package io.github.mangi.eta.agent.runtime

import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/** Runtime 事件的稳定 JSON 投影；只编码 IPC 已公开的安全字段。 */
internal object AgentEventJsonCodec {
    /** Keep every checkpoint row well below Android CursorWindow's per-row limit. */
    const val MAX_CHECKPOINT_EVENT_BYTES = 64 * 1024

    private const val CHECKPOINT_DEGRADED_KEY = "__eta_checkpoint_degraded"
    private const val CHECKPOINT_SKIPPED_KEY = "__eta_checkpoint_skipped"
    private const val MAX_CHECKPOINT_TOOL_NAMES = 8

    data class CheckpointEncoding(
        val json: String,
        val degraded: Boolean,
    )

    /** Unbounded encoding used by the archive store; checkpoint callers use the bounded API. */
    fun encode(event: AgentEvent): String = bundleToJson(
        AgentRuntimeWire.eventToBundle(event)
    ).toString()

    /**
     * Encode without truncating either JSON or UTF-8. Oversized payloads are replaced by a
     * valid metadata-only event, or by a small explicit marker when no safe projection exists.
     */
    fun encodeForCheckpoint(
        event: AgentEvent,
        completeJson: String = encode(event),
    ): CheckpointEncoding {
        val complete = completeJson
        if (complete.utf8ByteSize() <= MAX_CHECKPOINT_EVENT_BYTES) {
            return CheckpointEncoding(complete, degraded = false)
        }

        val projection = event.checkpointSizeSafeProjection()
        if (projection != null) {
            val projected = runCatching {
                JSONObject(encode(projection))
                    .put(CHECKPOINT_DEGRADED_KEY, true)
                    .toString()
            }.getOrNull()
            if (projected != null && projected.utf8ByteSize() <= MAX_CHECKPOINT_EVENT_BYTES) {
                return CheckpointEncoding(projected, degraded = true)
            }
        }

        // Never persist a partial JSON value. The missing event is observable during recovery.
        return CheckpointEncoding(
            JSONObject().put(CHECKPOINT_SKIPPED_KEY, true).toString(),
            degraded = true,
        )
    }

    fun isCheckpointDegraded(raw: String): Boolean = runCatching {
        val json = JSONObject(raw)
        json.optBoolean(CHECKPOINT_DEGRADED_KEY, false) ||
            json.optBoolean(CHECKPOINT_SKIPPED_KEY, false)
    }.getOrDefault(false)

    fun decode(raw: String): AgentEvent? = runCatching {
        AgentRuntimeWire.eventFromBundle(jsonToBundle(JSONObject(raw)))
    }.getOrNull()

    private fun String.utf8ByteSize(): Int = toByteArray(Charsets.UTF_8).size

    /** Drop only payloads that are not needed to reconstruct the visible recovery trace. */
    private fun AgentEvent.checkpointSizeSafeProjection(): AgentEvent? = when (this) {
        is AgentEvent.GuiControlChanged -> this
        is AgentEvent.DecisionFallback -> this
        is AgentEvent.RunStarted -> this
        is AgentEvent.DispatchCreated -> this
        is AgentEvent.RoundStarted -> copy(historySnapshotId = "")
        is AgentEvent.ModelRetryScheduled -> copy(reasonDetail = "")
        is AgentEvent.ErrorReconnectChanged -> copy(reasonDetail = "")
        is AgentEvent.ProviderRequestStarted -> this
        is AgentEvent.ProviderResponseStarted -> this
        is AgentEvent.AssistantBlockStart -> copy(blockId = null, name = null)
        is AgentEvent.AssistantBlockDelta -> copy(delta = "")
        is AgentEvent.AssistantBlockEnd -> copy(blockId = null, name = null, replacementContent = null)
        is AgentEvent.AssistantReceived -> copy(
            reasoningContent = "",
            toolNames = toolNames.take(MAX_CHECKPOINT_TOOL_NAMES),
        )
        is AgentEvent.ChildContextUpdated -> null
        is AgentEvent.UsageReceived -> this
        is AgentEvent.UserSupplementReceived -> copy(text = "", requestId = "", imagesJson = "[]")
        is AgentEvent.QuestionRequested -> null
        is AgentEvent.QuestionResolved -> copy(answer = null)
        is AgentEvent.ToolStarted -> copy(argsPreview = "", command = null, argumentsDetail = null)
        is AgentEvent.ToolFinished -> copy(resultSummary = "", resultDetail = null, mediaPreviewsJson = "[]")
        is AgentEvent.HostedToolStarted -> this
        is AgentEvent.HostedToolFinished -> this
        is AgentEvent.ToolImagesAttached -> this
        is AgentEvent.AutoCompactWaiting -> this
        is AgentEvent.ContextCompactionStarted -> copy(modelName = "")
        is AgentEvent.ContextCompacted -> copy(
            history = emptyList(),
            compressorLabel = "",
            reason = "",
        )
        is AgentEvent.RunFinished -> this
        is AgentEvent.RunFailed -> copy(reason = "")
    }

    @Suppress("DEPRECATION")
    private fun bundleToJson(bundle: Bundle): JSONObject =
        JSONObject().also { json ->
            bundle.keySet().forEach { key ->
                when (val value = bundle.get(key)) {
                    is String -> json.put(key, value)
                    is Boolean -> json.put(key, value)
                    is Int -> json.put(key, value)
                    is Long -> json.put(key, value)
                    is ArrayList<*> -> json.put(key, JSONArray(value))
                    null -> json.put(key, JSONObject.NULL)
                }
            }
        }

    private fun jsonToBundle(json: JSONObject): Bundle =
        Bundle().also { bundle ->
            json.keys().forEach { key ->
                when (val value = json.opt(key)) {
                    is String -> bundle.putString(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is JSONArray -> bundle.putStringArrayList(
                        key,
                        ArrayList((0 until value.length()).map { index -> value.optString(index) }),
                    )
                }
            }
        }
}
