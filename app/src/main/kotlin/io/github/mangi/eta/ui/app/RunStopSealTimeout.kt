package io.github.mangi.eta.ui.app

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.localizedText
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 停止请求已发出、但 Runtime 还没回最终 RunResult 的窗口。
 * 这里只做超时判定，不碰消息、transcript 或落盘，因此 UI 解除锁定不依赖 Runtime 再回任何东西。
 */
internal class RunStopSealTimeout(
    /** Exposed so the caller's watchdog sleeps exactly as long as the seal it armed. */
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    // Injectable clock: the unlock decision must be testable without waiting 20 real seconds.
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Handle for one stop request; the generation keeps a stale watchdog off a newer seal. */
    class Ticket(val runId: String, val generation: Long)

    private class Seal(val generation: Long, val deadline: Long)

    private val seals = ConcurrentHashMap<String, Seal>()
    private val generations = AtomicLong()

    init {
        // A non-positive bound would either lock the UI forever or unlock before the result can land.
        require(timeoutMillis > 0L) { "stop seal timeout must be positive" }
    }

    /**
     * Arm the seal once per run. Returns null when a seal is already pending, so a repeated stop
     * request neither starts a second watchdog nor silently extends the user's wait.
     */
    fun beginStop(runId: String): Ticket? {
        val seal = Seal(generation = generations.incrementAndGet(), deadline = now() + timeoutMillis)
        if (seals.putIfAbsent(runId, seal) != null) return null
        return Ticket(runId = runId, generation = seal.generation)
    }

    /** Final result arrived, stop request was rejected or the conversation was torn down. */
    fun release(runId: String): Boolean = seals.remove(runId) != null

    /**
     * Watchdog wake-up. Only the expired seal this ticket armed can be claimed, and the map's
     * conditional remove succeeds for a single caller, so neither a duplicate nor a cancelled
     * watchdog can unlock the UI twice or unlock after a real result already arrived.
     */
    fun claimUnlock(ticket: Ticket): Boolean {
        val seal = seals[ticket.runId] ?: return false
        if (seal.generation != ticket.generation) return false
        if (now() < seal.deadline) return false
        return seals.remove(ticket.runId, seal)
    }

    fun isPending(runId: String): Boolean = seals.containsKey(runId)

    fun pendingCount(): Int = seals.size

    fun clear() {
        seals.clear()
    }

    companion object {
        /** Upper bound the user waits for a stop confirmation before the UI releases itself. */
        const val DEFAULT_TIMEOUT_MS = 20_000L

        /**
         * After a terminal event the run is already over, so only a short grace period is left for
         * the formal RunResult to land before the UI stops waiting for it.
         */
        const val TERMINAL_GRACE_MS = 3_000L
    }
}

/** Copy shown while the UI is still waiting for the runtime to confirm a stop. */
internal object StopSealNotices {
    /** Means "this round is still winding down", never "we are writing to disk". */
    const val PENDING = "本轮仍在停止，暂不能修改历史或继续发送；可切换或新建会话"

    /** Honest wording: the round's final result never arrived, so the record may be incomplete. */
    const val TIMED_OUT = "本轮最终结果未确认，已解除界面锁定；该轮记录可能不完整"

    /** 生产取值走字符串资源（文案真源 R.string.stopseal_*），纯 JVM 单测无 Context 时回退上方中文基准。 */
    fun pending(): String = localizedText(R.string.stopseal_pending, PENDING)

    fun timedOut(): String = localizedText(R.string.stopseal_timed_out, TIMED_OUT)
}
