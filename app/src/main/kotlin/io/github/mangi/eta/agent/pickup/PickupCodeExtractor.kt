// 取餐码/取件码识别统一入口。
// 通道编排移植自 badnng/Hyper-pick-up-code（AGPL-3.0-only）com.Badnng.moe.ocr.TextRecognitionHelper
// 的 recognizeAll（图片通道）与 recognizeFromText（文本通道），只保留
// PickupTwoPassEngine + SimpleRuleRuntime 两层引擎，剔除 Legacy 正则引擎、ML Kit 条码
// 与在线 VLM 识别（Eta 由 Agent 视觉兜底）。合规登记见 docs/THIRD_PARTY_NOTICES.md。

package io.github.mangi.eta.agent.pickup

import android.content.Context
import android.graphics.Bitmap
import io.github.mangi.eta.agent.pickup.ocr.PickupOcrEngine
import io.github.mangi.eta.agent.pickup.ocr.PickupTwoPassEngine
import io.github.mangi.eta.agent.pickup.rules.SimpleRuleRuntime
import io.github.mangi.eta.agent.pickup.rules.SimpleRuleSource
import io.github.mangi.eta.agent.pickup.rules.SimpleRulePack

/** 结构化识别结果：可直接落库或注入 Agent prompt。 */
data class PickupCodeDraft(
    val code: String,
    /** 餐食 / 饮品 / 快递。 */
    val orderType: String,
    val brandName: String?,
    val pickupLocation: String?,
    /** 通道原文（通知正文或 OCR 全文），供入库留痕与人工核对。 */
    val fullText: String?,
)

/**
 * 识别统一入口：
 * - [fromText]：通知 / 纯文本通道，直接走 SimpleRuleRuntime（模板规则 + 内置标签兜底）；
 * - [fromBitmap]：截图通道，OCR 全图识别 → 词汇两步引擎（类型判定 + 聚类裁剪 + 二次识别）
 *   → SimpleRule 兜底。OCR 不可用（模拟器 / 初始化失败）时返回空列表，由调用方
 *   交给 Agent 视觉兜底。
 */
object PickupCodeExtractor {

    /** 两步引擎进程级单例：词汇规则包有文件读取 + 内存缓存，反复 new 会重复落盘读。 */
    @Volatile
    private var cachedTwoPassEngine: PickupTwoPassEngine? = null

    private fun twoPassEngine(context: Context): PickupTwoPassEngine =
        cachedTwoPassEngine ?: synchronized(this) {
            cachedTwoPassEngine ?: PickupTwoPassEngine(context.applicationContext)
                .also { cachedTwoPassEngine = it }
        }

    /** 文本通道：外卖/快递通知、用户粘贴文本。多码场景（列表通知）返回多条。 */
    suspend fun fromText(
        context: Context,
        title: String?,
        text: String?,
        subText: String? = null,
        source: SimpleRuleSource = SimpleRuleSource.NOTIFICATION,
        sourcePackage: String? = null,
    ): List<PickupCodeDraft> {
        val raw = listOfNotNull(title, text, subText)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        if (raw.isBlank()) return emptyList()
        val matches = SimpleRuleRuntime.recognize(
            context = context,
            rawText = raw,
            source = source,
            sourcePackage = sourcePackage,
        )
        return matches.map { match ->
            PickupCodeDraft(
                code = match.code,
                orderType = match.category.resultType,
                brandName = match.brand,
                pickupLocation = match.location,
                fullText = raw,
            )
        }
    }

    /** 图片通道：截图识别。返回页面级裁决后的候选（通常 1 条，多卡列表可多条）。 */
    suspend fun fromBitmap(
        context: Context,
        bitmap: Bitmap,
        sourcePackage: String? = null,
    ): List<PickupCodeDraft> {
        val engine = PickupOcrEngine.getInstance(context)
        val pass1 = engine.recognizeAsync(bitmap)
            ?: return emptyList()

        // 瑞幸二维码在 Eta 无条码扫描通道（ML Kit 未引入），qrData 恒为 null；
        // 瑞幸文本规则仍经 SimpleRule 正常生效。
        val engineResult = twoPassEngine(context).run(
            bitmap = bitmap,
            keepCropBitmap = false,
            existingPass1 = pass1,
        )
        if (engineResult.codes.isNotEmpty()) {
            // 与原版 Router 口径一致：优先页面级裁决后的主候选，其余候选按序跟出。
            return engineResult.selectedCodes.map { candidate ->
                PickupCodeDraft(
                    code = candidate.code,
                    orderType = engineResult.pageType,
                    brandName = engineResult.brand?.name,
                    pickupLocation = null,
                    fullText = engineResult.fullText,
                )
            }
        }

        val matches = SimpleRuleRuntime.recognize(
            context = context,
            rawText = pass1.fullText,
            source = SimpleRuleSource.IMAGE,
            sourcePackage = sourcePackage,
        )
        return matches.map { match ->
            PickupCodeDraft(
                code = match.code,
                orderType = match.category.resultType,
                brandName = match.brand,
                pickupLocation = match.location,
                fullText = pass1.fullText,
            )
        }
    }

    /**
     * 屏幕上下文引用通道：解码 data URL / 本地文件引用为 Bitmap 后走 [fromBitmap]。
     * 助手面板的 screenContextAttachment.image.reference 是 base64 data URL（原生无损档）。
     */
    suspend fun fromScreenContextReference(
        context: Context,
        reference: String,
    ): List<PickupCodeDraft> {
        val bytes = decodeReference(reference) ?: return emptyList()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: return emptyList()
        return try {
            fromBitmap(context, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** data URL（data:image/...;base64,...）与本地文件路径两种引用的解码。 */
    private fun decodeReference(reference: String): ByteArray? {
        val trimmed = reference.trim()
        if (trimmed.startsWith("data:image/", ignoreCase = true)) {
            val marker = "base64,"
            val index = trimmed.indexOf(marker, ignoreCase = true)
            if (index < 0) return null
            return runCatching {
                android.util.Base64.decode(trimmed.substring(index + marker.length), android.util.Base64.DEFAULT)
            }.getOrNull()
        }
        val file = java.io.File(trimmed.removePrefix("file://"))
        return file.takeIf { it.isFile }?.runCatching { readBytes() }?.getOrNull()
    }

    /**
     * 判断规则包是否已就绪（schema v4）。用于诊断与设置页展示。
     */
    suspend fun ensureRulesReady(context: Context): SimpleRulePack =
        SimpleRuleRuntime.ensureLoaded(context)
}
