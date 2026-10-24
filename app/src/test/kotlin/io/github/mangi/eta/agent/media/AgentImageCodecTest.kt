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
    fun screenCopyUsesBoundedJpegForModelRequests() {
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        try {
            val image = AgentImageCodec.fromScreenBitmap(bitmap, source = "screen")
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")

            assertEquals("image/jpeg", image.mimeType)
            assertTrue(image.reference.startsWith("data:image/jpeg;base64,"))
            assertTrue(maxOf(width, height) <= 1_600)
            assertTrue(width.toLong() * height <= 1_500_000L)
            assertTrue(image.bytes < 1_000_000)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun assistantScreenContextKeepsNativeResolution() {
        val bitmap = patternedBitmap(width = 1_440, height = 3_200)
        try {
            val image = AgentImageCodec.fromScreenContextBitmap(
                bitmap,
                source = "screen_context",
            )
            val width = image.width ?: error("缺少图片宽度")
            val height = image.height ?: error("缺少图片高度")

            // 模型拿到的是屏幕原图：不缩放。体积超预算时才转同尺寸高质量 JPEG。
            assertEquals(1_440, width)
            assertEquals(3_200, height)
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
            assertEquals("image/jpeg", image.mimeType)
            assertTrue(maxOf(width, height) <= 1_600)
            assertTrue(image.bytes < png.size)
            assertTrue(image.bytes < 1_000_000)
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
            assertTrue(maxOf(width, height) <= 1_600)
            assertTrue(width * height <= 1_500_000)
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
            assertEquals("image/jpeg", preview.mimeType)
            assertTrue(maxOf(preview.width!!, preview.height!!) <= 512)
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

            // 留档预览按视觉档尺寸，聊天里点开不再是一屏糊；列表缩略图仍保持小图。
            assertEquals(1_600, maxOf(archive.width!!, archive.height!!))
            assertEquals("image/jpeg", archive.mimeType)
            assertTrue(archive.bytes in 1..MAX_AGENT_IMAGE_BYTES)
            assertTrue(maxOf(listPreview.width!!, listPreview.height!!) <= 512)
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

        assertEquals("image/jpeg", preview.mimeType)
        assertTrue(maxOf(preview.width!!, preview.height!!) <= 512)
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
