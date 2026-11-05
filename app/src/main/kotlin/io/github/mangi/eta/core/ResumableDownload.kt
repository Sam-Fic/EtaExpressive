package io.github.mangi.eta.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 可续传下载（HTTP Range）。
 *
 * 约定：
 * - `.part` 与 `.meta` 放在缓存目录；调用方校验通过后再改名成正式文件，半截文件不会
 *   变成“看起来已下载”。
 * - 取消不删 `.part`；只有调用方显式清理或缓存超限淘汰才删。
 * - `If-Range` 只用强 ETag 或 Last-Modified，弱 ETag 不参与。
 * - 实测 GitHub release / HuggingFace 会忽略 If-Range 直接回 206，Alpine 镜像遇到
 *   If-Range 会回 200 整包。因此除 If-Range 外，还必须用响应头的 ETag /
 *   Last-Modified / Content-Range 总长做二次校验，任一不匹配就丢弃断点重下。
 */
internal object ResumableDownload {

    /** 单个下载目录的 `.part` 总量上限；超限按最久未用淘汰。 */
    const val MAX_CACHE_BYTES: Long = 2L * 1024L * 1024L * 1024L

    private const val META_SUFFIX = ".meta"
    private const val PROGRESS_INTERVAL_BYTES = 256L * 1024L
    private const val ACTIVE_GRACE_MILLIS = 10L * 60L * 1000L

    private val locks = ConcurrentHashMap<String, Mutex>()

    data class Meta(
        val url: String,
        val etag: String?,
        val lastModified: String?,
        val totalBytes: Long?,
    )

    /** 非 2xx 响应；调用方按 code 区分鉴权失败等。 */
    class HttpFailure(val code: Int) : IOException("Download HTTP $code")

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    /**
     * 下载到 [part]。
     *
     * @param expectedBytes 制品清单里的固定大小；未知传 null。
     * @param maxBytes 流式上限，超出即中止（防止恶意/异常服务端无限写盘）。
     * @return 是否从已有断点继续。
     */
    suspend fun download(
        client: OkHttpClient,
        url: String,
        part: File,
        expectedBytes: Long? = null,
        maxBytes: Long? = null,
        headers: Map<String, String> = emptyMap(),
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): Boolean = withContext(Dispatchers.IO) {
        val mutex = locks.getOrPut(part.absolutePath) { Mutex() }
        mutex.withLock {
            downloadLocked(client, url, part, expectedBytes, maxBytes, headers, onProgress)
        }
    }

    /** 模型/语音这类最终文件不在缓存目录时，`.part` 统一落在 `<cacheDir>/downloads/`。 */
    fun partFile(cacheDir: File, target: File): File {
        val root = File(cacheDir, "downloads").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(target.absolutePath.toByteArray())
        val name = digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
        return File(root, "$name.part")
    }

    /** 已有可续传的字节数；URL 对不上（换了源/版本）时返回 0。 */
    fun partialBytes(part: File, url: String): Long {
        val meta = readMeta(part) ?: return 0L
        if (meta.url != url) return 0L
        return part.length().takeIf { it > 0L } ?: 0L
    }

    /** 校验通过后原子改名；`.meta` 随之删除。 */
    fun commit(part: File, target: File): Boolean {
        target.delete()
        var moved = part.renameTo(target)
        if (!moved) {
            // cacheDir 与 filesDir 不在同一文件系统时 rename 会失败，退回拷贝再删；
            // 拷贝失败则保留 `.part`，下次仍可重试。
            val copied = runCatching { part.copyTo(target, overwrite = true) }.getOrNull()
            moved = copied != null && copied.length() == part.length()
            if (moved) part.delete() else target.delete()
        }
        if (moved) metaFile(part).delete()
        return moved
    }

    /** 用户显式删除模型/清理缓存时调用；取消下载不要调它。 */
    fun discard(part: File) {
        part.delete()
        metaFile(part).delete()
    }

