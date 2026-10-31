package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import kotlinx.coroutines.flow.collect
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import io.github.mangi.eta.agent.voice.tts.DoubaoSpeech
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechPlaybackProfile
import io.github.mangi.eta.agent.voice.tts.SpeechSpeakableText
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class VoiceEntryMode(val wireValue: String) {
    DICTATION("dictation"),
    UNIVERSAL("universal"),
    DOUBAO_DUPLEX("doubao_duplex");

    companion object {
        fun fromWireValue(value: String): VoiceEntryMode =
            entries.firstOrNull { it.wireValue == value } ?: DICTATION
    }
}

enum class VoiceModePhase { Off, Connecting, Listening, Transcribing, Thinking, Speaking, Error }

data class VoiceModeState(
    val mode: VoiceEntryMode? = null,
    val phase: VoiceModePhase = VoiceModePhase.Off,
    val transcript: String = "",
    val reply: String = "",
    val error: String? = null,
) {
    val active: Boolean get() = phase != VoiceModePhase.Off && phase != VoiceModePhase.Error
}

data class VoiceChatSnapshot(
    val isStreaming: Boolean = false,
    val lastAgentId: String? = null,
    val lastAgentText: String = "",
    /**
     * 这一段快照来自哪场会话。回复自动朗读只在「武装自己的那一场会话」里开口，
     * 切会话时盯着目标会话的最后一条历史回复是最常见的误读来源。null = 生产方没提供。
     */
    val conversationId: String? = null,
    /** 进行中的运行 id；没有运行在跑时为 null。用于识别「切进一个正在生成的会话」。 */
    val runId: String? = null,
)

/** Owns either the cascaded chat loop or the direct SeedDuplex call, never both. */
/** Owner prefix of utterances spoken by voice mode; one owner per reply turn. */
internal const val VOICE_MODE_SPEECH_OWNER_PREFIX = "voice-mode-"

