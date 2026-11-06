package io.github.mangi.eta.agent.media.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Xml
import io.github.mangi.eta.data.db.MediaKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * 媒体抽取：把候选项转成嵌入引擎可接受的字节输入，并生成缩略图与内容哈希。
 *
 * 所有抽取都有硬预算（最长边、帧数、音频时长/窗口数、PDF 页数、文档字符数），避免大文件 OOM；
 * 产出统一走 JPEG/PNG（图像、PDF 页图）、WAV（音频窗口）与纯文本（文档）。
 * 预算值以函数参数传入，默认值 = 下方常量 = [IndexModeProfile] 全力档（PERFORMANCE）取值，
 * 低耗档（POWER_SAVER）按档减配；常量语义不变，仍是默认/满配预算。
 * 可单测的纯逻辑（音频切窗/WAV 封装/重采样、DOCX XML→文本、PDF 页预算、视频帧时刻序列）
 * 均以 internal 函数暴露，Android 解码器相关部分只做 IO 编排。
 */
internal object MediaExtraction {
    /** 嵌入输入图像的最长边；EmbeddingGemma 视觉编码再大收益有限，压小可省内存。 */
    const val MAX_IMAGE_EDGE = 896

    /** 缩略图最长边。 */
    const val MAX_THUMB_EDGE = 256

    private const val TAG = "MediaExtraction"

    /** 单个视频最多抽取的帧数；逐帧嵌入后聚合成一个向量。 */
    const val MAX_VIDEO_FRAMES = 8

    /** 文档抽取的字符上限，超出截断（PDF 走页图嵌入，DOCX 抽正文文本）。 */
    internal const val MAX_DOCUMENT_CHARS = 8_000

    /** PDF 页图嵌入的页数上限：从首页起，超预算的页直接丢弃。 */
    const val MAX_PDF_PAGES = 8

    /** PDF MIME：走页图嵌入通道，不读裸字节。 */
    const val PDF_MIME = "application/pdf"

    /** DOCX MIME：zip 读 word/document.xml 抽正文文本。 */
    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** 音频嵌入的目标采样率：EmbeddingGemma 音频编码器要求 16kHz 单声道。 */
    const val AUDIO_SAMPLE_RATE = 16_000

    /** 音频切窗长度：每窗独立嵌入后平均聚合。 */
    const val AUDIO_WINDOW_MS = 30_000L

    /** 音频总时长硬预算：超长只取前 5 分钟，避免长音频拖垮索引。 */
    const val MAX_AUDIO_MS = 5 * 60 * 1_000L

    /** DOCX 正文所在 zip 条目。 */
    private const val DOCX_DOCUMENT_ENTRY = "word/document.xml"

    /** 解码器输入/输出取缓冲的等待超时；连续空转次数上限防止个别文件卡死整轮索引。 */
    private const val CODEC_TIMEOUT_US = 10_000L
    private const val CODEC_MAX_IDLE_SPINS = 200

    /** 图像嵌入输入：降采样到 [maxEdge] 后重新编码为 JPEG；默认 [MAX_IMAGE_EDGE]（全力档预算）。 */
    fun imageBytes(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
        maxEdge: Int = MAX_IMAGE_EDGE,
    ): ByteArray? =
        runCatching {
            val bitmap = decodeSampled(context, candidate, maxEdge) ?: return@runCatching null
            bitmap.toJpeg(85).also { bitmap.recycle() }
        }.getOrNull()

    /** 视频抽帧结果：[timeMs] 为该帧在视频中的时刻（毫秒），供「时刻检索」二段精排回填命中时刻。 */
    data class VideoFrame(val timeMs: Long, val bytes: ByteArray)

    /**
     * 均匀布点的帧时刻序列（毫秒，纯逻辑）：避开首尾极端位置（首帧常为黑场或片头）；
     * 时长未知时全部落在 0。
     */
    internal fun frameTimestamps(durationMs: Long, maxFrames: Int = MAX_VIDEO_FRAMES): List<Long> =
        if (durationMs > 0) {
            (0 until maxFrames).map { index -> durationMs * (index + 1) / (maxFrames + 1) }
        } else {
            List(maxFrames) { 0L }
        }

