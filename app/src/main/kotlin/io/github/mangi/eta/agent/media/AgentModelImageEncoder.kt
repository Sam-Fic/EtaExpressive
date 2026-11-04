package io.github.mangi.eta.agent.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.min
import kotlin.math.sqrt

internal const val MAX_AGENT_IMAGE_BYTES = 12 * 1024 * 1024
internal const val MAX_AGENT_VIDEO_BYTES = 32 * 1024 * 1024

/**
 * 送模型图片的统一上限：1080p 口径 = 长边 1920、像素 1920×1080（2,073,600）。
 *
 * 所有进入模型请求的档位（截图观察、用户附件、文件工具图、语音屏幕上下文）都引用这里，
 * 避免新旧两套上限并存。聊天预览档（1024）低于该上限，不受影响；语音留档预览（1600）
 * 只进聊天、不进模型，也不受此约束。
 */
internal const val AGENT_MODEL_IMAGE_MAX_LONG_EDGE = 1_920
internal const val AGENT_MODEL_IMAGE_MAX_PIXELS = 1_920L * 1_080L

/**
 * 送模型图片的目标像素尺寸：先按长边上限缩，再按像素上限缩。
 *
 * 编码器（见 [AgentModelImageEncoder] 的 `targetSize`）与上下文预算
 * （[io.github.mangi.eta.agent.model.AgentContextBudget.countImageTokens]）共用这一处实现，
 * 保证「实际编码尺寸」与「token 估算尺寸」不会出现第二份公式。
 */
internal fun modelImageTargetSize(
    width: Int,
    height: Int,
    maxLongEdge: Int?,
    maxPixels: Long?,
): Pair<Int, Int> {
    if (width <= 0 || height <= 0) return width to height
    var scale = maxLongEdge
        ?.let { limit -> min(1.0, limit.toDouble() / maxOf(width, height)) }
        ?: 1.0
    val scaledPixels = width.toDouble() * height * scale * scale
    maxPixels?.let { limit ->
        if (scaledPixels > limit) {
            scale *= sqrt(limit / scaledPixels)
        }
    }
    return (width * scale).toInt().coerceIn(1, width) to
        (height * scale).toInt().coerceIn(1, height)
}

/**
 * 用户附件送模型时的单张字节目标（约 1.43 MiB）。
 *
 * 无损 WebP 对照片常到 1~3 MB，base64 后单张会变成 2~4 MB 字符串；取 1.5 MB
 * 让编码结果 base64 后约 2 MB 字符，既明显低于 [MAX_AGENT_IMAGE_BYTES]（12 MB）
 * 的兜底，也给同一条消息里的多张附件与请求体留出余量。超过即按降级阶梯缩小。
 */
internal const val ATTACHMENT_MODEL_TARGET_BYTES = 1_500_000

/** 语音入口「添加当前屏幕」生成的截图来源标记：留档预览按大图处理。 */
internal const val SCREEN_CONTEXT_IMAGE_SOURCE = "screen_context"

/** 仅负责为工具截图和聊天预览生成独立的图片副本。 */
internal object AgentModelImageEncoder {
    // WebP 无损模式的 quality 表示编码努力，不会丢失像素信息。
    private const val SCREEN_WEBP_EFFORT = 75

    // 聊天预览是有损档：同等观感下 WebP q90 通常比 JPEG q80 小约 30%，
    // 可抵消长边 512→1024 带来的部分体积增长（像素 4 倍，字节约 2~2.5 倍）。
    // JPEG 只作 WebP 编码不可用时的兜底。
    private const val PREVIEW_WEBP_QUALITY = 90
    private const val PREVIEW_JPEG_FALLBACK_QUALITY = 88

    private data class EncodingProfile(
        val format: Bitmap.CompressFormat,
        val mimeType: String,
        val quality: Int,
        val maxLongEdge: Int? = null,
        val maxPixels: Long? = null,
    )

