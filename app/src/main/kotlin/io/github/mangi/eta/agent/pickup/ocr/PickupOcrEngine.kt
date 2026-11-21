// 移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）com.Badnng.moe.ocr.PaddleOcrHelper.kt。
// Eta 移植版差异：
//   - 剥离全部 NPU/QNN 逻辑（NpuAccelerator 探测、NPU 失败降级、空闲释放的 NPU 保留分支），
//     只保留 CPU 推理路径；
//   - 剥离 SharedPreferences 线程数配置，固定 numThreads=4（与原版缺省一致）；
//   - 剥离诊断识别入口（recognizeDiagnosticAsync），保留诊断数据结构供两步引擎取耗时。
// 空闲释放纪律（60s 无请求后释放 ORT 会话省内存）保留原版语义。
// 合规登记见 docs/THIRD_PARTY_NOTICES.md「Hyper-pick-up-code」一节。

package io.github.mangi.eta.agent.pickup.ocr

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.model.OCRRunResult
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * PP-OCRv6 Tiny 推理封装（单例 + 双 Mutex + 空闲释放）。
 *
 * Eta 移植版只跑 CPU：det/rec 模型都在 assets（[DET_MODEL_ASSET]/[REC_MODEL_ASSET]），
 * 首次识别时惰性初始化，60 秒无请求自动释放 ORT 会话；再次识别会重新加载模型
 * （冷加载百毫秒级，取餐码是低频场景，省内存优先）。
 *
 * 并发口径与原版一致：[recognitionMutex] 串行化识别、[initializationMutex] 串行化初始化
 * 与释放；持 recognitionMutex 时必须用 Locked 版本释放，Mutex 不可重入。
 */
class PickupOcrEngine private constructor(private val context: Context) {
    @Volatile
    private var ocr: PaddleOCR? = null
    private val initializationMutex = Mutex()
    private val recognitionMutex = Mutex()
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val idleReleaseLock = Any()
    private var idleReleaseJob: Job? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var lastInferenceTimeMs = -1L

