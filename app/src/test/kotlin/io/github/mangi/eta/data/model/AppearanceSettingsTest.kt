package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun defaultsMatchPreferredAppearance() {
        val settings = AppearanceSettings()

        assertEquals(AppearanceThemeMode.LIGHT, settings.themeMode)
        assertEquals(AppearancePaletteStyle.NEUTRAL, settings.paletteStyle)
        assertEquals(AppearanceAccentColor.SYSTEM, settings.accentColor)
        assertTrue(settings.monetEnabled)
        assertTrue(settings.blurEnabled)
        assertEquals(AppearanceTopBarBlurStyle.GAUSSIAN, settings.topBarBlurStyle)
        assertEquals(false, settings.predictiveBackEnabled)
        assertEquals(DEFAULT_INTERFACE_SCALE, settings.interfaceScale)
    }

    @Test
    fun invalidPersistedEnumsFallBackToStableDefaults() {
        assertEquals(AppearanceThemeMode.LIGHT, AppearanceThemeMode.fromPersistedValue("unknown"))
        assertEquals(AppearancePaletteStyle.NEUTRAL, AppearancePaletteStyle.fromPersistedValue(null))
        assertEquals(AppearanceAccentColor.SYSTEM, AppearanceAccentColor.fromPersistedValue(""))
        assertEquals(
            AppearanceTopBarBlurStyle.GAUSSIAN,
            AppearanceTopBarBlurStyle.fromPersistedValue("future_style"),
        )
    }

    @Test
    fun interfaceScaleIsFiniteAndClamped() {
        assertEquals(MIN_INTERFACE_SCALE, normalizeInterfaceScale(0.2f))
        assertEquals(MAX_INTERFACE_SCALE, normalizeInterfaceScale(2f))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NaN))
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.POSITIVE_INFINITY))
        assertEquals(0.95f, normalizeInterfaceScale(0.95f))
    }

    @Test
    fun pureBlackOnlySurvivesInDarkMode() {
        // 纯黑档本身（深色 + 纯黑标志）原样保留。
        assertTrue(
            AppearanceSettings(
                themeMode = AppearanceThemeMode.DARK,
                pureBlackEnabled = true,
            ).normalized().pureBlackEnabled,
        )
        // 从纯黑切到跟随系统/浅色后残留的标志位必须收敛，
        // 否则「跟随系统」在系统深色时也会走进纯黑背景。
        for (mode in listOf(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.LIGHT)) {
            assertEquals(
                false,
                AppearanceSettings(themeMode = mode, pureBlackEnabled = true)
                    .normalized()
                    .pureBlackEnabled,
            )
        }
    }
}
