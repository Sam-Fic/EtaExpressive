package io.github.mangi.eta.agent.model

import okio.BufferedSource
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Sniff only the beginning of this response, without consuming or waiting for a whole frame. */
internal object AgentResponseFormat {
    const val MAX_PREFIX_BYTES = 4096
    enum class Kind { JSON, SSE, UNKNOWN }
    data class Inspection(val kind: Kind, val preambleBytes: Long)
    private val sseFields = setOf("data", "event", "id", "retry")

    /** Parse the whole JSON response before any provider content callbacks run. */
    fun parseJsonObject(payload: String): JSONObject {
        fun invalid(): Nothing = throw AgentModelFailure(
            code = "INVALID_JSON_RESPONSE",
            retryable = false,
            message = "模型接口未返回单个完整 JSON 对象",
        )
        // JVM JSONTokener can treat a raw NUL as EOF. An escaped \\u0000 is valid
        // string data, but a raw NUL anywhere in the response must be rejected.
        if ('\u0000' in payload) invalid()
        return try {
            val tokener = JSONTokener(payload)
            val json = JSONObject(tokener)
            // nextClean() also skips comments on Android, so inspect raw characters.
            while (tokener.more()) {
                when (tokener.next()) {
                    ' ', '\t', '\r', '\n' -> Unit
                    else -> invalid()
                }
            }
            json
        } catch (_: JSONException) {
            // Parser exceptions may include the response body; do not retain them.
            invalid()
        }
    }

    fun inspect(source: BufferedSource, contentType: String?): Inspection {
        val peek = source.peek()
        var position = 0L
        fun byteAt(index: Long): Int? {
            if (index >= MAX_PREFIX_BYTES || !peek.request(index + 1)) return null
            return peek.buffer[index].toInt() and 0xff
        }
        // The UTF-8 BOM may itself straddle network chunks.
        if (byteAt(0) == 0xef && byteAt(1) == 0xbb && byteAt(2) == 0xbf) position = 3
        while (byteAt(position) in listOf(0x20, 0x09, 0x0a, 0x0d)) position++
        val first = byteAt(position)
        if (first == '{'.code || first == '['.code) return Inspection(Kind.JSON, position)
        if (first == ':'.code) return Inspection(Kind.SSE, position) // heartbeat/comment
        if (first != null) {
            // Exact field at the start, never a substring search in an error page or JSON string.
            val field = StringBuilder()
            var index = position
            while (index < MAX_PREFIX_BYTES && field.length <= 5) {
                val next = byteAt(index)
                if (next == ':'.code || next == 0x0a || next == 0x0d || next == null) {
                    return Inspection(
                        if (field.toString() in sseFields) Kind.SSE else Kind.UNKNOWN,
                        position,
                    )
                }
                field.append(next.toChar())
                if (sseFields.none { it.startsWith(field.toString()) }) return Inspection(Kind.UNKNOWN, position)
                index++
            }
            return Inspection(Kind.UNKNOWN, position)
        }
        // No decisive body evidence within the bound (or an empty response): use the header.
        val mediaType = contentType.orEmpty().substringBefore(';').trim()
        val kind = when {
            mediaType.equals("text/event-stream", true) -> Kind.SSE
            mediaType.equals("application/json", true) || mediaType.endsWith("+json", true) -> Kind.JSON
            else -> Kind.UNKNOWN
        }
        return Inspection(kind, position)
    }
}
