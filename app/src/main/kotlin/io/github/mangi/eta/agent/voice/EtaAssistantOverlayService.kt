package io.github.mangi.eta.agent.voice

import android.Manifest
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.MAX_AGENT_VIDEO_BYTES
import io.github.mangi.eta.agent.media.SCREEN_CONTEXT_IMAGE_SOURCE
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentScreenIntent
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.question.AgentQuestionAnswer
import io.github.mangi.eta.agent.question.AgentQuestionCodec
import io.github.mangi.eta.agent.question.AgentQuestionStatus
import io.github.mangi.eta.ui.components.LocalOpenVoiceSettings
import io.github.mangi.eta.ui.model.AgentQuestionMessageUi
import io.github.mangi.eta.ui.app.AgentQuestionProjection
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.AgentLiveRunHandoff
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.config.AssistantEntryMode
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.ui.MainActivity
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.cacheDisplayName
import io.github.mangi.eta.ui.model.toRequestModelImage
import io.github.mangi.eta.ui.model.encodeUserMessageImages
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Eta 数字助理的用户界面窗口。
 *
 * 系统助理会话只负责承接电源键入口；这里固定使用全屏 TYPE_APPLICATION_OVERLAY，
 * 让输入法、动画和厂商助手式浮窗拥有同一个窗口生命周期。
 */
