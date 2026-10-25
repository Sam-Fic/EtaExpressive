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
    @org.junit.Before
    fun resetGate() {
        // 闸门是进程级单例；每个用例独立起点，避免授权从上一个用例漏过来。
        AgentDangerousConfirmationGate.clear()
    }
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
        // 必须明确「未做任何改动」、要求用 ask_user 取得同意、给出同意选项 id，并禁止绕过。
        assertTrue(decision.message.contains("未做任何改动"))
        assertTrue(decision.message.contains("ask_user"))
        assertTrue(decision.message.contains(AgentDangerousConfirmationGate.CONFIRM_OPTION_ID))
        assertTrue(decision.message.contains("不要换其他工具绕过"))
    }

    @Test fun caseAndWhitespaceAreNormalised() {
        assertTrue(AgentDangerousOperationPolicy.isDangerous(" Power_Control ", """{"action":"REBOOT"}"""))
        assertFalse(AgentDangerousOperationPolicy.isDangerous("power_control", """{"action":" LIST "}"""))
    }


    // ── 「模型自己用 ask_user 问」的完整闭环 ──────────────────────────────────

    @Test fun resendingWithoutAskingIsStillRejected() {
        val args = """{"action":"reboot"}"""
        // 第一次被拒。
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, true) is ToolExecutionDecision.Reject)
        // 没有问用户就重发：仍然被拒，不能靠重试蒙过去。
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, true) is ToolExecutionDecision.Reject)
    }

    @Test fun userApprovalLetsTheSameCallThroughExactlyOnce() {
        val args = """{"action":"reboot"}"""
        AgentDangerousOperationPolicy.decision("power_control", args, true)

        // 用户点了同意选项。
        assertTrue(AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID))

        // 重发同一个调用：放行。
        assertNull(AgentDangerousOperationPolicy.decision("power_control", args, true))
        // 授权只消费一次：再执行一次又要重新确认。
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, true) is ToolExecutionDecision.Reject)
    }

    @Test fun approvalDoesNotTransferToDifferentArgumentsOrTools() {
        AgentDangerousOperationPolicy.decision("manage_app", """{"action":"uninstall","package_name":"com.a"}""", true)
        AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID)

        // 换一个应用卸载：同意过 A 不代表同意 B。
        assertTrue(AgentDangerousOperationPolicy.decision(
            "manage_app", """{"action":"uninstall","package_name":"com.b"}""", true,
        ) is ToolExecutionDecision.Reject)
        // 换一个工具：同样不继承授权。
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", """{"action":"reboot"}""", true)
            is ToolExecutionDecision.Reject)
    }

    @Test fun nonApprovingAnswersDoNotUnlock() {
        val args = """{"action":"reboot"}"""
        listOf(
            "option" to "some_other_option",
            "other" to null,
            "delegate" to null,
        ).forEach { (kind, optionId) ->
            AgentDangerousConfirmationGate.clear()
            AgentDangerousOperationPolicy.decision("power_control", args, true)
            assertFalse("$kind 不构成同意", AgentDangerousConfirmationGate.onUserAnswered(kind, optionId))
            assertTrue("$kind 之后仍应要求确认",
                AgentDangerousOperationPolicy.decision("power_control", args, true) is ToolExecutionDecision.Reject)
        }
    }

    @Test fun answerWithoutAPendingDangerousCallIsIgnored() {
        AgentDangerousConfirmationGate.clear()
        // 普通 ask_user（不是危险操作触发的）不该产生授权。
        assertFalse(AgentDangerousConfirmationGate.onUserAnswered(
            "option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID,
        ))
    }

    @Test fun confirmationMessageTellsTheModelTheExactOptionIdAndToAsk() {
        val message = AgentDangerousOperationPolicy.confirmationMessage("power_control")
        assertTrue(message.contains("ask_user"))
        assertTrue(message.contains(AgentDangerousConfirmationGate.CONFIRM_OPTION_ID))
        assertTrue(message.contains("参数完全相同"))
        assertTrue(message.contains("未做任何改动"))
    }

    @Test fun staleApprovalExpires() {
        val args = """{"action":"reboot"}"""
        AgentDangerousOperationPolicy.decision("power_control", args, true)
        AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID)
        // 模拟授权过期：清理后重发必须重新确认。
        AgentDangerousConfirmationGate.pruneExpired(now = System.currentTimeMillis() + AgentDangerousConfirmationGate.TTL_MS + 1)
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, true) is ToolExecutionDecision.Reject)
    }
}
