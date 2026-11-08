package io.github.mangi.eta.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString
import io.github.mangi.eta.core.AndroidAgentLogger
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Official SeedDuplex Realtime JSON protocol with 16 kHz PCM input and 24 kHz PCM16 output. */
internal class DoubaoDuplexSession(
    context: Context,
    private val onState: (VoiceModeState) -> Unit,
    private val diagnostic: VoiceDiagnostics = VoiceDiagnostics("duplex"),
) {
    private val app = context.applicationContext
    private val opened = CompletableDeferred<WebSocket>()
    private val finished = CompletableDeferred<Unit>()
    private val events = Channel<JSONObject>(Channel.UNLIMITED)
    private val output = Channel<ByteArray>(Channel.BUFFERED)
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    @Volatile private var closed = false
    private var transcript = ""
    private var reply = ""
    private val textStream = DuplexTextStream(diagnostic)
    private val interruptionGate = DuplexInterruptionGate()
    private var turnAudioStartBytes = 0L
    private var receivedAudioBytes = 0L
    private var writtenAudioBytes = 0L

    private val client = AgentHttpClient.modelClient.newBuilder().addInterceptor(io.github.mangi.eta.agent.voice.doubao.DoubaoDiagnostics)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    suspend fun run(apiKey: String, voice: String, instructions: String) = coroutineScope {
        diagnostic.mark("connect.begin", "voiceConfigured" to if (voice.isNotBlank()) 1 else 0)
        val audioManager = app.getSystemService(AudioManager::class.java)
        diagnostic.mark("audio.environment", "mode" to audioManager.mode,
            "volume" to audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            "maxVolume" to audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            "muted" to if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) 1 else 0,
            "micMuted" to if (audioManager.isMicrophoneMute) 1 else 0)
        val request = DoubaoDuplexProtocol.request(apiKey)
        socket = client.newWebSocket(request, listener)
        val ws = withTimeout(15_000) { opened.await() }
        val sent = ws.send(DoubaoDuplexProtocol.sessionCreate(voice, instructions).toString())
        diagnostic.mark("session.send", "accepted" to if (sent) 1 else 0)
        check(sent) { "实时语音会话创建发送失败" }

        playbackJob = launch(Dispatchers.IO) { playOutput() }
        try {
            while (isActive && !closed) {
                val event = events.receiveCatching().getOrNull() ?: break
                val type = event.optString("type")
                val queuedMs = (android.os.SystemClock.elapsedRealtime() - event.optLong("_etaReceivedAt", android.os.SystemClock.elapsedRealtime())).coerceAtLeast(0)
                diagnostic.mark("event.consume", "queuedMs" to queuedMs, sampled = true)
                when (type) {
                    "session.created" -> {
                        onState(state(VoiceModePhase.Listening))
                        if (captureJob == null) captureJob = launch(Dispatchers.IO) { captureInput(ws) }
                    }
                    "conversation.item.input_audio_transcription.started" -> {
                        interruptionGate.started()
                        // A speculative VAD event must not discard queued speech or the reply.
                        diagnostic.mark("interruption.pending")
                    }
                    "conversation.item.input_audio_transcription.delta",
                    "conversation.item.input_audio_transcription.completed" -> {
                        val completed = type.endsWith(".completed")
                        val recognized = interruptionGate.transcript(
                            DoubaoDuplexProtocol.eventText(event), completed,
                        ) {
                            diagnostic.mark("interruption.confirmed")
                            transcript = ""
                            reply = ""
                            textStream.reset()
                            flushOutput()
                        }
                        if (recognized != null) {
                            transcript = DuplexAsrHypothesis.display(transcript, recognized, completed)
                            onState(state(if (completed) VoiceModePhase.Thinking else VoiceModePhase.Listening))
                        } else {
                            diagnostic.mark("interruption.unconfirmed", "completed" to if (completed) 1 else 0)
                        }
                    }
                    "conversation.item.input_audio_transcription.failed" -> {
                        interruptionGate.started()
                        diagnostic.mark("interruption.discarded")
                    }
                    "response.output_text.delta", "response.output_text.done" -> {
                        reply = textStream.accept(event, queuedMs)
                        val publishStarted = System.nanoTime()
                        onState(state(VoiceModePhase.Speaking))
                        diagnostic.mark("text.publish", "chars" to reply.length,
                            "callbackUs" to (System.nanoTime() - publishStarted) / 1_000,
                            "done" to if (type.endsWith(".done")) 1 else 0, sampled = !type.endsWith(".done"))
                    }
                    "response.output_audio.started" -> {
                        turnAudioStartBytes = receivedAudioBytes
                        onState(state(VoiceModePhase.Speaking))
                    }
                    "response.output_audio.delta" -> {
                        val payload = DoubaoDuplexProtocol.audioPayload(event)
                        val bytes = try { Base64.decode(payload, Base64.DEFAULT) } catch (e: IllegalArgumentException) {
                            diagnostic.mark("decode.failure", "chars" to payload.length)
                            throw e
                        }
                        diagnostic.mark("decode.audio", "chars" to payload.length, "decoded" to bytes.size, sampled = true, bytes = bytes.size.toLong())
                        if (bytes.isNotEmpty()) {
                            if (receivedAudioBytes == 0L) AndroidAgentLogger.info("Duplex: first audio bytes=${bytes.size}")
                            receivedAudioBytes += bytes.size
                            val queueStart = android.os.SystemClock.elapsedRealtime()
                            output.send(bytes)
                            diagnostic.mark("audio.enqueue", "blockedMs" to (android.os.SystemClock.elapsedRealtime() - queueStart), sampled = true, bytes = bytes.size.toLong())
                        }
                    }
                    "response.output_audio.done" -> {
                        if (receivedAudioBytes == turnAudioStartBytes) {
                            val code = event.optString("status_code").take(40)
                            error("豆包未返回可播放音频（状态码：${code.ifBlank { "未提供" }}），请检查实时语音音色和服务权限")
                        }
                        onState(state(VoiceModePhase.Listening))
                    }
                    "response.done", "response.canceled" -> {
                        onState(state(VoiceModePhase.Listening))
                    }
                    "error" -> error(safeError(event))
                    "session.closed" -> break
                }
            }
        } finally {
            textStream.finish()
            close()
            runCatching { withTimeout(2_000) { finished.await() } }
        }
    }

    private fun state(phase: VoiceModePhase) = VoiceModeState(
        mode = VoiceEntryMode.DOUBAO_DUPLEX,
        phase = phase,
        transcript = transcript,
        reply = reply,
    )

    @SuppressLint("MissingPermission")
    private suspend fun captureInput(ws: WebSocket) {
        // 与 SpeechInputSession.recognize 共用同一把进程级麦克风互斥锁：实时通话整个会话都
        // 持有 AudioRecord，不互斥时应用内语音对话与浮窗实时通话会同时占麦、互相串音。
        SpeechInputSession.lockMicrophoneOrThrow()
        try {
            captureInputLocked(ws)
        } finally {
            SpeechInputSession.unlockMicrophone()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureInputLocked(ws: WebSocket) {
        val minimum = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "无法初始化麦克风" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, INPUT_FRAME_BYTES * 8),
        )
        recorder = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "无法打开麦克风" }
        diagnostic.mark("capture.init", "state" to audio.state, "rate" to audio.sampleRate,
            "channels" to audio.channelCount, "session" to audio.audioSessionId, "source" to MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        audio.startRecording()
        diagnostic.mark("capture.start", "state" to audio.recordingState)
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风没有开始录音" }
        val frame = ByteArray(INPUT_FRAME_BYTES)
        try {
            while (currentCoroutineContext().isActive && !closed) {
                val count = audio.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                var peak = 0
                var sumSquares = 0.0
                for (i in 0 until (count.coerceAtLeast(0) - 1) step 2) {
                    val sample = ((frame[i].toInt() and 255) or (frame[i + 1].toInt() shl 8)).toShort().toInt()
                    peak = maxOf(peak, kotlin.math.abs(sample))
                    sumSquares += sample.toDouble() * sample
                }
                diagnostic.mark("capture.read", "read" to count, "peak" to peak,
                    "rms" to if (count > 1) kotlin.math.sqrt(sumSquares / (count / 2)) else 0.0,
                    "route" to (audio.routedDevice?.type ?: -1), sampled = true, bytes = count.coerceAtLeast(0).toLong())
                check(count >= 0) { "麦克风读取失败" }
                if (count == 0) continue
                val payload = if (count == frame.size) frame else frame.copyOf(count)
                val event = JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", Base64.encodeToString(payload, Base64.NO_WRAP))
                val accepted = ws.send(event.toString())
                diagnostic.mark("upload.send", "accepted" to if (accepted) 1 else 0,
                    "wsQueueBytes" to ws.queueSize(), sampled = accepted, bytes = count.toLong())
                check(accepted) { "实时语音连接已关闭" }
            }
        } finally {
            // close() 可能在另一条线程先行 stop/release（见 [close]），二次释放会抛
            // IllegalStateException；取消中的协程抛新异常会进未捕获处理器，必须吞掉。
            runCatching { audio.stop() }
            runCatching { audio.release() }
            if (recorder === audio) recorder = null
        }
    }

    private suspend fun playOutput() = withContext(Dispatchers.IO) {
        val minimum = AudioTrack.getMinBufferSize(
            OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "无法初始化扬声器" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 2, 24_000))
            .build()
        player = track
        check(track.state == AudioTrack.STATE_INITIALIZED) { "无法打开扬声器" }
        diagnostic.mark("playback.init", "state" to track.state, "rate" to track.sampleRate, "bufferFrames" to track.bufferSizeInFrames, "encoding" to track.audioFormat, "channels" to track.channelCount)
        track.play()
        diagnostic.mark("playback.start", "state" to track.playState)
        try {
            for (bytes in output) {
                currentCoroutineContext().ensureActive()
                var offset = 0
                while (offset < bytes.size) {
                    val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    diagnostic.mark("playback.write", "written" to written,
                        "route" to (track.routedDevice?.type ?: -1), "playState" to track.playState,
                        "headFrames" to track.playbackHeadPosition, "underruns" to track.underrunCount,
                        sampled = written > 0, bytes = written.coerceAtLeast(0).toLong())
                    check(written > 0) { "实时语音播放失败：AudioTrack write=$written" }
                    if (writtenAudioBytes == 0L) AndroidAgentLogger.info("Duplex: first playback write=$written route=${track.routedDevice?.type}")
                    writtenAudioBytes += written
                    offset += written
                }
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
            if (player === track) player = null
        }
    }

    private fun flushOutput() {
        var dropped = 0L
        while (true) { val bytes = output.tryReceive().getOrNull() ?: break; dropped += bytes.size }
        diagnostic.mark("playback.flush", "droppedBytes" to dropped)
        player?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.play() }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        diagnostic.mark("close.begin", "receivedBytes" to receivedAudioBytes, "writtenBytes" to writtenAudioBytes)
        socket?.send(JSONObject().put("type", "session.close").put("event_id", UUID.randomUUID().toString()).toString())
        captureJob?.cancel()
        playbackJob?.cancel()
        output.close()
        events.close()
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.release() }
        player = null
        socket?.close(1000, "voice mode stopped")
        socket = null
        diagnostic.mark("resources.close")
        textStream.finish()
        diagnostic.finish()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            diagnostic.mark("connect.open", "http" to response.code)
            opened.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            diagnostic.mark("ws.text", sampled = true, bytes = text.length.toLong())
            acceptEvent(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            diagnostic.mark("ws.binary", sampled = true, bytes = bytes.size.toLong())
            acceptEvent(bytes.utf8())
        }

        private fun acceptEvent(text: String) {
            runCatching { JSONObject(text) }
                .onSuccess { event ->
                    val known = setOf("session.created", "session.updated", "session.closed", "input_audio_buffer.committed",
                        "conversation.item.input_audio_transcription.started", "conversation.item.input_audio_transcription.delta",
                        "conversation.item.input_audio_transcription.completed", "conversation.item.input_audio_transcription.failed",
                        "response.output_text.delta", "response.output_text.done", "response.output_audio.started",
                        "response.output_audio.delta", "response.output_audio.done", "response.done", "response.canceled", "error")
                    val type = event.optString("type").takeIf { it in known } ?: "unknown"
                    diagnostic.mark("rx.$type", "audioChars" to event.optString("audio").length,
                        "deltaChars" to event.optString("delta").length, "textChars" to event.optString("text").length,
                        "transcriptChars" to (event.opt("transcript") as? String).orEmpty().length,
                        "contentChars" to (event.opt("content") as? String).orEmpty().length,
                        "status" to event.optLong("status_code", -1), "fields" to event.length(),
                        sampled = type.endsWith(".delta") || type == "unknown")
                    event.put("_etaReceivedAt", android.os.SystemClock.elapsedRealtime())
                    if (events.trySend(event).isFailure) diagnostic.mark("event.dropped", sampled = true)

                }
                .onFailure {
                    diagnostic.mark("event.parse_failure", "chars" to text.length)
                    events.trySend(JSONObject().put("type", "error").put("error",
                        JSONObject().put("message", "实时语音事件解析失败")))
                }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            diagnostic.mark("connect.failure", "http" to (response?.code ?: -1), "closed" to if (closed) 1 else 0)
            if (!opened.isCompleted) opened.completeExceptionally(t)
            if (!closed) events.trySend(
                JSONObject().put("type", "error").put("error", JSONObject().put("message", "实时语音连接失败")),
            )
            finished.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            diagnostic.mark("connect.closed", "code" to code)
            finished.complete(Unit)
        }
    }

    private fun safeError(event: JSONObject): String =
        event.optJSONObject("error")?.optString("message")?.take(300)?.ifBlank { null }
            ?: "豆包实时语音服务返回错误"

    companion object {
        private const val INPUT_RATE = 16_000
        private const val OUTPUT_RATE = 24_000
        private const val INPUT_FRAME_BYTES = 640
    }
}
