package io.github.mangi.eta.agent.media.index

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Half
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 进程内唯一的 EmbeddingGemma 2 嵌入引擎封装（LiteRT-LM EmbeddingEngine）。
 *
 * 约定：
 * - **instruction 前缀非对称**：入库用 [DOCUMENT_PREFIX]，查询用 [QUERY_PREFIX]，混用会显著降低召回；
 *   前缀统一由本类拼接，调用方禁止传裸文本给引擎。
 * - **向量归一化 + 截断**：统一用 `EmbeddingOptions(normalize = true, outputSize = dim)`，归一化后
 *   余弦相似度等于点积，检索层只需做点积。
 * - **生命周期**：懒加载 + 空闲卸载；引擎初始化较重（可达数秒），所有调用经 [Mutex] 串行，
 *   避免多线程竞争同一原生引擎。
 * - **后端**：按用户偏好（[InferenceBackend]）构建文本/视觉/音频三路后端；AUTO 依次尝试
 *   GPU → NPU → CPU，显式选择先试所选、失败回退 CPU。生效后端见 [activeBackend]，
 *   发生过回退见 [activeBackendFellBack]。
 * - **索引模式只管后台索引**：低耗档（[IndexMode.POWER_SAVER]）的 CPU 单线程与后端覆盖
 *   仅在 [MediaIndexer.isRunning] 时生效（不抢 GPU、不发热）；交互检索（GUI 与 AI 工具）
 *   始终按用户后端偏好链构建引擎。索引起止使生效档案变化，引擎随之重建（每轮至多两次）。
 * - **预热**：[warmUp] 只初始化不推理，供检索页进入时提前消化冷启动开销。
 */
internal object EtaEmbeddingEngine {
    /** Document Indexing 前缀：所有入库媒体/文本。 */
    const val DOCUMENT_PREFIX = "task: search result | text: "

    /** Search Query 前缀：用户搜索词与 AI 工具的 query。 */
    const val QUERY_PREFIX = "task: search query | text: "

    /**
     * 空闲超过该时长后释放引擎，避免长期占用数百 MB 内存。窗口给足一次浏览会话的
     * 连续检索：隔几分钟搜一次不再付引擎重建的秒级冷启动。
     */
    private const val IDLE_RELEASE_MS = 15 * 60 * 1_000L

    private const val TAG = "EtaEmbeddingEngine"

    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null
    private var engineModelPath: String? = null
    private var enginePreference: InferenceBackend? = null
    private var engineProfile: IndexModeProfile? = null
    private var lastUsedAtMs: Long = 0L

    @Volatile
    private var activeBackendKind: InferenceBackend? = null

    @Volatile
    private var backendFellBack = false

    /** 当前实际生效后端（只读状态）；引擎尚未初始化为 null。 */
    val activeBackend: InferenceBackend? get() = activeBackendKind

    /** 初始化时发生过回退（如显式 GPU 失败落到 CPU）为 true，供设置页标注「已回退」。 */
    val activeBackendFellBack: Boolean get() = backendFellBack

    /** 入库文本：去首尾空白后拼接 document 前缀。 */
    fun documentText(text: String): String = DOCUMENT_PREFIX + text.trim()

    /** 查询文本：去首尾空白后拼接 query 前缀。 */
    fun queryText(text: String): String = QUERY_PREFIX + text.trim()

    /** 文本嵌入（document 侧）。 */
    suspend fun embedDocumentText(context: Context, text: String, dim: Int): FloatArray =
        compute(context, listOf(InputData.Text(documentText(text))), dim)

    /** 文本嵌入（query 侧）。 */
    suspend fun embedQuery(context: Context, text: String, dim: Int): FloatArray =
        compute(context, listOf(InputData.Text(queryText(text))), dim)

