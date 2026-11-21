// 移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）
//   com.Badnng.moe.ocr.RecognitionTextStructure.kt
//   com.Badnng.moe.ocr.OcrReadingOrder.kt
// 合规登记见 docs/THIRD_PARTY_NOTICES.md「Hyper-pick-up-code」一节。

package io.github.mangi.eta.agent.pickup.ocr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 识别框纳入业务结果的置信度下限（SDK 层 recScoreThresh=0，业务阈值在这里执行）。 */
internal const val OCR_MIN_CONFIDENCE = 0.90f

/** 单个 OCR 文本框及其原图坐标（由四点框收敛为外接矩形）。 */
internal data class OcrTextRegion(
    val text: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

/**
 * 阅读序还原：把无序检测框聚合成行，再按行拼接全文。
 * 聚合判据与原版一致：行内垂直重叠率或行高中距双阈值之一满足即可。
 */
internal object OcrReadingOrder {
    private const val MIN_VERTICAL_OVERLAP = 0.45f
    private const val MAX_CENTER_DISTANCE_FACTOR = 0.55f

    fun groupIntoLines(regions: List<OcrTextRegion>): List<List<OcrTextRegion>> {
        if (regions.isEmpty()) return emptyList()

        val lines = mutableListOf<MutableLine>()
        regions
            .filter {
                it.confidence >= OCR_MIN_CONFIDENCE &&
                    it.text.isNotBlank() &&
                    it.right > it.left &&
                    it.bottom > it.top
            }
            .sortedWith(compareBy<OcrTextRegion>({ it.top }, { it.left }))
            .forEach { region ->
                val target = lines
                    .mapNotNull { line ->
                        val overlap = min(line.referenceBottom, region.bottom) -
                            max(line.referenceTop, region.top)
                        val overlapRatio = overlap.coerceAtLeast(0f) / min(line.height, region.height)
                        val centerDistance = abs(line.centerY - region.centerY)
                        val compatible = overlapRatio >= MIN_VERTICAL_OVERLAP ||
                            centerDistance <= max(line.height, region.height) * MAX_CENTER_DISTANCE_FACTOR
                        if (compatible) {
                            LineMatch(line, overlapRatio, centerDistance)
                        } else {
                            null
                        }
                    }
                    .sortedWith(
                        compareByDescending<LineMatch> { it.overlapRatio }
                            .thenBy { it.centerDistance },
                    )
                    .firstOrNull()
                    ?.line

                if (target == null) {
                    lines += MutableLine(region)
                } else {
                    target.add(region)
                }
            }

        return lines
            .sortedWith(compareBy<MutableLine>({ it.top }, { it.left }))
            .map { line -> line.regions.sortedBy(OcrTextRegion::left) }
    }

    fun buildFullText(lines: List<List<OcrTextRegion>>): String = lines
        .mapNotNull { line ->
            line.joinToString(separator = "") { it.text.trim() }.takeIf(String::isNotBlank)
        }
        .joinToString(separator = "\n")

    private data class LineMatch(
        val line: MutableLine,
        val overlapRatio: Float,
        val centerDistance: Float,
    )

    private class MutableLine(first: OcrTextRegion) {
        val regions = mutableListOf(first)
        val left: Float get() = regions.minOf { it.left }
        val top: Float get() = regions.minOf { it.top }
        val centerY: Float get() = regions.map(OcrTextRegion::centerY).average().toFloat()
        val height: Float
            get() {
                val heights = regions.map(OcrTextRegion::height).sorted()
                return heights[heights.size / 2].coerceAtLeast(1f)
            }
        val referenceTop: Float get() = centerY - height / 2f
        val referenceBottom: Float get() = centerY + height / 2f

        fun add(region: OcrTextRegion) {
            regions += region
        }
    }
}

/**
 * 识别文本的结构化预处理（移植自 RecognitionTextStructure.kt）：
 * 按规则包配置做去时间戳、空白折叠、字符剔除与纠错。Eta 第一版只接 SimpleRule
 * 引擎（其内部已做行合并与裁剪），本对象暂无调用方；保留以维持与上游移植文件的
 * 对应关系，接入 Legacy/自定义规则包时启用。
 */
internal object RecognitionTextStructure {
    fun normalizeForCodeMatching(
        text: String,
        datetimePattern: String,
        spaceCollapsePattern: String,
        charRemovals: List<String>,
        corrections: List<Pair<String, String>>,
    ): String {
        var result = text
            .replace(Regex(datetimePattern), "")
            .replace(Regex(spaceCollapsePattern), "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        for (removal in charRemovals) {
            result = result.replace(removal, "")
        }
        for ((from, to) in corrections) {
            result = result.replace(from, to)
        }
        return result
    }

    /** 从「标签：地点」形态的行（或其上一行）提取取件地点候选。 */
    fun trailingLocationCandidates(
        lines: List<String>,
        labels: List<String>,
    ): List<String> {
        if (lines.isEmpty() || labels.isEmpty()) return emptyList()
        val normalizedLines = lines.map { it.replace("\n", "").trim() }
        val candidates = mutableListOf<String>()

        normalizedLines.forEachIndexed { index, line ->
            for (label in labels) {
                if (label.isBlank()) continue
                val labelIndex = line.lastIndexOf(label)
                if (labelIndex < 0) continue
                val sameLine = line.substring(0, labelIndex).trim(' ', ':', '：', '>', '<')
                val candidate = sameLine.ifBlank {
                    normalizedLines.getOrNull(index - 1)
                        ?.trim(' ', ':', '：', '>', '<')
                        .orEmpty()
                }
                if (candidate.isNotBlank()) candidates += candidate
            }
        }
        return candidates.distinct()
    }
}
