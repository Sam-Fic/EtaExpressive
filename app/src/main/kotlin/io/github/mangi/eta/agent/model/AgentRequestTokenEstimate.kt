package io.github.mangi.eta.agent.model

import java.io.File
import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.media.MAX_AGENT_VIDEO_BYTES
import org.json.JSONArray
import org.json.JSONObject

/** Request-only input estimate. Does not hydrate attachments or serialize the conversation. */
internal object AgentRequestTokenEstimate {
    private const val OMITTED = "[Media omitted: this model is not configured to accept it. Do not claim to have seen it. Choose a vision-capable model to inspect images.]"

    fun tools(definitions: JSONArray): Int = if (definitions.length() == 0) 0 else AgentContextBudget.countTokens(definitions.toString())

    /** Use the very same filtered array subsequently passed to ProviderRequest. */
    fun filtered(messages: JSONArray, definitions: JSONArray): Int =
        (history(messages, true, true, alreadyFiltered = true).toLong() + tools(definitions))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Cheap boundary estimate: no filter(), JSON round-trip, image decoding or attachment-content reads. */
    fun boundary(
        messages: JSONArray,
        definitions: JSONArray,
        vision: Boolean,
        video: Boolean,
        toolTokens: Int? = null,
    ): Int =
        (history(messages, vision, video, alreadyFiltered = false).toLong() + (toolTokens ?: tools(definitions)))
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Local UI preview only. Cloud calibration continues using the original raw DTO counts. */
    fun history(messages: List<AgentModelClient.ConversationMessage>, vision: Boolean, video: Boolean,
        endpoint: EndpointKind = EndpointKind.CHAT_COMPLETIONS): Int {
        var total = 0L
        for (message in messages) {
            val json = AgentConversationCodec.toJsonObject(message)
            // Preview only. The silent boundary/history calibration below stays unchanged.
            if (!AgentWireRequestEstimate.sendsPlaintextReasoning(endpoint)) json.remove("reasoning_content")
            total += countMessage(json, vision, video, alreadyFiltered = false)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /** Fixed overhead must not be derived by subtracting RAW UI history from FILTERED request tokens. */
    fun fixed(
        messages: JSONArray,
        systemCount: Int,
        definitions: JSONArray,
        toolTokens: Int? = null,
    ): Int {
        var total = (toolTokens ?: tools(definitions)).toLong()
        for (i in 0 until systemCount.coerceIn(0, messages.length())) {
            val message = messages.optJSONObject(i) ?: continue
            total += AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(message))
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun history(messages: JSONArray, vision: Boolean, video: Boolean, alreadyFiltered: Boolean): Int {
        var total = 0L
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            total += countMessage(message, vision, video, alreadyFiltered)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun countMessage(message: JSONObject, vision: Boolean, video: Boolean, alreadyFiltered: Boolean): Int {
        var tokens = 3L
        val content = message.opt("content")
        tokens += when (content) {
            is JSONArray -> parts(content, vision, video, alreadyFiltered)
            is JSONObject -> part(content)
            is String -> AgentContextBudget.countTokens(content)
            else -> 0
        }
        for (key in listOf("reasoning_content", "tool_call_id")) {
            if (!message.isNull(key)) message.optString(key).takeIf(String::isNotBlank)?.let {
                tokens += AgentContextBudget.countTokens(it)
            }
        }
        val calls = message.opt("tool_calls")
        if (calls != null && calls !== JSONObject.NULL && !(calls is JSONArray && calls.length() == 0)) {
            tokens += AgentContextBudget.countTokens(calls.toString())
        }
        return tokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun parts(content: JSONArray, vision: Boolean, video: Boolean, alreadyFiltered: Boolean): Int {
        val persisted = !alreadyFiltered && (0 until content.length()).any {
            content.optJSONObject(it)?.optString("type") == "image_file" ||
                content.optJSONObject(it)?.optString("type") == "video_file"
        }
        var tokens = 0L
        var omitted = false
        val listings = mutableListOf<String>()
        var firstText: String? = null
        var hasKeptPart = false
        for (i in 0 until content.length()) {
            val p = content.optJSONObject(i)
            if (p == null) {
                // Hydration drops non-object entries; the plain media filter retains them.
                if (!persisted) {
                    tokens += AgentContextBudget.countTokens(content.optString(i))
                    hasKeptPart = true
                }
                continue
            }
            val type = p.optString("type")
            when {
                persisted && type == "image_file" -> {
                    val path = p.optString("path")
                    // Hydration lists the path for every model, vision or not.
                    listings += "[用户图片] $path"
                    if (vision) {
                        val size = fileLength(path)
                        if (size in 1..MAX_AGENT_IMAGE_BYTES.toLong()) {
                            // 新持久化的附件带有原图宽高，按统一视觉公式计；
                            // 旧数据没有宽高时只能按字节兜底（保守下限，仍会偏低）。
                            val width = p.optInt("width").takeIf { it > 0 }
                            val height = p.optInt("height").takeIf { it > 0 }
                            tokens += if (width != null && height != null) {
                                AgentContextBudget.countImageTokens(width, height)
                            } else {
                                AgentContextBudget.countImageBytesTokens(size)
                            }
                            hasKeptPart = true
                        }
                    }
                }
                persisted && type == "video_file" -> {
                    val path = p.optString("path")
                    if (!video) listings += "[用户视频] $path"
                    val size = fileLength(path)
                    if (video && size in 1..MAX_AGENT_VIDEO_BYTES.toLong()) {
                        tokens += AgentContextBudget.countVideoBytesTokens(size)
                        hasKeptPart = true
                    } else if (!video && vision && size > 0) {
                        // Preview extraction and duration are deliberately not decoded for budgeting.
                        tokens += AgentContextBudget.countStoredImages(1)
                        hasKeptPart = true
                    }
                }
                !alreadyFiltered && type in setOf("image_url", "input_image", "image") && !vision -> {
                    if (!persisted || type != "image_url") omitted = true
                }
                !alreadyFiltered && type in setOf("video_url", "input_video", "video") && !video -> {
                    if (!persisted || type != "video_url") omitted = true
                }
                else -> {
                    tokens += part(p)
                    if (!hasKeptPart && type == "text") firstText = p.optString("text")
                    hasKeptPart = true
                }
            }
        }
        if (listings.isNotEmpty()) {
            val listing = listings.joinToString("\n")
            tokens += if (firstText == null) AgentContextBudget.countTokens(listing)
                else AgentContextBudget.countTokens(if (firstText.isBlank()) listing else "$firstText\n\n$listing") -
                    AgentContextBudget.countTokens(firstText)
        }
        if (omitted) tokens += AgentContextBudget.countTokens(OMITTED)
        return tokens.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun fileLength(path: String): Long = if (path.isBlank()) 0 else File(path).let {
        if (it.isFile) it.length() else 0
    }

    private fun part(p: JSONObject): Int = when (p.optString("type")) {
        "text" -> AgentContextBudget.countTokens(p.optString("text"))
        "image_url", "image", "input_image", "image_file" -> {
            val width = p.optInt("width").takeIf { it > 0 }
                ?: p.optJSONObject("image_url")?.optInt("width")?.takeIf { it > 0 }
            val height = p.optInt("height").takeIf { it > 0 }
                ?: p.optJSONObject("image_url")?.optInt("height")?.takeIf { it > 0 }
            if (width != null && height != null) AgentContextBudget.countImageTokens(width, height)
            else AgentContextBudget.countImageBytesTokens(
                encodedBytes(p.optJSONObject("image_url")?.optString("url").orEmpty()
                    .ifBlank { p.optString("url") }).toLong()
            )
        }
        "video_url", "video", "input_video", "video_file" ->
            AgentContextBudget.countVideoBytesTokens(
                encodedBytes(p.optJSONObject("video_url")?.optString("url").orEmpty()
                    .ifBlank { p.optString("url") }).toLong()
            )
        else -> AgentContextBudget.countTokens(p.optString("text"))
    }

    private fun encodedBytes(url: String): Int {
        val marker = url.indexOf("base64,", ignoreCase = true)
        if (marker < 0) return 0
        var size = 0L
        for (i in marker + 7 until url.length) if (!url[i].isWhitespace()) size++
        return (size * 3 / 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
