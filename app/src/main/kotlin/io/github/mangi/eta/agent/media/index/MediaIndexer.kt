package io.github.mangi.eta.agent.media.index

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaIndexEntity
import io.github.mangi.eta.data.db.MediaKind
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 媒体索引器：扫描 → 抽取 → 嵌入 → 落库，增量、可取消、批提交。
 *
 * 增量判断：`(size_bytes, modified_at)` 未变则跳过（内容哈希只在真正入库时计算）；
 * 扫描不到的旧条目在收尾时清理（向量 + 缩略图）。同一时刻只允许一个索引任务。
 * 批大小、条目间节流与抽取预算按 [IndexModeProfile] 取值（[IndexMode] 两档）：
 * 全力档保持既有行为，低耗档批更小、逐条限速、抽帧/图像边减配，可长时间后台跑完。
 */
internal object MediaIndexer {
    data class Progress(
        val phase: String = "",
        val processed: Int = 0,
        val total: Int = 0,
        val indexed: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        // 清理数（消失条目）收尾才确定：写回进度快照，供 UI/工具与 [Result.removed] 同源展示。
        val removed: Int = 0,
        // 缩略图自愈：本轮重建成功/失败的数量（失败只影响缩略图，不影响条目本身的索引状态）。
        val thumbRepaired: Int = 0,
        val thumbFailed: Int = 0,
        val running: Boolean = false,
    )

    data class Result(
        val indexed: Int,
        val skipped: Int,
        val failed: Int,
        val removed: Int,
        val thumbRepaired: Int = 0,
        val thumbFailed: Int = 0,
    )

    private val mutable = MutableStateFlow(Progress())
    val state = mutable.asStateFlow()
    private val running = AtomicBoolean(false)

    fun isRunning(): Boolean = running.get()

    /**
     * 启动一轮索引；[mode] 缺省读存储的索引模式偏好（[EmbeddingModelStore.indexMode]），
     * 显式传档位时以传入值为准（测试与按档启动的调用方使用）。
     */
    suspend fun index(
        context: Context,
        mode: IndexMode = EmbeddingModelStore.indexMode(context.applicationContext),
    ): Result {
        if (!running.compareAndSet(false, true)) {
            throw IllegalStateException("索引任务已在进行中")
        }
        return try {
            withContext(Dispatchers.IO) { runIndex(context.applicationContext, mode) }
        } finally {
            running.set(false)
            mutable.value = mutable.value.copy(running = false, phase = "")
        }
    }

    /** 清空全部索引（模型与扫描源配置保留）。 */
    suspend fun clear(context: Context) {
        EtaDatabase.get(context.applicationContext).mediaIndexDao().clearAll()
        mutable.value = Progress()
    }

