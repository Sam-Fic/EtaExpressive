package io.github.mangi.eta.agent.voice.offline

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import io.github.mangi.eta.agent.voice.VoiceDiagnostics
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceEntryPolicy
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex

/**
 * No network and no audio files. Native engine calls stay on [offlineSpeechNative];
 * microphone capture runs on IO so the user can start speaking before a cold model finishes loading.
 */
internal object OfflineSpeechSession {
    private val microphone = Mutex()
    private val native = offlineSpeechNative
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var idleJob: Job? = null

    @SuppressLint("MissingPermission") // Permission is requested by the user-initiated UI entry.
    suspend fun recognize(
        context: Context,
        onListening: suspend () -> Unit,
        onText: suspend (String) -> Unit,
        mode: VoiceEntryMode = VoiceEntryMode.DICTATION,
        diagnostic: VoiceDiagnostics? = null,
    ): Boolean {
        check(microphone.tryLock()) { "Speech input is already active" }
        var audio: AudioRecord? = null
        var acquired: AcquiredSpeechEngine? = null
        // 取消/用户主动停止不算引擎损坏：下一轮 reset() 会清理残留流，模型继续常驻。
        var healthy = true
        cancelIdleRelease()
        try {
            check(
                VoiceEntryPolicy.enabled(DoubaoVoiceConfig.state.value, mode) &&
                    OfflineSpeechPack.state.value.ready,
            )
            val model = OfflineSpeechPack.state.value.model
            val dir = OfflineSpeechPack.directory(context, model)
            // Recheck sizes before passing paths to native code; full hashes were verified at startup/download.
            check(model.assets.all { File(dir, it.name).length() == it.bytes })

            // Start recording first: the model load must never hold the microphone hostage.
            val recorder = withContext(Dispatchers.IO) {
                val opened = openRecorder()
                // Publish before starting so a failed start is still released by the outer finally.
                audio = opened
                opened.startRecording()
                check(opened.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone cannot start" }
                opened
            }
            val recordStart = SystemClock.elapsedRealtime()

            return coroutineScope {
                val chunks = Channel<FloatArray>(Channel.UNLIMITED)
                val reader = launch(Dispatchers.IO) {
                    try {
                        readAudio(recorder, chunks)
                    } finally {
                        chunks.close()
                    }
                }
                try {
                    withContext(Dispatchers.Main) { onListening() }

                    // If a launch prewarm is already loading on the native worker, this waits behind it
                    // while capture keeps buffering; a warm cache returns without touching the model files.
                    val lease = withContext(native) {
                        val next = OfflineSpeechEngineCache.acquire(model, dir)
                        // Publish the lease before reset so cancellation on the way back still releases it.
                        acquired = next
                        runCatching { next.engine.reset() }.onFailure { error ->
                            OfflineSpeechEngineCache.release(next.engine, healthy = false)
                            acquired = null
                            throw error
                        }
                        next
                    }
                    diagnostic?.mark(
                        "recognition.engine",
                        "warm" to if (lease.warm) 1 else 0,
                        "loadMs" to lease.loadMs,
                    )

                    val heard = withContext(native) {
                        when (val engine = lease.engine) {
                            is CachedStreamingEngine -> runStreaming(engine, chunks, recordStart, onText, diagnostic)
                            is CachedBatchEngine -> runBatch(engine, chunks, recordStart, onText, diagnostic)
                        }
                    }
                    heard
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    healthy = false
                    throw error
                } finally {
                    // 读线程独占 AudioRecord；先停它再交给外层 finally 释放，避免 release 与 read 并发。
                    withContext(NonCancellable) { reader.cancelAndJoin() }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
            }
            acquired?.let { lease ->
                withContext(NonCancellable + native) {
                    OfflineSpeechEngineCache.release(lease.engine, healthy)
                }
            }
            scheduleIdleRelease()
            // 识别中收到的模型切换/失效在这里补做；损坏的引擎不立即重装，等下一次真实识别。
            if (healthy) OfflineSpeechPrewarm.refresh(context)
            microphone.unlock()
        }
    }

    private suspend fun readAudio(audio: AudioRecord, chunks: Channel<FloatArray>) {
        val samples = ShortArray(ReadSamples)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { "Microphone read failed" }
            if (count == 0) {
                delay(10)
                continue
            }
            chunks.send(FloatArray(count) { samples[it] / 32768f })
        }
    }

    private suspend fun runStreaming(
        cached: CachedStreamingEngine,
        chunks: Channel<FloatArray>,
        recordStart: Long,
        onText: suspend (String) -> Unit,
        diagnostic: VoiceDiagnostics?,
    ): Boolean {
        val engine = cached.engine
        var lastText = ""
        var bufferedSamples = 0
        var bufferedChunks = 0
        // Drain everything captured while the model was loading, then follow the live stream.
        while (true) {
            val chunk = chunks.tryReceive().getOrNull() ?: break
            bufferedSamples += chunk.size
            bufferedChunks++
            engine.accept(chunk)
            while (engine.ready()) {
                currentCoroutineContext().ensureActive()
                engine.decode()
            }
        }
        if (bufferedSamples > 0) {
            diagnostic?.mark(
                "recognition.audio.buffer",
                "samples" to bufferedSamples,
                "ms" to (bufferedSamples * 1000L / SampleRate),
                "chunks" to bufferedChunks,
            )
        }
        var text = engine.text().trim()
        if (text.isNotEmpty()) {
            lastText = text
            withContext(Dispatchers.Main) { onText(text) }
        }
        val listenStart = SystemClock.elapsedRealtime()

        if (!engine.endpoint()) {
            for (chunk in chunks) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                if (SpeechInputPolicy.timedOut(now - recordStart, now - listenStart, lastText.isNotEmpty())) break
                engine.accept(chunk)
                while (engine.ready()) {
                    currentCoroutineContext().ensureActive()
                    engine.decode()
                }
                text = engine.text().trim()
                if (text.isNotEmpty() && text != lastText) {
                    lastText = text
                    withContext(Dispatchers.Main) { onText(text) }
                }
                if (engine.endpoint()) break
            }
        }

        // Only natural completion flushes decoder tail. Explicit cancellation keeps already shown text.
        currentCoroutineContext().ensureActive()
        engine.finish()
        while (engine.ready()) {
            currentCoroutineContext().ensureActive()
            engine.decode()
        }
        val finalText = engine.text().trim()
        if (finalText.isNotEmpty() && finalText != lastText) {
            withContext(Dispatchers.Main) { onText(finalText) }
        }
        return finalText.isNotEmpty() || lastText.isNotEmpty()
    }

