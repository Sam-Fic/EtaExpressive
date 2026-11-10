package io.github.mangi.eta.agent.voice.doubao

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import io.github.mangi.eta.agent.model.AgentHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

internal object DoubaoAsrSession {
    @SuppressLint("MissingPermission")
    suspend fun recognize(onListening: suspend () -> Unit, onText: suspend (String) -> Unit,
        config: DoubaoVoiceConfig.Config = DoubaoVoiceConfig.state.value,
        onLevel: ((Float) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        check(config.asrKey.isNotBlank()) { "请配置豆包 ASR API Key" }
        val opened = CompletableDeferred<Unit>()
        val results = Channel<DoubaoAsrProtocol.Result>(32)
        val client = AgentHttpClient.modelClient.newBuilder().addInterceptor(io.github.mangi.eta.agent.voice.doubao.DoubaoDiagnostics).readTimeout(0, TimeUnit.MILLISECONDS).build()
        val socket = client.newWebSocket(Request.Builder().url(DoubaoAsrProtocol.URL)
            .header("X-Api-Key", config.asrKey).header("X-Api-Resource-Id", config.resource)
            .header("X-Api-Request-Id", UUID.randomUUID().toString()).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { DoubaoDiagnostics.mark("asr.connected"); opened.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    if (!results.trySend(DoubaoAsrProtocol.decode(bytes.toByteArray())).isSuccess) {
                        results.close(IllegalStateException("ASR 结果队列已满")); webSocket.cancel()
                    }
                } catch (e: Exception) { results.close(e); webSocket.cancel() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val failure = IllegalStateException("豆包 ASR 连接失败${response?.let { "（HTTP ${it.code}）" }.orEmpty()}")
                opened.completeExceptionally(failure); results.close(failure)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { results.close() }
        })
        var recorder: AudioRecord? = null
        try {
            withTimeout(15_000) { opened.await() }
            ensureActive()
            check(socket.send(DoubaoAsrProtocol.config().toByteString()))
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 64000))
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "麦克风初始化失败" }
            ensureActive(); audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            DoubaoDiagnostics.mark("asr.listening", "rate=16000 channels=1 bits=16")
            withContext(Dispatchers.Main) { onListening() }
            coroutineScope {
                val done = CompletableDeferred<Unit>()
                var lastText = ""
                val receiver = launch {
                    for (result in results) {
                        if (result.text.isNotBlank() && result.text != lastText) {
                            if (lastText.isBlank()) DoubaoDiagnostics.mark("asr.first_result")
                            lastText = result.text
                            withContext(Dispatchers.Main) { onText(result.text) }
                        }
                        if (result.utteranceDone || result.final) done.complete(Unit)
                        if (result.final) { DoubaoDiagnostics.mark("asr.final", "chars=${lastText.length}"); return@launch }
                    }
                    check(done.isCompleted) { "豆包 ASR 在最终结果前断开" }
                }
                val started = SystemClock.elapsedRealtime()
                val packet = ByteArray(6400)
                var filled = 0
                while (!done.isCompleted && SystemClock.elapsedRealtime() - started < 60_000 &&
                    (lastText.isNotBlank() || SystemClock.elapsedRealtime() - started < 10_000)) {
                    ensureActive()
                    val n = audio.read(packet, filled, packet.size - filled, AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "麦克风读取失败" }
                    filled += n
                    if (n > 0) onLevel?.invoke(io.github.mangi.eta.agent.voice.pcmLevel(packet, filled))
                    if (filled == packet.size) {
                        check(socket.queueSize() < 256_000) { "ASR 网络发送积压，请重试" }
                        check(socket.send(DoubaoAsrProtocol.frame(2, packet).toByteString()))
                        filled = 0
                    }
                    delay(10)
                }
                audio.stop()
                check(socket.send(DoubaoAsrProtocol.frame(2, packet.copyOf(filled), true).toByteString()))
                withTimeout(10_000) { receiver.join() }
                lastText.isNotBlank()
            }
        } finally {
            // release 与 stop 同为收尾兜底，失败不得吞掉 finally 里其余清理或冒充业务异常。
            runCatching { recorder?.stop() }; runCatching { recorder?.release() }
            socket.cancel(); results.cancel()
            DoubaoDiagnostics.mark("asr.closed")
        }
    }
}
