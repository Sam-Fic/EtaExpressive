package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentSensitiveToolPolicy
import io.github.mangi.eta.agent.model.AgentToolGroup
import io.github.mangi.eta.agent.model.AgentToolGroupCatalog
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 缓存优先档的转发入口 `tool_call`。
 *
 * 它只多一层「真实名解析 + 组加载校验 + 真实 schema 校验」，随后必须交回 [AgentLocalTools.execute]：
 * 危险操作确认、准入闸门、Root 判定都按**内层真实名**再跑一遍。壳名字一旦漏进这些闸门，
 * `tool_call` 就成了脱敏与确认的旁路——这是本档唯一不能回退的安全项。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsGroupCallTest {
    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    @Before fun resetPreferences() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        // 执行侧的组闸门与单工具禁用集都读 Prefs；同 sandbox 的其它测试类可能留下旧值。
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        Prefs.initLocal(context)
    }

    private fun tools(
        terminal: Boolean = true,
        device: Boolean = true,
        dynamicGroups: List<AgentToolGroup> = emptyList(),
        confirm: (String, String) -> ToolExecutionDecision? = { _, _ -> null },
        admission: (String) -> ToolExecutionDecision = { ToolExecutionDecision.Allow },
        mcpSchemas: (String) -> JSONArray = { JSONArray() },
        mcpExecutor: ((AgentModelClient.ToolCall) -> AgentModelClient.ToolResult)? = null,
    ): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        browserRunId = "group-call-run",
        terminalToolsEnabled = { terminal },
        deviceToolsEnabled = { device },
        dynamicToolGroups = { dynamicGroups },
        groupToolsViaCall = true,
        confirmDangerousOperation = confirm,
        beforeToolExecution = admission,
        mcpToolSchemas = mcpSchemas,
        mcpToolExecutor = mcpExecutor,
    )

    private fun mcpServer(): AgentToolGroup = AgentToolGroup(
        id = AgentToolGroups.mcpGroupId("demo"),
        summary = "demo server",
        toolNames = setOf("demo_tool"),
        label = "demo",
    )

    /** MCP 快照按单组渲染出的真实 schema：q 必填，转发入口靠它校验 arguments。 */
    private fun mcpDemoSchemas(): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", "demo_tool")
                        .put("description", "demo server 提供的工具")
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", JSONObject().put("q", JSONObject().put("type", "string")))
                                .put("required", JSONArray().put("q")),
                        ),
                ),
        )

    private fun open(tools: AgentLocalTools, groupId: String): JSONObject =
        JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    "open-$groupId",
                    AgentToolGroupCatalog.USE_GROUP,
                    JSONObject().put("group", groupId).toString(),
                ),
            ).content,
        )

    private fun forward(tools: AgentLocalTools, name: String, arguments: JSONObject): JSONObject =
        JSONObject(
            tools.execute(
                AgentModelClient.ToolCall(
                    "forward-1",
                    AgentToolGroupCatalog.CALL,
                    JSONObject().put("name", name).put("arguments", arguments).toString(),
                ),
            ).content,
        )

    @Test fun unloadedGroupTellsTheModelHowToRecover() {
        val tools = tools()
        try {
            val result = forward(tools, "stat_file", JSONObject().put("path", "/sdcard"))
            assertEquals("TOOL_GROUP_NOT_LOADED", result.getString("code"))
            assertTrue(
                "错误必须给出可自救的下一步",
                result.getString("message").contains("tools_use_group(group=terminal)"),
            )
        } finally {
            tools.close()
        }
    }

    @Test fun selfRecursionIsRefused() {
        val tools = tools()
        try {
            val result = forward(tools, AgentToolGroupCatalog.CALL, JSONObject())
            assertEquals("INVALID_ARGUMENT", result.getString("code"))
            assertTrue(result.getString("message").contains("不能调用自身"))
        } finally {
            tools.close()
        }
    }

    @Test fun blankAndUnknownNamesAreRefused() {
        val tools = tools()
        try {
            assertEquals("INVALID_ARGUMENT", forward(tools, "  ", JSONObject()).getString("code"))
            assertEquals("UNKNOWN_TOOL", forward(tools, "no_such_tool", JSONObject()).getString("code"))
        } finally {
            tools.close()
        }
    }

    @Test fun argumentsAreValidatedAgainstTheRealGroupSchema() {
        val tools = tools()
        try {
            assertTrue(open(tools, AgentToolGroups.TERMINAL_GROUP.id).getBoolean("ok"))
            // 工具不在 tools 里，主校验器看不到它；转发入口必须自己按组内真实 schema 校验。
            val result = forward(tools, "stat_file", JSONObject())
            assertEquals("INVALID_ARGUMENT", result.getString("code"))
            assertTrue(
                "应原样转达校验器的措辞",
                result.getString("message").contains("缺少必填字段 path"),
            )
        } finally {
            tools.close()
        }
    }

    @Test fun groupLoadReceiptCarriesDocsUsageAndImmediateAvailability() {
        val tools = tools()
        try {
            val receipt = open(tools, AgentToolGroups.TERMINAL_GROUP.id)
            assertTrue(receipt.getBoolean("ok"))
            assertEquals("now", receipt.getString("available"))
            assertEquals(AgentToolGroupCatalog.CALL, receipt.getString("call_via"))
            assertEquals(
                AgentToolGroups.groupUsage(AgentToolGroups.TERMINAL_GROUP.id),
                receipt.getString("usage"),
            )
            val docs = receipt.getJSONArray("tool_docs")
            assertTrue("回执必须带参数说明，否则模型无从填 arguments", docs.length() > 0)
            val usable = (0 until receipt.getJSONArray("tools").length())
                .map { receipt.getJSONArray("tools").getString(it) }
            for (index in 0 until docs.length()) {
                val doc = docs.getJSONObject(index)
                assertTrue(doc.getString("name") in usable)
                assertTrue(doc.getString("description").isNotEmpty())
                assertTrue(doc.has("parameters"))
            }
            assertTrue("stat_file 应可经转发调用", "stat_file" in usable)
            // 参数说明必须是真实 schema，不是描述性的空壳：转发入口靠它校验 arguments。
            val statFile = (0 until docs.length()).map { docs.getJSONObject(it) }
                .first { it.getString("name") == "stat_file" }
            val required = statFile.getJSONObject("parameters").getJSONArray("required")
            assertTrue((0 until required.length()).any { required.getString(it) == "path" })
        } finally {
            tools.close()
        }
    }

    @Test fun dangerousConfirmationSeesTheInnerToolName() {
        val seen = mutableListOf<Pair<String, String>>()
        val tools = tools(confirm = { name, arguments ->
            seen += name to arguments
            // 确认闸门对每个工具都会跑一遍（含 tools_use_group 与 tool_call 壳本身），
            // 只有内层真实名才该被判为危险。
            if (name == "manage_file") {
                ToolExecutionDecision.Reject(AgentDangerousOperationPolicy.CONFIRM_CODE, "先取得用户同意")
            } else {
                null
            }
        })
        try {
            // 删除是致命风险；闸门拒绝后不会真的执行，路径只是判定用的样本。
            val inner = JSONObject().put("action", "delete").put("path", "/sdcard/eta-group-call-sample")
            val outer = JSONObject().put("name", "manage_file").put("arguments", inner)
            assertFalse(
                "壳名字看不出破坏性——这正是不能拿它做判定的原因",
                AgentRiskPolicy.assess(AgentToolGroupCatalog.CALL, outer.toString()).requiresConfirmation,
            )
            assertTrue(AgentRiskPolicy.assess("manage_file", inner.toString()).requiresConfirmation)

            assertTrue(open(tools, AgentToolGroups.DEVICE_GROUP.id).getBoolean("ok"))
            val result = forward(tools, "manage_file", inner)
            // 只有 manage_file 会被拒：拿到这个码就证明闸门看到的是内层真实名。
            assertEquals(AgentDangerousOperationPolicy.CONFIRM_CODE, result.getString("code"))
            assertEquals("manage_file", seen.last().first)
            assertEquals("delete", JSONObject(seen.last().second).getString("action"))
        } finally {
            tools.close()
        }
    }

    @Test fun admissionGateSeesTheInnerToolName() {
        val seen = mutableListOf<String>()
        val tools = tools(admission = { name ->
            seen += name
            if (name == "stat_file") {
                ToolExecutionDecision.Reject("TEST_ADMISSION_DENIED", "本次未执行")
            } else {
                ToolExecutionDecision.Allow
            }
        })
        try {
            assertTrue(open(tools, AgentToolGroups.TERMINAL_GROUP.id).getBoolean("ok"))
            val result = forward(tools, "stat_file", JSONObject().put("path", "/sdcard"))
            assertEquals("TEST_ADMISSION_DENIED", result.getString("code"))
            assertEquals("stat_file", seen.last())
        } finally {
            tools.close()
        }
    }

    @Test fun mcpGroupWithoutAnExecutorKeepsNativeNextTurnSemantics() {
        // 没注入 MCP 执行器时不能改道：转发过去够不到执行器，
        // 反而会把可调用的 server 工具说成「不在此轮」。
        val server = mcpServer()
        val tools = tools(dynamicGroups = listOf(server))
        try {
            val receipt = open(tools, server.id)
            assertTrue(receipt.getBoolean("ok"))
            assertEquals("next_turn", receipt.getString("available"))
            assertEquals("direct", receipt.getString("call_via"))
            assertFalse(receipt.has("tool_docs"))
        } finally {
            tools.close()
        }
    }

    @Test fun mcpGroupJoinsSuffixWhenTheExecutorIsInjected() {
        val server = mcpServer()
        val seen = mutableListOf<String>()
        val tools = tools(
            dynamicGroups = listOf(server),
            mcpSchemas = { groupId ->
                assertEquals("应按组 id 向快照取 schema", server.id, groupId)
                mcpDemoSchemas()
            },
            mcpExecutor = { call ->
                seen += call.name
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )
        try {
            val receipt = open(tools, server.id)
            assertTrue(receipt.getBoolean("ok"))
            assertEquals("打开 server 不再改写前缀，本轮即可调用", "now", receipt.getString("available"))
            assertEquals(AgentToolGroupCatalog.CALL, receipt.getString("call_via"))
            val docs = receipt.getJSONArray("tool_docs")
            assertEquals(1, docs.length())
            assertEquals("demo_tool", docs.getJSONObject(0).getString("name"))
            assertTrue("回执必须带参数说明", docs.getJSONObject(0).has("parameters"))

            // 工具不在 tools 里，主校验器看不到它；转发入口按快照 schema 自行校验。
            assertEquals("INVALID_ARGUMENT", forward(tools, "demo_tool", JSONObject()).getString("code"))
            assertTrue("校验不过就不该执行", seen.isEmpty())

            assertTrue(forward(tools, "demo_tool", JSONObject().put("q", "x")).getBoolean("ok"))
            // 必须交给 MCP 执行器且用内层真实名：交回 AgentLocalTools.execute 会判 UNKNOWN_TOOL。
            assertEquals(listOf("demo_tool"), seen)
        } finally {
            tools.close()
        }
    }

    @Test fun theMcpForwardingShellIsNotASensitivityBypass() {
        // MCP 结果本来就不入持久会话；套上 tool_call 壳后仍必须判为敏感。
        val outer = JSONObject().put("name", "mcp_demo_tool").put("arguments", JSONObject()).toString()
        assertTrue(AgentSensitiveToolPolicy.isSensitive(AgentToolGroupCatalog.CALL, outer))
        assertEquals("mcp_demo_tool", AgentSensitiveToolPolicy.resolveName(AgentToolGroupCatalog.CALL, outer))
    }

    @Test fun mcpForwardingResultIsMarkedSensitiveAtTheShell() {
        // MCP 分支直接 `return mcp(inner)`，不经过内层 execute 的敏感标记包装——
        // 外壳层必须按参数里的内层真实名补标（双参 isSensitive），不能依赖内层自带。
        // 这里让假执行器返回不带 sensitive 的结果，外层补标就是本测试钉住的防线。
        val server = AgentToolGroup(
            id = AgentToolGroups.mcpGroupId("demo"),
            summary = "demo server",
            toolNames = setOf("mcp_demo_tool"),
            label = "demo",
        )
        val tools = tools(
            dynamicGroups = listOf(server),
            mcpSchemas = { groupId ->
                assertEquals(server.id, groupId)
                JSONArray().put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "mcp_demo_tool")
                                .put("description", "demo server 提供的工具")
                                .put(
                                    "parameters",
                                    JSONObject().put("type", "object").put("properties", JSONObject()),
                                ),
                        ),
                )
            },
            mcpExecutor = { AgentModelClient.ToolResult("""{"ok":true}""") },
        )
        try {
            assertTrue(open(tools, server.id).getBoolean("ok"))
            val shell = AgentModelClient.ToolCall(
                "forward-1",
                AgentToolGroupCatalog.CALL,
                JSONObject().put("name", "mcp_demo_tool").put("arguments", JSONObject()).toString(),
            )
            assertTrue("MCP 转发结果必须在外壳层标记敏感", tools.execute(shell).sensitive)
        } finally {
            tools.close()
        }
    }
}