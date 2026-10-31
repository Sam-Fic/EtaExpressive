package io.github.mangi.eta.agent.runtime


import io.github.mangi.eta.agent.question.AgentQuestionCoordinator
import io.github.mangi.eta.agent.question.AgentQuestionLedger
import io.github.mangi.eta.agent.question.AgentQuestionSnapshot
import io.github.mangi.eta.agent.question.AgentQuestionStateIndex
import io.github.mangi.eta.agent.question.AgentQuestionInterruptedException
import java.util.UUID
import io.github.mangi.eta.agent.question.AgentQuestionReceipt
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 一次 Runtime run 的控制权和唯一终态。
 *
 * Service 替换、用户取消和正常完成都必须经过此对象，避免旧 run 向新 reply channel 发消息，
 * 也避免同一 run 发送两个最终结果。
 */
internal class AgentRuntimeSession(
    val runId: String,
    val controller: AgentRunController = AgentRunController(),
    eventSink: ((AgentEvent) -> Unit)? = null,
    resultSink: ((AgentRuntimeWire.RunResult) -> Unit)? = null,
    // Freeze once for both execution and presentation, including late terminal callbacks.
    taskSurfaceMode: AgentTaskSurfaceMode =
        runCatching { AgentTaskSurface.stored() }.getOrDefault(AgentTaskSurfaceMode.ASK),
    private val questionLedger: AgentQuestionLedger? = null,
    val questionOwnerGeneration: String = UUID.randomUUID().toString(),
    // Android queues Main's terminal work; plain JVM sessions retain synchronous semantics.
    private val terminalWork: ((() -> Unit) -> Unit) = { it() },
    // 终态发布前的最后一刻：仍在提交窗口内、订阅者尚未收到结果。运行登记在这里注销。
    private val beforeTerminalPublish: () -> Unit = {},
) {
    /** “每次询问”在用户选定后改成前台或后台一次，之后不再变。 */
    @Volatile
    var taskSurfaceMode: AgentTaskSurfaceMode = taskSurfaceMode
        private set

    private val surfaceLock = Any()

    fun resolveTaskSurface(mode: AgentTaskSurfaceMode): Boolean = synchronized(surfaceLock) {
        if (taskSurfaceMode != AgentTaskSurfaceMode.ASK || mode == AgentTaskSurfaceMode.ASK) return@synchronized false
        taskSurfaceMode = mode
        true
    }

    private enum class State {
        RUNNING,
        STOPPING,
        COMMITTING,
        TERMINAL,
    }

    private val lock = ReentrantLock()
    @Volatile private var state = State.RUNNING
    @Volatile private var stopSignalled = false
    @Volatile private var questionEvidenceFailed = false
    private val questionIndex = AgentQuestionStateIndex(runId)
    private val questionAdmission = ReentrantLock()
    private var admittedQuestions = 0
    private val questionsFinished = lock.newCondition()
    private var terminalAfterQuestions: (() -> Unit)? = null
    private val historySnapshots = linkedMapOf<String, HistorySnapshot>()

    data class HistorySnapshot(
        val id: String,
        val round: Int,
        val history: List<io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage>,
    )

    /** Called only by the model worker at closed request boundaries, before RoundStarted. */
    fun publishHistorySnapshot(round: Int,
        history: List<io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage>): String {
        val frozen = java.util.Collections.unmodifiableList(ArrayList(history))
        return withSessionLock {
            if (state != State.RUNNING || stopSignalled) return@withSessionLock ""
            val id = UUID.randomUUID().toString()
            historySnapshots[id] = HistorySnapshot(id, round, frozen)
            // Retain one previous boundary for an in-flight click, without retaining all requests.
            while (historySnapshots.size > 2) historySnapshots.remove(historySnapshots.keys.first())
            id
        }
    }

    fun historySnapshot(id: String): HistorySnapshot? = withSessionLock {
        if (state != State.RUNNING || stopSignalled) null else historySnapshots[id]
    }

    private val replayEvents = mutableListOf<AgentEvent>()
    private val subscribers = mutableListOf<Subscriber>()
    private val afterUnlock = mutableListOf<() -> Unit>()
    private val pendingEvents = ArrayDeque<EventDelivery>()
    private var dispatchDepth = 0
    private var childCompactions = 0
    private val childCompactionsFinished = lock.newCondition()
    private val childCompactionDepth = ThreadLocal<Int>()
    private var terminalAfterChildCompactions: (() -> Unit)? = null

    // Identity matters when an interrupted attach removes its provisional subscriber.
    private class Subscriber(
        val eventSink: (AgentEvent) -> Unit,
        val resultSink: (AgentRuntimeWire.RunResult) -> Unit,
    )

    private class EventDelivery(val event: AgentEvent, val recipients: List<Subscriber>)

    init {
        questionLedger?.registerOwner(runId, questionOwnerGeneration)
        if (eventSink != null || resultSink != null) {
            subscribers += Subscriber(
                eventSink = eventSink ?: {},
                resultSink = resultSink ?: {},
            )
        }
    }

    /**
     * Event/replay callbacks may reenter the session. Leaving an inner lock scope is
     * not enough: resource cancellation can wait for another thread that needs this
     * lock. The outermost scope takes its actions before unlocking, then runs all of
     * them without a session lock, even if a callback or an earlier action fails.
     */
    private inline fun <T> withSessionLock(block: () -> T): T {
        lock.lock()
        var blockFailure: Throwable? = null
        try {
            return block()
        } catch (failure: Throwable) {
            blockFailure = failure
            throw failure
        } finally {
            val actions = if (lock.holdCount == 1) {
                afterUnlock.toList().also { afterUnlock.clear() }
            } else {
                emptyList()
            }
            lock.unlock()
            var actionFailure = blockFailure
            for (action in actions) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val previous = actionFailure
                    if (previous == null) actionFailure = failure
                    else if (previous !== failure) previous.addSuppressed(failure)
                }
            }
            if (blockFailure == null) actionFailure?.let { throw it }
        }
    }

    /** User stop cancels resources after callbacks unwind; the worker still seals history. */
    fun requestStop(): Boolean = withSessionLock {
        if (state != State.RUNNING) return false
        state = State.STOPPING
        afterUnlock += { controller.cancel() }
        true
    }

    @Volatile
    var terminalResult: AgentRuntimeWire.RunResult? = null
        private set

    val isTerminal: Boolean
        get() = state == State.TERMINAL

    /** 终态提交窗口：结果已定、尚未发布；准入用它把同会话后来者继续挡在门外。 */
    val isCommitting: Boolean
        get() = state == State.COMMITTING

    /** Main may signal stop without competing with callbacks or worker persistence. */
    fun signalStop() { stopSignalled = true }

    fun emit(event: AgentEvent): Boolean {
        if (event is AgentEvent.QuestionRequested || event is AgentEvent.QuestionResolved) return emitQuestion(event)
        return withSessionLock {
            if (state != State.RUNNING || stopSignalled) return false
            publishEvent(event)
            true
        }
    }

    /** Worker pipeline with accepted-only checkpoint work, also outside the state lock. */
    fun emit(event: AgentEvent, beforeDispatch: () -> Unit): Boolean {
        if (event is AgentEvent.QuestionRequested || event is AgentEvent.QuestionResolved) return emitQuestion(event, beforeDispatch)
        if (lock.isHeldByCurrentThread) return false
        questionAdmission.lock()
        try {
            withSessionLock {
                if (state != State.RUNNING || stopSignalled) return false
                admittedQuestions++
            }
            try { beforeDispatch() }
            catch (failure: Throwable) {
                withSessionLock { finishQuestionAdmission() }
                throw failure
            }
            return withSessionLock {
                try { publishEvent(event); true }
                finally { finishQuestionAdmission() }
            }
        } finally { questionAdmission.unlock() }
    }

    /**
     * Reserve under the state lock, persist WITHOUT it, then apply/dispatch the admitted evidence.
     * COMMITTING rejects new reservations but must wait for existing ones. No ledger callback
     * acquires the session lock, and no Main stop/registry operation waits for file I/O.
     */
    fun emitQuestion(event: AgentEvent, beforeDispatch: () -> Unit = {}): Boolean {
        if (lock.isHeldByCurrentThread) return false // Reentrant callbacks cannot perform file I/O under the state lock.
        questionAdmission.lock()
        try {
            val snapshot = withSessionLock {
                if (questionEvidenceFailed || state != State.RUNNING && state != State.STOPPING) return false
                val prepared = questionIndex.prepare(event) ?: return false
                admittedQuestions++
                prepared
            }
            try {
                questionLedger?.accept(questionOwnerGeneration, snapshot)
                beforeDispatch()
            } catch (failure: Throwable) {
                questionEvidenceFailed = true
                if (failure !is AgentQuestionInterruptedException) questionLedger?.invalidate()
                withSessionLock { finishQuestionAdmission() }
                throw AgentQuestionInterruptedException(failure)
            }
            return withSessionLock {
                try {
                    questionIndex.accept(snapshot)
                    publishEvent(event)
                    true
                } finally {
                    finishQuestionAdmission()
                }
            }
        } finally {
            questionAdmission.unlock()
        }
    }

    /**
     * Record and snapshot recipients at acceptance, not when the queue drains. A
     * subscriber attached during a broadcast gets earlier events only via replay.
     * Nested events wait for every recipient of the current event; replay similarly
     * holds live delivery until its acknowledgement, including non-replayable events.
     */
    private fun publishEvent(event: AgentEvent) {
        recordForReplay(event)
        pendingEvents.addLast(EventDelivery(event, subscribers.toList()))
        drainEvents()
    }

    private fun drainEvents() {
        if (dispatchDepth != 0) return
        dispatchDepth++
        try {
            while (pendingEvents.isNotEmpty() && state != State.TERMINAL) {
                val delivery = pendingEvents.removeFirst()
                for (subscriber in delivery.recipients) {
                    if (state == State.TERMINAL) break
                    if (subscriber in subscribers) {
                        runCatching { subscriber.eventSink(delivery.event) }
                    }
                }
            }
            // STOPPING/COMMITTING close admission, not delivery of already accepted
            // events. Drain those before afterUnlock persistence/result publication.
            // Immediate cancellation alone cuts delivery short at its terminal boundary.
            if (state == State.TERMINAL) pendingEvents.clear()
        } finally {
            dispatchDepth--
        }
    }

    /**
     * Activity 被移出任务栈后 Runtime 仍可能继续执行。安全历史回放、完成确认和实时订阅
     * 共用同一把锁，保证客户端收到确认前的事件都是历史，新增事件与终态不会越过边界。
     */
    fun attach(
        eventSink: (AgentEvent) -> Unit,
        resultSink: (AgentRuntimeWire.RunResult) -> Unit,
        onReplayComplete: () -> Unit = {},
    ): Boolean = withSessionLock {
        if (state == State.TERMINAL) return false
        val history = replayEvents.toList()
        val subscriber = Subscriber(eventSink, resultSink)
        subscribers += subscriber
        var attached = false
        dispatchDepth++
        try {
            for (event in history) {
                if (state == State.TERMINAL) return false
                if (runCatching { eventSink(event) }.isFailure) return false
            }
            if (state == State.TERMINAL) return false
            if (runCatching { onReplayComplete() }.isFailure) return false
            if (state == State.TERMINAL) return false
            attached = true
            true
        } finally {
            if (!attached) subscribers.remove(subscriber)
            dispatchDepth--
            drainEvents()
        }
    }

    @Volatile var questionCoordinator: AgentQuestionCoordinator? = null

    fun submitQuestionAnswer(submission: AgentRuntimeWire.QuestionAnswerSubmission): AgentQuestionReceipt {
        val coordinator = withSessionLock {
            if (state != State.RUNNING || stopSignalled || questionEvidenceFailed || submission.runId != runId) return AgentQuestionReceipt(
                false, "QUESTION_RUN_NOT_ACTIVE", "该任务已停止或结束")
            questionCoordinator
        } ?: return AgentQuestionReceipt(false, "QUESTION_NOT_PENDING", "没有待回答的问题")
        // Never hold the session lock while the coordinator publishes a resolved event.
        return coordinator.submitAnswer(submission.conversationId, submission.runId,
            submission.questionId, submission.toolCallId, submission.answer)
    }

    fun questionSnapshot(conversationId: String, questionId: String, toolCallId: String): AgentQuestionSnapshot? {
        if (lock.isHeldByCurrentThread) return if (questionEvidenceFailed || questionLedger?.isAvailable == false) null
            else questionIndex.snapshot(conversationId, questionId, toolCallId)
        questionAdmission.lock()
        try {
            return withSessionLock {
                if (questionEvidenceFailed || questionLedger?.isAvailable == false) null else questionIndex.snapshot(conversationId, questionId, toolCallId)
            }
        } finally { questionAdmission.unlock() }
    }

    fun steer(text: String): Boolean = withSessionLock {
        if (state != State.RUNNING) return false
        val interrupt = controller.enqueueSteering(AgentRunController.SteeringInput(text)) ?: return false
        deferSteering(interrupt)
        true
    }

    /** Called under the session lock; interruption and resume must outlive every lock scope. */
    private fun deferSteering(interrupt: Boolean) {
        afterUnlock += {
            if (withSessionLock { state == State.RUNNING }) {
                controller.interruptSteering(interrupt)
                if (!interrupt) controller.resume()
            }
        }
    }

    @Volatile var childCompactor: ((String, Int?, io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig?) -> Boolean)? = null

    /**
     * A captured task ID targets exactly one child; rejection never falls back to main.
     * The child coordinator may call back while holding its own monitor. Synchronous
     * reentrant child requests must therefore reject, not invoke under an outer lock
     * or return an invented success for deferred work. Ordinary calls are admitted
     * under the lock and run outside it; sealing waits for admitted calls to unwind.
     */
    fun requestCompact(
        keepRecentMessages: Int? = null,
        compressModelConfig: io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig? = null,
        childTaskId: String? = null,
    ): Boolean {
        if (childTaskId == null) return withSessionLock {
            if (state != State.RUNNING) return false
            if (!controller.enqueueCompact(keepRecentMessages, compressModelConfig)) return false
            // Pause observers emit back into the session; never notify while holding its lock.
            afterUnlock += {
                if (withSessionLock { state == State.RUNNING }) controller.resumeForCompact()
            }
            true
        }
        // A child callback runs outside the session lock but can still own its coordinator.
        if (lock.isHeldByCurrentThread || (childCompactionDepth.get() ?: 0) > 0) return false
        val compactor = withSessionLock {
            if (state != State.RUNNING) return false
            val target = childCompactor ?: return false
            childCompactions++
            target
        }
        val previousDepth = childCompactionDepth.get() ?: 0
        childCompactionDepth.set(previousDepth + 1)
        try {
            return compactor(childTaskId, keepRecentMessages, compressModelConfig)
        } finally {
            try {
                withSessionLock {
                    childCompactions--
                    if (childCompactions == 0) {
                        childCompactionsFinished.signalAll()
                        terminalAfterChildCompactions?.let { afterUnlock += it }
                        terminalAfterChildCompactions = null
                    }
                }
            } finally {
                if (previousDepth == 0) childCompactionDepth.remove()
                else childCompactionDepth.set(previousDepth)
            }
        }
    }

    /**
     * The caller has already closed admission by claiming COMMITTING. Never wait
     * for a coordinator from an event/replay/child callback: it may be waiting on
     * that callback's monitor. Its last admitted child performs the commit instead.
     * A non-reentrant terminal caller still waits synchronously, releasing the
     * session lock while waiting so child callbacks and isTerminal remain usable.
     */
    private fun afterChildCompactions(
        deferForCallback: Boolean = lock.holdCount > 1 || dispatchDepth > 0 || (childCompactionDepth.get() ?: 0) > 0,
        action: () -> Unit,
    ) {
        if (childCompactions == 0) {
            afterUnlock += action
        } else if (deferForCallback) {
            terminalAfterChildCompactions = action
        } else {
            afterUnlock += {
                awaitChildCompactions()
                action()
            }
        }
    }

    /**
     * A pending child compaction must never block the terminal seal. The wait releases
     * the session lock between timeouts, so child callbacks and isTerminal stay usable,
     * and it proceeds to the seal once the budget runs out or the thread is interrupted
     * instead of waiting forever on an unrunnable child.
     */
    private fun awaitChildCompactions() {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CHILD_COMPACTION_SEAL_TIMEOUT_MS)
        var interrupted = false
        var pending = 0
        try {
            withSessionLock {
                while (childCompactions != 0) {
                    val remainingNanos = deadlineNanos - System.nanoTime()
                    if (remainingNanos <= 0) break
                    try {
                        childCompactionsFinished.awaitNanos(remainingNanos)
                    } catch (interruptedWait: InterruptedException) {
                        // An interrupt is a stop signal, not a reason to keep waiting out the budget.
                        interrupted = true
                        break
                    }
                }
                pending = childCompactions
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (interrupted || pending != 0) {
            // Diagnostics must never decide whether the terminal result is published.
            runCatching {
                AndroidAgentLogger.warn(
                    "Runtime terminal seal wait ended: pending_child_compactions=$pending interrupted=$interrupted"
                )
            }
        }
    }

    private fun finishQuestionAdmission() {
        admittedQuestions--
        if (admittedQuestions == 0) {
            questionsFinished.signalAll()
            terminalAfterQuestions?.let { afterUnlock += it }
            terminalAfterQuestions = null
        }
    }

    /** Like child sealing, callbacks cannot wait on their own admitted question. */
    private fun afterQuestions(action: () -> Unit) {
        if (admittedQuestions == 0) afterUnlock += action
        else if (lock.holdCount > 1 || dispatchDepth > 0 || (childCompactionDepth.get() ?: 0) > 0) terminalAfterQuestions = action
        else afterUnlock += {
            withSessionLock { while (admittedQuestions != 0) questionsFinished.awaitUninterruptibly() }
            action()
        }
    }

    private fun commitTerminal(result: AgentRuntimeWire.RunResult, beforePublish: (AgentRuntimeWire.RunResult) -> Unit) {
        terminalWork {
            val coordinateQuestions = questionLedger != null || admittedQuestions != 0
            if (coordinateQuestions) questionAdmission.lock()
            try {
                val persistenceFailure = runCatching {
                    if (questionEvidenceFailed) throw AgentQuestionInterruptedException()
                    questionLedger?.sealOwner(questionOwnerGeneration)
                }.exceptionOrNull()
                if (persistenceFailure != null) {
                    questionEvidenceFailed = true
                    // No transcript/outbox success and no invented Interrupted snapshot after failed storage.
                    withSessionLock { sealTerminal(result.copy(ok = false, error = AgentQuestionInterruptedException().message)) }
                } else {
                    val commitFailure = runCatching { beforePublish(result) }.exceptionOrNull()
                    runCatching { beforeTerminalPublish() }
                    withSessionLock { sealTerminal(result) }
                    commitFailure?.let { throw it }
                }
            } finally { if (coordinateQuestions) questionAdmission.unlock() }
        }
    }

    fun <T : AgentEvent> steer(
        text: String,
        imagesJson: String = "[]",
        eventFactory: () -> T,
    ): T? = withSessionLock {
        if (state != State.RUNNING) return null
        val interrupt = controller.enqueueSteering(AgentRunController.SteeringInput(text, imagesJson)) ?: return null
        val event = eventFactory()
        if (state != State.RUNNING) return null
        publishEvent(event)
        deferSteering(interrupt)
        event
    }

    private fun recordForReplay(event: AgentEvent) {
        val projected = event.recoveryProjection() ?: return
        if (projected !is AgentEvent.AssistantBlockDelta) {
            replayEvents += projected
            return
        }
        val previous = replayEvents.lastOrNull() as? AgentEvent.AssistantBlockDelta
        if (
            previous != null &&
            previous.round == projected.round &&
            previous.kind == projected.kind &&
            previous.index == projected.index
        ) {
            replayEvents[replayEvents.lastIndex] = previous.copy(
                deltaChars = previous.deltaChars + projected.deltaChars,
                delta = previous.delta + projected.delta,
            )
        } else {
            replayEvents += projected
        }
    }

    /**
     * 先原子竞争 COMMITTING，再完成提交前副作用和结果发布。取消与替换不能越过提交胜者，
     * 因而不会出现“客户端收到取消、outbox 却留下成功结果”的分裂状态；耗时 I/O 也不持有锁。
     * [beforePublish] 必须自行吸收非致命持久化异常。
     * Reentrant completion claims the result now and commits after the outer callback unwinds.
     */
    fun complete(
        result: AgentRuntimeWire.RunResult,
        beforePublish: (AgentRuntimeWire.RunResult) -> Unit = {},
    ): Boolean = withSessionLock {
        if (state != State.RUNNING && state != State.STOPPING) return false
        require(result.runId == runId) { "Result runId does not match the active session" }
        val terminal = if (state == State.STOPPING || stopSignalled) result.copy(ok = false, error = "已停止") else result
        state = State.COMMITTING
        val deferForCallback = lock.holdCount > 1 || dispatchDepth > 0 || (childCompactionDepth.get() ?: 0) > 0
        if (admittedQuestions == 0) afterChildCompactions { commitTerminal(terminal, beforePublish) }
        else afterQuestions {
            withSessionLock { afterChildCompactions(deferForCallback) { commitTerminal(terminal, beforePublish) } }
        }
        true
    }

    fun cancel(reason: String): Boolean = withSessionLock {
        if (state != State.RUNNING) return false
        val result = AgentRuntimeWire.RunResult(
            runId = runId,
            ok = false,
            content = "",
            error = reason,
        )
        if (questionLedger == null && admittedQuestions == 0 && childCompactions == 0) {
            sealTerminal(result)
        } else {
            state = State.COMMITTING
            val deferForCallback = lock.holdCount > 1 || dispatchDepth > 0 || (childCompactionDepth.get() ?: 0) > 0
            if (admittedQuestions == 0) afterChildCompactions { commitTerminal(result) {} }
            else afterQuestions {
                withSessionLock { afterChildCompactions(deferForCallback) { commitTerminal(result) {} } }
            }
        }
        true
    }

    /** Seal and snapshot under lock; cleanup and isolated result callbacks run after unlock. */
    private fun sealTerminal(result: AgentRuntimeWire.RunResult) {
        state = State.TERMINAL
        historySnapshots.clear()
        terminalResult = result
        val recipients = subscribers.toList()
        subscribers.clear()
        // The lightweight authority outlives replay, without retaining prompts/transcript text.
        if (!questionEvidenceFailed) questionIndex.seal()
        replayEvents.clear()
        questionLedger?.retireOwner(questionOwnerGeneration)
        pendingEvents.clear()
        afterUnlock += {
            try {
                controller.cancel()
            } finally {
                for (subscriber in recipients) {
                    runCatching { subscriber.resultSink(result) }
                }
            }
        }
    }

    internal companion object {
        /** Upper bound on waiting for admitted child compactions before sealing the terminal. */
        const val CHILD_COMPACTION_SEAL_TIMEOUT_MS = 10_000L
    }
}