    /** 图像嵌入（document 侧）；[bytes] 为 JPEG/PNG 原始字节。 */
    suspend fun embedImage(context: Context, bytes: ByteArray, dim: Int): FloatArray =
        compute(context, listOf(InputData.Text(DOCUMENT_PREFIX), InputData.Image(bytes)), dim)

    /**
     * 视频嵌入（document 侧）：逐帧嵌入后平均再归一化，得到整段视频的聚合向量。
     * 帧时间戳的「时刻检索」在检索侧二段精排完成（见 MediaSearchService），入库仍是每视频一行。
     */
    suspend fun embedVideo(context: Context, frames: List<ByteArray>, dim: Int): FloatArray =
        embedImages(context, frames, dim)

    /**
     * 图像组嵌入（document 侧）：逐张嵌入后平均再归一化。
     * 视频帧序列与 PDF 页图共用该聚合策略（页 = 帧）。
     */
    suspend fun embedImages(context: Context, images: List<ByteArray>, dim: Int): FloatArray {
        require(images.isNotEmpty()) { "图像列表不能为空" }
        return averaged(images.map { image -> embedImage(context, image, dim) })
    }

    /** 音频嵌入（document 侧）；[wav] 为完整 WAV 字节（LiteRT-LM 仅接受 WAV，不收裸 PCM）。 */
    suspend fun embedAudio(context: Context, wav: ByteArray, dim: Int): FloatArray =
        compute(context, listOf(InputData.Text(DOCUMENT_PREFIX), InputData.Audio(wav)), dim)

    /**
     * 音频窗口嵌入（document 侧）：逐窗嵌入后平均再归一化（与视频帧同策略）。
     * [windows] 为切窗后的完整 WAV 字节（见 MediaExtraction.audioWindows）。
     */
    suspend fun embedAudioWindows(context: Context, windows: List<ByteArray>, dim: Int): FloatArray {
        require(windows.isNotEmpty()) { "音频窗口列表不能为空" }
        return averaged(windows.map { window -> embedAudio(context, window, dim) })
    }

    /** 多向量聚合：逐个相加后按模长归一化，保证输出仍是单位向量（余弦 = 点积）。 */
    private fun averaged(vectors: List<FloatArray>): FloatArray {
        val merged = FloatArray(vectors.first().size)
        vectors.forEach { vector ->
            for (i in merged.indices) merged[i] += vector[i]
        }
        val norm = kotlin.math.sqrt(merged.fold(0f) { acc, value -> acc + value * value })
        if (norm > 0f) for (i in merged.indices) merged[i] /= norm
        return merged
    }

    /** 显式释放引擎（如内存告急或切换模型时）。 */
    suspend fun release() = mutex.withLock { closeEngineLocked() }

