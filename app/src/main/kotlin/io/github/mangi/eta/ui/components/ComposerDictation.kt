package io.github.mangi.eta.ui.components

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.agent.voice.SpeechInputSession
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 输入区「口述」的单次听写会话。
 *
 * 与语音对话（`VoiceModeController`）的区别：听写只录一句话——说完停顿（识别自然结束）
 * 内容**直接发送**（[autoCommit] 一次性交给 UI，走发送钮同一条门禁；发不出去时退回
 * 「继续编辑」）；按 ✓（[beginCommit]）先停录、等识别引擎把尾段识别完，再经 [onCommitText]
 * 把最终文字同步交回输入框继续编辑（不发送），按 × 直接丢弃。识别引擎复用
 * 语音对话的配置（[VoiceEntryMode.UNIVERSAL] 的识别设置）：听写本身不再是独立入口
 * （见 `VoiceEntryPolicy`），而 [SpeechInputSession.recognize] 只看所选模式的识别开关
 * 与引擎配置。
 */
internal class ComposerDictation(private val context: Context) {

    /** 听写条是否在场：从开始一直保持到取消 / 确认 / 出错。 */
    var active by mutableStateOf(false)
        private set

    /** 麦克风是否已经开录（连接与初始化阶段为 false）。 */
    var listening by mutableStateOf(false)
        private set

    /** 实时（累计）转写；识别结束前一直增长。 */
    var transcript by mutableStateOf("")
        private set

    /**
     * ✓ 触发的收尾中：麦克风已请求停录，识别引擎正在刷尾段、组织最终文字。
     * UI 侧 ✓ 钮随本标记原地 morph 成 40dp 正圆加载态、缩进流光胶囊内部右侧同心位
     * （见 M3DictationMorphButton）；
     * 收尾完成（或超时/出错）后随 [active] 一起翻 false。
     */
    var finalizing by mutableStateOf(false)
        private set

    /**
     * ✓ 收尾完成后的交付回调：识别收尾一结束就在主线程同步调用（落进输入框，不发送）。
     *
     * 必须同步而不能走一次性状态 + effect：active 翻 false 与落字若不同帧，尾部槽会先
     * 退回 mic 圆钮、下一帧才切 Send（「hasContent」晚一帧到位），交接闪一下麦克风；
     * 同帧落字让尾部槽直接从收尾圆 morph 到 Send。
     */
    var onCommitText: ((String) -> Unit)? = null

    /** 结束原因，供 UI 弹出一次性提示后清空（[consumeOutcome]）。正常取消 / 确认不产生结果。 */
    var outcome by mutableStateOf<ComposerDictationOutcome?>(null)
        private set

    /**
     * 自然说完（未按 ✓ / ×，识别自己结束）产生的最终转写：UI 一次性消费（[consumeAutoCommit]）
     * 后清空。这里只负责把内容交出去——发送与否由 UI 按发送钮同款门禁决定，门禁不通时
     * 退回「继续编辑」路径。
     */
    var autoCommit by mutableStateOf<String?>(null)
        private set

    /**
     * 未配置识别引擎时的失败提示。
     *
     * 与旧语音入口同一套呈现：不在识别内部抛异常、也不弹 toast，而是在输入区原位展示
     * [NotConfiguredMessage]（与 VoiceModeController 未配置时的文案一致），右侧圆钮直达语音设置。
     */
    var notice by mutableStateOf<String?>(null)
        private set

    /**
     * 麦克风瞬时音量（0..1）。音频线程每 10ms 写一次，流光画布逐帧读取并自行平滑——
     * 不走 Compose 状态，避免每个音频帧都触发整棵听写条重组。麦克风关闭时归零。
     */
    val micLevel = VoiceMicLevel()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** ✓ 的停录信号：complete 后识别停止采集、刷尾段拿最终结果（而不是取消丢尾段）。 */
    private var stopSignal: CompletableDeferred<Unit>? = null