internal class VoiceModeController(
    context: Context,
    private val scope: CoroutineScope,
    private val submit: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val mutableState = MutableStateFlow(VoiceModeState())
    val state = mutableState.asStateFlow()
    private val chat = MutableStateFlow(VoiceChatSnapshot())
    private var job: Job? = null
    private var duplex: DoubaoDuplexSession? = null
    private var generation = 0L
    private var diagnostic: VoiceDiagnostics? = null

    init {
        DoubaoVoiceConfig.load(app)
        OfflineSpeechPack.initialize(app)
        scope.launch {
            DoubaoVoiceConfig.state.collect { config ->
                state.value.mode?.let { if (!VoiceEntryPolicy.enabled(config, it)) stop() }
            }
        }
    }

    fun updateChat(snapshot: VoiceChatSnapshot) {
        chat.value = snapshot
        val current = mutableState.value
        if (current.mode == VoiceEntryMode.UNIVERSAL &&
            current.phase in setOf(VoiceModePhase.Thinking, VoiceModePhase.Speaking)) {
            mutableState.value = current.copy(reply = snapshot.lastAgentText)
        }
    }

    fun start(mode: VoiceEntryMode) {
        if (mode == VoiceEntryMode.DICTATION || job?.isActive == true ||
            !VoiceEntryPolicy.enabled(DoubaoVoiceConfig.state.value, mode)) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = VoiceModeState(mode, VoiceModePhase.Error, error = "需要麦克风权限")
            return
        }
        when (mode) {
            VoiceEntryMode.UNIVERSAL -> startUniversal()
            VoiceEntryMode.DOUBAO_DUPLEX -> startDuplex()
            VoiceEntryMode.DICTATION -> Unit
        }
    }

    private fun startUniversal() {
        io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig.load(app)
        if (!SpeechInputSession.ready(VoiceEntryMode.UNIVERSAL)) {
            mutableState.value = VoiceModeState(
                VoiceEntryMode.UNIVERSAL,
                VoiceModePhase.Error,
                error = "请在语音 → 语音对话中配置识别方式，或下载离线语音包",
            )
            return
        }
        stop()
        // Snapshot each feature's own choices once; read-aloud/dictation changes cannot leak in.
        val recognitionSettings = SpeechInputSession.configFor(VoiceEntryMode.UNIVERSAL)
        val playbackSettings = SpeechPlaybackProfile.CONVERSATION.snapshot()
        val trace = VoiceDiagnostics("universal")
        diagnostic = trace
        trace.mark("session.begin")
        job = scope.launch {
            try {
                while (true) {
                    var text = ""
                    mutableState.value = VoiceModeState(VoiceEntryMode.UNIVERSAL, VoiceModePhase.Connecting)
                    val token = SpeechPlayback.beginInput()
                    trace.mark("recognition.begin")
                    val heard = try {
                        SpeechInputSession.recognize(
                            app,
                            mode = VoiceEntryMode.UNIVERSAL,
                            settings = recognitionSettings,
                            onListening = {
                                trace.mark("recognition.listening")
                                mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Listening)
                            },
                            onText = { next ->
                                trace.mark("recognition.partial", "chars" to next.length, sampled = true)
                                text = next
                                mutableState.value = mutableState.value.copy(
                                    phase = VoiceModePhase.Listening,
                                    transcript = next,
                                )
                            },
                        )
                    } finally {
                        SpeechPlayback.endInput(token)
                    }
                    trace.mark("recognition.done", "heard" to if (heard) 1 else 0, "chars" to text.length)
                    if (!heard || text.isBlank()) continue

                    val baseline = chat.value.lastAgentId
                    // 说出去的内容已经交给模型，气泡里的识别文字立即清空；
                    // 下一轮开录时再显示新的转写，不再把上一轮的话挂在思考 / 播报阶段。
                    mutableState.value = mutableState.value.copy(
                        phase = VoiceModePhase.Transcribing,
                        transcript = "",
                    )
                    trace.mark("model.submit", "chars" to text.length)
                    submit(text)
                    mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Thinking)
                    withTimeout(20_000) {
                        chat.first { it.isStreaming || it.lastAgentId != baseline }
                    }
                    val owner = "$VOICE_MODE_SPEECH_OWNER_PREFIX${UUID.randomUUID()}"
                    var spoken = 0
                    var speakingMessageId: String? = null
                    withTimeout(240_000) {
                        while (true) {
                            val snap = chat.value
                            trace.mark("model.snapshot", "streaming" to if (snap.isStreaming) 1 else 0, "chars" to snap.lastAgentText.length, sampled = true)
                            if (snap.lastAgentId == baseline || snap.lastAgentId == null) {
                                chat.first { it != snap }
                                continue
                            }
                            if (speakingMessageId != snap.lastAgentId) {
                                speakingMessageId = snap.lastAgentId
                                spoken = 0
                            }
                            val ready = SpeechSpeakableText.committedSentences(
                                snap.lastAgentText,
                                finalized = !snap.isStreaming && snap.lastAgentId != baseline,
                            )
                            val utterances = ready.drop(spoken)
                            if (utterances.isNotEmpty()) {
                                mutableState.value = mutableState.value.copy(
                                    phase = VoiceModePhase.Speaking,
                                    reply = snap.lastAgentText,
                                )
                                for (utterance in utterances) {
                                    SpeechPlayback.speak(app, owner, utterance, trace, settings = playbackSettings)
                                    val playback = SpeechPlayback.state.first { it.owner != owner }
                                    trace.mark("tts.await_done", "error" to if (playback.error != null) 1 else 0)
                                    playback.error?.let { error(it) }
                                }
                                spoken = ready.size
                            } else if (snap.lastAgentText.isNotBlank()) {
                                mutableState.value = mutableState.value.copy(reply = snap.lastAgentText)
                            }
                            if (!snap.isStreaming && snap.lastAgentId != baseline) break
                            chat.first {
                                it.lastAgentText != snap.lastAgentText ||
                                    it.isStreaming != snap.isStreaming ||
                                    it.lastAgentId != snap.lastAgentId
                            }
                        }
                    }
                    delay(300)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutableState.value = VoiceModeState(
                    mode = VoiceEntryMode.UNIVERSAL,
                    phase = VoiceModePhase.Error,
                    error = e.message ?: "语音对话失败",
                )
            } finally {
                // Only this conversation's utterances; Agent speech and reply read-aloud keep playing.
                SpeechPlayback.stopOwned("voice_mode_end") { it.startsWith(VOICE_MODE_SPEECH_OWNER_PREFIX) }
            }
        }
    }

    private fun startDuplex() {
        stop()
        val sessionGeneration = generation
        val trace = VoiceDiagnostics("duplex")
        diagnostic = trace
        trace.mark("controller.start")
        job = scope.launch {
            var ownedSession: DoubaoDuplexSession? = null
            try {
                val providerId = Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID)
                val provider = ProviderRepository.providerById(providerId)
                    ?.takeIf(io.github.mangi.eta.data.model.SpeechSynthesisModels::isRealtimeVoiceProvider)
                    ?: error(app.getString(R.string.voice_doubao_duplex_pick_provider))
                check(DoubaoSpeech.isOpenspeech(provider.baseUrl)) { app.getString(R.string.voice_doubao_duplex_provider_only) }
                val apiKey = provider.apiKey.trim()
                check(apiKey.isNotBlank()) { app.getString(R.string.voice_doubao_duplex_missing_key) }
                io.github.mangi.eta.agent.voice.doubao.PersonalVoices.load(app)
                val storedVoice = Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE)
                val personal = io.github.mangi.eta.agent.voice.doubao.PersonalVoices.selected(storedVoice, apiKey)
                val voice = personal?.id ?: DoubaoDuplexProtocol.resolveVoice(storedVoice)
                val instructions = Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS)
                    .ifBlank { DEFAULT_DUPLEX_INSTRUCTIONS }
                mutableState.value = VoiceModeState(VoiceEntryMode.DOUBAO_DUPLEX, VoiceModePhase.Connecting)
                val token = SpeechPlayback.beginInput()
                try {
                    val session = DoubaoDuplexSession(app, onState = { next ->
                        trace.mark("controller.phase", "phase" to next.phase.ordinal, sampled = true)
                        val accepted = generation == sessionGeneration
                        val changed = mutableState.value.reply != next.reply
                        if (accepted) mutableState.value = next
                        if (changed || !accepted) trace.mark("text.controller",
                            "accepted" to if (accepted) 1 else 0, "changed" to if (changed) 1 else 0,
                            "chars" to next.reply.length, sampled = true)
                    }, diagnostic = trace)
                    ownedSession = session
                    duplex = session
                    session.run(apiKey, voice, instructions)
                } finally {
                    SpeechPlayback.endInput(token)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation != sessionGeneration) return@launch
                mutableState.value = VoiceModeState(
                    mode = VoiceEntryMode.DOUBAO_DUPLEX,
                    phase = VoiceModePhase.Error,
                    error = e.message ?: app.getString(R.string.voice_doubao_duplex_failed),
                )
            } finally {
                ownedSession?.close()
                if (duplex === ownedSession) duplex = null
            }
        }
    }

    fun stop() {
        diagnostic?.mark("controller.stop")
        generation++
        job?.cancel()
        job = null
        duplex?.close()
        duplex = null
        SpeechPlayback.stopOwned("voice_mode_stop") { it.startsWith(VOICE_MODE_SPEECH_OWNER_PREFIX) }
        mutableState.value = VoiceModeState()
        diagnostic?.finish()
        diagnostic = null
    }

    companion object {
        const val DEFAULT_DUPLEX_VOICE = DoubaoRealtimeVoices.DEFAULT_ID
        const val DEFAULT_DUPLEX_INSTRUCTIONS = "你是Eta，一个简洁、自然、友善的中文语音助手。"
    }
}
