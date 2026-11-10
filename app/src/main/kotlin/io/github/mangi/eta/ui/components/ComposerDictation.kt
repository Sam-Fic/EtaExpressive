package io.github.mangi.eta.ui.components

import android.content.Context
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
 * 「继续编辑」）；按 ✓ 把文字交回输入框继续编辑（不发送），按 × 直接丢弃。识别引擎复用
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

    /**
     * 会话代号：旧会话被取消后它的收尾（finally）可能晚于新会话开始执行，
     * 用一个自增代号把「旧会话的收尾」挡在外面，避免它把新会话的在场状态清掉。
     */
    private var session = 0

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
        listening = false
        transcript = ""
        outcome = null
        autoCommit = null
        job = scope.launch {
            try {
                val heard = SpeechInputSession.recognize(
                    context = context.applicationContext,
                    onListening = { listening = true },
                    onText = { next -> transcript = next },
                    mode = VoiceEntryMode.UNIVERSAL,
                    settings = SpeechInputSession.configFor(VoiceEntryMode.UNIVERSAL),
                    onLevel = { level -> micLevel.value = level },
                )
                if (session == id) {
                    if (!heard) {
                        // 一句话结束但一个字都没识别出来：给出提示并退出听写条。
                        outcome = ComposerDictationOutcome.Empty
                    } else {
                        // 说完停顿、识别自然结束（未按 ✓ / ×）：把最终转写交给 UI 直接发送
                        // （发送门禁不通时 UI 退回「继续编辑」）。在 finally 收摊前先放好。
                        val text = transcript.trim()
                        if (text.isNotBlank()) autoCommit = text
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session == id) outcome = ComposerDictationOutcome.Failed(error)
            } finally {
                if (session == id) {
                    active = false
                    listening = false
                    micLevel.reset()
                }
            }
        }
    }

    /** × ：丢弃这次听写（或关掉未配置提示），麦克风随协程取消一起释放。 */
    fun cancel() {
        session++
        job?.cancel()
        job = null
        active = false
        listening = false
        transcript = ""
        outcome = null
        // 用户显式取消：即使识别恰好同时自然结束，也不该再自动发送。
        autoCommit = null
        notice = null
        micLevel.reset()
    }

    /** ✓ ：结束听写并把当前转写交回输入框；没有识别到文字时返回 null。 */
    fun commit(): String? {
        val text = transcript.trim()
        session++
        job?.cancel()
        job = null
        active = false
        listening = false
        transcript = ""
        outcome = null
        // 用户显式确认：内容交回输入框即为止，自然结束的自动发送作废。
        autoCommit = null
        notice = null
        micLevel.reset()
        return text.takeIf { it.isNotBlank() }
    }

    fun consumeOutcome() {
        outcome = null
    }

    /** 消费掉一次自然结束的转写（UI 处理完发送 / 退回编辑后调用）。 */
    fun consumeAutoCommit() {
        autoCommit = null
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