    /**
     * 会话代号：旧会话被取消后它的收尾（finally）可能晚于新会话开始执行，
     * 用一个自增代号把「旧会话的收尾」挡在外面，避免它把新会话的在场状态清掉。
     */
    private var session = 0

    /** 熄屏守卫：听写进行中注册，ACTION_SCREEN_OFF 即停录（与实时通话的熄屏兜底同款）。 */
    private var screenOffReceiver: BroadcastReceiver? = null

    fun start() {
        if (active) return
        // 与 VoiceModeController 相同的开始前判据：未配置识别方式时不必进识别链路，
        // 直接原位给出旧语音入口的同一句提示，避免 toast 一闪而过且无处可去。
        DoubaoVoiceConfig.load(context)
        val settings = SpeechInputSession.configFor(VoiceEntryMode.UNIVERSAL)
        if (!SpeechInputSession.ready(VoiceEntryMode.UNIVERSAL, settings)) {
            notice = NotConfiguredMessage
            return
        }
        notice = null
        micLevel.reset()
        val id = ++session
        active = true
        finalizing = false
        listening = false
        transcript = ""
        outcome = null
        autoCommit = null
        registerScreenOffGuard()
        val stop = CompletableDeferred<Unit>()
        stopSignal = stop
        job = scope.launch {
            try {
                val heard = SpeechInputSession.recognize(
                    context = context.applicationContext,
                    onListening = { listening = true },
                    onText = { next -> transcript = next },
                    mode = VoiceEntryMode.UNIVERSAL,
                    settings = SpeechInputSession.configFor(VoiceEntryMode.UNIVERSAL),
                    onLevel = { level -> micLevel.value = level },
                    stop = stop,
                )
                if (session == id) {
                    if (!heard) {
                        // 一句话结束但一个字都没识别出来：给出提示并退出听写条。
                        // ✓ 收尾期间静音同理——用户按 ✓ 已经表达过「就这样」，不再补空态提示。
                        if (!finalizing) outcome = ComposerDictationOutcome.Empty
                    } else {
                        // 说完停顿、识别自然结束（未按 ✓ / ×）：把最终转写交给 UI 直接发送
                        // （发送门禁不通时 UI 退回「继续编辑」）。✓ 收尾走 [onCommitText] 同步
                        // 交付（交回输入框继续编辑，不自动发送）。在 finally 收摊前先交出。
                        val text = transcript.trim()
                        if (text.isNotBlank()) {
                            if (finalizing) onCommitText?.invoke(text) else autoCommit = text
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session == id) outcome = ComposerDictationOutcome.Failed(error)
            } finally {
                if (session == id) {
                    active = false
                    finalizing = false
                    listening = false
                    micLevel.reset()
                    unregisterScreenOffGuard()
                }
            }
        }
    }

    /** × ：丢弃这次听写（或关掉未配置提示），麦克风随协程取消一起释放。 */
    fun cancel() {
        session++
        job?.cancel()
        job = null
        stopSignal = null
        active = false
        finalizing = false
        listening = false
        transcript = ""
        outcome = null
        // 用户显式取消：即使识别恰好同时自然结束，也不该再自动发送。
        autoCommit = null
        notice = null
        micLevel.reset()
        unregisterScreenOffGuard()
    }

    /**
     * ✓ ：开始收尾提交——结束录音并把「识别引擎的最终转写」交回输入框，不是 [cancel]
     * 那样的立即取消。
     *
     * 立即取消会把麦克风里还没识别完的尾段直接丢掉（用户话音刚落就按 ✓ 时最明显）；
     * 这里只 complete 停录信号，识别侧照常停麦、刷尾段、组织最终文字（豆包发末包等
     * final、离线 finish()/flush()），最终文字经 [onCommitText] 同步交回。收尾期间
     * [finalizing] 置 true：听写条保持在场（转写定格），✓ 钮 morph 成正圆加载态。
     *
     * 识别链路自身仍有超时兜底（豆包等 final / 离线刷尾都有界），收尾不会无限等；
     * 收尾中重复按下是空操作；✓ 落在自然结束之后的窄竞态按「拦截自动发送、同步落字」
     * 处理（见实现），× 与重新 start 仍可随时打断收尾。
     * （不叫 finalize()：那会撞上 java.lang.Object.finalize 的 JVM 签名。）
     */
    fun beginCommit() {
        if (!active) {
            // 自然结束与 ✓ 的窄竞态：识别恰好在 ✓ 生效前收尾（autoCommit 已就位待发、
            // 听写条正在退场）。✓ 的语义（拿回去改）优先于自动发送——拦下 autoCommit，
            // 同步落字进编辑框。cancel / 未开始时转写为空，天然空操作。
            if (transcript.isNotBlank()) {
                autoCommit = null
                onCommitText?.invoke(transcript.trim())
            }
            return
        }
        if (finalizing) return
        if (!listening) {
            // 连接阶段（还没开录）：没有任何已捕获的尾段可等，直接按 × 的语义收场——
            // 停录信号也要等引擎进采集循环才会被看到（连接/模型装载可能挂数秒），不能等。
            // 开录前的转写必为空（onText 只在 onListening 之后到达），cancel 不丢任何内容。
            cancel()
            return
        }
        finalizing = true
        // 说完停顿恰好同时发生：自然结束路径已经接管（autoCommit / outcome 二选一），
        // complete 停录信号是幂等的，两条路径都会把文字交回，不会双发。
        stopSignal?.complete(Unit)
        stopSignal = null
    }

    fun consumeOutcome() {
        outcome = null
    }

    /** 消费掉一次自然结束的转写（UI 处理完发送 / 退回编辑后调用）。 */
    fun consumeAutoCommit() {
        autoCommit = null
    }

    /**
     * 熄屏即停：听写进行中注册 ACTION_SCREEN_OFF 守卫，熄屏立刻取消会话释放麦克风。
     * 不停的话浮窗（TYPE_APPLICATION_OVERLAY 悬在锁屏之上）会在无人注视时继续录音，
     * 环境噪声触发端点还会把噪声转写自动发送出去；与实时通话的熄屏兜底同一规则。
     */
    private fun registerScreenOffGuard() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) cancel()
            }
        }
        screenOffReceiver = receiver
        runCatching {
            context.registerReceiver(
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_OFF),
                Context.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure {
            // 注册失败不阻断听写，只失去这条兜底（与浮窗熄屏接收器同一取舍）。
            screenOffReceiver = null
        }
    }

    private fun unregisterScreenOffGuard() {
        screenOffReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenOffReceiver = null
    }

    private companion object {
        /** 与 VoiceModeController 未配置分支的文案保持一致（旧语音入口的失败提示）。 */
        const val NotConfiguredMessage =
            "请在语音 → 语音对话中配置识别方式，或下载离线语音包"
    }
}

/** 听写结束的异常结果：识别出错 / 没有识别到内容。 */
internal sealed interface ComposerDictationOutcome {
    data class Failed(val error: Throwable) : ComposerDictationOutcome
    data object Empty : ComposerDictationOutcome
}

/**
 * 跨线程的音量槽：音频线程只写 [value]，UI 帧循环只读，不需要快照状态。
 *
 * @Volatile 足够——单个 float 的可见性只需 volatile，动画侧每帧还会再做一次指数平滑。
 */
internal class VoiceMicLevel {
    @Volatile
    var value: Float = 0f

    fun reset() {
        value = 0f
    }
}

/** 记住一个听写会话，并在退出组合（切会话、关浮层、页面销毁）时释放麦克风。 */
@Composable
internal fun rememberComposerDictation(): ComposerDictation {
    val context = LocalContext.current
    val dictation = remember(context) { ComposerDictation(context) }
    DisposableEffect(dictation) {
        onDispose { dictation.cancel() }
    }
    return dictation
}
