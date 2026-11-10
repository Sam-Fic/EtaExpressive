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

    /**
     * 开麦限时。AudioRecord 构造/startRecording 是 native 调用，MIUI 麦克风仲裁
     * （语音键盘等前台麦克风服务占用时）会让它阻塞等待而非失败，实测可挂 30s+；
     * 超时后放弃等待并给出可行动的错误，锁由 finally 立即释放。
     */
    private const val MicOpenTimeoutMillis = 5_000L

    @SuppressLint("MissingPermission") // Permission is requested by the user-initiated UI entry.
    suspend fun recognize(
        context: Context,
        onListening: suspend () -> Unit,
        onText: suspend (String) -> Unit,
        mode: VoiceEntryMode = VoiceEntryMode.DICTATION,
        diagnostic: VoiceDiagnostics? = null,
        /** 逐帧音量（音频线程回调，只驱动动画）。 */
        onLevel: ((Float) -> Unit)? = null,
    ): Boolean {
        check(microphone.tryLock()) { "Speech input is already active" }
        var audio: AudioRecord? = null
        // 会话通过 await 成功认领的录音器；finally 只对它负责。
        var micSlot: CompletableDeferred<AudioRecord>? = null
        // 会话通过 await 成功认领的租约；finally 只对它负责。
        var acquired: AcquiredSpeechEngine? = null
        // 引擎装载的落定槽位：装载任务挂在对象级 scope 上，不随会话取消。
        var leaseSlot: CompletableDeferred<AcquiredSpeechEngine>? = null
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

            // 开麦与装载同样挪出会话协程（2026-11-08 设备实测：MIUI 麦克风仲裁下
            // AudioRecord 构造/startRecording 可 native 阻塞 30s+ 且取消打不断，曾把
            // 两把麦克风互斥锁一起拖住，之后浮窗/应用内的任何语音入口都报占麦）。
            // 开麦任务挂对象级 scope；会话只 await 槽位——可取消、限时。
            val mic = CompletableDeferred<AudioRecord>()
            micSlot = mic
            scope.launch {
                val outcome = runCatching {
                    withContext(Dispatchers.IO) {
                        val opened = openRecorder()
                        try {
                            opened.startRecording()
                            check(opened.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone cannot start" }
                        } catch (startError: Throwable) {
                            runCatching { opened.stop() }
                            runCatching { opened.release() }
                            throw startError
                        }
                        opened
                    }
                }
                outcome.fold(mic::complete, mic::completeExceptionally)
            }

            // 引擎装载挪出会话协程：预热/冷载可能占住 native 单线程十几秒（2026-11-08 用户
            // 实测 loadMs=15706，期间点停止时协程卡在 native 队列里取消不动，麦克风与
            // SpeechInputSession 互斥锁一起被拖住，浮窗唤起与应用内重试全部报
            // 「语音输入正在使用麦克风」）。装载任务挂对象级 scope 不随会话取消；
            // 会话只 await 独立槽位，取消立即打断等待。
            val slot = CompletableDeferred<AcquiredSpeechEngine>()
            leaseSlot = slot
            scope.launch {
                val outcome = runCatching {
                    withContext(native) {
                        val next = OfflineSpeechEngineCache.acquire(model, dir)
                        try {
                            next.engine.reset()
                        } catch (resetError: Throwable) {
                            OfflineSpeechEngineCache.release(next.engine, healthy = false)
                            throw resetError
                        }
                        next
                    }
                }
                outcome.fold(slot::complete, slot::completeExceptionally)
            }

            // 先录音后装载：开麦等待与模型装载并行，两边都可被取消立即打断。
            val recorder = try {
                withTimeout(MicOpenTimeoutMillis) { mic.await() }
            } catch (timeout: TimeoutCancellationException) {
                error("麦克风暂时无法打开，可能被其他应用占用，请稍后重试")
            }
            audio = recorder
            val recordStart = SystemClock.elapsedRealtime()

            return coroutineScope {
                val chunks = Channel<FloatArray>(Channel.UNLIMITED)
                val reader = launch(Dispatchers.IO) {
                    try {
                        readAudio(recorder, chunks, onLevel)
                    } finally {
                        chunks.close()
                    }
                }
                try {
                    withContext(Dispatchers.Main) { onListening() }

                    // If a launch prewarm is already loading on the native worker, this waits behind it
                    // while capture keeps buffering; a warm cache returns without touching the model files.
                    val lease = slot.await()
                    acquired = lease
                    diagnostic?.mark(
                        "recognition.engine",
                        "warm" to if (lease.warm) 1 else 0,
                        "loadMs" to lease.loadMs,
                    )

                    withContext(native) {
                        when (val engine = lease.engine) {
                            is CachedStreamingEngine -> runStreaming(engine, chunks, recordStart, onText, diagnostic)
                            is CachedBatchEngine -> runBatch(engine, chunks, recordStart, onText, diagnostic)
                        }
                    }
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
            // 录音器释放转后台：stop/release 同样可能被麦克风仲裁拖住（见上方开麦注释），
            // 在这里同步等 native 会把麦克风互斥锁一起拖住，下一次语音就会报占麦。
            audio?.let { claimed ->
                scope.launch {
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { claimed.stop() }
                        runCatching { claimed.release() }
                    }
                }
            }
            micSlot?.takeIf { audio == null }?.let { slot ->
                // 超时/取消时开麦可能仍在后台阻塞：落定后由一次性回收者释放构造出的录音器。
                scope.launch {
                    runCatching { slot.await() }.getOrNull()?.let { late ->
                        withContext(NonCancellable + Dispatchers.IO) {
                            runCatching { late.stop() }
                            runCatching { late.release() }
                        }
                    }
                }
            }
            // 引擎租约回收全部转交后台：在这里同步等 native 会把麦克风互斥锁一起拖住
            // （见上方装载注释里的实测时间线），下一次语音（浮窗/应用内）就会报占麦。
            acquired?.let { lease ->
                scope.launch {
                    withContext(native) { OfflineSpeechEngineCache.release(lease.engine, healthy) }
                }
            }
            leaseSlot?.takeIf { acquired == null }?.let { slot ->
                // 会话取消时装载可能仍在后台排队：装载落定后由一次性回收者处理，
                // healthy 释放会让模型继续常驻，这次装载成果留给下一次识别。
                scope.launch {
                    runCatching { slot.await() }.getOrNull()?.let { late ->
                        withContext(native) { OfflineSpeechEngineCache.release(late.engine, healthy) }
                    }
                }
            }
            scheduleIdleRelease()
            // 识别中收到的模型切换/失效在这里补做；损坏的引擎不立即重装，等下一次真实识别。
            if (healthy) OfflineSpeechPrewarm.refresh(context)
            microphone.unlock()
        }
    }

    private suspend fun readAudio(
        audio: AudioRecord,
        chunks: Channel<FloatArray>,
        onLevel: ((Float) -> Unit)? = null,
    ) {
        val samples = ShortArray(ReadSamples)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = audio.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { "Microphone read failed" }
            if (count == 0) {
                delay(10)
                continue
            }
            onLevel?.invoke(io.github.mangi.eta.agent.voice.pcmLevel(samples, count))
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
