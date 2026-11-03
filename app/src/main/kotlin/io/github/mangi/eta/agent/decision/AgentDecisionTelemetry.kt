package io.github.mangi.eta.agent.decision

/**
 * 判断模型的进程内只读统计（M4）。
 *
 * 用途：设置页展示「决策次数 / 升级次数与原因 / 缓存命中率 / 降级与原因 / 漏判次数」，
 * 以及给升级触发提供「历史命中率」样本。**不写文件、不落库、不跨进程**：
 *
 * - 生命周期从进程创建到进程结束；会话数上限 [MAX_CONVERSATIONS]（LRU，按写入淘汰）；
 * - 每种原因只保留 [MAX_REASONS_PER_KIND] 个键，超出按最早写入淘汰，避免恶意输入打爆内存；
 * - 所有读写都在 [lock] 内完成，返回的是不可变 [Snapshot]，可跨线程读取；
 * - [reset] 是唯一清空入口（设置页「清理缓存与粘性状态」会调用）。
 *
 * 这些数字只用于**评估与调参参考**：不能据此自动修改判据（样本少、口径也不可能完美）。
 */
internal object AgentDecisionTelemetry {

    const val MAX_CONVERSATIONS = 8
    private const val MAX_REASONS_PER_KIND = 6

    private data class Entry(
        var decisions: Int = 0,
        var escalations: Int = 0,
        var cacheHits: Int = 0,
        var degraded: Int = 0,
        var mismatches: Int = 0,
        val escalationReasons: MutableMap<String, Int> = linkedMapOf(),
        val degradedReasons: MutableMap<String, Int> = linkedMapOf(),
    )

    /** 某个会话（或全局）的只读快照。 */
    data class Snapshot(
        val decisions: Int,
        val escalations: Int,
        val cacheHits: Int,
        val degraded: Int,
        val mismatches: Int,
        val escalationReasons: Map<String, Int>,
        val degradedReasons: Map<String, Int>,
    ) {
        /** 缓存命中率 = 命中 /（真实调用 + 命中）；无请求时为 0。 */
        val cacheHitRate: Double
            get() {
                val total = decisions + cacheHits
                return if (total <= 0) 0.0 else cacheHits.toDouble() / total
            }

        /** 漏判率 = 漏判工具次数 / 决策次数，封顶 1.0（一次决策可能漏多组）。 */
        val missRate: Double
            get() = if (decisions <= 0) 0.0 else (mismatches.toDouble() / decisions).coerceIn(0.0, 1.0)
    }

    /** 升级触发用的历史样本；样本不足时由调用方回退到默认阈值。 */
    data class History(val samples: Int, val missRate: Double)

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(MAX_CONVERSATIONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_CONVERSATIONS
    }

    fun recordDecision(conversationId: String) = touch(conversationId) { it.decisions++ }

    fun recordEscalation(conversationId: String, reason: String) = touch(conversationId) {
        it.escalations++
        bump(it.escalationReasons, reason)
    }

    fun recordCacheHit(conversationId: String) = touch(conversationId) { it.cacheHits++ }

    fun recordDegraded(conversationId: String, reason: String) = touch(conversationId) {
        it.degraded++
        bump(it.degradedReasons, reason)
    }

    fun recordMismatch(conversationId: String) = touch(conversationId) { it.mismatches++ }

    fun snapshot(conversationId: String): Snapshot? = synchronized(lock) {
        entries[conversationId]?.let(::snapshotOf)
    }

    /** 所有已记录会话的合并视图（设置页是 App 级页面，拿不到「当前会话」，因此看全局）。 */
    fun globalSnapshot(): Snapshot = synchronized(lock) {
        if (entries.isEmpty()) return@synchronized Snapshot(0, 0, 0, 0, 0, emptyMap(), emptyMap())
        val merged = Entry()
        for (entry in entries.values) {
            merged.decisions += entry.decisions
            merged.escalations += entry.escalations
            merged.cacheHits += entry.cacheHits
            merged.degraded += entry.degraded
            merged.mismatches += entry.mismatches
            mergeReasons(merged.escalationReasons, entry.escalationReasons)
            mergeReasons(merged.degradedReasons, entry.degradedReasons)
        }
        snapshotOf(merged)
    }

    fun history(conversationId: String): History = synchronized(lock) {
        val entry = entries[conversationId] ?: return@synchronized History(0, 0.0)
        val decisions = entry.decisions
        val rate = if (decisions <= 0) 0.0 else (entry.mismatches.toDouble() / decisions).coerceIn(0.0, 1.0)
        History(decisions, rate)
    }

    fun reset() {
        synchronized(lock) { entries.clear() }
    }

    private inline fun touch(conversationId: String, block: (Entry) -> Unit) {
        if (conversationId.isBlank()) return
        synchronized(lock) {
            val entry = entries.getOrPut(conversationId) { Entry() }
            block(entry)
        }
    }

    private fun snapshotOf(entry: Entry): Snapshot = Snapshot(
        decisions = entry.decisions,
        escalations = entry.escalations,
        cacheHits = entry.cacheHits,
        degraded = entry.degraded,
        mismatches = entry.mismatches,
        escalationReasons = entry.escalationReasons.toMap(),
        degradedReasons = entry.degradedReasons.toMap(),
    )

    private fun bump(reasons: MutableMap<String, Int>, reason: String) {
        val key = reason.ifBlank { "unknown" }
        reasons[key] = (reasons[key] ?: 0) + 1
        while (reasons.size > MAX_REASONS_PER_KIND) {
            reasons.remove(reasons.keys.first())
        }
    }

    private fun mergeReasons(target: MutableMap<String, Int>, source: Map<String, Int>) {
        for ((key, value) in source) {
            target[key] = (target[key] ?: 0) + value
        }
        while (target.size > MAX_REASONS_PER_KIND) {
            target.remove(target.keys.first())
        }
    }
}
