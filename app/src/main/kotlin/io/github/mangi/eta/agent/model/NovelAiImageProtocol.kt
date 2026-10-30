package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.media.MAX_AGENT_IMAGE_BYTES
import io.github.mangi.eta.agent.media.hasSupportedImageMagic
import io.github.mangi.eta.agent.media.sniffAgentImageMimeType
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipInputStream

/** Native text-to-image wire format. Model-specific native parameters are explicit configuration. */
internal object NovelAiImageProtocol {
    fun request(prepared: ImageRequestParameters.Prepared): JSONObject {
        val options = prepared.options
        if (options.quality != null || options.responseFormat != null)
            AgentImageGenerationOptions.invalid("NovelAI 原生协议不接受 OpenAI quality/response_format")
        if (prepared.body.keys().asSequence().any { it !in setOf("model", "prompt", "size", "n") })
            AgentImageGenerationOptions.invalid("NovelAI 专用参数请放在 eta_image_config.native_parameters，不能混用 Images 请求字段")
        val p = JSONObject(prepared.nativeParameters.toString())
        fun intValue(key: String, min: Int, max: Int): Int {
            val number = p.opt(key) as? Number ?: AgentImageGenerationOptions.invalid("NovelAI native_parameters.$key 需要整数")
            val v = number.toDouble()
            if (!v.isFinite() || v % 1 != 0.0 || v < min || v > max)
                AgentImageGenerationOptions.invalid("NovelAI native_parameters.$key 超出允许范围")
            return v.toInt()
        }
        intValue("params_version", 1, 100)
        intValue("steps", 1, 50)
        val scale = p.opt("scale") as? Number
        if (scale == null || !scale.toDouble().isFinite() || scale.toDouble() !in 0.0..10.0)
            AgentImageGenerationOptions.invalid("NovelAI native_parameters.scale 应为 0–10")
        val sampler = p.opt("sampler") as? String
        if (sampler.isNullOrBlank()) AgentImageGenerationOptions.invalid("请明确配置 NovelAI sampler")
        if (options.size == "auto") AgentImageGenerationOptions.invalid("NovelAI 原生协议需要明确宽高，不能使用 auto")
        if (options.size != null) {
            val (w, h) = AgentImageGenerationOptions.dimensions(options.size)
            p.put("width", w).put("height", h)
        }
        val width = intValue("width", 64, 2048); val height = intValue("height", 64, 2048)
        if (width % 64 != 0 || height % 64 != 0)
            AgentImageGenerationOptions.invalid("NovelAI 原生宽高需要为 64 的倍数，不会自动取整")
        val count = options.count ?: 1
        if (count !in 1..4) AgentImageGenerationOptions.invalid("NovelAI 原生路径一次支持 1–4 张")
        p.put("n_samples", count)
        if (!p.has("seed")) p.put("seed", SecureRandom().nextLong().ushr(32))
        val seed = (p.opt("seed") as? Number)?.toDouble()
        if (seed == null || !seed.isFinite() || seed !in 0.0..4294967295.0 || seed % 1 != 0.0)
            AgentImageGenerationOptions.invalid("NovelAI seed 应为 0–4294967295 的整数")
        val prompt = prepared.body.getString("prompt")
        // Native v4/v5 base-caption must describe this request, not a stale saved template.
        if (p.getInt("params_version") >= 3) {
            fun caption(key: String, text: String) {
                val wrapper = if (p.has(key)) p.optJSONObject(key)
                    ?: AgentImageGenerationOptions.invalid("NovelAI $key 必须是对象") else JSONObject()
                val content = if (wrapper.has("caption")) wrapper.optJSONObject("caption")
                    ?: AgentImageGenerationOptions.invalid("NovelAI caption 必须是对象") else JSONObject()
                content.put("base_caption", text)
                if (!content.has("char_captions")) content.put("char_captions", JSONArray())
                wrapper.put("caption", content)
                if (!wrapper.has("use_coords")) wrapper.put("use_coords", false)
                if (!wrapper.has("use_order")) wrapper.put("use_order", true)
                p.put(key, wrapper)
            }
            caption("v4_prompt", prompt)
            caption("v4_negative_prompt", p.optString("negative_prompt", ""))
        }
        return JSONObject().put("model", prepared.body.getString("model"))
            .put("input", prompt).put("action", "generate").put("parameters", p)
    }

    /** Bound compressed and decompressed bytes, including ignored ZIP entries; never extract paths. */
    fun parse(bytes: ByteArray, checkCancelled: () -> Unit = {}): AgentImageGenerationParser.Parsed {
        require(bytes.size <= MAX_RESPONSE_BYTES) { "NovelAI 响应过大" }
        if (bytes.hasSupportedImageMagic()) {
            require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "NovelAI 图片过大" }
            return AgentImageGenerationParser.Parsed(listOf(AgentImageGenerationParser.ImageRef(
                bytes = bytes, mimeType = bytes.sniffAgentImageMimeType())))
        }
        require(bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) { "NovelAI 没有返回图片或 ZIP" }
        val images = mutableListOf<AgentImageGenerationParser.ImageRef>()
        var total = 0L; var entries = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                checkCancelled()
                val entry = zip.nextEntry ?: break
                require(++entries <= 32) { "NovelAI ZIP 条目过多" }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancelled()
                    val n = zip.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_RESPONSE_BYTES && out.size().toLong() + n <= MAX_AGENT_IMAGE_BYTES) { "NovelAI ZIP 解压数据超限" }
                    out.write(buffer, 0, n)
                }
                val raw = out.toByteArray()
                if (!entry.isDirectory && raw.hasSupportedImageMagic()) {
                    require(images.size < 4) { "NovelAI 返回图片数量超限" }
                    images += AgentImageGenerationParser.ImageRef(bytes = raw, mimeType = raw.sniffAgentImageMimeType())
                }
                zip.closeEntry()
            }
        }
        require(images.isNotEmpty()) { "NovelAI ZIP 中没有可用图片" }
        return AgentImageGenerationParser.Parsed(images)
    }
    const val MAX_RESPONSE_BYTES = MAX_AGENT_IMAGE_BYTES * 4
}
