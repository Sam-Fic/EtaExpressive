package io.github.mangi.eta.agent.media.index

import io.github.mangi.eta.R

/**
 * 索引模式：两档资源/速度取舍，供 [MediaIndexer]、[EtaEmbeddingEngine] 与 [MediaSearchService] 按档取参。
 *
 * - [PERFORMANCE] 全力索引：保持既有行为（GPU 优先后端、批提交 32、无节流、抽取预算满配、
 *   视频命中精排 top-5），最快把索引建完。
 * - [POWER_SAVER] 低耗后台：CPU 单线程 + 条目间节流 + 抽取预算减配 + 精排放宽，
 *   可长时间挂在后台慢慢跑完，不抢 GPU、不发热（CPU 收敛仅作用于后台索引管线，
 *   交互检索按用户后端偏好链构建引擎，见 [IndexModeProfile.asInteractive]）。
 *
 * 档位参数集中在 [IndexModeProfile]（纯函数），切换档位即时落库、下一轮索引起生效。
 */
internal enum class IndexMode(
    val wire: String,
    /** 档位名（设置页单选行标题、后台通知摘要共用同一套文案）。 */
    val labelRes: Int,
    /** 一句说明（设置页单选行摘要）。 */
    val summaryRes: Int,
) {
    PERFORMANCE("performance", R.string.media_mode_performance, R.string.media_mode_performance_summary),
    POWER_SAVER("power_saver", R.string.media_mode_power_saver, R.string.media_mode_power_saver_summary);

    companion object {
        /**
         * 默认档 = [POWER_SAVER]：索引常态是后台/熄屏长任务，低耗档不抢 GPU、不发热、
         * 可无感挂机跑完；首次索引慢一些但更省电，追求速度的用户可切 [PERFORMANCE]。
         */
        val DEFAULT: IndexMode = POWER_SAVER

        /** 未知或空偏好一律回落 [DEFAULT]，偏好损坏不能影响索引可用性。 */
        fun from(wire: String?): IndexMode = entries.firstOrNull { it.wire == wire } ?: DEFAULT
    }
}

/**
 * 索引模式档位参数（纯逻辑，便于单测）：批提交、条目间节流、抽取预算与检索精排的取值都从这里来。
 *
 * [PERFORMANCE] 档直接取 [MediaExtraction] 的全局常量（默认值即全力档预算），
 * 低耗档在其基础上减配；两档差异只体现在本数据类字段上，调用方不再散落魔法数。
 */
internal data class IndexModeProfile(
    /** 每批落库的条目数：避免长事务，也避免每条一提交的开销。 */
    val batchSize: Int,
    /** 条目间节流延时（毫秒）：处理（抽取/嵌入）过的条目之间歇一拍，0 表示不节流。 */
    val throttleDelayMs: Long,
    /** 单个视频最多抽取的帧数；逐帧嵌入后聚合成一个向量。 */
    val videoFrames: Int,
    /** 嵌入输入图像（含 PDF 页图）的最长边。 */
    val imageMaxEdge: Int,
    /** PDF 页图嵌入的页数上限。 */
    val maxPdfPages: Int,
    /** 视频命中「时刻检索」二段精排的条数上限。 */
    val refineTopK: Int,
    /** 强制 CPU 后端时的线程数；0 = 交 LiteRT 默认值（仅在 [backendChainOverride] 强制 CPU 时生效）。 */
    val cpuThreads: Int,
    /** 后端尝试链覆盖：非空时取代用户后端偏好（低耗档强制 [InferenceBackend.CPU]）；null 按偏好链走。 */
    val backendChainOverride: List<InferenceBackend>?,
) {
    /**
     * 交互检索视图：低耗档的 CPU 单线程与后端覆盖只属于后台索引管线（引擎在
     * MediaIndexer 运行中才取原档案，见 [EtaEmbeddingEngine]），交互侧按用户后端
     * 偏好构建引擎；抽取与精排预算仍按档取值，不受本视图影响。
     */
    fun asInteractive(): IndexModeProfile = copy(backendChainOverride = null, cpuThreads = 0)
    companion object {
        /** 全力档批提交条目数（与旧版 MediaIndexer.BATCH_SIZE 一致）。 */
        const val PERFORMANCE_BATCH_SIZE = 32

        /** 全力档视频命中精排条数。 */
        const val PERFORMANCE_REFINE_TOP_K = 5

        /** 低耗档批提交条目数。 */
        const val POWER_SAVER_BATCH_SIZE = 8

        /** 低耗档条目间节流延时（毫秒），常量可调。 */
        const val POWER_SAVER_THROTTLE_MS = 300L

        /** 低耗档图像最长边：较 [MediaExtraction.MAX_IMAGE_EDGE] 略降。 */
        const val POWER_SAVER_IMAGE_EDGE = 640

        /** 低耗档视频命中精排条数。 */
        const val POWER_SAVER_REFINE_TOP_K = 3

        /** 低耗档 CPU 线程数：单线程不抢核、不发热。 */
        const val POWER_SAVER_CPU_THREADS = 1

        /**
         * 档位 → 参数（纯函数）：
         * [IndexMode.PERFORMANCE] 满配（[MediaExtraction] 常量即全力档预算），
         * [IndexMode.POWER_SAVER] 批 8 / 节流 300ms / 视频帧减半 / 图像边 640 / 页数减半 / 精排 top-3 / CPU 单线程。
         */
        fun forMode(mode: IndexMode): IndexModeProfile = when (mode) {
            IndexMode.PERFORMANCE -> IndexModeProfile(
                batchSize = PERFORMANCE_BATCH_SIZE,
                throttleDelayMs = 0L,
                videoFrames = MediaExtraction.MAX_VIDEO_FRAMES,
                imageMaxEdge = MediaExtraction.MAX_IMAGE_EDGE,
                maxPdfPages = MediaExtraction.MAX_PDF_PAGES,
                refineTopK = PERFORMANCE_REFINE_TOP_K,
                cpuThreads = 0,
                backendChainOverride = null,
            )
            IndexMode.POWER_SAVER -> IndexModeProfile(
                batchSize = POWER_SAVER_BATCH_SIZE,
                throttleDelayMs = POWER_SAVER_THROTTLE_MS,
                videoFrames = MediaExtraction.MAX_VIDEO_FRAMES / 2,
                imageMaxEdge = POWER_SAVER_IMAGE_EDGE,
                maxPdfPages = MediaExtraction.MAX_PDF_PAGES / 2,
                refineTopK = POWER_SAVER_REFINE_TOP_K,
                cpuThreads = POWER_SAVER_CPU_THREADS,
                backendChainOverride = listOf(InferenceBackend.CPU),
            )
        }
    }
}
