package io.github.mangi.eta.agent.media.index

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.collection.LruCache
import androidx.room.InvalidationTracker
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaIndexDao
import io.github.mangi.eta.data.db.MediaIndexEntity
import io.github.mangi.eta.data.db.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 语义检索唯一入口：GUI 搜索框与 `semantic_search_media` 工具都走这里，
 * 保证「用户搜到的」与「AI 搜到的」结果完全一致。
 *
 * 检索实现收敛到 [vectorIndex]（当前为 [BruteForceVectorIndex] 暴力点积）：万级条目在手机上
 * 可接受；规模超过 [ANN_SWITCH_THRESHOLD] 后提示并由后续版本换 ANN（HNSW/sqlite-vec）实现，
 * 对外接口保持不变。
 *
 * 缓存分两级、共用一个失效信号：向量切片行缓存（免全表重读 BLOB）与检索结果缓存
 * （同词重复搜索秒回，含精排时刻）。命中前提都是索引代数一致——media_index 表任何写入
 * 经 Room InvalidationTracker 使代数 +1，两级缓存随之整体失效，新增/删除文件不会吃到旧结果。
 *
 * 匹配度阈值是检索时参数：结果集 = 所有 ≥ 阈值的命中，不设数量上限（GUI 传
 * [UNLIMITED_RESULTS]，阈值是唯一的筛选条件）；[queryEmbeddings] 缓存让换阈值重检
 * 只做内存重排，滑杆调档近乎即时。
 */
internal object MediaSearchService {
    /**
     * 暴力检索规模阈值：条目数超过该值后在 Status/设置页给出「后续版本切换 ANN」提示。
     * 真实 ANN 索引（HNSW/sqlite-vec）不在本期，只预留切换点（见 [VectorIndex]）。
     */
    const val ANN_SWITCH_THRESHOLD = 50_000

    /** 向量切片行缓存的规模护栏：超过该条数（≈30MB Float16 BLOB @768dim）直接绕过缓存。 */
    private const val ROW_CACHE_MAX_ROWS = 20_000

    /** 检索结果缓存容量：条目只含字符串与标量，16 条足够覆盖反复搜索的高频词。 */
    private const val RESULT_CACHE_SIZE = 16

    /** 查询向量缓存容量：8 条 × 768 维 Float ≈ 24KB，覆盖阈值滑杆反复重检的同一关键词。 */
    private const val QUERY_EMBEDDING_CACHE_SIZE = 8

    /**
     * 检索结果数量不设上限的标记值：结果集完全由匹配度阈值决定（结果 = 所有 ≥ 阈值的命中）。
     * GUI 检索传此值；数量护栏由调用方自负（AI 工具层自有 1..50 限定）。
     */
    const val UNLIMITED_RESULTS = Int.MAX_VALUE

    /** 检索实现入口：当前为暴力点积；规模超阈值后在此切换 ANN 实现，调用方不变。 */
    private val vectorIndex: VectorIndex = BruteForceVectorIndex

    // ── 缓存层：全部进程内内存缓存，以「索引代数一致」为命中前提 ──────────────────
    //
    // 索引代数：media_index 表任何写入（索引器增量轮、移出索引、删文件、move purge、
    // 备份导入、清空）都会使代数 +1。用 Room InvalidationTracker 做表级监听而不是在
    // 各写入方手工埋点——现有与未来的写入方自动覆盖，索引变了绝不返回旧结果。
    private val indexGeneration = AtomicLong(0)

    @Volatile
    private var hookedDatabase: EtaDatabase? = null

    /** 惰性注册 media_index 表失效监听；EtaDatabase 为进程级单例（EtaDatabase.get），注册一次即可。 */
    private fun hookInvalidation(db: EtaDatabase) {
        if (hookedDatabase === db) return
        synchronized(this) {
            if (hookedDatabase === db) return
            db.invalidationTracker.addObserver(object : InvalidationTracker.Observer("media_index") {
                override fun onInvalidated(invalidatedTables: Set<String>) {
                    indexGeneration.incrementAndGet()
                }
            })
            // 数据库实例变了：旧 rowSlice 属于另一个库（generation 不跨库推进），
            // 必须直接作废，否则换库后的首次检索会命中带着旧数据与旧代数的缓存。
            rowSlice = null
            hookedDatabase = db
        }
    }