    /**
     * 预热：只做引擎初始化不做推理，供检索页进入时提前消化冷启动的秒级开销。
     * 模型未就绪返回 false（与首次检索时的报错同源，不额外抛出）；生效后端等状态
     * 随初始化就绪，设置页展示不变。
     */
    suspend fun warmUp(context: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { ensureLoadedLocked(context) }.isSuccess
        }
    }

    private suspend fun compute(
        context: Context,
        contents: List<InputData>,
        dim: Int,
    ): FloatArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engine = ensureLoadedLocked(context)
            val response = engine.computeEmbedding(
                contents,
                EmbeddingOptions(normalize = true, outputSize = dim),
            )
            lastUsedAtMs = SystemClock.elapsedRealtime()
            response.embedding
        }
    }

    private fun ensureLoadedLocked(context: Context): EmbeddingEngine {
        val current = engine
        val path = requireNotNull(EmbeddingModelStore.modelFile(context)) {
            "嵌入模型尚未下载或校验未通过，请先在「媒体搜索」设置页下载模型"
        }
        // 后端偏好或生效档案变化后旧引擎作废：下次按新偏好/新档案重建。
        val preference = EmbeddingModelStore.backend(context)
        // 低耗档的资源收敛（CPU 单线程、后端覆盖）只作用于后台索引管线：MediaIndexer
        // 运行中按档位档案构建引擎；交互检索（GUI 与 AI 工具）按用户偏好链构建，仅在
        // 索引进行中时被动共用低耗引擎（避免来回重建抖动）。抽取与精排预算不受影响，
        // 两侧始终按档取值。
        val mode = EmbeddingModelStore.indexMode(context)
        val profile = if (MediaIndexer.isRunning()) {
            IndexModeProfile.forMode(mode)
        } else {
            IndexModeProfile.forMode(mode).asInteractive()
        }
        if (current != null && engineModelPath == path.absolutePath && enginePreference == preference && engineProfile == profile) {
            if (SystemClock.elapsedRealtime() - lastUsedAtMs < IDLE_RELEASE_MS) return current
            closeEngineLocked()
        } else if (current != null) {
            closeEngineLocked()
        }
        val cacheDir = context.applicationContext.cacheDir.absolutePath
        // 按偏好链依次尝试：任一初始化失败回退下一个候选；全部失败抛最后一次异常。
        // 后台索引的低耗档案经 profile.backendChainOverride 强制 CPU，交互检索走用户偏好链。
        val chain = EmbeddingBackendPolicy.chain(preference, profile.backendChainOverride)
        var lastFailure: Throwable? = null
        for (kind in chain) {
            try {
                val created = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = path.absolutePath,
                        backend = backendOf(context, kind, profile.cpuThreads),
                        visionBackend = backendOf(context, kind, profile.cpuThreads),
                        audioBackend = backendOf(context, kind, profile.cpuThreads),
                        cacheDir = cacheDir,
                    ),
                ).also { it.initialize() }
                engine = created
                engineModelPath = path.absolutePath
                enginePreference = preference
                engineProfile = profile
                activeBackendKind = kind
                backendFellBack = kind != chain.first()
                lastUsedAtMs = SystemClock.elapsedRealtime()
                return created
            } catch (failure: Throwable) {
                lastFailure = failure
                // 显式选择失败时这里标注「已回退」，下一轮尝试链上下一个后端（通常是 CPU）。
                Log.w(TAG, "推理后端 ${kind.wire} 初始化失败，已回退到下一个候选", failure)
            }
        }
        throw lastFailure ?: IllegalStateException("嵌入引擎初始化失败：${preference.wire}")
    }

    /**
     * 后端偏好 → LiteRT-LM 后端实例；文本/视觉/音频三路统一走同一偏好。
     * [cpuThreads] > 0 时 CPU 固定线程数（低耗档单线程，不抢核）；0 交给 LiteRT 默认值。
     */
    private fun backendOf(context: Context, kind: InferenceBackend, cpuThreads: Int = 0): Backend = when (kind) {
        InferenceBackend.GPU -> Backend.GPU()
        // NPU 依赖设备自带的加速库目录，按设备适配；threadCount 交给 LiteRT 默认值。
        InferenceBackend.NPU -> Backend.NPU(context.applicationInfo.nativeLibraryDir)
        InferenceBackend.CPU, InferenceBackend.AUTO ->
            if (cpuThreads > 0) Backend.CPU(threadCount = cpuThreads) else Backend.CPU()
    }

    private fun closeEngineLocked() {
        runCatching { engine?.close() }
        engine = null
        engineModelPath = null
        enginePreference = null
        engineProfile = null
        activeBackendKind = null
        backendFellBack = false
    }
}

/**
 * 推理后端偏好：存 EmbeddingModelStore 的 `backend` 偏好，文本/视觉/音频三路共用。
 * AUTO = GPU → NPU → CPU 依次尝试；显式选择先试所选、失败回退 CPU。
 */
internal enum class InferenceBackend(val wire: String) {
    AUTO("auto"),
    CPU("cpu"),
    GPU("gpu"),
    NPU("npu");

