package io.github.mangi.eta.agent.mobileactions

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.ResumableDownload
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 端侧设备动作模型的按需下载与校验，存放于 App 私有 no-backup 目录，不入 APK。
 *
 * 与 [io.github.mangi.eta.agent.media.index.EmbeddingModelStore] 同一套约定：
 * `<modelId>/<revision>` 分目录、逐文件字节数 + SHA-256 全量校验、`.part` 落盘后原子改名。
 * 差异点是多源可访问性不同：Hugging Face 仓库是 gated，需要用户提供访问令牌。
 */
internal object MobileActionsModelStore {
    /** 面向用户的失败原因；文案由 UI 侧映射，避免在此持有 Context 文案。 */
    enum class Failure { NETWORK, AUTH, CHECKSUM, NO_SPACE }

    /** 推理后端；NPU 需要额外 native 库，这里只提供 CPU / GPU。 */
    enum class Backend(val wire: String) {
        AUTO("auto"),
        GPU("gpu"),
        CPU("cpu");

        companion object {
            fun from(wire: String?): Backend = entries.firstOrNull { it.wire == wire } ?: AUTO
        }
    }

    data class State(
        val checking: Boolean = true,
        val ready: Boolean = false,
        val downloading: Boolean = false,
        val downloadedBytes: Long = 0,
        val failure: Failure? = null,
        val source: MobileActionsModelCatalog.Source = MobileActionsModelCatalog.source(null),
        val hasToken: Boolean = false,
    ) {
        val totalBytes: Long get() = MobileActionsModelCatalog.asset.bytes
    }

    private const val PREFS = "mobile_actions_model"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(
        State(source = MobileActionsModelCatalog.source(null)),
    )
    val state = mutable.asStateFlow()
    private var initialized = false
    private var download: Job? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 模型目录：`<modelId>/<revision>`，按内容版本存放以便校验与增量替换。 */
    fun directory(context: Context): File = File(
        context.applicationContext.noBackupFilesDir,
        "mobile-actions/${MobileActionsModelCatalog.MODEL_ID}/${MobileActionsModelCatalog.REVISION}",
    )

