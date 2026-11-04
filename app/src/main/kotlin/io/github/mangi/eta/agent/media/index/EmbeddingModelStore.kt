package io.github.mangi.eta.agent.media.index

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
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
 * Embedding 模型的按需下载与校验，存放于 App 私有 no-backup 目录，不入 APK。
 *
 * 与 [io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack] 同一套约定：
 * `<modelId>/<revision>` 分目录、逐文件字节数 + SHA-256 全量校验、`.part` 落盘后原子改名。
 */
internal object EmbeddingModelStore {
    data class State(
        val checking: Boolean = true,
        val ready: Boolean = false,
        val downloading: Boolean = false,
        val downloadedBytes: Long = 0,
        val error: Boolean = false,
        val model: EmbeddingModelCatalog.Model = EmbeddingModelCatalog.defaultModel(),
        val source: EmbeddingModelCatalog.Source = EmbeddingModelCatalog.defaultModel().sources.first(),
    ) {
        val totalBytes: Long get() = model.totalBytes
    }

    /** Matryoshka 截断维度：默认 768，可省存储切 512 / 256。 */
    const val DEFAULT_DIM = 768
    private val ALLOWED_DIMS = setOf(768, 512, 256)

    /**
     * 检索页匹配度阈值默认值：较强匹配（检索时下推给 search 的 minScore，不是展示层过滤）。
     * 用户拖过滑杆后落盘，永远以用户值为准；检索页滑杆初值与本值共用，保证默认停在对应档位。
     */
    internal const val DEFAULT_SEARCH_MIN_SCORE = 0.75f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var download: Job? = null

    private fun prefs(context: Context) =
        context.getSharedPreferences("embedding_model", Context.MODE_PRIVATE)

    /** 模型目录：`<modelId>/<revision>`，按固定 revision 存放以便校验与备份恢复。 */
    fun directory(context: Context, model: EmbeddingModelCatalog.Model = mutable.value.model): File =
        File(context.noBackupFilesDir, "embedding/${model.id}/${model.revision}")

    /** 已校验模型文件的指纹：路径 + 长度 + mtime 均未变即视为仍然有效。 */
    private data class ValidatedModelFile(val path: String, val length: Long, val mtime: Long, val file: File)

    @Volatile
    private var validatedFile: ValidatedModelFile? = null