internal class EtaAssistantOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cancellationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EtaAssistantRuntimeCancel")
    }
    private val runtimeClient = AgentRuntimeClient(this, AndroidAgentLogger)
    // 生成中追加指令（不附带图片时）无需落盘；带附件时按应用内同一套路径把附件暂存到会话缓存目录。
    // 与下方三个协作者同理：AgentChatImageCache 构造期读取 applicationContext，必须等
    // attachBaseContext 之后创建，否则主进程在 new Service 实例时就 NPE 崩溃，浮窗永远唤不出来。
    private lateinit var chatImageCache: AgentChatImageCache
    private val runMessageProjector = AgentRunMessageProjector()
    private val conversationKey = "eta_assistant_${UUID.randomUUID()}"
    private var conversationHistory = emptyList<AgentModelClient.ConversationMessage>()

    private var windowManager: WindowManager? = null
    private var windowView: ComposeView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var detachingWindowView: View? = null
    private val windowDetachCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var backInvokedDispatcher: OnBackInvokedDispatcher? = null
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private var runJob: Job? = null

    /**
     * 对话运行的作用域：独立于浮窗 UI 生命周期。
     *
     * 关闭浮窗只应结束 UI 承载，不应中断已经在 Runtime 中推进的对话。因此 run 不使用
     * [scope]（它随 Service 销毁被取消），而由每次运行自建的 runScope 承载；只有显式
     * 停止（[stopCurrentRun]）才取消它，运行结束（含浮窗已关闭的情况）会在回执结果后
     * 自行回收，结果由 Runtime 归档后进入应用内会话历史。
     */
    private var runScope: CoroutineScope? = null
    private var entryCaptureJob: Job? = null
    private var activeRunId: String? = null

    /**
     * 在跑的 run 的交接信息：关闭浮窗时发布给主程序，让它接管这一单的实时事件。
     *
     * 与 [activeRunId] 同生命周期：run 开跑时写入、终态时清除。runScope 在 IO 线程上结束、
     * 关闭浮窗在主线程上读，所以标 `@Volatile`。
     */
    @Volatile private var activeRunHandoff: AgentLiveRunHandoff.Notice? = null

    private var entryGeneration = 0L
    private var presentedEntryGeneration = -1L
    private var screenContextAttachment: EtaScreenContextAttachment? = null
    private var hiddenForForegroundOperation = false
    private var exitAnimationRequested by mutableStateOf(false)
    private var inputFocusRequestKey by mutableIntStateOf(-1)
    /** 唤起方式=语音识别的听写开录令牌：每次语音唤起 ++；面板在屏时经重组触发重开。 */
    private var dictationStartRequest by mutableIntStateOf(0)
    /**
     * 系统选择器让位中：附件/权限跳板把选择器 Activity 打到浮窗之下，浮窗必须整体
     * 隐身（alpha 0）且整窗放行触摸（FLAG_NOT_TOUCHABLE），选择器才能被正常操作。
     * 会话结束由跳板的 sessionEndListener 信号恢复（见 [beginPickerYield]）。
     */
    private var pickerYield by mutableStateOf(false)
    private var lastWakeHapticAt = 0L
    private var uiState by mutableStateOf(EtaVoiceUiState())

    /**
     * 浮窗的系统选择器 / 权限结果注册表：Service 窗口没有 `startActivityForResult`，
     * 由透明跳板 Activity 代发并把结果回灌到这里（见 [EtaAssistantOverlayResultRegistry]）。
     *
     * 下面三个协作者都在构造期读取 `context.applicationContext`，而 Service 的字段初始化
     * 早于框架的 `attachBaseContext()`，那一刻 `ContextWrapper` 的基类还是空的，取
     * applicationContext 会直接抛 NPE、把整个进程崩在建 Service 对象这一步，浮窗完全唤不出来。
     * 因此一律推迟到 [onCreate] 里创建。
     */
    private lateinit var overlayResultRegistry: EtaAssistantOverlayResultRegistry

    /** 输入区草稿：由浮窗持有，生命周期与浮窗一致，不写入应用内当前会话。 */
    private val composerTextFieldState = TextFieldState()

    /** 输入区状态持有者：模型 / 助手 / 思考档位 / 附件都独立于应用内会话。 */
    private lateinit var composerState: EtaAssistantComposerState

    /** 语音入口控制器：通用语音对话 / 豆包实时通话都由它驱动。 */
    private lateinit var voiceController: VoiceModeController

    /**
     * 熄屏收声接收器。浮窗不经过 Activity 生命周期，没有 ON_STOP 可挂：锁屏 / 亮屏唤起的
     * 语音输入在用户再按电源键（或自动熄屏）后没有任何自然收尾路径，麦克风会一直占用。
     * 只停语音、不动窗口与运行——锁屏唤起场景屏幕仍亮，不受影响；语音自身持有的播报也随
     * stop() 一并结束。
     */
    private var screenOffReceiver: BroadcastReceiver? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        // 基类已 attach，这里才允许读取 applicationContext（见上方字段注释）。
        overlayResultRegistry = EtaAssistantOverlayResultRegistry(this)
        chatImageCache = AgentChatImageCache(this)
        composerState = EtaAssistantComposerState(this, scope)
        voiceController = VoiceModeController(this, scope) { text ->
            // 语音回调来自后台协程；提交会操作 WindowManager / Compose 状态，统一切回主线程。
            scope.launch(Dispatchers.Main.immediate) { submitInput(text) }
        }
        val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF && ::voiceController.isInitialized) {
                    voiceController.stop()
                }
            }
        }
        // 系统广播（熄屏）按本仓惯例注册为 NOT_EXPORTED；注册失败不阻断浮窗，只失去这条兜底。
        runCatching {
            registerReceiver(
                screenOffReceiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
            this.screenOffReceiver = screenOffReceiver
        }
        activeService = this
        // 浮窗生命周期内对外暴露结果注册表，供透明跳板 Activity 回灌选择器 / 权限结果。
        EtaAssistantOverlayPickerBridge.registry = overlayResultRegistry
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 唯一动作就是「显示入口浮窗」；空 action / 未知 action 同样按显示处理。
        showEntry()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        screenOffReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenOffReceiver = null
        entryGeneration++
        entryCaptureJob?.cancel()
        entryCaptureJob = null
        screenContextAttachment = null
        // 关闭浮窗只结束 UI 承载，不打断正在 Runtime 中推进的对话：运行协程挂在独立的
        // runScope 上，会在结果回执后自行回收；只有显式停止（stopCurrentRun）才取消运行。
        // onCreate 之前就被销毁（例如启动即失败）时这些对象还不存在，不能直接访问。
        if (::voiceController.isInitialized) voiceController.stop()
        removeWindow()
        // 服务被销毁而 run 还活着（例如被系统回收窗口）：同样把实时流交给主程序。
        handoffActiveRun()
        scope.cancel()
        cancellationExecutor.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        if (::overlayResultRegistry.isInitialized &&
            EtaAssistantOverlayPickerBridge.registry === overlayResultRegistry
        ) {
            EtaAssistantOverlayPickerBridge.registry = null
        }
        EtaAssistantOverlayPickerBridge.sessionEndListener = null
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    private fun showEntry() {
        if (!Settings.canDrawOverlays(this)) {
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_permission_missing") {
                "Eta assistant overlay permission is missing"
            }
            stopSelf()
            return
        }
        performWakeHaptic()
        // 主应用已在前台：不再叠一层弹窗——直接在主应用内响应（语音识别 → 聊天输入区听写；
        // 键盘 → 聚焦聊天输入框）。先收起不可见的系统会话（存活会话会经 onBackPressed 吞
        // 主应用 BACK），浮窗若在屏（如应用外唤起后切回主应用）也一并收起——run 挂在
        // Runtime 的独立 runScope 上，继续推进不受影响。
        if (MainActivity.isForeground) {
            EtaVoiceInteractionSession.requestHideForForegroundOperation(this)
            dismissAndStop()
            if (Prefs.assistantEntryMode() == AssistantEntryMode.VOICE && startVoiceEntry()) {
                MainActivity.requestInAppDictation()
            } else {
                MainActivity.requestInAppFocus()
            }
            return
        }
        // 任何唤醒都先退出选择器让位态（幂等）：让位中浮窗是隐身+整窗穿透的，
        // 唤醒意图表明用户要回到助手，恢复可视可触后走正常续进/重建分支。
        endPickerYield()
        // 唤起即听写的预热：配置里 3 个槽位是 AndroidKeyStore 解密，进程内首次成本可观。
        // 放到截图采集窗口内（IO 线程）先跑，首帧组合里听写 start() 的同步 load 就是纯缓存命中，
        // 不给上浮弹簧的起步帧添主线程负担。
        scope.launch { DoubaoVoiceConfig.load(applicationContext) }
        // 唤起方式=语音识别：本次唤起要让听写开录/重开。令牌 ++ 后——面板在屏时经重组
        // 触发重开，面板重建时随首次组合触发；start() 幂等（在录时空操作）。
        // 麦克风权限缺失时 toast 并整体退回键盘路径；识别方式未配置由听写条原位提示。
        val voiceWake = Prefs.assistantEntryMode() == AssistantEntryMode.VOICE && startVoiceEntry()
        // 浮窗已不再承载语音对话 UI（入口恒为口述）：残留的浮窗实时通话只会隐形占着
        // 进程级麦克风互斥锁（DoubaoDuplexSession 全会话持锁），开录前一律停掉。
        // 空闲态 stop() 是空操作，代价为零。
        voiceController.stop()
        if (voiceWake) dictationStartRequest++
        // 面板已在屏（未在退场、未在前台操作让位中）：重复唤起不重建窗口——
        // removeWindow + addView 会重建 Compose 组合、把入场弹簧从头再播一遍。
        // 顺着当前状态继续：语音识别重开听写（上面令牌已 ++），键盘则重新聚焦。
        if (windowView != null && !exitAnimationRequested && !hiddenForForegroundOperation) {
            if (voiceWake) {
                // 上一段键盘会话可能还留着 IME：开录前收掉，别让它盖住听写条。
                updateSoftInput(visible = false)
            } else {
                showKeyboard()
            }
            return
        }
        // 入口采集尚未结束：忽略紧随其后的重复 SHOW（采集窗口本就短暂）。
        if (entryCaptureJob != null) return
        // 有在途运行：再次唤起只把窗口挂回当前运行的流式状态，绝不取消运行。
        // 关窗与前台操作让位都只是收起 UI，运行归属在 Runtime 的 runScope；
        // 只有用户显式「停止」（[stopCurrentRun]）才允许取消。
        if (activeRunId != null) {
            hiddenForForegroundOperation = false
            exitAnimationRequested = false
            presentEntry(++entryGeneration, voiceWake)
            return
        }
        entryCaptureJob?.cancel()
        removeWindow()
        val generation = ++entryGeneration
        presentedEntryGeneration = -1L
        screenContextAttachment = null
        composerTextFieldState.setTextAndPlaceCursorAtEnd("")
        composerState.clearAttachments()
        uiState = EtaVoiceUiState(
            screenContext = EtaScreenContextUiState(
                phase = EtaScreenContextPhase.CAPTURING,
            ),
        )
        hiddenForForegroundOperation = false
        exitAnimationRequested = false
        val accessibility = AgentAccessibilityService.current()
        if (accessibility == null) {
            uiState = uiState.copy(
                screenContext = EtaScreenContextUiState(
                    phase = EtaScreenContextPhase.UNAVAILABLE,
                ),
            )
            presentEntry(generation, voiceWake)
            return
        }
        entryCaptureJob = scope.launch {
            val result = accessibility.captureScreenshotExcludingOverlays(
                onWindowsSubmitted = {
                    scope.launch(Dispatchers.Main.immediate) {
                        presentEntry(generation, voiceWake)
                    }
                },
            )
            val bitmap = result.bitmap
            val attachment = if (bitmap == null || result.criticalWindowMissing) {
                null
            } else {
                try {
                    runCatching {
                        val image = AgentImageCodec.fromScreenContextBitmap(
                            bitmap,
                            source = SCREEN_CONTEXT_IMAGE_SOURCE,
                        )
                        val preview = AgentImageCodec.previewFromReference(
                            this@EtaAssistantOverlayService,
                            image,
                        ) ?: return@runCatching null
                        EtaScreenContextAttachment(
                            image = image,
                            previewDataUrl = preview.reference,
                        )
                    }.onFailure { throwable ->
                        AndroidAgentLogger.warn(
                            "Eta assistant entry screenshot encode failed: " +
                                "type=${throwable.javaClass.simpleName}"
                        )
                    }.getOrNull()
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != entryGeneration) return@withContext
                entryCaptureJob = null
                screenContextAttachment = attachment
                uiState = uiState.copy(
                    screenContext = if (attachment == null) {
                        EtaScreenContextUiState(phase = EtaScreenContextPhase.UNAVAILABLE)
                    } else {
                        EtaScreenContextUiState(
                            phase = EtaScreenContextPhase.AVAILABLE,
                            previewDataUrl = attachment.previewDataUrl,
                        )
                    },
                )
                presentEntry(generation, voiceWake)
            }
        }
    }

    private fun presentEntry(generation: Long, voiceWake: Boolean) {
        if (generation != entryGeneration || presentedEntryGeneration == generation) return
        presentedEntryGeneration = generation
        scope.launch {
            // 外观设置来自 DataStore，首帧是异步的。若先用 [AppearanceSettings] 默认值（浅色）
            // 挂窗，深色 / 自定义配色的用户会先看到一帧浅色胶囊，等设置回来再切成深色，
            // 看起来就是「弹一下白」。先取一次真实外观再建窗，保证第一帧就是最终配色；
            // 读取失败才退回默认值（宁可偶发闪白，也不能因为设置读不出来就唤不出浮窗）。
            // 解析（toSettings）留在 IO：唤起路径上不能拿主线程做反序列化。
            val appearance = try {
                AppearanceSettingsRepository.settings()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AndroidAgentLogger.warnThrottled("eta_assistant_overlay_appearance_failed") {
                    "Eta assistant overlay appearance load failed: ${error.javaClass.simpleName}"
                }
                AppearanceSettings()
            }
            withContext(Dispatchers.Main.immediate) {
                // 等待期间可能被重复 SHOW 重置或被关闭：过期的一代不再挂窗。
                if (generation != entryGeneration || exitAnimationRequested) return@withContext
                showWindow(appearance)
                if (windowView == null) {
                    stopSelf()
                    return@withContext
                }
                // 语音入口不弹键盘：两者同时出现会把系统 IME 的入场动画和语音条打架。
                if (voiceWake) return@withContext
                showKeyboard()
            }
        }
    }

    /**
     * 唤起方式=语音识别时的入口前置检查。
     *
     * 只查麦克风权限：缺失时提示并返回 false，由调用方退回键盘输入。识别本身由
     * 弹窗里 `Material3ComposerBody` 的听写会话在落座时自动开录（dictationStartRequest 令牌）；
     * 识别方式未配置不再走 toast——听写条会原位给出「去配置」提示（与聊天输入区同一套）。
     */
    private fun startVoiceEntry(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, R.string.assistant_entry_voice_permission_missing, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /**
     * 电源键唤起时的触感。系统可能在一轮唤起里回调多次（session 重建 / 重复 SHOW），
     * 500ms 内只震一次，避免连震。
     */
    private fun performWakeHaptic() {
        val now = SystemClock.uptimeMillis()
        if (now - lastWakeHapticAt < WAKE_HAPTIC_DEDUPE_MS) return
        lastWakeHapticAt = now
        AgentHapticFeedback.perform(this, AgentHapticFeedback.Type.ASSISTANT_WAKE)
    }

    private fun showWindow(initialAppearance: AppearanceSettings) {
        if (windowView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = createComposeView {
            // 首帧必须直接是用户配色：外观已在挂窗前读取一次，这里只负责后续变更同步。
            val appearance by AppearanceSettingsRepository.settingsFlow()
                .collectAsState(initial = initialAppearance)
            val voiceState by voiceController.state.collectAsState()
            AgentAppTheme(
                appearance = appearance,
                applyInterfaceScale = false,
            ) {
                CompositionLocalProvider(
                    // 系统选择器 / 权限请求要拿到注册表 owner：官方组件会用
                    // `rememberLauncherForActivityResult`，没有 owner 会直接抛异常。
                    LocalActivityResultRegistryOwner provides overlayResultRegistry,
                    // 口述未配置的「去配置」圆钮：跳到主程序语音设置页，浮层随跳转收起。
                    LocalOpenVoiceSettings provides ::openVoiceSettings,
                ) {
                    // 语音会话快照：把浮窗当前的流式状态与最新助手消息同步给
                    // [VoiceModeController]，「通用语音对话」说完后才能读到回复。
                    // 同一份快照也驱动回复自动朗读（ReplyReadAloudController）。
                    LaunchedEffect(uiState.messages, uiState.phase) {
                        val lastAssistant = uiState.messages
                            .filterIsInstance<AgentMessageUi>()
                            .lastOrNull()
                        val snapshot = VoiceChatSnapshot(
                            isStreaming = uiState.phase == EtaVoicePhase.PROCESSING,
                            lastAgentId = lastAssistant?.id,
                            lastAgentText = lastAssistant?.content.orEmpty(),
                            // 浮窗有独立的会话标识：与应用内会话靠门禁互斥，避免两边同时读。
                            conversationId = conversationKey,
                            runId = activeRunId.takeIf { uiState.phase == EtaVoicePhase.PROCESSING },
                        )
                        voiceController.updateChat(snapshot)
                        ReplyReadAloudController.updateChat(applicationContext, snapshot)
                    }
                    EtaVoicePanel(
                        state = uiState,
                        textFieldState = composerTextFieldState,
                        composer = composerState,
                        voiceState = voiceState,
                        dictationStartRequest = dictationStartRequest,
                        pickerYield = pickerYield,
                        onSystemPickerLaunch = ::beginPickerYield,
                        inputFocusRequestKey = inputFocusRequestKey,
                        onScreenContextSelect = ::selectScreenContext,
                        onScreenContextRemove = ::removeScreenContext,
                        onScreenContextPreset = ::submitScreenContextPreset,
                        onSubmit = { text -> submitInput(text) },
                        onStop = ::stopCurrentRun,
                        onClose = ::dismissAndStop,
                        exitRequested = exitAnimationRequested,
                        composerConversationId = conversationKey,
                        onStartVoiceMode = { mode -> voiceController.start(mode) },
                        onStopVoiceMode = { voiceController.stop() },
                        onQuestionDraftChanged = ::updateQuestionDraft,
                        onSubmitQuestionAnswer = ::submitQuestionAnswer,
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            dimAmount = 0f
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            // 只声明「键盘弹起时不重排窗口」。不要带 STATE_ALWAYS_VISIBLE / STATE_ALWAYS_HIDDEN：
            // 入口窗口在 Compose 输入框拿到焦点前没有 InputConnection，窗口级状态会让系统
            // 先弹一次空键盘再收回，等真正聚焦后又弹一次——就是唤起动画里的键盘闪现。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "EtaAssistantOverlay"
        }
        runCatching { wm.addView(view, params) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_add_failed") {
                "Eta assistant overlay addView failed: type=${throwable.javaClass.simpleName}"
            }
            return
        }
        windowManager = wm
        windowView = view
        windowParams = params
        registerSystemBackCallback(view)
        view.requestFocus()
    }

    private fun createComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusableInTouchMode = true
            setViewTreeLifecycleOwner(this@EtaAssistantOverlayService)
            setViewTreeSavedStateRegistryOwner(this@EtaAssistantOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent(content)
        }

    private fun registerSystemBackCallback(view: View) {
        unregisterSystemBackCallback()
        val dispatcher = view.findOnBackInvokedDispatcher()
        if (dispatcher == null) {
            AndroidAgentLogger.warn("Eta assistant overlay back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        backInvokedDispatcher = dispatcher
        backInvokedCallback = callback
    }

    private fun unregisterSystemBackCallback() {
        val dispatcher = backInvokedDispatcher
        val callback = backInvokedCallback
        backInvokedDispatcher = null
        backInvokedCallback = null
        if (dispatcher != null && callback != null) {
            dispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    private fun showKeyboard(status: EtaVoiceStatus = EtaVoiceStatus.InputRequest) {
        uiState = uiState.copy(
            phase = EtaVoicePhase.READY,
            status = status,
        )
        // 只发焦点请求，键盘由面板在输入框真正拿到焦点后统一唤出（见 EtaVoicePanel）。
        // 在这里直接改窗口 softInputMode 会先于 InputConnection 弹出一次空键盘。
        inputFocusRequestKey++
    }

    private fun submitInput(prompt: String) {
        // 生成中：与应用内输入区一致。输入框保持可编辑，尾部按钮回到「发送」，
        // 此时提交的内容作为追加指令（steering）送进当前 run，而不是被丢弃。
        val runId = activeRunId
        if (runId != null) {
            steerInput(runId, prompt.trim())
            return
        }
        val normalized = prompt.trim()
        if (normalized.isBlank()) return
        // 唤起即屏幕上下文：指令明示要看屏幕内容且入口截图就绪时自动选中——预览卡弹出即
        // 功能触发的可见反馈，消息附带入口截图，首轮模型直接看到屏幕（省一次工具往返）。
        // AgentScreenIntent 只在弹窗链路使用；主程序对话不做该判断（硬边界，用户定稿）。
        if (!uiState.screenContext.selected &&
            uiState.screenContext.phase == EtaScreenContextPhase.AVAILABLE &&
            screenContextAttachment != null &&
            AgentScreenIntent.matches(normalized)
        ) {
            uiState = uiState.copy(
                screenContext = EtaScreenContextStateReducer.select(
                    state = uiState.screenContext,
                    enabled = true,
                    hasAttachment = true,
                ),
            )
        }
        submitPrompt(normalized)
    }

    /**
     * 生成中追加指令：与应用内输入区同一条路径 —— 附件先落盘，正文与引用按同一套编码随
     * `steerRun` 送给运行中的 run（未落盘的 base64 附件运行时无法识别，会整单退回）。
     *
     * 草稿只在真正送达后才清空：落盘失败 / 未送达时正文与附件原样保留，用户可直接重试。
     */
    private fun steerInput(runId: String, prompt: String) {
        val images = composerState.pendingImages
        val pendingReferences = composerState.pendingFileReferences
        if (prompt.isBlank() && images.isEmpty() && pendingReferences.isEmpty()) return
        val references = pendingReferences.map { it.reference }
        val requestId = UUID.randomUUID().toString()
        scope.launch {
            try {
                // 保持位置、整单退回：落盘失败时不能悄悄把某张图挪到别的位置。
                val staged = images.mapIndexed { index, image ->
                    if (image.isVideo) {
                        val file = java.io.File(image.uri.removePrefix("file://"))
                        if (!file.isFile) return@mapIndexed null
                        chatImageCache.stageFromFile(
                            conversationKey,
                            file,
                            image.cacheDisplayName(index),
                            MAX_AGENT_VIDEO_BYTES,
                        )
                    } else {
                        val bytes = AgentChatImageCache.readBytes(image.uri)
                            ?: AgentChatImageCache.readBytes(image.dataUrl)
                            ?: return@mapIndexed null
                        chatImageCache.stage(conversationKey, bytes, image.cacheDisplayName(index))
                    }
                }
                if (staged.size != images.size || staged.any { it == null }) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.appst_steer_attachment_save_failed))
                    }
                    return@launch
                }
                val stagedReferences = staged.filterNotNull()
                val previews = images.mapIndexed { index, image ->
                    if (image.isVideo) {
                        // 视频另存一张预览图：运行时要的是可解码的缩略图，不是视频本体。
                        val bytes = AgentChatImageCache.readBytes(image.dataUrl)
                        val preview = bytes?.let {
                            chatImageCache.stage(conversationKey, it, "preview-${image.id}.jpg")
                        }
                        preview?.absolutePath ?: image.dataUrl
                    } else {
                        stagedReferences[index].absolutePath
                    }
                }
                val imagesJson = encodeUserMessageImages(
                    previews,
                    stagedReferences.map { it.absolutePath },
                    images.map { it.isVideo },
                    images.map { it.durationMs },
                )
                // 附件描述过大时不能塞进 Binder。
                if (imagesJson.length > 64_000) {
                    withContext(Dispatchers.Main) {
                        toast(getString(R.string.appst_steer_preview_save_failed))
                    }
                    return@launch
                }
                val text = AgentFileReferencePromptCodec.format(prompt, references + stagedReferences)
                    .ifBlank { getString(R.string.appst_steer_attachment_fallback) }
                val sent = runtimeClient.steerRun(runId, text, requestId, imagesJson)
                withContext(Dispatchers.Main) {
                    if (!sent) {
                        toast(getString(R.string.appst_steer_not_delivered))
                        return@withContext
                    }
                    // 只有真正送达才清空草稿：正文若已被继续编辑则保留，附件按 id 精确移除
                    // （中途新加的附件不会被误删）。
                    if (composerTextFieldState.text.toString() == prompt) {
                        composerTextFieldState.setTextAndPlaceCursorAtEnd("")
                    }
                    images.forEach { composerState.removePendingImage(it.id) }
                    pendingReferences.forEach { composerState.removePendingFileReference(it.id) }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                withContext(Dispatchers.Main) {
                    toast(getString(R.string.appst_steer_failed))
                }
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun submitPrompt(prompt: String, toolPreset: String = "") {
        val normalized = prompt.trim()
        if (normalized.isBlank() || activeRunId != null) return
        val attachment = screenContextAttachment.takeIf { uiState.screenContext.selected }
        // 待发附件先在 Main 快照，真正编码放到 runJob 的 IO 线程：附件要从原始引用重编码成
        // 「尽量无损」档，不能在主线程上解码大图。屏幕上下文图片本就是原生无损档，原样使用。
        val pendingForRun = composerState.pendingImages
        val supportsVideo = composerState.selectedModel?.supportsVideo == true
        val previewImages = attachment?.let { listOf(it.previewDataUrl) }.orEmpty() +
            pendingForRun.map { it.dataUrl }
        // 文件 / 文件夹引用按应用内同一编码写进用户消息（正文 + 引用信封）。
        val fileReferences = composerState.pendingFileReferences.map { it.reference }
        val runtimePrompt = AgentFileReferencePromptCodec.format(normalized, fileReferences)
        screenContextAttachment = null
        composerTextFieldState.setTextAndPlaceCursorAtEnd("")
        composerState.clearAttachments()
        activeRunId = UUID.randomUUID().toString()
        val runId = activeRunId ?: return
        uiState = uiState.copy(
            phase = EtaVoicePhase.PROCESSING,
            status = EtaVoiceStatus.Reasoning,
            screenContext = EtaScreenContextStateReducer.consume(),
            messages = uiState.messages + UserMessageUi(
                id = "user-$runId",
                content = runtimePrompt,
                images = previewImages,
            ),
        )
        updateSoftInput(visible = false)
        // 本次运行使用独立作用域：关闭浮窗后 Service 的 scope 会被取消，但对话必须继续。
        val runScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        this.runScope = runScope
        val job = runScope.launch {
            // 屏幕上下文图片在前，用户附件图片 / 视频在后，与应用内发送路径的顺序一致。
            val runImages = attachment?.let { listOf(it.image) }.orEmpty() +
                withContext(Dispatchers.IO) {
                    pendingForRun.map { it.toRequestModelImage(this@EtaAssistantOverlayService, supportsVideo) }
                }
            // 输入区携带的模型 / 助手 / 思考档位 / 工具开关 → 运行时配置；
            // 取不到（理论上不会）时退回默认配置。
            val config = composerState.buildRunConfig() ?: AgentModelClient.loadConfig()
            val payload = AgentExternalArchivePayload(
                userText = runtimePrompt,
                conversationKey = conversationKey,
                title = normalized.take(40),
                // 归档会话回放时靠这两个字段还原本次 run 的思考档位；缺了会回退到
                // 全局布尔推导的 legacy 默认档，把 HIGH/LOW 等具体档位显示错。
                thinkingEnabled = config.thinkingEnabled,
                reasoningEffort = config.effectiveReasoningEffort,
            )
            // 关闭浮窗后要把这一单交给主程序：先备好交接信息（runId + 首轮输入 + 展示档位），
            // 终态时清掉，避免把已完成的单当成「还在跑」交给应用内。
            activeRunHandoff = AgentLiveRunHandoff.Notice(
                runId = runId,
                source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                payload = payload,
                userImagePreviews = previewImages,
                createdAt = System.currentTimeMillis(),
            )
            val result = runtimeClient.run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    // 与应用内请求对齐：同一套 turn/session 标识、助手、历史状态与上下文计量。
                    // 语音没有云端回执，calibratedInputTokens 保持 null，等价于应用内「无回执」
                    // 分支；allowUnmeasuredContextSend 同样按该分支置 true。
                    turnId = runId,
                    assistantId = config.assistantId,
                    prompt = runtimePrompt,
                    config = config,
                    images = runImages,
                    history = conversationHistory,
                    historyAlreadyCompacted = true,
                    modelSessionId = conversationKey,
                    allowUnmeasuredContextSend = true,
                    // 空串 = 普通对话；快捷功能预设由 Runtime 选择目录构建策略。
                    toolPreset = toolPreset,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = "$conversationKey:$runId",
                        source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        payload = payload.toJson(),
                        dismissEntrySurfaceOnForegroundOperation = true,
                    ),
                ),
                onEvent = { event -> handleRuntimeEvent(runId, event) },
            )
            val shouldStopAfterResult = withContext(Dispatchers.Main.immediate) {
                if (activeRunId != runId) return@withContext false
                activeRunId = null
                runJob = null
                // 已在浮窗里跑完：没有可交接的实时流了，结果由归档进应用内历史。
                if (activeRunHandoff?.runId == runId) activeRunHandoff = null
                if (result.ok) {
                    conversationHistory = conversationHistory +
                        AgentModelClient.buildUserHistoryMessage(runtimePrompt, runImages) +
                        result.transcript
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.READY,
                        status = EtaVoiceStatus.Completed,
                        messages = finishRunMessages(runId, result),
                    )
                } else {
                    uiState = uiState.copy(
                        phase = EtaVoicePhase.ERROR,
                        status = EtaVoiceStatus.Failed(null),
                        messages = finishRunMessages(runId, result),
                    )
                }
                if (!hiddenForForegroundOperation) {
                    updateSoftInput(visible = false)
                }
                hiddenForForegroundOperation
            }
            runtimeClient.ackResult(runId)
            if (shouldStopAfterResult) {
                withContext(Dispatchers.Main.immediate) {
                    if (activeRunId == null) {
                        removeWindow()
                        stopSelf()
                    }
                }
            }
        }
        runJob = job
        job.invokeOnCompletion {
            // 运行结束（无论浮窗是否已经关闭）都回收本次运行独占的作用域。
            if (this@EtaAssistantOverlayService.runScope === runScope) {
                this@EtaAssistantOverlayService.runScope = null
            }
            runScope.cancel()
        }
    }

    private fun updateQuestionDraft(conversationId: String, questionId: String, draft: AgentQuestionAnswer) {
        val question = uiState.messages.filterIsInstance<AgentQuestionMessageUi>().singleOrNull {
            it.request.conversationId == conversationId && it.request.questionId == questionId
        } ?: return
        if (question.request.runId != activeRunId || question.status != AgentQuestionStatus.Waiting || question.submitting) return
        val kind = when {
            draft.kind == "option" -> "option"
            draft.kind == "other" && question.request.allowOther -> "other"
            draft.kind == "delegate" && question.request.allowDelegation -> "delegate"
            else -> return
        }
        val updated = question.copy(answerKind = kind,
            selectedOptionId = draft.optionId?.takeIf { id -> question.request.options.any { it.id == id } },
            otherText = draft.otherText.take(2000), note = if (question.request.allowNote) draft.note.take(2000) else "", error = null)
        uiState = uiState.copy(messages = uiState.messages.map { if (it.id == question.id) updated else it })
    }

    private fun submitQuestionAnswer(conversationId: String, questionId: String) {
        val question = uiState.messages.filterIsInstance<AgentQuestionMessageUi>().singleOrNull {
            it.request.conversationId == conversationId && it.request.questionId == questionId
        } ?: return
        if (question.request.runId != activeRunId || question.status != AgentQuestionStatus.Waiting || question.submitting) return
        val answer = AgentQuestionProjection.draftAnswer(question)
        val validation = AgentQuestionCodec.validateAnswer(question.request, answer)
        if (!validation.accepted) {
            uiState = uiState.copy(messages = uiState.messages.map {
                if (it.id == question.id) question.copy(error = validation.message.ifBlank { validation.code }) else it
            })
            return
        }
        uiState = uiState.copy(messages = uiState.messages.map {
            if (it.id == question.id) question.copy(submitting = true, error = null) else it
        })
        scope.launch(Dispatchers.Main.immediate) {
            val owner = question.request
            val receipt = withContext(Dispatchers.IO) { runtimeClient.submitQuestionAnswer(
                owner.conversationId, owner.runId, owner.questionId, owner.toolCallId, answer) }
            uiState = uiState.copy(messages = uiState.messages.map { m -> if (m is AgentQuestionMessageUi &&
                AgentQuestionProjection.sameOwner(m.request, owner)) AgentQuestionProjection.acknowledged(m, answer, receipt) else m })
            if (receipt.accepted) kotlinx.coroutines.delay(1_000)
            // Terminal projection can close the card before the ACK; still query its exact
            // owner once unless already Answered. The read corrects missed answers, not liveness.
            if (AgentQuestionProjection.needsAuthoritativeQuery(uiState.messages, owner)) {
                val snapshot = withContext(Dispatchers.IO) { runtimeClient.queryQuestion(owner.conversationId,
                    owner.runId, owner.questionId, owner.toolCallId) }
                uiState = uiState.copy(messages = AgentQuestionProjection.reconcileMessages(
                    uiState.messages, owner, snapshot))
            }
        }
    }

    private fun handleRuntimeEvent(runId: String, event: AgentEvent) {
        scope.launch(Dispatchers.Main.immediate) {
            if (activeRunId != runId) return@launch
            if (AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)) {
                hideForForegroundOperation()
            }
            uiState = projectRuntimeEvent(runId, event, uiState)
        }
    }

    private fun projectRuntimeEvent(
        runId: String,
        event: AgentEvent,
        state: EtaVoiceUiState,
    ): EtaVoiceUiState {
        var messages = state.messages
        var status = state.status
        var phase = state.phase
        when (event) {
            // 派发落定与语音表面无关：语音入口不经调度，忽略即可。
            is AgentEvent.DispatchCreated -> Unit
            is AgentEvent.QuestionRequested -> {
                messages = runMessageProjector.requestQuestion(event.request.conversationId, runId, event, messages)
                status = EtaVoiceStatus.WaitingForAnswer
                updateSoftInput(visible = true)
            }
            is AgentEvent.QuestionResolved -> {
                val owner = messages.filterIsInstance<AgentQuestionMessageUi>().singleOrNull {
                    it.request.runId == runId && it.request.questionId == event.questionId
                }?.request?.conversationId
                if (owner != null) messages = runMessageProjector.resolveQuestion(owner, runId, event, messages)
                status = EtaVoiceStatus.Reasoning
            }
            is AgentEvent.AssistantBlockStart -> {
                messages = runMessageProjector.startAssistantBlock(runId, event, messages)
            }

            is AgentEvent.AssistantBlockDelta -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.appendTextDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.appendReasoningDelta(
                            runId,
                            event.round,
                            event.index,
                            event.delta,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                messages = when (event.kind) {
                    AgentEvent.AssistantBlockKind.TEXT ->
                        runMessageProjector.finalizeTextBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.THINKING ->
                        runMessageProjector.finalizeThinkingBlock(
                            runId,
                            event.round,
                            event.index,
                            event.replacementContent,
                            messages,
                        )

                    AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                }
            }

            is AgentEvent.UsageReceived -> {
                if (!event.projected) {
                    val assistantPrefix = "assistant-$runId-${event.round}"
                    val usage = TokenUsageUi(
                        contextTokens = event.usage.contextTokens,
                        inputTokens = event.usage.inputTokens,
                        outputTokens = event.usage.outputTokens,
                        reasoningTokens = event.usage.reasoningTokens,
                        cachedTokens = event.usage.cachedTokens,
                    )
                    val targetIndex = messages.indexOfLast { message ->
                        message is AgentMessageUi &&
                            (message.id == assistantPrefix || message.id.startsWith("$assistantPrefix-"))
                    }
                    messages = messages.mapIndexed { index, message ->
                        if (index == targetIndex && message is AgentMessageUi) {
                            message.copy(usage = usage)
                        } else {
                            message
                        }
                    }
                }
            }

            is AgentEvent.UserSupplementReceived -> {
                val id = "user-$runId-supplement-${event.index}"
                if (messages.none { it.id == id }) {
                    messages = messages + UserMessageUi(id = id, content = event.text)
                }
            }

            is AgentEvent.ToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.ToolFinished -> {
                messages = runMessageProjector.finishTool(runId, event, messages)
            }

            is AgentEvent.HostedToolStarted -> {
                status = EtaVoiceStatus.RunningTool(event.name)
                messages = runMessageProjector.startHostedTool(
                    runId,
                    event,
                    runMessageProjector.finalizeTextRound(
                        runId,
                        event.round,
                        runMessageProjector.finalizeThinkingRound(runId, event.round, messages),
                    ),
                )
            }

            is AgentEvent.HostedToolFinished -> {
                messages = runMessageProjector.finishHostedTool(runId, event, messages)
            }

            is AgentEvent.ErrorReconnectChanged -> {
                messages = runMessageProjector.reconnectChanged(runId, event, messages)
                status = EtaVoiceStatus.Reasoning
            }

            is AgentEvent.RunFailed -> {
                phase = EtaVoicePhase.ERROR
                status = EtaVoiceStatus.Failed(null)
                messages = runMessageProjector.terminalFailure(runId, event.reason,
                    runMessageProjector.failRunningTools(event.reason,
                        runMessageProjector.finalizeText(runId,
                            runMessageProjector.finalizeThinking(runId, messages))))
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    messages = runMessageProjector.ensureCompletedThinking(
                        runId = runId,
                        round = event.round,
                        content = event.reasoningContent,
                        messages = messages,
                    )
                }
            }

            is AgentEvent.RunFinished -> {
                messages = runMessageProjector.finalizeText(
                    runId,
                    runMessageProjector.finalizeThinking(runId, messages),
                )
            }

            is AgentEvent.ModelRetryScheduled -> {
                messages = runMessageProjector.scheduleModelRetry(runId, event, messages)
                status = EtaVoiceStatus.Reasoning
            }
            is AgentEvent.ProviderRequestStarted -> status = EtaVoiceStatus.Reasoning
            is AgentEvent.ChildContextUpdated,
            is AgentEvent.GuiControlChanged,
            is AgentEvent.DecisionFallback,
            is AgentEvent.RunStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            is AgentEvent.AutoCompactWaiting,
            is AgentEvent.ContextCompactionStarted,
            is AgentEvent.ContextCompacted,
            -> Unit
        }
        return state.copy(messages = messages, phase = phase, status = status)
    }

    private fun finishRunMessages(
        runId: String,
        result: AgentRuntimeWire.RunResult,
    ): List<AgentChatMessageUi> {
        var messages = runMessageProjector.finalizeText(
            runId,
            runMessageProjector.finalizeThinking(runId, uiState.messages),
        )
        if (!result.ok) {
            messages = runMessageProjector.failRunningTools(
                result.error ?: SYNTHETIC_RUNTIME_FAILED,
                messages,
            )
        }
        if (!result.ok) {
            messages = if (result.error == LEGACY_STOPPED_ERROR) {
                runMessageProjector.runStopped(runId, messages) +
                    SystemNoticeMessageUi("interrupted-$runId", SystemNoticeCode.Stopped)
            } else runMessageProjector.terminalFailure(runId,
                result.error ?: SYNTHETIC_RUNTIME_FAILED, messages)
            runMessageProjector.clearRun(runId)
            return messages
        }
        val notice = when {
            result.ok && result.content.isBlank() -> SystemNoticeCode.EmptyResult
            !result.ok && result.error == LEGACY_STOPPED_ERROR -> SystemNoticeCode.Stopped
            !result.ok -> SystemNoticeCode.RuntimeFailed
            else -> null
        }
        val lastAssistantIndex = AgentRunMessageProjector.resultTargetIndex(runId, messages)
        val resultTail = AgentRunMessageProjector.completedResultTail(runId, messages, lastAssistantIndex, result.content)
        messages = if (lastAssistantIndex >= 0) {
            val targetRound = (messages[lastAssistantIndex] as AgentMessageUi).id
                .assistantRound(runId)
            val sameRoundBlocks = targetRound?.let { round ->
                messages.count { message ->
                    message is AgentMessageUi && message.id.assistantRound(runId) == round
                }
            } ?: 0
            messages.mapIndexed { index, message ->
                if (index == lastAssistantIndex && message is AgentMessageUi) {
                    if (notice == null) {
                        message.copy(
                            content = if (sameRoundBlocks <= 1) {
                                resultTail
                            } else {
                                message.content.ifBlank { resultTail }
                            },
                            isStreaming = false,
                            renderMarkdown = true,
                        )
                    } else {
                        SystemNoticeMessageUi(
                            id = message.id,
                            code = notice,
                            detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                        )
                    }
                } else {
                    message
                }
            }
        } else {
            if (notice == null) {
                messages + AgentMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    content = resultTail,
                    isStreaming = false,
                    renderMarkdown = true,
                )
            } else {
                messages + SystemNoticeMessageUi(
                    id = AgentRunMessageProjector.resultFallbackId(runId, messages),
                    code = notice,
                    detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                )
            }
        }
        messages = runMessageProjector.finalizeRun(runId, messages)
        runMessageProjector.clearRun(runId)
        return messages
    }

    private fun String.assistantRound(runId: String): Int? {
        val prefix = "assistant-$runId-"
        return removePrefix(prefix)
            .takeIf { it != this }
            ?.substringBefore('-')
            ?.toIntOrNull()
    }

    private fun stopCurrentRun() {
        val runId = activeRunId
        if (runId != null) {
            activeRunId = null
            requestRuntimeCancellation(runId)
            runJob?.cancel()
            runJob = null
            runScope?.cancel()
            runScope = null
            uiState = uiState.copy(
                phase = EtaVoicePhase.READY,
                status = EtaVoiceStatus.Stopped,
                messages = runMessageProjector.runStopped(runId,
                    runMessageProjector.failRunningTools(SYNTHETIC_STOPPED, uiState.messages)),
            )
            uiState = uiState.copy(messages = runMessageProjector.finalizeRun(runId, uiState.messages))
            runMessageProjector.clearRun(runId)
            updateSoftInput(visible = true)
            inputFocusRequestKey++
        } else {
            dismissAndStop()
        }
    }

    private fun requestRuntimeCancellation(runId: String) {
        runCatching {
            cancellationExecutor.execute { runtimeClient.cancelRun(runId) }
        }
    }

    /**
     * 显式显示 / 隐藏输入法，不再改写窗口的 `softInputMode`。
     *
     * 窗口级 `STATE_ALWAYS_VISIBLE` 会在 Compose 输入框还没有 InputConnection 时先弹一次
     * 键盘，系统随后发现没有输入目标又收回，造成唤起动画里的「键盘闪现」。入口的键盘
     * 统一由 [EtaVoicePanel] 在聚焦后唤出；这里只服务生成中 / 结束后 / 提问卡片这些
     * 窗口已经存在、焦点已建立的场景。
     */
    private fun updateSoftInput(visible: Boolean) {
        val view = windowView ?: return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        val controller = ViewCompat.getWindowInsetsController(view)
        if (visible) {
            imm?.showSoftInput(view.findFocus() ?: view, 0)
            controller?.show(WindowInsetsCompat.Type.ime())
        } else {
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
            controller?.hide(WindowInsetsCompat.Type.ime())
        }
    }

    /**
     * 选择器让位：附件/权限的跳板把系统选择器 Activity 打到浮窗之下，而浮窗是全屏
     * TYPE_APPLICATION_OVERLAY——不处理的话选择器永远被盖住，点它等于点浮窗空白处
     * （误关弹窗）。这里让浮窗**隐身且整窗放行触摸**：内容 alpha 归零（组合保持存活，
     * launcher 注册不丢、结果回灌管线完好）+ FLAG_NOT_TOUCHABLE（触摸穿透到选择器）。
     *
     * 不走拆窗让位（hideForForegroundOperation）：拆窗即销毁组合、注销结果注册，
     * 选择结果会无人接收。会话结束由跳板 [EtaAssistantOverlayPickerBridge.sessionEndListener]
     * 信号触发恢复；唤醒（[showEntry]）也兜底复位。
     */
    private fun beginPickerYield() {
        if (pickerYield) return
        pickerYield = true
        setWindowTouchable(false)
        EtaAssistantOverlayPickerBridge.sessionEndListener = ::endPickerYield
    }

    /** 从选择器让位态恢复（幂等）：恢复可视与触摸，摘除会话结束信号。 */
    private fun endPickerYield() {
        if (!pickerYield) return
        pickerYield = false
        setWindowTouchable(true)
        EtaAssistantOverlayPickerBridge.sessionEndListener = null
    }

    /** 整窗触摸放行开关：让位时加 FLAG_NOT_TOUCHABLE，恢复时去除。 */
    private fun setWindowTouchable(touchable: Boolean) {
        val view = windowView ?: return
        val params = windowParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager?.updateViewLayout(view, params) }
    }

    private fun selectScreenContext() {
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.select(
                state = uiState.screenContext,
                enabled = activeRunId == null,
                hasAttachment = screenContextAttachment != null,
            ),
        )
    }

    /**
     * 屏幕快捷预设：一次点击就等于「带上当前屏幕截图 + 发送这句预设」，
     * 省掉先点「添加当前屏幕」再打字的两步。
     *
     * 预设 chip 在屏幕未采好时是禁用的；这里再兜一次底，避免竞态下不带图就把
     * 「翻译当前屏幕」这类指令发出去。
     */
    private fun submitScreenContextPreset(preset: ScreenContextPreset) {
        if (activeRunId != null) return
        if (uiState.screenContext.phase != EtaScreenContextPhase.AVAILABLE ||
            screenContextAttachment == null
        ) {
            toast(getString(R.string.voice_screen_unavailable))
            return
        }
        // 语音模式里预设同样常驻：先退出语音（含听写与播报），再把这句话连同屏幕截图发出去，
        // 否则麦克风 / 播报会和这一轮 run 抢同一段音频。
        voiceController.stop()
        // submitPrompt 按 selected 决定是否带图；预设本身就是带屏幕提问，直接选中再发。
        uiState = uiState.copy(
            screenContext = uiState.screenContext.copy(selected = true),
        )
        submitPrompt(getString(preset.promptRes), toolPreset = preset.toolPreset)
    }

    private fun removeScreenContext() {
        uiState = uiState.copy(
            screenContext = EtaScreenContextStateReducer.remove(
                state = uiState.screenContext,
                enabled = activeRunId == null,
            ),
        )
    }

    private fun hideForForegroundOperation(onComplete: ((Boolean) -> Unit)? = null) {
        hiddenForForegroundOperation = true
        // 浮窗这一去不再回来（见 shouldStopAfterResult）：run 的后续输出不能只留在看不见的
        // uiState 里，交给主程序接着写。
        handoffActiveRun()
        EtaVoiceInteractionSession.requestHideForForegroundOperation(this)
        removeWindow(onComplete)
    }

    /**
     * 把仍在跑的 run 交给主程序（一次性）。
     *
     * 浮窗消失（用户关闭 / 让位前台操作 / 服务被销毁）时调用；run 已终态则什么都不做——
     * 那种情况结果由 Runtime 归档，走归档导入路径。
     */
    private fun handoffActiveRun() {
        val handoff = activeRunHandoff?.takeIf { it.runId == activeRunId } ?: return
        activeRunHandoff = null
        AgentLiveRunHandoff.publish(handoff)
    }

    private fun removeWindow(onComplete: ((Boolean) -> Unit)? = null) {
        // 浮窗是语音条唯一的可见承载：任何一条拆窗路径（用户关闭 dismissAndStop / 让位前台
        // 操作 hideForForegroundOperation / 重复唤起重整入口 showEntry / 服务销毁 onDestroy）
        // 都必须连带结束语音。否则浮窗不可见后麦克风继续被占用，且没有任何 UI 能停掉它——
        // hideForForegroundOperation 只拆窗不 stopSelf，服务继续存活，是最典型的漏停路径。
        // stop() 幂等，onDestroy 等处已有的显式停止不受影响。
        if (::voiceController.isInitialized) voiceController.stop()
        unregisterSystemBackCallback()
        detachingWindowView?.let { detachingView ->
            onComplete?.let(windowDetachCallbacks::add)
            if (!detachingView.isAttachedToWindow) {
                finishWindowDetach(success = true)
            }
            return
        }

        val view = windowView
        val wm = windowManager
        if (view == null || wm == null || !view.isAttachedToWindow) {
            windowView = null
            windowParams = null
            windowManager = null
            onComplete?.invoke(true)
            return
        }

        onComplete?.let(windowDetachCallbacks::add)
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit

            override fun onViewDetachedFromWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                finishWindowDetach(success = true)
            }
        }
        detachingWindowView = view
        view.addOnAttachStateChangeListener(attachListener)

        val removed = runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
            wm.removeView(view)
            true
        }.getOrElse { throwable ->
            view.removeOnAttachStateChangeListener(attachListener)
            AndroidAgentLogger.warnThrottled("eta_assistant_overlay_remove_failed") {
                "Eta assistant overlay removeView failed: type=${throwable.javaClass.simpleName}"
            }
            false
        }
        if (!removed) {
            finishWindowDetach(success = false)
            return
        }

        windowView = null
        windowParams = null
        windowManager = null
        if (!view.isAttachedToWindow) {
            view.removeOnAttachStateChangeListener(attachListener)
            finishWindowDetach(success = true)
        }
    }

    private fun finishWindowDetach(success: Boolean) {
        detachingWindowView = null
        val callbacks = windowDetachCallbacks.toList()
        windowDetachCallbacks.clear()
        callbacks.forEach { callback -> callback(success) }
    }

    private fun dismissAndStop() {
        // 先播放退场动画再移除浮窗：退场动画是入场的逆过程（普通非线性曲线）。
        if (exitAnimationRequested) return
        exitAnimationRequested = true
        entryGeneration++
        entryCaptureJob?.cancel()
        entryCaptureJob = null
        // 关闭浮窗只结束 UI 承载：把运行与 UI 解绑但不取消，对话继续在 Runtime 中推进；
        // 有在跑的单就把实时流交给主程序（attach → 事件投影 → 终态落库），
        // 用户接着打开应用能看到它正在写，而不是等终态后一次性冒出全过程。
        handoffActiveRun()
        activeRunId = null
        // 浮窗关闭即收声：自动朗读不跨浮窗生命周期继续。
        ReplyReadAloudController.stop()
        scope.launch(Dispatchers.Main.immediate) {
            delay(EXIT_ANIMATION_DURATION_MS)
            screenContextAttachment = null
            removeWindow()
            stopSelf()
        }
    }

    /**
     * 口述「去配置」：跳到主程序的语音设置页，浮层随跳转收起。
     * 后台启动放行取「浮窗可见时 ALLOW_IF_VISIBLE」；先发意图再收浮层。
     */
    private fun openVoiceSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_OPEN_VOICE_SETTINGS)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        val creatorOptions = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                pendingIntentCreatorBackgroundActivityStartMode =
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            VOICE_SETTINGS_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            creatorOptions.toBundle(),
        )
        val senderOptions = ActivityOptions.makeBasic().apply {
            pendingIntentBackgroundActivityStartMode =
                if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
        }
        runCatching { pendingIntent.send(senderOptions.toBundle()) }
        dismissAndStop()
    }

    internal companion object {
        const val ACTION_SHOW = "io.github.mangi.eta.agent.voice.SHOW"
        const val ACTION_OPEN_VOICE_SETTINGS = "io.github.mangi.eta.agent.voice.OPEN_VOICE_SETTINGS"

        /**
         * 移除浮窗前等待退场动画播完的时长，略大于 [EtaVoicePanel] 的退场动画时长
         * （[AssistantExitDurationMillis] = 220ms），保证动画不被截断。
         */
        private const val EXIT_ANIMATION_DURATION_MS = 240L
        private const val WAKE_HAPTIC_DEDUPE_MS = 500L

        private const val VOICE_SETTINGS_REQUEST_CODE = 0x455442
        private const val LEGACY_STOPPED_ERROR = "已停止"
        private const val SYNTHETIC_STOPPED = "eta_status:stopped"
        private const val SYNTHETIC_RUNTIME_FAILED = "eta_status:runtime_failed"
        private const val FOREGROUND_DISMISS_TIMEOUT_MS = 2_000L
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var activeService: EtaAssistantOverlayService? = null

        /**
         * Eta 自己拥有入口浮层，直接关闭并等待具体 View detach；不能按包名猜测，
         * 因为入口、Runtime 与结果浮层都属于同一个包。
         */
        fun dismissForForegroundOperation(context: Context): Boolean {
            val service = activeService
            if (service == null) {
                EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                return true
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                service.hideForForegroundOperation()
                return service.windowView == null && service.detachingWindowView == null
            }

            val completed = CountDownLatch(1)
            val dismissed = AtomicBoolean(false)
            mainHandler.post {
                val current = activeService
                if (current == null) {
                    EtaVoiceInteractionSession.requestHideForForegroundOperation(context)
                    dismissed.set(true)
                    completed.countDown()
                } else if (current !== service) {
                    completed.countDown()
                } else {
                    current.hideForForegroundOperation { success ->
                        dismissed.set(success)
                        completed.countDown()
                    }
                }
            }
            return try {
                completed.await(FOREGROUND_DISMISS_TIMEOUT_MS, TimeUnit.MILLISECONDS) &&
                    dismissed.get()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun show(context: Context) {
            context.applicationContext.startService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java)
                    .setAction(ACTION_SHOW),
            )
        }

        fun dismiss(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, EtaAssistantOverlayService::class.java),
            )
        }
    }
}

private data class EtaScreenContextAttachment(
    val image: AgentModelClient.ModelImage,
    val previewDataUrl: String,
)
