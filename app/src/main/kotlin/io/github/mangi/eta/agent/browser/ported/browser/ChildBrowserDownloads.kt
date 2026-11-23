// SPDX-License-Identifier: GPL-3.0-only
// Part of the OpenMinis browser port package (agent/browser/ported); provenance and
// adaptation scope are tracked in third_party/openminis-browser/PORT_STATUS.md.
package io.github.mangi.eta.agent.browser.ported.browser

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One bounded, non-blocking download budget shared by a research child pool's
 * [ChildBrowserDownloads] saver and every tab that reads a page blob.
 *
 * At most three permits exist. A permit is acquired BEFORE the work starts (for
 * a blob: before the injected page fetch/read/decode; for an HTTP download
 * before the connection opens) and held until the native save has finished, so
 * the HTTP fetch, the page-triggered HTTP download and the whole blob
 * read→decode→save chain all draw from the SAME three slots. A full budget
 * refuses the new request ([ChildBrowserDownloads.Refused] "CHILD_DOWNLOAD_BUSY")
 * instead of queueing unbounded work, and [Lease.release] is idempotent so a
 * duplicate release can never grow the capacity.
 */
internal class ChildDownloadBudget {
    private val slots = Semaphore(3)

    /** A held slot; [release] returns it exactly once even when called again. */
    class Lease internal constructor(private val owner: ChildDownloadBudget, private val slots: Semaphore) {
        private val released = AtomicBoolean(false)
        private val saveClaimed = AtomicBoolean(false)
        // A held permit is valid for exactly one save in its own task budget.
        fun claimForSave(budget: ChildDownloadBudget): Boolean =
            owner === budget && !released.get() && saveClaimed.compareAndSet(false, true)
        fun release() { if (released.compareAndSet(false, true)) slots.release() }
    }

    /** Claim a slot without blocking, or null when all three are held. */
    fun tryAcquire(): Lease? = if (slots.tryAcquire()) Lease(this, slots) else null
}