    val isInitialized: Boolean get() = initialized

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic") ||
            Build.PRODUCT.contains("sdk") ||
            Build.PRODUCT.contains("vbox86p") ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")
    }

    data class TextBlock(
        val text: String,
        val boundingBox: Rect?,
        val confidence: Float,
    )

    data class RecognizeResult(
        val fullText: String,
        val textBlocks: List<TextBlock>,
        val diagnosticResult: DiagnosticResult,
    )

    data class DiagnosticPoint(
        val x: Float,
        val y: Float,
    )

    data class DiagnosticTextBlock(
        val text: String,
        val confidence: Float,
        val points: List<DiagnosticPoint>,
        val recognitionTimeMs: Long?,
    )

    data class DiagnosticResult(
        val textBlocks: List<DiagnosticTextBlock>,
        val imageWidth: Int,
        val imageHeight: Int,
        val detectionTimeMs: Long,
        val recognitionTimeMs: Long,
        val totalTimeMs: Long,
    )

    suspend fun initAsync(): Boolean {
        if (isEmulator()) {
            Log.w(TAG, "检测到模拟器，跳过 PP-OCRv6 Tiny 初始化")
            return false
        }
        cancelIdleRelease()
        if (initialized) {
            scheduleIdleRelease()
            return true
        }

        return initializationMutex.withLock {
            if (initialized) return@withLock true
            try {
                check(OpenCVUtils.init(context)) { "OpenCV 初始化失败" }

                val newOcr = PaddleOCR.create(
                    context = context,
                    config = PaddleOCRConfig(
                        detLimitSideLen = 64,
                        detLimitType = "min",
                        detMaxSideLimit = 2560,
                        detThresh = 0.2f,
                        detBoxThresh = 0.4f,
                        detUnclipRatio = 1.4f,
                        detMaxCandidates = 3000,
                        detUseDilation = false,
                        detScoreMode = "fast",
                        detBoxType = "quad",
                        // 保留 SDK 原始结果用于日志，业务阈值在 parseResult() 中执行。
                        recScoreThresh = 0f,
                        recBatchSize = 1,
                    ),
                    engineConfig = EngineConfig(numThreads = OCR_NUM_THREADS),
                    detModelAssetPath = DET_MODEL_ASSET,
                    recModelAssetPath = REC_MODEL_ASSET,
                    recConfigAssetPath = REC_CONFIG_ASSET,
                )
                ocr = newOcr
                initialized = true
                Log.i(
                    TAG,
                    "PP-OCRv6 Tiny 初始化成功, coldLoad=${newOcr.coldLoadTimeMs}ms, " +
                        "backend=${newOcr.activeBackend}, recThreshold=$OCR_MIN_CONFIDENCE",
                )
                scheduleIdleRelease()
                true
            } catch (error: Throwable) {
                ocr = null
                initialized = false
                Log.e(TAG, "PP-OCRv6 Tiny 初始化失败: ${error.message}", error)
                false
            }
        }
    }

    fun init(): Boolean = runBlocking { initAsync() }

    suspend fun recognizeAsync(bitmap: android.graphics.Bitmap): RecognizeResult? {
        cancelIdleRelease()
        return recognitionMutex.withLock {
            try {
                if (!initialized && !initAsync()) return@withLock null
                val currentOcr = ocr ?: return@withLock null
                val result = currentOcr.recognize(bitmap)
                lastInferenceTimeMs = result.totalTimeMs
                logRawResults(result)
                parseResult(result, bitmap.width, bitmap.height)
            } catch (error: Throwable) {
                Log.e(TAG, "PP-OCRv6 Tiny 识别失败: ${error.message}", error)
                null
            } finally {
                scheduleIdleRelease()
            }
        }
    }

    fun recognize(bitmap: android.graphics.Bitmap): RecognizeResult? = runBlocking {
        recognizeAsync(bitmap)
    }

    private fun logRawResults(result: OCRRunResult) {
        Log.d(RAW_LOG_TAG, "PP-OCRv6 原始识别结果，共 ${result.results.size} 行")
        result.results.forEach { line ->
            val accuracy = String.format(
                Locale.US,
                "%.2f%%",
                line.confidence.coerceIn(0f, 1f) * 100f,
            )
            Log.d(RAW_LOG_TAG, "${line.text}\t准确率=$accuracy")
        }
    }

    private fun parseResult(result: OCRRunResult, imageWidth: Int, imageHeight: Int): RecognizeResult {
        val diagnosticResult = DiagnosticResult(
            textBlocks = result.results.mapIndexed { index, line ->
                DiagnosticTextBlock(
                    text = line.text,
                    confidence = line.confidence,
                    points = line.box.points.map { point -> DiagnosticPoint(point.x, point.y) },
                    recognitionTimeMs = result.perLineRecMs.getOrNull(index),
                )
            },
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            detectionTimeMs = result.detectionTimeMs,
            recognitionTimeMs = result.recognitionTimeMs,
            totalTimeMs = result.totalTimeMs,
        )
        val regions = result.results
            .asSequence()
            .filter { it.text.isNotBlank() }
            .mapNotNull { line ->
                val points = line.box.points
                if (points.size != 4) return@mapNotNull null
                OcrTextRegion(
                    text = line.text,
                    confidence = line.confidence,
                    left = points.minOf { it.x },
                    top = points.minOf { it.y },
                    right = points.maxOf { it.x },
                    bottom = points.maxOf { it.y },
                )
            }
            .toList()
        val lines = OcrReadingOrder.groupIntoLines(regions)
        val blocks = lines.flatten().map { region ->
            TextBlock(
                text = region.text,
                boundingBox = Rect(
                    floor(region.left).toInt(),
                    floor(region.top).toInt(),
                    ceil(region.right).toInt(),
                    ceil(region.bottom).toInt(),
                ),
                confidence = region.confidence,
            )
        }
        val fullText = OcrReadingOrder.buildFullText(lines)
        Log.i(
            TAG,
            "PP-OCRv6 Tiny 识别完成: accepted=${blocks.size}/${result.results.size}, " +
                "lines=${lines.size}, total=${result.totalTimeMs}ms",
        )
        return RecognizeResult(
            fullText = fullText,
            textBlocks = blocks,
            diagnosticResult = diagnosticResult,
        )
    }

    fun close(reason: String = "explicit"): Boolean = runBlocking {
        releaseResources(reason, cancelScheduledRelease = true)
    }

    private fun cancelIdleRelease() {
        synchronized(idleReleaseLock) {
            idleReleaseJob?.cancel()
            idleReleaseJob = null
        }
    }

    private fun scheduleIdleRelease() {
        synchronized(idleReleaseLock) {
            idleReleaseJob?.cancel()
            idleReleaseJob = releaseScope.launch {
                delay(IDLE_RELEASE_DELAY_MS)
                releaseResources(reason = "idle-${IDLE_RELEASE_DELAY_MS}ms", cancelScheduledRelease = false)
            }
        }
    }

    private suspend fun releaseResources(reason: String, cancelScheduledRelease: Boolean): Boolean {
        if (cancelScheduledRelease) cancelIdleRelease()
        return recognitionMutex.withLock {
            initializationMutex.withLock initializationLock@{
                if (cancelScheduledRelease) cancelIdleRelease()
                val current = ocr ?: return@initializationLock false
                ocr = null
                initialized = false
                lastInferenceTimeMs = -1L
                val startedAt = android.os.SystemClock.elapsedRealtime()
                runCatching {
                    // 一旦开始释放就必须完成，避免新识别取消空闲任务后泄漏旧模型。
                    withContext(NonCancellable) { current.release() }
                }
                    .onSuccess {
                        Log.i(
                            TAG,
                            "PP-OCRv6 Tiny 资源释放完成, reason=$reason, " +
                                "elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms",
                        )
                    }
                    .onFailure { error ->
                        Log.e(TAG, "PP-OCRv6 Tiny 资源释放失败, reason=$reason", error)
                    }
                    .isSuccess
            }
        }
    }

    fun getLastInferenceTime(): Long = lastInferenceTimeMs

    companion object {
        private const val TAG = "PickupOcrEngine"
        private const val RAW_LOG_TAG = "PaddleOcrRaw"
        private const val DET_MODEL_ASSET = "models/ppocrv6_tiny/det/inference.onnx"
        private const val REC_MODEL_ASSET = "models/ppocrv6_tiny/rec/inference.onnx"
        private const val REC_CONFIG_ASSET = "models/ppocrv6_tiny/rec/inference.yml"
        private const val IDLE_RELEASE_DELAY_MS = 60_000L

        /** ORT CPU 推理线程数：与原版缺省一致（原版可经 prefs 调，Eta 固定）。 */
        private const val OCR_NUM_THREADS = 4

        @Volatile
        private var instance: PickupOcrEngine? = null

        fun getInstance(context: Context): PickupOcrEngine =
            instance ?: synchronized(this) {
                instance ?: PickupOcrEngine(context.applicationContext).also { instance = it }
            }
    }
}
