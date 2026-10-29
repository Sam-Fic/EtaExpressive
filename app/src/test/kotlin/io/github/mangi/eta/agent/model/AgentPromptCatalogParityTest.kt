package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 提示点名的每个工具，在同一轮请求里都必须真的能调。
 *
 * 提示点名一个目录里没有的工具时，模型照着调用会被 [AgentToolCallValidator] 以
 * 「工具未在本次运行的能力目录中声明」拒掉；连续三次就耗尽纠错预算并终止整轮——
 * 表现就是「第一次调用成功，后面全都调不了」。
 *
 * 这里从**真实请求**取提示与目录：提示由 [AgentPromptBuilder] 生成，目录由
 * [AgentToolCatalog] 生成，两者在 [AgentModelClient] 里同源组装；只有请求级的比较
 * 才能覆盖 Root、无障碍、运行时权限、延迟组与 shell 子开关的组合。
 */
class AgentPromptCatalogParityTest {
    @Test
    fun everyToolNamedInThePromptIsDeclaredInTheSameRequest() {
        val violations = mutableListOf<String>()
        for (scenario in scenarios()) {
            for (request in captureRequests(scenario)) {
                val declared = request.declaredToolNames()
                for (content in request.systemMessages()) {
                    // 延迟组索引宣传的是「还没打开的组」，它点名的工具本来就不在目录里。
                    if (content.startsWith(DEFERRED_INDEX_PREFIX)) continue
                    for (clause in content.split(CLAUSE_SPLIT)) {
                        if (clause.isBlank()) continue
                        // 否定句里的工具名是「不要调用它」，不是「可以调用它」。
                        if (NEGATIVE_MARKERS.any { it in clause }) continue
                        for (match in TOKEN.findAll(clause)) {
                            val name = match.value
                            if (name !in KNOWN_TOOLS) continue
                            if (name !in declared) violations += "${scenario.label} → $name"
                        }
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun aGroupOpenedMidRunKeepsThePromptAndTheCatalogInSync() {
        val opened = mutableSetOf<String>()
        val requests = mutableListOf<ProviderRequest>()
        val provider = capturingProvider(requests) { round ->
            if (round == 1) {
                assistant(
                    finishReason = "tool_calls",
                    toolCalls = listOf(toolCall("open-1", "tools_use_group", """{"group":"terminal"}""")),
                )
            } else {
                assistant(content = "完成", finishReason = "stop")
            }
        }

        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "看看文件",
            provider = provider,
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = true) },
            openedToolGroupsProvider = { opened.toSet() },
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                if (call.name == "tools_use_group") opened += "terminal"
                AgentModelClient.ToolResult("""{"ok":true}""")
            },
        )

        assertEquals(2, requests.size)
        val second = requests.last()
        val declared = second.declaredToolNames()
        assertEquals(true, "run_command" in declared)
        for (content in second.systemMessages()) {
            if (content.startsWith(DEFERRED_INDEX_PREFIX)) continue
            for (clause in content.split(CLAUSE_SPLIT)) {
                if (clause.isBlank()) continue
                if (NEGATIVE_MARKERS.any { it in clause }) continue
                for (match in TOKEN.findAll(clause)) {
                    val name = match.value
                    if (name !in KNOWN_TOOLS) continue
                    assertEquals("$name 应当在打开终端组后的目录里", true, name in declared)
                }
            }
        }
    }

    private data class Scenario(
        val label: String,
        val capabilities: AgentToolCapabilities,
        val openedGroups: Set<String>,
    )

    private fun scenarios(): List<Scenario> = buildList {
        val all = AgentToolGroups.allIds
        add(Scenario("root", AgentToolCapabilities(rootAvailable = true, virtualDisplay = true), all))
        add(Scenario("no-root", AgentToolCapabilities(rootAvailable = false), all))
        add(
            Scenario(
                "no-accessibility",
                AgentToolCapabilities(rootAvailable = true, accessibilityAvailable = false),
                all,
            )
        )
        add(
            Scenario(
                "no-calendar-permission",
                AgentToolCapabilities(
                    rootAvailable = false,
                    calendarReadable = false,
                    calendarWritable = false,
                ),
                all,
            )
        )
        add(Scenario("basic-only", AgentToolCapabilities(rootAvailable = true), setOf("basic")))
        for (group in AgentToolGroups.deferredGroups) {
            add(
                Scenario(
                    "group=${group.id}",
                    AgentToolCapabilities(rootAvailable = true, virtualDisplay = true),
                    setOf("basic", group.id),
                )
            )
        }
    }

    /** 跑一轮真实请求，把每一轮的请求抓下来。 */
    private fun captureRequests(scenario: Scenario): List<ProviderRequest> {
        val requests = mutableListOf<ProviderRequest>()
        val provider = capturingProvider(requests) { assistant(content = "完成", finishReason = "stop") }
        AgentModelClient.complete(
            config = modelConfig(),
            prompt = "检查一下",
            provider = provider,
            capabilitiesProvider = { scenario.capabilities },
            openedToolGroupsProvider = { scenario.openedGroups },
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
        )
        return requests
    }

    private fun capturingProvider(
        requests: MutableList<ProviderRequest>,
        respond: (Int) -> JSONObject,
    ): AgentProviderClient = object : AgentProviderClient {
        override val id: String = "capturing"
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
            requests += request
            return ProviderResponse(respond(requests.size))
        }
    }

    private fun ProviderRequest.declaredToolNames(): Set<String> = buildSet {
        for (index in 0 until tools.length()) {
            tools.optJSONObject(index)
                ?.optJSONObject("function")
                ?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
    }

    private fun ProviderRequest.systemMessages(): List<String> = buildList {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") != "system") continue
            val content = message.optString("content")
            if (content.isNotBlank()) add(content)
        }
    }

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) message.put("tool_calls", JSONArray(toolCalls))
            }

    private fun toolCall(id: String, name: String, arguments: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", arguments))

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
            browserTools = true,
            screenTools = true,
            deviceTools = true,
            personalTools = true,
            terminalTools = true,
        )

    private companion object {
        val KNOWN_TOOLS: Set<String> = AgentToolRequirements.toolNames
        /** 否定句里的工具名不是「可以调用」。 */
        val NEGATIVE_MARKERS = listOf("不要", "不能", "不得", "不该", "不会", "不是", "无需", "禁止", "别", "不为")
        /** 按子句切分，让「必须调用 read_image」不被后半句的否定词连累。 */
        val CLAUSE_SPLIT = Regex("[。！？；，\n]")
        val TOKEN = Regex("[a-z][a-z0-9_]{2,}")
        const val DEFERRED_INDEX_PREFIX = "Deferred tool groups"
    }
}
