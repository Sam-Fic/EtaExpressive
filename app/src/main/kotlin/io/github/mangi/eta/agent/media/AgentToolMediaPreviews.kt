package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentAppContext
import org.json.JSONArray

/**
 * 工具读回/生成的图片是瞬时观察，下一次推理后原始字节就会被丢弃；
 * 工具卡片要展示的是一份独立的预览，因此单独编码成 1024px 有损 WebP data URL，
 * 随工具完成事件进入会话记录。与聊天附件预览同一档位，不参与模型请求。
 */
internal object AgentToolMediaPreviews {
    const val EMPTY = "[]"

    /** 一张卡片最多展示三张，避免多图工具把事件与数据库撑大。 */
    const val MAX_PREVIEWS = 3

    /**
     * 单张预览的字符上限。
     *
     * 按 1024 长边估算：9:20 竖屏 460×1024 约 47 万像素、16:9 约 59 万像素，
     * WebP q90 截图典型 30~80 KB，base64 后约 40~110k 字符；方形 1024² 极端情况
     * （接近照片）约 200 KB → 约 270k 字符。取 400k 留出约一倍余量，
     * 同时兜住“WebP 编码器缺失退回 JPEG q88”时的更大体积。
     */
    private const val MAX_PREVIEW_CHARS = 400_000

    /**
     * 一条工具消息的预览总字符上限。
     *
     * ToolFinished 事件通过 Messenger 的 Bundle 投递（Binder 单次事务约 1 MB），
     * 三张预览再加上其它字段必须留余量，因此总量封顶 640k 字符（≈625 KB），
     * 与旧档（3 × 220k）基本持平：单张更清晰，但整条消息的体积不会被放大数倍。
     */
    private const val MAX_TOTAL_PREVIEW_CHARS = 640_000

    fun encode(images: List<AgentModelClient.ModelImage>): String {
        if (images.isEmpty()) return EMPTY
        val context = AgentAppContext.resolve() ?: return EMPTY
        val previews = images.take(MAX_PREVIEWS).mapNotNull { image ->
            runCatching { AgentImageCodec.previewFromReference(context, image) }.getOrNull()?.reference
        }
        return encodePreviews(previews)
    }

    /** UI 侧回写会话记录时只处理已经是预览 data URL 的字符串。 */
    fun encodePreviews(previews: List<String>): String {
        val accepted = mutableListOf<String>()
        var totalChars = 0
        for (raw in previews) {
            val source = raw.trim()
            if (!source.startsWith("data:image/", ignoreCase = true)) continue
            if (source.length > MAX_PREVIEW_CHARS) continue
            if (totalChars + source.length > MAX_TOTAL_PREVIEW_CHARS) continue
            accepted += source
            totalChars += source.length
            if (accepted.size == MAX_PREVIEWS) break
        }
        if (accepted.isEmpty()) return EMPTY
        val array = JSONArray()
        accepted.forEach(array::put)
        return array.toString()
    }

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == EMPTY) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val source = array.optString(index).trim()
                if (source.startsWith("data:image/", ignoreCase = true)) add(source)
                if (size >= MAX_PREVIEWS) break
            }
        }
    }
}
