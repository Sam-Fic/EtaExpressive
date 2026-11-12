package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用审查四档（只读 / 标准 / 智能审查 / 完全放行）的确认闸门行为：
 * 标准档中风险本会话确认一次、高/致命每次确认；完全放行档豁免一切确认；
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
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
        // 没有问用户就重发：仍然被拒，不能靠重试蒙过去。
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
    }

    @Test fun userApprovalLetsTheSameCallThroughExactlyOnce() {
        val args = """{"action":"reboot"}"""
        AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD)

        // 用户点了同意选项。
        assertTrue(AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID))

        // 重发同一个调用：放行。
        assertNull(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD))
        // 授权只消费一次：再执行一次又要重新确认。
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
    }

    @Test fun approvalDoesNotTransferToDifferentArgumentsOrTools() {
        AgentDangerousOperationPolicy.decision("manage_app", """{"action":"uninstall","package_name":"com.a"}""", AgentToolReviewMode.STANDARD)
        AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID)

        // 换一个应用卸载：同意过 A 不代表同意 B。
        assertTrue(AgentDangerousOperationPolicy.decision(
            "manage_app", """{"action":"uninstall","package_name":"com.b"}""", AgentToolReviewMode.STANDARD,
        ) is ToolExecutionDecision.Reject)
        // 换一个工具：同样不继承授权。
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", """{"action":"reboot"}""", AgentToolReviewMode.STANDARD)
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
            AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD)
            assertFalse("$kind 不构成同意", AgentDangerousConfirmationGate.onUserAnswered(kind, optionId))
            assertTrue("$kind 之后仍应要求确认",
                AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
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
        AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD)
        AgentDangerousConfirmationGate.onUserAnswered("option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID)
        // 模拟授权过期：清理后重发必须重新确认。
        AgentDangerousConfirmationGate.pruneExpired(now = System.currentTimeMillis() + AgentDangerousConfirmationGate.TTL_MS + 1)
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
    }

    // ── 确认粒度：本次允许 / 本会话允许 ────────────────────────────────────────

    @Test fun mediumRiskCanBeAllowedForTheWholeSession() {
        val args = """{"action":"set","timezone":"Asia/Shanghai"}"""
        assertTrue(AgentDangerousOperationPolicy.decision("set_timezone", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
        assertEquals(
            AgentConfirmationScope.SESSION,
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID,
            ),
        )
        // 本会话授权可重复使用，不消费。
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, AgentToolReviewMode.STANDARD))
        assertNull(AgentDangerousOperationPolicy.decision("set_timezone", args, AgentToolReviewMode.STANDARD))
        // 换参数仍然要重新确认。
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", """{"action":"set","timezone":"UTC"}""", AgentToolReviewMode.STANDARD,
            ) is ToolExecutionDecision.Reject,
        )
    }

    @Test fun fatalRiskNeverGrantsSessionScope() {
        val args = """{"action":"reboot"}"""
        AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD)
        // 即使模型给了「本会话允许」选项，致命操作也只授权一次。
        assertEquals(
            AgentConfirmationScope.ONCE,
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID,
            ),
        )
        assertNull(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD))
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
    }

    @Test fun cancelOptionNeverGrants() {
        val args = """{"action":"reboot"}"""
        AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD)
        assertNull(
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CANCEL_OPTION_ID,
            ),
        )
        assertTrue(AgentDangerousOperationPolicy.decision("power_control", args, AgentToolReviewMode.STANDARD) is ToolExecutionDecision.Reject)
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
                "recipe_clean", """{"path":"/storage"}""", listOf("rm -rf /sdcard/Download"), AgentToolReviewMode.STANDARD,
            ) is ToolExecutionDecision.Reject,
        )
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

    @Test fun offModeSkipsEveryConfirmationIncludingFatal() {
        // 完全放行档：用户显式豁免一切确认——中/高/致命一律直接放行，是唯一的底线豁免档。
        listOf(
            "set_timezone" to """{"action":"set","timezone":"UTC"}""",
            "terminal" to """{"action":"exec","command":"rm -rf build"}""",
            "power_control" to """{"action":"reboot"}""",
        ).forEach { (name, args) ->
            assertNull(
                "$name 在完全放行档应直接执行",
                AgentDangerousOperationPolicy.decision(name, args, mode = AgentToolReviewMode.OFF),
            )
        }
    }

    @Test fun retiredPrefsValuesMapToStandard() {
        // 严格/自动已并入标准：退役值（以及任何未知值）一律落回标准档。
        assertEquals(AgentToolReviewMode.STANDARD, AgentToolReviewMode.from("strict"))
        assertEquals(AgentToolReviewMode.STANDARD, AgentToolReviewMode.from("auto"))
        assertEquals(AgentToolReviewMode.STANDARD, AgentToolReviewMode.from("anything-unknown"))
        // 现存四档的字符串值各自映射到位。
        assertEquals(AgentToolReviewMode.READONLY, AgentToolReviewMode.from("readonly"))
        assertEquals(AgentToolReviewMode.STANDARD, AgentToolReviewMode.from("standard"))
        assertEquals(AgentToolReviewMode.SMART, AgentToolReviewMode.from("smart"))
        assertEquals(AgentToolReviewMode.OFF, AgentToolReviewMode.from("off"))
    }

    // ── 智能审查档（模型裁决 + 失败回退） ──────────────────────────────────────

    @Test fun smartModeAllowsWhenModelAllows() {
        assertNull(
            AgentDangerousOperationPolicy.decision(
                "terminal", """{"action":"exec","command":"rm -rf build"}""", mode = AgentToolReviewMode.SMART,
                review = { _, _, _ -> AgentToolReviewVerdict.Allow("consistent with request", "llm", 42) },
            ),
        )
    }

    @Test fun smartModeDeniesWithoutAskPath() {
        val decision = AgentDangerousOperationPolicy.decision(
            "power_control", """{"action":"reboot"}""", mode = AgentToolReviewMode.SMART,
            review = { _, _, _ -> AgentToolReviewVerdict.Deny("out of scope", "decision", 31) },
        ) as ToolExecutionDecision.Reject
        assertEquals(AgentDangerousOperationPolicy.SMART_DENY_CODE, decision.code)
        assertTrue(decision.message.contains("未做任何改动"))
        assertFalse(decision.message.contains(AgentDangerousConfirmationGate.CONFIRM_OPTION_ID))
    }

    @Test fun smartModeFallsBackToRuleReviewOnFailure() {
        // 模型失败/超时/未注入裁决闭包：一律回退标准档规则确认，绝不直接放行。
        listOf(
            null,
            AgentToolReviewVerdict.Fallback("timeout", 6000),
        ).forEach { verdict ->
            val decision = AgentDangerousOperationPolicy.decision(
                "set_timezone", """{"action":"set","timezone":"UTC"}""", mode = AgentToolReviewMode.SMART,
                review = { _, _, _ -> verdict },
            )
            assertEquals(
                AgentDangerousOperationPolicy.CONFIRM_CODE,
                (decision as ToolExecutionDecision.Reject).code,
            )
        }
        // 裁决闭包抛异常也不该打断工具流程（策略内 try/catch）。
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", """{"action":"set","timezone":"UTC"}""", mode = AgentToolReviewMode.SMART,
                review = { _, _, _ -> throw IllegalStateException("boom") },
            ) is ToolExecutionDecision.Reject,
        )
    }

    @Test fun smartModeSkipsLowRiskCalls() {
        // 低风险/纯读不花模型往返：裁决闭包根本不该被调用。
        var invoked = false
        assertNull(
            AgentDangerousOperationPolicy.decision(
                "read_file", """{"path":"/sdcard/a"}""", mode = AgentToolReviewMode.SMART,
                review = { _, _, _ ->
                    invoked = true
                    AgentToolReviewVerdict.Deny("should not be called", "llm", 1)
                },
            ),
        )
        assertFalse(invoked)
    }

    // ── 跨 run / 跨会话隔离（调度台派发 run 与主会话并发） ────────────────────

    @Test fun concurrentRunsHaveIndependentPendingSlots() {
        val args = """{"action":"reboot"}"""
        // 两个 run 并发命中同一个危险调用：各自的待确认槽各自记账，不互相覆盖。
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "power_control", args, AgentToolReviewMode.STANDARD, runId = "run-a", conversationId = "conv-a",
            ) is ToolExecutionDecision.Reject,
        )
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "power_control", args, AgentToolReviewMode.STANDARD, runId = "run-b", conversationId = "conv-b",
            ) is ToolExecutionDecision.Reject,
        )
        // 用户回答了 run-a 的提问：只登记 run-a 的授权（进程级单槽实现会把授权错登到 run-b）。
        assertEquals(
            AgentConfirmationScope.ONCE,
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CONFIRM_OPTION_ID, runId = "run-a",
            ),
        )
        // run-a 重发放行；run-b 没被代答，重发仍被拒。
        assertNull(
            AgentDangerousOperationPolicy.decision(
                "power_control", args, AgentToolReviewMode.STANDARD, runId = "run-a", conversationId = "conv-a",
            ),
        )
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "power_control", args, AgentToolReviewMode.STANDARD, runId = "run-b", conversationId = "conv-b",
            ) is ToolExecutionDecision.Reject,
        )
    }

    @Test fun sessionApprovalDoesNotCrossConversations() {
        val args = """{"action":"set","timezone":"Asia/Shanghai"}"""
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", args, AgentToolReviewMode.STANDARD, runId = "run-a", conversationId = "conv-a",
            ) is ToolExecutionDecision.Reject,
        )
        assertEquals(
            AgentConfirmationScope.SESSION,
            AgentDangerousConfirmationGate.onUserAnsweredScoped(
                "option", AgentDangerousConfirmationGate.CONFIRM_SESSION_OPTION_ID, runId = "run-a",
            ),
        )
        // 同会话内本会话授权可复用。
        assertNull(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", args, AgentToolReviewMode.STANDARD, runId = "run-a", conversationId = "conv-a",
            ),
        )
        // 另一个会话发同一个调用：不继承授权，仍要确认（授权按会话隔离，防跨会话提权）。
        assertTrue(
            AgentDangerousOperationPolicy.decision(
                "set_timezone", args, AgentToolReviewMode.STANDARD, runId = "run-b", conversationId = "conv-b",
            ) is ToolExecutionDecision.Reject,
        )
    }
}
