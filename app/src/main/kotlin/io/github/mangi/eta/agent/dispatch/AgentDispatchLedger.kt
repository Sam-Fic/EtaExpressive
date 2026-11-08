package io.github.mangi.eta.agent.dispatch

/**
 * 调度债权账本：零 I/O 纯状态机（借 tai-ji ADR-0087 的 notify-once 模型）。
 *
 * 每笔派发生灭如下：
 * ```
 * admit ──目标空闲──▶ armed(run 起跑) ──settleRun──▶ settled（通知恰一次，随后出账）
 * admit ──目标忙───▶ queued(按会话排队) ──drain──▶ armed
 * 未武装的排队项 ──cancelConversation──▶ dropped
 * drain 交出/已武装的单在起跑前发现目标仍忙 ──requeueFront/unarmToQueue──▶ queued（队首）
 * ```
 *
 * 不变量：
 * - 每 dispatchId 恰好一次 armed、至多一次结算通知；settleRun/cancelRun 幂等。
 * - 排队项只在目标会话空闲时被 [drain] 交出；调用方必须在派发前后用真实注册表复核忙态。
 * - 无债权完成一律静默：没有 armed 记录的 run 结算调用不产生任何通知。
 */
internal class AgentDispatchLedger {

    data class DispatchRequest(
        val dispatchId: String,
        val conversationId: String,
        val brief: String,
        val originText: String,
    )

    sealed interface Admission {
        /** 目标空闲：调用方应立即武装（起 run 并 [markArmed]）。 */
        data class Start(val request: DispatchRequest) : Admission

        /** 目标忙：已入队，返回当前队列深度。 */
        data class Queued(val request: DispatchRequest, val queueDepth: Int) : Admission

        data class Rejected(val code: String, val message: String) : Admission
    }

    enum class Outcome { COMPLETED, FAILED, STOPPED }

    /** 武装中的债权：run 在跑，等终态。 */
    data class ArmedClaim(
        val request: DispatchRequest,
        val runId: String,
        val armedAt: Long,
    )

    /** 一次结算：待通知的债权，以及（若队列非空）应紧接着派发的下一单。 */
    data class Settlement(
        val claim: ArmedClaim,
        val outcome: Outcome,
        val detail: String,
        val next: DispatchRequest?,
    )

    private class QueueEntry(val request: DispatchRequest) {
        var dropped = false
    }

    private val armedByRunId = linkedMapOf<String, ArmedClaim>()
    private val armedByDispatchId = linkedMapOf<String, ArmedClaim>()
    private val queues = linkedMapOf<String, ArrayDeque<QueueEntry>>()

    /** 已交出（admit-Start 或 drain）但尚未武装的请求；markArmed 只受理这一态。 */
    private val handedOut = linkedMapOf<String, DispatchRequest>()

    val armedCount: Int get() = synchronized(lock) { armedByRunId.size }
    val queuedCount: Int get() = synchronized(lock) { queues.values.sumOf { it.size } }

    private val lock = Any()

    /**
     * 受理一笔派发。[conversationBusy] 由调用方按真实 run 注册表判定；账本只记账不探测。
     * 同一 [DispatchRequest.dispatchId] 重复受理返回已存在的裁决（幂等，不重复入队）。
     */
    fun admit(request: DispatchRequest, conversationBusy: Boolean): Admission = synchronized(lock) {
        armedByDispatchId[request.dispatchId]?.let { return Admission.Start(it.request) }
        if (request.brief.isBlank()) {
            return Admission.Rejected("EMPTY_BRIEF", "brief 不能为空")
        }
        if (handedOut.containsKey(request.dispatchId)) {
            return Admission.Start(request)
        }
        val queue = queues.getOrPut(request.conversationId) { ArrayDeque() }
        if (queue.any { it.request.dispatchId == request.dispatchId }) {
            return Admission.Queued(request, queue.size)
        }
        if (conversationBusy) {
            queue.addLast(QueueEntry(request))
            return Admission.Queued(request, queue.size)
        }
        handedOut[request.dispatchId] = request
        Admission.Start(request)
    }

