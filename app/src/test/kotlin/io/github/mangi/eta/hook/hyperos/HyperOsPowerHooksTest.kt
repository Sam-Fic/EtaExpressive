package io.github.mangi.eta.hook.hyperos

import io.github.mangi.eta.config.PowerAssistantTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperOsPowerHooksTest {
    @Test
    fun `hyperos intercepts only long press power voice assistant for managed targets`() {
        assertTrue(
            HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.ETA,
            ),
        )
        assertTrue(
            HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.GEMINI,
            ),
        )
    }

    @Test
    fun `hyperos preserves oem and unrelated shortcuts`() {
        assertFalse(
            HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.OEM,
            ),
        )
        assertFalse(
            HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_voice_assistant",
                shortcut = "keyboard",
                target = PowerAssistantTarget.ETA,
            ),
        )
        assertFalse(
            HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistant(
                function = "launch_xiaoai_screen_identification",
                shortcut = "long_press_power_key",
                target = PowerAssistantTarget.ETA,
            ),
        )
    }

    @Test
    fun `hyperos final XiaoAi launch exit follows managed assistant target`() {
        assertTrue(HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistantLaunch(PowerAssistantTarget.ETA))
        assertTrue(HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistantLaunch(PowerAssistantTarget.GEMINI))
        assertFalse(HyperOsPowerHooks.shouldInterceptHyperOsVoiceAssistantLaunch(PowerAssistantTarget.OEM))
    }
}
