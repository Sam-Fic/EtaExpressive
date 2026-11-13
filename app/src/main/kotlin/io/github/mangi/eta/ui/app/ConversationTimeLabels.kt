package io.github.mangi.eta.ui.app

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.temporal.ChronoUnit

internal object ConversationTimeLabels {
    fun label(
        timestampMillis: Long,
        nowMillis: Long = System.currentTimeMillis(),
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault(),
        use24HourClock: Boolean = true,
        yesterdayLabel: String = "Yesterday",
        recentLabel: String = "Recent",
    ): String {
        if (timestampMillis <= 0L) return recentLabel

        val dayDelta = dayDelta(timestampMillis, nowMillis, timeZone)

        return when {
            dayDelta <= 0 -> formatPattern(
                if (use24HourClock) "HH:mm" else "h:mm a",
                timestampMillis,
                locale,
                timeZone,
            )
            dayDelta == 1L -> yesterdayLabel
            dayDelta in 2L..6L -> formatPattern("EEE", timestampMillis, locale, timeZone)
            sameYear(timestampMillis, nowMillis, locale, timeZone) ->
                formatPattern(sameYearPattern(locale), timestampMillis, locale, timeZone)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                .also { it.timeZone = timeZone }
                .format(Date(timestampMillis))
        }
    }

    fun isToday(
        timestampMillis: Long,
        nowMillis: Long,
        timeZone: TimeZone,
    ): Boolean = timestampMillis <= 0L || dayDelta(timestampMillis, nowMillis, timeZone) == 0L

    private fun dayDelta(timestampMillis: Long, nowMillis: Long, timeZone: TimeZone): Long {
        val zoneId = timeZone.toZoneId()
        val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
        val targetDate = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).toLocalDate()
        return ChronoUnit.DAYS.between(targetDate, nowDate)
    }

    private fun sameYear(
        timestampMillis: Long,
        nowMillis: Long,
        locale: Locale,
        timeZone: TimeZone,
    ): Boolean {
        // java.time 直取年份，替代每次 new 两个 Calendar 的旧写法（dayDelta 已是同款）。
        val zoneId = timeZone.toZoneId()
        val nowYear = Instant.ofEpochMilli(nowMillis).atZone(zoneId).year
        val targetYear = Instant.ofEpochMilli(timestampMillis).atZone(zoneId).year
        return nowYear == targetYear
    }

    // (pattern, locale) → SimpleDateFormat 缓存：抽屉全量会话行每次摘要重建都会逐条 label，
    // 现场 new SimpleDateFormat 是最大的重复分配源。SimpleDateFormat 非线程安全，
    // 但本对象全部调用点都在主线程（会话摘要与抽屉行渲染），共享实例安全。
    private val formatterCache = HashMap<Pair<String, Locale>, SimpleDateFormat>()

    private fun formatPattern(
        pattern: String,
        millis: Long,
        locale: Locale,
        timeZone: TimeZone,
    ): String {
        val formatter = synchronized(formatterCache) {
            formatterCache.getOrPut(pattern to locale) { SimpleDateFormat(pattern, locale) }
        }
        // clone 后换时区：SimpleDateFormat 非线程安全，克隆让共享缓存保持干净，
        // 克隆的开销远低于每次现场 new 的 Pattern 解析。
        val clone = formatter.clone() as SimpleDateFormat
        clone.timeZone = timeZone
        return clone.format(Date(millis))
    }

    private fun sameYearPattern(locale: Locale): String = when (locale.language) {
        Locale.CHINESE.language -> "M月d日"
        Locale.JAPANESE.language -> "M月d日"
        Locale.KOREAN.language -> "M월 d일"
        else -> "MMM d"
    }
}
