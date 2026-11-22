package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrSession
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.math.sqrt

internal object SpeechInputSession {
    private val microphone = Mutex()

    /**
     * 实时通话（DoubaoDuplexSession）不经 [recognize]，而是整个会话期间直接持有 AudioRecord；
     * 这里把同一把麦克风互斥锁开放给它，保证应用内语音对话与浮窗实时通话两条链路（各持一个
     * VoiceModeController）不会同时占麦。锁是进程级单例，两条链路都跑在主进程。
     */
    internal fun lockMicrophoneOrThrow() {
        check(microphone.tryLock()) { "语音输入正在使用麦克风" }
    }

    /** 与 [lockMicrophoneOrThrow] 成对调用；只在成功获锁后调用。 */
    internal fun unlockMicrophone() {
        microphone.unlock()
    }

    fun configFor(mode: VoiceEntryMode, config: DoubaoVoiceConfig.Config = DoubaoVoiceConfig.state.value): DoubaoVoiceConfig.Config =
        if (mode == VoiceEntryMode.UNIVERSAL) config.copy(
            cloudAsr = config.conversationCloudAsr,
            asrKey = config.conversationAsrKey,
            resource = config.conversationResource,
        ) else config

    fun ready(mode: VoiceEntryMode = VoiceEntryMode.DICTATION, config: DoubaoVoiceConfig.Config = configFor(mode)): Boolean =
        mode != VoiceEntryMode.DOUBAO_DUPLEX && VoiceEntryPolicy.enabled(DoubaoVoiceConfig.state.value, mode) &&
            if (config.cloudAsr) config.asrKey.isNotBlank() else OfflineSpeechPack.state.value.ready

    /**
     * [ready] 的可挂起版：离线识别等包校验落定后再判资格，云端凭据缺失仍同步判死。
     *
     * 冷进程被系统助理唤醒时（浮窗唤起即听写），[OfflineSpeechPack.initialize] 的校验
     * 协程（237 MB 模型逐件 SHA-256，冷缓存下秒级）还在 IO 线程上跑，[ready] 同步读
     * `state.value.ready` 会把「校验中」误判成「未配置」——听写首帧组合早于校验发布。
     * 这里等 `checking` 归零再判；真未下载的离线包校验快速失败（文件缺失逐件长度即断），
     * 不会白等。
     *
     * [context] 非空时补一脚幂等的 [OfflineSpeechPack.initialize]：调用方若早于任何
     * 控制器构造（控制器 init 会触发 initialize），校验从未起步，等待将永不落定。
     */
    suspend fun awaitReady(
        mode: VoiceEntryMode = VoiceEntryMode.DICTATION,
        config: DoubaoVoiceConfig.Config = configFor(mode),
        context: Context? = null,
    ): Boolean {
        if (mode == VoiceEntryMode.DOUBAO_DUPLEX ||
            !VoiceEntryPolicy.enabled(DoubaoVoiceConfig.state.value, mode)
        ) return false
        if (config.cloudAsr) return config.asrKey.isNotBlank()
        context?.let { OfflineSpeechPack.initialize(it) }
        return OfflineSpeechPack.state.first { !it.checking }.ready
    }

    suspend fun recognize(
        context: Context,
        onListening: suspend () -> Unit,
        onText: suspend (String) -> Unit,
        mode: VoiceEntryMode = VoiceEntryMode.DICTATION,
        settings: DoubaoVoiceConfig.Config? = null,
        diagnostic: VoiceDiagnostics? = null,
        /**
         * 录音期间逐帧上报 0..1 的瞬时音量（音频线程回调，禁止在其中做 Compose 状态写入）。
         * 只用于驱动声波/流光动画，识别结果与它无关。
         */
        onLevel: ((Float) -> Unit)? = null,
        /**
         * 停录信号：complete 后停止采集、走各引擎既有的「刷尾段 → 拿最终结果」收尾
         * （不是取消——取消会把还没识别完的尾段直接丢掉）。null = 保持旧行为，一直录到
         * 说完停顿 / 超时为止。
         */
        stop: CompletableDeferred<Unit>? = null,
    ): Boolean {
        check(microphone.tryLock()) { "语音输入正在使用麦克风" }
        try {
            // 调用方显式传入 settings 时已经加载过配置（听写 start() 刚 load 完）：再 load
            // 一次会把 AndroidKeyStore 解密重付一遍，唤醒路径上这段落在首帧组合的主线程里。
            if (settings == null) DoubaoVoiceConfig.load(context)
            val selectedConfig = settings ?: configFor(mode)
            check(ready(mode, selectedConfig)) { "请启用对应语音功能，并配置所选识别引擎" }
            val selected = selectedConfig.cloudAsr
            return coroutineScope {
                val owner = currentCoroutineContext().job
                val watcher = launch {
                    DoubaoVoiceConfig.state.first { !VoiceEntryPolicy.enabled(it, mode) || (settings == null && configFor(mode, it).cloudAsr != selected) }
                    owner.cancel(CancellationException("对应语音功能已关闭或识别引擎已切换"))
                }
                try {
                    if (selected) DoubaoAsrSession.recognize(onListening, onText, selectedConfig, onLevel, stop)
                    else OfflineSpeechSession.recognize(context, onListening, onText, mode, diagnostic, onLevel, stop)
                } finally { watcher.cancel() }
            }
        } finally { microphone.unlock() }
    }
}

/**
 * 一帧 16-bit PCM 的音量，归一到 0..1。
 *
 * 取 RMS 后放大 8 倍：正常说话大约落在 0.2~0.8，与原型的
 * `average / 96`（分析器频域平均值）同一量级，直接喂给流光的
 * `activeSpeed` / `glowIntensity` 混合即可。
 */
internal fun pcmLevel(samples: ShortArray, count: Int = samples.size): Float {
    if (count <= 0) return 0f
    var sum = 0.0
    for (i in 0 until count) {
        val v = samples[i] / 32768.0
        sum += v * v
    }
    return ((sqrt(sum / count) * 8.0).toFloat()).coerceIn(0f, 1f)
}

/** 同 [pcmLevel]，输入为小端序 16-bit PCM 字节流（豆包 ASR 的发送缓冲）。 */
internal fun pcmLevel(bytes: ByteArray, count: Int): Float {
    val frames = count / 2
    if (frames <= 0) return 0f
    var sum = 0.0
    for (frame in 0 until frames) {
        val i = frame * 2
        val v = (((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort()) / 32768.0
        sum += v * v
    }
    return ((sqrt(sum / frames) * 8.0).toFloat()).coerceIn(0f, 1f)
}