/** Bounded task-owned IO. No URL, headers or exception body are returned as diagnostics. */
internal class ChildBrowserDownloads(
    private val directory: () -> File,
    private val cookies: (String) -> String?,
    private val budget: ChildDownloadBudget = ChildDownloadBudget(),
) {
    data class Saved(val file: File, val size: Long)
    class Refused(val code: String) : IllegalStateException(code)
    private val generation = AtomicLong()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    @Volatile private var closed = false

    fun cancelAll() {
        generation.incrementAndGet()
        connections.values.forEach { runCatching { it.disconnect() } }
    }
    fun close() { closed = true; cancelAll() }

    private fun checked(raw: String): URI {
        val value = runCatching { URI(raw) }.getOrNull()
        if (value == null || value.scheme?.lowercase() !in setOf("http", "https") ||
            value.host.isNullOrBlank() || value.rawUserInfo != null || raw.length > 8192 ||
            raw.any { it.code < 32 }) throw Refused("CHILD_DOWNLOAD_URL_NOT_ALLOWED")
        return value
    }
    private suspend fun checkActive(epoch: Long) {
        currentCoroutineContext().ensureActive()
        if (closed || generation.get() != epoch) throw kotlinx.coroutines.CancellationException()
    }

    suspend fun fetch(raw: String, userAgent: String? = null, declaredLength: Long = -1): Saved {
        if (declaredLength > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
        val initial = checked(raw)
        return save(budget.tryAcquire() ?: throw Refused("CHILD_DOWNLOAD_BUSY")) { file, epoch ->
            var target = initial
            for (redirect in 0..5) {
                checkActive(epoch)
                val key = UUID.randomUUID().toString()
                val connection = target.toURL().openConnection() as HttpURLConnection
                connections[key] = connection
                try {
                    checkActive(epoch)
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    connection.instanceFollowRedirects = false
                    userAgent?.takeIf { it.length < 2048 && it.none { c -> c == '\r' || c == '\n' } }
                        ?.let { connection.setRequestProperty("User-Agent", it) }
                    // Re-resolve for each redirect target; never forward the old host header.
                    cookies(target.toString())?.let { connection.setRequestProperty("Cookie", it) }
                    val status = connection.responseCode
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        if (redirect == 5) throw Refused("CHILD_DOWNLOAD_REDIRECT_LIMIT")
                        val location = connection.getHeaderField("Location")
                            ?: throw Refused("CHILD_DOWNLOAD_REDIRECT_INVALID")
                        val next = checked(target.resolve(location).toString())
                        if (target.scheme.equals("https", true) && !next.scheme.equals("https", true))
                            throw Refused("CHILD_DOWNLOAD_DOWNGRADE_REFUSED")
                        target = next
                        continue
                    }
                    if (status !in 200..299) throw Refused("CHILD_DOWNLOAD_HTTP_FAILURE")
                    if (connection.contentLengthLong > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
                    connection.inputStream.use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var copied = 0L
                            while (true) {
                                checkActive(epoch)
                                val count = input.read(buffer)
                                if (count < 0) break
                                checkActive(epoch)
                                if (copied + count > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
                                output.write(buffer, 0, count)
                                copied += count
                            }
                        }
                    }
                    return@save
                } finally {
                    connections.remove(key)
                    connection.disconnect()
                }
            }
            throw Refused("CHILD_DOWNLOAD_REDIRECT_LIMIT")
        }
    }

    /**
     * Persist decoded blob bytes. When [lease] is supplied the caller already
     * holds one budget slot for the page fetch/read/decode that produced these
     * bytes, so no second slot is acquired; the held slot is released only after
     * the native save below finishes. When [lease] is null the slot is claimed
     * here (the data: URL path, whose payload is already inline).
     */
    suspend fun blob(bytes: ByteArray, lease: ChildDownloadBudget.Lease? = null): Saved {
        if (lease == null && bytes.size.toLong() > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
        val held = lease ?: (budget.tryAcquire() ?: throw Refused("CHILD_DOWNLOAD_BUSY"))
        return save(held) { file, epoch ->
            if (bytes.size.toLong() > MAX_BYTES) throw Refused("CHILD_DOWNLOAD_TOO_LARGE")
            file.outputStream().use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    checkActive(epoch)
                    val count = minOf(8192, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
            }
        }
    }

    private suspend fun save(lease: ChildDownloadBudget.Lease, write: suspend (File, Long) -> Unit): Saved {
        // Never consume/release a foreign, already released or already used permit.
        // A duplicate concurrent invocation must not release the first save's slot.
        if (!lease.claimForSave(budget)) throw Refused("CHILD_DOWNLOAD_LEASE_INVALID")
        val epoch = generation.get()
        try {
            return withContext(Dispatchers.IO) {
                checkActive(epoch)
                val folder = directory()
                if (!folder.isDirectory && !folder.mkdirs()) throw Refused("CHILD_DOWNLOAD_DIRECTORY_UNAVAILABLE")
                val temporary = File.createTempFile("eta-child-", ".part", folder)
                var destination: File? = null
                var published = false
                try {
                    write(temporary, epoch)
                    checkActive(epoch)
                    val reserved = File(folder, "download_${UUID.randomUUID()}.bin")
                    if (!reserved.createNewFile()) throw Refused("CHILD_DOWNLOAD_FILE_CONFLICT")
                    destination = reserved
                    if (!temporary.renameTo(reserved)) throw Refused("CHILD_DOWNLOAD_PUBLISH_FAILED")
                    checkActive(epoch)
                    val result = Saved(reserved, reserved.length())
                    published = true
                    result
                } finally {
                    temporary.delete()
                    if (!published) destination?.delete()
                }
            }
        } finally { lease.release() }
    }

    companion object { const val MAX_BYTES = 32L * 1024 * 1024 }
}
