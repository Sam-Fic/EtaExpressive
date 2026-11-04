package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 危险操作逐次确认：中风险受设置开关控制，高风险/致命不受控制，
 * 纯读类动作即使挂在同名工具下也不会被打扰。
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
            val decision = AgentDangerousOperationPolicy.decision(name, args, mode = AgentToolReviewMode.STANDARD)
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
                AgentDangerousOperationPolicy.decision(name, args, mode = AgentToolReviewMode.STANDARD),
            )
        }
    }

    @Test fun ordinaryToolsNeverAsk() {
        listOf("read_file", "web_search", "observe_screen", "tap_element", "memory_write").forEach { name ->
            assertFalse(name, AgentDangerousOperationPolicy.isDangerous(name, "{}"))
            assertNull(name, AgentDangerousOperationPolicy.decision(name, "{}", mode = AgentToolReviewMode.STANDARD))
        }
    }

    @Test fun autoModeSkipsMediumRiskButKeepsTheBottomLine() {
        // 自动档：中风险直接执行——这是原「危险操作确认」开关关闭的历史行为。
        val noDecision = AgentDangerousOperationPolicy.decision(
            "set_timezone", """{"action":"set","timezone":"Asia/Shanghai"}""", mode = AgentToolReviewMode.AUTO,
        )
        assertNull(noDecision)
    }

    @Test fun fatalOperationsAlwaysAskEvenInAutoMode() {
        // 致命操作不可撤销：任何档位（含自动）都始终确认，这是安全底线。
        listOf(
            "power_control" to """{"action":"reboot"}""",
            "manage_app" to """{"action":"clear_data","package_name":"x"}""",
            "manage_file" to """{"action":"delete","path":"/sdcard/a"}""",
            "memory_write" to """{"mode":"clear"}""",
            "eta_manage_history" to """{"action":"empty_trash"}""",
        ).forEach { (name, args) ->
            val decision = AgentDangerousOperationPolicy.decision(name, args, mode = AgentToolReviewMode.AUTO)
            assertEquals(
                "$name 应在自动档仍被拦下",
                AgentDangerousOperationPolicy.CONFIRM_CODE,
                (decision as ToolExecutionDecision.Reject).code,
            )
        }
    }

    @Test fun highRiskOperationsAlsoAskEvenInAutoMode() {
        // 递归删除、git 破坏、批量删除属于高风险：不可撤销，自动档也不能跳过确认。
        val shell = AgentDangerousOperationPolicy.decision(
            "terminal", """{"action":"exec","command":"rm -rf build"}""", mode = AgentToolReviewMode.AUTO,
        )
        assertTrue(shell is ToolExecutionDecision.Reject)
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "manage_file", """{"action":"move","path":"/sdcard/a"}""", mode = AgentToolReviewMode.AUTO,
            ) is ToolExecutionDecision.Reject,
        )
        // 中风险在自动档免确认。
        assertNull(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", """{"action":"set","timezone":"UTC"}""", mode = AgentToolReviewMode.AUTO,
            ),
        )
    }

    @Test fun malformedArgumentsDoNotCrashAndDefaultToAsking() {
        // 参数不是合法 JSON 时无法证明它无害：保守地按危险处理（提示里也要求先说明）。
        assertTrue(AgentDangerousOperationPolicy.isDangerous("power_control", "not-json"))
        assertTrue(AgentDangerousOperationPolicy.isDangerous("power_control", ""))
    }

    @Test fun rejectionMessageTellsTheModelHowToProceed() {
        val decision = AgentDangerousOperationPolicy.decision(
            "manage_app", """{"action":"clear_data"}""", mode = AgentToolReviewMode.STANDARD,
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

    // ── 确认粒度：本次允许 / 本会话允许 ────────────────────────────────────────

    @Test fun mediumRiskCanBeAllowedForTheWholeSession() {
        val args = """{"action":"set","timezone":"Asia/Shanghai"}"""
        assertTrue(AgentDangerousOperationPolicy.decision("set_timezone", args, true) is ToolExecutionDecision.Reject)
        assertEquals(
            AgentConfirmationScope.SESSION,
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID,
            ),
        )
        // 本会话授权可重复使用，不消费。
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, true))
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, true))
        // 换参数仍然要重新确认。
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", """{"action":"set","timezone":"UTC"}""", true,
            ) is ToolExecutionDecision.Reject,
        )
    }

    @Test fun fatalRiskNeverGrantsSessionScope() {
        val args = """{"action":"reboot"}"""
        AgentDangerousOperationPolicy.decision("power_control", args, true)
        // 即使模型给了「本会话允许」选项，致命操作也只授权一次。
        assertEquals(
            AgentConfirmationScope.ONCE,
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID,
            ),
        )
        assertNull(AgentDangerousOperationPolicy.decision("power_control", args, true))
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, true) is ToolExecutionDecision.Reject)
    }

    @Test fun cancelOptionNeverGrants() {
        val args = """{"action":"reboot"}"""
        AgentDangerousOperationPolicy.decision("power_control", args, true)
        assertNull(
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CANCEL_OPTION_ID,
            ),
        )
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, true) is ToolExecutionDecision.Reject)
    }

    @Test fun shellToolsAreAssessedBeforeExecution() {
        // 终端/命令原先完全不在确认清单里：模型可以直接 rm -rf。
        val args = """{"action":"exec","command":"rm -rf /sdcard/Download"}"""
        assertTrue(AgentDangerousOperationPolicy.isDangerous("terminal", args))
        val decision = AgentDangerousOperationPolicy.decision("terminal", args, mode = AgentToolReviewMode.STANDARD)
        assertTrue(decision is ToolExecutionDecision.Reject)
        assertTrue((decision as ToolExecutionDecision.Reject).message.contains("递归删除"))
    }

    @Test fun unknownToolsWithDestructiveNamesOrActionsAreNotSilentlyAllowed() {
        // 新工具没有登记风险等级时，名字/动作里的不可逆动词仍要兜底确认一次。
        assertEquals(
            AgentRiskLevel.MEDIUM,
            AgentRiskPolicy.assess("mystery_tool", """{"action":"delete"}""").level,
        )
        assertEquals(AgentRiskLevel.MEDIUM, AgentRiskPolicy.assess("purge_cache_v2", "{}").level)
        // 只读的未知工具不被打扰。
        assertEquals(AgentRiskLevel.LOW, AgentRiskPolicy.assess("mystery_tool", """{"action":"inspect"}""").level)
    }

    @Test fun recipeShellStepsShareTheSameGate() {
        val args = """{"path":"/sdcard"}"""
        val first = AgentDangerousOperationPolicy.decisionForRecipe(
            "recipe_clean", args, listOf("rm -rf /sdcard/Download"), mode = AgentToolReviewMode.STANDARD,
        )
        assertTrue(first is ToolExecutionDecision.Reject)
        AgentDangerousConfirmationGate.onUserAnsweredScoped(
            "option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID,
        )
        // 同一配方 + 同一参数重发才放行，换参数仍需确认。
        assertNull(
            AgentDangerousOperationPolicy.decisionForRecipe(
                "recipe_clean", args, listOf("rm -rf /sdcard/Download"), mode = AgentToolReviewMode.STANDARD,
            ),
        )
        assertTrue(
            AgentDangerousOperationPolicy.decisionForRecipe(
                "recipe_clean", """{"path":"/storage"}""", listOf("rm -rf /sdcard/Download"), true,
            ) is ToolExecutionDecision.Reject,
        )
    }
}

    // ── 审查模式四档（对齐 ZCode 权限模式光谱） ────────────────────────────────

    @Test fun readOnlyModeDeclinesNonLowRiskWithoutAskPath() {
        // 只读档表达的是「这次别做」：非低风险直接拒绝，不提供 ask_user 授权路径。
        listOf(
            "set_timezone" to """{"action":"set","timezone":"UTC"}""",
            "terminal" to """{"action":"exec","command":"rm -rf build"}""",
            "power_control" to """{"action":"reboot"}""",
        ).forEach { (name, args) ->
            val decision = AgentDangerousOperationPolicy.decision(name, args, mode = AgentToolReviewMode.READONLY)
            assertEquals(
                "$name 在只读档应被直接拒绝",
                AgentDangerousOperationPolicy.READ_ONLY_CODE,
                (decision as ToolExecutionDecision.Reject).code,
            )
            // 不给确认选项 id：模型不该尝试 ask_user 求授权。
            assertFalse(decision.message.contains(AgentDangerousConfirmationGate.CONFIRM_OPTION_ID))
        }
        // 低风险与纯读调用在只读档照常执行。
        assertNull(
            AgentDangerousOperationPolicy.decision(
                "read_file", """{"path":"/sdcard/a"}""", mode = AgentToolReviewMode.READONLY,
            ),
        )
    }

    @Test fun strictModeConfirmsMediumRiskEveryTimeWithoutSessionGrants() {
        val args = """{"action":"set","timezone":"Asia/Shanghai"}"""
        // 严格档：中风险第一次调用被拦（与标准档相同的确认闭环）。
        val first = AgentDangerousOperationPolicy.decision("set_timezone", args, mode = AgentToolReviewMode.STRICT)
        assertEquals(AgentDangerousOperationPolicy.CONFIRM_CODE, (first as ToolExecutionDecision.Reject).code)
        // 确认文案不提供「本会话允许」：严格档逐次确认。
        assertFalse(first.message.contains(AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID))
        // 用户选「本会话允许」也只登记一次性授权（闸门对 allowSession=false 的降级）。
        assertTrue(
            AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID),
        )
        // 一次性授权放行同一个调用（确认→重发的闭环本身仍然成立）。
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, mode = AgentToolReviewMode.STRICT))
        // 换参数后重新确认：逐次语义。
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", """{"action":"set","timezone":"UTC"}""", mode = AgentToolReviewMode.STRICT,
            ) is ToolExecutionDecision.Reject,
        )
    }

    @Test fun strictModeDoesNotConsumeSessionGrantsFromStandardMode() {
        val args = """{"action":"set","timezone":"Asia/Shanghai"}"""
        // 标准档：确认一次并选择「本会话允许」→ 会话授权。
        assertTrue(
            AgentDangerousOperationPolicy.decision("set_timezone", args, mode = AgentToolReviewMode.STANDARD)
                is ToolExecutionDecision.Reject,
        )
        assertTrue(
            AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID),
        )
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, mode = AgentToolReviewMode.STANDARD))
        // 标准档的会话授权放行同一调用。
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, mode = AgentToolReviewMode.STANDARD))
        // 切到严格档：会话授权不再被认领，也不被顺手清掉。
        assertTrue(
            AgentDangerousOperationPolicy.decision("set_timezone", args, mode = AgentToolReviewMode.STRICT)
                is ToolExecutionDecision.Reject,
        )
        // 调回标准档：此前的会话授权仍然有效（严格档没有消耗它）。
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, mode = AgentToolReviewMode.STANDARD))
    }

    @Test fun autoModeStillConfirmsHighAndFatalRisks() {
        // 自动档免的是中风险；高/致命的确认是底线，任何档位不豁免。
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "terminal", """{"action":"exec","command":"rm -rf build"}""", mode = AgentToolReviewMode.AUTO,
            ) is ToolExecutionDecision.Reject,
        )
        assertEquals(
            AgentDangerousOperationPolicy.CONFIRM_CODE,
            (AgentDangerousOperationPolicy.decision(
                "power_control", """{"action":"reboot"}""", mode = AgentToolReviewMode.AUTO,
            ) as ToolExecutionDecision.Reject).code,
        )
    }