    private suspend fun runIndex(app: Context, mode: IndexMode): Result {
        // 模型未就绪直接失败，避免索引出无向量的空行。
        checkNotNull(EmbeddingModelStore.modelFile(app)) { "嵌入模型尚未下载或校验未通过" }
        // 缩略图寻址迁移（旧 cacheDir 绝对路径 → 持久目录 + 文件名）先跑完，避免旧值被当成有效路径。
        MediaThumbnailStore.ensureMigrated(app)
        // 档位参数（批大小/节流/抽取预算）本轮固定：中途切档下一轮才生效。
        val profile = IndexModeProfile.forMode(mode)
        val model = EmbeddingModelStore.state.value.model
        val dim = EmbeddingModelStore.vectorDim(app)
        val dao = EtaDatabase.get(app).mediaIndexDao()
        val sources = dao.sources().filter { it.enabled }
        val existing = dao.listForModel(model.id, dim).associateBy { it.sourceKey }
        val seen = mutableSetOf<String>()
        val pending = mutableListOf<MediaIndexEntity>()
        var indexed = 0
        var skipped = 0
        var failed = 0
        var thumbRepaired = 0
        var thumbFailed = 0
        // 自愈分支的失败单独计数：这些条目已计入 skipped 之外的处理量，需并进 processed；
        // 嵌入路径的 thumbFailed 已包含在 indexed 里，不能重复计入 processed。
        var repairFailed = 0
        // 单轮缩略图重建上限：低耗档下避免一次性重解整库缩略图，把恢复摊到后续轮次。
        var repairAttempts = 0

        // 先统计总量，便于进度展示；统计失败不影响索引。
        val perSource = sources.associateWith { runCatching { MediaSourceScanner.scan(app, it) }.getOrDefault(emptyList()) }
        val total = perSource.values.sumOf { it.size }
        mutable.value = Progress(phase = "scanning", total = total, running = true)

        for ((source, candidates) in perSource) {
            for (candidate in candidates) {
                coroutineContext.ensureActive()
                seen += candidate.sourceKey
                val old = existing[candidate.sourceKey]
                if (old != null && old.sizeBytes == candidate.sizeBytes && old.modifiedAt == candidate.modifiedAt) {
                    // 缩略图自愈：(size, mtime) 未变但缩略图不可用（系统清缓存/换机恢复/迁移后文件丢失/LRU 淘汰），
                    // 就地重建并复用原向量，**不重新嵌入**。单轮有上限 [MediaThumbnailStore.MAX_REPAIRS_PER_RUN]
                    // 并按档位节流，避免低耗档被大量缺失条目拖成全量重扫；音频/文档本就无缩略图，不重建。
                    val stored = old.thumbPath
                    // 只有图片/视频有缩略图；音频/文档本来就"没有"，不在这里重建。
                    val supportsThumb = candidate.kind == MediaKind.IMAGE || candidate.kind == MediaKind.VIDEO
                    val needsRepair = supportsThumb && !MediaThumbnailStore.isUsable(app, stored)
                    when {
                        !needsRepair -> skipped++
                        repairAttempts >= MediaThumbnailStore.MAX_REPAIRS_PER_RUN -> skipped++
                        else -> {
                            repairAttempts++
                            val name = MediaExtraction.saveThumbnail(app, candidate)
                            if (name != null) {
                                // 只更新缩略图列，向量原样复用；走同一批提交，收尾统一落库。
                                pending += old.copy(thumbPath = name)
                                thumbRepaired++
                            } else {
                                thumbFailed++
                                repairFailed++
                            }
                            if (profile.throttleDelayMs > 0) delay(profile.throttleDelayMs)
                        }
                    }
                } else {
                    // 单条嵌入失败（引擎异常/格式不支持）只计 failed，不中断整轮索引，
                    // 也不丢已累积待提交的批次；取消信号照常上抛。
                    val entity = try {
                        embedCandidate(app, candidate, model.id, dim, model.modalities, profile)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        null
                    }
                    if (entity == null) {
                        failed++
                    } else {
                        // 图片/视频缺缩略图（生成失败）单独计数：条目本身仍已索引，不影响向量检索。
                        if ((candidate.kind == MediaKind.IMAGE || candidate.kind == MediaKind.VIDEO) &&
                            entity.thumbPath == null
                        ) {
                            thumbFailed++
                        }
                        pending += entity
                        indexed++
                        if (pending.size >= profile.batchSize) {
                            dao.commitIndex(pending.toList(), emptyList())
                            pending.clear()
                        }
                    }
                    // 低耗档条目间节流：处理过（抽取/嵌入）的条目之间歇一拍摊薄 CPU/发热；
                    // delay 随协程取消即抛 CancellationException，可随时中断索引。
                    if (profile.throttleDelayMs > 0) {
                        delay(profile.throttleDelayMs)
                    }
                }
                mutable.value = Progress(
                    phase = "indexing",
                    processed = indexed + skipped + failed + thumbRepaired + repairFailed,
                    total = total,
                    indexed = indexed,
                    skipped = skipped,
                    failed = failed,
                    thumbRepaired = thumbRepaired,
                    thumbFailed = thumbFailed,
                    running = true,
                )
            }
            dao.updateSourceScan(source.id, candidates.size, System.currentTimeMillis())
        }

        // 已消失的媒体：删除其向量行与缩略图（按文件名寻址，删不到也只是孤儿，由 LRU 兜底）。
        val stale = existing.keys - seen
        stale.forEach { key ->
            val stored = existing[key]?.thumbPath
            MediaThumbnailStore.deleteForSourceKey(app, key, stored)
        }
        dao.commitIndex(pending.toList(), stale.toList())
        // 收尾做容量回收（超上限才真正动作），并同步把被淘汰行的缩略图标记为哨兵。
        runCatching { MediaThumbnailStore.trim(app) }
        // 收尾把清理数写回进度快照，「清理」统计与下面的 Result 同源。
        mutable.value = mutable.value.copy(removed = stale.size)
        return Result(
            indexed = indexed,
            skipped = skipped,
            failed = failed,
            removed = stale.size,
            thumbRepaired = thumbRepaired,
            thumbFailed = thumbFailed,
        )
    }

