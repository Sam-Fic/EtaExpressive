package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrSession
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

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

    suspend fun recognize(
        context: Context,
        onListening: suspend () -> Unit,
        onText: suspend (String) -> Unit,
        mode: VoiceEntryMode = VoiceEntryMode.DICTATION,
        settings: DoubaoVoiceConfig.Config? = null,
        diagnostic: VoiceDiagnostics? = null,
    ): Boolean {
        check(microphone.tryLock()) { "语音输入正在使用麦克风" }
        try {
            DoubaoVoiceConfig.load(context)
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
                    if (selected) DoubaoAsrSession.recognize(onListening, onText, selectedConfig)
                    else OfflineSpeechSession.recognize(context, onListening, onText, mode, diagnostic)
                } finally { watcher.cancel() }
            }
        } finally { microphone.unlock() }
    }
}
