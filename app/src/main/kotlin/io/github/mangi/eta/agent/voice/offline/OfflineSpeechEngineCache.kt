package io.github.mangi.eta.agent.voice.offline

import android.os.SystemClock
import com.k2fsa.sherpa.ncnn.DecoderConfig
import com.k2fsa.sherpa.ncnn.FeatureExtractorConfig
import com.k2fsa.sherpa.ncnn.ModelConfig
import com.k2fsa.sherpa.ncnn.RecognizerConfig
import com.k2fsa.sherpa.ncnn.SherpaNcnn
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

internal const val SampleRate = 16_000
internal const val ReadSamples = 1600
internal const val VadWindowSamples = 512
private const val TailPaddingSamples = 4800

/**
 * OpenMP / ncnn / ONNX Runtime 都保留进程级状态，模型装载、解码与释放必须落在同一线程。
 * 先录音后加载时，麦克风读取走 IO，但所有 native 引擎调用仍然回到这里。
 */
internal val offlineSpeechNative: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "eta-speech-native").apply { isDaemon = true }
    }.asCoroutineDispatcher()

/**
 * 两个运行库（ncnn / ONNX Runtime）的调用形态不同，但「送样本 → 解码 → 取文本 → 端点」
 * 的循环完全一致，所以在最薄的一层做适配，避免把整个播放/录音循环写两遍。
 */
internal interface StreamingEngine : AutoCloseable {
    fun accept(samples: FloatArray)
    fun ready(): Boolean
    fun decode()
    fun text(): String
    fun endpoint(): Boolean
    fun finish()
    /** 下一轮说话前清空上一轮的流/特征状态，模型本体保持常驻。 */
    fun reset()
}

private class NcnnEngine(private val recognizer: SherpaNcnn) : StreamingEngine {
    private var finished = false

    override fun accept(samples: FloatArray) = recognizer.acceptSamples(samples)
    override fun ready(): Boolean = recognizer.isReady()
    override fun decode() = recognizer.decode()
    override fun text(): String = recognizer.text
    override fun endpoint(): Boolean = recognizer.isEndpoint()
    override fun finish() {
        // ncnn 版的 transducer 需要补一小段静音才会吐出尾包结果。
        recognizer.acceptSamples(FloatArray(TailPaddingSamples))
        recognizer.inputFinished()
        finished = true
    }

    override fun reset() {
        // inputFinished 之后重建流，避免上一轮的尾包状态污染下一轮。
        recognizer.reset(recreate = finished)
        finished = false
    }

    override fun close() = recognizer.close()
}

private class OnnxEngine(
    private val recognizer: OnlineRecognizer,
) : StreamingEngine {
    private var stream: OnlineStream = recognizer.createStream()
    private var used = false

    override fun accept(samples: FloatArray) {
        used = true
        stream.acceptWaveform(samples, SampleRate)
    }

    override fun ready(): Boolean = recognizer.isReady(stream)
    override fun decode() = recognizer.decode(stream)
    override fun text(): String = recognizer.getResult(stream).text
    override fun endpoint(): Boolean = recognizer.isEndpoint(stream)
    override fun finish() {
        used = true
        stream.inputFinished()
    }

    override fun reset() {
        if (!used) return
        runCatching { stream.release() }
        stream = recognizer.createStream()
        used = false
    }

    override fun close() {
        runCatching { stream.release() }
        recognizer.release()
    }
}

/**
 * SenseVoice 走非流式整句推理：Silero VAD 在本地判断「说完了」，再把这一段交给
 * OfflineRecognizer。它没有逐字中间结果，但中文准确率和标点 / ITN 明显更好。
 */
internal class SenseVoiceEngine(model: String, tokens: String, vadModel: String) : AutoCloseable {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SampleRate, featureDim = 80),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model,
                    // 空串表示自动检测中 / 英 / 日 / 韩 / 粤。
                    language = "",
                    useInverseTextNormalization = true,
                ),
                tokens = tokens,
                numThreads = 4,
            ),
            decodingMethod = "greedy_search",
        ),
    )
    private val vad = try {
        Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModel,
                    threshold = 0.5f,
                    // 比流式模型的 1.2 秒端点更利落；0.8 秒足够区分句中停顿和说完。
                    minSilenceDuration = 0.8f,
                    minSpeechDuration = 0.25f,
                    windowSize = VadWindowSamples,
                    maxSpeechDuration = 30f,
                ),
                sampleRate = SampleRate,
                numThreads = 1,
            ),
        )
    } catch (error: Throwable) {
        // VAD 装载失败时不能让已经装载的 240 MB 识别器悬空到 GC。
        runCatching { recognizer.release() }
        throw error
    }

    fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    fun speechDetected(): Boolean = vad.isSpeechDetected()

    /** 收尾时把 VAD 里尚未满一个静音窗口的语音也吐出来，避免超时前说的话丢掉。 */
    fun flush() = vad.flush()

    /** 返回一个已经结束的语音段；连续说话超过 30 秒时 VAD 会先切一段出来。 */
    fun segment(): FloatArray? {
        if (vad.empty()) return null
        val samples = vad.front().samples
        vad.pop()
        return samples
    }

    fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SampleRate)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** 下一轮说话前清掉上一轮 VAD 队列与端点状态，识别器本体保持常驻。 */
    fun reset() {
        vad.clear()
        vad.reset()
    }

    override fun close() {
        runCatching { vad.release() }
        runCatching { recognizer.release() }
    }
}

