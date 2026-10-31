package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch

/**
 * 运行准入与生命周期登记：同一会话同时只允许一个写入者，取消外部替换的前任后要等它排空。
 * 与 [AgentRuntimeSessionRegistry] 分工：这里管「能不能跑、等谁」，那里管「事件订阅与投递」。
 */
internal class RuntimeRunRegistry(private val capacity: Int = MAX_ACTIVE_RUNS) {
    init {
        require(capacity > 0)
    }

    sealed interface Admission {
        data class Accepted(val run: Run, val replacedRuns: List<Run> = emptyList()) : Admission
        data class Duplicate(val run: Run) : Admission
        data class Rejected(val code: String, val message: String) : Admission
    }

    enum class RunPhase { Preparing, Running, WaitingForGui, Paused, Committing }

    data class RunSnapshot(
        val runId: String,
        val conversationId: String,
        val handoffSource: String,
        val phase: RunPhase,
    )

    class Run(initialRequest: AgentRuntimeWire.RunRequest, val session: AgentRuntimeSession) {
        val runId: String get() = session.runId
        val conversationId: String = AgentConversationIdentity.fromHandoff(initialRequest.handoff, initialRequest.runId)

        @Volatile var request: AgentRuntimeWire.RunRequest = initialRequest
            private set

        @Volatile var preparing: Boolean = true
            private set

        @Volatile var guiStatus: GuiControlStatus? = null
        internal var predecessors: List<Run> = emptyList()
        private val terminal = CountDownLatch(1)

        fun prepared(request: AgentRuntimeWire.RunRequest) {
            check(request.runId == runId)
            check(AgentConversationIdentity.fromHandoff(request.handoff, request.runId) == conversationId)
            this.request = request
            preparing = false
        }

        /** 外部归档替换自己的前任时，先等已取消的前任排空，不能与其工具清理并行写会话。 */
        fun awaitPredecessors() {
            if (predecessors.isEmpty()) return
            val worker = Thread.currentThread()
            val cancellation = session.controller.register(cancel = worker::interrupt)
            try {
                predecessors.forEach { previous -> previous.terminal.await() }
            } catch (_: InterruptedException) {
                worker.interrupt()
                throw AgentRunCancelledException()
            } finally {
                cancellation.close()
                predecessors = emptyList()
            }
            session.controller.throwIfCancelled()
        }

        internal fun finished() = terminal.countDown()

        fun snapshot(): RunSnapshot = RunSnapshot(
            runId = runId,
            conversationId = conversationId,
            handoffSource = request.handoff?.source.orEmpty(),
            phase = when {
                session.isCommitting -> RunPhase.Committing
                preparing -> RunPhase.Preparing
                guiStatus == GuiControlStatus.Waiting -> RunPhase.WaitingForGui
                session.controller.isPaused -> RunPhase.Paused
                else -> RunPhase.Running
            },
        )
    }

    private val lock = Any()
    private val runs = linkedMapOf<String, Run>()

    fun admit(request: AgentRuntimeWire.RunRequest, create: () -> Run): Admission = synchronized(lock) {
        val conversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
        runs[request.runId]?.let { existing ->
            return if (existing.conversationId == conversationId) Admission.Duplicate(existing)
            else Admission.Rejected("RUN_ID_CONFLICT", "运行标识与已有会话不一致，请重新发起任务")
        }
        val previous = runs.values.filter { it.conversationId == conversationId }
        val source = request.handoff?.source
        val replacesExternalRun = source != null && source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE &&
            AgentExternalArchivePayload.from(request.handoff.payload) != null &&
            previous.all { it.request.handoff?.source == source }
        if (previous.isNotEmpty() && !replacesExternalRun) {
            return Admission.Rejected("CONVERSATION_BUSY", "当前会话仍有任务在运行，请等待完成或停止后再试")
        }
        if (runs.size >= capacity) {
            return Admission.Rejected("RUN_CAPACITY_REACHED", "同时运行的任务已达上限，请等待任务完成后再试")
        }
        val run = create()
        check(run.runId == request.runId && run.conversationId == conversationId)
        run.predecessors = previous
        runs[run.runId] = run
        Admission.Accepted(run, previous)
    }

    fun get(runId: String): Run? = synchronized(lock) { runs[runId] }

    fun all(): List<Run> = synchronized(lock) { runs.values.toList() }

    fun contains(run: Run): Boolean = synchronized(lock) { runs[run.runId] === run }

    fun finish(runId: String, session: AgentRuntimeSession): Run? = synchronized(lock) {
        runs[runId]?.takeIf { it.session === session }?.also {
            runs.remove(runId)
            it.finished()
        }
    }

    // 不持登记锁读取 Session 状态；终态发布回调（beforeTerminalPublish）会反向移除登记。
    fun snapshots(): List<RunSnapshot> = all()
        .filterNot { it.session.isTerminal }
        .map(Run::snapshot)

    companion object {
        const val MAX_ACTIVE_RUNS = 8
    }
}
