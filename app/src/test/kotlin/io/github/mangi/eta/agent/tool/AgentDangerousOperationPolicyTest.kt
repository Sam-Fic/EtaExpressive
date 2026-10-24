package io.github.mangi.eta.agent.tool

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危险操作逐次确认：只在用户开启时生效，只作用于写操作，
 * 且读类动作即使挂在同一个工具名下也不会被打扰。
 */
class AgentDangerousOperationPolicyTest {
    @Test fun destructiveWritesRequireConfirmation() {
        val cases = listOf(
            "power_control" to """{"action":"reboot"}""",
            "manage_app" to """{"action":"uninstall","package_name":"x"}""",
            "package_control" to """{"action":"disable"}""",
            "user_control" to """{"action":"remove","user_id":10}""",
            "manage_file" to """{"action":"delete","path":"/sdcard/a"}""",
            "set_device_state" to """{"target":"airplane_mode","enabled":true}""",
        )
        cases.forEach { (name, args) ->
            assertTrue("$name 应被判定为危险", AgentDangerousOperationPolicy.isDangerous(name, args))
            val decision = AgentDangerousOperationPolicy.decision(name, args, confirmationEnabled = true)
            assertEquals(
                AgentDangerousOperationPolicy.CONFIRM_CODE,
                (decision as ToolExecutionDecision.Reject).code,
            )
        }
    }

    @Test fun readOnlyActionsOnTheSameToolAreNotInterrupted() {
        // 查看类动作挂在同名工具下，不该为它们弹确认。
        listOf(
            "user_control" to """{"action":"list"}""",
            "user_control" to """{"action":"current"}""",
            "package_control" to """{"action":"list"}""",
            "manage_app" to """{"action":"get"}""",
            "display_control" to """{"action":"reset_resolution"}""",
        ).forEach { (name, args) ->
            assertFalse("$name/$args 不该被判定为危险",
                AgentDangerousOperationPolicy.isDangerous(name, args))
            assertNull(
                AgentDangerousOperationPolicy.decision(name, args, confirmationEnabled = true),
            )
        }
    }

    @Test fun ordinaryToolsNeverAsk() {
        listOf("read_file", "web_search", "observe_screen", "tap_element", "memory_write").forEach { name ->
            assertFalse(name, AgentDangerousOperationPolicy.isDangerous(name, "{}"))
            assertNull(name, AgentDangerousOperationPolicy.decision(name, "{}", confirmationEnabled = true))
        }
    }

    @Test fun disabledSwitchKeepsHistoricalBehaviour() {
        // 用户没开启确认时，危险操作照旧直接执行——这是历史行为，不能擅自改变。
        val noDecision = AgentDangerousOperationPolicy.decision(
            "power_control", """{"action":"reboot"}""", confirmationEnabled = false,
        )
        assertNull(noDecision)
    }

    @Test fun malformedArgumentsDoNotCrashAndDefaultToAsking() {
        // 参数不是合法 JSON 时无法证明它无害：保守地按危险处理（提示里也要求先说明）。
        assertTrue(AgentDangerousOperationPolicy.isDangerous("power_control", "not-json"))
        assertTrue(AgentDangerousOperationPolicy.isDangerous("power_control", ""))
    }

    @Test fun rejectionMessageTellsTheModelHowToProceed() {
        val decision = AgentDangerousOperationPolicy.decision(
            "manage_app", """{"action":"clear_data"}""", confirmationEnabled = true,
        ) as ToolExecutionDecision.Reject
        // 必须明确「未做任何改动」与「取得同意后重发同一个调用」，并禁止绕过。
        assertTrue(decision.message.contains("未做任何改动"))
        assertTrue(decision.message.contains("重新发起同一个调用"))
        assertTrue(decision.message.contains("不要换其他工具绕过"))
    }

    @Test fun caseAndWhitespaceAreNormalised() {
        assertTrue(AgentDangerousOperationPolicy.isDangerous(" Power_Control ", """{"action":"REBOOT"}"""))
        assertFalse(AgentDangerousOperationPolicy.isDangerous("power_control", """{"action":" LIST "}"""))
    }
}
