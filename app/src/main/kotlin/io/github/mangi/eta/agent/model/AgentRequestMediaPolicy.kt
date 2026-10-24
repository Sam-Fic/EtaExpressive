package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentRequestMediaPolicy {
    private val MEDIA_KEYS = setOf("image", "image_file", "image_url", "input_image", "video", "video_file", "video_url", "input_video")
    private val IMAGE_TYPES = setOf("image_url", "input_image", "image", "image_file")
    private val VIDEO_TYPES = setOf("video_url", "input_video", "video", "video_file")
    private val PERSISTED_TYPES = setOf("image_file", "video_file")
    private const val OMITTED_TEXT =
        "[Media omitted: this model is not configured to accept it. Do not claim to have seen it. Choose a vision-capable model to inspect images.]"

    /**
     * 发往模型前的媒体裁剪。
     *
     * 原实现无条件 `JSONArray(messages.toString())` 深拷贝整段历史，长会话上每轮就是数百 KB
     * 的序列化 + 解析，只为在副本上删掉 [AgentTurnIdentity.JSON_KEY]。真正的裁剪需求只有两种：
     * 含持久化 image_file/video_file 需要水合、或含当前模型不支持的媒体需要剔除。
     *
     * 因此改为逐条按需复制：
     * - 无媒体、能力齐全的消息复制一层浅壳（去掉 turn 标记即可），不序列化整段历史；
     * - 含 `content` 数组的消息才真正重建 content；
     * - 不需要任何改动的消息直接共享原对象（只读消费，各 provider 都会自行重建请求体）。
     *
     * 返回值仍然保证：不含 `eta_turn_id`，且对不含需处理媒体的历史逐条与原文等价。
     */
    fun filter(messages: JSONArray, supportsVision: Boolean, supportsVideo: Boolean): JSONArray =
        JSONArray().also { output ->
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                output.put(filterMessage(message, supportsVision, supportsVideo))
            }
        }

    private fun filterMessage(message: JSONObject, supportsVision: Boolean, supportsVideo: Boolean): JSONObject {
        val content = message.optJSONArray("content")
        val needsWork = content != null && containsMedia(content) &&
            (needsHydration(content) || !supportsAllMedia(content, supportsVision, supportsVideo))
        if (!needsWork) {
            // 无媒体或能力齐全：只去掉 turn 标记，其余字段共享引用。
            return withoutTurnIdentity(message)
        }
        val rebuilt = JSONObject().also { target ->
            for (key in message.keys()) {
                if (key == AgentTurnIdentity.JSON_KEY || key == "content") continue
                target.put(key, message.opt(key))
            }
        }
        val source = if (needsHydration(content!!)) {
            val hydrated = io.github.mangi.eta.agent.media.AgentHistoryImageHydrator.hydrate(
                AgentModelClient.ConversationMessage(
                    role = message.optString("role"),
                    content = "",
                    contentJson = content.toString(),
                ),
                supportsVision,
                supportsVideo,
            )
            JSONArray(hydrated.contentJson)
        } else content
        val allowed = JSONArray()
        var omitted = false
        for (index in 0 until source.length()) {
            val part = source.optJSONObject(index)
            val keep = when (part?.optString("type")) {
                in IMAGE_TYPES -> supportsVision
                in VIDEO_TYPES -> supportsVideo
                else -> true
            }
            if (keep) allowed.put(source.get(index)) else omitted = true
        }
        if (omitted) allowed.put(JSONObject().put("type", "text").put("text", OMITTED_TEXT))
        rebuilt.put("content", allowed)
        return rebuilt
    }

    /** 不含媒体时也只需要一个去掉 turn 标记的浅壳；没有标记就直接共享。 */
    private fun withoutTurnIdentity(message: JSONObject): JSONObject {
        if (!message.has(AgentTurnIdentity.JSON_KEY)) return message
        return JSONObject().also { target ->
            for (key in message.keys()) {
                if (key == AgentTurnIdentity.JSON_KEY) continue
                target.put(key, message.opt(key))
            }
        }
    }

    private fun containsMedia(content: JSONArray): Boolean {
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            if (part.optString("type") in MEDIA_KEYS) return true
        }
        return false
    }

    private fun needsHydration(content: JSONArray): Boolean {
        for (index in 0 until content.length()) {
            val part = content.optJSONObject(index) ?: continue
            if (part.optString("type") in PERSISTED_TYPES) return true
        }
        return false
    }

    private fun supportsAllMedia(content: JSONArray, supportsVision: Boolean, supportsVideo: Boolean): Boolean {
        if (supportsVision && supportsVideo) return true
        for (index in 0 until content.length()) {
            val type = content.optJSONObject(index)?.optString("type") ?: continue
            when (type) {
                in IMAGE_TYPES -> if (!supportsVision) return false
                in VIDEO_TYPES -> if (!supportsVideo) return false
            }
        }
        return true
    }
}