/** 按模型声明的引擎族装载对应运行库。只走文件路径，模型不打包进 APK。 */
private fun createStreamingEngine(model: SpeechModelCatalog.Model, dir: File): StreamingEngine {
    fun path(name: String) = File(dir, name).absolutePath
    return when (model.engine) {
        SpeechModelCatalog.Engine.NCNN_TRANSDUCER -> NcnnEngine(
            SherpaNcnn(
                RecognizerConfig(
                    featConfig = FeatureExtractorConfig(16_000f, 80),
                    modelConfig = ModelConfig(
                        encoderParam = path(SpeechModelCatalog.NCNN_ENCODER_PARAM),
                        encoderBin = path(SpeechModelCatalog.NCNN_ENCODER_BIN),
                        decoderParam = path(SpeechModelCatalog.NCNN_DECODER_PARAM),
                        decoderBin = path(SpeechModelCatalog.NCNN_DECODER_BIN),
                        joinerParam = path(SpeechModelCatalog.NCNN_JOINER_PARAM),
                        joinerBin = path(SpeechModelCatalog.NCNN_JOINER_BIN),
                        tokens = path(SpeechModelCatalog.TOKENS),
                        numThreads = 1, useGPU = false,
                    ),
                    decoderConfig = DecoderConfig(method = "greedy_search"),
                    enableEndpoint = true,
                    rule1MinTrailingSilence = 8f,
                    rule2MinTrailingSilence = 1.2f,
                    rule3MinUtteranceLength = 60f,
                ),
            ),
        )

        SpeechModelCatalog.Engine.ONNX_ONLINE_ZIPFORMER2 -> {
            val recognizer = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SampleRate, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = path(SpeechModelCatalog.ONNX_ENCODER),
                            decoder = path(SpeechModelCatalog.ONNX_DECODER),
                            joiner = path(SpeechModelCatalog.ONNX_JOINER),
                        ),
                        tokens = path(SpeechModelCatalog.TOKENS),
                        // fp32 编码器比 ncnn 版大得多，单/双线程跟不上 480 ms 的分块节奏。
                        numThreads = 4,
                        // 不写 modelType：导出时已把 zipformer2、chunk 等元数据写进 ONNX，
                        // 显式覆盖反而可能选到不匹配的实现。
                    ),
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 8f, minUtteranceLength = 0f),
                        rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0f),
                        rule3 = EndpointRule(mustContainNonSilence = false, minTrailingSilence = 0f, minUtteranceLength = 60f),
                    ),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                ),
            )
            OnnxEngine(recognizer)
        }

        // 非流式整句模型由 SenseVoiceEngine 单独装载，不走这条流式接口。
        SpeechModelCatalog.Engine.ONNX_OFFLINE_SENSE_VOICE -> error("SenseVoice does not stream")
    }
}

internal data class SpeechEngineKey(
    val modelId: String,
    val revision: String,
    val engine: SpeechModelCatalog.Engine,
)

private fun SpeechModelCatalog.Model.engineKey() = SpeechEngineKey(id, revision, engine)

internal sealed class CachedSpeechEngine : AutoCloseable {
    abstract val key: SpeechEngineKey
    abstract val batch: Boolean
    abstract fun reset()
}

internal class CachedStreamingEngine(
    override val key: SpeechEngineKey,
    val engine: StreamingEngine,
) : CachedSpeechEngine() {
    override val batch: Boolean get() = false
    override fun reset() = engine.reset()
    override fun close() = engine.close()
}

internal class CachedBatchEngine(
    override val key: SpeechEngineKey,
    val engine: SenseVoiceEngine,
) : CachedSpeechEngine() {
    override val batch: Boolean get() = true
    override fun reset() = engine.reset()
    override fun close() = engine.close()
}

