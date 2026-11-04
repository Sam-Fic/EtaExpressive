package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.media.index.EmbeddingModelStore
import io.github.mangi.eta.agent.media.index.MediaFileOps
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaDir
import io.github.mangi.eta.agent.media.index.MediaFileOps.MediaTarget
import io.github.mangi.eta.agent.media.index.MediaIndexer
import io.github.mangi.eta.agent.media.index.MediaIndexService
import io.github.mangi.eta.agent.media.index.MediaSearchService
import io.github.mangi.eta.agent.media.index.MediaSourceScanner
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * `semantic_search_media` 执行器：本地多模态语义检索（EmbeddingGemma 2，全程离线）。
 *
 * GUI 搜索框与本工具共用 [MediaSearchService]，保证「用户搜到的」与「AI 搜到的」一致；
 * 结果里的 path / content_uri 可直接被 read_image、终端文件工具等已有能力链式使用。
 */
internal class AgentMediaSearchTools(private val context: Context) {

    fun execute(args: JSONObject): String = io {
        try {
            when (val action = args.optString("action").trim()) {
                "search" -> search(args)
                "find_similar" -> findSimilar(args)
                "list_sources" -> listSources()
                "index_status" -> indexStatus()
                "reindex" -> reindex()
                "set_threshold" -> setThreshold(args)
                "batch_act" -> batchAct(args)
                else -> error("INVALID_ARGUMENT", "action 取值无效：$action")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (invalid: IllegalArgumentException) {
            error("INVALID_ARGUMENT", invalid.message ?: "参数无效")
        } catch (failure: Throwable) {
            error("INTERNAL_ERROR", failure.message ?: "媒体语义检索失败")
        }
    }

    private suspend fun search(args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isEmpty()) return error("INVALID_ARGUMENT", "action=search 需要 query")
        // 参数先于就绪状态校验，保证非法 kinds 一律报 INVALID_ARGUMENT。
        val kinds = parseKinds(args)
        val minScore = parseMinScore(args)
        notReady()?.let { return it }
        val result = MediaSearchService.search(
            context,
            query = query,
            kinds = kinds,
            limit = parseLimit(args),
            minScore = minScore,
        )
        return JSONObject()
            .put("ok", true)
            .put("action", "search")
            .put("query", result.query)
            .put("model", result.modelId)
            .put("dim", result.dim)
            .put("count", result.hits.size)
            .put("total_candidates", result.totalCandidates)
            .put("min_score", minScore.toDouble())
            .put("results", JSONArray().also { array -> result.hits.forEach { array.put(hitJson(it)) } })
            .toString()
    }

    private suspend fun findSimilar(args: JSONObject): String {
        val reference = args.optString("reference").trim()
        if (reference.isEmpty()) return error("INVALID_ARGUMENT", "action=find_similar 需要 reference")
        // 参数先于就绪状态校验，保证非法 kinds 一律报 INVALID_ARGUMENT。
        val kinds = parseKinds(args)
        notReady()?.let { return it }
        val result = MediaSearchService.findSimilar(
            context,
            reference = reference,
            kinds = kinds,
            limit = parseLimit(args),
        )
        return JSONObject()
            .put("ok", true)
            .put("action", "find_similar")
            .put("reference", reference)
            .put("model", result.modelId)
            .put("dim", result.dim)
            .put("count", result.hits.size)
            .put("results", JSONArray().also { array -> result.hits.forEach { array.put(hitJson(it)) } })
            .toString()
    }

    private suspend fun listSources(): String {
        val sources = EtaDatabase.get(context).mediaIndexDao().sources()
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("id", source.id)
                    .put("type", source.type)
                    .put("label", source.label)
                    .put("uri", source.uri)
                    .put("enabled", source.enabled)
                    .put("item_count", source.itemCount)
                    .put("last_scan_at", source.lastScanAt ?: JSONObject.NULL),
            )
        }
        // 已登记但尚未配置的推荐源一并列出，方便模型引导用户完成首次设置。
        val configured = sources.map { it.id }.toSet()
        MediaSourceScanner.recommendedSources(context)
            .filter { it.id !in configured }
            .forEach { source ->
                array.put(
                    JSONObject()
                        .put("id", source.id)
                        .put("type", source.type)
                        .put("label", source.label)
                        .put("enabled", false)
                        .put("configured", false),
                )
            }
        return JSONObject()
            .put("ok", true)
            .put("action", "list_sources")
            .put("count", array.length())
            .put("sources", array)
            .toString()
    }

    private suspend fun indexStatus(): String {
        val status = MediaSearchService.status(context)
        val progress = MediaIndexer.state.value
        return JSONObject()
            .put("ok", true)
            .put("action", "index_status")
            .put("ready", status.ready)
            .put("model", status.modelId)
            .put("model_downloaded", status.modelDownloaded)
            .put("dim", status.dim)
            .put("indexed_count", status.indexedCount)
            .put("last_indexed_at", status.lastIndexedAt ?: JSONObject.NULL)
            .put("enabled_sources", status.enabledSources)
            .put("indexing", status.indexing)
            .put("index_mode", EmbeddingModelStore.indexMode(context).wire)
            // 增量语义标注：已索引且未变化的条目计 skipped、消失条目计 removed，均不会重复处理已索引内容。
            .put("incremental", true)
            .put(
                "progress",
                JSONObject()
                    .put("phase", progress.phase)
                    .put("processed", progress.processed)
                    .put("total", progress.total)
                    .put("indexed", progress.indexed)
                    .put("skipped", progress.skipped)
                    .put("failed", progress.failed)
                    .put("removed", progress.removed)
                    // 缩略图自愈：repair 是"向量未变、仅重建缩略图"（不重新嵌入）；
                    // thumb_failed 是缩略图生成失败数，不影响条目本身的索引状态。
                    .put("thumb_repaired", progress.thumbRepaired)
                    .put("thumb_failed", progress.thumbFailed),
            )
            .toString()
    }

    /** reindex 是长任务：只负责触发（交 [MediaIndexService] 按存储的索引档位后台执行），进度经 action=index_status 轮询。 */
    private fun reindex(): String {
        val mode = EmbeddingModelStore.indexMode(context)
        if (MediaIndexer.isRunning()) {
            return JSONObject()
                .put("ok", true)
                .put("action", "reindex")
                .put("started", false)
                .put("index_mode", mode.wire)
                .put("note", "索引任务已在进行中（增量索引，已索引且未变化的条目会自动跳过），稍后用 action=index_status 查询进度")
                .toString()
        }
        MediaIndexService.start(context)
        return JSONObject()
            .put("ok", true)
            .put("action", "reindex")
            .put("started", true)
            .put("index_mode", mode.wire)
            .put("note", "后台增量索引已启动（模式：${mode.wire}），已索引且未变化的条目会自动跳过；用 action=index_status 查询进度")
            .toString()
    }

    /**
     * 持久化用户偏好阈值（与检索页滑杆同一 prefs key）：后续 search / batch_act 省略
     * min_score 时按这个值过滤，AI 不必每次重复传参。
     */
    private fun setThreshold(args: JSONObject): String {
        if (!args.has("min_score")) {
            return error("INVALID_ARGUMENT", "action=set_threshold 需要 min_score（0–1）")
        }
        val raw = args.optDouble("min_score", Double.NaN)
        if (raw.isNaN()) return error("INVALID_ARGUMENT", "min_score 需为数字")
        val value = raw.toFloat().coerceIn(0f, 1f)
        EmbeddingModelStore.setSearchMinScore(context, value)
        return JSONObject()
            .put("ok", true)
            .put("action", "set_threshold")
            .put("min_score", value.toDouble())
            .toString()
    }

    /**
     * 对检索结果批量移动/复制：显式 source_keys 优先，省略时按 query 现场检索取命中。
     * 逐项执行、单项失败不中断，返回成功 / 失败清单供模型继续处理。
     */
    private suspend fun batchAct(args: JSONObject): String {
        val wire = args.optString("operation").trim()
        val operation = when (wire) {
            MediaFileOps.Op.MOVE.wire -> MediaFileOps.Op.MOVE
            MediaFileOps.Op.COPY.wire -> MediaFileOps.Op.COPY
            else -> return error("INVALID_ARGUMENT", "operation 取值无效：$wire（可选 move / copy）")
        }
        val targetRaw = args.optString("target_dir").trim()
        if (targetRaw.isEmpty()) {
            return error("TARGET_REQUIRED", "action=batch_act 需要 target_dir（SAF tree uri 或绝对路径）")
        }
        val targetDir = MediaDir.parse(targetRaw)
            ?: return error("TARGET_REQUIRED", "target_dir 需为 SAF tree uri（content://…/tree/…）或绝对路径")
        // 参数先于就绪状态校验，保证非法 kinds / 来源缺省一律报 INVALID_ARGUMENT。
        val kinds = parseKinds(args)
        val minScore = parseMinScore(args)
        val spec = MediaFileOps.parseBatchSourceSpec(
            sourceKeys = parseSourceKeys(args),
            query = args.optString("query"),
        ) ?: return error("INVALID_ARGUMENT", "action=batch_act 需要 source_keys 或 query（二选一）")
        val sources = when (spec.mode) {
            MediaFileOps.BatchSourceMode.SOURCE_KEYS -> spec.keys.mapNotNull { MediaTarget.parse(it) }
            MediaFileOps.BatchSourceMode.QUERY -> {
                notReady()?.let { return it }
                MediaSearchService.search(
                    context,
                    query = spec.query,
                    kinds = kinds,
                    limit = parseBatchLimit(args),
                    minScore = minScore,
                ).hits.map { MediaTarget.from(it) }
            }
        }
        if (sources.isEmpty()) {
            return error("INVALID_ARGUMENT", "没有可操作的结果：source_keys 为空或检索无命中")
        }
        val batch = MediaFileOps.runBatch(context, sources, targetDir, operation)
        val succeeded = JSONArray().also { array ->
            batch.succeeded.forEach { item ->
                array.put(
                    JSONObject()
                        .put("source_key", item.sourceKey)
                        .put("new_uri", item.newUri ?: JSONObject.NULL)
                        .put("new_path", item.newPath ?: JSONObject.NULL),
                )
            }
        }
        val failed = JSONArray().also { array ->
            batch.failed.forEach { item ->
                array.put(
                    JSONObject()
                        .put("source_key", item.sourceKey)
                        .put("code", item.code)
                        .put("message", item.message),
                )
            }
        }
        return JSONObject()
            .put("ok", true)
            .put("action", "batch_act")
            .put("operation", operation.wire)
            .put("target_dir", targetRaw)
            .put("count", batch.total)
            .put("succeeded_count", batch.succeeded.size)
            .put("failed_count", batch.failed.size)
            .put("succeeded", succeeded)
            .put("failed", failed)
            .toString()
    }

    /** 未就绪时返回结构化错误，就绪时返回 null。 */
    private suspend fun notReady(): String? {
        val status = MediaSearchService.status(context)
        return when {
            !status.modelDownloaded -> error(
                "MEDIA_INDEX_NOT_READY",
                "嵌入模型尚未下载。请提示用户到「媒体搜索」设置页下载模型并确认扫描范围。",
            )
            status.enabledSources == 0 -> error(
                "MEDIA_INDEX_NOT_READY",
                "尚未确认扫描范围。请提示用户到「媒体搜索」设置页确认要索引的范围。",
            )
            else -> null
        }
    }

    private fun parseKinds(args: JSONObject): Set<MediaKind> {
        val raw = args.optJSONArray("kinds") ?: return emptySet()
        val kinds = mutableSetOf<MediaKind>()
        for (index in 0 until raw.length()) {
            val wire = raw.optString(index).trim()
            val kind = MediaKind.from(wire)
                ?: throw IllegalArgumentException("kinds 取值无效：$wire（可选 image / video / audio / document）")
            kinds += kind
        }
        return kinds
    }

    private fun parseLimit(args: JSONObject): Int =
        if (args.has("limit")) args.optInt("limit", 10).coerceIn(1, 50) else DEFAULT_LIMIT

    /** batch_act 现场检索的条数：默认 20，上限 50。 */
    private fun parseBatchLimit(args: JSONObject): Int =
        if (args.has("limit")) args.optInt("limit", DEFAULT_BATCH_LIMIT).coerceIn(1, 50) else DEFAULT_BATCH_LIMIT

    /** batch_act 的 source_keys：去空白、保留顺序（去重交给 MediaFileOps.parseBatchSourceSpec）。 */
    private fun parseSourceKeys(args: JSONObject): List<String> {
        val raw = args.optJSONArray("source_keys") ?: return emptyList()
        return (0 until raw.length()).mapNotNull { index ->
            raw.optString(index).trim().takeIf { it.isNotEmpty() }
        }
    }

    /**
     * 匹配度阈值：schema 限 0–1，越界收敛；省略时用 set_threshold 持久化的用户偏好
     * （与检索页滑杆同一 key），从未设置过则为 0（不过滤）。
     */
    private fun parseMinScore(args: JSONObject): Float =
        if (args.has("min_score")) {
            args.optDouble("min_score", 0.0).toFloat().coerceIn(0f, 1f)
        } else {
            EmbeddingModelStore.searchMinScore(context)
        }

    private fun hitJson(hit: MediaSearchService.Hit): JSONObject {
        val item = JSONObject()
            // source_key 是索引主键，也是 batch_act 引用结果做移动/复制的唯一凭据。
            .put("source_key", hit.sourceKey)
            .put("kind", hit.kind.wire)
            .put("display_name", hit.displayName)
            .put("mime", hit.mime)
            .put("score", hit.score.toDouble())
            .put("size_bytes", hit.sizeBytes)
            .put("modified_at", hit.modifiedAt)
        hit.contentUri?.let { item.put("content_uri", it) }
        hit.path?.let { item.put("path", it) }
        hit.durationMs?.let { item.put("duration_ms", it) }
        hit.width?.let { item.put("width", it) }
        hit.height?.let { item.put("height", it) }
        // 视频「时刻检索」的命中时刻（毫秒）；二段精排未命中/未执行时省略。
        hit.frameTimeMs?.let { item.put("matched_at_ms", it) }
        return item
    }

    private fun io(block: suspend () -> String): String = runBlocking(Dispatchers.IO) { block() }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()

    private companion object {
        const val DEFAULT_LIMIT = 10
        const val DEFAULT_BATCH_LIMIT = 20
    }
}
