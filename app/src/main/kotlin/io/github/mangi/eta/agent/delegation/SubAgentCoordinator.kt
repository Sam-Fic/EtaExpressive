package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentImageGenerationOptions
import io.github.mangi.eta.agent.model.ImageGenerationParameterException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.browser.ChildBrowserAccess
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A coordinator owns its queues, pools, workspaces and task results beyond the parent's reply. */
internal class SubAgentCoordinator(
    private val workers: List<AgentModelClient.ModelConfig>,
    private val timeoutMs: Long = 360_000,
    private val compressionTimeoutMs: Long = 360_000,
    private val videoTimeoutMs: Long = 600_000,
    private val roles: List<String> = List(workers.size) { "research" },
    workspace: SubAgentWorkspace? = null,
    executeWorkspaceChild: ((AgentModelClient.ModelConfig, String, AgentRunController, String, String, Boolean) -> String)? = null,
    onContext: (SubAgentContextStats) -> Unit = {},
    executeObservedChild: ((AgentModelClient.ModelConfig, String, AgentRunController, String, String?, Boolean, (AgentEvent) -> Unit) -> String)? = null,
    private val workerIds: List<String> = workers.indices.map { "worker-${it + 1}" },
    private val workerNames: List<String> = workerIds,
    private val workerModelIds: List<String> = List(workers.size) { "" },
    prepareManualCompactor: (AgentModelClient.ModelConfig) -> AgentModelClient.ModelConfig = { it },
    executeVideoChild: ((AgentModelClient.ModelConfig, String, AgentRunController) -> String)? = null,
    executeImageChild: ((AgentModelClient.ModelConfig, String, AgentRunController, AgentImageGenerationOptions) -> String)? = null,
    private val modelParallelLimits: List<Int> = List(workers.size) { 1 },
    private val allowTimeoutContinuation: Boolean = false,
    diagnostics: SubAgentDiagnostics = SubAgentDiagnostics(),
    onTaskChanged: () -> Unit = {},
    poolScope: String? = null,
    executeChild: (AgentModelClient.ModelConfig, String, AgentRunController) -> String,
) : AutoCloseable {
    init { require(workers.isNotEmpty() && roles.size == workers.size && workerIds.size == workers.size && workerIds.distinct().size == workers.size && workerNames.size == workers.size && workerModelIds.size == workers.size && modelParallelLimits.size == workers.size && modelParallelLimits.all { it >= 0 }) }

    @Volatile private var workspace = workspace
    @Volatile private var executeWorkspaceChild = executeWorkspaceChild
    @Volatile private var onContext: ((SubAgentContextStats) -> Unit)? = onContext
    @Volatile private var executeObservedChild = executeObservedChild
    @Volatile private var prepareManualCompactor = prepareManualCompactor
    @Volatile private var executeVideoChild = executeVideoChild
    @Volatile private var executeImageChild = executeImageChild
    @Volatile private var diagnostics: SubAgentDiagnostics? = diagnostics
    @Volatile private var onTaskChanged: (() -> Unit)? = onTaskChanged
    @Volatile private var executeChild = executeChild
    private val callbacks = SubAgentCallbackDispatcher()
    private var resourcesReleased = false

    private inner class Task(val id: String, val worker: Int, val role: String, val project: String, @Volatile var workspaceId: String? = null, val browserAccess: ChildBrowserAccess = ChildBrowserAccess.FULL) {
        @Volatile var workspaceOwnershipVerified = false
        lateinit var context: SubAgentContextTracker
        lateinit var clock: SubAgentExecutionClock
        val journal = SubAgentEventJournal()
        val confirmedText = SubAgentConfirmedText()
        @Volatile var watchdog: java.util.concurrent.ScheduledFuture<*>? = null
        @Volatile var leaseRenewal: java.util.concurrent.ScheduledFuture<*>? = null
        @Volatile var workspacePath = ""
        @Volatile var workspaceBase = ""
        @Volatile var artifactEvidence: JSONObject? = null
        @Volatile var modelReport = ""
        @Volatile var workspaceLeaseOpen = false
        @Volatile var renewing = false
        @Volatile var executing = false
        @Volatile var preparing = false
            set(value) { field = value; refreshContextStatus() }
        @Volatile var boundaryReached = false
            set(value) { field = value; refreshContextStatus() }
        @Volatile var continuationCount = 0
        @Volatile var finalizing = false
        @Volatile var successorId: String? = null
        var predecessorId: String? = null
        var groupPauseEpoch = 0L
        var seenGroupPauseEpoch = 0L
        val queuedAt = System.nanoTime() / 1_000_000
        @Volatile var startedAt: Long? = null
        @Volatile var decisionAt: Long? = null
        @Volatile var decisionWaitMs = 0L
        @Volatile var lastProgress = queuedAt
        @Volatile var warnedStall = false
        val dispatchGate = java.util.concurrent.CountDownLatch(1)
        val controller = AgentRunController().also { check(it.freezeChildBrowserAccess(browserAccess)) }
        @Volatile var state = "queued"
            set(value) { field = value; refreshContextStatus() }
        @Volatile var result = ""
        @Volatile var errorCode = ""
        @Volatile var future: Future<*>? = null

        // Only mutation paths call this; readers never advance a task's transition token.
        fun refreshContextStatus() {
            if (!::context.isInitialized) return
            val status = when {
                state == "awaiting_decision" && (!boundaryReached || preparing) -> "pausing"
                state in setOf("queued", "running") && pendingGroupPauses.get() > 0 && role !in MEDIA -> "pausing"
                else -> state
            }
            context.updateStatus(status)
        }
    }
    private val poolLeases = workers.indices.map { i ->
        val config = workers[i]
        val key = if (config.providerId.isBlank()) "unconfigured:${System.identityHashCode(this)}:${workerIds[i]}" else config.providerId + "\u0000" + config.model
        val scopedKey = poolScope?.let { scope -> "scoped:${scope.length}:$scope:${key.length}:$key" } ?: key
        SubAgentModelPools.acquire(scopedKey, modelParallelLimits[i])
    }
    private val pools = poolLeases.map { it.executor }
    private val timer = Executors.newScheduledThreadPool(2)
    private val tasks = linkedMapOf<String, Task>()
    @Volatile private var closed = false
    @Volatile private var stopping = false
    @Volatile private var activeGroupPauseEpoch = 0L
    private var nextGroupPauseEpoch = 0L
    private val groupControl = Any()
    private val pendingGroupPauses = AtomicInteger()

    private fun active(task: Task) = task.state in ACTIVE || task.executing || task.preparing || task.renewing
    fun ownsTask(taskId: String): Boolean = synchronized(this) { tasks.containsKey(taskId) }
    fun taskIds(): List<String> = synchronized(this) { tasks.keys.toList() }
    fun hasActiveTasks(): Boolean = synchronized(this) { tasks.values.any(::active) }
    /** Direct telemetry read: no tool call, handoff receipt, control epoch or task mutation. */
    fun contextStats(): List<SubAgentContextStats> = synchronized(this) { tasks.values.toList() }.map { task ->
        synchronized(task) {
            task.context.value.copy(isCompacting = task.state in ACTIVE && task.context.value.isCompacting)
        }
    }
    fun preventNewTasks() { stopping = true }
    fun reserveGroupPause() {
        pendingGroupPauses.incrementAndGet()
        // The caller may hold the registry monitor. Never acquire task monitors on that thread.
        callbacks.post("pause_status") {
            synchronized(this) { tasks.values.toList() }.forEach { task ->
                synchronized(task) { task.refreshContextStatus() }
            }
            changed()
        }
        changed()
    }
    fun finishGroupPauseRequest() {
        pendingGroupPauses.decrementAndGet()
        synchronized(this) { tasks.values.toList() }.forEach { task -> synchronized(task) {
            task.refreshContextStatus()
            (task as java.lang.Object).notifyAll()
        } }
        changed()
    }
    fun cancelAll() { synchronized(this) { tasks.values.toList() }.forEach { stop(it, "cancelled") } }

    fun pauseGroup() {
        synchronized(groupControl) {
            val epoch: Long
            val owned = synchronized(this) {
                if (closed || stopping) return
                if (activeGroupPauseEpoch != 0L && tasks.values.none { it.role !in MEDIA && !it.finalizing && it.state in setOf("queued", "running") }) return
                epoch = ++nextGroupPauseEpoch
                activeGroupPauseEpoch = epoch
                tasks.values.toList()
            }
            owned.forEach { task -> synchronized(task) { pauseForGroup(task, epoch) } }
            changed()
        }
    }
    fun resumeGroup() {
        synchronized(groupControl) {
            val epoch = activeGroupPauseEpoch
            if (epoch == 0L || stopping) return
            activeGroupPauseEpoch = 0L
            val owned = synchronized(this) { tasks.values.toList() }
            owned.forEach { task -> synchronized(task) {
                if (!stopping && task.groupPauseEpoch == epoch && task.state == "awaiting_decision") resumeTask(task)
            } }
            changed()
        }
    }
    private fun pauseForGroup(task: Task, epoch: Long) {
        if (epoch == 0L || task.seenGroupPauseEpoch == epoch) return
        task.seenGroupPauseEpoch = epoch
        if (task.role in MEDIA || task.finalizing || task.state !in setOf("queued", "running")) return
        task.groupPauseEpoch = epoch
        pause(task, "SUB_AGENT_GROUP_PAUSE")
    }
    private fun changed() { callbacks.post("changed") { onTaskChanged?.invoke() } }
    fun requestCompact(taskId: String, keepRecent: Int?, model: AgentModelClient.ModelConfig?): Boolean {
        val task = synchronized(this) { if (closed || stopping) null else tasks[taskId] } ?: return false
        return synchronized(task) {
            if (task.state != "running" || task.role in MEDIA) { publishContext(task.context.manualRequest("ended")); false }
            else if (task.context.value.isCompacting || task.context.value.manualCompactionState == "pending") true
            else {
                val accepted = task.controller.requestCompact(keepRecent, model ?: prepareManualCompactor(workers[task.worker]))
                publishContext(task.context.manualRequest(if (accepted) "pending" else "ended")); changed(); accepted
            }
        }
    }
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val json = try {
            val args = JSONObject(call.argumentsJson)
            when (call.name) {
                "delegate_task" -> start(args)
                "get_task_result" -> get(args)
                "continue_task" -> continueTask(args)
                "supervise_task" -> supervise(args)
                "manage_agent_workspace" -> manage(args)
                "cancel_task" -> { val task = find(args.getString("task_id")); stop(task, "cancelled"); snapshot(task) }
                else -> error("Unknown delegation tool")
            }
        } catch (error: ImageGenerationParameterException) {
            JSONObject().put("ok", false).put("code", "IMAGE_GENERATION_INVALID_OPTIONS").put("message", error.message)
        } catch (_: UnknownTaskException) { errorResult("TASK_NOT_FOUND").put("known_task_ids", JSONArray(recentTaskIds())) }
        catch (error: IllegalArgumentException) { invalidArguments(error.message) }
        catch (error: org.json.JSONException) { invalidArguments(error.message) }
        SubAgentErrorHints.annotate(json)
        if (!json.optBoolean("ok", true)) callbacks.post("dispatch_rejected") { diagnostics?.mark("dispatch_rejected", errorCode = json.optString("code")) }
        return AgentModelClient.ToolResult(json.toString(), sensitive = true)
    }

    private fun start(args: JSONObject): JSONObject {
        var blockedPredecessor: Task? = null
        val task = synchronized(this) {
            if (closed || stopping) return errorResult("RUN_CLOSED")
            if (activeGroupPauseEpoch != 0L || pendingGroupPauses.get() > 0) return errorResult("TASK_GROUP_PAUSED")
            val browserAccess = ChildBrowserAccess.fromArgs(args)
                ?: return invalidArguments("browser_access 必须是 full/read_only/disabled 字符串；未创建任务。")
            val instruction = args.getString("task")
            val context = args.optString("context")
            require(instruction.isNotBlank() && instruction.length <= 12000 && context.length <= 20000) {
                "task 必须非空且不超过 12000 字；context 不超过 20000 字"
            }
            val suppliedRole = if (args.has("role")) args.getString("role") else null
            require(suppliedRole == null || suppliedRole in ROLES) { "role 只能是 ${ROLES.joinToString("/")}" }
            val predecessor = args.optString("replace_task_id").takeIf { it.isNotBlank() }?.let { tasks[it] ?: return errorResult("UNKNOWN_REPLACED_TASK") }
            predecessor?.successorId?.let { successor -> return errorResult("REPLACEMENT_ALREADY_DISPATCHED").put("task_id", successor) }
            val workerById = args.optString("agent_id").takeIf { it.isNotBlank() }?.let { workerIds.indexOf(it) }
            if (workerById != null && workerById < 0) return errorResult("AGENT_NOT_CONFIGURED")
            if (workerById != null && args.has("worker") && workerById != args.getInt("worker") - 1) return errorResult("WORKER_ID_MISMATCH")
            val role = suppliedRole ?: predecessor?.role ?: "research"
            val worker = workerById ?: if (args.has("worker")) args.getInt("worker") - 1 else {
                val desired = if (role == "summary") "review" else role
                val candidates = if (role == "research") roles.indices.filter { roles[it] !in MEDIA } else roles.indices.filter { roles[it] == desired }
                candidates.minByOrNull { i -> tasks.values.count { it.worker == i && active(it) } } ?: return roleNotConfigured(role)
            }
            require(worker in workers.indices) { "worker 必须在 1..${workers.size} 之间" }
            if (predecessor != null) {
                if (worker == predecessor.worker) return errorResult("REPLACEMENT_REQUIRES_NEW_WORKER")
                if (predecessor.errorCode == "SUB_AGENT_PROVIDER_UNAVAILABLE" && workers[worker].providerId == workers[predecessor.worker].providerId) return errorResult("REPLACEMENT_PROVIDER_UNAVAILABLE")
            }
            // Rejected here, before any model pool slot or isolated worktree is used, and never
            // silently re-routed to another worker, provider or model.
            if (role == "research" && roles[worker] in MEDIA) return roleMismatch(worker, role)
            if (role != "research" && roles[worker] != (if (role == "summary") "review" else role)) return roleMismatch(worker, role)
            val imageOptions = if (args.has("image_options")) {
                if (role != "image_generation") AgentImageGenerationOptions.invalid("image_options 仅适用于 image_generation。")
                val options = args.optJSONObject("image_options") ?: AgentImageGenerationOptions.invalid("image_options 必须是对象。")
                AgentImageGenerationOptions.fromJson(options).also { it.applyTo(JSONObject(), workers[worker].model) }
            } else AgentImageGenerationOptions()
            val project = args.optString("project")
            val workspaceId = (args.opt("workspace_id") as? String)?.takeIf { it.isNotBlank() }
            if (role in MEDIA) {
                require(project.isBlank() && workspaceId == null) { "生图和生视频任务不能带 project 或 workspace_id" }
                if (role == "image_generation" && executeImageChild == null) return errorResult("IMAGE_GENERATION_UNAVAILABLE")
                if (role == "video_generation" && executeVideoChild == null) return errorResult("VIDEO_GENERATION_UNAVAILABLE")
            }
            if (role == "implementation" || workspaceId != null) {
                if (workspace == null || executeWorkspaceChild == null) return errorResult("WORKSPACE_UNAVAILABLE")
                require(Regex("/workspace/[^/]+").matches(project)) { "implementation 和带 workspace_id 的任务需要 project=/workspace/<仓库名>" }
            }
            if (workspaceId != null && tasks.values.any { it.project == project && it.workspaceId == workspaceId && active(it) }) {
                return workspaceInUse(tasks.values.filter { it.project == project && it.workspaceId == workspaceId && active(it) })
            }
            require(role != "implementation" || workspaceId == null) { "implementation 会自己创建工作树，不能带 workspace_id；审查已有工作区用 role=review" }
            if (predecessor != null) {
                if (predecessor.role in MEDIA || role != predecessor.role) return errorResult("REPLACEMENT_ROLE_MISMATCH")
                if (predecessor.role == "implementation" || predecessor.workspaceId != null) return errorResult("WORKSPACE_HANDOFF_REQUIRES_MANUAL_REVIEW")
                    .put("workspace_id", predecessor.workspaceId)
                    .put("can_replace", false)
                    .put("allowed_actions", JSONArray(allowedActions(predecessor)))
                    .put("next_step", nextStep(predecessor).ifBlank {
                        "带工作区（含失败实现）的任务不能用 replace_task_id 替换；先读取任务状态，执行未退出时只等待结果。"
                    })
                if (SubAgentExecutionFailure.nextStep(predecessor.errorCode) != null)
                    return errorResult("REPLACEMENT_NOT_ALLOWED")
                        .put("can_replace", false).put("next_step", nextStep(predecessor))
                if (predecessor.state == "awaiting_decision" && predecessor.errorCode == "SUB_AGENT_NO_PROGRESS") { blockedPredecessor = predecessor; return@synchronized null }
                if (predecessor.state != "failed" && predecessor.errorCode != "REPLACED_AFTER_BLOCK") return errorResult("REPLACEMENT_NOT_ALLOWED")
                if (predecessor.executing || predecessor.renewing) return errorResult("REPLACE_PENDING_STOP")
            }
            val t = Task(UUID.randomUUID().toString(), worker, role, project, workspaceId, browserAccess)
            t.predecessorId = predecessor?.id
            t.preparing = role == "implementation"
            val model = workers[worker]
            t.context = SubAgentContextTracker(SubAgentContextStats(t.id, worker + 1, role, model.model,
                model.modelDisplayName.ifBlank { model.model }, model.providerName, model.contextWindow,
                status = "queued", agentId = workerIds[worker], agentName = workerNames[worker], providerId = model.providerId, modelId = workerModelIds[worker]))
            t.controller.setPauseBoundaryObserver {
                synchronized(t) {
                    if (t.state == "awaiting_decision" && !t.boundaryReached) { t.boundaryReached = true; t.journal.mark("pause_boundary"); publishContext(t.context.value) }
                }
            }
            t.controller.setTaskProgressReporter { summary ->
                synchronized(t) {
                    if (t.state !in setOf("running", "awaiting_decision") || summary.isBlank()) false
                    else { t.journal.setCheckpoint(summary); changed(); true }
                }
            }
            tasks[t.id] = t
            changed()
            try {
                t.future = pools[worker].submit {
                    try { t.dispatchGate.await() } catch (_: InterruptedException) { return@submit }
                    synchronized(t) {
                        if (stopping) return@submit
                        pauseForGroup(t, activeGroupPauseEpoch)
                        while (!stopping && (t.state == "awaiting_decision" || (t.state == "queued" && t.role !in MEDIA && pendingGroupPauses.get() > 0))) {
                            try { (t as java.lang.Object).wait() } catch (_: InterruptedException) { return@submit }
                            pauseForGroup(t, activeGroupPauseEpoch)
                        }
                        if (t.state != "queued" || stopping) return@submit
                        t.executing = true
                        t.startedAt = System.nanoTime() / 1_000_000
                        t.lastProgress = t.startedAt!!
                        t.state = "running"
                        val budget = when (role) { "video_generation" -> videoTimeoutMs; "image_generation" -> minOf(timeoutMs, IMAGE_TIMEOUT_MS); else -> timeoutMs }
                        t.clock = SubAgentExecutionClock(budget, compressionTimeoutMs, softExecution = role !in MEDIA)
                        publishContext(t.context.value); diagnostic(t, "started"); t.journal.mark("running"); changed()
                    }
                    try {
                        t.watchdog = timer.scheduleAtFixedRate({
                            val now = System.nanoTime() / 1_000_000
                            synchronized(t) {
                                if (t.state in ACTIVE && !t.finalizing) {
                                    val expired = t.clock.expired()
                                    if (expired != null) stop(t, "timed_out", expired)
                                    else if (t.state == "awaiting_decision" && t.groupPauseEpoch == 0L && !t.boundaryReached && now - (t.decisionAt ?: now) >= PAUSE_BOUNDARY_TIMEOUT_MS) stop(t, "timed_out", "SUB_AGENT_PAUSE_BOUNDARY_TIMEOUT")
                                    else if (t.state == "running" && role !in MEDIA) {
                                        if (t.clock.softWarningDue()) { t.journal.mark("execution_soft_warning"); diagnostic(t, "execution_soft_warning") }
                                        val stalled = now - t.lastProgress
                                        if (stalled >= STALL_WARNING_MS && !t.warnedStall) { t.warnedStall = true; t.journal.mark("no_progress_warning"); diagnostic(t, "no_progress_warning") }
                                        if (stalled >= STALL_PAUSE_MS) pause(t, "SUB_AGENT_NO_PROGRESS")
                                    }
                                }
                                if (t.state in ACTIVE && now - t.journal.lastHeartbeatMs >= 30_000) t.journal.mark("heartbeat")
                            }
                            if (t.state !in ACTIVE) t.watchdog?.cancel(false)
                        }, 100, 1000, TimeUnit.MILLISECONDS)
                        t.controller.throwIfCancelled()
                        if (role == "implementation") {
                            if (t.workspaceId == null) {
                                diagnostic(t, "workspace_prepare")
                                val prepared = workspace!!.requireOperation(project, "prepare")
                                synchronized(t) { t.workspaceId = prepared.getString("id"); t.workspacePath = prepared.getString("path"); t.workspaceBase = prepared.optString("base"); t.workspaceOwnershipVerified = true; t.workspaceLeaseOpen = true }
                            }
                        } else if (workspaceId != null) {
                            diagnostic(t, "workspace_begin_review")
                            val existing = workspace!!.requireOperation(project, "begin_review", workspaceId)
                            check(existing.getString("state") == "reviewing")
                            synchronized(t) { t.workspacePath = existing.getString("path"); t.workspaceOwnershipVerified = true; t.workspaceLeaseOpen = true }
                        }
                        renewWorkspaceLease(t)
                        t.controller.throwIfCancelled()
                        val prompt = "Role: $role\nTask:\n$instruction\n\nContext supplied by main agent:\n$context"
                        diagnostic(t, if (role in MEDIA) "media_request" else "model_loop")
                        val observedChild = executeObservedChild
                        val answer = if (role in MEDIA) {
                            val mediaPrompt = instruction + if (context.isBlank()) "" else "\n\n补充要求：\n$context"
                            if (role == "video_generation") executeVideoChild!!.invoke(workers[worker], mediaPrompt, t.controller) else executeImageChild!!.invoke(workers[worker], mediaPrompt, t.controller, imageOptions)
                        } else if (observedChild != null) {
                            observedChild.invoke(workers[worker], prompt, t.controller, project, t.workspaceId, role == "implementation") { event ->
                                diagnosticEvent(t, event)
                                synchronized(t) {
                                    t.confirmedText.accept(event)
                                    if (t.state in ACTIVE) {
                                        if (t.journal.accept(event)) { t.lastProgress = System.nanoTime() / 1_000_000; t.warnedStall = false }
                                        t.context.accept(event)?.let { stats -> t.clock.setCompacting(stats.isCompacting); publishContext(stats); changed() }
                                    }
                                }
                            }
                        } else t.workspaceId?.let { id -> executeWorkspaceChild!!.invoke(workers[worker], prompt, t.controller, project, id, role == "implementation") }
                            ?: executeChild(workers[worker], prompt, t.controller)
                        synchronized(t) {
                            // Never wait for a pause while holding the task monitor: resume,
                            // cancellation and snapshots all need it. Finalization waits below.
                            if (t.controller.isCancelled || t.state !in ACTIVE) throw io.github.mangi.eta.agent.runtime.AgentRunCancelledException()
                            val report = answer // Keep the final report; only public query pages are bounded.
                            if (role == "implementation") t.modelReport = report else t.result = report
                        }
                        awaitFinalization(t)
                        val implementationEvidence = if (role == "implementation") {
                            diagnostic(t, "workspace_seal")
                            workspace!!.sealImplementation(project, requireNotNull(t.workspaceId), t.workspaceBase, t.controller)
                        } else {
                            if (t.workspaceId != null) workspace!!.requireOperation(project, if (role == "review") "review" else "end_review", t.workspaceId)
                            null
                        }
                        t.workspaceLeaseOpen = false
                        t.controller.throwIfCancelled()
                        synchronized(t) {
                            t.controller.throwIfCancelled()
                            if (t.state == "running") {
                                if (role == "implementation") {
                                    t.artifactEvidence = requireNotNull(implementationEvidence)
                                    t.result = "已核验非空 Git 提交产物，等待独立审查；尚未验证业务接线、测试通过或用户目标完成。"
                                }
                                t.state = "completed"; t.journal.mark("completed", progress = true); diagnostic(t, "completed"); t.context.finish(t.state); changed()
                            }
                        }
                    } catch (error: Exception) {
                        val interrupted = Thread.interrupted()
                        releaseWorkspaceLease(t)
                        if (interrupted) Thread.currentThread().interrupt()
                        synchronized(t) {
                            if (t.state in ACTIVE) {
                                val cancelled = t.controller.isCancelled || interrupted || error is io.github.mangi.eta.agent.runtime.AgentRunCancelledException || error is java.util.concurrent.CancellationException
                                if (cancelled) { t.state = "cancelled"; t.errorCode = "" }
                                else {
                                    val providerFailure = SubAgentProviderFailure.find(error)
                                    val executionFailure = SubAgentExecutionFailure.find(error)
                                    t.errorCode = when {
                                        error is ImageGenerationParameterException -> "IMAGE_GENERATION_INVALID_OPTIONS"
                                        error is SubAgentContextLimitException -> "SUB_AGENT_CONTEXT_LIMIT"
                                        error is io.github.mangi.eta.agent.model.AgentOutputLimitException -> "SUB_AGENT_OUTPUT_LIMIT"
                                        error is WorkspaceOperationException -> error.code
                                        executionFailure != null -> executionFailure.code
                                        providerFailure != null -> "SUB_AGENT_PROVIDER_UNAVAILABLE"
                                        role == "image_generation" -> "IMAGE_GENERATION_FAILED"
                                        role == "video_generation" -> "VIDEO_GENERATION_FAILED"
                                        else -> "SUB_AGENT_FAILED"
                                    }
                                    if (role == "implementation" || t.result.isBlank()) t.result = when {
                                        role == "implementation" && t.errorCode == SubAgentDeliveryEvidence.EMPTY ->
                                            "未交付代码改动：相对任务基线的净 diff 为空。模型原回复仅保存在 model_report_unverified 字段；工作树保留，请 inspect 核对，不自动重试"
                                        role == "implementation" && error is WorkspaceOperationException ->
                                            "实现产物未通过运行时核验（${t.errorCode}）；不能认定实现完成。工作树保留，先 inspect 核对，模型原回复不是交付证据"
                                        error is ImageGenerationParameterException -> error.message.orEmpty()
                                        error is SubAgentContextLimitException -> "子代理上下文不足，自动压缩不可用或未能释放足够空间。请拆分任务；已有工作树改动保留"
                                        // Built from runtime-owned fields only; no provider or tool text.
                                        error is io.github.mangi.eta.agent.model.AgentOutputLimitException -> "子代理模型连续在输出上限处截断，未产出正文或工具调用（${workers[worker].providerName} / ${workers[worker].model}）。已有工作树改动保留；请拆小任务或调整该模型的推理档位"
                                        executionFailure != null -> executionFailure.message + "\n" + executionFailure.nextStep
                                        providerFailure != null -> "子代理供应商不可用（${providerFailure.code.replace('_', ' ')}）：${workerNames[worker]}（${workers[worker].providerName} / ${workers[worker].model}）。这不是任务结论；不要自动重试副作用或付费请求"
                                        // The class and the first Eta frame locate the failure; the message may carry
                                        // provider bodies, paths or prompt text, so it is never echoed.
                                        else -> "子代理未完成，请主代理接手；不会自动重新执行。（${error.javaClass.simpleName}${failureOrigin(error)?.let { " @ $it" }.orEmpty()}）"
                                    }
                                    t.state = "failed"
                                }
                                t.journal.mark(t.state); t.context.finish(t.state); changed()
                            }
                        }
                        // Emit after classification so the allowlisted diagnostic includes the stable
                        // failure code. Never inspect or log a provider/tool message for classification.
                        diagnostic(t, "worker_exception", error)
                    } finally {
                        t.watchdog?.cancel(false); t.leaseRenewal?.cancel(false)
                        synchronized(t) { publishContext(t.context.finish(t.state)); t.executing = false; changed() }
                        diagnostic(t, "worker_released")
                        releaseIfClosedAndIdle()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { tasks.remove(t.id); return errorResult("TASK_DISPATCH_REJECTED") }
            predecessor?.successorId = t.id
            t
        }
        if (task == null) {
            val predecessor = requireNotNull(blockedPredecessor)
            synchronized(predecessor) { if (predecessor.state == "awaiting_decision" && predecessor.errorCode == "SUB_AGENT_NO_PROGRESS") stop(predecessor, "cancelled", "REPLACED_AFTER_BLOCK") }
            return errorResult("REPLACE_PENDING_STOP")
        }
        if (task.role == "implementation") {
            try {
                val backend = requireNotNull(workspace)
                if (task.state !in ACTIVE || stopping) return snapshot(task)
                val prepared = backend.requireOperation(task.project, "prepare")
                synchronized(task) { task.workspaceId = prepared.getString("id"); task.workspacePath = prepared.getString("path"); task.workspaceBase = prepared.optString("base"); task.workspaceOwnershipVerified = true; task.workspaceLeaseOpen = true }
                renewWorkspaceLease(task)
            } catch (error: Exception) {
                val code = (error as? WorkspaceOperationException)?.code ?: "WORKSPACE_PREPARE_FAILED"
                stop(task, "failed", code)
                if (error is InterruptedException) Thread.currentThread().interrupt()
                return errorResult(code)
            } finally {
                val cleanup = synchronized(task) {
                    if (task.state !in ACTIVE || stopping) true
                    else { task.preparing = false; if (task.state == "awaiting_decision" && task.startedAt == null) task.boundaryReached = true; false }
                }
                if (cleanup) { releaseWorkspaceLease(task); synchronized(task) { task.preparing = false } }
                task.dispatchGate.countDown(); changed(); releaseIfClosedAndIdle()
            }
        }
        synchronized(task) { publishContext(task.context.value); diagnostic(task, "queued") }
        task.dispatchGate.countDown()
        return snapshot(task)
    }

    private fun failureOrigin(error: Throwable): String? = error.stackTrace
        .firstOrNull { it.className.startsWith("io.github.mangi.eta.") }
        ?.let { "${it.className.substringAfterLast('.')}:${it.lineNumber}" }

    private fun renewWorkspaceLease(task: Task) = synchronized(task) {
        if (!task.workspaceLeaseOpen || task.leaseRenewal != null) return@synchronized
        task.leaseRenewal = timer.scheduleWithFixedDelay({
            val renew = synchronized(task) { if (task.state in ACTIVE && !task.finalizing && task.workspaceLeaseOpen) { task.renewing = true; true } else false }
            if (renew) try {
                val renewed = runCatching { workspace!!.requireOperation(task.project, "renew", task.workspaceId) }
                if (renewed.isFailure) { diagnostic(task, "lease_failed", renewed.exceptionOrNull()); stop(task, "failed", "WORKSPACE_LEASE_LOST") }
            } finally { task.renewing = false; changed(); releaseIfClosedAndIdle() }
        }, 60, 60, TimeUnit.SECONDS)
    }
    private fun releaseWorkspaceLease(task: Task) {
        val release = synchronized(task) { task.workspaceLeaseOpen.also { task.workspaceLeaseOpen = false; task.leaseRenewal?.cancel(false) } }
        if (release) runCatching { workspace?.operation(task.project, if (task.role == "implementation") "fail" else "end_review", task.workspaceId) }
    }
    private fun awaitFinalization(task: Task) {
        while (true) {
            task.controller.throwIfCancelled()
            synchronized(task) {
                if (task.state == "running") { task.finalizing = true; return }
                if (task.state !in ACTIVE) throw io.github.mangi.eta.agent.runtime.AgentRunCancelledException()
                // The provider has returned: this is a safe pause boundary, not a spin loop.
                if (task.state == "awaiting_decision" && !task.boundaryReached) {
                    task.boundaryReached = true
                    task.journal.mark("pause_boundary")
                    publishContext(task.context.value)
                }
                (task as java.lang.Object).wait()
            }
        }
    }
    private fun pause(task: Task, code: String) {
        synchronized(task) {
            if (task.state !in setOf("queued", "running") || task.finalizing || task.role in MEDIA) return
            task.boundaryReached = task.startedAt == null && !task.preparing
            if (code == "SUB_AGENT_GROUP_PAUSE") task.controller.pauseAtCheckpoint() else task.controller.pause()
            if (task.startedAt != null) task.clock.pauseExecution()
            task.state = "awaiting_decision"; task.decisionAt = System.nanoTime() / 1_000_000; task.errorCode = code
            task.journal.mark("awaiting_decision"); publishContext(task.context.value); diagnostic(task, "awaiting_decision"); changed()
        }
    }
    private fun diagnostic(task: Task, stage: String, failure: Throwable? = null, more: Map<String, Number> = emptyMap(), tool: String = "") {
        runCatching {
            val now = System.nanoTime() / 1_000_000
            val fields = linkedMapOf<String, Number>("elapsed_ms" to (now - task.queuedAt), "queue_ms" to ((task.startedAt ?: now) - task.queuedAt),
                "decision_wait_ms" to (task.decisionWaitMs + (task.decisionAt?.let { now - it } ?: 0)), "continuations" to task.continuationCount,
                "limit" to SubAgentModelPools.currentLimit(poolLeases[task.worker]), "workspace_present" to if (task.workspaceId == null) 0 else 1)
            if (task.startedAt != null) fields.putAll(task.clock.diagnostics())
            fields.putAll(SubAgentModelPools.diagnostics(poolLeases[task.worker])); fields.putAll(more)
            val state = task.state; val code = task.errorCode
            callbacks.post("diagnostic:${task.id}:$stage") { diagnostics?.mark(stage, task.id, workerIds[task.worker], workers[task.worker].providerId, workers[task.worker].model, task.role, state, code, failure, fields, tool) }
        }
    }
    private fun diagnosticEvent(task: Task, event: AgentEvent) {
        when (event) {
            is AgentEvent.ProviderRequestStarted -> diagnostic(task, "provider_request", more = mapOf("round" to event.round))
            is AgentEvent.ProviderResponseStarted -> diagnostic(task, "provider_response", more = mapOf("round" to event.round, "http_status" to event.httpCode))
            is AgentEvent.ModelRetryScheduled -> diagnostic(task, "provider_retry", more = mapOf("attempt" to event.attempt, "delay_ms" to event.delayMs))
            is AgentEvent.ToolStarted -> diagnostic(task, "tool_started", more = mapOf("round" to event.round), tool = event.name)
            is AgentEvent.ToolFinished -> diagnostic(task, "tool_finished", more = mapOf("round" to event.round, "tool_ok" to if (event.success == true) 1 else if (event.success == false) 0 else -1), tool = event.name)
            is AgentEvent.ContextCompactionStarted -> diagnostic(task, "compaction_started", more = mapOf("round" to event.round))
            is AgentEvent.ContextCompacted -> diagnostic(task, "compaction_finished", more = mapOf("round" to event.round))
            else -> Unit
        }
    }
    private fun publishContext(stats: SubAgentContextStats) {
        callbacks.post("context:${stats.taskId}") { onContext?.invoke(stats) }
        // Registry subscriptions survive parent sink detachment and failed sink callbacks.
        changed()
    }
    @Synchronized private fun find(id: String): Task = tasks[id] ?: throw UnknownTaskException()
    private fun get(args: JSONObject): JSONObject {
        SubAgentResultPage.validate(args)
        if (!args.has("task_id")) {
            val all = synchronized(this) { tasks.values.toList().asReversed() }
            val offset = args.optInt("offset", 0).coerceAtLeast(0)
            val page = all.drop(offset).take(20).map { t -> synchronized(t) { JSONObject().put("task_id", t.id).put("status", t.state).put("role", t.role).put("worker", t.worker + 1).put("agent_id", workerIds[t.worker]) } }
            return JSONObject().put("ok", true).put("tasks", JSONArray(page)).put("total", all.size).put("next_offset", if (offset + page.size < all.size) offset + page.size else JSONObject.NULL)
        }
        val task = find(args.getString("task_id"))
        val wait = args.optLong("wait_ms", 0).coerceIn(0, 10000)
        if (wait > 0 && task.state in setOf("queued", "running")) {
            if (args.has("after_seq")) task.journal.awaitPage(args.optLong("after_seq"), args.optInt("event_limit", 16), wait) { task.state in setOf("queued", "running") }
            else try { task.future?.get(wait, TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { } catch (_: java.util.concurrent.CancellationException) { }
            catch (_: java.util.concurrent.ExecutionException) { } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val limit = args.optInt("event_limit", 16)
        return snapshot(task, args.optLong("after_seq", 0), limit, args).also { json ->
            if (limit > MAX_EVENT_LIMIT) json.put("event_limit_used", MAX_EVENT_LIMIT)
            if (args.optLong("wait_ms", 0) > MAX_WAIT_MS) json.put("wait_ms_used", MAX_WAIT_MS)
        }
    }
    private fun supervise(args: JSONObject): JSONObject {
        val task = find(args.getString("task_id")); val action = args.getString("action")
        require(action in setOf("guide", "checkpoint", "pause")) { "action 只能是 guide/checkpoint/pause" }
        synchronized(task) {
            if (stopping) return errorResult("RUN_CLOSED")
            if (task.role in MEDIA) return notRunning(task)
            if (action == "pause" && task.state == "awaiting_decision") {
                // 模型常在已暂停的任务上再暂停一次；状态已经是想要的结果，不算失败。
                return snapshot(task).put("note", "任务已经暂停，没有重复暂停。用 continue_task 继续，或 cancel_task 取消。")
            }
            if (action == "pause" && task.state == "queued") { pause(task, "SUB_AGENT_MANUAL_PAUSE"); return snapshot(task) }
            if (task.state != "running") return notRunning(task)
            when (action) {
                "guide" -> { val guidance = args.optString("guidance"); require(guidance.isNotBlank() && guidance.length <= 2000) { "action=guide 需要非空 guidance（不超过 2000 字）" }; if (!task.controller.queueBoundaryGuidance(guidance)) return errorResult("TASK_NOT_ACCEPTING_GUIDANCE"); task.journal.mark("guidance_accepted") }
                "checkpoint" -> { if (!task.controller.queueBoundaryGuidance("请在下一轮调用 report_task_progress，报告已核实工作、下一步及阻碍的高层摘要；不包含私有思维、密钥或敏感参数，然后继续原任务。")) return errorResult("TASK_NOT_ACCEPTING_GUIDANCE"); task.journal.mark("checkpoint_requested") }
                "pause" -> pause(task, "SUB_AGENT_MANUAL_PAUSE")
                else -> return errorResult("INVALID_TASK_ARGUMENTS")
            }
            return snapshot(task)
        }
    }
    private fun continueTask(args: JSONObject): JSONObject {
        val task = find(args.getString("task_id"))
        synchronized(task) {
            if (stopping) return errorResult("RUN_CLOSED")
            if (task.state != "awaiting_decision" || task.role in MEDIA) {
                return errorResult("TASK_NOT_AWAITING_DECISION").put("status", task.state).put("allowed_actions", JSONArray(allowedActions(task)))
            }
            resumeTask(task); return snapshot(task)
        }
    }
    private fun resumeTask(task: Task) {
        task.decisionAt?.let { task.decisionWaitMs += System.nanoTime() / 1_000_000 - it }
        task.decisionAt = null; task.lastProgress = System.nanoTime() / 1_000_000; task.warnedStall = false
        task.groupPauseEpoch = 0L; task.seenGroupPauseEpoch = activeGroupPauseEpoch
        if (task.startedAt != null) task.clock.renewExecution()
        task.errorCode = ""; task.continuationCount++; task.state = if (task.startedAt == null) "queued" else "running"
        task.boundaryReached = false
        publishContext(task.context.value)
        diagnostic(task, "continued"); task.journal.mark("continued"); changed(); task.controller.resume(); (task as java.lang.Object).notifyAll()
    }
    private fun stop(task: Task, state: String, errorCode: String = "") {
        val cleanupQueued = synchronized(task) {
            if (task.state !in ACTIVE) return
            task.state = state; task.errorCode = errorCode; task.groupPauseEpoch = 0L; task.boundaryReached = false
            val cleanup = !task.executing && !task.preparing
            if (cleanup) task.preparing = true
            task.watchdog?.cancel(false); task.journal.mark(state); publishContext(task.context.finish(state)); diagnostic(task, state); changed(); (task as java.lang.Object).notifyAll(); cleanup
        }
        try { task.controller.cancel(); task.future?.cancel(true); if (cleanupQueued) releaseWorkspaceLease(task) }
        finally { if (cleanupQueued) synchronized(task) { task.preparing = false; changed() }; releaseIfClosedAndIdle() }
    }
    /** Internal process-local archive export; never exposed as an unbounded tool response. */
    internal fun archiveRecord(taskId: String): JSONObject = snapshot(find(taskId), exportRecord = true)

    private fun snapshot(task: Task, after: Long = 0, limit: Int = 16,
        textArgs: JSONObject = JSONObject(), exportRecord: Boolean = false): JSONObject = synchronized(task) {
        val model = workers[task.worker]
        val replaceReason = when {
            task.role in MEDIA -> "media_delivery_uncertain"
            task.role == "implementation" || task.workspaceId != null -> "workspace_requires_manual_review"
            SubAgentExecutionFailure.nextStep(task.errorCode) != null -> "local_tool_arguments_exhausted"
            task.state == "failed" -> "failed"
            task.state == "awaiting_decision" && task.errorCode == "SUB_AGENT_NO_PROGRESS" -> "blocked_no_progress"
            task.errorCode == "REPLACED_AFTER_BLOCK" && !task.executing -> "blocked_stopped"
            else -> "healthy_or_not_isolated"
        }
        val paused = task.state == "awaiting_decision"
        val pendingPause = task.role !in MEDIA && task.state in setOf("queued", "running") && pendingGroupPauses.get() > 0
        val record = JSONObject().put("ok", true).put("task_id", task.id).put("worker", task.worker + 1).put("agent_id", workerIds[task.worker]).put("agent_name", workerNames[task.worker])
            .put("model", model.model).put("model_display_name", model.modelDisplayName.ifBlank { model.model }).put("provider_id", model.providerId).put("provider_name", model.providerName)
            .put("status", task.state).put("result", task.result).put("partial_result", task.confirmedText.value()).put("partial_result_unverified", true).put("partial_result_truncated", task.confirmedText.truncated)
            .put("delivery_state", when {
                task.role != "implementation" -> "not_applicable"
                task.state == "completed" && task.artifactEvidence != null -> "artifact_ready_pending_review"
                task.errorCode == SubAgentDeliveryEvidence.EMPTY -> "no_changes"
                task.state in ACTIVE -> "pending"
                else -> "unverified"
            }).put("acceptance_verified", false)
            .put("artifact_verified", task.role == "implementation" && task.state == "completed" && task.artifactEvidence != null)
            .put("artifact_evidence", task.artifactEvidence?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
            .put("model_report_unverified", task.modelReport)
            .put("execution_exited", !active(task)).put("stopping", stopping || (task.state !in ACTIVE && active(task))).put("pause_supported", task.role !in MEDIA)
            .put("pause_requested", paused || pendingPause).put("pause_confirmed", paused && task.boundaryReached && !task.preparing)
            .put("pause_source", if (pendingPause) "group" else if (!paused) "" else if (task.groupPauseEpoch != 0L) "group" else if (task.errorCode == "SUB_AGENT_MANUAL_PAUSE") "manual" else "supervision")
            .put("context_usage", task.context.value.copy(isCompacting = task.state in ACTIVE && task.context.value.isCompacting).toJson())
            .put("browser_access", task.browserAccess.wire).put("role", task.role).put("project", task.project).put("error_code", task.errorCode).put("workspace_id", task.workspaceId ?: JSONObject.NULL).put("workspace_path", task.workspacePath)
            .put("workspace_ownership_verified", task.workspaceOwnershipVerified).put("review_required", true).put("can_continue", paused && !closed && !stopping)
            // Eligibility is not a promise that execution has stopped: a no-progress pause still
            // needs the explicit stop/handoff path. Terminal tasks finishing cleanup are not eligible.
            .put("can_replace", !closed && !stopping && (task.state in ACTIVE || !active(task)) &&
                task.successorId == null && task.workspaceId == null &&
                replaceReason in setOf("failed", "blocked_no_progress", "blocked_stopped"))
            .put("successor_task_id", task.successorId ?: JSONObject.NULL).put("replaces_task_id", task.predecessorId ?: JSONObject.NULL).put("replace_reason", replaceReason)
            .put("allowed_actions", JSONArray(allowedActions(task))).put("next_step", nextStep(task))
            .put("continuation_count", task.continuationCount).put("parallel_limit", SubAgentModelPools.currentLimit(poolLeases[task.worker])).put("supervision", task.journal.page(after.coerceAtLeast(0), limit))
            .put("continuation_note", if (paused && !closed && !stopping) "已请求在安全边界暂停，pause_confirmed 表示已到达边界；保留同一任务、上下文与工作树。主代理可显式 continue_task 或 cancel_task，不重放正在进行的请求/工具。" else "")
        SubAgentResultPage.attachRevision(record)
        if (exportRecord) record else SubAgentResultPage.project(record, textArgs)
    }
    @Synchronized private fun manage(args: JSONObject): JSONObject {
        if (closed || stopping) return errorResult("RUN_CLOSED")
        val backend = workspace ?: return errorResult("WORKSPACE_UNAVAILABLE")
        val action = args.getString("action"); require(action in setOf("list", "inspect", "merge", "discard")) { "action 只能是 list/inspect/merge/discard" }
        val project = args.getString("project"); val id = (args.opt("workspace_id") as? String)?.takeIf { it.isNotBlank() }
        val holders = tasks.values.filter { it.project == project && (id == null || it.workspaceId == id) && active(it) }
        if (holders.isNotEmpty()) return workspaceInUse(holders)
        return backend.operation(project, action, id)
    }
    private fun errorResult(code: String) = JSONObject().put("ok", false).put("code", code)
    private fun notRunning(task: Task) = errorResult("TASK_NOT_RUNNING_TEXT")
        .put("status", task.state).put("allowed_actions", JSONArray(allowedActions(task)))
    /** Task-local recovery only; other tasks may still hold a project/workspace lease. */
    private fun allowedActions(task: Task): List<String> = when {
        closed || stopping || (task.state !in ACTIVE && active(task)) -> listOf("get_task_result")
        task.role in MEDIA && task.state !in ACTIVE -> listOf("get_task_result")
        task.state !in ACTIVE -> buildList {
            add("get_task_result")
            if (task.workspaceId != null) {
                add("manage_agent_workspace:inspect")
                add("manage_agent_workspace:discard")
                add("delegate_task")
            } else if (task.state == "failed") add("delegate_task")
        }
        task.role in MEDIA -> listOf("get_task_result", "cancel_task")
        task.state == "awaiting_decision" -> listOf("continue_task", "cancel_task", "get_task_result")
        task.state == "queued" -> listOf("get_task_result", "supervise_task:pause", "cancel_task")
        else -> listOf("supervise_task", "get_task_result", "cancel_task")
    }
    /** 终止状态下的准确恢复路径；不把失败工作树说成 review-ready，也不指路给被禁止的替换。 */
    private fun nextStep(task: Task): String = when {
        closed || stopping -> "本任务组正在收尾或已关闭：只能用 get_task_result 读取，不再继续或委派。"
        task.state !in ACTIVE && active(task) ->
            "任务已进入终态，但执行或工作区租约仍在收尾：只用 get_task_result 等待 execution_exited/execution_stopped=true；现在不要恢复、替换或操作工作区。"
        task.role in MEDIA && task.state !in ACTIVE ->
            "付费媒体任务已结束，交付是否成功以实际结果为准；不要替换或自动重放请求。"
        SubAgentExecutionFailure.nextStep(task.errorCode) != null ->
            requireNotNull(SubAgentExecutionFailure.nextStep(task.errorCode))
        task.workspaceId == null && task.state == "failed" ->
            "任务失败且执行已停止：可复核结果后用不带 replace_task_id 的新任务重新委派。"
        task.state == "failed" && task.role == "implementation" ->
            "实现工作树已保留，但这不是 review-ready：先用 manage_agent_workspace inspect 查看 state 与 merge_blocked_by；确认改动不需要后 discard，或作为新任务重新委派。本运行策略不允许用 replace_task_id 替换 implementation 任务。"
        task.state == "failed" ->
            "带工作区的任务不能用 replace_task_id 替换：先用 manage_agent_workspace inspect 查看工作区与 merge_blocked_by，再决定 discard 后重新委派，或交给 review。"
        task.state in setOf("cancelled", "timed_out") && task.workspaceId != null ->
            "工作树改动已保留：先用 manage_agent_workspace inspect 查看；不能用 replace_task_id 替换带工作区的任务。"
        task.state == "completed" && task.workspaceId != null ->
            "运行已结束；completed 不代表业务验收。先用 manage_agent_workspace inspect 核对 artifact_evidence、实际 diff 与 merge_blocked_by，独立审查逐项确认任务要求后才可 ff-only merge；未运行测试不得称测试通过。"
        else -> ""
    }
    /** 某个已选 worker 能合法承担的 role；只用于解释拒绝，不做静默改派。 */
    private fun rolesForWorker(worker: Int): List<String> = when (roles[worker]) {
        in MEDIA -> listOf(roles[worker])
        "review" -> listOf("research", "review", "summary")
        else -> listOf("research", roles[worker])
    }
    private fun dispatchActions(): JSONArray = JSONArray(listOf("delegate_task", "get_task_result"))
    private fun roleMismatch(worker: Int, requested: String) = errorResult("WORKER_ROLE_MISMATCH")
        .put("requested_role", requested)
        .put("worker", worker + 1)
        .put("agent_id", workerIds[worker])
        .put("worker_role", roles[worker])
        .put("allowed_roles", JSONArray(rolesForWorker(worker)))
        .put("allowed_actions", dispatchActions())
        .put("next_step", "这个 worker 只能承担 ${rolesForWorker(worker).joinToString("/")}；改用其中之一作为 role，或去掉 agent_id/worker 让系统按 role 选择。不会自动换 worker/provider/model。")
    private fun roleNotConfigured(requested: String) = errorResult("ROLE_NOT_CONFIGURED")
        .put("requested_role", requested)
        .put("allowed_roles", JSONArray(roles.indices.flatMap { rolesForWorker(it) }.distinct()))
        .put("allowed_actions", dispatchActions())
        .put("next_step", "没有配置 role=$requested 的 worker；改用 allowed_roles 里的 role，或由主代理自己完成。不会自动换 worker/provider/model。")
    private fun workspaceInUse(holders: List<Task>) = errorResult("WORKSPACE_IN_USE")
        .put("active_task_ids", JSONArray(holders.map { it.id }))
    @Synchronized private fun recentTaskIds(): List<String> = tasks.keys.toList().takeLast(10).asReversed()
    private fun invalidArguments(detail: String?) = errorResult("INVALID_TASK_ARGUMENTS").also { json ->
        detail?.takeIf { it.isNotBlank() }?.let { json.put("detail", it.take(300)) }
    }
    private class UnknownTaskException : IllegalArgumentException("Task does not belong to this session")
    private companion object {
        val MEDIA = setOf("image_generation", "video_generation")
        val ACTIVE = setOf("queued", "running", "awaiting_decision")
        const val MAX_EVENT_LIMIT = 32
        const val MAX_WAIT_MS = 10_000L
        val ROLES = setOf("research", "implementation", "review", "summary", "image_generation", "video_generation")
        const val IMAGE_TIMEOUT_MS = 180_000L
        const val STALL_WARNING_MS = 120_000L
        const val STALL_PAUSE_MS = 360_000L
        const val PAUSE_BOUNDARY_TIMEOUT_MS = 30_000L
    }
    fun releaseExecutionResources() {
        synchronized(this) { check(tasks.values.none(::active)) { "Child execution is still active" }; closed = true }
        releaseIfClosedAndIdle()
    }
    private fun releaseIfClosedAndIdle() {
        val release = synchronized(this) { if (!closed || resourcesReleased || tasks.values.any(::active)) false else { resourcesReleased = true; true } }
        if (!release) return
        timer.shutdownNow(); poolLeases.forEach(SubAgentModelPools::release)
        workspace = null; executeWorkspaceChild = null; executeObservedChild = null; executeVideoChild = null; executeImageChild = null
        executeChild = { _, _, _ -> error("Child execution resources released") }; prepareManualCompactor = { it }; onContext = null; onTaskChanged = null; diagnostics = null; callbacks.close()
    }
    override fun close() {
        preventNewTasks()
        val owned = synchronized(this) { closed = true; tasks.values.toList() }
        owned.forEach { stop(it, "cancelled") }
        releaseIfClosedAndIdle()
    }
}
