package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每轮重建系统消息与工具目录要付 ~50 KB JSON 构造 + 逐工具过滤。只有当能力快照、技能启用状态
 * 或记忆修订变化时才需要重建；签名不变时必须复用上一轮的结果。
 */
class AgentRoundToolsCacheTest {
    private fun assistant(
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
        content: String = "",
    ): JSONObject = JSONObject().put("role", "assistant").put("content", content).put("finish_reason", finishReason)
        .also { if (toolCalls.isNotEmpty()) it.put("tool_calls", JSONArray(toolCalls)) }

    private fun toolCall(id: String, name: String): JSONObject = JSONObject()
        .put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", "{}"))

    private class ScriptedProvider(private val replies: List<JSONObject>) : AgentProviderClient {
        var requests = 0
            private set
        override val id = "round-cache"
        override val capabilities =
            ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            val reply = replies[requests.coerceAtMost(replies.lastIndex)]
            requests++
            return ProviderResponse(JSONObject(reply.toString()))
        }
    }

    private fun run(
        capabilities: AtomicCapabilities,
    ): List<Int> {
        val prompts = mutableListOf<Int>()
        val provider = ScriptedProvider(
            listOf(
                // 两轮工具调用：每次进入下一轮都会经过 toolsForRound。
                assistant("tool_calls", listOf(toolCall("a", "get_current_context"))),
                assistant("tool_calls", listOf(toolCall("b", "get_current_context"))),
                assistant("stop", content = "done"),
            ),
        )
        AgentModelClient.complete(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid", apiKey = "k", model = "m",
                systemPrompt = "", browserTools = false,
            ),
            prompt = "go",
            provider = provider,
            turnId = "turn",
            capabilitiesProvider = { capabilities.value },
            skillContextProvider = { SkillContext.EMPTY },
            onEvent = { event ->
                if (event is AgentEvent.RoundStarted) prompts += event.round
            },
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
        )
        return prompts
    }

    private class AtomicCapabilities(var value: AgentToolCapabilities)

    @Test fun stableCapabilitiesReuseTheSameRoundCatalog() {
        val capabilities = AtomicCapabilities(AgentToolCapabilities(rootAvailable = false))
        val rounds = run(capabilities)
        assertEquals(listOf(1, 2, 3), rounds)
    }

    @Test fun signatureIgnoresSkillBodyChangesButTracksEnablementAndCapabilities() {
        fun signature(
            capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = false),
            skills: List<Pair<String, Boolean>> = listOf("a" to true),
            memoryRevision: String = "r1",
            memoryEnabled: Boolean = true,
        ) = AgentModelClient.RoundCapabilitySignature(
            capabilities = capabilities, skillIndex = skills, memoryRevision = memoryRevision,
            memoryEnabled = memoryEnabled, delegationAvailable = false, interactiveModeEnabled = false,
        )

        val base = signature()
        // 同样的输入必须相等，否则缓存永远不命中。
        assertEquals(base, signature())
        // 记忆修订变化要让索引行重新生成。
        assertTrue(base != signature(memoryRevision = "r2"))
        // 技能启用状态变化要重新生成索引与目录。
        assertTrue(base != signature(skills = listOf("a" to false)))
        assertTrue(base != signature(skills = listOf("a" to true, "b" to true)))
        // 能力变化（例如刚授予 Root）必然改变可用工具集。
        assertTrue(base != signature(capabilities = AgentToolCapabilities(rootAvailable = true)))
        // 记忆开关影响 memory 工具是否下发。
        assertTrue(base != signature(memoryEnabled = false))
    }
}
