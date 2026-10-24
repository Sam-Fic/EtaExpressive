package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.delegation.SubAgentRunner
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runtime request assertions complement (but are not replaced by) the Python wiring contracts. */
class AgentInteractiveModePromptTest {
    @Test
    fun switchChangesOnlyExistingBaseSystemMessageAndKeepsQuestionDiscipline() {
        for (persona in listOf("", "Custom persona")) {
            for (terminal in listOf(false, true)) {
                for (browser in listOf(false, true)) {
                    for (delegation in listOf(false, true)) {
                        val config = config().copy(systemPrompt = persona, terminalTools = terminal, browserTools = browser)
                        fun systems(enabled: Boolean) = AgentPromptBuilder.buildSystemMessages(
                            config, SkillContext.EMPTY, AgentMemoryContext.DISABLED, rootAvailable = false,
                            delegationAvailable = delegation, interactiveModeEnabled = enabled,
                        )
                        val off = systems(false)
                        val on = systems(true)
                        val defaults = AgentPromptBuilder.buildSystemMessages(
                            config, SkillContext.EMPTY, AgentMemoryContext.DISABLED, rootAvailable = false,
                            delegationAvailable = delegation,
                        )
                        assertEquals(off.toString(), defaults.toString())
                        // 基线条数随内置工具族增长（例如网页搜索/正文读取），
                        // 用关掉所有可选族时的条数当基线，测试只关心「交互模式开关不改条数」。
                        val baseline = AgentPromptBuilder.buildSystemMessages(
                            config.copy(systemPrompt = "", terminalTools = false, browserTools = false),
                            SkillContext.EMPTY, AgentMemoryContext.DISABLED, rootAvailable = false,
                            delegationAvailable = false,
                        ).length()
                        // browserTools 现在贡献两段提示（fetch_url/web_search 一段 + browser_use 一段）。
                        val expectedCount = baseline + (if (persona.isNotBlank()) 1 else 0) +
                            (if (delegation) 1 else 0) + (if (terminal) 1 else 0) + (if (browser) 2 else 0)
                        assertEquals(expectedCount, off.length())
                        assertEquals(off.length(), on.length())
                        val baseIndex = (if (persona.isNotBlank()) 1 else 0) + (if (delegation) 1 else 0)
                        for (index in 0 until off.length()) {
                            assertEquals("system", on.getJSONObject(index).getString("role"))
                            if (index != baseIndex) {
                                assertEquals(off.getJSONObject(index).toString(), on.getJSONObject(index).toString())
                            }
                        }
                        val offBase = off.getJSONObject(baseIndex).getString("content")
                        val onBase = on.getJSONObject(baseIndex).getString("content")
                        assertEquals(offBase.substringBefore("\n交互模式"), onBase.substringBefore("\n交互模式"))
                        assertTrue(onBase.contains("交互模式已开启"))
                        assertTrue(onBase.contains("无法从上下文可靠推断的关键歧义"))
                        assertTrue(onBase.contains("2-8 个互不重叠且可执行的选项"))
                        assertTrue(onBase.contains("不为交互而多问"))
                        assertTrue(offBase.contains("交互模式已关闭"))
                        assertTrue(offBase.contains("仍须用 ask_user 澄清"))
                        for (guidance in listOf(offBase, onBase)) {
                            assertQuestionDiscipline(guidance)
                            assertTrue(guidance.contains("不改变任何工具权限"))
                            assertTrue(guidance.contains("前台、后台、每次询问（ASK）执行偏好"))
                            assertTrue(guidance.contains("不要用 ask_user 询问执行位置"))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun initialMessagesForwardSwitchWithoutMovingHistoryOrCurrentInput() {
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "old question"),
            AgentModelClient.ConversationMessage("assistant", "old answer"),
        )
        fun initial(enabled: Boolean) = AgentPromptBuilder.buildInitialMessages(
            config = config(), prompt = "current question", images = emptyList(), history = history,
            skillContext = SkillContext.EMPTY, interactiveModeEnabled = enabled,
        )
        val off = initial(false)
        val on = initial(true)
        // 三个基础 system 消息 + 延迟工具域索引（未打开的域会追加一行索引）。
        assertEquals(listOf("system", "system", "system", "system", "user", "assistant", "user"),
            (0 until on.length()).map { on.getJSONObject(it).getString("role") })
        assertEquals(off.length(), on.length())
        assertTrue(on.getJSONObject(0).getString("content").contains("交互模式已开启"))
        assertTrue(off.getJSONObject(0).getString("content").contains("交互模式已关闭"))
        for (index in 1 until off.length()) {
            assertEquals(off.getJSONObject(index).toString(), on.getJSONObject(index).toString())
        }
        assertEquals("old question", on.getJSONObject(4).getString("content"))
        assertEquals("old answer", on.getJSONObject(5).getString("content"))
        assertEquals("current question", on.getJSONObject(6).getString("content"))
    }

    @Test
    fun completeKeepsFrozenPolicyAcrossRealRoundsAndDoesNotFilterQuestionTool() {
        val catalogs = mutableMapOf<Boolean, List<String>>()
        for (enabled in listOf(false, true)) {
            var selectedMode = enabled
            var rootAvailable = true
            var captures = 0
            val provider = CapturingProvider()
            val executed = mutableListOf<String>()
            val result = AgentModelClient.complete(
                config = config(), prompt = "complete task", provider = provider,
                interactiveModeEnabled = selectedMode,
                capabilitiesProvider = {
                    captures++
                    AgentToolCapabilities(rootAvailable = rootAvailable)
                },
                toolExecutor = AgentModelClient.ToolExecutor { call ->
                    executed += call.name
                    // Simulate the owner changing its selection while tools execute; this run stays frozen.
                    selectedMode = !selectedMode
                    rootAvailable = !rootAvailable
                    AgentModelClient.ToolResult("{\"ok\":true}")
                },
            )
            assertEquals("done", result.content)
            assertEquals(listOf("get_current_context", "ask_user"), executed)
            assertEquals(3, provider.messages.size)
            // 首轮复用发送前捕获的初始快照（未执行任何工具，能力不可能变），之后每轮各取一次：
            // 3 轮 = 1 次初始 + 第 2/3 轮各 1 次。
            assertEquals(3, captures)
            provider.messages.forEachIndexed { round, messages ->
                val guidance = messages.systemContents().single { it.contains("交互模式已") }
                assertTrue(guidance.contains(if (enabled) "交互模式已开启" else "交互模式已关闭"))
                assertFalse(guidance.contains(if (enabled) "交互模式已关闭" else "交互模式已开启"))
                assertQuestionDiscipline(guidance)
                assertEquals(round != 1, guidance.contains("相关应用私有文件与数据库"))
                assertEquals(4, messages.systemContents().size)
            }
            provider.tools.forEach { assertTrue(it.toolNames().contains("ask_user")) }
            catalogs[enabled] = provider.tools.map { it.toString() }
        }
        // The exact same capability snapshots yield the exact same schemas in either mode.
        assertEquals(catalogs[false], catalogs[true])
    }

    @Test
    fun defaultModelCallRemainsOffAndActualChildRunnerReceivesNoInteractivePolicy() {
        val parent = CapturingProvider(textOnly = true)
        AgentModelClient.complete(
            config = config(), prompt = "parent", provider = parent, interactiveModeEnabled = true,
            toolExecutor = AgentModelClient.ToolExecutor { error("no tools expected") },
        )
        assertTrue(parent.messages.single().toString().contains("交互模式已开启"))

        val defaultCall = CapturingProvider(textOnly = true)
        AgentModelClient.complete(
            config = config(), prompt = "other caller", provider = defaultCall,
            toolExecutor = AgentModelClient.ToolExecutor { error("no tools expected") },
        )
        assertTrue(defaultCall.messages.single().toString().contains("交互模式已关闭"))
        assertFalse(defaultCall.messages.single().toString().contains("交互模式已开启"))

        val child = CapturingProvider(textOnly = true)
        assertEquals("done", SubAgentRunner.run(
            config = config(), prompt = "child task", tools = AgentToolCatalog.build(false, false),
            executor = AgentModelClient.ToolExecutor { error("no tools expected") },
            controller = AgentRunController(), provider = child, compactPolicy = AgentLoop.CompactPolicy.Disabled,
        ))
        assertFalse(child.messages.single().toString().contains("交互模式"))
        assertFalse(child.tools.single().toolNames().contains("ask_user"))
        assertEquals(1, child.messages.single().systemContents().size)
    }

    @Test
    fun overheadAndProtocolPreviewCountTheSamePromptPolicyAndUnfilteredSchemas() {
        val config = config()
        val caps = AgentToolCapabilities(rootAvailable = false)
        val catalog = AgentToolCatalog.build(
            terminalTools = false, browserTools = false, skillGitHubDiscovery = true,
            skillGitHubInstall = true, capabilities = caps,
        )
        val overheads = mutableListOf<Int>()
        val promptTokens = mutableListOf<Int>()
        for (enabled in listOf(false, true)) {
            val systems = AgentPromptBuilder.buildSystemMessages(
                config, SkillContext.EMPTY, AgentMemoryContext.DISABLED, rootAvailable = false,
                interactiveModeEnabled = enabled,
            )
            val systemTokens = systems.systemContents().sumOf { content ->
                AgentContextBudget.countMessage(AgentModelClient.ConversationMessage("system", content))
            }
            var preview = -1
            val overhead = AgentRequestOverhead.estimate(
                config = config, capabilities = caps, interactiveModeEnabled = enabled,
                onProtocolPreview = { preview = it },
            )
            assertEquals(systemTokens + AgentContextBudget.countTokens(catalog.toString()), overhead)
            assertEquals(AgentWireRequestEstimate.measure(
                AgentWireRequestEstimate.previewBody(config, systems, catalog),
                AgentWireRequestEstimate.endpoint(config),
            ).tokens, preview)
            overheads += overhead
            promptTokens += systemTokens
        }
        assertNotEquals(promptTokens[0], promptTokens[1])
        assertEquals(promptTokens[1] - promptTokens[0], overheads[1] - overheads[0])
    }

    private fun assertQuestionDiscipline(guidance: String) {
        for (clause in listOf(
            "立即调用工具", "不要先输出计划、解释或中间进度",
            "缺少会影响执行结果的关键信息时，再简短询问，不猜测关键参数",
            "需要用户补充时只用 ask_user 提一个关键问题，并单独成批调用、不和其它工具混在同一批",
            "能由上下文合理推断的细节直接处理，不为此追问",
            "把推荐项只当参考，不自动替用户选择",
            "用户说“你看着办”只授权当前这一个具体问题，不等于扩大设备权限或同意其它操作",
            "子代理不能直接向你提问",
        )) assertTrue("Missing existing question discipline: $clause", guidance.contains(clause))
    }

    private class CapturingProvider(private val textOnly: Boolean = false) : AgentProviderClient {
        override val id = "interactive-mode-test"
        override val capabilities = ProviderCapabilities(
            EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false,
        )
        val messages = mutableListOf<JSONArray>()
        val tools = mutableListOf<JSONArray>()
        override fun complete(
            request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            messages += JSONArray(request.messages.toString())
            tools += JSONArray(request.tools.toString())
            val round = messages.size
            if (textOnly || round == 3) return ProviderResponse(
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"),
            )
            check(round <= 2) { "Unexpected extra model round" }
            val name = if (round == 1) "get_current_context" else "ask_user"
            val arguments = if (round == 1) "{}" else JSONObject()
                .put("title", "Choose target").put("question", "Which target should be used?")
                .put("options", JSONArray()
                    .put(JSONObject().put("id", "a").put("label", "Target A"))
                    .put(JSONObject().put("id", "b").put("label", "Target B"))).toString()
            val call = JSONObject().put("id", "call-$round").put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", arguments))
            return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", JSONArray().put(call)).put("finish_reason", "tool_calls"))
        }
    }

    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test-key", model = "test-model",
        systemPrompt = "", terminalTools = false, browserTools = false, supportsVision = true,
    )

    private fun JSONArray.systemContents(): List<String> = (0 until length())
        .map { getJSONObject(it) }.filter { it.optString("role") == "system" }.map { it.getString("content") }

    private fun JSONArray.toolNames(): Set<String> = (0 until length())
        .map { getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
}