    /**
     * 非流式链路只在 VAD 判定「这一段说完了」后回调一次文字，不会像流式链路那样
     * 反复改写转写；超时与取消行为和流式链路保持一致。
     */
    private suspend fun runBatch(
        cached: CachedBatchEngine,
        chunks: Channel<FloatArray>,
        recordStart: Long,
        onText: suspend (String) -> Unit,
        diagnostic: VoiceDiagnostics?,
    ): Boolean {
        val engine = cached.engine
        var speechDetected = false
        var decodedSegment = false
        var text = ""
        var bufferedSamples = 0
        var bufferedChunks = 0
        // 先消费模型装载期间积压的音频；若那时已经说完，就立刻整段识别。
        while (true) {
            val chunk = chunks.tryReceive().getOrNull() ?: break
            bufferedSamples += chunk.size
            bufferedChunks++
            engine.accept(chunk)
            if (engine.speechDetected()) speechDetected = true
            val segment = engine.segment() ?: continue
            decodedSegment = true
            text = engine.decode(segment).trim()
            break
        }
        if (bufferedSamples > 0) {
            diagnostic?.mark(
                "recognition.audio.buffer",
                "samples" to bufferedSamples,
                "ms" to (bufferedSamples * 1000L / SampleRate),
                "chunks" to bufferedChunks,
            )
        }
        val listenStart = SystemClock.elapsedRealtime()

        if (!decodedSegment) {
            for (chunk in chunks) {
                currentCoroutineContext().ensureActive()
                val now = SystemClock.elapsedRealtime()
                if (SpeechInputPolicy.timedOut(now - recordStart, now - listenStart, speechDetected)) break
                engine.accept(chunk)
                if (engine.speechDetected()) speechDetected = true
                val segment = engine.segment() ?: continue
                decodedSegment = true
                text = engine.decode(segment).trim()
                break
            }
        }

        if (!decodedSegment) {
            // 超时收尾：VAD 里可能还压着一段没等到静音判定的语音。
            currentCoroutineContext().ensureActive()
            engine.flush()
            val tail = engine.segment()
            if (tail != null) {
                currentCoroutineContext().ensureActive()
                text = engine.decode(tail).trim()
            }
        }
        if (text.isNotEmpty()) withContext(Dispatchers.Main) { onText(text) }
        return text.isNotEmpty()
    }

    /**
     * 根据离线包/语音配置决定是否保留或预加载模型。model 为 null 表示当前不该常驻本地引擎。
     * 预加载失败只记录，不影响后续第一次真实识别。
     */
    fun applyDemand(context: Context, model: SpeechModelCatalog.Model?, prewarm: Boolean) {
        scope.launch {
            withContext(native) {
                if (model == null) {
                    OfflineSpeechEngineCache.invalidate("local speech inactive")
                    cancelIdleRelease()
                    return@withContext
                }
                val dir = OfflineSpeechPack.directory(context, model)
                if (!model.assets.all { File(dir, it.name).length() == it.bytes }) {
                    OfflineSpeechEngineCache.invalidate("model files changed")
                    cancelIdleRelease()
                    return@withContext
                }
                if (prewarm) {
                    runCatching { OfflineSpeechEngineCache.prewarm(model, dir) }
                        .onFailure { error ->
                            AndroidAgentLogger.warn(
                                "Offline speech prewarm failed: ${error.safeLogType()}",
                            )
                        }
                } else if (!OfflineSpeechEngineCache.matches(model)) {
                    OfflineSpeechEngineCache.invalidate("model changed")
                }
                scheduleIdleRelease()
            }
        }
    }

    fun invalidate(reason: String) {
        scope.launch {
            withContext(native) {
                OfflineSpeechEngineCache.invalidate(reason)
                cancelIdleRelease()
            }
        }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        val minutes = OfflineSpeechRuntimeSettings.state.value.idleReleaseMinutes
        if (minutes <= 0) {
            idleJob = null
            return
        }
        val idleMs = minutes * 60_000L
        idleJob = scope.launch {
            withContext(native) {
                val lastUsed = OfflineSpeechEngineCache.lastUsedAt() ?: return@withContext
                val wait = lastUsed + idleMs - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                OfflineSpeechEngineCache.releaseIdleIfUnused(idleMs)
            }
        }
    }

    private fun cancelIdleRelease() {
        idleJob?.cancel()
        idleJob = null
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0)
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer * 2, 64_000))
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            error("Microphone cannot be initialized")
        }
        return audio
    }
}
