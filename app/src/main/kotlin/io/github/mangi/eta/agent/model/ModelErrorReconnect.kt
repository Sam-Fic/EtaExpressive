package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Injectable monotonic time and wakeups. Neither wall-clock changes nor retry count set the budget. */
internal interface ReconnectTiming {
    fun nowMs(): Long
    fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable
}

internal object SystemReconnectTiming : ReconnectTiming {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-model-reconnect").apply { isDaemon = true }
    }
    override fun nowMs(): Long = System.nanoTime() / 1_000_000
    override fun schedule(delayMs: Long, action: () -> Unit): AutoCloseable {
        val task = scheduler.schedule(action, delayMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
        return AutoCloseable { task.cancel(false) }
    }
}

/** One disconnect, including waits AND in-flight attempts. Closing never cancels the user run. */
internal class ModelErrorReconnect(
    private val round: Int,
    private val windowMs: Long?,
    private val timing: ReconnectTiming,
    private val emit: (AgentEvent) -> Unit,
    reason: AgentModelFailure,
    private val secrets: List<String>,
) {
    private val lock = Any()
    private val startedMs = timing.nowMs()
    private var id = UUID.randomUUID().toString()
    private var pausedAtMs: Long? = null
    private var pausedDurationMs = 0L
    private var started = false
    private var wakeupGeneration = 0L
    private var elapsedMs = 0L
    private var terminal = false
    private var wakeup: AutoCloseable? = null
    private var transport: AgentRunController.TransportScope? = null
    private var lastReason = reason
    @Volatile var expired = false
        private set
    @Volatile private var observerFailure: Throwable? = null

    fun start() = synchronized(lock) {
        if (terminal) return@synchronized
        started = true
        if (pausedAtMs != null) return@synchronized
        if (windowMs == 0L) finishLocked("failed")
        else {
            sendLocked("running")
            scheduleLocked()
        }
    }

    /** Pause closes the visible segment and freezes its budget, not the task/tool owners. */
    fun bind(controller: AgentRunController): AutoCloseable {
        val stopBinding = controller.register(wakeBeforeCleanup = true) { finish("stopped") }
        val pauseBinding = controller.observePause { paused ->
            try { setPaused(paused) }
            catch (failure: Throwable) {
                observerFailure = failure
                synchronized(lock) { transport }?.cancelTransport()
            }
        }
        return AutoCloseable { pauseBinding.close(); stopBinding.close() }
    }

    private fun setPaused(paused: Boolean) {
        val interruptedScope = synchronized(lock) {
            if (terminal) return
            if (paused) {
                if (pausedAtMs != null) return
                elapsedLocked()
                pausedAtMs = timing.nowMs()
                wakeupGeneration++
                wakeup?.close()
                wakeup = null
                sendLocked("stopped")
                transport
            } else {
                val pauseStart = pausedAtMs ?: return
                pausedDurationMs += (timing.nowMs() - pauseStart).coerceAtLeast(0)
                pausedAtMs = null
                // Stopped IDs are terminal in chat projection. Resume uses a new visible segment.
                id = UUID.randomUUID().toString()
                if (started) {
                    sendLocked("running")
                    scheduleLocked()
                }
                null
            }
        }
        // Expire the old attempt gate as well as its HTTP resources. Fast resume cannot
        // make callbacks from a paused request look like fresh recovery evidence.
        interruptedScope?.cancelTransport()
    }

    fun updateReason(reason: AgentModelFailure) = synchronized(lock) { lastReason = reason }

    fun attach(scope: AgentRunController.TransportScope) = synchronized(lock) {
        transport = scope
        if (expired || pausedAtMs != null || observerFailure != null) scope.cancelTransport()
    }
    fun detach(scope: AgentRunController.TransportScope) = synchronized(lock) {
        if (transport === scope) transport = null
    }

    /** Also check on the request thread: a late successful return cannot beat the deadline. */
    fun check() {
        observerFailure?.let { throw it }
        val cancel = synchronized(lock) {
            if (!terminal && windowMs != null && elapsedLocked() >= windowMs) expired = true
            if (expired) transport else null
        }
        cancel?.cancelTransport()
        if (expired) throw AgentModelFailure(
            "ERROR_RECONNECT_DEADLINE", false,
            "错误重连期限已到，模型请求仍未恢复；此前正文和工具结果已保留",
            lastReason,
        )
    }

    fun remainingMs(): Long? = synchronized(lock) {
        windowMs?.let { (it - elapsedLocked()).coerceAtLeast(0) }
    }

    fun finish(status: String) = synchronized(lock) {
        if (terminal) return@synchronized
        val finalStatus = if (pausedAtMs != null) "stopped" else status
        if (finalStatus == "succeeded" && (expired || (windowMs != null && elapsedLocked() >= windowMs))) {
            expired = true
            throw AgentModelFailure("ERROR_RECONNECT_DEADLINE", false,
                "错误重连期限已到，未接纳迟到的响应；此前正文和工具结果已保留", lastReason)
        }
        finishLocked(finalStatus)
    }

    private fun elapsedLocked(): Long {
        elapsedMs = maxOf(elapsedMs, ((pausedAtMs ?: timing.nowMs()) - startedMs - pausedDurationMs).coerceAtLeast(0))
        return elapsedMs
    }
    private fun sendLocked(status: String) {
        emit(AgentEvent.ErrorReconnectChanged(round, id, status, elapsedLocked(), lastReason.code,
            AgentHttpFailureDiagnostics.safe(lastReason.message.orEmpty(), secrets, 600)))
    }
    private fun finishLocked(status: String) {
        terminal = true
        wakeupGeneration++
        wakeup?.close()
        wakeup = null
        sendLocked(status)
    }
    private fun scheduleLocked() {
        if (terminal || pausedAtMs != null) return
        val generation = ++wakeupGeneration
        val delay = windowMs?.let { minOf(1_000L, (it - elapsedLocked()).coerceAtLeast(1)) } ?: 1_000L
        wakeup = timing.schedule(delay) { tick(generation) }
    }
    private fun tick(generation: Long) {
        var cancel: AgentRunController.TransportScope? = null
        synchronized(lock) {
            if (terminal || pausedAtMs != null || generation != wakeupGeneration) return
            try {
                if (windowMs != null && elapsedLocked() >= windowMs) {
                    expired = true
                    cancel = transport
                    // A terminal event is emitted by the request thread after transport has unwound.
                } else {
                    sendLocked("running")
                    scheduleLocked()
                }
            } catch (failure: Throwable) {
                // Propagate on the request thread; do not turn observer bugs/Error into network retries.
                observerFailure = failure
                cancel = transport
            }
        }
        cancel?.cancelTransport()
    }
}
