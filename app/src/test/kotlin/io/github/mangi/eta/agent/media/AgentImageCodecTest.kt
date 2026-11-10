package io.github.mangi.eta.agent.media

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentImageCodecTest {
    @Test
    fun screenCopyStaysWithinVisionBudgetAndNeverUpscales() {
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        try {
            val image = AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")

            // 截图优先无损 WebP；个别环境不支持 WebP 编码时退回同尺寸 JPEG。
            assertTrue(image.mimeType == "image/webp" || image.mimeType == "image/jpeg")
            assertTrue(image.reference.startsWith("data:${image.mimeType};base64,"))
            assertTrue(maxOf(width, height) <= AGENT_MODEL_IMAGE_MAX_LONG_EDGE)
            assertTrue(width.toLong() * height <= AGENT_MODEL_IMAGE_MAX_PIXELS)
            // 只允许缩小，绝不允许“解码尺寸小于目标尺寸再放大”的上采样。
            assertTrue(width <= bitmap.width)
            assertTrue(height <= bitmap.height)
            assertTrue(image.bytes in 1..MAX_AGENT_IMAGE_BYTES)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun screenToolNeverUpscalesForCommonSourceSizes() {
        val sizes = listOf(
            1_080 to 2_400,   // 常见竖屏
            1_280 to 800,     // 横屏 / 桌面网页
            2_000 to 2_000,   // 正方形，像素上限生效
            3_200 to 2_400,   // 大图，inSampleSize 会先预采样
        )
        for ((width, height) in sizes) {
            val bitmap = patternedBitmap(width = width, height = height)
            try {
                val image = AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
                val outWidth = image.width ?: error("缺少图片宽度")
                val outHeight = image.height ?: error("缺少图片高度")
                assertTrue(outWidth <= width)
                assertTrue(outHeight <= height)
                assertTrue(maxOf(outWidth, outHeight) <= AGENT_MODEL_IMAGE_MAX_LONG_EDGE)
                assertTrue(outWidth.toLong() * outHeight <= AGENT_MODEL_IMAGE_MAX_PIXELS)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun assistantScreenContextIsCappedTo1080p() {
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        try {
            val image = AgentImageCodec.fromScreenContextBitmap(
                bitmap,
                source = "screen_context",
            )
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")

            // 语音入口的屏幕上下文也送模型，按统一 1080p 上限缩到 864×1920。
            assertEquals(864, width)
            assertEquals(1_920, height)
            assertTrue(image.mimeType == "image/webp" || image.mimeType == "image/jpeg")
            assertTrue(image.reference.startsWith("data:${image.mimeType};base64,"))
            assertTrue(image.bytes in 1..MAX_AGENT_IMAGE_BYTES)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun encodedScreenBytesStayWithinVisionBudget() {
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        try {
            val image = AgentImageCodec.fromScreenBytes(png, source = "screen")
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")
            assertTrue(image.mimeType == "image/webp" || image.mimeType == "image/jpeg")
            assertTrue(image.reference.startsWith("data:${image.mimeType};base64,"))
            assertTrue(maxOf(width, height) <= AGENT_MODEL_IMAGE_MAX_LONG_EDGE)
            assertTrue(width <= 1_440)
            assertTrue(height <= 3_200)
            assertTrue(image.bytes < png.size)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun largeAttachmentKeepsOriginalBytesAndDimensions() {
        val bitmap = patternedBitmap(width = 2_400, height = 1_600)
        val original = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromAttachmentBytes(
            bytes = original,
            source = "user_attach",
            mimeHint = "image/jpeg",
        )
        val width = image.width ?: error("缺少图片宽度")
        val height = image.height ?: error("缺少图片高度")

        assertEquals("image/jpeg", image.mimeType)
        assertEquals(2_400, width)
        assertEquals(1_600, height)
        assertEquals(original.size, image.bytes)
        assertArrayEquals(original, image.reference.decodeDataUrl())
    }

    @Test
    fun smallAttachmentKeepsItsOriginalEncoding() {
        val bitmap = patternedBitmap(width = 96, height = 96)
        val original = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val image = AgentImageCodec.fromAttachmentBytes(
            bytes = original,
            source = "user_attach",
            mimeHint = "image/png",
        )

        assertEquals("image/png", image.mimeType)
        assertEquals(original.size, image.bytes)
        assertEquals(96, image.width)
        assertEquals(96, image.height)
    }

    @Test
    fun fileToolImageIsDownscaledForMultiImageRequests() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "tool-image-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 3_200, height = 2_400)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()

        try {
            val image = AgentImageCodec.fromToolFile(sourceFile, "tool_read_image")
                ?: error("无法压缩文件工具图片")
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")

            assertEquals("image/jpeg", image.mimeType)
            assertTrue(maxOf(width, height) <= AGENT_MODEL_IMAGE_MAX_LONG_EDGE)
            assertTrue(width * height <= AGENT_MODEL_IMAGE_MAX_PIXELS)
            assertTrue(image.bytes < sourceFile.length())
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun chatPreviewIsIndependentFromTheOriginalFile() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "image-preview-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 1_200, height = 800)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()
        val originalSize = sourceFile.length()
        try {
            val source = AgentImageCodec.fromTransferReference(
                context = context,
                value = sourceFile.absolutePath,
                source = "user_attach",
            ) ?: error("无法读取测试图片")
            val preview = AgentImageCodec.previewFromReference(context, source)
                ?: error("无法生成测试预览")

            assertEquals(sourceFile.absolutePath, source.reference)
            // 预览改为 1024 长边有损 WebP；个别环境 WebP 编码不可用时退回 JPEG。
            assertTrue(preview.mimeType == "image/webp" || preview.mimeType == "image/jpeg")
            assertEquals(1_024, maxOf(preview.width!!, preview.height!!))
            assertEquals(originalSize, sourceFile.length())
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun screenContextArchivePreviewKeepsChatDetail() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "screen-context-preview-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()
        try {
            val source = AgentImageCodec.fromTransferReference(
                context = context,
                value = sourceFile.absolutePath,
                source = SCREEN_CONTEXT_IMAGE_SOURCE,
            ) ?: error("无法读取测试图片")
            val archive = AgentImageCodec.archivePreviewFromReference(context, source)
                ?: error("无法生成留档预览")
            val listPreview = AgentImageCodec.previewFromReference(context, source)
                ?: error("无法生成列表预览")

            // 留档预览按视觉档尺寸，聊天里点开不再是一屏糊；列表缩略图保持 1024 长边。
            assertEquals(1_600, maxOf(archive.width!!, archive.height!!))
            assertEquals("image/jpeg", archive.mimeType)
            assertTrue(archive.bytes in 1..MAX_AGENT_IMAGE_BYTES)
            assertTrue(maxOf(listPreview.width!!, listPreview.height!!) <= 1_024)
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun pickedAttachmentPreviewDoesNotReopenThePickerUri() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "picked-image-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 1_200, height = 800)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()

        val attachment = AgentImageCodec.fromReference(
            context = context,
            value = sourceFile.absolutePath,
            source = "user_attach",
        ) ?: error("无法读取测试图片")
        assertTrue(sourceFile.delete())

        val preview = AgentImageCodec.previewFromReference(context, attachment)
            ?: error("无法从已读取的附件生成预览")

        assertTrue(preview.mimeType == "image/webp" || preview.mimeType == "image/jpeg")
        assertTrue(maxOf(preview.width!!, preview.height!!) <= 1_024)
    }

    @Test
    fun pickerUriFallsBackToTypedAssetStream() {
        val context = RuntimeEnvironment.getApplication()
        val sourceFile = File(context.cacheDir, "typed-picker-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 873, height = 1_920)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        bitmap.recycle()
        val authority = "io.github.mangi.eta.test.picker.${System.nanoTime()}"
        val provider = TypedImageProvider(sourceFile)
        provider.attachInfo(
            context,
            ProviderInfo().apply { this.authority = authority },
        )
        ShadowContentResolver.registerProviderInternal(authority, provider)

        try {
            val uri = Uri.parse("content://$authority/image")
            val image = AgentImageCodec.fromReference(
                context = context,
                value = uri.toString(),
                source = "user_attach",
            ) ?: error("无法通过 typed asset 读取测试图片")

            assertEquals("image/jpeg", image.mimeType)
            assertEquals(873, image.width)
            assertEquals(1_920, image.height)
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun toolMediaPreviewsEnforcePerImageAndPerMessageCharBudgets() {
        fun preview(chars: Int): String =
            "data:image/webp;base64," + "A".repeat(chars - "data:image/webp;base64,".length)

        // 单张超过 400k 字符的异常预览整张丢弃。
        assertEquals(AgentToolMediaPreviews.EMPTY, AgentToolMediaPreviews.encodePreviews(listOf(preview(410_000))))

        // 总量封顶 640k：三张 300k 只收下前两张，避免 Messenger 单次事务溢出。
        val accepted = AgentToolMediaPreviews.decode(
            AgentToolMediaPreviews.encodePreviews(listOf(preview(300_000), preview(300_000), preview(300_000))),
        )
        assertEquals(2, accepted.size)
    }

    @Test
    fun attachmentModelReencodesFromOriginalAtVisionBudget() {
        val context = RuntimeEnvironment.getApplication()
        // 源文件用 JPEG：1440×3200 的图案 PNG 在 Robolectric 编码下约 18MB，
        // 必超 inspectFile 的 12MB 附件闸门（视为不可读），测试就无从谈起重编码了。
        val sourceFile = File(context.cacheDir, "attachment-model-${System.nanoTime()}.jpg")
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        FileOutputStream(sourceFile).use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
        }
        bitmap.recycle()
        try {
            val original = AgentImageCodec.fromTransferReference(
                context = context,
                value = sourceFile.absolutePath,
                source = "user_attach",
            ) ?: error("无法读取测试图片")
            val preview = AgentImageCodec.previewFromReference(context, original)
                ?: error("缺少聊天预览")
            val model = AgentImageCodec.attachmentModelFromReference(context, original.reference)
                ?: error("缺少附件模型图")

            // 气泡预览固定 1024 长边；送模型的附件从原图重编码，长边回到 1080p 上限
            // （1920，极端体积下按阶梯降到 1536），反正不会等于聊天预览的 1024。
            assertEquals(1_024, maxOf(preview.width!!, preview.height!!))
            val longEdge = maxOf(model.width!!, model.height!!)
            assertTrue(longEdge in 1_536..AGENT_MODEL_IMAGE_MAX_LONG_EDGE)
            // 优先无损 WebP；个别环境 WebP 编码不可用时按阶梯退到 JPEG。
            assertTrue(model.mimeType == "image/webp" || model.mimeType == "image/jpeg")
            assertTrue(model.bytes in 1..ATTACHMENT_MODEL_TARGET_BYTES)
        } finally {
            sourceFile.delete()
        }
    }

    private fun patternedBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 92, 180)
                strokeWidth = 7f
            }
            val step = (minOf(width, height) / 12).coerceAtLeast(8)
            for (offset in 0 until maxOf(width, height) step step) {
                canvas.drawLine(0f, offset.toFloat(), width.toFloat(), (offset / 2).toFloat(), paint)
                canvas.drawLine(offset.toFloat(), 0f, (offset / 2).toFloat(), height.toFloat(), paint)
            }
        }

    private fun String.decodeDataUrl(): ByteArray =
        Base64.decode(substringAfter("base64,"), Base64.DEFAULT)

    private class TypedImageProvider(
        private val sourceFile: File,
    ) : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String = "image/jpeg"

        override fun openTypedAssetFile(
            uri: Uri,
            mimeTypeFilter: String,
            opts: Bundle?,
        ): AssetFileDescriptor {
            if (mimeTypeFilter != "image/*") {
                throw FileNotFoundException("only typed images are available")
            }
            val descriptor = ParcelFileDescriptor.open(
                sourceFile,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
            return AssetFileDescriptor(descriptor, 0L, sourceFile.length())
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }
}