    fun markArmed(dispatchId: String, runId: String, atMillis: Long): ArmedClaim? = synchronized(lock) {
        armedByDispatchId[dispatchId]?.let { return it }
        // 只有 admit-Start / drain 交出的单才能武装；排队中的单绝不能绕过队列直接起跑。
        val request = handedOut.remove(dispatchId) ?: return null
        ArmedClaim(request, runId, atMillis).also {
            armedByRunId[runId] = it
            armedByDispatchId[dispatchId] = it
        }
    }

    /** run 终态结算；未知 runId 静默返回 null（无债权完成不通知）。 */
    fun settleRun(runId: String, outcome: Outcome, detail: String): Settlement? = synchronized(lock) {
        val claim = armedByRunId.remove(runId) ?: return null
        armedByDispatchId.remove(claim.request.dispatchId)
        Settlement(claim, outcome, detail, drainLocked(claim.request.conversationId))
    }

    /**
     * 目标会话空闲时交出队首；调用方起跑后必须 [markArmed]，否则该单停留在已出队未武装态。
     */
    fun drain(conversationId: String): DispatchRequest? = synchronized(lock) {
        drainLocked(conversationId)
    }

    /**
     * 恢复通道：重启对账把存活的排队单重新交出（与 [drain] 同态：交出后必须 [markArmed]）。
     */
    fun restore(request: DispatchRequest) = synchronized(lock) {
        handedOut[request.dispatchId] = request
    }

    /**
     * 排空回退：drain 交出的单在起跑前发现目标仍忙，退回会话队列队首（先于其他排队项
     * 再次被交出）。请求不在 handedOut 态（仍排队/已武装/未知）时静默忽略——
     * 已武装的单必须走 [unarmToQueue]，否则会双重入队。
     */
    fun requeueFront(request: DispatchRequest) = synchronized(lock) {
        handedOut.remove(request.dispatchId) ?: return
        queues.getOrPut(request.conversationId) { ArrayDeque() }.addFirst(QueueEntry(request))
    }

    /**
     * 起跑拒绝回退：registry 以目标忙拒绝刚武装的 run 时，撤销武装并把请求退回队列队首。
     * 未知 runId 静默忽略（非派发 run / 已结算）；调用方需同步把 DB 侧 claim 行从 armed 改回 queued。
     */
    fun unarmToQueue(runId: String): ArmedClaim? = synchronized(lock) {
        val claim = armedByRunId.remove(runId) ?: return null
        armedByDispatchId.remove(claim.request.dispatchId)
        queues.getOrPut(claim.request.conversationId) { ArrayDeque() }.addFirst(QueueEntry(claim.request))
        claim
    }

    /** 目标会话的全部排队项按取消处理（会话被用户停止/删除时）；返回被丢弃的请求。 */
    fun cancelConversation(conversationId: String): List<DispatchRequest> = synchronized(lock) {
        val queue = queues.remove(conversationId) ?: return emptyList()
        queue.mapNotNull { entry ->
            if (entry.dropped) null else {
                entry.dropped = true
                entry.request
            }
        }
    }

    /** 归属判定辅助：该会话是否仍有未结算的武装债权或排队项。 */
    fun hasPendingWork(conversationId: String): Boolean = synchronized(lock) {
        armedByRunId.values.any { it.request.conversationId == conversationId } ||
            !queues[conversationId].isNullOrEmpty()
    }

    fun pendingRequests(): List<DispatchRequest> = synchronized(lock) {
        armedByRunId.values.map { it.request } + queues.values.flatMap { queue -> queue.map { it.request } }
    }

    private fun drainLocked(conversationId: String): DispatchRequest? {
        val queue = queues[conversationId] ?: return null
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            if (!entry.dropped) {
                handedOut[entry.request.dispatchId] = entry.request
                return entry.request
            }
        }
        queues.remove(conversationId)
        return null
    }
}