    private suspend fun embedCandidate(
        app: Context,
        candidate: MediaSourceScanner.Candidate,
        modelId: String,
        dim: Int,
        modalities: EmbeddingModelCatalog.Modalities,
        profile: IndexModeProfile,
    ): MediaIndexEntity? {
        val vector = when (candidate.kind) {
            MediaKind.IMAGE -> {
                if (!modalities.vision) return null
                val bytes = MediaExtraction.imageBytes(app, candidate, profile.imageMaxEdge) ?: return null
                EtaEmbeddingEngine.embedImage(app, bytes, dim)
            }
            MediaKind.VIDEO -> {
                if (!modalities.vision) return null
                val frames = MediaExtraction.videoFrameBytes(app, candidate, profile.videoFrames)
                if (frames.isEmpty()) return null
                EtaEmbeddingEngine.embedVideo(app, frames, dim)
            }
            MediaKind.DOCUMENT -> {
                if (MediaExtraction.isPdf(candidate)) {
                    // PDF 二段式：页渲染为位图走图像路径嵌入；纯文本变体无视觉能力，跳过。
                    if (!modalities.vision) return null
                    val pages = MediaExtraction.pdfPageImages(app, candidate, profile.maxPdfPages, profile.imageMaxEdge)
                    if (pages.isEmpty()) return null
                    EtaEmbeddingEngine.embedImages(app, pages, dim)
                } else {
                    // DOCX 抽正文 / 纯文本直读。
                    val text = MediaExtraction.documentText(app, candidate) ?: return null
                    EtaEmbeddingEngine.embedDocumentText(app, text, dim)
                }
            }
            MediaKind.AUDIO -> {
                // 纯文本 270M 变体无音频能力，跳过；其余按 30s 窗口嵌入后聚合。
                if (!modalities.audio) return null
                val windows = MediaExtraction.audioWindows(app, candidate)
                if (windows.isEmpty()) return null
                EtaEmbeddingEngine.embedAudioWindows(app, windows, dim)
            }
        }
        return MediaIndexEntity(
            sourceKey = candidate.sourceKey,
            modelId = modelId,
            vectorDim = dim,
            kind = candidate.kind.wire,
            displayName = candidate.displayName,
            mime = candidate.mime,
            sizeBytes = candidate.sizeBytes,
            modifiedAt = candidate.modifiedAt,
            contentHash = MediaExtraction.contentHash(app, candidate).orEmpty(),
            durationMs = candidate.durationMs,
            width = candidate.width,
            height = candidate.height,
            frameTimeMs = null,
            thumbPath = MediaExtraction.saveThumbnail(app, candidate),
            vector = Float16VectorCodec.encode(vector),
            indexedAt = System.currentTimeMillis(),
        )
    }
}
