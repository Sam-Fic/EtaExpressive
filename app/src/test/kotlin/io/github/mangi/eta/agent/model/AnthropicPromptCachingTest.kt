package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.ProviderTypes
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AnthropicPromptCachingTest {
    private val config = AgentModelClient.ModelConfig(
        providerType = ProviderTypes.ANTHROPIC,
        baseUrl = "https://example.invalid", apiKey = "test", model = "claude-test", systemPrompt = "",
    )
    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)
    private fun tool(name: String) = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", name)
            .put("parameters", JSONObject().put("type", "object")))
    private fun custom(key: String, value: String) = CustomBody(key, Json.parseToJsonElement(value))
    private fun build(messages: JSONArray, tools: JSONArray = JSONArray(), body: List<CustomBody> = emptyList()) =
        AnthropicMessagesProvider.buildRequestJson(config.copy(customBody = body), messages, tools)
    private fun control(block: JSONObject) = block.getJSONObject("cache_control")
    private fun markerCount(body: JSONObject): Int {
        var count = if (body.has("cache_control")) 1 else 0
        for (key in listOf("system", "tools")) {
            val blocks = body.optJSONArray(key) ?: continue
            for (index in 0 until blocks.length()) {
                if (blocks.optJSONObject(index)?.has("cache_control") == true) count++
            }
        }
        val messages = body.optJSONArray("messages") ?: return count
        for (index in 0 until messages.length()) {
            val blocks = messages.optJSONObject(index)?.optJSONArray("content") ?: continue
            for (blockIndex in 0 until blocks.length()) {
                if (blocks.optJSONObject(blockIndex)?.has("cache_control") == true) count++
            }
        }
        return count
    }

    @Test fun defaultsMarkToolsSystemAndFinalUserWithoutMutatingInput() {
        val messages = JSONArray().put(msg("system", "instructions")).put(msg("user", "hello"))
        val tools = JSONArray().put(tool("first")).put(tool("last"))
        val beforeMessages = messages.toString()
        val beforeTools = tools.toString()
        val body = build(messages, tools)
        assertEquals(3, markerCount(body))
        assertEquals("ephemeral", control(body.getJSONArray("tools").getJSONObject(1)).getString("type"))
        assertFalse(body.getJSONArray("tools").getJSONObject(0).has("cache_control"))
        // 默认 5m：活跃会话里每次命中都会刷新 TTL，而 5m 的写入只按 1.25x 计价，
        // 比 1h 的 2x 便宜一半——命中率不受影响，未命中的代价直接减半。
        assertFalse(control(body.getJSONArray("system").getJSONObject(0)).has("ttl"))
        assertFalse(control(body.getJSONArray("tools").getJSONObject(1)).has("ttl"))
        assertFalse(control(body.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)).has("ttl"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).has("cache_control"))
        assertEquals(beforeMessages, messages.toString())
        assertEquals(beforeTools, tools.toString())
        assertFalse(body.toString().contains(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY))
    }

    @Test fun stableInstructionBoundarySurvivesChangingMemoryAndStaysWithinFourBreakpoints() {
        fun messages(memory: String) = JSONArray()
            .put(msg("system", "stable instructions").put(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY, true))
            .put(msg("system", memory)).put(msg("user", "hello"))
        val first = build(messages("memory revision 1"), JSONArray().put(tool("tool")))
        val second = build(messages("memory revision 2"), JSONArray().put(tool("tool")))
        assertEquals(4, markerCount(first))
        assertEquals(4, markerCount(second))
        assertEquals(first.getJSONArray("system").getJSONObject(0).toString(),
            second.getJSONArray("system").getJSONObject(0).toString())
        assertNotEquals(first.getJSONArray("system").getJSONObject(1).getString("text"),
            second.getJSONArray("system").getJSONObject(1).getString("text"))
        assertTrue(first.getJSONArray("system").getJSONObject(1).has("cache_control"))
        assertFalse(first.toString().contains(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY))
    }

    @Test fun realPromptBuilderMarksInstructionsBeforeMemoryAndOnlyForAnthropic() {
        val memory = AgentMemoryContext.DISABLED.copy(enabled = true, revision = "revision-1")
        val model = config.copy(systemPrompt = "persona", terminalTools = false, browserTools = false)
        val messages = AgentPromptBuilder.buildSystemMessages(
            config = model, skillContext = SkillContext.EMPTY, memoryContext = memory, rootAvailable = false,
        )
        val marked = (0 until messages.length()).filter {
            messages.getJSONObject(it).optBoolean(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY)
        }
        assertEquals(1, marked.size)
        val boundary = marked.single()
        assertTrue(messages.getJSONObject(boundary + 1).getString("content").contains("revision=revision-1"))
        messages.put(msg("user", "hello"))
        val body = AnthropicMessagesProvider.buildRequestJson(model, messages, JSONArray().put(tool("tool")))
        assertEquals(4, markerCount(body))
        assertTrue(body.getJSONArray("system").getJSONObject(boundary).has("cache_control"))
        assertFalse(body.toString().contains(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY))
        val otherProvider = AgentPromptBuilder.buildSystemMessages(
            config = model.copy(providerType = ProviderTypes.OPENAI_COMPATIBLE),
            skillContext = SkillContext.EMPTY, memoryContext = memory, rootAvailable = false,
        )
        assertFalse(otherProvider.toString().contains(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY))
    }

    @Test fun identicalStableAndFullSystemBoundaryIsNotCountedTwice() {
        val body = build(JSONArray().put(msg("system", "stable")
            .put(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY, true)).put(msg("user", "hello")),
            JSONArray().put(tool("tool")))
        assertEquals(3, markerCount(body))
    }

    @Test fun normalizedToolResultsCacheOnlyTheEndOfTheBatch() {
        val assistant = msg("assistant", "").put("tool_calls", JSONArray()
            .put(JSONObject().put("id", "call_1").put("type", "function")
                .put("function", JSONObject().put("name", "first").put("arguments", "{}")))
            .put(JSONObject().put("id", "call_2").put("type", "function")
                .put("function", JSONObject().put("name", "last").put("arguments", "{}"))))
        val messages = JSONArray().put(msg("user", "lookup")).put(assistant)
            .put(msg("tool", "one").put("tool_call_id", "call_1"))
            .put(msg("tool", "two").put("tool_call_id", "call_2"))
        val body = build(messages, JSONArray().put(tool("first")).put(tool("last")))
        val batch = body.getJSONArray("messages").getJSONObject(2).getJSONArray("content")
        assertEquals(2, batch.length())
        assertFalse(batch.getJSONObject(0).has("cache_control"))
        assertEquals("tool_result", batch.getJSONObject(1).getString("type"))
        assertTrue(batch.getJSONObject(1).has("cache_control"))
        AnthropicMessageSequence.validateFinalRequest(body)
    }

    @Test fun retentionNoneRemovesLocalSettingAndDoesNotAddControls() {
        val body = build(JSONArray().put(msg("system", "stable")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")), listOf(custom(AnthropicPromptCaching.CONFIG_KEY, "\"none\"")))
        assertEquals(0, markerCount(body))
        assertFalse(body.has(AnthropicPromptCaching.CONFIG_KEY))
    }

    @Test fun explicitOneHourAppliesToAllDefaultBoundaries() {
        val body = build(JSONArray().put(msg("system", "stable")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")), listOf(custom(AnthropicPromptCaching.CONFIG_KEY, "\"1h\"")))
        assertEquals("1h", control(body.getJSONArray("tools").getJSONObject(0)).getString("ttl"))
        assertEquals("1h", control(body.getJSONArray("system").getJSONObject(0)).getString("ttl"))
        assertEquals("1h", control(body.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)).getString("ttl"))
        assertFalse(body.has(AnthropicPromptCaching.CONFIG_KEY))
    }

    @Test fun explicitFiveMinutesOverridesOneHourDefault() {
        val body = build(JSONArray().put(msg("system", "stable")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")), listOf(custom(AnthropicPromptCaching.CONFIG_KEY, "\"5m\"")))
        assertEquals(3, markerCount(body))
        for (block in listOf(
            body.getJSONArray("tools").getJSONObject(0),
            body.getJSONArray("system").getJSONObject(0),
            body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0),
        )) {
            assertEquals("ephemeral", control(block).getString("type"))
            assertFalse(control(block).has("ttl"))
        }
        assertFalse(body.has(AnthropicPromptCaching.CONFIG_KEY))
    }

    @Test fun explicitTopLevelAutomaticCachePolicyIsNotCombinedWithDefaults() {
        val body = build(JSONArray().put(msg("system", "stable")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")), listOf(custom("cache_control", """{"type":"ephemeral","ttl":"1h"}""")))
        assertEquals(1, markerCount(body))
        assertEquals("1h", control(body).getString("ttl"))
    }

    @Test fun explicitBlockPolicyIsPreservedAndDoesNotMixTtls() {
        val customSystem = """[{"type":"text","text":"caller system","cache_control":{"type":"ephemeral","ttl":"1h"}}]"""
        val body = build(JSONArray().put(msg("system", "generated")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")), listOf(custom("system", customSystem)))
        assertEquals(1, markerCount(body))
        assertEquals("1h", control(body.getJSONArray("system").getJSONObject(0)).getString("ttl"))
    }

    @Test fun customMessageContentReceivesTheFinalDefaultMarker() {
        val customMessages = """[{"role":"user","content":"override"}]"""
        val body = build(JSONArray().put(msg("user", "generated")),
            body = listOf(custom("messages", customMessages)))
        val block = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("override", block.getString("text"))
        assertEquals("ephemeral", control(block).getString("type"))
    }

    @Test fun explicitMessagePolicyAndNullTopLevelPreferenceRemainCallerOwned() {
        val override = """[{"role":"user","content":[{"type":"text","text":"override","cache_control":{"type":"ephemeral","ttl":"1h"}}]}]"""
        val messagePolicy = build(JSONArray().put(msg("system", "generated")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")), listOf(custom("messages", override)))
        assertEquals(1, markerCount(messagePolicy))
        assertEquals("1h", control(messagePolicy.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)).getString("ttl"))
        val disabled = build(JSONArray().put(msg("system", "stable")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")), listOf(custom("cache_control", "null")))
        assertEquals(1, markerCount(disabled))
        assertTrue(disabled.isNull("cache_control"))
        assertFalse(disabled.getJSONArray("tools").getJSONObject(0).has("cache_control"))
    }

    @Test fun systemOverrideDoesNotReuseGeneratedStableBoundaryIndex() {
        val original = JSONArray().put(msg("system", "stable")
            .put(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY, true)).put(msg("user", "hello"))
        val override = """[{"type":"text","text":"custom first"},{"type":"text","text":"custom last"}]"""
        val body = build(original, body = listOf(custom("system", override)))
        val system = body.getJSONArray("system")
        assertFalse(system.getJSONObject(0).has("cache_control"))
        assertTrue(system.getJSONObject(1).has("cache_control"))
    }

    @Test fun schemaPropertyNamedCacheControlIsNotTreatedAsAnExplicitBreakpoint() {
        val t = tool("tool")
        t.getJSONObject("function").getJSONObject("parameters").put("properties",
            JSONObject().put("cache_control", JSONObject().put("type", "string")))
        val body = build(JSONArray().put(msg("user", "hello")), JSONArray().put(t))
        assertEquals(2, markerCount(body))
    }

    @Test fun thinkingOrEmptyFinalBlocksAreNeverMarkedButAssistantPrefillIsMarked() {
        // thinking/空文本块本身永远不打 cache_control：从后往前找不到可缓存块时一个断点都不加。
        val thinkingOnly = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "user")
            .put("content", JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "private")))))
        AnthropicPromptCaching.applyDefaults(thinkingOnly)
        assertEquals(0, markerCount(thinkingOnly))
        val empty = build(JSONArray().put(msg("user", "")))
        assertEquals(0, markerCount(empty))
        // 末位断点不区分角色：一轮通常以 assistant 结尾，prefill 文本也要在缓存链上。
        val assistant = build(JSONArray().put(msg("user", "hello")).put(msg("assistant", "prefill")))
        val prefill = assistant.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("ephemeral", control(prefill.getJSONObject(0)).getString("type"))
        // thinking 收尾时不能直接放弃：回溯到最近的可缓存块（text）打标，thinking 不吃标记。
        val trailingThinking = JSONObject().put("messages", JSONArray().put(JSONObject().put("role", "assistant")
            .put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", "answer"))
                .put(JSONObject().put("type", "thinking").put("thinking", "private")))))
        AnthropicPromptCaching.applyDefaults(trailingThinking)
        val blocks = trailingThinking.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("ephemeral", control(blocks.getJSONObject(0)).getString("type"))
        assertFalse(blocks.getJSONObject(1).has("cache_control"))
    }

    @Test fun assistantFinalRoundKeepsFourDistinctBreakpoints() {
        // 一轮通常以 assistant 收尾。末位断点若只认 user，本轮最终内容就永远不在缓存链上，
        // 下一轮第一个请求只能从头重写——这里钉住「四个断点仍然齐、且末位落在 assistant 上」。
        val messages = JSONArray()
            .put(msg("system", "stable instructions").put(AnthropicPromptCaching.SYSTEM_BOUNDARY_KEY, true))
            .put(msg("system", "memory revision 1"))
            .put(msg("user", "hello"))
            .put(msg("assistant", "answer"))
        val body = build(messages, JSONArray().put(tool("first")).put(tool("last")))
        assertEquals(4, markerCount(body))
        assertTrue(body.getJSONArray("tools").getJSONObject(1).has("cache_control"))
        assertTrue(body.getJSONArray("system").getJSONObject(0).has("cache_control"))
        assertTrue(body.getJSONArray("system").getJSONObject(1).has("cache_control"))
        val finalBlock = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0)
        assertEquals("answer", finalBlock.getString("text"))
        assertTrue(finalBlock.has("cache_control"))
        assertFalse(body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(0).has("cache_control"))
    }

    @Test fun applyingDefaultsTwiceIsIdempotent() {
        val body = build(JSONArray().put(msg("system", "stable")).put(msg("user", "hello")),
            JSONArray().put(tool("tool")))
        val before = body.toString()
        AnthropicPromptCaching.applyDefaults(body)
        assertEquals(before, body.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRetentionDoesNotBecomeAnUnsupportedWireValue() {
        build(JSONArray().put(msg("user", "hello")),
            body = listOf(custom(AnthropicPromptCaching.CONFIG_KEY, "\"forever\"")))
    }
}