    /**
     * 向量切片行缓存：免去每次检索从 Room 全量重读 Float16 BLOB（1 万条 ≈ 15MB IO）。
     * 只服务检索入口（search/findSimilar）；索引管线（MediaIndexer）必须直读数据库做
     * 增量判断，不得消费本缓存。单槽即可——检索永远只看当前模型 + 维度这一片。
     */
    private data class RowSlice(
        val modelId: String,
        val dim: Int,
        val generation: Long,
        val rows: List<MediaIndexEntity>,
    )

    @Volatile
    private var rowSlice: RowSlice? = null

    /** 取当前模型 + 维度的候选行：代数与切片键全等才命中，否则重读并回填（超护栏不缓存）。 */
    private suspend fun rowsForSearch(
        db: EtaDatabase,
        dao: MediaIndexDao,
        modelId: String,
        dim: Int,
        generation: Long,
    ): List<MediaIndexEntity> {
        rowSlice?.takeIf {
            it.modelId == modelId && it.dim == dim && it.generation == generation
        }?.let { return it.rows }
        val rows = dao.listForModel(modelId, dim)
        if (rows.size <= ROW_CACHE_MAX_ROWS) {
            rowSlice = RowSlice(modelId, dim, generation, rows)
        } else {
            rowSlice = null
        }
        return rows
    }

    /** 检索结果缓存键：除查询词与过滤条件外，模型/维度/精排参数都参与——任一变化结果不同。 */
    private data class ResultCacheKey(
        val query: String,
        val modelId: String,
        val dim: Int,
        val kinds: List<String>,
        val limit: Int,
        val minScore: Float,
        val refineMoments: Boolean,
        val indexMode: IndexMode,
    )

    private data class ResultCacheEntry(val generation: Long, val result: SearchResult)

    /**
     * 检索结果缓存：同一 (关键词, 模型, 过滤条件) 的重复搜索直接复用上轮结果，
     * 连同二段精排回填的命中时刻一起返回。命中前提同样是代数一致——索引任何变动后
     * 自动重检，新增/删除/移出的文件都会反映到下一次搜索。
     */
    private val resultCache = LruCache<ResultCacheKey, ResultCacheEntry>(RESULT_CACHE_SIZE)

    /** 查询向量缓存的键：嵌入只依赖查询词与模型/维度，与索引内容无关，故不随索引代数失效。 */
    private data class QueryEmbeddingKey(val query: String, val modelId: String, val dim: Int)

    /**
     * 查询向量缓存：阈值滑杆松手触发的重检与上一轮同词，直接复用嵌入向量，
     * 跳过模型推理只做内存点积（万级条目约数十毫秒），滑杆调档近乎即时。
     */
    private val queryEmbeddings = LruCache<QueryEmbeddingKey, FloatArray>(QUERY_EMBEDDING_CACHE_SIZE)

    /**
     * 检索结果一行；[contentUri] / [path] 可直接被 read_image、终端文件工具链式使用。
     * [frameTimeMs] 为视频「时刻检索」的命中时刻（毫秒）：二段精排逐帧比对后回填，无值表示未精排。
     */
    data class Hit(
        val sourceKey: String,
        val kind: MediaKind,
        val displayName: String,
        val mime: String,
        val score: Float,
        val contentUri: String?,
        val path: String?,
        val sizeBytes: Long,
        val modifiedAt: Long,
        val durationMs: Long?,
        val width: Int?,
        val height: Int?,
        val thumbPath: String?,
        val frameTimeMs: Long? = null,
    )

    data class SearchResult(
        val query: String,
        val hits: List<Hit>,
        val totalCandidates: Int,
        val modelId: String,
        val dim: Int,
        /** 检索时 kinds 范围内低于匹配度阈值、未进入结果的候选数（信息性：喂给阈值面板文案）。 */
        val belowThresholdCount: Int = 0,
    )

    data class Status(
        val modelDownloaded: Boolean,
        val modelId: String,
        val dim: Int,
        val indexedCount: Int,
        val lastIndexedAt: Long?,
        val enabledSources: Int,
        val indexing: Boolean,
    ) {
        /** 模型就绪且至少配置过一个扫描源才算「可检索」。 */
        val ready: Boolean get() = modelDownloaded && enabledSources > 0

        /**
         * 条目规模已超过暴力检索阈值（[ANN_SWITCH_THRESHOLD]）：暴力点积仍可用但偏慢，
         * 后续版本切换 ANN。仅该字段为 true 时 UI 才显示提示。
         */
        val approachingAnnSwitch: Boolean
            get() = MediaSearchService.exceedsAnnSwitchThreshold(indexedCount)
    }