    private data class ImageBounds(
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    private data class TargetSize(val width: Int, val height: Int)

    private val screenProfile = EncodingProfile(
        format = Bitmap.CompressFormat.WEBP_LOSSLESS,
        mimeType = "image/webp",
        quality = SCREEN_WEBP_EFFORT,
    )

    /**
     * 聊天里的图片预览档。
     *
     * 旧档长边 512：竖屏截图只有约 230×512，点开截图的小字基本不可读。
     * 提到 1024 后，9:20 竖屏是 460×1024（约 47 万像素），16:9 是 1024×576（约 59 万像素），
     * 像素多了 4 倍；[maxPixels] 取 1024² 只是方形图的天然上限，常规截图用不到，
     * 因此不会出现“预算变 4 倍”的浪费。格式改为有损 WebP q90，字节只增约 2~2.5 倍。
     */
    private val previewProfile = EncodingProfile(
        maxLongEdge = 1_024,
        maxPixels = 1_048_576L,
        format = Bitmap.CompressFormat.WEBP_LOSSY,
        mimeType = "image/webp",
        quality = PREVIEW_WEBP_QUALITY,
    )

    /** WebP 编码器不可用（个别 ROM/测试环境）时的同尺寸兜底，尺寸预算保持一致。 */
    private val previewJpegFallbackProfile = EncodingProfile(
        maxLongEdge = 1_024,
        maxPixels = 1_048_576L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = PREVIEW_JPEG_FALLBACK_QUALITY,
    )

    /**
     * 用户附件送模型的主档位：优先无损 WebP，统一 1080p 上限
     * （长边 [AGENT_MODEL_IMAGE_MAX_LONG_EDGE]、[AGENT_MODEL_IMAGE_MAX_PIXELS]）。
     *
     * 与预览档分开，附件不会拿聊天里的 1024 有损预览再压。
     */
    private val attachmentModelProfile = EncodingProfile(
        maxLongEdge = AGENT_MODEL_IMAGE_MAX_LONG_EDGE,
        maxPixels = AGENT_MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.WEBP_LOSSLESS,
        mimeType = "image/webp",
        quality = SCREEN_WEBP_EFFORT,
    )

    /** 降级阶梯 1：无损超预算时改同尺寸 WebP q95（照片通常 200~500 KB）。 */
    private val attachmentModelQualityProfile = EncodingProfile(
        maxLongEdge = AGENT_MODEL_IMAGE_MAX_LONG_EDGE,
        maxPixels = AGENT_MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.WEBP_LOSSY,
        mimeType = "image/webp",
        quality = 95,
    )

    /** 降级阶梯 2：WebP 编码不可用或仍超预算，退同尺寸 JPEG q92。 */
    private val attachmentModelJpegProfile = EncodingProfile(
        maxLongEdge = AGENT_MODEL_IMAGE_MAX_LONG_EDGE,
        maxPixels = AGENT_MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = 92,
    )

    /**
     * 降级阶梯 3：仍然超预算时把长边降到 1536 / 2MP。
     * 新上限 1920 之下无需再微调：1536 已比 1920 少约 20% 长边、约 36% 像素，
     * 作为一档降级仍然足够明显，且低于新的统一上限，不会与新口径冲突。
     */
    private val attachmentModelCompactProfile = EncodingProfile(
        maxLongEdge = 1_536,
        maxPixels = 2_000_000L,
        format = Bitmap.CompressFormat.WEBP_LOSSY,
        mimeType = "image/webp",
        quality = 90,
    )

    /** 降级阶梯 4：最终兜底，1280 / 1.3MP JPEG q90，保证请求发得出去（低于统一上限，同样无需微调）。 */
    private val attachmentModelCompactJpegProfile = EncodingProfile(
        maxLongEdge = 1_280,
        maxPixels = 1_300_000L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = 90,
    )

    /**
     * 语音入口截图的留档预览：列表缩略图档在竖屏上只有四百多像素宽，聊天里点开还是一屏糊。
     * 这类截图仍按视觉档尺寸留一张更清晰的预览，体积只多几百 KB。
     */
    private val archiveScreenPreviewProfile = EncodingProfile(
        maxLongEdge = 1_600,
        maxPixels = 1_500_000L,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = 88,
    )

    /**
     * 文件类工具图片（read_image 等）的发图档位，同时作为 [screenToolProfile] 超预算时的退化档。
     *
     * 统一按 1080p 口径限制（长边 [AGENT_MODEL_IMAGE_MAX_LONG_EDGE]、
     * [AGENT_MODEL_IMAGE_MAX_PIXELS]），兼顾小字可读性与单图 token / 请求体成本。
     */
    private val toolVisionProfile = EncodingProfile(
        maxLongEdge = AGENT_MODEL_IMAGE_MAX_LONG_EDGE,
        maxPixels = AGENT_MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = 88,
    )

    /**
     * 观察类截图（无障碍窗口合成 / 虚拟屏 / Root screencap）的发图档位。
     *
     * 截图内容是「文字 + 纯色块」，Android 的 `Bitmap.compress(JPEG)` 走 Skia 默认 4:2:0
     * 色度抽样且 API 无法关闭，彩色小字会掉色/出彩边，quality 再高也救不回来；振铃还会在
     * 高对比笔画边缘拖影。因此截图改走 WebP 无损，分辨率与 [toolVisionProfile] 同为
     * 1080p 上限，token 成本不变，只增加请求体字节。超出 [MAX_AGENT_IMAGE_BYTES]
     * 的极端图才退回 JPEG。
     */
    private val screenToolProfile = EncodingProfile(
        maxLongEdge = AGENT_MODEL_IMAGE_MAX_LONG_EDGE,
        maxPixels = AGENT_MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.WEBP_LOSSLESS,
        mimeType = "image/webp",
        quality = SCREEN_WEBP_EFFORT,
    )

    /**
     * 助理入口的屏幕上下文也**送进模型**，因此同样受 1080p 上限约束：
     * 超过 1920 长边时先按比例缩到上限，再走无损 WebP（不引入色度抽样与振铃）。
     * 体积只由 [MAX_AGENT_IMAGE_BYTES] 兜底（见 [nativeScreenContext]）。
     */
    private val nativeScreenProfile = EncodingProfile(
        maxLongEdge = AGENT_MODEL_IMAGE_MAX_LONG_EDGE,
        maxPixels = AGENT_MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.WEBP_LOSSLESS,
        mimeType = "image/webp",
        quality = SCREEN_WEBP_EFFORT,
    )

    /** 无损图超出请求预算时保持同一尺寸，只退到高质量 JPEG。 */
    private val nativeScreenJpegProfile = EncodingProfile(
        maxLongEdge = AGENT_MODEL_IMAGE_MAX_LONG_EDGE,
        maxPixels = AGENT_MODEL_IMAGE_MAX_PIXELS,
        format = Bitmap.CompressFormat.JPEG,
        mimeType = "image/jpeg",
        quality = 92,
    )

    fun screen(
        bytes: ByteArray,
        source: String,
        mimeHint: String,
    ): AgentModelClient.ModelImage? {
        if (!bytes.hasSupportedImageMagic()) return null
        val encoded = transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBounds(bytes, mimeHint),
            profile = screenProfile,
        )
        // Root screencap 已经是压缩图片；只在无损 WebP 确实更小时替换它。
        return encoded?.takeIf { it.bytes < bytes.size }
    }

