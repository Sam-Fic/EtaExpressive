package io.github.mangi.eta.ui.app

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearancePaletteStyle

/** 对比度（0.0 = 标准）。 */
private const val CONTRAST_LEVEL = 0.0

/**
 * 取色工厂：把「外观与主题」里的强调色 / 配色风格 / 深浅色解析成 Material 3 [ColorScheme]。
 *
 * 取色链使用 material-color-utilities 的 Spec2025 方案：
 * - `SYSTEM` 强调色：Android 12+ 取系统动态色的 primary 作为种子，低版本回退到蓝色种子；
 * - 固定强调色：直接使用该强调色的预设种子；
 * - 种子 + [AppearancePaletteStyle] → 对应的 `Scheme*` 变体（与旧 `ThemePaletteStyle` 一一对应）。
 *
 * tone→role 全部使用官方 M3 角色（页面背景 = `surfaceContainer`、分段条目 = `surfaceBright`），
 * 因此「外观与主题」的三层色阶布局保持不变；纯黑开关在暗色下把页面背景角色
 * （background / surface / surfaceContainer / surfaceDim）压黑，嵌套角色保留色阶。
 */
@Composable
internal fun rememberEtaColorScheme(
    accentColor: AppearanceAccentColor,
    paletteStyle: AppearancePaletteStyle,
    isDark: Boolean,
    pureBlackEnabled: Boolean,
): ColorScheme {
    val context = LocalContext.current
    val seedArgb = remember(context, accentColor, isDark) {
        resolveSeedArgb(context, accentColor, isDark)
    }
    return remember(seedArgb, paletteStyle, isDark, pureBlackEnabled) {
        dynamicScheme(paletteStyle, seedArgb, isDark).toEtaColorScheme(isDark, pureBlackEnabled)
    }
}

/** 解析强调色种子（ARGB int）。 */
private fun resolveSeedArgb(
    context: Context,
    accentColor: AppearanceAccentColor,
    isDark: Boolean,
): Int =
    if (accentColor == AppearanceAccentColor.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // 系统动态取色只在 Android 12+ 可用；取到的 primary 即旧实现的 monet 种子。
        val dynamic = if (isDark) {
            dynamicDarkColorScheme(context)
        } else {
            dynamicLightColorScheme(context)
        }
        dynamic.primary.toArgb()
    } else {
        accentColor.seedColor().toArgb()
    }

/** 由配色风格选择对应的 DynamicScheme 变体（Spec2025）。 */
private fun dynamicScheme(
    paletteStyle: AppearancePaletteStyle,
    seedArgb: Int,
    isDark: Boolean,
): DynamicScheme {
    val source = Hct.fromInt(seedArgb)
    val spec = ColorSpec.SpecVersion.SPEC_2025
    val platform = DynamicScheme.Platform.PHONE
    return when (paletteStyle) {
        AppearancePaletteStyle.TONAL_SPOT -> SchemeTonalSpot(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.NEUTRAL -> SchemeNeutral(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.VIBRANT -> SchemeVibrant(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.EXPRESSIVE -> SchemeExpressive(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.RAINBOW -> SchemeRainbow(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.FRUIT_SALAD -> SchemeFruitSalad(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.MONOCHROME -> SchemeMonochrome(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.FIDELITY -> SchemeFidelity(source, isDark, CONTRAST_LEVEL, spec, platform)
        AppearancePaletteStyle.CONTENT -> SchemeContent(source, isDark, CONTRAST_LEVEL, spec, platform)
    }
}

/**
 * DynamicScheme → 官方 Material 3 [ColorScheme]（全部使用标准色角色）。
 *
 * 「外观与主题」预览卡（AppearanceSettingsScreen）与真实主题共用本映射，
 * 保证预览与点下去之后的实际配色逐项一致；不要在本函数之外再写第二份映射。
 */
internal fun DynamicScheme.toEtaColorScheme(
    isDark: Boolean,
    pureBlackEnabled: Boolean,
): ColorScheme {
    val scheme = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
        primary = primary.toColor(),
        onPrimary = onPrimary.toColor(),
        primaryContainer = primaryContainer.toColor(),
        onPrimaryContainer = onPrimaryContainer.toColor(),
        inversePrimary = inversePrimary.toColor(),
        secondary = secondary.toColor(),
        onSecondary = onSecondary.toColor(),
        secondaryContainer = secondaryContainer.toColor(),
        onSecondaryContainer = onSecondaryContainer.toColor(),
        tertiary = tertiary.toColor(),
        onTertiary = onTertiary.toColor(),
        tertiaryContainer = tertiaryContainer.toColor(),
        onTertiaryContainer = onTertiaryContainer.toColor(),
        background = background.toColor(),
        onBackground = onBackground.toColor(),
        surface = surface.toColor(),
        onSurface = onSurface.toColor(),
        surfaceVariant = surfaceVariant.toColor(),
        onSurfaceVariant = onSurfaceVariant.toColor(),
        surfaceTint = primary.toColor(),
        inverseSurface = inverseSurface.toColor(),
        inverseOnSurface = inverseOnSurface.toColor(),
        error = error.toColor(),
        onError = onError.toColor(),
        errorContainer = errorContainer.toColor(),
        onErrorContainer = onErrorContainer.toColor(),
        outline = outline.toColor(),
        outlineVariant = outlineVariant.toColor(),
        scrim = scrim.toColor(),
        surfaceBright = surfaceBright.toColor(),
        surfaceDim = surfaceDim.toColor(),
        surfaceContainer = surfaceContainer.toColor(),
        surfaceContainerHigh = surfaceContainerHigh.toColor(),
        surfaceContainerHighest = surfaceContainerHighest.toColor(),
        surfaceContainerLow = surfaceContainerLow.toColor(),
        surfaceContainerLowest = surfaceContainerLowest.toColor(),
    )
    return if (isDark && pureBlackEnabled) {
        // 纯黑（AMOLED）：把充当页面背景的角色全部压黑。Spec2025 布局下页面背景
        // 是 surfaceContainer（主 Scaffold / 顶栏都用它），background / surface 只是
        // 兜底；surfaceDim 是背景的暗色变体。卡片 / 输入 / 分隔等嵌套角色
        // （ContainerLow/Lowest/High/Highest、Bright、Variant、OutlineVariant）保留
        // 原 tone——纯黑底上色阶层级依然可辨，纯黑指「页面底」而非「所有面」。
        scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color.Black,
            surfaceDim = Color.Black,
        )
    } else {
        scheme
    }
}

private fun Int.toColor(): Color = Color(this)

/** 强调色的预设种子（外观页色板 / 预览与真实主题共用的唯一真相源）。 */
internal fun AppearanceAccentColor.seedColor(): Color = when (this) {
    AppearanceAccentColor.SYSTEM, AppearanceAccentColor.BLUE -> Color(0xFF3482FF)
    AppearanceAccentColor.PURPLE -> Color(0xFF6750A4)
    AppearanceAccentColor.PINK -> Color(0xFFB0006D)
    AppearanceAccentColor.RED -> Color(0xFFBA1A1A)
    AppearanceAccentColor.ORANGE -> Color(0xFFB65D00)
    AppearanceAccentColor.YELLOW -> Color(0xFF7D5700)
    AppearanceAccentColor.GREEN -> Color(0xFF006D3B)
    AppearanceAccentColor.TEAL -> Color(0xFF006A6A)
}
