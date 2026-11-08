package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

const val MIN_INTERFACE_SCALE = 0.8f
const val MAX_INTERFACE_SCALE = 1.1f
const val DEFAULT_INTERFACE_SCALE = 1f

@Serializable
data class AppearanceSettings(
    val themeMode: AppearanceThemeMode = AppearanceThemeMode.LIGHT,
    val monetEnabled: Boolean = true,
    val paletteStyle: AppearancePaletteStyle = AppearancePaletteStyle.NEUTRAL,
    val accentColor: AppearanceAccentColor = AppearanceAccentColor.SYSTEM,
    val pureBlackEnabled: Boolean = false,
    val blurEnabled: Boolean = true,
    val topBarBlurStyle: AppearanceTopBarBlurStyle = AppearanceTopBarBlurStyle.GAUSSIAN,
    val predictiveBackEnabled: Boolean = false,
    /** 边缘横移返回上一页；交给 miuix NavDisplay 的 entry.swipeDismiss，不依赖系统 API 等级。 */
    val swipeBackEnabled: Boolean = true,
    val interfaceScale: Float = DEFAULT_INTERFACE_SCALE,
    val morphLoadingIndicator: Boolean = true,
    val morphLoadingBeforeResponseOnly: Boolean = false,
    val messageTimestampsEnabled: Boolean = false,
    /**
     * 本轮已经给出正文回答、且生成停下之后，回答之前的内容怎么收（档位见 [AnswerFoldLevel]）。
     *
     * 用户手动点开/收起过的分组始终优先于本策略；[AnswerFoldLevel.NONE] 则完全不自动收起。
     */
    val answerFoldLevel: AnswerFoldLevel = AnswerFoldLevel.AGGRESSIVE,
    val iconLightColor: Int = DEFAULT_ICON_LIGHT,
    val iconDarkColor: Int = DEFAULT_ICON_DARK,
) {
    fun normalized(): AppearanceSettings = copy(
        monetEnabled = true,
        interfaceScale = normalizeInterfaceScale(interfaceScale),
        iconLightColor = iconLightColor or 0xFF000000.toInt(),
        iconDarkColor = iconDarkColor or 0xFF000000.toInt(),
        // 纯黑 = 「深色 + 纯黑背景」这一档模式的标志位，只在 themeMode == DARK 时有意义。
        // 旧实现从纯黑切到跟随系统/浅色只覆写 themeMode，会残留 true，导致「跟随系统」
        // 在系统深色时也走进纯黑背景；这里在唯一的读写汇聚点把非法组合收敛掉，
        // 存量残留随下一次读设置自愈，预览卡与真实主题消费归一化数据后自然一致。
        pureBlackEnabled = pureBlackEnabled && themeMode == AppearanceThemeMode.DARK,
    )

    companion object {
        const val DEFAULT_ICON_LIGHT = 0xFFFFFFFF.toInt()
        const val DEFAULT_ICON_DARK = 0xFF1C1C1E.toInt()
    }
}

@Serializable
enum class AppearanceThemeMode(val persistedValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceThemeMode =
            entries.firstOrNull { it.persistedValue == value } ?: LIGHT
    }
}

@Serializable
enum class AppearancePaletteStyle(val persistedValue: String) {
    TONAL_SPOT("tonal_spot"),
    NEUTRAL("neutral"),
    VIBRANT("vibrant"),
    EXPRESSIVE("expressive"),
    RAINBOW("rainbow"),
    FRUIT_SALAD("fruit_salad"),
    MONOCHROME("monochrome"),
    FIDELITY("fidelity"),
    CONTENT("content");

    companion object {
        fun fromPersistedValue(value: String?): AppearancePaletteStyle =
            entries.firstOrNull { it.persistedValue == value } ?: NEUTRAL
    }
}

@Serializable
enum class AppearanceAccentColor(val persistedValue: String) {
    SYSTEM("system"),
    BLUE("blue"),
    PURPLE("purple"),
    PINK("pink"),
    RED("red"),
    ORANGE("orange"),
    YELLOW("yellow"),
    GREEN("green"),
    TEAL("teal");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceAccentColor =
            entries.firstOrNull { it.persistedValue == value } ?: SYSTEM
    }
}

@Serializable
enum class AppearanceTopBarBlurStyle(val persistedValue: String) {
    GAUSSIAN("gaussian"),
    PROGRESSIVE("progressive");

    companion object {
        fun fromPersistedValue(value: String?): AppearanceTopBarBlurStyle =
            entries.firstOrNull { it.persistedValue == value } ?: GAUSSIAN
    }
}

/**
 * 「回答出现后收起本轮内容」的分级。枚举顺序即设置页选项顺序。
 *
 * 三档共用同一套收起时机：更早的轮次只要已出现过正文回答就收（生成过程中也保持
 * 收起，不随新一轮生成重新摊开）；最新一轮只在整轮停下（非生成、非暂停、非压缩中）
 * 后收，生成过程摊开看进度。手动点过的分组永远优先。差别只在收起的范围：
 */
@Serializable
enum class AnswerFoldLevel(val persistedValue: String) {
    /**
     * 激进：只保留用户消息与本轮最后一条正文回复；中间的文字回复、提示、重连等
     * 与思考/工具步骤并成同一条工作过程卡片，点开可读全部内容。
     */
    AGGRESSIVE("aggressive"),

    /** 普通：只把思考与工具步骤收进「已完成 N 个步骤」卡片，中间文字回复照常显示。 */
    NORMAL("normal"),

    /** 不折叠：始终摊开，只有运行中/尾部跟随等旧有默认展开规则。 */
    NONE("none");

    companion object {
        fun fromPersistedValue(value: String?): AnswerFoldLevel =
            entries.firstOrNull { it.persistedValue == value } ?: AGGRESSIVE
    }
}

fun normalizeInterfaceScale(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
    } else {
        DEFAULT_INTERFACE_SCALE
    }
