package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun fromJsonObjectReadsReasoningAliasUsedBySomeGateways() {
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", "I will list it.")
            .put("reasoning", "先确认路径")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "list_directory")
                                .put("arguments", "{}"),
                        ),
                ),
            )

        val durable = AgentConversationCodec.fromJsonObject(message)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("先确认路径", durable.reasoningContent)
        assertEquals("先确认路径", replayed.getString("reasoning_content"))
        assertFalse(replayed.has("reasoning"))
    }

    @Test
    fun toolRoundTripPreservesReasoningContentForCompatibleProviders() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", JSONObject.NULL)
            .put("reasoning_content", "先分析工具参数")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "device_info")
                                .put("arguments", "{}")
                        )
                )
            )

        val durable = AgentConversationCodec.durableMessage(assistant)
        val replayed = AgentConversationCodec.toJsonObject(durable)

        assertEquals("先分析工具参数", replayed.getString("reasoning_content"))
        assertEquals("call-1", replayed.getJSONArray("tool_calls").getJSONObject(0).getString("id"))
    }

    @Test
    fun durableImageObservationNeverPersistsBase64Payload() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userMessage(
                text = "屏幕观察",
                images = listOf(
                    AgentModelClient.ModelImage(
                        reference = "data:image/png;base64,${"A".repeat(20_000)}",
                        mimeType = "image/png",
                        bytes = 15_000,
                    )
                ),
            )
        )

        assertFalse(message.contentJson.contains("base64"))
        assertTrue(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun durableUserAttachmentKeepsImageFilePathWithoutBase64() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userPersistedImageMessage(
                text = "看这张图",
                images = listOf(
                    AgentConversationCodec.PersistedImage(
                        path = "/data/user/0/io.github.mangi.eta/cache/eta-chat-images/c1/a.jpg",
                        mimeType = "image/jpeg",
                        displayName = "chat-image-1.jpg",
                    ),
                ),
            )
        )
        assertTrue(message.contentJson.contains("image_file"))
        assertTrue(message.contentJson.contains("/data/user/0/io.github.mangi.eta/cache/eta-chat-images/c1/a.jpg"))
        assertFalse(message.contentJson.contains("base64"))
        assertFalse(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun persistedImageSourcesReadsImageFilePaths() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userPersistedImageMessage(
                text = "看这张图",
                images = listOf(
                    AgentConversationCodec.PersistedImage(
                        path = "/data/user/0/io.github.mangi.eta/cache/eta-chat-images/c1/a.jpg",
                        mimeType = "image/jpeg",
                        displayName = "chat-image-1.jpg",
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("/data/user/0/io.github.mangi.eta/cache/eta-chat-images/c1/a.jpg"),
            AgentConversationCodec.persistedImageSources(message),
        )
    }

    @Test
    fun ipcTranscriptHasHardBudgetAndNeverStartsWithOrphanToolResult() {
        val messages = buildList {
            repeat(30) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(40_000)}",
                    )
                )
                add(
                    AgentModelClient.ConversationMessage(
                        role = "tool",
                        toolCallId = "call-$index",
                        content = "结果-${"y".repeat(40_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "assistant", content = "最终答案"))
        }

        val encoded = AgentConversationCodec.encodeTranscriptForIpc(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentConversationCodec.MAX_IPC_TRANSCRIPT_CHARS)
        assertTrue(decoded.isNotEmpty())
        assertFalse(decoded.first().role == "tool")
        assertTrue(decoded.first().content.contains("容量上限已压缩"))
        assertTrue(decoded.last().content.contains("最终答案"))
    }

    @Test
    fun transferTranscriptKeepsHistoryThatWouldBeCutAtTheBinderLimit() {
        val messages = buildList {
            repeat(40) { index ->
                add(AgentModelClient.ConversationMessage(role = "user", content = "问-$index"))
                add(AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "答-$index-${"x".repeat(40_000)}",
                    turnId = "turn-$index",
                ))
            }
        }
        val encoded = AgentConversationCodec.encodeTranscriptForTransfer(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)
        assertTrue(encoded.length > AgentConversationCodec.MAX_IPC_TRANSCRIPT_CHARS)
        assertEquals(messages.size, decoded.size)
        assertEquals(messages.first().content, decoded.first().content)
        assertFalse(decoded.first().content.contains("容量上限已压缩"))
    }

    @Test
    fun conversationCheckpointHasHardBudgetAndKeepsNewestContext() {
        val messages = buildList {
            repeat(30) { index ->
                add(
                    AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = "回答-$index-${"x".repeat(50_000)}",
                    )
                )
            }
            add(AgentModelClient.ConversationMessage(role = "user", content = "继续处理最新任务"))
        }

        val encoded = AgentConversationCodec.encodeConversationCheckpoint(messages)
        val decoded = AgentConversationCodec.decodeTranscript(encoded)

        assertTrue(encoded.length <= AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS)
        assertTrue(decoded.first().content.contains("容量上限已压缩"))
        assertEquals("继续处理最新任务", decoded.last().content)
    }

    @Test
    fun responsesOutputItemsStayInMemoryAndNeverEnterStableTranscript() {
        val source = JSONObject().put("role", "assistant").put("content", "完成")
        ResponsesEphemeralState.attachOutputItems(
            source,
            JSONArray().put(
                JSONObject()
                    .put("type", "reasoning")
                    .put("encrypted_content", "opaque-secret"),
            ),
        )
        val history = AgentConversationCodec.assistantHistoryMessage(source, emptyList())
        assertTrue(ResponsesEphemeralState.outputItems(history) != null)

        val stable = AgentConversationCodec.durableMessage(history)
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(listOf(stable))
        assertFalse(encoded.contains("opaque-secret"))
        assertFalse(encoded.contains("_eta_responses_output_items"))
    }

    @Test
    fun durableUserAttachmentKeepsVideoFilePathWithoutBase64() {
        val message = AgentConversationCodec.durableMessage(
            AgentConversationCodec.userPersistedImageMessage(
                text = "看这段视频",
                images = listOf(
                    AgentConversationCodec.PersistedImage(
                        path = "/data/user/0/io.github.mangi.eta/cache/eta-chat-images/c1/a.mp4",
                        mimeType = "image/jpeg",
                        displayName = "chat-video-1.mp4",
                    ),
                ),
            )
        )
        assertTrue(message.contentJson.contains("video_file"))
        assertTrue(message.contentJson.contains("/data/user/0/io.github.mangi.eta/cache/eta-chat-images/c1/a.mp4"))
        assertFalse(message.contentJson.contains("base64"))
        assertFalse(message.contentJson.contains("未写入持久会话"))
    }

    @Test
    fun sensitiveArgumentsRedactIntoSelfDescribingPlaceholder() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call-1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "tool_call")
                                .put("arguments", "{\"name\":\"mcp_demo_read\",\"arguments\":{\"query\":\"私密\"}}"),
                        ),
                ),
            )

        val durable = AgentConversationCodec.durableMessage(assistant, setOf("call-1"))
        val replayed = AgentConversationCodec.toJsonObject(durable)
        val arguments = replayed.getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function").getString("arguments")

        // `redacted` 布尔键是 UI 识别脱敏调用的依据；note 自述占位性质，防止模型照抄。
        assertTrue(JSONObject(arguments).optBoolean("redacted"))
        assertTrue(arguments.contains("历史脱敏占位"))
        assertTrue(arguments.contains("不要照抄"))
        assertFalse(arguments.contains("私密"))
    }

    @Test
    fun redactedArgumentsPlaceholderDetectionCoversLegacyAndNotedShapes() {
        assertTrue(AgentConversationCodec.isRedactedArgumentsPlaceholder("{\"redacted\":true}"))
        assertTrue(AgentConversationCodec.isRedactedArgumentsPlaceholder("{\"redacted\":true,\"note\":\"x\"}"))
        assertFalse(AgentConversationCodec.isRedactedArgumentsPlaceholder("{}"))
        assertFalse(AgentConversationCodec.isRedactedArgumentsPlaceholder("not-json"))
        assertFalse(AgentConversationCodec.isRedactedArgumentsPlaceholder(""))
        assertFalse(AgentConversationCodec.isRedactedArgumentsPlaceholder(null))
    }
}