    /** 视频嵌入输入：按时间均匀抽取关键帧（带时刻戳），最多 [maxFrames] 帧（默认 [MAX_VIDEO_FRAMES]，全力档预算）。 */
    fun videoFrames(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
        maxFrames: Int = MAX_VIDEO_FRAMES,
    ): List<VideoFrame> =
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                setDataSource(retriever, context, candidate)
                val durationMs = candidate.durationMs
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                    ?: 0L
                val frames = mutableListOf<VideoFrame>()
                for (timeMs in frameTimestamps(durationMs, maxFrames)) {
                    val frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: continue
                    val scaled = frame.scaleToEdge(640)
                    frames += VideoFrame(timeMs = timeMs, bytes = scaled.toJpeg(80))
                    if (scaled !== frame) frame.recycle()
                    scaled.recycle()
                }
                frames
            } finally {
                retriever.release()
            }
        }.getOrDefault(emptyList())

    /** 视频嵌入输入（不含时刻）：入库侧只用帧字节聚合成整段视频向量。 */
    fun videoFrameBytes(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
        maxFrames: Int = MAX_VIDEO_FRAMES,
    ): List<ByteArray> = videoFrames(context, candidate, maxFrames).map { it.bytes }

    /**
     * 音频嵌入输入：解码为 16kHz 单声道 PCM，按 30s 窗口切片，每窗封装成完整 WAV
     * （LiteRT-LM 的 InputData.Audio 只接受 WAV 字节，不收裸 PCM）。
     * 硬预算：只取前 [MAX_AUDIO_MS]（5 分钟）；编码不支持或解码失败返回空清单（索引计 failed）。
     */
    fun audioWindows(context: Context, candidate: MediaSourceScanner.Candidate): List<ByteArray> =
        runCatching {
            val pcm = decodeMono16kPcm(context, candidate) ?: return@runCatching emptyList()
            splitWindows(capToBudget(pcm, AUDIO_SAMPLE_RATE), AUDIO_SAMPLE_RATE)
                .map { window -> wavBytes(window, AUDIO_SAMPLE_RATE) }
        }.getOrDefault(emptyList())

    /**
     * 文档嵌入输入：
     * - PDF：返回 null，由 [pdfPageImages] 走页图嵌入（不读裸字节，避免乱码入库）；
     * - DOCX：zip 抽 `word/document.xml` 正文纯文本；
     * - 其余：纯文本直读（大文件截断）。
     * 本地路径优先；无路径（SAF 候选）时回落 contentResolver 内容流读 UTF-8 文本。
     */
    fun documentText(context: Context, candidate: MediaSourceScanner.Candidate): String? =
        runCatching {
            val raw = when {
                isPdf(candidate) -> null
                isDocx(candidate) -> openStream(context, candidate)?.use { docxTextFrom(it) }
                else -> openStream(context, candidate)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }
            raw?.trim()
                ?.take(MAX_DOCUMENT_CHARS)
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

    /** PDF 候选判定：MIME 或扩展名；[MediaIndexer] 据此把 PDF 分流到页图嵌入。 */
    internal fun isPdf(candidate: MediaSourceScanner.Candidate): Boolean =
        candidate.mime == PDF_MIME || candidate.displayName.endsWith(".pdf", ignoreCase = true)

    /** DOCX 候选判定：MIME 或扩展名。 */
    internal fun isDocx(candidate: MediaSourceScanner.Candidate): Boolean =
        candidate.mime == DOCX_MIME || candidate.displayName.endsWith(".docx", ignoreCase = true)

    /**
     * DOCX 正文抽取（纯逻辑）：解析 `word/document.xml`，取 `<w:t>` 文本运行逐段拼接，
     * 去掉标签与排版空白，段落间换行，超出 [limit] 截断。
     */
    internal fun docxText(xml: String, limit: Int = MAX_DOCUMENT_CHARS): String {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        val text = StringBuilder()
        var insideTextRun = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localNameOf(parser)) {
                    // 段落边界：段前补换行（首段除外），拼出可读正文。
                    "p" -> if (text.isNotEmpty() && text.last() != '\n') text.append('\n')
                    "t" -> insideTextRun = true
                    else -> Unit
                }
                XmlPullParser.TEXT -> if (insideTextRun) parser.text?.let { text.append(it) }
                XmlPullParser.END_TAG -> if (localNameOf(parser) == "t") insideTextRun = false
                else -> Unit
            }
            event = parser.next()
        }
        return text.toString().trim().take(limit)
    }

    /** 标签本地名：去掉 `w:` 之类的命名空间前缀。 */
    private fun localNameOf(parser: XmlPullParser): String =
        parser.name?.substringAfterLast(':').orEmpty()

    /**
     * DOCX 抽正文（纯逻辑，输入任意流）：zip 里找 [DOCX_DOCUMENT_ENTRY]，
     * 找不到条目或解析失败返回 null；只读该条目，文档内嵌图片等资源不进内存。
     */
    internal fun docxTextFrom(stream: InputStream, limit: Int = MAX_DOCUMENT_CHARS): String? = runCatching {
        var xml: String? = null
        ZipInputStream(stream).use { zip ->
            while (xml == null) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name == DOCX_DOCUMENT_ENTRY) {
                    xml = zip.readBytes().toString(Charsets.UTF_8)
                }
            }
        }
        xml?.let { docxText(it, limit) }
    }.getOrNull()

    /** 页预算（纯逻辑）：从首页起截断到 [maxPages] 页（默认 [MAX_PDF_PAGES]，全力档预算）。 */
    internal fun pdfPageBudget(totalPages: Int, maxPages: Int = MAX_PDF_PAGES): Int =
        totalPages.coerceIn(0, maxPages)

    /**
     * PDF 页图嵌入输入：从首页起渲染，最多 [maxPages] 页，页图降采样到 [maxEdge] 以内后编码 JPEG
     * （每页即一张 InputData.Image）；密码/损坏/不可定位流一律返回空清单。
     * 默认 [MAX_PDF_PAGES] / [MAX_IMAGE_EDGE]（全力档预算）。
     */
    fun pdfPageImages(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
        maxPages: Int = MAX_PDF_PAGES,
        maxEdge: Int = MAX_IMAGE_EDGE,
    ): List<ByteArray> =
        runCatching {
            val descriptor = openFileDescriptor(context, candidate) ?: return@runCatching emptyList()
            descriptor.use { handle ->
                val renderer = PdfRenderer(handle)
                try {
                    val pages = mutableListOf<ByteArray>()
                    val count = pdfPageBudget(renderer.pageCount, maxPages)
                    for (index in 0 until count) {
                        val page = renderer.openPage(index)
                        try {
                            val bitmap = renderPdfPage(page, maxEdge)
                            try {
                                pages += bitmap.toJpeg(85)
                            } finally {
                                bitmap.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                    pages
                } finally {
                    renderer.close()
                }
            }
        }.getOrDefault(emptyList())

    /** 页渲染为位图：按最长边 [maxEdge] 降采样，避免大开本页面占满内存。 */
    private fun renderPdfPage(page: PdfRenderer.Page, maxEdge: Int): Bitmap {
        val largest = maxOf(page.width, page.height).coerceAtLeast(1)
        val scale = maxEdge.toFloat() / largest
        val width = if (scale < 1f) (page.width * scale).toInt().coerceAtLeast(1) else page.width
        val height = if (scale < 1f) (page.height * scale).toInt().coerceAtLeast(1) else page.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // PDF 页默认无底色，先铺白再渲染，避免透明背景编成黑图。
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    /** 解码任意音频为 16kHz 单声道 16-bit PCM；编码不支持或无音轨返回 null。 */
    private fun decodeMono16kPcm(context: Context, candidate: MediaSourceScanner.Candidate): ShortArray? {
        val extractor = MediaExtractor()
        try {
            setDataSource(extractor, context, candidate)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val initChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            val initSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, AUDIO_SAMPLE_RATE).coerceAtLeast(1)
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                return drainDecoder(decoder, extractor, initChannels, initSampleRate)
            } finally {
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * 解码主循环：喂输入、收输出，逐块做声道混合与重采样后累积到 16kHz 单声道缓冲；
     * 到时长预算、EOS 或空转超限即停。解码输出非 16-bit PCM 视为不支持，返回 null。
     */
    private fun drainDecoder(
        decoder: MediaCodec,
        extractor: MediaExtractor,
        initChannels: Int,
        initSampleRate: Int,
    ): ShortArray? {
        val maxSamples = (MAX_AUDIO_MS * AUDIO_SAMPLE_RATE / 1000L).toInt()
        val output = ShortArray(maxSamples)
        var outputCount = 0
        var channels = initChannels
        var sampleRate = initSampleRate
        var inputDone = false
        var idleSpins = 0
        val info = MediaCodec.BufferInfo()
        while (true) {
            if (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return null
                    val size = extractor.readSampleData(inputBuffer, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    idleSpins = 0
                }
            }
            val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
            when {
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate).coerceAtLeast(1)
                    // 只接受 16-bit PCM 输出（浮点/8bit PCM 等一律按不支持处理）。
                    if (format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        != AudioFormat.ENCODING_PCM_16BIT
                    ) {
                        return null
                    }
                }
                outputIndex >= 0 -> {
                    val outputBuffer = decoder.getOutputBuffer(outputIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        outputBuffer.get(bytes)
                        val converted = resamplePcm(
                            pcmToMono(bytesToShorts(bytes), channels),
                            sampleRate,
                            AUDIO_SAMPLE_RATE,
                        )
                        val take = minOf(maxSamples - outputCount, converted.size)
                        if (take > 0) {
                            System.arraycopy(converted, 0, output, outputCount, take)
                            outputCount += take
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    idleSpins = 0
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || outputCount >= maxSamples) {
                        return output.copyOf(outputCount)
                    }
                }
                else -> {
                    // 输入喂不进、输出也取不到：连续空转到上限即按当前已解码内容返回，避免卡死整轮索引。
                    idleSpins += 1
                    if (idleSpins > CODEC_MAX_IDLE_SPINS) return output.copyOf(outputCount)
                }
            }
        }
    }

    /** 小端字节序 → 16-bit 样本数组。 */
    private fun bytesToShorts(bytes: ByteArray): ShortArray =
        ShortArray(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
        }

    /** 声道混合（纯逻辑）：多声道按声道平均为单声道；单声道原样返回。 */
    internal fun pcmToMono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        val frames = samples.size / channels
        return ShortArray(frames) { frame ->
            var sum = 0L
            for (channel in 0 until channels) sum += samples[frame * channels + channel]
            (sum / channels).toInt().toShort()
        }
    }

    /**
     * 线性插值重采样（纯逻辑）：任意采样率 → 目标采样率（如 44.1k/48k → 16k）。
     * 逐块独立转换，块间接缝误差小于一个样本，对检索精度无影响。
     */
    internal fun resamplePcm(samples: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        require(fromRate > 0 && toRate > 0) { "采样率必须为正" }
        if (samples.isEmpty() || fromRate == toRate) return samples
        val outCount = ((samples.size.toLong() * toRate + fromRate / 2) / fromRate).toInt().coerceAtLeast(1)
        return ShortArray(outCount) { index ->
            val position = index.toDouble() * fromRate / toRate
            val lower = position.toInt().coerceAtMost(samples.size - 1)
            val upper = (lower + 1).coerceAtMost(samples.size - 1)
            val fraction = position - lower
            kotlin.math.round(samples[lower] * (1 - fraction) + samples[upper] * fraction).toInt().toShort()
        }
    }

    /** 时长硬预算（纯逻辑）：只保留前 [MAX_AUDIO_MS] 的样本。 */
    internal fun capToBudget(samples: ShortArray, sampleRate: Int): ShortArray {
        val maxSamples = (MAX_AUDIO_MS * sampleRate / 1000L).toInt()
        return if (samples.size <= maxSamples) samples else samples.copyOf(maxSamples)
    }

    /** 30s 窗口切片（纯逻辑）：按 [windowMs] 切，尾窗不足整窗也保留。 */
    internal fun splitWindows(
        samples: ShortArray,
        sampleRate: Int,
        windowMs: Long = AUDIO_WINDOW_MS,
    ): List<ShortArray> {
        if (samples.isEmpty()) return emptyList()
        val windowSamples = (windowMs * sampleRate / 1000L).toInt().coerceAtLeast(1)
        val windows = mutableListOf<ShortArray>()
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + windowSamples, samples.size)
            windows += samples.copyOfRange(start, end)
            start = end
        }
        return windows
    }

    /**
     * 16-bit PCM 封装 WAV（纯逻辑）：44 字节标准 RIFF 头 + 小端样本。
     * 即 LiteRT-LM `InputData.Audio` 唯一接受的入参形态（裸 PCM 不被接受）。
     */
    internal fun wavBytes(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArrayOutputStream(44 + dataSize)
        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun le16(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
        fun le32(value: Int) {
            le16(value and 0xffff)
            le16((value shr 16) and 0xffff)
        }
        ascii("RIFF")
        le32(36 + dataSize)
        ascii("WAVE")
        ascii("fmt ")
        le32(16) // fmt 块长度
        le16(1) // PCM 编码
        le16(1) // 单声道
        le32(sampleRate)
        le32(sampleRate * 2) // 字节率：采样率 × 声道数 × 位深 / 8
        le16(2) // 块对齐：声道数 × 位深 / 8
        le16(16) // 位深
        ascii("data")
        le32(dataSize)
        samples.forEach { sample ->
            le16(sample.toInt() and 0xffff)
        }
        return out.toByteArray()
    }

    /** 全文件 SHA-256，备份导出后重装重关联的依据。 */
    fun contentHash(context: Context, candidate: MediaSourceScanner.Candidate): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            openStream(context, candidate)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            } ?: return@runCatching null
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()

    /**
     * 生成并缓存缩略图，返回**文件名**（寻址见 [MediaThumbnailStore]，库里不再存绝对路径）。
     * 失败返回 null 且留日志（带 kind/mime/sourceKey），不影响该条目的索引。
     */
    fun saveThumbnail(context: Context, candidate: MediaSourceScanner.Candidate): String? =
        runCatching {
            // 音频/文档本来就没有缩略图，直接返回 null，不当作失败记日志。
            if (candidate.kind != MediaKind.IMAGE && candidate.kind != MediaKind.VIDEO) {
                return@runCatching null
            }
            val bitmap = when (candidate.kind) {
                MediaKind.IMAGE -> decodeSampled(context, candidate, MAX_THUMB_EDGE)
                else -> videoThumbnail(context, candidate)
            } ?: run {
                // 解码/取帧返回 null（文件损坏、格式不支持、无可用帧）也是失败分支，必须留证据。
                Log.w(
                    TAG,
                    "缩略图生成失败（无可解码图像） source=${candidate.sourceKey} " +
                        "kind=${candidate.kind.wire} mime=${candidate.mime}",
                )
                return@runCatching null
            }
            val bytes = bitmap.toJpeg(75)
            bitmap.recycle()
            MediaThumbnailStore.write(context, candidate.sourceKey, bytes)
        }.onFailure { failure ->
            Log.w(
                TAG,
                "缩略图生成失败 source=${candidate.sourceKey} kind=${candidate.kind.wire} " +
                    "mime=${candidate.mime} type=${failure.javaClass.simpleName}",
            )
        }.getOrNull()

    /**
     * 视频缩略图取帧：固定 1 秒在时长 <1s 的视频上必然越界，会出现「有向量无缩略图」。
     * 改为 `min(1s, duration/2)`，再依次回退首帧、均匀抽帧的首个时刻。
     */
    private fun videoThumbnail(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            setDataSource(retriever, context, candidate)
            val durationMs = candidate.durationMs
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val preferredUs = durationMs
                ?.takeIf { it > 0 }
                ?.let { minOf(1_000L, it / 2) * 1_000 }
                ?: 1_000_000L
            val frame = retriever.getFrameAtTime(preferredUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(0)
                ?: durationMs?.takeIf { it > 0 }?.let { duration ->
                    frameTimestamps(duration).firstOrNull()?.let { retriever.getFrameAtTime(it * 1_000) }
                }
            frame?.scaleToEdge(MAX_THUMB_EDGE)
        } catch (failure: Throwable) {
            Log.w(
                TAG,
                "视频缩略图取帧失败 source=${candidate.sourceKey} mime=${candidate.mime} " +
                    "type=${failure.javaClass.simpleName}",
            )
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * 按命中时刻取一帧 JPEG（已按 [MAX_THUMB_EDGE] 缩放），供检索结果把视频预览对齐到命中时刻。
     *
     * 取帧策略：`OPTION_CLOSEST_SYNC` 优先（只取同步帧，快）；失败退 `OPTION_CLOSEST`（精确但慢）；
     * 两者都拿不到返回 null，由调用方回退到索引封面图，不在这里抛错。
     * 阻塞且不可中断，必须在 IO 线程调用（渲染端用现有信号量限并发）。
     */
    fun momentFrameJpeg(
        context: Context,
        sourceKey: String,
        contentUri: String?,
        filePath: String?,
        timeMs: Long,
    ): ByteArray? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            // 取帧只需要定位数据源：构造最小 Candidate 复用现有 setDataSource（路径优先，其次 content uri）。
            setDataSource(
                retriever,
                context,
                MediaSourceScanner.Candidate(
                    sourceKey = sourceKey,
                    kind = MediaKind.VIDEO,
                    displayName = "",
                    mime = "",
                    sizeBytes = 0,
                    modifiedAt = 0,
                    contentUri = contentUri,
                    filePath = filePath,
                    durationMs = null,
                    width = null,
                    height = null,
                ),
            )
            val timeUs = timeMs.coerceAtLeast(0L) * 1_000L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: return@runCatching null
            val scaled = frame.scaleToEdge(MAX_THUMB_EDGE)
            val jpeg = scaled.toJpeg(75)
            if (scaled !== frame) frame.recycle()
            scaled.recycle()
            jpeg
        } finally {
            runCatching { retriever.release() }
        }
    }.onFailure { failure ->
        Log.w(
            TAG,
            "命中时刻取帧失败 source=$sourceKey timeMs=$timeMs type=${failure.javaClass.simpleName}",
        )
    }.getOrNull()

    private fun decodeSampled(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
        maxEdge: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeOnce(context, candidate, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = decodeOnce(context, candidate, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return decoded.scaleToEdge(maxEdge)
    }

    /** 优先走内容流（MediaStore/SAF），本地文件直接按路径解码。 */
    private fun decodeOnce(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
        options: BitmapFactory.Options,
    ): Bitmap? =
        openStream(context, candidate)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: candidate.filePath?.let { BitmapFactory.decodeFile(it, options) }

    private fun Bitmap.scaleToEdge(maxEdge: Int): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= maxEdge) return this
        val scale = maxEdge.toFloat() / largest
        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
        return scaled
    }

    private fun Bitmap.toJpeg(quality: Int): ByteArray =
        ByteArrayOutputStream().also { output ->
            compress(Bitmap.CompressFormat.JPEG, quality, output)
        }.toByteArray()

    private fun setDataSource(
        retriever: MediaMetadataRetriever,
        context: Context,
        candidate: MediaSourceScanner.Candidate,
    ) {
        val path = candidate.filePath
        if (path != null) {
            retriever.setDataSource(path)
        } else {
            retriever.setDataSource(context, Uri.parse(candidate.contentUri))
        }
    }

    private fun setDataSource(
        extractor: MediaExtractor,
        context: Context,
        candidate: MediaSourceScanner.Candidate,
    ) {
        val path = candidate.filePath
        if (path != null) {
            extractor.setDataSource(path)
        } else {
            extractor.setDataSource(context, Uri.parse(candidate.contentUri), null)
        }
    }

    /** PDF 渲染要求可定位文件描述符：本地路径直接打开，内容 URI 走 contentResolver。 */
    private fun openFileDescriptor(
        context: Context,
        candidate: MediaSourceScanner.Candidate,
    ): ParcelFileDescriptor? {
        val path = candidate.filePath
        return if (path != null) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            candidate.contentUri?.let { context.contentResolver.openFileDescriptor(Uri.parse(it), "r") }
        }
    }

    private fun openStream(context: Context, candidate: MediaSourceScanner.Candidate) =
        candidate.filePath?.let { File(it).takeIf(File::isFile)?.inputStream() }
            ?: candidate.contentUri?.let { context.contentResolver.openInputStream(Uri.parse(it)) }
}
