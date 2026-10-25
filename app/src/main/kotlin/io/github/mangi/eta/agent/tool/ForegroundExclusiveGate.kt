package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 主屏按 run 独占至工具宿主关闭。冲突立即拒绝，不排队、不抢占、不延后补执行。
 * 离屏浏览器有自己的会话隔离，不占用 Android 主屏。
 */
internal object ForegroundExclusiveGate {
    enum class Admission { ACQUIRED, BUSY, CLOSED }

    private val lock = ReentrantLock()
    private var ownerRunId: String? = null

    fun shouldSerialize(toolName: String): Boolean {
        val name = toolName.trim().lowercase()
        return AgentOverlayVisibilityPolicy.isForegroundOperationTool(name) ||
            name in setOf("wait_for_text", "wait_for_package", "set_timer")
    }

    fun acquire(runId: String, isClosed: () -> Boolean = { false }): Admission =
        admission(runId, claim = true, isClosed)

    /** Preflight before ASK: does not claim an idle screen; execution must acquire again. */
    fun checkAvailability(runId: String, isClosed: () -> Boolean = { false }): Admission =
        admission(runId, claim = false, isClosed)

    private fun admission(runId: String, claim: Boolean, isClosed: () -> Boolean): Admission {
        val id = runId.trim()
        if (id.isEmpty() || Thread.currentThread().isInterrupted) return Admission.CLOSED
        try {
            lock.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return Admission.CLOSED
        }
        try {
            if (isClosed() || Thread.currentThread().isInterrupted) return Admission.CLOSED
            if (ownerRunId != null && ownerRunId != id) return Admission.BUSY
            if (claim) ownerRunId = id
            return Admission.ACQUIRED
        } finally {
            lock.unlock()
        }
    }

    fun release(runId: String) {
        val id = runId.trim()
        if (id.isEmpty()) return
        lock.withLock {
            if (ownerRunId == id) ownerRunId = null
        }
    }

    internal fun resetForTests() {
        lock.withLock { ownerRunId = null }
    }

    internal fun ownerForTests(): String? = lock.withLock { ownerRunId }
}