    companion object {
        /** 未知或空偏好一律回落 AUTO，偏好损坏不能影响引擎可用性。 */
        fun from(wire: String?): InferenceBackend = entries.firstOrNull { it.wire == wire } ?: AUTO
    }
}

/**
 * 后端选择策略（纯逻辑，便于单测）：候选链与「按失败序列解析最终后端」。
 *
 * 引擎初始化按 [chain] 顺序尝试，任一失败回退下一个；[resolve] 是同一策略的纯函数表达，
 * 供单测覆盖「给定偏好 + 失败序列 → 最终后端」的判定。
 * [override] 非空时直接取代偏好链（低耗档经 [IndexModeProfile.backendChainOverride]
 * 强制 CPU），偏好链逻辑本身不变。
 */
internal object EmbeddingBackendPolicy {
    /** 最终生效后端；[fellBack] 表示链上更优先的后端初始化失败后回退而来。 */
    data class Selection(val backend: InferenceBackend, val fellBack: Boolean)

    /** 尝试顺序：AUTO = GPU → NPU → CPU；显式选择 = 所选 → CPU（选 CPU 时不重复）。 */
    fun chain(preference: InferenceBackend, override: List<InferenceBackend>? = null): List<InferenceBackend> =
        override ?: when (preference) {
            InferenceBackend.AUTO -> listOf(InferenceBackend.GPU, InferenceBackend.NPU, InferenceBackend.CPU)
            InferenceBackend.CPU -> listOf(InferenceBackend.CPU)
            InferenceBackend.GPU -> listOf(InferenceBackend.GPU, InferenceBackend.CPU)
            InferenceBackend.NPU -> listOf(InferenceBackend.NPU, InferenceBackend.CPU)
        }

    /**
     * 按 [chain] 依次尝试，[failures] 逐项记录每次尝试结果（true = 该次初始化失败），
     * 首次成功（false）即最终生效后端。全部失败或尝试超出链长返回 null（调用方抛异常）。
     */
    fun resolve(
        preference: InferenceBackend,
        failures: List<Boolean>,
        override: List<InferenceBackend>? = null,
    ): Selection? {
        val candidates = chain(preference, override)
        val index = failures.indexOfFirst { !it }
        if (index < 0 || index >= candidates.size) return null
        return Selection(backend = candidates[index], fellBack = index > 0)
    }
}

/**
 * 向量与 Float16 BLOB 的互转：存储用半精度（省一半空间），读取后还原 Float 参与点积。
 */
internal object Float16VectorCodec {
    // 半精度位型统一走 android.util.Half（已存索引的兼容基线）；lint HalfFloat 检查对此处正确用法误报，按误报抑制。
    @SuppressLint("HalfFloat")
    fun encode(vector: FloatArray): ByteArray {
        val bytes = ByteArray(vector.size * 2)
        vector.forEachIndexed { index, value ->
            val bits = Half.toHalf(value)
            bytes[index * 2] = (bits.toInt() and 0xff).toByte()
            bytes[index * 2 + 1] = ((bits.toInt() shr 8) and 0xff).toByte()
        }
        return bytes
    }

    @SuppressLint("HalfFloat")
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0) { "Float16 向量字节数必须为偶数" }
        return FloatArray(bytes.size / 2) { index -> decodeElement(bytes, index) }
    }

    /**
     * 解码单个 Float16 元素（[decode] 的逐元素形态）：供暴力检索复用同一块输出缓冲、
     * 逐行解码后就地点积，免掉逐行分配（万级候选一次检索省下几十 MB 临时对象的 GC 压力）。
     */
    @SuppressLint("HalfFloat")
    fun decodeElement(bytes: ByteArray, index: Int): Float {
        val low = bytes[index * 2].toInt() and 0xff
        val high = bytes[index * 2 + 1].toInt() and 0xff
        return Half.toFloat((low or (high shl 8)).toShort())
    }
}