    /**
     * 已落盘的 `.litertlm` 路径（廉价检查：存在且字节数相符，不重算 SHA-256）。
     * 完整哈希校验在下载与 [initialize] 时完成，引擎启动路径不应为此多读 280 MB。
     */
    fun modelFile(context: Context): File? {
        val asset = MobileActionsModelCatalog.asset
        val file = File(directory(context), asset.name)
        return file.takeIf { it.isFile && it.length() == asset.bytes }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            val stored = prefs(app)
            val source = MobileActionsModelCatalog.source(
                MobileActionsModelCatalog.SourceId.from(stored.getString("source", null)),
            )
            val ready = withContext(Dispatchers.IO) { verify(directory(app)) }
            mutable.value = State(
                checking = false,
                ready = ready,
                source = source,
                hasToken = stored.getString("token", null)?.isNotBlank() == true,
            )
        }
    }

    /** 文件被外部改动/备份恢复后重新校验；取消进行中的下载，避免旧任务发布过期状态。 */
    @Synchronized
    fun refresh(context: Context) {
        download?.cancel()
        download = null
        initialized = false
        mutable.value = mutable.value.copy(
            checking = true,
            downloading = false,
            downloadedBytes = 0,
            failure = null,
        )
        initialize(context)
    }

    /** 切换下载源；下载进行中拒绝切换，避免同一目录混用两个源的字节。 */
    fun selectSource(context: Context, sourceId: MobileActionsModelCatalog.SourceId) {
        if (mutable.value.downloading) return
        val source = MobileActionsModelCatalog.source(sourceId)
        if (source.id == mutable.value.source.id) return
        val app = context.applicationContext
        prefs(app).edit().putString("source", source.id.wire).apply()
        mutable.value = mutable.value.copy(source = source, failure = null)
    }

    /** 保存 Hugging Face 访问令牌；空白视为清除。 */
    fun setToken(context: Context, token: String) {
        val app = context.applicationContext
        val trimmed = token.trim()
        prefs(app).edit().apply {
            if (trimmed.isEmpty()) remove("token") else putString("token", trimmed)
        }.apply()
        mutable.value = mutable.value.copy(hasToken = trimmed.isNotEmpty(), failure = null)
    }

    fun token(context: Context): String =
        prefs(context).getString("token", null)?.takeIf { it.isNotBlank() } ?: ""

    fun backend(context: Context): Backend =
        Backend.from(prefs(context).getString("backend", null))

    fun setBackend(context: Context, backend: Backend) {
        prefs(context).edit().putString("backend", backend.wire).apply()
    }

    fun cancelDownload() {
        download?.cancel()
    }

    /** 当前源已存在的 `.part` 字节数；>0 时入口文案应显示「继续下载」。 */
    fun partialBytes(context: Context): Long {
        val app = context.applicationContext
        val asset = MobileActionsModelCatalog.asset
        val target = File(directory(app), asset.name)
        return ResumableDownload.partialBytes(
            ResumableDownload.partFile(app.cacheDir, target),
            mutable.value.source.baseUrl + asset.name,
        )
    }

    /** 删除已下载的模型文件与断点；保留来源/后端/令牌偏好。 */
    fun delete(context: Context) {
        download?.cancel()
        download = null
        val app = context.applicationContext
        val asset = MobileActionsModelCatalog.asset
        scope.launch {
            withContext(Dispatchers.IO) {
                // 显式删除是唯一会清掉 `.part` 的入口；取消下载不删。
                val target = File(directory(app), asset.name)
                ResumableDownload.discard(ResumableDownload.partFile(app.cacheDir, target))
                directory(app).deleteRecursively()
            }
            mutable.value = mutable.value.copy(
                checking = false,
                ready = false,
                downloading = false,
                downloadedBytes = 0,
                failure = null,
            )
        }
    }

    fun download(context: Context) {
        val snapshot = mutable.value
        if (snapshot.checking || snapshot.downloading || snapshot.ready) return
        val app = context.applicationContext
        val source = snapshot.source
        val token = token(app)
        mutable.value = snapshot.copy(downloading = true, downloadedBytes = 0, failure = null)
        download = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val asset = MobileActionsModelCatalog.asset
                    val dir = directory(app).apply { mkdirs() }
                    val target = File(dir, asset.name)
                    if (!fullValid(target, asset)) {
                        target.delete()
                        // 先做空间检查：289 MB 的半截文件比下载失败更糟。
                        if (dir.usableSpace < asset.bytes) throw MissingSpace()
                        // `.part` 放缓存目录；取消/中断保留，下次同 URL 用 Range 续传。
                        val part = ResumableDownload.partFile(app.cacheDir, target)
                        val headers = buildMap {
                            if (source.requiresToken && token.isNotEmpty()) {
                                put("Authorization", "Bearer $token")
                            }
                        }
                        ResumableDownload.download(
                            client = AgentHttpClient.client,
                            url = source.baseUrl + asset.name,
                            part = part,
                            expectedBytes = asset.bytes,
                            headers = headers,
                            onProgress = { received, _ ->
                                withContext(Dispatchers.Main) {
                                    mutable.value = mutable.value.copy(downloadedBytes = received)
                                }
                            },
                        )
                        if (!fullValid(part, asset)) throw ChecksumMismatch()
                        ensureActive()
                        check(ResumableDownload.commit(part, target)) {
                            "Cannot commit verified model"
                        }
                    }
                    withContext(Dispatchers.Main) {
                        mutable.value = mutable.value.copy(
                            checking = false,
                            ready = true,
                            downloadedBytes = asset.bytes,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: ResumableDownload.HttpFailure) {
                mutable.value = mutable.value.copy(
                    failure = if (auth.code == 401 || auth.code == 403) Failure.AUTH else Failure.NETWORK,
                )
            } catch (space: MissingSpace) {
                mutable.value = mutable.value.copy(failure = Failure.NO_SPACE)
            } catch (checksum: ChecksumMismatch) {
                mutable.value = mutable.value.copy(failure = Failure.CHECKSUM)
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(failure = Failure.NETWORK)
            } finally {
                mutable.value = mutable.value.copy(downloading = false)
            }
        }
    }

    private class MissingSpace : Exception()
    private class ChecksumMismatch : Exception()

    private fun fullValid(file: File, asset: MobileActionsModelCatalog.Asset): Boolean = runCatching {
        if (!file.isFile || file.length() != asset.bytes) {
            false
        } else {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
        }
    }.getOrDefault(false)

    private fun verify(dir: File): Boolean {
        val asset = MobileActionsModelCatalog.asset
        return fullValid(File(dir, asset.name), asset)
    }

    /** 人类可读体积（保留一位小数），只用于设置页副标题。 */
    fun formatBytes(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1_000) {
            String.format(java.util.Locale.US, "%.2f GB", mb / 1_000.0)
        } else {
            String.format(java.util.Locale.US, "%.0f MB", mb)
        }
    }
}