    /**
     * 已校验的 `.litertlm` 主文件路径；未就绪返回 null。
     *
     * 带指纹缓存：本函数被每次 embed（含精排每帧、索引管线每文件）与状态查询高频调用，
     * 此前每次都重新对整个模型文件算 SHA-256（740M 档 484MB），是检索与索引的共同大头。
     * 文件被替换必然改变长度或 mtime，指纹自动失效；@Volatile + 良性竞态——未命中时
     * 两线程并发哈希结果一致，后写者胜。备份恢复走 [refresh] 主动清缓存强制重验。
     */
    fun modelFile(context: Context): File? {
        val model = mutable.value.model
        val asset = model.assets.firstOrNull() ?: return null
        val file = File(directory(context, model), asset.name)
        val length = file.length()
        val mtime = file.lastModified()
        validatedFile?.let { cached ->
            if (cached.path == file.absolutePath && cached.length == length && cached.mtime == mtime) {
                return cached.file
            }
        }
        return file.takeIf { valid(it, asset) }?.also {
            validatedFile = ValidatedModelFile(file.absolutePath, length, mtime, file)
        }
    }

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            val stored = prefs(app)
            // 首次未显式选择模型时按设备内存定默认：低内存降级纯文本 270M，其余全模态 740M。
            val model = EmbeddingModelCatalog.model(
                stored.getString("model", null),
                lowMemory = lowMemoryDevice(app),
            )
            val source = model.source(EmbeddingModelCatalog.SourceId.from(stored.getString("source", null)))
            val ready = withContext(Dispatchers.IO) { verify(directory(app, model), model) }
            mutable.value = State(checking = false, ready = ready, model = model, source = source)
        }
    }

    /** 模型文件被备份恢复后重新校验；取消进行中的下载，避免旧任务发布过期状态。 */
    @Synchronized
    fun refresh(context: Context) {
        download?.cancel()
        download = null
        initialized = false
        // 恢复可能替换了模型文件而长度/mtime 碰巧未变：清指纹缓存强制全量重验。
        validatedFile = null
        mutable.value = mutable.value.copy(
            checking = true,
            downloading = false,
            downloadedBytes = 0,
            error = false,
        )
        initialize(context)
    }

    /** 切换变体或下载源会重新校验；下载进行中拒绝切换。 */
    fun select(context: Context, modelId: String, sourceId: EmbeddingModelCatalog.SourceId) {
        if (mutable.value.downloading) return
        val model = EmbeddingModelCatalog.model(modelId)
        val source = model.source(sourceId)
        if (model.id == mutable.value.model.id && source.id == mutable.value.source.id) return
        val app = context.applicationContext
        prefs(app).edit().putString("model", model.id).putString("source", source.id.wire).apply()
        mutable.value = State(checking = true, model = model, source = source)
        scope.launch {
            val ready = withContext(Dispatchers.IO) { verify(directory(app, model), model) }
            // 哈希校验耗时较长，期间用户可能又切换了变体；不发布过期的就绪状态。
            if (mutable.value.model.id != model.id || mutable.value.source.id != source.id) return@launch
            mutable.value = mutable.value.copy(checking = false, ready = ready, error = false)
        }
    }

    fun cancelDownload() {
        download?.cancel()
    }

    fun download(context: Context) {
        val snapshot = mutable.value
        if (snapshot.checking || snapshot.downloading || snapshot.ready) return
        val app = context.applicationContext
        val model = snapshot.model
        val source = snapshot.source
        mutable.value = snapshot.copy(downloading = true, downloadedBytes = 0, error = false)
        download = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = directory(app, model).apply { mkdirs() }
                    var done = 0L
                    for (asset in model.assets) {
                        ensureActive()
                        val target = File(dir, asset.name)
                        if (!valid(target, asset)) {
                            val partial = File(dir, asset.name + ".part")
                            val connection = URI(source.baseUrl + asset.name).toURL()
                                .openConnection() as HttpURLConnection
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 30_000
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            try {
                                check(connection.responseCode == 200) { "Embedding model download HTTP failure" }
                                check(connection.url.protocol == "https") { "Insecure model redirect" }
                                var received = 0L
                                var lastProgress = 0L
                                connection.inputStream.use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            received += count
                                            check(received <= asset.bytes) { "Model file exceeds manifest size" }
                                            output.write(buffer, 0, count)
                                            if (received - lastProgress >= 256 * 1024) {
                                                lastProgress = received
                                                withContext(Dispatchers.Main) {
                                                    mutable.value = mutable.value.copy(downloadedBytes = done + received)
                                                }
                                            }
                                        }
                                    }
                                }
                                check(valid(partial, asset)) { "Model checksum mismatch" }
                                ensureActive()
                                check(partial.renameTo(target)) { "Cannot commit verified model" }
                            } finally {
                                connection.disconnect()
                                partial.delete()
                            }
                        }
                        done += asset.bytes
                        withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(downloadedBytes = done) }
                    }
                }
                mutable.value = mutable.value.copy(checking = false, ready = true, error = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = true)
            } finally {
                mutable.value = mutable.value.copy(downloading = false)
            }
        }
    }

    fun vectorDim(context: Context): Int {
        val stored = prefs(context).getInt("dim", DEFAULT_DIM)
        return stored.takeIf { it in ALLOWED_DIMS } ?: DEFAULT_DIM
    }

    fun setVectorDim(context: Context, dim: Int) {
        if (dim !in ALLOWED_DIMS) return
        prefs(context).edit().putInt("dim", dim).apply()
    }

    /** 低内存设备检测；判定逻辑在 [EmbeddingModelCatalog.isLowMemory]（纯函数，可单测）。 */
    fun lowMemoryDevice(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
        return EmbeddingModelCatalog.isLowMemory(manager.isLowRamDevice, info.totalMem)
    }

    /** 推理后端偏好；缺省 AUTO（GPU → NPU → CPU 依次尝试）。 */
    fun backend(context: Context): InferenceBackend =
        InferenceBackend.from(prefs(context).getString("backend", null))

    fun setBackend(context: Context, backend: InferenceBackend) {
        prefs(context).edit().putString("backend", backend.wire).apply()
    }

    /**
     * 模型是否已下载（廉价检查：文件存在 + 字节数相符，不重算 SHA-256）：
     * 供 [MediaIndexAutoUpdate] 的守卫（[MediaIndexAutoUpdate.dueReason]）在主线程调用；
     * 完整哈希校验由索引管线（[modelFile]）负责。
     */
    fun modelDownloaded(context: Context): Boolean {
        val model = EmbeddingModelCatalog.model(
            prefs(context).getString("model", null),
            lowMemory = lowMemoryDevice(context),
        )
        val asset = model.assets.firstOrNull() ?: return false
        val file = File(directory(context, model), asset.name)
        return file.isFile && file.length() == asset.bytes
    }

    /** 索引模式偏好；缺省 [IndexMode.DEFAULT]（低耗后台）。切档即时落库，下一轮索引起生效。 */
    fun indexMode(context: Context): IndexMode =
        IndexMode.from(prefs(context).getString("index_mode", null))

    fun setIndexMode(context: Context, mode: IndexMode) {
        prefs(context).edit().putString("index_mode", mode.wire).apply()
    }

    /**
     * 检索页匹配度阈值偏好：低于该相似度的命中在检索时被过滤，结果集 = 所有 ≥ 阈值的命中。
     * 默认 [DEFAULT_SEARCH_MIN_SCORE]；进检索页时恢复上次调节的阈值，AI 工具 min_score
     * 省略时同样回退本值（可显式传 0 绕过）。用户拖过滑杆后落盘，永远以用户值为准。
     */
    fun searchMinScore(context: Context): Float =
        prefs(context).getFloat("search_min_score", DEFAULT_SEARCH_MIN_SCORE).coerceIn(0f, 1f)

    fun setSearchMinScore(context: Context, minScore: Float) {
        prefs(context).edit().putFloat("search_min_score", minScore.coerceIn(0f, 1f)).apply()
    }

    internal fun valid(file: File, asset: EmbeddingModelCatalog.Asset): Boolean = runCatching {
        if (!file.isFile || file.length() != asset.bytes) return false
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
    }.getOrDefault(false)

    private fun verify(dir: File, model: EmbeddingModelCatalog.Model): Boolean =
        model.assets.all { valid(File(dir, it.name), it) }
}
