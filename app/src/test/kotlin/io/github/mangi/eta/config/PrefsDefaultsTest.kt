package io.github.mangi.eta.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PrefsDefaultsTest {
    @Test
    fun trashRetentionParsingCoversChoiceValuesAndJunk() {
        // 目录四个选项：7/30/90/never。
        assertEquals(7, Prefs.trashRetentionDays("7"))
        assertEquals(30, Prefs.trashRetentionDays("30"))
        assertEquals(90, Prefs.trashRetentionDays("90"))
        assertEquals(0, Prefs.trashRetentionDays("never"))
        // 兼容 eta_set_settings 可能写入的 "0" 表示永不清理。
        assertEquals(0, Prefs.trashRetentionDays("0"))
        // 大小写与空白容忍。
        assertEquals(0, Prefs.trashRetentionDays("  NEVER "))
        assertEquals(14, Prefs.trashRetentionDays(" 14 "))
        // 非整数视为非法回落默认 30 天；越界夹取到 [1, 3650]。
        assertEquals(30, Prefs.trashRetentionDays("0.5"))
        assertEquals(3650, Prefs.trashRetentionDays("99999"))
        assertEquals(30, Prefs.trashRetentionDays("soon"))
        assertEquals(30, Prefs.trashRetentionDays(""))
        assertEquals(1, Prefs.trashRetentionDays("-3"))
    }

    @Test
    fun defaultsMatchRecommendedInitialSettings() {
        assertEquals(
            mapOf(
                Prefs.Keys.POWER_KEY_TAKEOVER to false,
                Prefs.Keys.ASSISTANT_AUTO_CONFIG to false,
                Prefs.Keys.HOTWORD_SELF_HEAL to false,
                Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH to true,
                Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH to false,
                Prefs.Keys.LOCKSCREEN_VOICE_COMMAND to false,
                Prefs.Keys.SCREEN_ON_VOICE_COMMAND to false,
                Prefs.Keys.AGENT_CUSTOM_MODEL to true,
                Prefs.Keys.AGENT_REQUIRE_PREFIX to false,
                Prefs.Keys.AGENT_TERMINAL_TOOLS to true,
                Prefs.Keys.AGENT_SHELL_TOOLS to true,
                Prefs.Keys.AGENT_BROWSER_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS to true,
                Prefs.Keys.AGENT_PERSONAL_DATA_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_STATE_READ_TOOLS to true,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS to true,
                Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS to true,
                Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS to false,
                Prefs.Keys.AGENT_SUBAGENT_TOOLS to true,
                Prefs.Keys.AGENT_CONFIRM_DANGEROUS_TOOLS to false,
                Prefs.Keys.AGENT_THINKING_ENABLED to true,
                Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED to false,
                Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED to false,
                Prefs.Keys.AGENT_TTS_AUTO_READ to false,
                Prefs.Keys.AGENT_TTS_STREAM_SYNTH to true,
                Prefs.Keys.HAPTIC_TOUCH_FEEDBACK to true,
                Prefs.Keys.HAPTIC_TAP to true,
                Prefs.Keys.HAPTIC_LONG_PRESS to true,
                Prefs.Keys.HAPTIC_KEYBOARD to true,
                Prefs.Keys.HAPTIC_MESSAGE_GENERATION to true,
                Prefs.Keys.HAPTIC_TYPING to true,
                Prefs.Keys.HAPTIC_TOOL_STEPS to true,
                Prefs.Keys.HAPTIC_REASONING_DONE to true,
                Prefs.Keys.HAPTIC_BACKGROUND to true,
                Prefs.Keys.HAPTIC_BACKGROUND_REASONING to true,
            ),
            Prefs.Keys.BOOLEAN_DEFAULTS,
        )
        assertFalse(
            Prefs.Keys.BOOLEAN_DEFAULTS.containsKey(Prefs.Keys.POWER_KEY_ASSISTANT_TARGET),
        )
    }

    @Test
    fun localAgentKeysMatchRuntimeOwnedSettings() {
        assertEquals(
            setOf(
                Prefs.Keys.AGENT_TERMINAL_TOOLS,
                Prefs.Keys.AGENT_SHELL_TOOLS,
                Prefs.Keys.AGENT_BROWSER_TOOLS,
                Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                Prefs.Keys.AGENT_PERSONAL_DATA_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_STATE_READ_TOOLS,
                Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                Prefs.Keys.AGENT_SELF_SETTINGS_TOOLS,
                Prefs.Keys.AGENT_MEDIA_SEARCH_TOOLS,
                Prefs.Keys.AGENT_SUBAGENT_TOOLS,
                Prefs.Keys.AGENT_CONFIRM_DANGEROUS_TOOLS,
                Prefs.Keys.AGENT_THINKING_ENABLED,
                Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED,
                Prefs.Keys.AGENT_COMPRESS_CUSTOM_MODEL_ENABLED,
                Prefs.Keys.AGENT_TTS_AUTO_READ,
                Prefs.Keys.AGENT_TTS_STREAM_SYNTH,
                Prefs.Keys.HAPTIC_TOUCH_FEEDBACK,
                Prefs.Keys.HAPTIC_TAP,
                Prefs.Keys.HAPTIC_LONG_PRESS,
                Prefs.Keys.HAPTIC_KEYBOARD,
                Prefs.Keys.HAPTIC_MESSAGE_GENERATION,
                Prefs.Keys.HAPTIC_TYPING,
                Prefs.Keys.HAPTIC_TOOL_STEPS,
                Prefs.Keys.HAPTIC_REASONING_DONE,
                Prefs.Keys.HAPTIC_BACKGROUND,
                Prefs.Keys.HAPTIC_BACKGROUND_REASONING,
            ),
            Prefs.Keys.LOCAL_AGENT_KEYS,
        )
    }
}
