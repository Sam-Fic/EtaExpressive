package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.AGENT_MODEL_IMAGE_MAX_LONG_EDGE
import io.github.mangi.eta.agent.media.AGENT_MODEL_IMAGE_MAX_PIXELS
import io.github.mangi.eta.agent.media.modelImageTargetSize
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object AgentContextBudget {
    private const val RESERVED_TOKENS = 8_000
    private const val TOKENS_PER_MESSAGE = 3
    private const val IMAGE_GRID = 32
    // 图片 token 估算与送模型图片共用同一套上限（长边 1920 + 像素 2.07MP），避免两套口径漂移。
    private const val IMAGE_MAX_EDGE = AGENT_MODEL_IMAGE_MAX_LONG_EDGE
    private const val IMAGE_MAX_PIXELS = AGENT_MODEL_IMAGE_MAX_PIXELS
    private const val IMAGE_MIN_TOKENS = 85
    // 只有字节数、没有宽高时的兜底换算（与历史 image_url / 持久化文件共用）。
    private const val IMAGE_BYTES_PER_TOKEN = 4_096L
    private const val VIDEO_MIN_TOKENS = 1_200
    private const val VIDEO_BYTES_PER_TOKEN = 1_024L
    // 对齐 Operit：中文约 1.5 token/字，拉丁约 4 字符/token。
    // 原先统一 codepoint/3，中文会少算 4～6 倍，界面用量就会远低于接口账单。
    private const val CJK_TOKENS_PER_CHAR = 1.5
    private const val LATIN_TOKENS_PER_CHAR = 0.25
    private val DATA_URL_REGEX =
        Regex("""data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=\s]+""", RegexOption.IGNORE_CASE)

    fun countTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val extra = if (text[index].isHighSurrogate() && index + 1 < text.length) 2 else 1
            val codePoint = if (extra == 2) {
                Character.toCodePoint(text[index], text[index + 1])
            } else {
                text[index].code
            }
            if (isCjkCodePoint(codePoint)) cjk++ else other++
            index += extra
        }
        return max(1, (cjk * CJK_TOKENS_PER_CHAR + other * LATIN_TOKENS_PER_CHAR).roundToInt())
    }

    private fun isCjkCodePoint(codePoint: Int): Boolean = when (codePoint) {
        in 0x3400..0x4DBF -> true
        in 0x4E00..0x9FFF -> true
        in 0xF900..0xFAFF -> true
        in 0x3040..0x30FF -> true
        in 0xAC00..0xD7AF -> true
        in 0x3000..0x303F -> true
        in 0xFF00..0xFFEF -> true
        in 0x20000..0x2A6DF -> true
        else -> false
    }

    fun countStoredImages(count: Int): Int = count.coerceAtLeast(0) * IMAGE_MIN_TOKENS

    /** 只有字节量、没有宽高时的图片兜底（保守下限：实际视觉 token 通常高于该值）。 */
    fun countImageBytesTokens(bytes: Long): Int =
        max(IMAGE_MIN_TOKENS.toLong(), bytes / IMAGE_BYTES_PER_TOKEN).toInt()

    /** 只有字节量、没有宽高时的视频兜底。 */
    fun countVideoBytesTokens(bytes: Long): Int =
        max(VIDEO_MIN_TOKENS.toLong(), bytes / VIDEO_BYTES_PER_TOKEN).toInt()

    fun countImageTokens(image: AgentModelClient.ModelImage): Int {
        if (image.mimeType.startsWith("video/", ignoreCase = true) ||
            image.reference.startsWith("data:video/", ignoreCase = true)
        ) {
            return countVideoBytesTokens(image.bytes.toLong())
        }
        val w = image.width
        val h = image.height
        return if (w != null && h != null && w > 0 && h > 0) {
            countImageTokens(w, h)
        } else {
            countImageBytesTokens(image.bytes.toLong())
        }
    }

    /**
     * 图片 token 的唯一公式：先把原图尺寸夹到送模型档位（[modelImageTargetSize]，
     * 与编码器共用同一处实现），再按 32px 网格折算 `ceil(w/32) * ceil(h/32)`。
     *
     * 传入的宽高应当是**原图**尺寸而非聊天预览尺寸，否则会低估 1920 档的视觉 token。
     */
    fun countImageTokens(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return IMAGE_MIN_TOKENS
        val (targetW, targetH) =
            modelImageTargetSize(width, height, IMAGE_MAX_EDGE, IMAGE_MAX_PIXELS)
        val tokens = (
            ceil(targetW.toDouble() / IMAGE_GRID) * ceil(targetH.toDouble() / IMAGE_GRID)
            ).toInt()
        return max(IMAGE_MIN_TOKENS, tokens)
    }

    fun estimate(messages: org.json.JSONArray): Int {
        var tokens = 0
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            tokens += countMessage(AgentConversationCodec.fromJsonObject(message))
        }
        return tokens
    }

    fun countMessage(message: AgentModelClient.ConversationMessage): Int {
        var tokens = TOKENS_PER_MESSAGE
        // contentJson 才是发出去的 content；有它时不要再加一份纯文本。
        tokens += countPayload(message)
        if (message.reasoningContent.isNotBlank()) {
            tokens += countTokens(message.reasoningContent)
        }
        if (message.toolCallId.isNotBlank()) {
            tokens += countTokens(message.toolCallId)
        }
        if (message.toolCallsJson.isNotBlank()) {
            tokens += countTokens(message.toolCallsJson)
        }
        return tokens
    }

    private fun countPayload(message: AgentModelClient.ConversationMessage): Int {
        if (message.contentJson.isNotBlank()) {
            return countStructuredContent(message.contentJson)
        }
        return countTokens(stripInlineDataUrls(message.content))
    }

    /**
     * 多模态 content 里的图片按视觉 token 计，不能把 data URL / 路径当正文去估。
     * 工具回图进下一轮 prompt 时，base64 按拉丁 4 字符/token 会一下子多出几万。
     */
    private fun countStructuredContent(raw: String): Int {
        val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull()
        when (parsed) {
            is JSONArray -> {
                var tokens = 0
                for (index in 0 until parsed.length()) {
                    val item = parsed.optJSONObject(index)
                    tokens += if (item != null) {
                        countContentPart(item)
                    } else {
                        countTokens(stripInlineDataUrls(parsed.optString(index)))
                    }
                }
                return tokens
            }
            is JSONObject -> return countContentPart(parsed)
            is String -> return countTokens(stripInlineDataUrls(parsed))
            else -> return countTokens(stripInlineDataUrls(raw))
        }
    }

    private fun countContentPart(part: JSONObject): Int {
        val type = part.optString("type")
        return when (type) {
            "text" -> countTokens(part.optString("text"))
            "image_url", "image_file", "image", "input_image" -> countContentImage(part)
            "video_url", "video_file", "input_video" -> countContentVideo(part)
            else -> {
                val text = part.optString("text")
                if (text.isNotBlank()) countTokens(text) else 0
            }
        }
    }

    private fun countContentVideo(part: JSONObject): Int {
        val url = part.optJSONObject("video_url")?.optString("url").orEmpty()
            .ifBlank { part.optString("url") }
            .ifBlank { part.optString("path") }
        if (url.startsWith("data:video/", ignoreCase = true)) {
            val marker = "base64,"
            val index = url.indexOf(marker, ignoreCase = true)
            if (index >= 0) {
                val encoded = url.substring(index + marker.length).filterNot { it.isWhitespace() }
                val bytes = (encoded.length * 3) / 4
                return countVideoBytesTokens(bytes.toLong())
            }
        }
        return VIDEO_MIN_TOKENS
    }

    private fun countContentImage(part: JSONObject): Int {
        val url = part.optJSONObject("image_url")?.optString("url").orEmpty()
            .ifBlank { part.optString("url") }
            .ifBlank { part.optString("path") }
        val width = part.optInt("width").takeIf { it > 0 }
            ?: part.optJSONObject("image_url")?.optInt("width")?.takeIf { it > 0 }
        val height = part.optInt("height").takeIf { it > 0 }
            ?: part.optJSONObject("image_url")?.optInt("height")?.takeIf { it > 0 }
        if (width != null && height != null) {
            return countImageTokens(width, height)
        }
        val bytes = dataUrlDecodedBytes(url)
        return if (bytes != null) countImageBytesTokens(bytes.toLong()) else IMAGE_MIN_TOKENS
    }

    private fun dataUrlDecodedBytes(url: String): Int? {
        val marker = "base64,"
        val index = url.indexOf(marker, ignoreCase = true)
        if (index < 0) return null
        val encoded = url.substring(index + marker.length).filterNot { it.isWhitespace() }
        if (encoded.isEmpty()) return null
        return (encoded.length * 3) / 4
    }

    private fun stripInlineDataUrls(text: String): String {
        if (!text.contains("data:image", ignoreCase = true)) return text
        return DATA_URL_REGEX.replace(text, "[image]")
    }

    fun countCurrentTurn(prompt: String, images: List<AgentModelClient.ModelImage>): Int {
        var tokens = TOKENS_PER_MESSAGE
        tokens += countTokens(prompt)
        images.forEach { tokens += countImageTokens(it) }
        return tokens
    }

    fun historyBudget(contextWindow: Int, prompt: String, images: List<AgentModelClient.ModelImage>): Int {
        val currentTurn = countCurrentTurn(prompt, images)
        return (contextWindow - RESERVED_TOKENS - currentTurn).coerceAtLeast(0)
    }

    fun trimHistory(
        history: List<AgentModelClient.ConversationMessage>,
        budgetTokens: Int,
    ): List<AgentModelClient.ConversationMessage> {
        if (history.isEmpty()) return history
        if (budgetTokens <= 0) return emptyList()
        var remaining = budgetTokens
        val result = ArrayDeque<AgentModelClient.ConversationMessage>()
        for (index in history.size - 1 downTo 0) {
            val message = history[index]
            val tokens = countMessage(message)
            if (tokens > remaining && result.isNotEmpty()) {
                break
            }
            remaining -= tokens
            result.addFirst(message)
        }
        return result.toList()
    }
}
