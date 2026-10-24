package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentConversationCodec.durableMessageLength] 是 AgentLoop 增量字符计数的基石：
 * 只有它与「全量构造 transcript 再逐条 toString 求和」逐字符一致，缓存才是无行为变化的优化。
 */
class AgentConversationDurableLengthTest {
    private fun fullPass(messages: JSONArray, start: Int, sensitive: Set<String>): Long =
        AgentConversationCodec.transcript(messages, start, sensitive)
            .sumOf { AgentConversationCodec.toJsonObject(it).toString().length.toLong() }

    private fun perMessage(messages: JSONArray, start: Int, sensitive: Set<String>): Long {
        var total = 0L
        for (index in start until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            total += AgentConversationCodec.durableMessageLength(message, sensitive)
        }
        return total
    }

    /** 与「全量构造 transcript 再逐条 countMessage」等价：增量词元统计的基础。 */
    private fun fullPassTokens(messages: JSONArray, start: Int, sensitive: Set<String>): Long =
        AgentConversationCodec.transcript(messages, start, sensitive)
            .sumOf { AgentContextBudget.countMessage(it).toLong() }

    private fun perMessageTokens(messages: JSONArray, start: Int, sensitive: Set<String>): Long {
        var total = 0L
        for (index in start until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            total += AgentContextBudget.countMessage(
                AgentConversationCodec.durableMessage(message, sensitive),
            ).toLong()
        }
        return total
    }

    @Test fun perMessageTokensMatchTheFullTranscriptTokenSum() {
        for (sensitive in listOf(emptySet(), setOf("call_secret"))) {
            val messages = history()
            assertEquals(fullPassTokens(messages, 0, sensitive), perMessageTokens(messages, 0, sensitive))
            assertEquals(fullPassTokens(messages, 1, sensitive), perMessageTokens(messages, 1, sensitive))
        }
    }

    @Test fun redactedTokenCountDiffersFromUnredacted() {
        // 脱敏替换了敏感工具正文，词元数必须跟着变（方向不重要，是否变化才重要）；
        // 否则增量缓存会把旧值留在总额里。
        val messages = history()
        assertNotEquals(
            perMessageTokens(messages, 0, emptySet()),
            perMessageTokens(messages, 0, setOf("call_secret")),
        )
    }

    private fun history(): JSONArray = JSONArray()
        .put(JSONObject().put("role", "system").put("content", "系统提示"))
        .put(JSONObject().put("role", "user").put("content", "帮我看看屏幕"))
        .put(
            JSONObject().put("role", "assistant").put("content", "好的")
                .put(AgentTurnIdentity.JSON_KEY, "turn-1")
                .put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject().put("id", "call_ok").put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", "read_file")
                                    .put("arguments", """{"path":"/workspace/a.txt"}"""),
                            ),
                    ),
                ),
        )
        .put(
            JSONObject().put("role", "tool").put("tool_call_id", "call_ok")
                .put("content", """{"ok":true,"text":"hello"}"""),
        )
        .put(
            JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                .put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject().put("id", "call_secret").put("type", "function")
                            .put(
                                "function",
                                JSONObject().put("name", "memory_get")
                                    .put("arguments", """{"key":"私密偏好"}"""),
                            ),
                    ),
                ),
        )
        .put(
            JSONObject().put("role", "tool").put("tool_call_id", "call_secret")
                .put("content", """{"ok":true,"value":"非常私密"}"""),
        )
        .put(
            JSONObject().put("role", "user")
                .put(
                    "content",
                    JSONArray().put(
                        JSONObject().put("type", "text").put("text", "看这张图")
                            .let { it },
                    ),
                ),
        )
        .put(JSONObject().put("role", "assistant").put("content", "完成"))

    @Test fun perMessageLengthsSumToTheFullTranscriptLength() {
        for (sensitive in listOf(emptySet(), setOf("call_secret"))) {
            val messages = history()
            assertEquals(
                fullPass(messages, 0, sensitive),
                perMessage(messages, 0, sensitive),
            )
            // 起始索引（systemCount）同样成立：跳过的前缀不参与求和。
            assertEquals(
                fullPass(messages, 1, sensitive),
                perMessage(messages, 1, sensitive),
            )
        }
    }

    @Test fun redactionChangesTheLengthAndInvalidatesCachedEntries() {
        val messages = history()
        val withoutRedaction = perMessage(messages, 0, emptySet())
        val withRedaction = perMessage(messages, 0, setOf("call_secret"))
        assertTrue("脱敏会替换正文，长度必须变化", withRedaction < withoutRedaction)
        // 同一对象在脱敏集合增长后必须给出新长度，否则缓存会把旧值留在总额里。
        // 索引 5 是 call_secret 的 tool 结果（0 system / 1 user / 2 assistant / 3 tool /
        // 4 assistant / 5 tool-secret）。
        val secret = messages.getJSONObject(5)
        assertTrue(
            AgentConversationCodec.durableMessageLength(secret, setOf("call_secret")) <
                AgentConversationCodec.durableMessageLength(secret, emptySet()),
        )
    }

    @Test fun sanitizationAppliesTheSameWayForBothPaths() {
        // 超长正文在 sanitizeMessage 里被截断；两条路径都必须看到截断后的长度。
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", "很长的内容".repeat(50_000)),
        )
        assertEquals(fullPass(messages, 0, emptySet()), perMessage(messages, 0, emptySet()))
        val capped = AgentConversationCodec.durableMessageLength(messages.getJSONObject(0), emptySet())
        assertEquals(
            AgentConversationCodec.toJsonObject(
                AgentConversationCodec.durableMessage(messages.getJSONObject(0)),
            ).toString().length.toLong(),
            capped,
        )
    }

    @Test fun nonObjectEntriesAreSkippedByBothPaths() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "a"))
            .put("not-an-object")
            .put(JSONObject().put("role", "assistant").put("content", "b"))
        assertEquals(fullPass(messages, 0, emptySet()), perMessage(messages, 0, emptySet()))
    }
}
