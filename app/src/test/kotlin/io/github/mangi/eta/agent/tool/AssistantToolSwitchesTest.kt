package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.data.model.AssistantProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按助手的工具开关：与记忆/技能同级，但**只能收紧**——助手配置不能把用户全局
 * 关掉的能力重新打开。这与 eta_set_settings 的「模型只能收紧」是同一条原则。
 */
class AssistantToolSwitchesTest {
    private fun assistant(vararg switches: Pair<String, Boolean>) = AssistantProfile(
        id = "a", name = "A", prompt = "", toolGroupSwitches = switches.toMap(),
    )

    @Test fun missingEntryFallsBackToTheGlobalSwitch() {
        val bare = assistant()
        AssistantToolSwitches.all.forEach { group ->
            assertTrue(group, AssistantToolSwitches.allows(bare, group, globalEnabled = true))
            assertFalse(group, AssistantToolSwitches.allows(bare, group, globalEnabled = false))
        }
    }

    @Test fun assistantCanNarrowItsOwnCapabilities() {
        val narrowed = assistant(AssistantToolSwitches.TERMINAL to false)
        assertFalse(AssistantToolSwitches.allows(narrowed, AssistantToolSwitches.TERMINAL, true))
        // 其它组不受影响。
        assertTrue(AssistantToolSwitches.allows(narrowed, AssistantToolSwitches.BROWSER, true))
    }

    @Test fun assistantCannotWidenWhatTheUserDisabledGlobally() {
        // 助手配置写着 true，但用户全局关掉了：仍然是不可用。
        val widened = assistant(
            AssistantToolSwitches.TERMINAL to true,
            AssistantToolSwitches.PERSONAL_DATA to true,
        )
        assertFalse(AssistantToolSwitches.allows(widened, AssistantToolSwitches.TERMINAL, globalEnabled = false))
        assertFalse(
            AssistantToolSwitches.allows(widened, AssistantToolSwitches.PERSONAL_DATA, globalEnabled = false),
        )
    }

    @Test fun everyGroupHasALabelAndAGlobalSwitch() {
        AssistantToolSwitches.all.forEach { group ->
            val label = UiToolGroupLabel.label(group)
            assertTrue("$group 应有可读标签", label.isNotBlank() && label != group)
            // globallyEnabled 必须能回答，不能抛异常。
            UiToolGroupLabel.globallyEnabled(group)
        }
    }

    @Test fun switchingOneAssistantDoesNotAffectAnother() {
        val a = assistant(AssistantToolSwitches.TERMINAL to false)
        val b = assistant()
        assertFalse(AssistantToolSwitches.allows(a, AssistantToolSwitches.TERMINAL, true))
        assertTrue(AssistantToolSwitches.allows(b, AssistantToolSwitches.TERMINAL, true))
    }

    @Test fun profileSerializationRoundTripsTheSwitches() {
        // 存进 JSON 再读回来必须保留：老档案缺这个字段时回落为默认（空 map）。
        val profile = assistant(AssistantToolSwitches.TERMINAL to false, AssistantToolSwitches.BROWSER to true)
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }.encodeToString(AssistantProfile.serializer(), profile)
        val restored = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(AssistantProfile.serializer(), json)
        assertTrue(restored.toolGroupSwitches == profile.toolGroupSwitches)
    }
}