    private suspend fun downloadLocked(
        client: OkHttpClient,
        url: String,
        part: File,
        expectedBytes: Long?,
        maxBytes: Long?,
        headers: Map<String, String>,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Boolean {
        part.parentFile?.mkdirs()
        prune(part.parentFile)

        var meta = readMeta(part)
        var have = part.length()
        val reusable = meta != null &&
            meta.url == url &&
            (expectedBytes == null || have <= expectedBytes)
        if (!reusable) {
            discard(part)
            meta = null
            have = 0L
        }
        val knownTotal = expectedBytes ?: meta?.totalBytes
        if (have > 0L) onProgress(have, knownTotal)
        if (have > 0L && knownTotal != null && have == knownTotal) {
            // 上次已下完但还没来得及改名（进程被杀）；交给调用方做摘要/大小校验后提交。
            return true
        }

        var resumed = have > 0L
        var attempt = 0
        while (true) {
            var retryWithoutIfRange = false
            var restart = false
            var done = false

            val validator = if (attempt == 0 && have > 0L) ifRangeValue(meta) else null
            val builder = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .get()
            headers.forEach { (name, value) -> builder.header(name, value) }
            if (have > 0L) builder.header("Range", "bytes=$have-")
            if (validator != null) builder.header("If-Range", validator)

            client.newCall(builder.build()).execute().use { response ->
                if (url.startsWith("https") && response.request.url.scheme != "https") {
                    throw IOException("Insecure download redirect")
                }
                when {
                    response.code == 206 -> {
                        val range = parseContentRange(response.header("Content-Range"))
                        val total = range?.total ?: expectedBytes
                        val valid = range != null &&
                            range.start == have &&
                            validatorMatches(response, meta) &&
                            consistentTotal(range.total, meta?.totalBytes, expectedBytes) &&
                            withinLimit(total, maxBytes)
                        if (!valid) {
                            restart = true
                        } else {
                            writeMeta(
                                part,
                                Meta(url, strongEtag(response.header("ETag")), response.header("Last-Modified"), total),
                            )
                            have = stream(response, part, have, total, maxBytes, onProgress)
                            done = true
                        }
                    }
                    response.code == 200 -> {
                        val total = response.body.contentLength()
                            .takeIf { it > 0L }
                            ?: expectedBytes
                        if (attempt == 0 && have > 0L && validatorMatches(response, meta)) {
                            // 服务端没按 If-Range 回 200 的范围语义（Alpine 镜像实测如此），
                            // 但文件没变：丢掉 If-Range 再试一次，保留已下载的 .part。
                            retryWithoutIfRange = true
                        } else {
                            if (!withinLimit(total, maxBytes)) throw IOException("Download exceeds limit")
                            part.delete()
                            writeMeta(
                                part,
                                Meta(url, strongEtag(response.header("ETag")), response.header("Last-Modified"), total),
                            )
                            have = stream(response, part, 0L, total, maxBytes, onProgress)
                            resumed = false
                            done = true
                        }
                    }
                    response.code == 416 -> restart = true
                    else -> throw HttpFailure(response.code)
                }
            }

            when {
                done -> return resumed
                retryWithoutIfRange -> attempt = 1
                restart -> {
                    // 已经从头重启过一次还是拿不到合法 206（服务器不给 Range/总长对不上），
                    // 再循环只会无限重试同一故障：丢弃断点后报错，下次点击从头整包下载。
                    if (attempt > 0) {
                        discard(part)
                        throw IOException("Download cannot be resumed at $have")
                    }
                    discard(part)
                    meta = null
                    have = 0L
                    resumed = false
                    attempt = 1
                }
                else -> error("Download stalled")
            }
        }
    }

    private suspend fun stream(
        response: Response,
        part: File,
        start: Long,
        total: Long?,
        maxBytes: Long?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long {
        var written = start
        var lastReported = start
        FileOutputStream(part, start > 0L).buffered().use { output ->
            response.body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count
                    if (maxBytes != null) {
                        check(written <= maxBytes) { "Download exceeds limit" }
                    }
                    output.write(buffer, 0, count)
                    if (written - lastReported >= PROGRESS_INTERVAL_BYTES) {
                        lastReported = written
                        onProgress(written, total)
                    }
                }
            }
        }
        if (total != null && written != total) {
            throw IOException("Download truncated at $written of $total")
        }
        onProgress(written, total)
        return written
    }

    private fun ifRangeValue(meta: Meta?): String? =
        meta?.etag?.takeUnless { it.startsWith("W/", ignoreCase = true) }
            ?: meta?.lastModified

    private fun strongEtag(etag: String?): String? =
        etag?.takeUnless { it.startsWith("W/", ignoreCase = true) }

    private fun validatorMatches(response: Response, meta: Meta?): Boolean {
        if (meta == null) return false
        val etag = strongEtag(response.header("ETag"))
        val lastModified = response.header("Last-Modified")
        return (meta.etag != null && etag != null && meta.etag == etag) ||
            (meta.lastModified != null && lastModified != null && meta.lastModified == lastModified)
    }

    private fun consistentTotal(rangeTotal: Long?, metaTotal: Long?, expected: Long?): Boolean {
        if (rangeTotal == null) return expected == null
        if (expected != null && rangeTotal != expected) return false
        if (metaTotal != null && rangeTotal != metaTotal) return false
        return true
    }

    private fun withinLimit(total: Long?, maxBytes: Long?): Boolean =
        total == null || maxBytes == null || total <= maxBytes

    private fun parseContentRange(value: String?): ContentRange? {
        val match = CONTENT_RANGE.find(value ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeIf { it != "*" }?.toLongOrNull()
        return ContentRange(start, end, total)
    }

    private fun metaFile(part: File): File = File(part.path + META_SUFFIX)

    private fun readMeta(part: File): Meta? = runCatching {
        val file = metaFile(part)
        if (!file.isFile) return null
        val properties = Properties()
        file.inputStream().use { stream -> properties.load(stream) }
        val url = properties.getProperty("url") ?: return null
        Meta(
            url = url,
            etag = properties.getProperty("etag"),
            lastModified = properties.getProperty("lastModified"),
            totalBytes = properties.getProperty("totalBytes")?.toLongOrNull(),
        )
    }.getOrNull()

    private fun writeMeta(part: File, meta: Meta) {
        val file = metaFile(part)
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty("url", meta.url)
            meta.etag?.let { setProperty("etag", it) }
            meta.lastModified?.let { setProperty("lastModified", it) }
            meta.totalBytes?.let { setProperty("totalBytes", it.toString()) }
        }
        file.outputStream().use { properties.store(it, null) }
    }

    /** 超限时按最久未用淘汰；10 分钟内改动过的 `.part` 视为仍在下载，不动。 */
    private fun prune(directory: File?) {
        if (directory == null || !directory.isDirectory) return
        val parts = directory.listFiles { file -> file.isFile && file.name.endsWith(".part") }
            ?: return
        var total = parts.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        val now = System.currentTimeMillis()
        parts.sortedBy { it.lastModified() }.forEach { part ->
            if (total <= MAX_CACHE_BYTES) return@forEach
            if (now - part.lastModified() < ACTIVE_GRACE_MILLIS) return@forEach
            total -= part.length()
            part.delete()
            metaFile(part).delete()
        }
    }

    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
}