    /** 纯函数：条目规模是否已超过暴力检索阈值（超过才提示后续切换 ANN）。 */
    internal fun exceedsAnnSwitchThreshold(indexedCount: Int): Boolean = indexedCount > ANN_SWITCH_THRESHOLD

    suspend fun status(context: Context): Status = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val model = EmbeddingModelStore.state.value.model
        val dim = EmbeddingModelStore.vectorDim(app)
        val dao = EtaDatabase.get(app).mediaIndexDao()
        Status(
            modelDownloaded = EmbeddingModelStore.modelFile(app) != null,
            modelId = model.id,
            dim = dim,
            indexedCount = dao.countForModel(model.id, dim),
            lastIndexedAt = dao.lastIndexedAt(),
            enabledSources = dao.sources().count { it.enabled },
            indexing = MediaIndexer.isRunning(),
        )
    }

    /**
     * 自然语言搜索：query 走 query 前缀嵌入，与库中归一化向量做点积排序。
     * 入库是「每视频一行平均向量」，命中视频后可选做二段时刻精排（[refineMoments]）：
     * 重新抽帧逐帧比对，把最高分帧的时刻回填到 [Hit.frameTimeMs]。
     * 精排条数与重抽帧预算按 [IndexModeProfile] 取值（全力档 top-5 / 低耗档 top-3）。
     *
     * 同一 (关键词, 模型, 过滤条件) 的重复搜索在索引代数一致的前提下直接命中
     * [resultCache]，连精排时刻一起原样返回，不再重跑嵌入与检索；查询向量本身另有
     * [queryEmbeddings] 缓存，换阈值重检时跳过模型推理。
     *
     * [minScore] 为匹配度阈值：在构建 Hit 之前按分数过滤（[applyMinScore]），结果集
     * 就是「所有 ≥ 阈值的命中」，低于阈值的候选既不进入结果也不占精排预算；
     * 0（默认）表示不过滤。数量上限由调用方自负：GUI 传 [UNLIMITED_RESULTS] 让阈值
     * 完全决定结果规模，AI 工具层自行限定 1..50。
     */
    suspend fun search(
        context: Context,
        query: String,
        kinds: Set<MediaKind> = emptySet(),
        limit: Int = 10,
        minScore: Float = 0f,
        refineMoments: Boolean = true,
    ): SearchResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        require(query.isNotBlank()) { "query 不能为空" }
        val model = EmbeddingModelStore.state.value.model
        val dim = EmbeddingModelStore.vectorDim(app)
        val db = EtaDatabase.get(app)
        val indexMode = EmbeddingModelStore.indexMode(app)
        hookInvalidation(db)
        val key = ResultCacheKey(
            query = query.trim(),
            modelId = model.id,
            dim = dim,
            kinds = kinds.map { it.wire }.sorted(),
            limit = limit,
            minScore = minScore,
            refineMoments = refineMoments,
            indexMode = indexMode,
        )
        // 代数在嵌入前取快照：期间若索引写入提交，代数 +1，本轮结果带着旧代数入缓存，
        // 下次检索代数不一致自动重检（宁可多跑一次，绝不返回旧结果）。
        val generation = indexGeneration.get()
        resultCache.get(key)?.takeIf { it.generation == generation }?.let { return@withContext it.result }
        val embeddingKey = QueryEmbeddingKey(query.trim(), model.id, dim)
        val queryVector = queryEmbeddings.get(embeddingKey)
            ?: EtaEmbeddingEngine.embedQuery(app, query, dim).also { queryEmbeddings.put(embeddingKey, it) }
        val rows = rowsForSearch(db, db.mediaIndexDao(), model.id, dim, generation)
        val ranked = rank(queryVector, rows, kinds, limit)
        // 阈值过滤放在 hitOf 之前：低于阈值的候选不构建 Hit（免 resolvePath 跨进程查询），
        // 也不占精排预算；不限量检索时这里是万级候选与秒级耗时的分界。
        val matched = applyMinScore(ranked, { it.second }, minScore)
        // 二段精排预算按索引模式取值：全力档 top-5，低耗档 top-3（逐帧嵌入较贵）。
        val profile = IndexModeProfile.forMode(indexMode)
        var refined = 0
        val hits = matched.map { (row, score) -> hitOf(app, row, score) }
            .map { hit ->
                val shouldRefine = refineMoments &&
                    hit.kind == MediaKind.VIDEO &&
                    model.modalities.vision &&
                    refined < profile.refineTopK
                if (shouldRefine) {
                    refined++
                    refineMoment(app, queryVector, dim, hit, profile.videoFrames) ?: hit
                } else {
                    hit
                }
            }
        val result = SearchResult(
            query = query.trim(),
            hits = hits,
            totalCandidates = rows.size,
            modelId = model.id,
            dim = dim,
            belowThresholdCount = ranked.size - matched.size,
        )
        resultCache.put(key, ResultCacheEntry(generation, result))
        result
    }

    /**
     * 二段精排：对视频命中重新抽帧（含时刻戳，最多 [maxFrames] 帧，按索引模式取值）逐帧嵌入，
     * 取与查询点积最大帧的时刻。只精排结果里前 [IndexModeProfile.refineTopK] 个视频命中；
     * 引擎不可用/抽取失败一律静默跳过（[Hit.frameTimeMs] 保持 null），不影响其余结果。
     */
    private suspend fun refineMoment(
        context: Context,
        queryVector: FloatArray,
        dim: Int,
        hit: Hit,
        maxFrames: Int,
    ): Hit? {
        return runCatching {
            val candidate = MediaSourceScanner.Candidate(
                sourceKey = hit.sourceKey,
                kind = MediaKind.VIDEO,
                displayName = hit.displayName,
                mime = hit.mime,
                sizeBytes = hit.sizeBytes,
                modifiedAt = hit.modifiedAt,
                contentUri = hit.contentUri,
                filePath = hit.path,
                durationMs = hit.durationMs,
                width = hit.width,
                height = hit.height,
            )
            val frames = MediaExtraction.videoFrames(context, candidate, maxFrames)
            if (frames.isEmpty()) return@runCatching null
            val scored = frames.map { frame ->
                frame.timeMs to EtaEmbeddingEngine.embedImage(context, frame.bytes, dim)
            }
            bestMoment(queryVector, scored)?.let { timeMs -> hit.copy(frameTimeMs = timeMs) }
        }.getOrNull()
    }

    /** 二段精排选帧（纯逻辑）：取与查询点积最大的帧时刻；无帧可比返回 null。 */
    internal fun bestMoment(query: FloatArray, frames: List<Pair<Long, FloatArray>>): Long? =
        frames
            .map { (timeMs, vector) -> timeMs to if (vector.size == query.size) dot(query, vector) else Float.NEGATIVE_INFINITY }
            .maxByOrNull { (_, score) -> score }
            ?.takeIf { (_, score) -> score > Float.NEGATIVE_INFINITY }
            ?.first

    /** 「找相似」：以某条目自身向量为参考找相近媒体；[reference] 用结果里的 content_uri 或 path。 */
    suspend fun findSimilar(
        context: Context,
        reference: String,
        kinds: Set<MediaKind> = emptySet(),
        limit: Int = 10,
    ): SearchResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        require(reference.isNotBlank()) { "reference 不能为空" }
        val model = EmbeddingModelStore.state.value.model
        val dim = EmbeddingModelStore.vectorDim(app)
        val db = EtaDatabase.get(app)
        val dao = db.mediaIndexDao()
        hookInvalidation(db)
        val rows = rowsForSearch(db, dao, model.id, dim, indexGeneration.get())
        val referenceRow = rows.firstOrNull { it.sourceKey == reference }
            ?: dao.findBySourceKey(reference).firstOrNull { it.modelId == model.id && it.vectorDim == dim }
            ?: throw IllegalArgumentException("找不到已索引的参考项：$reference")
        val referenceVector = Float16VectorCodec.decode(referenceRow.vector)
        val ranked = rank(referenceVector, rows.filter { it.sourceKey != referenceRow.sourceKey }, kinds, limit)
        SearchResult(
            query = "similar:${referenceRow.displayName}",
            hits = ranked.map { (row, score) -> hitOf(app, row, score) },
            totalCandidates = rows.size,
            modelId = model.id,
            dim = dim,
        )
    }

    /**
     * 归一化点积排名；纯函数便于单测（构造向量即可验证排名与过滤）。
     * 过滤维度由调用方保证（SQL 已按 vector_dim 过滤），这里再按 kind 过滤。
     * 实现已收敛到 [vectorIndex] 单一入口（当前为 [BruteForceVectorIndex]）。
     */
    internal fun rank(
        query: FloatArray,
        rows: List<MediaIndexEntity>,
        kinds: Set<MediaKind> = emptySet(),
        limit: Int = 10,
    ): List<Pair<MediaIndexEntity, Float>> = vectorIndex.search(query, rows, kinds, limit)

    /**
     * 纯函数：按匹配度阈值过滤。[minScore] <= 0 表示不过滤；等于阈值的条目保留
     * （边界含等于）；过滤不改变原有排序。[score] 由调用方给定取分方式——检索服务
     * 取排序对的分数，单测取 Hit 的分数字段，保证「用户看到的」与「AI 拿到的」口径一致。
     */
    internal fun <T> applyMinScore(items: List<T>, score: (T) -> Float, minScore: Float): List<T> =
        if (minScore <= 0f) items else items.filter { score(it) >= minScore }

    internal fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** 索引行 → 检索结果；[row.frameTimeMs] 有值（帧级行）时直接带出命中时刻。 */
    internal fun hitOf(
        context: Context,
        row: MediaIndexEntity,
        score: Float,
    ): Hit = Hit(
        sourceKey = row.sourceKey,
        kind = MediaKind.from(row.kind) ?: MediaKind.DOCUMENT,
        displayName = row.displayName,
        mime = row.mime,
        score = score,
        contentUri = row.sourceKey.takeIf { it.startsWith("content://") },
        path = row.sourceKey.takeIf { it.startsWith("/") } ?: resolvePath(context, row.sourceKey),
        sizeBytes = row.sizeBytes,
        modifiedAt = row.modifiedAt,
        durationMs = row.durationMs,
        width = row.width,
        height = row.height,
        thumbPath = row.thumbPath,
        frameTimeMs = row.frameTimeMs,
    )

    /** MediaStore 条目补一个文件系统路径，方便终端类工具直接链式使用。 */
    private fun resolvePath(context: Context, sourceKey: String): String? = runCatching {
        context.contentResolver.query(
            Uri.parse(sourceKey),
            arrayOf(MediaStore.MediaColumns.DATA),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}

/**
 * 向量检索抽象：暴力检索与未来 ANN 的统一入口。
 *
 * **ANN 接入点（HNSW / sqlite-vec）**：新增本接口的实现类（如 `AnnVectorIndex`），
 * 在 [MediaSearchService] 的 `vectorIndex` 处替换实例即可，`search` / `findSimilar`
 * 与工具层调用方无需改动；实现需自行维护「入库增量建索引 / 清理」的生命周期。
 * 切换时机由 [MediaSearchService.ANN_SWITCH_THRESHOLD] 标定：超过阈值后 UI 提示，
 * 随后把 [BruteForceVectorIndex] 换成 ANN 实现。
 *
 * 实现约定：query 与库内向量均已归一化（余弦相似度 = 点积），返回按分数降序、
 * 长度不超过 limit；[kinds] 为空不过滤。
 */
internal interface VectorIndex {
    fun search(
        query: FloatArray,
        rows: List<MediaIndexEntity>,
        kinds: Set<MediaKind>,
        limit: Int,
    ): List<Pair<MediaIndexEntity, Float>>
}

/**
 * 暴力点积实现：全量解码 Float16 后逐条点积排序，万级条目在手机上可接受。
 * 规模超过 [MediaSearchService.ANN_SWITCH_THRESHOLD] 后仍可用，仅提示后续切换 ANN。
 * 数量上限由调用方自负（AI 工具层 1..50；GUI 传 [MediaSearchService.UNLIMITED_RESULTS]
 * 由阈值定界），本实现只保证非负下界。
 */
internal object BruteForceVectorIndex : VectorIndex {
    override fun search(
        query: FloatArray,
        rows: List<MediaIndexEntity>,
        kinds: Set<MediaKind>,
        limit: Int,
    ): List<Pair<MediaIndexEntity, Float>> {
        val effectiveLimit = limit.coerceAtLeast(1)
        return rows.asSequence()
            .filter { row -> kinds.isEmpty() || MediaKind.from(row.kind) in kinds }
            .mapNotNull { row ->
                val vector = runCatching { Float16VectorCodec.decode(row.vector) }.getOrNull()
                    ?: return@mapNotNull null
                if (vector.size != query.size) return@mapNotNull null
                row to MediaSearchService.dot(query, vector)
            }
            .sortedByDescending { it.second }
            .take(effectiveLimit)
            .toList()
    }
}