    fun screen(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        encodeBitmap(bitmap, source, screenProfile, flattenAlpha = false)

    /**
     * Agent 观察工具截图走统一的 1080p 视觉上限，避免多轮截图把请求体撑大。
     * 优先无损 WebP（规避 JPEG 4:2:0 色度抽样与振铃），体积超预算时退回同尺寸 JPEG。
     */
    fun screenContext(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        encodeWithinBudget(bitmap, source, screenToolProfile, flattenAlpha = false)
            ?: encodeBitmap(bitmap, source, toolVisionProfile, flattenAlpha = true)

    /**
     * 「添加当前屏幕」/ 快捷预设发给模型的截图：按统一 1080p 上限缩放（超过 1920 长边才缩）。
     * 先走无损 WebP；只有画面信息量极大（接近照片的满屏噪点）超出请求体积预算时，
     * 才依次退到同尺寸高质量 JPEG、最后才退到视觉档，保证这一轮请求仍然发得出去。
     */
    fun nativeScreenContext(
        bitmap: Bitmap,
        source: String,
    ): AgentModelClient.ModelImage =
        encodeWithinBudget(bitmap, source, nativeScreenProfile, flattenAlpha = false)
            ?: encodeWithinBudget(bitmap, source, nativeScreenJpegProfile, flattenAlpha = true)
            ?: encodeBitmap(bitmap, source, toolVisionProfile, flattenAlpha = true)

    /** Root screencap / 副屏帧等 PNG 原始字节：与 [screenContext] 同样的无损截图档位。 */
    fun screenTool(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/png",
    ): AgentModelClient.ModelImage? {
        if (!bytes.hasSupportedImageMagic()) return null
        val bounds = inspectBounds(bytes, mimeHint)
        return transcodeWithinBudget(bytes, source, bounds, screenToolProfile)
            ?: transcodeWithinBudget(bytes, source, bounds, toolVisionProfile)
    }

    private fun transcodeWithinBudget(
        bytes: ByteArray,
        source: String,
        bounds: ImageBounds,
        profile: EncodingProfile,
    ): AgentModelClient.ModelImage? = try {
        transcodeBytes(bytes, source, bounds, profile)
    } catch (error: Exception) {
        null
    }

    /** 文件工具图片仅在发送模型前缩放压缩，保持多图请求的体积可控。 */
    fun toolVision(
        bytes: ByteArray,
        source: String,
        mimeHint: String = "image/jpeg",
    ): AgentModelClient.ModelImage? = runCatching {
        if (!bytes.hasSupportedImageMagic()) return@runCatching null
        transcodeBytes(
            bytes = bytes,
            source = source,
            bounds = inspectBounds(bytes, mimeHint),
            profile = toolVisionProfile,
        )
    }.getOrNull()

    /**
     * 用户附件真正送进模型的一份图：从**原始引用**（data URL / 本地文件 / content URI）解码，
     * 按 [attachmentModelProfile] 重编码，绝不拿聊天里的 1024 预览二次压缩。
     *
     * 降级阶梯（每一步都用同一张解码后的位图重新编码，不叠加代际损失）：
     * 无损 WebP 1920 → WebP q95 1920 → JPEG q92 1920 → WebP q90 1536 → JPEG q90 1280，
     * 取第一个不超过 [ATTACHMENT_MODEL_TARGET_BYTES] 的结果。
     */
    fun attachmentModel(
        context: Context,
        reference: String,
        source: String,
    ): AgentModelClient.ModelImage? = runCatching {
        val openStream = referenceStreamFactory(context, reference) ?: return@runCatching null
        val bytes = openStream().use(::readBoundedImageBytes) ?: return@runCatching null
        if (!bytes.hasSupportedImageMagic()) return@runCatching null
        val bounds = inspectBounds(bytes, "image/jpeg")
        if (bounds.width <= 0 || bounds.height <= 0) return@runCatching null
        val target = bounds.targetSize(attachmentModelProfile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return@runCatching null
        try {
            encodeWithinBudget(
                bitmap = bitmap,
                source = source,
                profile = attachmentModelProfile,
                flattenAlpha = false,
                maxBytes = ATTACHMENT_MODEL_TARGET_BYTES,
            ) ?: encodeWithinBudget(
                bitmap = bitmap,
                source = source,
                profile = attachmentModelQualityProfile,
                flattenAlpha = false,
                maxBytes = ATTACHMENT_MODEL_TARGET_BYTES,
            ) ?: encodeWithinBudget(
                bitmap = bitmap,
                source = source,
                profile = attachmentModelJpegProfile,
                flattenAlpha = true,
                maxBytes = ATTACHMENT_MODEL_TARGET_BYTES,
            ) ?: encodeWithinBudget(
                bitmap = bitmap,
                source = source,
                profile = attachmentModelCompactProfile,
                flattenAlpha = false,
                maxBytes = ATTACHMENT_MODEL_TARGET_BYTES,
            ) ?: encodeWithinBudget(
                bitmap = bitmap,
                source = source,
                profile = attachmentModelCompactJpegProfile,
                flattenAlpha = true,
                maxBytes = ATTACHMENT_MODEL_TARGET_BYTES,
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }.getOrNull()

    /** 读取原始图片字节；超过 [MAX_AGENT_IMAGE_BYTES] 视为不可用，避免把超大图解进内存。 */
    private fun readBoundedImageBytes(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_AGENT_IMAGE_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = preview(context, image, previewProfile)

    /**
     * 语音入口的屏幕上下文会作为聊天里的截图留档：列表缩略图档太糊，
     * 这一类改用更大尺寸的预览；其他附件仍保持 [previewProfile]。
     */
    fun archivePreview(
        context: Context,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ModelImage? = preview(
        context = context,
        image = image,
        profile = if (image.source == SCREEN_CONTEXT_IMAGE_SOURCE) {
            archiveScreenPreviewProfile
        } else {
            previewProfile
        },
    )

    /**
     * 从模型原图（[AgentModelClient.ModelImage.reference]）解码后重编码一次，生成聊天预览。
     *
     * 预览不进入模型请求，模型读的始终是原图；这里即便原图是 WebP 无损，预览也是
     * 从原图解码再编码的一次代际，不会在已压过的预览上二次压缩。
     *
     * [profile] 为 WebP 时保留 alpha；编码失败（如个别 ROM 缺少 WebP 编码器）时
     * 退回同尺寸 JPEG，避免预览整张消失。
     */
    private fun preview(
        context: Context,
        image: AgentModelClient.ModelImage,
        profile: EncodingProfile,
    ): AgentModelClient.ModelImage? = runCatching {
        val width = image.width ?: return@runCatching null
        val height = image.height ?: return@runCatching null
        val target = targetSize(width, height, profile)
        val openStream = referenceStreamFactory(context, image.reference) ?: return@runCatching null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(width, height, target)
        }
        val decoded = openStream().use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: return@runCatching null
        try {
            encodeWithinBudget(
                bitmap = decoded,
                source = image.source,
                profile = profile,
                flattenAlpha = profile.format == Bitmap.CompressFormat.JPEG,
            ) ?: encodeWithinBudget(
                bitmap = decoded,
                source = image.source,
                profile = previewJpegFallbackProfile,
                flattenAlpha = true,
            )
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }.getOrNull()

    /** 编码结果超出 [maxBytes] 时返回 null，交给调用方换更小的档位。 */
    private fun encodeWithinBudget(
        bitmap: Bitmap,
        source: String,
        profile: EncodingProfile,
        flattenAlpha: Boolean,
        maxBytes: Int = MAX_AGENT_IMAGE_BYTES,
    ): AgentModelClient.ModelImage? = try {
        encodeBitmap(bitmap, source, profile, flattenAlpha, maxBytes)
    } catch (error: Exception) {
        null
    }

    private fun transcodeBytes(
        bytes: ByteArray,
        source: String,
        bounds: ImageBounds,
        profile: EncodingProfile,
    ): AgentModelClient.ModelImage? {
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val target = bounds.targetSize(profile)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize(bounds.width, bounds.height, target)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        return try {
            encodeBitmap(
                bitmap = bitmap,
                source = source,
                profile = profile,
                flattenAlpha = profile.format == Bitmap.CompressFormat.JPEG &&
                    !bounds.mimeType.equals("image/jpeg", ignoreCase = true),
            )
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun encodeBitmap(
        bitmap: Bitmap,
        source: String,
        profile: EncodingProfile,
        flattenAlpha: Boolean,
        maxBytes: Int = MAX_AGENT_IMAGE_BYTES,
    ): AgentModelClient.ModelImage {
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) { "图片位图不可用" }
        val encodedBitmap = renderBitmap(
            bitmap = bitmap,
            target = targetSize(bitmap.width, bitmap.height, profile),
            flattenAlpha = flattenAlpha,
        )
        try {
            val initialCapacity = minOf(
                encodedBitmap.width * encodedBitmap.height / 4,
                2 * 1024 * 1024,
            ).coerceAtLeast(32 * 1024)
            val output = ByteArrayOutputStream(initialCapacity)
            check(encodedBitmap.compress(profile.format, profile.quality, output)) {
                "图片编码失败"
            }
            val encoded = output.toByteArray()
            require(encoded.isNotEmpty() && encoded.size <= maxBytes) {
                "图片数据过大：${encoded.size}"
            }
            return AgentModelClient.ModelImage(
                reference = "data:${profile.mimeType};base64,${Base64.encodeToString(encoded, Base64.NO_WRAP)}",
                mimeType = profile.mimeType,
                bytes = encoded.size,
                width = encodedBitmap.width,
                height = encodedBitmap.height,
                source = source,
            )
        } finally {
            if (encodedBitmap !== bitmap && !encodedBitmap.isRecycled) {
                encodedBitmap.recycle()
            }
        }
    }

    private fun renderBitmap(
        bitmap: Bitmap,
        target: TargetSize,
        flattenAlpha: Boolean,
    ): Bitmap {
        if (
            target.width == bitmap.width &&
            target.height == bitmap.height &&
            !flattenAlpha
        ) {
            return bitmap
        }
        return Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888).also { output ->
            val canvas = Canvas(output)
            if (flattenAlpha) canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, target.width, target.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            output.setHasAlpha(false)
        }
    }

    private fun inspectBounds(bytes: ByteArray, mimeHint: String): ImageBounds {
        require(bytes.isNotEmpty()) { "图片内容为空" }
        require(bytes.size <= MAX_AGENT_IMAGE_BYTES) { "图片数据过大：${bytes.size}" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return ImageBounds(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType
                ?.takeIf { it.isNotBlank() }
                ?: mimeHint.normalizedAgentImageMimeType(),
        )
    }

    private fun ImageBounds.targetSize(profile: EncodingProfile): TargetSize =
        targetSize(width, height, profile)

    private fun targetSize(
        width: Int,
        height: Int,
        profile: EncodingProfile,
    ): TargetSize {
        val (scaledWidth, scaledHeight) =
            modelImageTargetSize(width, height, profile.maxLongEdge, profile.maxPixels)
        return TargetSize(scaledWidth, scaledHeight)
    }

    private fun sampleSize(
        width: Int,
        height: Int,
        target: TargetSize,
    ): Int {
        var sample = 1
        while (sample <= 64) {
            val next = sample * 2
            val nextWidth = width.toDouble() / next
            val nextHeight = height.toDouble() / next
            if (
                nextWidth < target.width * 0.9 ||
                nextHeight < target.height * 0.9
            ) {
                break
            }
            sample = next
        }
        return sample
    }

    private fun referenceStreamFactory(
        context: Context,
        reference: String,
    ): (() -> InputStream)? {
        val trimmed = reference.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            val decoded = Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            if (decoded.isEmpty() || decoded.size > MAX_AGENT_IMAGE_BYTES) return null
            return { ByteArrayInputStream(decoded) }
        }
        val uri = Uri.parse(trimmed)
        if (uri.scheme == "content") {
            return {
                context.contentResolver.openInputStream(uri)
                    ?: error("无法打开本地图片")
            }
        }
        val file = when (uri.scheme) {
            "file" -> uri.path?.let(::File)
            null, "" -> File(trimmed)
            else -> null
        } ?: return null
        if (!file.isFile) return null
        return { file.inputStream() }
    }
}

internal fun String.normalizedAgentImageMimeType(): String =
    substringBefore(';')
        .trim()
        .takeIf {
            it.startsWith("image/", ignoreCase = true) &&
                !it.equals("image/*", ignoreCase = true)
        }
        ?: "image/jpeg"

internal fun ByteArray.sniffAgentImageMimeType(): String {
    if (size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return "image/jpeg"
    if (
        size >= 4 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return "image/png"
    }
    if (
        size >= 3 &&
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return "image/gif"
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return "image/webp"
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII).lowercase()
        return if (brand in setOf("avif", "avis")) "image/avif" else "image/heic"
    }
    return "image/png"
}

internal fun ByteArray.hasSupportedImageMagic(): Boolean {
    if (size < 8) return false
    if (this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) return true
    if (
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4E.toByte() && this[3] == 0x47.toByte()
    ) {
        return true
    }
    if (
        this[0] == 'G'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[0] == 'R'.code.toByte() && this[1] == 'I'.code.toByte() &&
        this[2] == 'F'.code.toByte() && this[3] == 'F'.code.toByte() &&
        this[8] == 'W'.code.toByte() && this[9] == 'E'.code.toByte() &&
        this[10] == 'B'.code.toByte() && this[11] == 'P'.code.toByte()
    ) {
        return true
    }
    if (
        size >= 12 &&
        this[4] == 'f'.code.toByte() && this[5] == 't'.code.toByte() &&
        this[6] == 'y'.code.toByte() && this[7] == 'p'.code.toByte()
    ) {
        val brand = String(this, 8, 4, Charsets.US_ASCII)
        return brand in setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1", "avif", "avis")
    }
    return false
}