internal data class AcquiredSpeechEngine(
    val engine: CachedSpeechEngine,
    /** true 表示直接命中常驻模型，没有重新装载。 */
    val warm: Boolean,
    val loadMs: Long,
)

private fun createEngine(model: SpeechModelCatalog.Model, dir: File): CachedSpeechEngine {
    val key = model.engineKey()
    return when (model.engine) {
        SpeechModelCatalog.Engine.ONNX_OFFLINE_SENSE_VOICE -> {
            fun path(name: String) = File(dir, name).absolutePath
            CachedBatchEngine(
                key,
                SenseVoiceEngine(
                    model = path(SpeechModelCatalog.SENSE_VOICE_MODEL),
                    tokens = path(SpeechModelCatalog.TOKENS),
                    vadModel = path(SpeechModelCatalog.SILERO_VAD),
                ),
            )
        }
        else -> CachedStreamingEngine(key, createStreamingEngine(model, dir))
    }
}

/**
 * 进程内只保留一个当前模型。所有方法都必须在 [offlineSpeechNative] 上调用。
 *
 * 识别进行中收到失效请求时只挂起，等 lease 释放后再关闭，避免把正在解码的引擎抽走。
 */
internal object OfflineSpeechEngineCache {
    private var current: CachedSpeechEngine? = null
    private var inUse = 0
    private var pendingInvalidate = false
    private var lastTouchedAt = 0L

    fun matches(model: SpeechModelCatalog.Model): Boolean = current?.key == model.engineKey()

    fun lastUsedAt(): Long? = current?.let { lastTouchedAt }

    fun acquire(model: SpeechModelCatalog.Model, dir: File): AcquiredSpeechEngine {
        check(inUse == 0) { "Speech engine is already in use" }
        val key = model.engineKey()
        current?.let { existing ->
            if (existing.key == key) {
                inUse = 1
                lastTouchedAt = SystemClock.elapsedRealtime()
                return AcquiredSpeechEngine(existing, warm = true, loadMs = 0)
            }
            closeCurrent()
        }
        val start = SystemClock.elapsedRealtime()
        val created = createEngine(model, dir)
        current = created
        inUse = 1
        pendingInvalidate = false
        lastTouchedAt = SystemClock.elapsedRealtime()
        val loadMs = SystemClock.elapsedRealtime() - start
        AndroidAgentLogger.info("Offline speech engine loaded model=${model.id} loadMs=$loadMs")
        return AcquiredSpeechEngine(created, warm = false, loadMs = loadMs)
    }

    /** @return true 表示确实新建了引擎；命中已有模型时返回 false。 */
    fun prewarm(model: SpeechModelCatalog.Model, dir: File): Boolean {
        val key = model.engineKey()
        // 命中常驻模型时不做任何事；识别中使用中的同款模型也不能因此报错。
        if (current?.key == key) return false
        check(inUse == 0) { "Speech engine is already in use" }
        closeCurrent()
        val start = SystemClock.elapsedRealtime()
        current = createEngine(model, dir)
        pendingInvalidate = false
        lastTouchedAt = SystemClock.elapsedRealtime()
        AndroidAgentLogger.info(
            "Offline speech engine prewarmed model=${model.id} loadMs=${SystemClock.elapsedRealtime() - start}",
        )
        return true
    }

    fun release(engine: CachedSpeechEngine, healthy: Boolean) {
        if (current !== engine) {
            runCatching { engine.close() }
            return
        }
        inUse = (inUse - 1).coerceAtLeast(0)
        lastTouchedAt = SystemClock.elapsedRealtime()
        if (!healthy) {
            AndroidAgentLogger.warn("Offline speech engine evicted after an unhealthy session")
            closeCurrent()
            return
        }
        if (pendingInvalidate && inUse == 0) closeCurrent()
    }

    fun invalidate(reason: String) {
        if (current != null) AndroidAgentLogger.info("Offline speech engine invalidated: $reason")
        pendingInvalidate = true
        if (inUse == 0) closeCurrent()
    }

    fun releaseIdleIfUnused(idleMs: Long): Boolean {
        if (idleMs <= 0 || inUse > 0 || current == null) return false
        if (SystemClock.elapsedRealtime() - lastTouchedAt < idleMs) return false
        AndroidAgentLogger.info("Offline speech engine released after idle")
        closeCurrent()
        return true
    }

    private fun closeCurrent() {
        if (inUse > 0) {
            pendingInvalidate = true
            return
        }
        current?.let { runCatching { it.close() } }
        current = null
        pendingInvalidate = false
    }
}
