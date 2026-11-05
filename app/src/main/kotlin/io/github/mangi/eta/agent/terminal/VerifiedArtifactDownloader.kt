package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.ResumableDownload
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient

internal data class VerifiedArtifact(
    val id: String,
    val version: String,
    val fileName: String,
    val url: String,
    val sha256: String,
    val sizeBytes: Long,
    /** 在官方地址前尝试的镜像；镜像只改变传输路径，完整大小和摘要校验仍以制品清单为准。 */
    val preferredUrls: List<String> = emptyList(),
    val fallbackUrls: List<String> = emptyList(),
)

internal class VerifiedArtifactDownloader(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val logger: AgentLogger = AndroidAgentLogger,
) {
    suspend fun download(
        artifact: VerifiedArtifact,
        target: File,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Boolean {
        target.parentFile?.mkdirs()
        if (verify(artifact, target)) return true
        target.delete()
        val candidates = (artifact.preferredUrls + artifact.url + artifact.fallbackUrls).distinct()
        // `.part` 与 `.meta` 和目标同目录（cacheDir），取消/失败时保留，下次命中同一 URL 才续传。
        val part = File(target.path + ".part")
        for ((attempt, url) in candidates.withIndex()) {
            if (downloadOnce(artifact, target, part, url, attempt + 1, onProgress)) return true
        }
        return false
    }

    private suspend fun downloadOnce(
        artifact: VerifiedArtifact,
        target: File,
        part: File,
        url: String,
        attempt: Int,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val valid = try {
            ResumableDownload.download(
                client = httpClient,
                url = url,
                part = part,
                expectedBytes = artifact.sizeBytes,
                onProgress = { bytesRead, _ -> onProgress(bytesRead, artifact.sizeBytes) },
            )
            if (!verify(artifact, part)) {
                // 续传拼出的字节过不了摘要：丢弃断点，避免下次继续拼坏文件。
                val rejectedBytes = part.length()
                ResumableDownload.discard(part)
                logger.info(
                    "Verified artifact action=download id=${artifact.id} attempt=$attempt " +
                        "outcome=rejected bytes=$rejectedBytes",
                )
                false
            } else {
                val committed = ResumableDownload.commit(part, target)
                logger.info(
                    "Verified artifact action=download id=${artifact.id} attempt=$attempt " +
                        "outcome=${if (committed) "succeeded" else "failed"} bytes=${artifact.sizeBytes}",
                )
                committed
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            logger.warn(
                "Verified artifact action=download id=${artifact.id} attempt=$attempt outcome=failed " +
                    "errorType=${throwable.safeLogType()}",
            )
            false
        }
        return valid
    }

    fun verify(artifact: VerifiedArtifact, file: File): Boolean {
        if (!file.isFile || file.length() != artifact.sizeBytes) return false
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().toHexString() == artifact.sha256
        }.getOrDefault(false)
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.MINUTES)
                .build()
    }
}

private fun ByteArray.toHexString(): String =
    joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
