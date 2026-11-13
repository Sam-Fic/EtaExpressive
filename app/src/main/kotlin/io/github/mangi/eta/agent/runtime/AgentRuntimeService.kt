package io.github.mangi.eta.agent.runtime

import android.app.Service
import io.github.mangi.eta.agent.question.AgentQuestionReceipt
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.OverScroller
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.ComposeView
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.dispatch.AgentDispatchCoordinator
import io.github.mangi.eta.agent.dispatch.AgentDispatchPayload
import io.github.mangi.eta.agent.dispatch.AgentDispatchRuntime
import io.github.mangi.eta.agent.device.AgentTaskSurface
import io.github.mangi.eta.agent.device.AgentTaskSurfaceMode
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.OwnerDisplayPresence
import io.github.mangi.eta.agent.device.VirtualDisplaySession
import io.github.mangi.eta.agent.live.AgentCompletionNotifier
import io.github.mangi.eta.agent.live.AgentRunStreamTracker
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayBubble
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentOverlayOrb
import io.github.mangi.eta.agent.overlay.AgentOrbPosition
import io.github.mangi.eta.agent.overlay.AgentOrbBounds
import io.github.mangi.eta.agent.overlay.AgentOrbPlacement
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.CapsuleFlingPhysics
import io.github.mangi.eta.agent.overlay.CapsuleFlingPlan
import io.github.mangi.eta.agent.overlay.applyControlledEvent
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.agent.xiaomi.XiaomiToolsBridgeProtocol
import io.github.mangi.eta.data.model.AssistantPrompt
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayController
import io.github.mangi.eta.agent.virtualdisplay.VirtualDisplaySnapshot
import io.github.mangi.eta.agent.virtualdisplay.VirtualTouchEffect
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceMessenger = Messenger(IncomingHandler())

    /** 反向 Messenger 调用器：Runtime 进程把 ToolsBridge 工具调用送回入口进程。 */
    private val xiaomiToolsBridgeRemoteCaller =
        XiaomiToolsBridgeRemoteCaller(serviceMessenger)

    /**
     * 本地多 run 并发扩展：宿主代理按 runId 登记。fork 单槽在并发 run 下会互踩，
     * 这里每个带宿主能力的 run 各持一份，事件按 callId 由持有者消费。
     */
    private val hostProxies = ConcurrentHashMap<String, AgentRuntimeHostProxy>()

    /** 思维流：浮层归属 run 的事件折叠成通知卡预览；作用域与 state 投影一致（见 applyControlledEvent）。 */
    private val streamTracker = AgentRunStreamTracker(this, mainHandler) { snapshot ->
        AgentExecutionService.updateRunStream(this@AgentRuntimeService, snapshot)
    }

    /** 外部入口连点时的请求解码池；过去每个 IPC 请求都直接建线程，线程数没有上限。 */
    private val requestExecutor = AgentRequestExecutor()

    private val sessions = AgentRuntimeSessionRegistry()
    // 准入：同会话单飞、并发容量、外部替换前任排空；GUI：手机操作权按动作阶段互斥。
    private val runs = RuntimeRunRegistry()
    private val guiControl = GuiControlCoordinator()
    private val questionLedger by lazy { io.github.mangi.eta.agent.question.AgentQuestionLedgerAndroid.forContext(applicationContext) }
    private val stopWorker = AgentRuntimeStopWorker { failure ->
        AndroidAgentLogger.warn("Runtime stop failed: type=${failure.safeLogType()}")
    }
    private val resultWorker = AgentRuntimeResultWorker { failure ->
        AndroidAgentLogger.warnThrottled("runtime_result_work_failed") {
            "Agent runtime result work failed: type=${failure.safeLogType()}"
        }
    }
    @Volatile private var destroyed = false
    private val pendingStartRequests = linkedMapOf<String, PendingStartRequest>()
    // Main thread only: a stop sent while this run's history payload is still decoding must
    // reject the request when ingest is finally posted, not be silently dropped.
    private val decodingStartRuns = linkedSetOf<String>()
    private val stoppedBeforeIngest = linkedSetOf<String>()
    // Keep the originating identity and frozen mode even after terminal registry removal.
    @Volatile
    private var overlaySession: AgentRuntimeSession? = null
    private val overlayRunId: String?
        get() = overlaySession?.runId

    private data class PendingStartRequest(
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )

    private var windowManager: WindowManager? = null
    private var orbView: ComposeView? = null
    private var bubbleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardDismissing = false
    private var capsuleParams: WindowManager.LayoutParams? = null
    // Retain the user's placement across hide/show while this service is alive.
    private var orbPosition: AgentOrbPosition? = null
    private var orbDragStart = AgentOrbPosition(0f, 0f)

    /** 顶部胶囊只描述当前虚拟屏；画面与阶段由控制器快照驱动。 */
    private val virtualDisplayState = mutableStateOf(VirtualDisplaySnapshot.Inactive)
    /** 后台 run 的 owner 副屏存在性；非空时胶囊手势改投 owner。 */
    private var ownerPresence: OwnerDisplayPresence? = null
    private val capsuleExpanded = mutableStateOf(false)
    private val capsuleSupplementFocusable = mutableStateOf(false)
    private val capsuleYielded = mutableStateOf(false)
    /**
     * 胶囊落点（**内容盒**口径，物理坐标）：中心 x + 内容顶边 y。
     * null = 还没定过位，由 [clampCapsuleIntoViewport] 落到「可用区水平居中、贴系统栏下」。
     * 唯一写入方是 Compose 侧的帧循环（[applyCapsulePosition]）；宿主不再自己算位置，
     * 只在首次 addView 与边界变化时按同一套内容盒几何钳一次。
     */
    private var capsuleCenterX: Float? = null
    private var capsuleContentTopY: Float? = null
    /** 光球抛掷惯性：OverScroller 摩擦曲线 + Choreographer 物理帧（左上坐标、边界硬停）。 */
    private var orbFlingScroller: OverScroller? = null
    private val orbFlingFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val scroller = orbFlingScroller ?: return
            if (!scroller.computeScrollOffset()) {
                orbFlingScroller = null
                return
            }
            orbPosition = AgentOrbPosition(scroller.currX.toFloat(), scroller.currY.toFloat())
            positionOverlayWindows()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    /** 缩球停靠态：大力甩向横边触发，点球恢复。 */
    private val capsuleMinimized = mutableStateOf(false)
    private val restoreCapsule = Runnable { setCapsuleYielded(false) }
    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            mainHandler.removeCallbacks(restoreCapsule)
            mainHandler.postDelayed(restoreCapsule, delayMs)
        }

        override fun cancelRestore() {
            mainHandler.removeCallbacks(restoreCapsule)
        }
    }

    /**
     * 浮层与执行通知共用同一份运行状态；写入只发生在主线程。
     *
     * 实现为 `MutableState` 的委托对象（而非普通 `mutableStateOf`）：写入时把状态投送到
     * 执行通知（状态栏胶囊 / 锁屏），同时保持字段类型不变，反射读取该字段的测试照常工作。
     */
    private val state = object : MutableState<AgentOverlayState> {
        private val holder = mutableStateOf(AgentOverlayState.Initial)
        override var value: AgentOverlayState
            get() = holder.value
            set(next) {
                holder.value = next
                // 只有 Agent run 在跑时才把状态投送到执行通知；没有活动 run 时回落为普通常驻通知。
                AgentExecutionService.updateRunStatus(
                    this@AgentRuntimeService,
                    next.status.takeIf { sessions.activeRunIds().isNotEmpty() },
                )
            }
        override fun component1(): AgentOverlayState = value
        override fun component2(): (AgentOverlayState) -> Unit = { value = it }
    }
    // The compact orb does not display tool details or round counters.
    private val orbPhase = derivedStateOf { state.value.phase }
    private val collapsed = mutableStateOf(true)
    private var hasExecutedForegroundTool = false
    private val supplementsLock = Any()
    private val supplementsByRunId = linkedMapOf<String, RunSupplements>()
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null

    /**
     * 调度域：协调器 + 每会话最近一次 run 触碰时间（含是否派发来源）。
     * touch 供派发续跑的历史组合判断「这会话被非派发 run 动过没有」。
     */
    private val dispatchCoordinator: AgentDispatchCoordinator = AgentDispatchCoordinator(
        context = this,
        startRun = { request -> startRun(request) },
        stopRun = { runId -> runCatching { cancelRun(runId); true }.getOrDefault(false) },
        activeConversationIds = { runs.snapshots().map { it.conversationId }.toSet() },
        activeRunIdFor = { conversationId ->
            runs.snapshots().firstOrNull { it.conversationId == conversationId }?.runId
        },
    )
    private val hideToken = Any()
    private val pendingHistorySnapshots = ConcurrentHashMap<String, AgentRuntimeHistoryTransfer.PreparedHistory>()
    private val pendingResultTranscripts =
        ConcurrentHashMap<String, AgentRuntimeTranscriptTransfer.PreparedTranscript>()

    private val pendingCompactionTransfers = ConcurrentHashMap<String, MutableList<AgentRuntimeTranscriptTransfer.PreparedTranscript>>()

    override fun onCreate() {
        super.onCreate()
        AgentDispatchRuntime.coordinator = dispatchCoordinator
        // 重启对账：armed 判死发声、queued 重新派发（见 AgentDispatchCoordinator.recoverClaims）。
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { dispatchCoordinator.recoverClaims() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Dispatch claim recovery failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentOverlayClearance.attach(clearanceHost)
        lifecycleScope.launch {
            AgentChildTaskGroups.revision.collect { stopIfRuntimeIdle() }
        }
        lifecycleScope.launch {
            VirtualDisplayController.snapshots.collect { snapshot ->
                // 前台 run 使用进程内控制器；后台 run 的副屏由 owner 会话驱动。
                val presence = ownerPresence
                applyVirtualDisplaySnapshot(
                    if (!snapshot.active && presence != null) ownerVirtualSnapshot(presence) else snapshot,
                )
            }
        }
        lifecycleScope.launch {
            VirtualDisplaySession.displayPresence.collect { presences ->
                val presence = activeOwnerPresence(presences)
                val previous = ownerPresence
                ownerPresence = presence
                if (presence == null) {
                    if (previous != null && !VirtualDisplayController.snapshots.value.active) {
                        applyVirtualDisplaySnapshot(VirtualDisplaySnapshot.Inactive)
                    }
                    return@collect
                }
                if (VirtualDisplayController.snapshots.value.active) return@collect
                applyVirtualDisplaySnapshot(ownerVirtualSnapshot(presence))
            }
        }
        lifecycleScope.launch {
            // 胶囊展开时抓取 owner 副屏实况；只在确实展开且窗口存在时轮询，收起后立即停。
            while (isActive) {
                delay(OWNER_PREVIEW_INTERVAL_MS)
                val presence = ownerPresence ?: continue
                if (!capsuleExpanded.value || capsuleView == null) continue
                val frame = withContext(Dispatchers.IO) {
                    VirtualDisplaySession.capturePreview(presence.runId)
                } ?: continue
                if (ownerPresence != presence) continue
                val current = virtualDisplayState.value
                if (!current.active || current.displayId != presence.displayId) continue
                virtualDisplayState.value = current.copy(
                    frame = frame,
                    frameSequence = current.frameSequence + 1,
                )
            }
        }
    }

    private fun activeOwnerPresence(presences: Map<String, OwnerDisplayPresence>): OwnerDisplayPresence? {
        // 只有一个 owner 副屏；胶囊首次出现时 overlaySession 可能还没绑定。
        return overlayRunId?.let(presences::get) ?: presences.values.singleOrNull()
    }

    private fun ownerVirtualSnapshot(presence: OwnerDisplayPresence): VirtualDisplaySnapshot {
        val previous = virtualDisplayState.value
        val sameDisplay = previous.active && previous.displayId == presence.displayId
        return VirtualDisplaySnapshot(
            active = true,
            displayId = presence.displayId,
            width = presence.width.coerceAtLeast(1),
            height = presence.height.coerceAtLeast(1),
            appName = presence.appName,
            packageName = presence.packageName,
            userControlling = sameDisplay && previous.userControlling,
            materializingToMain = false,
            frame = if (sameDisplay) previous.frame else null,
            frameSequence = previous.frameSequence,
            touchEffect = if (sameDisplay) previous.touchEffect else null,
        )
    }

    private fun applyVirtualDisplaySnapshot(snapshot: VirtualDisplaySnapshot) {
        val wasActive = virtualDisplayState.value.active
        virtualDisplayState.value = snapshot
        if (wasActive == snapshot.active) return
        if (!snapshot.active) {
            VirtualDisplayController.setStageExpanded(false)
            capsuleExpanded.value = false
            capsuleSupplementFocusable.value = false
            capsuleYielded.value = false
            mainHandler.removeCallbacks(restoreCapsule)
            // 后台 run 失去副屏后没有任何可展示的表面；保留会话身份只会在终态时误判。
            val session = overlaySession
            if (session != null && !AgentOverlayVisibilityPolicy.allowsOverlay(
                    session.taskSurfaceMode, false,
                )
            ) {
                removeAmbientWindows(animated = true)
                overlaySession = null
            }
        } else {
            bindVirtualDisplayOverlaySession()
        }
        syncActiveRunOverlayVisibility()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stopOrbFling()
        // 旋转后物理坐标系（屏幕宽高）失效：按新边界把内容落点重钳一次再投影。留白与 y 用
        // 最后一次上报的内容尺寸算，尺寸本身等 Compose 重新布局后上报。
        // 帧循环那侧也会用新 viewport 逐帧夹回——两侧同一套内容盒几何，结果一致，不会互相推。
        windowManager?.let(::clampCapsuleIntoViewport)
        applyCapsuleWindowBounds()
        positionOverlayWindows()
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // start 面调用方校验：onStartCommand 里拿不到真实调用方（投递由 system_server
        // 代转，Binder.getCallingUid 恒为系统 uid），Messenger 的 uid 白名单只护 bind 面。
        // 本服务仅有的两个 start action 都由本应用组件发起（执行通知卡按钮、进程内
        // keep-alive），统一要求携带进程存活期随机的能力令牌：外部 app 伪造不出带
        // 当前令牌的 intent（读不到别的进程内存），最多触发 stopSelf，操纵不了运行
        // 中的 agent。进程重启后令牌更换，旧通知按钮失效属预期（通知随后重建换新）。
        if (intent == null || intent.getStringExtra(EXTRA_START_TOKEN) != startActionToken) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_ISLAND_TOGGLE_PAUSE) {
            toggleIslandPause()
            if (sessions.isEmpty() && !AgentChildTaskGroups.hasAnyActive()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_KEEP_ALIVE || (sessions.isEmpty() && !AgentChildTaskGroups.hasAnyActive())) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    /** 通知卡上的暂停/继续：目标取浮层归属的 run——卡片状态正是它的投影。 */
    private fun toggleIslandPause() {
        val session = overlaySession ?: return
        val controller = session.controller
        if (controller.isPaused) controller.resume() else controller.pause()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (sessions.anyNonTerminal()) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        // Losing the presentation/service is not consent to stop children. Keep their snapshots
        // and pending choices; a replacement service must not receive a deferred global stop.
        destroyed = true
        setKeepScreenOn(false)
        resultWorker.close()
        val retiring = sessions.snapshot()
        retiring.forEach { it.signalStop() }
        // Service destruction also runs on Main. Drain accepted stop work independently of
        // the lifecycle; neither child coordination nor resource cancellation may block Main.
        stopWorker.close(retiring.map { session ->
            {
                session.controller.cancel()
                try {
                    AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.USER_CANCEL)
                } finally {
                    session.cancel("Agent Runtime 服务已停止")
                }
                Unit
            }
        })
        failPendingStarts("Agent Runtime 服务已停止")
        xiaomiToolsBridgeRemoteCaller.close()
        hostProxies.values.forEach { proxy -> runCatching { proxy.close() } }
        hostProxies.clear()
        overlaySession = null
        mainHandler.removeCallbacksAndMessages(null)
        AgentOverlayClearance.detach(clearanceHost)
        VirtualDisplayController.release(this)
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        orbView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        bubbleView = null
        orbView = null
        resultCardParams = null
        capsuleParams = null
        bubbleParams = null
        orbParams = null
        windowManager = null
        pendingResultTranscripts.values.forEach { prepared -> runCatching { prepared.close() } }
        pendingResultTranscripts.clear()
        pendingHistorySnapshots.values.forEach { it.close() }
        pendingHistorySnapshots.clear()
        pendingCompactionTransfers.values.forEach { transfers -> synchronized(transfers) { transfers.forEach { it.close() } } }
        pendingCompactionTransfers.clear()
        // 停止接收新的请求解码任务；已入队的请求继续跑完，不影响正在进行的 run。
        requestExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                } else if (msg.what == AgentRuntimeWire.MSG_HOST_RESULT) {
                    AgentRuntimeWire.closeHostAttachments(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    val replyTo = msg.replyTo
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", replyTo)
                        return
                    }
                    val requestedRunId = AgentRuntimeWire.runIdFromBundle(data)
                    if (requestedRunId.isNotBlank()) decodingStartRuns.add(requestedRunId)
                    // incomingRunRequestFromBundle reads and decodes the history transfer file
                    // (bounded at 16 MiB). Keep that off the main looper; state changes and
                    // failure delivery still happen on main.
                    requestExecutor.execute(
                        name = "agent-runtime-request-ingest",
                        onRejected = {
                            if (requestedRunId.isNotBlank()) decodingStartRuns.remove(requestedRunId)
                            AndroidAgentLogger.warnThrottled("runtime_request_rejected") {
                                "Agent runtime rejected start request: executor saturated"
                            }
                            finishWithFailure("Agent Runtime 请求过多，本次未执行；请稍后重试", replyTo)
                        },
                    ) {
                        val decoded = runCatching {
                            AgentRuntimeWire.incomingRunRequestFromBundle(data)
                        }
                        val posted = mainHandler.post {
                            if (requestedRunId.isNotBlank()) decodingStartRuns.remove(requestedRunId)
                            val stopped = requestedRunId.isNotBlank() &&
                                stoppedBeforeIngest.remove(requestedRunId)
                            val incoming = decoded.getOrNull()
                            if (incoming == null) {
                                AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                                    "Agent runtime rejected invalid start request: " +
                                        "type=${decoded.exceptionOrNull()?.safeLogType()}"
                                }
                                finishWithFailure("Agent Runtime 请求格式无效", replyTo)
                                return@post
                            }
                            if (stopped) {
                                incoming.close()
                                sendRequestIngestedTo(replyTo, incoming.request.runId)
                                sendResultTo(
                                    replyTo,
                                    AgentRuntimeWire.RunResult(
                                        runId = incoming.request.runId,
                                        ok = false,
                                        content = "",
                                        error = "已停止",
                                    ),
                                )
                                return@post
                            }
                            val request = incoming.request
                            if (request.runId.isBlank() || (request.prompt.isBlank() && incoming.images.isEmpty())) {
                                incoming.close()
                                finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", replyTo)
                                return@post
                            }
                            ingestRunRequest(incoming, replyTo)
                        }
                        if (!posted) decoded.getOrNull()?.close()
                    }
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_CANCEL_CONVERSATION -> {
                    val conversationId = msg.data?.getString("conversation_id").orEmpty()
                    if (conversationId.isNotBlank() && conversationId.length <= 1024) {
                        cancelConversation(conversationId)
                    }
                }

                AgentRuntimeWire.MSG_STOP_MAIN_RUN -> {
                    val data = msg.data ?: return
                    val runId = AgentRuntimeWire.runIdFromBundle(data)
                    val reason = if (data.getString("child_stop_reason") == AgentChildControlPolicy.Reason.SETTINGS_CHANGED.name) {
                        AgentChildControlPolicy.Reason.SETTINGS_CHANGED
                    } else AgentChildControlPolicy.Reason.USER_STOP
                    if (runId.isNotBlank()) stopMainRun(runId, reason)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    resultWorker.submit {
                        AgentRuntimeResultStore.remove(applicationContext, runId)
                        releaseResultTranscript(runId)
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo)
                }

                AgentRuntimeWire.MSG_XIAOMI_TOOLS_BRIDGE_RESULT -> {
                    val result = msg.data?.let(XiaomiToolsBridgeProtocol::callResultFromBundle)
                    if (result != null) xiaomiToolsBridgeRemoteCaller.accept(result)
                }

                AgentRuntimeWire.MSG_HOST_EVENT -> {
                    val event = runCatching {
                        AgentRuntimeWire.hostEventFromBundle(msg.data ?: return)
                    }.getOrNull() ?: return
                    hostProxies.values.forEach { proxy -> proxy.onEvent(event) }
                }

                AgentRuntimeWire.MSG_HOST_RESULT -> {
                    val data = msg.data ?: return
                    val result = runCatching {
                        AgentRuntimeWire.hostResultFromBundle(data)
                    }.getOrElse {
                        AgentRuntimeWire.closeHostAttachments(data)
                        return
                    }
                    val owner = hostProxies.values.firstOrNull { proxy -> proxy.isTracking(result.callId) }
                    if (owner != null) {
                        owner.onResult(result)
                    } else {
                        result.attachments.forEach { attachment ->
                            runCatching { attachment.fileDescriptor?.close() }
                        }
                    }
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }

                AgentRuntimeWire.MSG_QUERY_HISTORY -> {
                    val data = msg.data ?: return
                    val reply = msg.replyTo ?: return
                    val runId = AgentRuntimeWire.runIdFromBundle(data)
                    val snapshotId = data.getString("history_snapshot_id").orEmpty()
                    val conversationId = data.getString("conversation_id").orEmpty()
                    val queryId = data.getString("history_query_id").orEmpty()
                    val capturedSession = sessions.get(runId)
                    requestExecutor.execute(name = "eta-history-query") {
                        val response = AgentRuntimeWire.ackBundle(runId).apply {
                            putString("history_snapshot_id", snapshotId)
                            putString("history_query_id", queryId)
                            putString("conversation_id", conversationId)
                            putBoolean("history_available", false)
                        }
                        var prepared: AgentRuntimeHistoryTransfer.PreparedHistory? = null
                        try {
                            require(runId.isNotBlank() && runId.length <= 1024 &&
                                snapshotId.length <= 128 && conversationId.isNotBlank() && conversationId.length <= 1024 &&
                                queryId.matches(Regex("[0-9a-f-]{36}")))
                            // A UUID does not confer ownership: check the captured run's UI handoff.
                            val owner = synchronized(supplementsLock) { supplementsByRunId[runId]?.conversationId }
                            val snapshot = capturedSession?.takeIf { sessions.contains(it) && owner == conversationId }
                                ?.historySnapshot(snapshotId)
                            if (snapshot != null) {
                                prepared = AgentRuntimeHistoryTransfer.prepareSnapshot(this@AgentRuntimeService, snapshot.history)
                                response.putInt("snapshot_round", snapshot.round)
                                response.putParcelable(AgentRuntimeWire.KEY_HISTORY_FD, requireNotNull(prepared).descriptor)
                                response.putBoolean("history_available", true)
                            }
                            if (prepared != null) {
                                val transfer = requireNotNull(prepared)
                                check(pendingHistorySnapshots.putIfAbsent(queryId, transfer) == null)
                                // Keep the same-process descriptor alive until the receiver consumes it.
                                // A lost/late client is cleaned without affecting the running session.
                                mainHandler.postDelayed({
                                    if (pendingHistorySnapshots.remove(queryId, transfer)) transfer.close()
                                }, 60_000L)
                            }
                            reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_HISTORY_RESPONSE).apply { this.data = response })
                            prepared = null // Pending transfer now owns it; release/timeout closes it.
                        } catch (failure: Exception) {
                            response.remove(AgentRuntimeWire.KEY_HISTORY_FD)
                            response.putBoolean("history_available", false)
                            runCatching { reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_HISTORY_RESPONSE).apply { this.data = response }) }
                        } finally {
                            prepared?.let { transfer ->
                                pendingHistorySnapshots.remove(queryId, transfer)
                                transfer.close()
                            }
                        }
                    }
                }

                AgentRuntimeWire.MSG_RELEASE_HISTORY -> {
                    msg.data?.getString("history_query_id")?.let { pendingHistorySnapshots.remove(it)?.close() }
                }

                AgentRuntimeWire.MSG_QUERY_QUESTION -> {
                    val data = msg.data ?: return
                    val reply = msg.replyTo ?: return
                    requestExecutor.execute(name = "eta-question-query") {
                        val snapshot = runCatching {
                            val conversationId = data.getString("conversation_id").orEmpty()
                            val runId = AgentRuntimeWire.runIdFromBundle(data)
                            val questionId = data.getString("question_id").orEmpty()
                            val toolCallId = data.getString("tool_call_id").orEmpty()
                            require(listOf(conversationId, runId, questionId, toolCallId).all { it.isNotBlank() && it.length <= 1024 })
                            AgentQuestionAuthority.query(sessions, questionLedger,
                                conversationId, runId, questionId, toolCallId)
                        }.getOrNull()
                        runCatching { reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUERY_QUESTION_RESPONSE).apply {
                            this.data = AgentRuntimeWire.questionSnapshotBundle(data, snapshot)
                        }) }
                    }
                }

                AgentRuntimeWire.MSG_QUESTION_ANSWER -> {
                    val data = msg.data ?: return
                    val reply = msg.replyTo ?: return
                    // Decoding is bounded; answer validation and event callbacks do not run on Main.
                    val questionId = data.getString("question_id").orEmpty().take(1024)
                    requestExecutor.execute(name = "eta-question-answer") {
                        val receipt = runCatching {
                            val submission = AgentRuntimeWire.questionAnswerFromBundle(data)
                            sessions.get(submission.runId)?.submitQuestionAnswer(submission)
                                ?: AgentQuestionReceipt(false, "QUESTION_RUN_NOT_ACTIVE", "该任务已停止或结束")
                        }.getOrElse {
                            AgentQuestionReceipt(false, "QUESTION_INVALID_ANSWER", "回答格式无效")
                        }
                        runCatching {
                            reply.send(Message.obtain(null, AgentRuntimeWire.MSG_QUESTION_ANSWER_RESPONSE).apply {
                                this.data = AgentRuntimeWire.questionReceiptBundle(questionId, receipt)
                            })
                        }
                    }
                }

                AgentRuntimeWire.MSG_STEER_RUN -> {
                    val data = msg.data ?: return
                    requestSupplementForRun(
                        runId = AgentRuntimeWire.runIdFromBundle(data),
                        text = AgentRuntimeWire.steerTextFromBundle(data),
                        requestId = data.getString("request_id").orEmpty(),
                        imagesJson = data.getString("images_json") ?: "[]",
                    )
                }

                AgentRuntimeWire.MSG_PAUSE_RUN -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    requestPause(runId)
                }

                AgentRuntimeWire.MSG_RESUME_RUN -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    requestResume(runId)
                }

                AgentRuntimeWire.MSG_COMPACT_RUN -> {
                    val data = msg.data ?: return
                    val accepted = requestCompactRun(
                        runId = AgentRuntimeWire.runIdFromBundle(data),
                        keepRecent = AgentRuntimeWire.compactKeepRecentFromBundle(data),
                        compressModelConfig = AgentRuntimeWire.compactModelConfigFromBundle(data),
                        childTaskId = AgentRuntimeWire.compactChildTaskFromBundle(data),
                    )
                    msg.replyTo?.let { reply -> runCatching {
                        reply.send(Message.obtain(null, AgentRuntimeWire.MSG_COMPACT_RUN).apply {
                            this.data = android.os.Bundle().apply { putBoolean("compact_accepted", accepted) }
                        })
                    } }
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val runId = incoming.request.runId
        pendingStartRequests.remove(runId)?.let { previous ->
            failPendingStart(previous, "已被同一任务的新请求替换")
        }
        val pending = PendingStartRequest(incoming, replyTo)
        pendingStartRequests[runId] = pending
        requestExecutor.execute(
            name = "agent-runtime-image-ingest",
            onRejected = {
                pendingStartRequests.remove(runId)?.let { previous ->
                    failPendingStart(previous, "请求过多，已拒绝")
                }
                AndroidAgentLogger.warnThrottled("runtime_request_rejected") {
                    "Agent runtime rejected image ingest: executor saturated"
                }
                finishWithFailure("Agent Runtime 请求过多，本次未执行；请稍后重试", replyTo)
            },
        ) {
            val prepared = runCatching {
                val request = AgentRuntimeImageTransfer.materialize(incoming)
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    // 账号登录的令牌只在本进程解密，绝不能从调用方的 Bundle 读取。
                    val runtimeConfig = runBlocking {
                        RuntimeConfigRepository.oauthRuntimeConfigFor(
                            providerId = request.config.providerId,
                            authMode = request.config.authMode,
                            modelName = request.config.model,
                        )
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                if (pendingStartRequests[runId] !== pending) return@post
                pendingStartRequests.remove(runId)
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                        )
                    },
                )
            }
        }
    }

    /**
     * 派发 run 的执行位置豁免：全局「每次询问」档下不弹窗——派发是后台干活，弹系统级
     * 前台/后台选择框会阻塞工具线程 ≤90s（AgentTaskPrompt.await）、不说明是哪个会话在问、
     * 调度台也收不到任何信号。副屏可用（AGENT_VIRTUAL_DISPLAY_ENABLED，与 executor 的
     * GUI 工具准入同一开关）时直接落到后台副屏：GUI 操作在虚拟屏上完成，不碰真实屏幕；
     * 副屏不可用（无 root/模块或开关关闭）保持 ASK——弹窗仍是非 root 设备的既有 UX。
     * 前台/后台档原样继承；非派发 run 不受影响。
     */
    private fun resolveRunTaskSurface(request: AgentRuntimeWire.RunRequest): AgentTaskSurfaceMode {
        val stored = runCatching { AgentTaskSurface.stored() }.getOrDefault(AgentTaskSurfaceMode.FOREGROUND)
        if (request.handoff?.source != AgentRuntimeWire.ETA_DISPATCH_HANDOFF_SOURCE ||
            stored != AgentTaskSurfaceMode.ASK
        ) {
            return stored
        }
        return if (currentRuntimePermissions().virtualDisplay) {
            AgentTaskSurfaceMode.BACKGROUND
        } else {
            AgentTaskSurfaceMode.ASK
        }
    }

    private fun startRun(
        incoming: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) = startRunPrepared(incoming.withPlainAssistantForExternalEntry(), replyTo)

    /** 实际起跑：入参已按入口归一化（见 [AgentRuntimeWire.RunRequest.withPlainAssistantForExternalEntry]）。 */
    private fun startRunPrepared(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        // 准入：同 runId 在跑 → 把新客户端挂到现有 run；同会话已有任务 → 拒绝（CONVERSATION_BUSY）；
        // 外部归档替换同会话前任 → 接受并取消前任，由 run 线程等前任排空后再执行。
        lateinit var newSession: AgentRuntimeSession
        newSession = AgentRuntimeSession(
            runId = request.runId,
            // 派发 run 豁免「每次询问」：后台干活不该弹全局弹窗问执行位置（弹窗不说是谁在
            // 问，还会阻塞工具线程 ≤90s 且调度台零信号）。见 [resolveRunTaskSurface]。
            taskSurfaceMode = resolveRunTaskSurface(request),
            questionLedger = questionLedger,
            terminalWork = { action ->
                if (Looper.myLooper() == Looper.getMainLooper()) thread(name = "eta-question-seal", block = action)
                else action()
                Unit
            },
            beforeTerminalPublish = { runs.finish(request.runId, newSession) },
            eventSink = { event -> sendEventTo(replyTo, event, request.runId) },
            resultSink = { result -> sendResultTo(replyTo, result) },
        )
        val admission = runs.admit(request) { RuntimeRunRegistry.Run(request, newSession) }
        val accepted = when (admission) {
            is RuntimeRunRegistry.Admission.Accepted -> admission
            is RuntimeRunRegistry.Admission.Duplicate -> {
                sendRequestIngestedTo(replyTo, request.runId)
                attachRun(request.runId, replyTo)
                return
            }
            is RuntimeRunRegistry.Admission.Rejected -> {
                AndroidAgentLogger.debug { "Agent runtime request rejected: code=${admission.code}" }
                finishWithFailure(admission.message, replyTo)
                // 派发 run 的债权已武装：忙拒绝回队首重试，其他拒绝按 FAILED 收声；
                // 非派发 run 在账本里静默忽略。随后排空本 run 出口暂存的下一单。
                dispatchCoordinator.onRunStartRejected(request, admission.code)
                dispatchCoordinator.onRunFinished(request.runId)
                return
            }
        }
        accepted.replacedRuns.forEach { previous -> cancelRun(previous.runId) }
        val run = accepted.run
        val session = run.session
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val runLease = AgentRuntimeRunLease.create(request.runId)
        val executionHeld = AgentExecutionService.acquire(
            this, runLease.id, allowBoundFallback = allowBoundFallback,
        ) {
            session.signalStop()
            stopWorker.submit(session) {
                session.controller.cancel()
                session.cancel("已停止")
            }
        }
        if (!executionHeld && (!allowBoundFallback || AgentExecutionService.backupMaintenance)) {
            val startFailure = AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = getString(R.string.runtime_service_start_failed),
            )
            // 此分支的 session.complete 不经 persistCompletedRun（beforePublish 为空），
            // 派发债权收不到终态会挂到重启：这里先结算，registry 随 complete 里的
            // beforeTerminalPublish 注销后即可安全排空。
            dispatchCoordinator.onRunTerminal(request, startFailure)
            session.complete(startFailure) {}
            dispatchCoordinator.onRunFinished(request.runId)
            return
        }
        AgentChildRunControl.begin(session)
        sessions.put(session)
        // 常亮只跟随 run 的存活与暂停：取消、暂停、结束都要立刻交还系统息屏。
        val screenOnBindings = listOf(
            session.controller.register { postKeepScreenOnUpdate() },
            session.controller.observePause { postKeepScreenOnUpdate() },
        )
        updateKeepScreenOn()
        if (overlayRunId == request.runId) {
            // ASK 选前台后才会用悬浮窗，先按前台保留入口；选后台时后续事件不会再显示。
            if (AgentOverlayVisibilityPolicy.mayUseOverlay(
                    session.taskSurfaceMode, virtualDisplayActive(),
                )
            ) {
                overlaySession = session
            } else {
                // Only retire the replaced run's windows, never another foreground run's.
                dismissAndStop()
            }
        }
        if (lastCompletedRunContext?.request?.runId == request.runId) {
            lastCompletedRunContext = null
        }
        runCatching {
            startService(startActionIntent(this, ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        if (AgentOverlayVisibilityPolicy.mayUseOverlay(
                session.taskSurfaceMode, virtualDisplayActive(),
            )
        ) {
            mainHandler.removeCallbacksAndMessages(hideToken)
            state.value = AgentOverlayState.Initial
            collapsed.value = true
            if (overlayRunId == null || overlaySession === session) {
                hasExecutedForegroundTool = false
            }
        }
        synchronized(supplementsLock) {
            // 派发 run 也登记会话归属：MSG_CANCEL_CONVERSATION 按这里找到该会话的全部 run。
            val extras = RunSupplements(conversationId = when (request.handoff?.source) {
                AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE ->
                    request.handoff.payload.let { AgentUiHandoffPayload.from(it).conversationId }
                AgentRuntimeWire.ETA_DISPATCH_HANDOFF_SOURCE ->
                    request.handoff.payload.let { AgentDispatchPayload.from(it)?.conversationId }.orEmpty()
                else -> ""
            })
            if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                val payload = AgentUiHandoffPayload.from(request.handoff.payload)
                extras.items += payload.supplements
                extras.nextIndex = (
                    listOfNotNull(payload.promptSupplement?.index) +
                        payload.supplements.map { it.index }
                    ).maxOrNull()?.plus(1) ?: 1
            }
            supplementsByRunId[request.runId] = extras
        }

        // 无回信通道的调用方无法承接入口工具反向调用：剥掉 entryTools 声明。
        val executableRequest = if (replyTo == null && request.entryTools.isNotEmpty()) {
            request.copy(entryTools = emptyList())
        } else {
            request
        }
        // 宿主代理按 run 登记；同 runId 的替换请求先关旧代理，避免跨 run 泄漏。
        hostProxies.remove(request.runId)?.close()
        val hostProxy = if (replyTo != null && request.hostCapabilities.isNotEmpty()) {
            AgentRuntimeHostProxy(
                context = this,
                hostMessenger = replyTo,
                capabilities = request.hostCapabilities,
                directTools = request.config.deviceTools,
                sensitiveActionTools = request.config.deviceTools,
                logger = AndroidAgentLogger,
            )
        } else {
            null
        }
        hostProxy?.let { hostProxies[request.runId] = it }

        try {
            run.prepared(request)
            thread(name = "agent-runtime") {
                // 外部归档替换时等已取消的前任排空；等待中被取消则直接落终态，不再发模型请求。
                val drained = runCatching { run.awaitPredecessors() }
                if (drained.isFailure) {
                    // 多 run 并发：还有其他活动 run 时不撤销状态投送，避免通知闪回空闲、思维流被清。
                    if (sessions.activeRunIds().none { it != request.runId }) {
                        AgentExecutionService.updateRunStatus(this@AgentRuntimeService, null)
                    }
                    AgentChildRunControl.finish(session)
                    AgentExecutionService.release(runLease.id)
                    screenOnBindings.forEach { it.close() }
                    postKeepScreenOnUpdate()
                    session.cancel("已停止")
                    return@thread
                }
                try {
                    executeRun(session, executableRequest, replyTo, hostProxy)
                } finally {
                    // run 结束即撤销状态栏胶囊，避免残留的状态投送；多 run 并发时还有其他
                    // 活动 run 就先不撤，等它们的下一个事件接管（防通知闪回空闲）。
                    if (sessions.activeRunIds().none { it != request.runId }) {
                        AgentExecutionService.updateRunStatus(this@AgentRuntimeService, null)
                    }
                    AgentChildRunControl.finish(session)
                    AgentExecutionService.release(runLease.id)
                    screenOnBindings.forEach { it.close() }
                    postKeepScreenOnUpdate()
                    // 兜底注销：终态发布钩子被跳过时（如存储失败路径）也要释放会话名额。
                    runs.finish(request.runId, session)
                    // 出 registry 后才排空调度队列：结算点本 run 还在册，同步起跑必撞忙。
                    dispatchCoordinator.onRunFinished(request.runId)
                }
            }
        } catch (failure: Throwable) {
            screenOnBindings.forEach { it.close() }
            AgentChildRunControl.finish(session)
            AgentExecutionService.release(runLease.id)
            val result = AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "", error = getString(R.string.runtime_thread_start_failed),
            )
            AndroidAgentLogger.error("Agent runtime thread start failed: type=${failure.safeLogType()}")
            runCatching { persistCompletedRun(request, result) }
            session.complete(result) {}
            // 线程没起来就没有 finally 兜底：这里显式排空（persistCompletedRun 已结算暂存）。
            dispatchCoordinator.onRunFinished(request.runId)
            postTerminalOverlay(
                session, result, null,
                conversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId),
            )
        }
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
        entryMessenger: Messenger?,
        hostProxy: AgentRuntimeHostProxy?,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            entryToolTarget = entryMessenger,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withActiveSupplements() },
            guiControl = guiControl,
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
            xiaomiToolsBridgeInvoker = entryMessenger?.let { target ->
                { call -> xiaomiToolsBridgeRemoteCaller.call(target, call) }
            },
            hostToolExecutor = hostProxy,
        ).execute(session, request)
        if (hostProxy != null) hostProxies.remove(request.runId, hostProxy)
        hostProxy?.close()
        if (!outcome.shouldUpdateHost) return
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            conversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId),
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    CompletedRunContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        // 操作权状态与悬浮窗策略无关，先于叠加层早退分支登记，供 run 快照读取。
        if (event is AgentEvent.GuiControlChanged) {
            runs.get(session.runId)?.guiStatus = event.status
            // 派发 run 的操作权等待同步到调度台（非派发 run 在账本里静默忽略）：
            // 并发 GUI 时派发 run 可能无限期排队，这是提问管线之外第二个看不见的等待。
            dispatchCoordinator.onChildGuiControl(session.runId, event.status)
        }
        // 子会话提问同步到调度台：仅派发 run（协调器按账本武装债权判定，非派发 run 返回
        // null 静默）。横幅只在后台提醒（前台静默），返回早退分支不影响这里——提问通知
        // 与悬浮窗策略无关，必须先于它们执行。
        when (event) {
            is AgentEvent.QuestionRequested -> {
                dispatchCoordinator.onChildQuestionAsked(event)?.let { conversationId ->
                    runCatching {
                        AgentCompletionNotifier.notifyQuestionWaiting(
                            this,
                            conversationId,
                            summary = listOf(event.request.title, event.request.question)
                                .filter { it.isNotBlank() }
                                .joinToString("："),
                        )
                    }
                }
            }
            is AgentEvent.QuestionResolved -> {
                dispatchCoordinator.onChildQuestionResolved(event)?.let { conversationId ->
                    runCatching { AgentCompletionNotifier.cancelQuestionWaiting(this, conversationId) }
                }
            }
            else -> Unit
        }
        if (!sessions.contains(session)) return
        if (!AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode, virtualDisplayActive())) return
        val revealsForegroundOperation =
            AgentOverlayVisibilityPolicy.shouldRevealFor(
                event, session.taskSurfaceMode, virtualDisplayActive(),
            )
        val requiresEntrySurfaceDismissal =
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event, session.taskSurfaceMode)
        val entrySurfaceReady = if (requiresEntrySurfaceDismissal && entrySurfaceGuard != null) {
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)
        } else {
            true
        }
        mainHandler.post {
            if (!sessions.contains(session)) return@post
            if (
                AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                    session.taskSurfaceMode,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                val ownsOverlay = claimOverlay(session, revealsForegroundOperation && entrySurfaceReady)
                if (ownsOverlay) {
                    state.value = state.value.applyControlledEvent(event, session.controller.isPaused)
                    streamTracker.onEvent(event)
                }
                if (revealsForegroundOperation && entrySurfaceReady && ownsOverlay) {
                    if (orbView == null && capsuleView == null) {
                        AgentHapticFeedback.perform(
                            this,
                            AgentHapticFeedback.Type.RUN_STARTED,
                        )
                    }
                    ensureOverlayVisible()
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
        conversationId: String = "",
    ) {
        mainHandler.post {
            // Removal is identity-based: a late callback cannot consume a replacement
            // session's supplements or reveal its windows just because run IDs match.
            if (!sessions.remove(session)) return@post
            updateKeepScreenOn()
            synchronized(supplementsLock) { supplementsByRunId.remove(session.runId) }
            // 完成横幅先于浮层早退分支发布：后台 run / 副屏 run 不进浮层投影，也要能收到终态通知。
            AgentCompletionNotifier.notifyTerminal(
                this@AgentRuntimeService,
                conversationId = conversationId,
                kind = when {
                    result.ok -> AgentCompletionNotifier.TerminalKind.COMPLETED
                    // 与下方浮层终态同口径：错误文案「已停止」即用户主动停止。
                    result.error == "已停止" -> AgentCompletionNotifier.TerminalKind.STOPPED
                    else -> AgentCompletionNotifier.TerminalKind.FAILED
                },
                summary = (if (result.ok) result.content else result.error.orEmpty()).take(60),
            )
            // 岛终态（小米私有）：成功才上岛展开，失败/停止在执行卡安静收尾（Number444 fork 真机定稿）。
            if (result.ok) {
                AgentCompletionNotifier.notifyIslandTerminal(
                    this@AgentRuntimeService,
                    AgentCompletionNotifier.TerminalKind.COMPLETED,
                    conversationId = conversationId,
                    summary = result.content,
                )
            }
            // 思维流随整机 run 收尾一并清空（与 updateRunStatus 的 null 口径一致）。
            if (sessions.activeRunIds().isEmpty()) {
                streamTracker.reset()
                AgentExecutionService.updateRunStream(this@AgentRuntimeService, null)
            }
            if (
                !AgentOverlayVisibilityPolicy.allowsOverlay(
                    session.taskSurfaceMode, virtualDisplayActive(),
                ) ||
                overlaySession !== session
            ) {
                if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive() &&
                    orbView == null && bubbleView == null && capsuleView == null && resultCardView == null
                ) {
                    stopSelf()
                }
                return@post
            }
            lastCompletedRunContext = completedContext
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                        session = session,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                        session = session,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(target: Messenger?, event: AgentEvent, runId: String) {
        if (target == null) return
        var prepared: AgentRuntimeTranscriptTransfer.PreparedTranscript? = null
        try {
            if (event is AgentEvent.ContextCompacted && event.applied && event.history.isNotEmpty()) {
                prepared = AgentRuntimeTranscriptTransfer.prepare(this, event.history)
            }
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event, prepared?.descriptor).apply {
                if (event is AgentEvent.AssistantBlockDelta) {
                    putLong(StreamDeliveryTiming.KEY, android.os.SystemClock.elapsedRealtimeNanos())
                }
            }
            target.send(msg)
            prepared?.let { transfer ->
                val pending = pendingCompactionTransfers.computeIfAbsent(runId) { mutableListOf() }
                synchronized(pending) { pending += transfer }
            }
        } catch (failure: Exception) {
            prepared?.close()
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${failure.safeLogType()}"
            }
        }
    }

    private fun sendResultTo(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        if (target == null) return
        val prepared = runCatching {
            AgentRuntimeTranscriptTransfer.prepare(this, result.transcript)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_transcript_prepare_failed") {
                "Agent runtime result transcript prepare failed: type=${throwable.safeLogType()}"
            }
        }.getOrNull()
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, prepared?.descriptor)
            target.send(msg)
            if (prepared != null) {
                val key = result.runId.ifBlank { "anonymous-${System.nanoTime()}" }
                pendingResultTranscripts.put(key, prepared)?.close()
            }
        }.onFailure { throwable ->
            prepared?.close()
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun releaseResultTranscript(runId: String) {
        if (runId.isBlank()) return
        pendingResultTranscripts.remove(runId)?.close()
        pendingCompactionTransfers.remove(runId)?.let { transfers -> synchronized(transfers) { transfers.forEach { it.close() } } }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?) {
        if (replyTo == null) return
        resultWorker.submit {
            if (destroyed) return@submit
            val results = AgentRuntimeResultStore.list(applicationContext)
            if (destroyed) return@submit
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(results)
            replyTo.send(msg)
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.activeRunsBundle(sessions.activeRunIds())
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?, attached: Boolean = false) {
        val session = sessions.get(runId)
        val attachedToSession = replyTo != null &&
            runId.isNotBlank() &&
            session != null &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event, runId) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attachedToSession) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        // 调度域挂钩对所有 run 生效（在 handoff 早退之前）：未知 runId 在账本里静默忽略，
        // 但每个会话的组合历史都在这里记录——它是接续派发比 UI 历史文件新的权威来源。
        dispatchCoordinator.onRunTerminal(request, result)
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    // 屏幕截图会作为聊天里的大图留档，用留档预览；普通附件仍走列表缩略图档。
                    AgentImageCodec.archivePreviewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
        // Preparation failures have no session/overlay ownership. They must not
        // reuse another run's foreground-execution flag or terminal windows.
        if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive() &&
            orbView == null && bubbleView == null && capsuleView == null && resultCardView == null
        ) {
            stopSelf()
        }
    }

    private fun requestStop() {
        val runId = overlayRunId ?: sessions.activeRunIds().lastOrNull()
        if (runId == null) {
            dismissAndStop()
            return
        }
        stopMainRun(runId)
    }

    private fun stopMainRun(
        runId: String,
        reason: AgentChildControlPolicy.Reason = AgentChildControlPolicy.Reason.USER_STOP,
    ) {
        if (runId.isBlank()) return
        // The start request may still be off-main decoding history; remember the stop so the
        // ingest post cannot start it after this point.
        if (runId in decodingStartRuns) stoppedBeforeIngest.add(runId)
        pendingStartRequests.remove(runId)?.let { pending -> failPendingStart(pending, "已停止") }
        val session = sessions.get(runId) ?: return
        // Capture this exact session before leaving Main; a delayed stop must never re-resolve
        // runId and cancel a replacement. Blocking cancellation used to starve UI watchdogs.
        session.signalStop()
        stopWorker.submit(session) {
            if (!session.isTerminal) {
                // Publish the child-choice decision before requestStop cancels the controller;
                // otherwise the cancellation gate can make the continue/pause prompt disappear.
                try {
                    AgentChildRunControl.terminate(session, reason)
                } finally {
                    val accepted = session.requestStop()
                    if (accepted) mainHandler.post {
                        if (!destroyed && overlaySession === session && session.terminalResult == null) {
                            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
                        }
                    }
                }
            }
        }
    }

    private fun cancelConversation(conversationId: String) {
        val pendingIds = pendingStartRequests.filterValues { pending ->
            pending.incoming.request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.payload
                ?.let { AgentUiHandoffPayload.from(it).conversationId } == conversationId
        }.keys.toList()
        pendingIds.forEach { runId ->
            pendingStartRequests.remove(runId)?.let { pending -> failPendingStart(pending, "已停止") }
        }
        val runIds = synchronized(supplementsLock) {
            supplementsByRunId.filterValues { it.conversationId == conversationId }.keys.toList()
        }
        runIds.forEach(::cancelRun)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        // 派发债权先行按「已停止」结算：晚到的终态结算因账本幂等而无效，避免通知悬挂。
        dispatchCoordinator.onRunAborted(runId)
        val targets = AgentChildTaskGroups.captureRunStopTargets(runId)
        // Explicit whole-task cancellation is not a terminal network failure or a second choice.
        stopMainRun(runId, AgentChildControlPolicy.Reason.USER_CANCEL)
        retireChildTargets(targets)
    }

    private fun retireChildTargets(targets: List<AgentChildTaskGroups.StopTarget>) {
        if (targets.isEmpty()) return
        childCleanup.execute {
            targets.forEach { captured ->
                runCatching { AgentChildTaskGroups.stop(captured) }.onFailure { failure ->
                    AndroidAgentLogger.error("Child task cleanup failed: type=${failure.safeLogType()}")
                }
            }
        }
    }

    private fun stopIfRuntimeIdle() {
        if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive() &&
            orbView == null && bubbleView == null && capsuleView == null && resultCardView == null) stopSelf()
    }

    private fun requestPause() {
        requestPause(overlayRunId.orEmpty())
    }

    private fun requestPause(runId: String) {
        if (runId.isBlank()) return
        val session = sessions.get(runId)?.takeUnless { it.isTerminal || it.controller.isCancelled } ?: return
        AgentChildRunControl.pause(session)
        // 用户暂停期间保留接管权：冻结该 run 等待中/后续的手机操作，恢复时统一放行。
        guiControl.pauseForUser(runId)
        if (overlaySession === session) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.PAUSED,
                status = AgentOverlayStatus.Paused,
            )
        }
    }

    private fun requestResume() {
        requestResume(overlayRunId.orEmpty())
    }

    private fun requestCompactRun(
        runId: String,
        keepRecent: Int? = null,
        compressModelConfig: AgentModelClient.ModelConfig? = null,
        childTaskId: String? = null,
    ): Boolean {
        if (runId.isBlank()) return false
        return sessions.get(runId)?.requestCompact(keepRecent, compressModelConfig, childTaskId) ?: false
    }

    private fun requestResume(runId: String) {
        if (runId.isBlank()) return
        val session = sessions.get(runId)?.takeUnless { it.isTerminal || it.controller.isCancelled } ?: return
        AgentChildRunControl.resume(session)
        guiControl.resumeForUser()
        if (overlaySession === session) {
            state.value = state.value.copy(
                phase = AgentOverlayPhase.RUNNING,
                status = AgentOverlayStatus.Continuing,
            )
        }
    }

    private fun requestSupplementForRun(runId: String, text: String, requestId: String = "", imagesJson: String = "[]") {
        if (runId.isBlank() || sessions.get(runId) == null) return
        requestSupplement(text, runId, requestId, imagesJson)
    }

    private fun requestSupplement(text: String, runId: String? = overlayRunId, requestId: String = "", imagesJson: String = "[]") {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        val targetRunId = runId.orEmpty()
        if (runCatching { io.github.mangi.eta.agent.model.AgentSupplementMedia.persistedImages(imagesJson) }.isFailure) return
        if (requestId.isNotBlank() && synchronized(supplementsLock) {
                supplementsByRunId[targetRunId]?.items?.any { it.requestId == requestId } == true
            }) return
        val targetSession = sessions.get(targetRunId)
        val updatesOverlay = targetSession?.let {
            AgentOverlayVisibilityPolicy.allowsOverlay(it.taskSurfaceMode, virtualDisplayActive())
        } ?: true
        if (updatesOverlay) setOverlayInputMode(focusable = false)
        targetSession?.let { session ->
            val event = session.steer(supplementText, imagesJson) {
                recordSupplementEvent(targetRunId, supplementText, requestId, imagesJson)
            }
            if (event == null) {
                if (!session.isTerminal) {
                    if (updatesOverlay) {
                        state.value = state.value.copy(
                            status = AgentOverlayStatus.Finishing,
                        )
                    }
                    return
                }
            } else {
                AgentChildRunControl.resume(session)
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                if (updatesOverlay) state.value = state.value.applyEvent(event)
                return
            }
        }

        val completed = lastCompletedRunContext ?: return
        if (targetRunId.isNotBlank() && completed.request.runId != targetRunId) return
        if (completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
            requestId = requestId,
            imagesJson = imagesJson,
        )
        startRun(continuationRequest)
    }

    private fun recordSupplementEvent(runId: String, text: String, requestId: String = "", imagesJson: String = "[]"): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            val extras = supplementsByRunId.getOrPut(runId) { RunSupplements() }
            AgentUiHandoffPayload.Supplement(
                index = extras.nextIndex++,
                text = text,
                requestId = requestId,
                imagesJson = imagesJson,
                createdAt = System.currentTimeMillis(),
            ).also { extras.items += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
            requestId = supplement.requestId,
            imagesJson = supplement.imagesJson,
        )
    }

    /** 副屏激活时把顶部胶囊绑定到当前应当显示的任务。 */
    private fun bindVirtualDisplayOverlaySession() {
        val current = overlaySession
        if (current != null && sessions.contains(current) && !current.isTerminal) return
        val candidate = sessions.snapshot().lastOrNull { session ->
            !session.isTerminal &&
                AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode, true)
        } ?: return
        overlaySession = candidate
        state.value = AgentOverlayState.Initial
        collapsed.value = true
        capsuleExpanded.value = false
        capsuleSupplementFocusable.value = false
        capsuleYielded.value = false
        hasExecutedForegroundTool = false
    }

    /**
     * 副屏出现/消失时切换表面：有副屏用顶部胶囊，没有副屏时前台任务回到光球；
     * 后台任务失去副屏后没有任何可展示的表面，直接撤窗并释放叠加层所有权。
     */
    private fun syncActiveRunOverlayVisibility() {
        val session = overlaySession ?: return
        val vdActive = virtualDisplayActive()
        if (!AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode, vdActive)) {
            removeAmbientWindows(animated = true)
            overlaySession = null
            return
        }
        if (vdActive) {
            removeOrbWindows(animated = true)
        } else {
            removeCapsuleWindow(animated = true)
        }
        showOverlay()
    }

    private fun virtualDisplayActive(): Boolean = virtualDisplayState.value.active

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    /** 控制器回调可能来自工作线程；窗口标志只能在 Main 上改。 */
    private fun postKeepScreenOnUpdate() {
        mainHandler.post { updateKeepScreenOn() }
    }

    private fun updateKeepScreenOn() {
        setKeepScreenOn(
            !destroyed && sessions.snapshot().any {
                !it.isTerminal && !it.controller.isCancelled && !it.controller.isPaused
            },
        )
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        keepScreenOnState.value = enabled
        orbView?.keepScreenOn = enabled
        bubbleView?.keepScreenOn = enabled
        capsuleView?.keepScreenOn = enabled
    }

    private fun showOverlay() {
        val session = overlaySession ?: return
        val vdActive = virtualDisplayActive()
        if (!AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode, vdActive)) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm
        if (vdActive) {
            if (capsuleView != null) return
            removeOrbWindows(animated = true)
            showCapsuleOverlay(wm, session)
        } else {
            if (orbView != null) return
            removeCapsuleWindow(animated = true)
            showOrbOverlay(wm)
        }
    }

    private fun showOrbOverlay(wm: WindowManager) {
        // No full-screen decorative window: it shared the UI/RenderThread with chat scrolling.
        // ── 光球窗口：默认右侧中下，可拖动 ──────────────────────────
        val orb = createOverlayComposeView {
            AgentOverlayOrb(
                phase = orbPhase.value,
                collapsed = collapsed.value,
                onToggleCollapse = ::toggleCollapse,
                onDragStart = ::beginOrbDrag,
                onDrag = ::handleDrag,
                onDragEnd = ::handleOrbDragEnd,
            )
        }
        val orbLp = orbLayoutParams()
        orb.keepScreenOn = keepScreenOnState.value
        // 从胶囊切回光球时与胶囊淡出交叉衔接；先置透明再挂窗，避免挂上一帧闪满。
        orb.alpha = 0f
        orb.scaleX = ORB_ENTER_SCALE
        orb.scaleY = ORB_ENTER_SCALE
        runCatching { wm.addView(orb, orbLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_orb_add_view_failed") {
                "Agent runtime orb addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        orb.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(WINDOW_ENTER_DURATION_MS)
            .start()
        orbView = orb
        orbParams = orbLp
        orb.visibility = View.VISIBLE
        orb.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (orbView === orb && (r - l != or - ol || b - t != ob - ot)) positionOverlayWindows()
        }
        positionOverlayWindows()

        // ── 小气泡窗口：展开态显示，跟随光球，窗口外触摸穿透 ─────────
        if (!collapsed.value) {
            showBubble(wm)
        }
    }

    /**
     * 顶部状态胶囊：状态栏下方居中，收起时只有阶段指示器 + 一句状态，展开后补充/暂停/停止，
     * 虚拟屏激活时追加画廊装裱视窗与接管、全屏投射。
     */
    private fun showCapsuleOverlay(wm: WindowManager, session: AgentRuntimeSession) {
        val controlRunId = session.runId
        val capsule = createOverlayComposeView {
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                yielded = capsuleYielded.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = {
                    if (ownerPresence == null) VirtualDisplayController.setUserControlling(false)
                    requestPause(controlRunId)
                },
                onResume = {
                    if (ownerPresence == null) VirtualDisplayController.setUserControlling(false)
                    requestResume(controlRunId)
                },
                onStop = { stopMainRun(controlRunId) },
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = { text -> requestSupplement(text, controlRunId) },
                virtualDisplay = virtualDisplayState.value,
                ownerBacked = ownerPresence != null,
                onTakeOverVirtualDisplay = {
                    if (ownerPresence == null) VirtualDisplayController.setUserControlling(true)
                    requestPause(controlRunId)
                },
                onResumeVirtualDisplay = {
                    if (ownerPresence == null) VirtualDisplayController.setUserControlling(false)
                    requestResume(controlRunId)
                },
                onFullscreenVirtualDisplay = {
                    if (ownerPresence == null) requestFullscreenVirtualDisplay()
                },
                onTapVirtualCanvas = { nx, ny, longPress ->
                    val presence = ownerPresence
                    if (presence != null) {
                        val now = android.os.SystemClock.uptimeMillis()
                        virtualDisplayState.value = virtualDisplayState.value.copy(
                            touchEffect = VirtualTouchEffect.Tap(now, now, nx, ny, longPress),
                        )
                        thread(name = "eta-vd-stage-tap", isDaemon = true) {
                            VirtualDisplaySession.dispatchUserTap(presence.runId, nx, ny, longPress)
                        }
                    } else {
                        VirtualDisplayController.dispatchUserTapOnCanvas(nx, ny, longPress)
                    }
                },
                onSwipeVirtualCanvas = { sx, sy, ex, ey, durationMs ->
                    val presence = ownerPresence
                    if (presence != null) {
                        val now = android.os.SystemClock.uptimeMillis()
                        virtualDisplayState.value = virtualDisplayState.value.copy(
                            touchEffect = VirtualTouchEffect.Swipe(now, now, sx, sy, ex, ey, durationMs),
                        )
                        thread(name = "eta-vd-stage-swipe", isDaemon = true) {
                            VirtualDisplaySession.dispatchUserSwipe(
                                presence.runId, sx, sy, ex, ey, durationMs,
                            )
                        }
                    } else {
                        VirtualDisplayController.dispatchUserSwipeOnCanvas(sx, sy, ex, ey, durationMs)
                    }
                },
                onContentSizeChanged = ::updateCapsuleWindowSize,
                onDragEnd = ::prepareCapsuleFling,
                onPosition = ::applyCapsulePosition,
                capsulePlacement = {
                    val centerX = capsuleCenterX
                    val contentTopY = capsuleContentTopY
                    if (centerX == null || contentTopY == null) null else Offset(centerX, contentTopY)
                },
                minimized = capsuleMinimized.value,
            )
        }
        val lp = capsuleLayoutParams()
        capsule.keepScreenOn = keepScreenOnState.value
        runCatching { wm.addView(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = lp
    }

    private fun toggleCapsule() {
        if (capsuleMinimized.value) {
            // 点球恢复：原地收成球，就在原地展开回收起态胶囊，形变由逐帧窗口贴合无缝承接。
            capsuleMinimized.value = false
            capsuleExpanded.value = false
            AgentHapticFeedback.perform(overlayContext(), AgentHapticFeedback.Type.TAP)
            return
        }
        val expanded = !capsuleExpanded.value
        capsuleExpanded.value = expanded
        // 窗口由 Compose 上报状态机「早涨晚缩」贴合（updateCapsuleWindowSize）：
        // morph 两端各动一次窗，中途内容在窗内动画，不再逐帧 resize。
        if (virtualDisplayActive() && ownerPresence == null) {
            VirtualDisplayController.setStageExpanded(expanded)
        }
    }

    private fun onCapsuleCollapsedSettled() {
        if (ownerPresence == null) VirtualDisplayController.setStageExpanded(false)
    }

    /** 胶囊内的补充输入需要窗口可获焦才能唤起输入法；结束后恢复为穿透态。 */
    private fun setCapsuleInputMode(focusable: Boolean) {
        if (focusable) {
            setCapsuleYielded(false)
            capsuleSupplementFocusable.value = true
        } else {
            capsuleSupplementFocusable.value = false
        }
        val wm = windowManager
        val view = capsuleView
        val lp = capsuleParams
        if (wm != null && view != null && lp != null) {
            val nextFlags = if (focusable) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            }
            if (lp.flags != nextFlags) {
                lp.flags = nextFlags
                runCatching { wm.updateViewLayout(view, lp) }.onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                        "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
                    }
                }
            }
        }
    }

    private fun setOverlayInputMode(focusable: Boolean) {
        if (virtualDisplayActive() && capsuleView != null) {
            setCapsuleInputMode(focusable)
        } else {
            setBubbleInputMode(focusable)
        }
    }

    /** 手势即将落在胶囊上时让出触摸与画面，结束后由调用方延迟恢复。 */
    private fun setCapsuleYielded(yielded: Boolean) {
        if (capsuleYielded.value == yielded) return
        capsuleYielded.value = yielded
        val wm = windowManager ?: return
        val view = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(view, lp) }
    }

    private fun requestFullscreenVirtualDisplay() {
        VirtualDisplayController.setStageExpanded(false)
        thread(name = "eta-vd-handoff-main", isDaemon = true) {
            runCatching { VirtualDisplayController.handoffToMainDisplay(this@AgentRuntimeService) }
                .onFailure { throwable ->
                    AndroidAgentLogger.warnThrottled("runtime_virtual_fullscreen_failed") {
                        "Virtual display fullscreen handoff failed: type=${throwable.safeLogType()}"
                    }
                }
        }
    }

    private fun capsuleWindowWidthPx(): Int {
        val widthDp = if (capsuleExpanded.value) {
            CAPSULE_EXPANDED_WINDOW_WIDTH_DP
        } else {
            // 微缩镜预览已移除：收起态宽度不再因虚拟屏在场而加宽。
            CAPSULE_WINDOW_WIDTH_DP
        }
        return dpToPx(widthDp)
    }

    private fun capsuleWindowHeightPx(): Int {
        val heightDp = when {
            // 从舞台进入补充输入时保留舞台高度，让整块舞台完整淡出，而不是被窗口当场裁掉。
            capsuleSupplementFocusable.value && virtualDisplayActive() -> CAPSULE_STAGE_HEIGHT_DP
            capsuleSupplementFocusable.value -> CAPSULE_SUPPLEMENT_HEIGHT_DP
            !capsuleExpanded.value -> CAPSULE_COLLAPSED_HEIGHT_DP
            virtualDisplayActive() -> CAPSULE_STAGE_HEIGHT_DP
            else -> CAPSULE_CONTROLS_HEIGHT_DP
        }
        return dpToPx(heightDp)
    }

    /** Compose 侧上报的胶囊 Surface 当前布局尺寸（仅 morph 两端与自愈时变化）。 */
    private var capsuleContentWidthPx = 0
    private var capsuleContentHeightPx = 0

    /**
     * 胶囊内容尺寸回调：来自 Compose「早涨晚缩」上报状态机——分析目标变大立即贴上，
     * 变小方向等动画落定（测量值静默期）后上报 max(分析目标, 测量)。被窗口钳制过的
     * 测量值因此永不回喂，「钳制→上报→收窗」逐帧缩水回路无从触发；负值一律丢弃。
     * 窗口据此外扩固定留白后贴合，morph 两端各动一次。
     */
    private fun updateCapsuleWindowSize(contentWidthPx: Int, contentHeightPx: Int) {
        if (contentWidthPx != Int.MIN_VALUE) capsuleContentWidthPx = contentWidthPx
        if (contentHeightPx != Int.MIN_VALUE) capsuleContentHeightPx = contentHeightPx.coerceAtLeast(0)
        applyCapsuleWindowBounds()
    }

    /**
     * 窗口投影：把「上报的内容尺寸 + 内容落点」换算成窗口尺寸与左上角，**不自己发明位置**。
     *
     * 位置只有一个来源——Compose 侧帧循环逐帧下发的内容落点（[applyCapsulePosition]）；
     * 边界变化时由 [clampCapsuleIntoViewport] 按同一套内容盒几何钳一次。上一版这里还会
     * 「静止时」按**状态目标窗口尺寸**把中心钳回界内，而循环按**实时内容尺寸**钳，
     * 两者每次涨缩都相差 Δ宽/2，只能靠一堆所有权标志互相让渡，每一次让渡就是一次跳变
     * （贴左边收起态展开要瞬移 ~56dp 就是它）。
     */
    private fun applyCapsuleWindowBounds() {
        val wm = windowManager ?: return
        val view = capsuleView ?: return
        val lp = capsuleParams ?: return
        if (capsuleContentWidthPx <= 0 || capsuleContentHeightPx <= 0) return
        val centerX = capsuleCenterX ?: return
        val contentTopY = capsuleContentTopY ?: return
        // 外衬必须与 Compose 侧 Surface padding 的实际像素严格对齐再加缓冲：
        // Compose 是每侧独立 roundToPx(12dp)，而 dpToPx 是截断——2.8125 密度下
        // 12dp 截断成 33px、Compose 实际消耗 34px，窗口每帧比内容矮 1px，内容被
        // 钳小后上报更小，形成「钳制→上报→收窗」逐帧缩水回路（几秒缩到消失）。
        val width = capsuleContentWidthPx + capsulePaddingPx(CAPSULE_PADDING_HORIZONTAL_DP) +
            CAPSULE_WINDOW_SLACK_PX
        val height = capsuleContentHeightPx + capsulePaddingPx(CAPSULE_PADDING_VERTICAL_DP) +
            CAPSULE_WINDOW_SLACK_PX
        // 窗口绕内容中心对称收放、顶边贴内容顶边减外衬：内容涨缩时本体不动，
        // 外衬那 12dp 允许溢出可用边界（停靠贴合的就是本体边缘，同参考实现）。
        val x = (centerX - width / 2f).roundToInt()
        val y = (contentTopY - capsuleGutterPx()).roundToInt()
        if (lp.width == width && lp.height == height && lp.x == x && lp.y == y) return
        lp.width = width
        lp.height = height
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(view, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_bounds_update_failed") {
                "Agent runtime capsule bounds update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /**
     * 帧循环逐帧下发的内容落点：只记位置并做对称投影，不做任何钳制
     * （越界与贴边回弹都由物理层负责，钳一下就会打断积分轨迹）。
     * x 用「中心 − 当前窗宽/2」，缩球形变期间窗口绕中心对称收窄，不跳位。
     */
    private fun applyCapsulePosition(centerX: Float, contentTopY: Float) {
        if (!centerX.isFinite() || !contentTopY.isFinite()) return
        capsuleCenterX = centerX
        capsuleContentTopY = contentTopY
        val wm = windowManager ?: return
        val view = capsuleView ?: return
        val lp = capsuleParams ?: return
        val x = (centerX - lp.width / 2f).roundToInt()
        val y = (contentTopY - capsuleGutterPx()).roundToInt()
        if (lp.x == x && lp.y == y) return
        lp.x = x
        lp.y = y
        runCatching { wm.updateViewLayout(view, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_bounds_update_failed") {
                "Agent runtime capsule bounds update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    /**
     * 把内容落点钳回可用边界并回存，返回钳后的「中心 x to 内容顶边 y」。
     *
     * 口径与帧循环严格同源：**内容盒**贴合边界，12dp 阴影外衬允许溢出屏外（参考实现里
     * 元素本身就是可见外形，停靠即贴死）。这是宿主仅存的两个自行定位场景——首次 addView
     * 前的初始落位、旋转/inset 变化后的重钳；其余时刻位置一律照单执行循环的下发。
     */
    private fun clampCapsuleIntoViewport(wm: WindowManager): Pair<Float, Float> {
        val bounds = overlayBounds(wm)
        // 还没有内容上报时（首次 addView 前）用窗口尺寸近似出内容尺寸。
        val gutter = capsuleGutterPx()
        val contentWidth = (
            capsuleContentWidthPx.takeIf { it > 0 } ?: capsuleWindowWidthPx() - 2 * gutter
            ).coerceAtLeast(1)
        val contentHeight = (
            capsuleContentHeightPx.takeIf { it > 0 } ?: capsuleWindowHeightPx() - 2 * gutter
            ).coerceAtLeast(1)
        val minCenterX = bounds.left + contentWidth / 2f
        val maxCenterX = (bounds.right - contentWidth / 2f).coerceAtLeast(minCenterX)
        val minTop = bounds.top.toFloat()
        val maxTop = (bounds.bottom - contentHeight).toFloat().coerceAtLeast(minTop)
        val centerX = (capsuleCenterX ?: (bounds.left + (bounds.right - bounds.left) / 2f))
            .coerceIn(minCenterX, maxCenterX)
        val contentTopY = (capsuleContentTopY ?: minTop).coerceIn(minTop, maxTop)
        capsuleCenterX = centerX
        capsuleContentTopY = contentTopY
        return centerX to contentTopY
    }

    /**
     * 抬手：宿主只结算「要不要缩球」（参考实现 release 里的 MORPHV 判定）与确认手感，
     * 拟合速度原样交给 Compose 物理层（逐帧惯性 + 贴边弹簧，见 CapsuleFlingPhysics）。
     * 起点、实时尺寸、边界全在循环手里，这里一律不碰。返回 null = 窗口已不在，无需甩出。
     */
    private fun prepareCapsuleFling(velocityX: Float, velocityY: Float): CapsuleFlingPlan? {
        if (capsuleView == null || capsuleParams == null) return null
        val flingX = if (velocityX.isFinite()) velocityX else 0f
        val flingY = if (velocityY.isFinite()) velocityY else 0f
        // 松手速度 ≥ MORPHV（1200dp/s，任意方向）→ 胶囊 morph 成球。
        // 与参考实现一致：不要求横向意图占优，也不按速度上限折算；参考实现里「甩得够狠才变球」，
        // 变球后惯性飞行、贴最近边停靠。初速上限 MAXV 由循环入口统一钳，这里再叠一道
        // 系统 scaledMaximumFlingVelocity 只会让手感比参考实现更钝。
        val morphVelocityPx = CapsuleFlingPhysics.MorphVelocityDpPerSec * resources.displayMetrics.density
        if (!capsuleMinimized.value && hypot(flingX, flingY) >= morphVelocityPx) {
            capsuleMinimized.value = true
            capsuleExpanded.value = false
            if (virtualDisplayActive() && ownerPresence == null) {
                VirtualDisplayController.setStageExpanded(false)
            }
            // 缩球是「收进收纳位」的确认感，重 click 一记；停靠/回弹不再叠加震动。
            AgentHapticFeedback.perform(overlayContext(), AgentHapticFeedback.Type.LONG_PRESS)
        }
        return CapsuleFlingPlan(
            velocity = Offset(flingX, flingY),
            ballTarget = capsuleMinimized.value,
        )
    }

    private fun capsuleLayoutParams(): WindowManager.LayoutParams {
        // NO_LIMITS 是甩出物理与「本体贴合屏幕边」的共同前提：没有它，WMS 会在每次
        // updateViewLayout 把窗口 frame 钳回显示范围内——飞行途中（参考实现允许整体冲出屏幕
        // 再被同一根弹簧拉回）会被系统逐帧拽回边缘，停靠时那 12dp 阴影外衬也会被顶回去、
        // 本体反而贴不到边。越界的只有外衬：内容盒始终钳在可用边界内（见
        // [clampCapsuleIntoViewport] 与帧循环里的按实时尺寸约束）。
        var flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (capsuleSupplementFocusable.value) {
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        if (capsuleYielded.value) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return WindowManager.LayoutParams(
            capsuleWindowWidthPx(),
            capsuleWindowHeightPx(),
            overlayType(),
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Capsule"
            // 胶囊自绘状态栏间距；窗口本身不参与系统栏自动避让。
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // 物理左上坐标（同光球）：位置与 LTR/RTL 无关；未定过位时钳到「可用区居中、贴系统栏下」。
            gravity = Gravity.TOP or Gravity.LEFT
            val (centerX, contentTopY) = clampCapsuleIntoViewport(
                windowManager
                    ?: overlayContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager,
            )
            x = (centerX - capsuleWindowWidthPx() / 2f).roundToInt()
            y = (contentTopY - capsuleGutterPx()).roundToInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            windowAnimations = 0
        }
    }

    /**
     * 窗口级退场：表面互换、终态收窗都先淡出再摘窗，避免光球/胶囊/气泡凭空消失。
     * 字段在调用方立即置空，保证淡出期间不会再有逻辑引用这块窗口。
     */
    private fun animateWindowOut(view: View) {
        val wm = windowManager ?: return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(0.92f)
            .scaleY(0.92f)
            .setDuration(WINDOW_EXIT_DURATION_MS)
            .withEndAction { runCatching { wm.removeView(view) } }
            .start()
    }

    private fun removeOrbWindows(animated: Boolean = false) {
        stopOrbFling()
        orbView?.let { view ->
            if (animated) animateWindowOut(view) else runCatching { windowManager?.removeView(view) }
        }
        bubbleView?.let { view ->
            if (animated) animateWindowOut(view) else runCatching { windowManager?.removeView(view) }
        }
        orbView = null
        bubbleView = null
        orbParams = null
        bubbleParams = null
    }

    private fun removeCapsuleWindow(animated: Boolean = false) {
        capsuleView?.let { view ->
            if (animated) animateWindowOut(view) else runCatching { windowManager?.removeView(view) }
        }
        capsuleView = null
        capsuleParams = null
    }

    private fun toggleCollapse() {
        collapsed.value = !collapsed.value
        val wm = windowManager ?: return
        if (collapsed.value) {
            bubbleView?.let(::animateWindowOut)
            bubbleView = null
            bubbleParams = null
        } else {
            if (bubbleView == null) showBubble(wm)
        }
    }

    private fun showBubble(wm: WindowManager) {
        val session = overlaySession ?: return
        // 副屏激活时由顶部胶囊取代光球与小气泡。
        if (virtualDisplayActive()) return
        if (!AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode)) return
        if (bubbleView != null) return
        val bubble = createOverlayComposeView {
            AgentOverlayBubble(
                state = state.value,
                onCollapse = ::toggleCollapse,
                onPause = ::requestPause,
                onResume = ::requestResume,
                onStop = ::requestStop,
                onSupplementModeChange = ::setBubbleInputMode,
                onSupplement = { text -> requestSupplement(text) },
            )
        }
        val lp = bubbleLayoutParams()
        bubble.keepScreenOn = keepScreenOnState.value
        runCatching { wm.addView(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_add_view_failed") {
                "Agent runtime bubble addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        bubbleView = bubble
        bubbleParams = lp
        bubble.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (bubbleView === bubble && (r - l != or - ol || b - t != ob - ot)) positionOverlayWindows()
        }
        positionOverlayWindows()
    }

    private fun showResultCard(wm: WindowManager, session: AgentRuntimeSession) {
        if (overlaySession !== session ||
            !AgentOverlayVisibilityPolicy.shouldShowResultCard(
                session.taskSurfaceMode, hasExecutedForegroundTool, virtualDisplayActive(),
            )
        ) return
        if (resultCardView != null) return
        resultCardDismissing = false
        val card = createOverlayComposeView {
            AgentResultCard(
                state = state.value,
                onClose = ::dismissResultCard,
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                // 与主程序（MainActivity → AgentAppTheme）共用同一份外观来源：强调色 / 配色风格 /
                // 深浅模式 / 纯黑 / 动态取色都由 AppearanceSettingsRepository 决定。浮窗常驻，
                // 必须订阅 DataStore 的实时变更，不能只在建窗时读一次。
                // 首帧等设置流第一次发射（通常一个帧内到），避免先闪一帧默认浅色再跳变。
                val appearance = AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = null)
                    .value
                val current = appearance ?: return@setContent
                AgentAppTheme(
                    appearance = current,
                    // 浮窗尺寸由宿主按 dp→px 计算，不能被界面缩放放大后裁切。
                    applyInterfaceScale = false,
                ) {
                    content()
                }
            }
        }

    private fun beginOrbDrag() {
        stopOrbFling()
        val lp = orbParams ?: return
        orbDragStart = orbPosition ?: AgentOrbPosition(lp.x.toFloat(), lp.y.toFloat())
    }

    // dx/dy are total raw-screen displacement from DOWN, not local frame deltas.
    private fun handleDrag(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        orbPosition = AgentOrbPosition(orbDragStart.x + dx, orbDragStart.y + dy)
        positionOverlayWindows()
    }

    private fun stopOrbFling() {
        orbFlingScroller?.forceFinished(true)
        orbFlingScroller = null
        Choreographer.getInstance().removeFrameCallback(orbFlingFrame)
    }

    /** 光球抛掷惯性：OverScroller 摩擦滑行（胶囊已改用 Compose 弹簧，见 CapsuleFlingPhysics）；气泡跟随由 positionOverlayWindows 承接。 */
    private fun handleOrbDragEnd(velocityX: Float, velocityY: Float) {
        val wm = windowManager ?: return
        stopOrbFling()
        if (!velocityX.isFinite() || !velocityY.isFinite()) return
        val orb = orbView ?: return
        val width = orb.width.takeIf { it > 0 } ?: dpToPx(56)
        val height = orb.height.takeIf { it > 0 } ?: dpToPx(56)
        val bounds = overlayBounds(wm)
        val start = orbPosition
            ?: AgentOrbPosition(orbParams?.x?.toFloat() ?: 0f, orbParams?.y?.toFloat() ?: 0f)
        val config = ViewConfiguration.get(overlayContext())
        val maxVelocity = config.scaledMaximumFlingVelocity.toFloat()
        val minVelocity = config.scaledMinimumFlingVelocity.toFloat()
        val flingX = velocityX.coerceIn(-maxVelocity, maxVelocity)
        val flingY = velocityY.coerceIn(-maxVelocity, maxVelocity)
        if (abs(flingX) < minVelocity && abs(flingY) < minVelocity) return
        val scroller = OverScroller(overlayContext())
        scroller.fling(
            start.x.roundToInt(), start.y.roundToInt(),
            flingX.roundToInt(), flingY.roundToInt(),
            bounds.left, bounds.right - width,
            bounds.top, bounds.bottom - height,
        )
        if (scroller.isFinished) return
        orbFlingScroller = scroller
        Choreographer.getInstance().postFrameCallback(orbFlingFrame)
    }

    private fun overlayBounds(wm: WindowManager): AgentOrbBounds = runCatching {
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
        )
        AgentOrbBounds(
            metrics.bounds.left + insets.left, metrics.bounds.top + insets.top,
            metrics.bounds.right - insets.right,
            metrics.bounds.bottom - insets.bottom,
        )
    }.getOrElse {
        AgentOrbBounds(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun positionOverlayWindows() {
        val wm = windowManager ?: return
        val orb = orbView ?: return
        val lp = orbParams ?: return
        val bounds = overlayBounds(wm)
        val orbWidth = orb.width.takeIf { it > 0 } ?: dpToPx(56)
        val orbHeight = orb.height.takeIf { it > 0 } ?: dpToPx(56)
        val desired = orbPosition ?: AgentOrbPlacement.initial(bounds, orbWidth, orbHeight, dpToPx(8))
        val position = AgentOrbPlacement.clamp(desired, bounds, orbWidth, orbHeight)
        orbPosition = position
        if (lp.x != position.x.roundToInt() || lp.y != position.y.roundToInt()) {
            lp.x = position.x.roundToInt()
            lp.y = position.y.roundToInt()
            runCatching { wm.updateViewLayout(orb, lp) }
        }
        val bubble = bubbleView ?: return
        val bubbleLp = bubbleParams ?: return
        val bubbleWidth = bubble.width.takeIf { it > 0 } ?: dpToPx(136)
        val bubbleHeight = bubble.height.takeIf { it > 0 } ?: dpToPx(56)
        val bubblePosition = AgentOrbPlacement.bubble(
            position, orbWidth, orbHeight, bubbleWidth, bubbleHeight, bounds, dpToPx(8),
        )
        if (bubbleLp.x != bubblePosition.x.roundToInt() || bubbleLp.y != bubblePosition.y.roundToInt()) {
            bubbleLp.x = bubblePosition.x.roundToInt()
            bubbleLp.y = bubblePosition.y.roundToInt()
            runCatching { wm.updateViewLayout(bubble, bubbleLp) }
        }
    }

    private fun orbLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Orb"
            // We clamp system-bar/cutout insets ourselves, in physical display coordinates.
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Physical top-left coordinates make raw dragging consistent in LTR and RTL.
            gravity = Gravity.LEFT or Gravity.TOP
            val bounds = overlayBounds(windowManager ?: overlayContext().getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            val position = AgentOrbPlacement.clamp(
                orbPosition ?: AgentOrbPlacement.initial(bounds, dpToPx(56), dpToPx(56), dpToPx(8)),
                bounds, dpToPx(56), dpToPx(56),
            )
            x = position.x.roundToInt()
            y = position.y.roundToInt()
        }

    private fun bubbleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Controls"
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // Follow the orb; measured layout later refines this initial placement.
            gravity = Gravity.LEFT or Gravity.TOP
            x = orbParams?.x ?: 0
            y = orbParams?.y ?: 0
            windowAnimations = 0
        }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            title = "Eta Agent Result"
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    private fun setBubbleInputMode(focusable: Boolean) {
        val wm = windowManager ?: return
        val bubble = bubbleView ?: return
        val lp = bubbleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(bubble, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_bubble_focus_update_failed") {
                "Agent runtime bubble focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    /**
     * Compose 侧胶囊 Surface 的水平/垂直 padding 实际像素：每侧独立 roundToPx 后求和
     * （与 Compose `Modifier.padding` 的测量公式一致）。窗口外衬必须用本函数而不是
     * [dpToPx]（截断）——两者在小数密度（如 2.8125）下差 1px，会被逐帧贴合反馈放大。
     */
    private fun capsulePaddingPx(perSideDp: Int): Int =
        2 * (perSideDp * resources.displayMetrics.density).roundToInt()

    /** 单侧阴影外衬（px）：内容顶边 → 窗口顶边的偏移，与 [capsulePaddingPx] 同一取整口径。 */
    private fun capsuleGutterPx(): Int =
        (CAPSULE_PADDING_HORIZONTAL_DP * resources.displayMetrics.density).roundToInt()

    private fun enterFinalState(
        finalState: AgentOverlayState,
        keepVisible: Boolean = false,
        session: AgentRuntimeSession,
    ) {
        val vdActive = virtualDisplayActive()
        if (overlaySession !== session ||
            !AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode, vdActive)
        ) return
        state.value = finalState

        if (AgentOverlayVisibilityPolicy.shouldShowResultCard(
                session.taskSurfaceMode, hasExecutedForegroundTool, vdActive,
            )
        ) {
            // 撤掉光球和小气泡，改显半屏结果卡片，不自动关闭，用户手动关闭
            collapsed.value = true
            removeAmbientWindows(animated = true)
            windowManager?.let { showResultCard(it, session) }
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows(animated: Boolean = false) {
        removeOrbWindows(animated)
        removeCapsuleWindow(animated)
    }

    /** 结果卡手动关闭：先整窗淡出收小，再释放服务，避免卡片瞬间消失。 */
    private fun dismissResultCard() {
        if (resultCardDismissing) return
        val wm = windowManager
        val card = resultCardView
        if (wm == null || card == null) {
            dismissAndStop()
            return
        }
        resultCardDismissing = true
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(WINDOW_EXIT_DURATION_MS)
            .withEndAction {
                if (resultCardView === card) {
                    resultCardView = null
                    resultCardParams = null
                }
                runCatching { wm.removeView(card) }
                dismissAndStop()
            }
            .start()
    }

    private fun dismissAndStop() {
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardDismissing = false
        removeOrbWindows()
        removeCapsuleWindow()
        resultCardView = null
        resultCardParams = null
        capsuleExpanded.value = false
        capsuleSupplementFocusable.value = false
        capsuleYielded.value = false
        windowManager = null
        overlaySession = null
        hasExecutedForegroundTool = false
        if (sessions.isEmpty() && pendingStartRequests.isEmpty() && !AgentChildTaskGroups.hasAnyActive()) {
            stopSelf()
        }
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    /**
     * 外部入口（系统语音助手浮窗 / 超级小爱 / Breeno）一律以「普通助理」身份运行，
     * 不继承全局活动助手里的调度台。
     *
     * 小爱与 Breeno 的 config 来自 `AgentModelClient.loadConfig()` 的全局快照——那是主界面
     * 最后一次同步时写下的，用户当时若停在调度台会话，快照的 systemPrompt 与 assistantId
     * 就都是调度台；语音浮窗虽已在入口侧回落，但快照路径必须在这里兜住。
     * 归一化同时改三处：`config.systemPrompt` 决定模型看到的纪律提示词，
     * `config.assistantId` / `request.assistantId` 决定 `AgentRuntimeRunExecutor` 是否下发
     * 派发工具、以及记忆与技能按哪个助手装载。
     *
     * 硬边界：主界面（agent_ui，调度台本体）与调度台派发（eta_dispatch）不归一化；
     * 已经是非调度台身份的请求原样返回，不触碰用户自定义助手与其人格设定。
     */
    private fun AgentRuntimeWire.RunRequest.withPlainAssistantForExternalEntry(): AgentRuntimeWire.RunRequest {
        val source = handoff?.source ?: return this
        if (source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE ||
            source == AgentRuntimeWire.ETA_DISPATCH_HANDOFF_SOURCE
        ) return this
        if (assistantId != AssistantPrompt.DISPATCHER_ID &&
            config.assistantId != AssistantPrompt.DISPATCHER_ID
        ) return this
        val plain = AssistantRepository.currentProfile(AssistantRepository.plainActiveId())
            ?: AssistantRepository.plainActive()
        return copy(
            assistantId = plain.id,
            config = config.copy(
                assistantId = plain.id,
                systemPrompt = AssistantPrompt.build(plain.name, plain.prompt),
            ),
        )
    }

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = synchronized(supplementsLock) {
            supplementsByRunId[runId]?.items?.toList().orEmpty()
        }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    private fun failPendingStarts(error: String) {
        pendingStartRequests.values.toList().forEach { pending -> failPendingStart(pending, error) }
        pendingStartRequests.clear()
    }

    private fun failPendingStart(pending: PendingStartRequest, error: String) {
        pending.incoming.close()
        sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
        sendResultTo(
            pending.replyTo,
            AgentRuntimeWire.RunResult(
                runId = pending.incoming.request.runId,
                ok = false,
                content = "",
                error = error,
            ),
        )
    }

    private fun claimOverlay(session: AgentRuntimeSession, wantsOverlay: Boolean): Boolean {
        if (!sessions.contains(session) ||
            !AgentOverlayVisibilityPolicy.allowsOverlay(session.taskSurfaceMode, virtualDisplayActive())
        ) return false
        val current = overlaySession
        if (current === session) return true
        if (!wantsOverlay) return current == null
        if (current != null && sessions.contains(current) && !current.isTerminal) return false
        overlaySession = session
        return true
    }

    companion object {
        /** 只投影到 App 与 GUI 浮层的现有窗口；窗口不可见时由系统自行恢复息屏。 */
        private val keepScreenOnState = MutableStateFlow(false)
        internal val keepScreenOn = keepScreenOnState.asStateFlow()

        // Independent of this service's lifecycle: onDestroy must not cancel its own cleanup.
        private val childCleanup = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "agent-child-cleanup").apply { isDaemon = true }
        }
        private const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"

        /** 执行通知卡上「暂停/继续」按钮的回跳入口；只切换浮层归属的那个 run。 */
        const val ACTION_ISLAND_TOGGLE_PAUSE = "io.github.mangi.eta.agent.runtime.ISLAND_TOGGLE_PAUSE"

        /** start 面能力令牌：进程存活期随机一次，仅本应用组件经 [startActionIntent] 携带。 */
        private val startActionToken: String = UUID.randomUUID().toString()
        private const val EXTRA_START_TOKEN = "io.github.mangi.eta.extra.START_TOKEN"

        /** 组装带能力令牌的 start intent——本服务 start 面的唯一合法入口。 */
        internal fun startActionIntent(context: Context, action: String): Intent =
            Intent(context, AgentRuntimeService::class.java)
                .setAction(action)
                .putExtra(EXTRA_START_TOKEN, startActionToken)
        private const val HIDE_DELAY_MS = 2_500L
        private const val RESULT_REVIEW_DELAY_MS = 120_000L
        private const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
        // 胶囊窗口初始尺寸估计（挂窗第一帧用），此后由 Compose「早涨晚缩」上报状态机
        // 贴合（updateCapsuleWindowSize）：目标变大立即贴上，变小等动画落定收拢。
        // 高度 = 上下留白 24（每侧 12dp 阴影外衬）+ 头部 56（TextFieldDefaults.MinHeight）
        // + 8dp 间距 + 内容 + 8dp 间距；估计偏大由「涨即贴/晚缩」自愈，偏小会裁内容。
        private const val CAPSULE_WINDOW_WIDTH_DP = 272
        // 展开宽 360（Compose 侧还会按屏宽−40 钳制）+ 上下留白 12*2 之外留出的余量；
        // 首帧估计偏大没关系，涨即贴上报会立刻收敛；分析目标 ≥ 任何测量值，不会触发钳制缩水。
        private const val CAPSULE_EXPANDED_WINDOW_WIDTH_DP = 368
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 80
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 144
        private const val CAPSULE_STAGE_HEIGHT_DP = 536
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 272
        // Compose 侧胶囊 Surface 外圈留白（每侧 12dp，按 roundToPx 求和），四周同等：
        // 其中垂直方向的 12dp 是 Compose `CapsuleShadowGutter` 的阴影净空——窗口边界紧贴
        // 内容，投影画在 Surface 布局边界之外，没有这段外衬就会被窗口裁成硬边。
        // 2px 缓冲确保窗口永远略大于「内容 + padding」，钝化任何取整误差；
        // 透明边距肉眼不可见，但任何 1px 缺口都会触发逐帧缩水回路。
        private const val CAPSULE_PADDING_HORIZONTAL_DP = 12
        private const val CAPSULE_PADDING_VERTICAL_DP = 12
        private const val CAPSULE_WINDOW_SLACK_PX = 2
        private const val CAPSULE_CLEARANCE_SLOP_DP = 8
        // 窗口级淡出时长；Compose 内部退场动画通常更短，窗口只负责收尾。
        private const val WINDOW_EXIT_DURATION_MS = 140L
        private const val WINDOW_ENTER_DURATION_MS = 170L
        private const val ORB_ENTER_SCALE = 0.88f
        // 展开视窗的 owner 实况帧轮询间隔；收起后不抓帧。
        private const val OWNER_PREVIEW_INTERVAL_MS = 1_000L
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private data class RunSupplements(
        val conversationId: String = "",
        val items: MutableList<AgentUiHandoffPayload.Supplement> = mutableListOf(),
        var nextIndex: Int = 1,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
