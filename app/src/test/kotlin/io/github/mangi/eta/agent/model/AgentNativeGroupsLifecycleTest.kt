package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 会话级原生组集合 S 的生命周期（缓存优先档）。
 *
 * S 决定哪些延迟组下发原生 schema，因此**它就是前缀本身**：run 内一律不变，
 * 跨 run 只在「前缀还小」的窗口里增长，超过预算即冻结，此后零重写。
 * 这里用真实请求的 tools 字节来验证，而不是只看快照——快照对不代表目录对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentNativeGroupsLifecycleTest {
    private var lastTools: String = ""
    private var lastNames: Set<String> = emptySet()

    @Before fun resetPreferences() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        // localAgent 是进程级单例，initLocal 只在 null 时赋值：同 sandbox 的其它测试类
        // 可能留下旧实例与旧值。按 Prefs 自己持有的那份清空，再重跑一次迁移补回标记。
        Prefs.localAgentPreferences()?.edit()?.clear()?.commit()
        Prefs.initLocal(context)
    }

    /** 跑一个单轮 run，记下它真实发出的 tools。 */
    private fun runOnce(sessionId: String, prompt: String, history: List<AgentModelClient.ConversationMessage>) {
        val provider = object : AgentProviderClient {
            override val id: String = "scripted"
            override val capabilities: ProviderCapabilities = ProviderCapabilities(
                endpoint = EndpointKind.CHAT_COMPLETIONS,
                streamingText = true,
                streamingToolCalls = true,
                imageInput = true,
                toolResultImages = false,
                strictTools = false,
                parallelToolCalls = false,
            )

            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                onEvent(ProviderEvent.RequestStarted)
                lastTools = request.tools.toString()
                lastNames = AgentToolCatalog.toolNames(request.tools)
                return ProviderResponse(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", "完成")
                        .put("finish_reason", "stop"),
                )
            }
        }
        AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
                terminalTools = true,
                browserTools = false,
                screenTools = true,
                deviceTools = true,
                personalTools = true,
            ),
            prompt = prompt,
            provider = provider,
            history = history,
            toolLoadingSuffixMode = true,
            sessionId = sessionId,
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
        )
    }

    /** 一段「真的调用过某个工具」的历史：assistant 的 tool_calls 加配对的 tool 结果。 */
    private fun usedTool(name: String): List<AgentModelClient.ConversationMessage> = listOf(
        AgentModelClient.ConversationMessage(
            role = "assistant",
            toolCallsJson = JSONArray().put(
                JSONObject().put("id", "call-$name").put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", "{}")),
            ).toString(),
        ),
        AgentModelClient.ConversationMessage(
            role = "tool",
            content = """{"ok":true}""",
            toolCallId = "call-$name",
        ),
    )

    @Test fun nativeGroupsGrowOnlyWhileThePrefixIsSmallThenFreeze() {
        val session = "native-groups-lifecycle"
        val terminal = AgentToolGroups.TERMINAL_GROUP.id
        val terminalHistory = usedTool("run_command")

        // 首个 run：无关键词、无历史 → 空集合，未冻结，延迟组一个 schema 都不下发。
        runOnce(session, prompt = "帮我处理一件事", history = emptyList())
        assertEquals(Prefs.NativeGroupsSnapshot(emptySet(), frozen = false), Prefs.nativeGroups(session))
        assertFalse("run_command" in lastNames)
        assertTrue(AgentToolGroupCatalog.CALL in lastNames)

        // 第二个 run：历史里真的用过 terminal，前缀还小 → 升级进 S，付一次重写换原生 schema。
        runOnce(session, prompt = "继续", history = terminalHistory)
        assertEquals(setOf(terminal), requireNotNull(Prefs.nativeGroups(session)).groups)
        assertFalse(requireNotNull(Prefs.nativeGroups(session)).frozen)
        assertTrue("升级后应下发原生 schema", "run_command" in lastNames)
        val grownTools = lastTools

        // 第三个 run：前缀超过字符预算 → 冻结。历史里新出现的 device 组只能走 tool_call。
        val longHistory = terminalHistory +
            AgentModelClient.ConversationMessage(role = "user", content = "x".repeat(40_000)) +
            usedTool("set_volume")
        runOnce(session, prompt = "继续", history = longHistory)
        assertTrue("超预算必须冻结", requireNotNull(Prefs.nativeGroups(session)).frozen)
        assertEquals(setOf(terminal), requireNotNull(Prefs.nativeGroups(session)).groups)
        assertEquals("冻结后 tools 必须逐字节不变", grownTools, lastTools)
        assertFalse("set_volume" in lastNames)

        // 冻结之后即使前缀重新变小也不解冻：默认零重写。
        runOnce(session, prompt = "继续", history = terminalHistory)
        assertEquals(setOf(terminal), requireNotNull(Prefs.nativeGroups(session)).groups)
        assertEquals(grownTools, lastTools)
    }

    @Test fun firstRunPreopensThePredictedGroupWithoutARoundTrip() {
        val session = "native-groups-predict"
        runOnce(session, prompt = "帮我截图", history = emptyList())
        assertEquals(
            setOf(AgentToolGroups.SCREEN_GROUP.id),
            requireNotNull(Prefs.nativeGroups(session)).groups,
        )
        assertTrue("预开的组首轮就该带 schema", "tap" in lastNames)
    }

    @Test fun anotherConversationDoesNotInheritTheSingleSlotSnapshot() {
        val terminalHistory = usedTool("run_command")
        runOnce("conv-a", prompt = "继续", history = terminalHistory)
        assertEquals(setOf(AgentToolGroups.TERMINAL_GROUP.id), requireNotNull(Prefs.nativeGroups("conv-a")).groups)

        // 单槽被别的会话占掉：conv-a 的快照读不到了，但新会话不会继承它的组。
        runOnce("conv-b", prompt = "帮我处理一件事", history = emptyList())
        assertEquals(emptySet<String>(), requireNotNull(Prefs.nativeGroups("conv-b")).groups)
        assertNull(Prefs.nativeGroups("conv-a"))
        assertFalse("run_command" in lastNames)
    }
}