package io.github.mangi.eta.ui.screens.stats

import java.text.NumberFormat
import java.util.Locale

/**
 * 缓存命中率。
 *
 * [reported] 为 false 表示 provider 从没上报过 cached_tokens——很多 OpenAI 兼容网关如此。
 * 这时 0% 只代表「没有数据」，不能当成「缓存真的没命中」，所以显示占位而不是 0.0%。
 */
internal fun formatCacheHitRate(
    cachedTokens: Long,
    inputTokens: Long,
    locale: Locale = Locale.getDefault(),
    reported: Boolean = true,
): String {
    if (!reported || inputTokens <= 0L) return "—"
    return NumberFormat.getPercentInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
        // cachedTokens is a subset of inputTokens in every provider's normalized usage, so a
        // ratio above 1 means the data is inconsistent; clamp instead of printing 380%.
    }.format((cachedTokens.coerceIn(0L, inputTokens).toDouble() / inputTokens.toDouble()))
}
