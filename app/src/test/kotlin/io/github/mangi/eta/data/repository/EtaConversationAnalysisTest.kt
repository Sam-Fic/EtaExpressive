package io.github.mangi.eta.data.repository

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaConversationAnalysisTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    private fun message(
        id: String,
        sortIndex: Int,
        type: String,
        content: String = "",
        toolName: String? = null,
        toolStatus: String? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        cachedTokens: Int? = null,
        contextTokens: Int? = null,
        elapsedSeconds: Int? = null,
        argumentsSummary: String? = null,
        resultSummary: String? = null,
        argumentsDetail: String? = null,
        resultDetail: String? = null,
        imageCount: Int = 0,
        imagesJson: String = "[]",
        mediaJson: String = "[]",
        toolsJson: String = "[]",
    ) = EtaConversationAnalysisMessage(
        id = id,
        sortIndex = sortIndex,
        type = type,
        content = content,
        toolName = toolName,
        toolStatus = toolStatus,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedTokens = cachedTokens,
        contextTokens = contextTokens,
        elapsedSeconds = elapsedSeconds,
        argumentsSummary = argumentsSummary,
        resultSummary = resultSummary,
        argumentsDetail = argumentsDetail,
        resultDetail = resultDetail,
        imageCount = imageCount,
        images = emptyList(),
        media = emptyList(),
    )

    @Test
    fun segmentsARoundFromTheToolRowsAndTheAssistantUsageRow() {
        val messages = listOf(
            message("user-1", 0, "user", "帮我看下配置"),
            message("run-1-tool-1-call-a", 1, "tool", toolName = "read_file", toolStatus = "Success"),
            message("run-1-tool-1-call-b", 2, "tool", toolName = "read_file", toolStatus = "Failed"),
            message("assistant-run-1-1-0", 3, "assistant", "好了", inputTokens = 120, outputTokens = 30, cachedTokens = 20),
        )

        val rounds = EtaConversationAnalysisRounds.segment(messages, listOf("run-1"))

        assertEquals(1, rounds.size)
        val round = rounds.single()
        assertEquals("run-1", round.runId)
        assertEquals(1, round.round)
        assertEquals(listOf("user-1", "run-1-tool-1-call-a", "run-1-tool-1-call-b", "assistant-run-1-1-0"), round.messageIds)
        assertEquals(2, round.toolCalls.size)
        assertEquals("read_file", round.toolCalls[0].name)
        assertEquals("Failed", round.toolCalls[1].status)
        assertEquals(120L, round.usage.inputTokens)
        assertEquals(30L, round.usage.outputTokens)
        assertEquals(150L, round.usage.billedTokens)
        assertEquals("好了", round.assistantText)
    }

    @Test
    fun keepsOneRoundWhenTheModelSpeaksBeforeCallingATool() {
        // 同一轮里模型先说话再调工具：两个 assistant 行属于同一回合，用量只落在最后一行。
        val messages = listOf(
            message("user-1", 0, "user", "继续"),
            message("assistant-run-1-1-0", 1, "assistant", "我先查一下"),
            message("run-1-tool-1-call-a", 2, "tool", toolName = "list_dir", toolStatus = "Success"),
            message("assistant-run-1-1-1", 3, "assistant", "查完了", inputTokens = 200, outputTokens = 50),
        )

        val rounds = EtaConversationAnalysisRounds.segment(messages, listOf("run-1"))

        assertEquals(1, rounds.size)
        assertEquals(1, rounds.single().toolCalls.size)
        assertEquals(200L, rounds.single().usage.inputTokens)
        assertEquals("查完了", rounds.single().assistantText)
    }

    @Test
    fun splitsRunsAndFallsBackToOrderForMessagesWithoutRunIdentity() {
        val messages = listOf(
            message("user-1", 0, "user", "第一问"),
            message("assistant-run-1-1-0", 1, "assistant", "答一", inputTokens = 10, outputTokens = 1),
            message("user-2", 2, "user", "第二问"),
            message("assistant-run-2-1-0", 3, "assistant", "答二", inputTokens = 20, outputTokens = 2),
        )

        val rounds = EtaConversationAnalysisRounds.segment(messages, listOf("run-1", "run-2"))

        assertEquals(2, rounds.size)
        assertEquals(listOf("run-1", "run-2"), rounds.map { it.runId })
        assertEquals(10L, rounds[0].usage.inputTokens)
        assertEquals(20L, rounds[1].usage.inputTokens)
    }

    @Test
    fun keepsARunSupplementInsideTheRoundItBelongsTo() {
        val messages = listOf(
            message("user-turn-1", 0, "user", "先做这个"),
            message("user-turn-1-supplement-1", 1, "user", "补充一句"),
            message("assistant-run-1-1-0", 2, "assistant", "好", inputTokens = 5, outputTokens = 5),
        )

        val rounds = EtaConversationAnalysisRounds.segment(messages, listOf("run-1"))

        assertEquals(1, rounds.size)
        assertEquals(3, rounds.single().messageIds.size)
    }

    @Test
    fun summaryAggregatesToolCallsAndTokenUsage() {
        val messages = listOf(
            message("user-1", 0, "user", "问题"),
            message("run-1-tool-1-call-a", 1, "tool", toolName = "read_file", toolStatus = "Success"),
            message("run-1-tool-2-call-b", 2, "tool", toolName = "read_file", toolStatus = "Failed"),
            message("run-1-tool-2-call-c", 3, "tool", toolName = "grep", toolStatus = "Success"),
            message("assistant-run-1-2-0", 4, "assistant", "答案", inputTokens = 300, outputTokens = 60, cachedTokens = 40, contextTokens = 900),
            message("compact-1", 5, "context_compacted", "摘要", inputTokens = 7, outputTokens = 3),
        )

        val rounds = EtaConversationAnalysisRounds.segment(messages, listOf("run-1"))
        val summary = EtaConversationAnalysisRounds.summarize(messages, rounds)

        assertEquals(6, summary.messageCount)
        assertEquals(3, summary.toolCallCount)
        assertEquals(1, summary.failedToolCallCount)
        assertEquals(2, summary.tools.size)
        assertEquals("read_file", summary.tools.first().name)
        assertEquals(2, summary.tools.first().count)
        assertEquals(1, summary.tools.first().failedCount)
        // 压缩行记的是压缩时保留下来的用量，和应用自身的账单口径一致：算进总数。
        assertEquals(307L, summary.usage.inputTokens)
        assertEquals(63L, summary.usage.outputTokens)
        assertEquals(40L, summary.usage.cachedTokens)
        // 回合用量只取 assistant 行。
        assertEquals(300L, rounds.sumOf { it.usage.inputTokens })
    }

    @Test
    fun codecRoundTripsTheDocument() {
        val document = EtaConversationAnalysisDocument(
            exportedAt = 42,
            conversation = EtaConversationAnalysisConversation(
                id = "conv-1",
                title = "标题",
                createdAt = 1,
                updatedAt = 2,
                providerId = "provider",
                modelId = "model",
            ),
            options = EtaConversationAnalysisOptions(includeRawRuntimeIo = true, includeImages = true),
            summary = EtaConversationAnalysisSummary(roundCount = 1, toolCallCount = 2),
            rounds = listOf(
                EtaConversationAnalysisRound(
                    index = 0,
                    runId = "run-1",
                    round = 1,
                    usage = EtaConversationAnalysisUsage(inputTokens = 7),
                    toolCalls = listOf(
                        EtaConversationAnalysisToolCall(name = "read_file", status = "Success"),
                    ),
                ),
            ),
            messages = listOf(message("user-1", 0, "user", "问题")),
            history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "问题")),
        )

        val encoded = EtaConversationAnalysisCodec.encode(document)
        val decoded = EtaConversationAnalysisCodec.decode(encoded)

        assertEquals(document, decoded)
        assertTrue(encoded.contains("\"eta-conversation-analysis\""))
        assertTrue(EtaConversationAnalysisCodec.looksLikeAnalysis(encoded))
    }

    @Test
    fun codecRejectsForeignDocuments() {
        val failure = runCatching {
            EtaConversationAnalysisCodec.decode("""{"format":"something-else","schemaVersion":1}""")
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun exportsRoundsUsageAndToolDetailsFromTheDatabase() = runBlocking {
        seedConversation()

        val document = EtaConversationAnalysisRepository.export(context, "conv-1")

        assertEquals("conv-1", document.conversation.id)
        assertEquals("测试会话", document.conversation.title)
        assertEquals(2, document.rounds.size)
        assertEquals(2, document.summary.roundCount)
        assertEquals(1, document.summary.toolCallCount)
        assertEquals("read_file", document.summary.tools.single().name)
        assertEquals(100L, document.summary.usage.inputTokens)
        assertEquals(20L, document.summary.usage.outputTokens)
        val toolCall = document.rounds.first().toolCalls.single()
        assertEquals("read_file", toolCall.name)
        assertEquals("Success", toolCall.status)
        assertEquals("/etc/hosts", toolCall.argumentsSummary)
        // 模型历史也跟着导出，导入后才能接上上下文。
        assertEquals(1, document.history.size)
        assertEquals("问题", document.history.single().content)
    }

    @Test
    fun importRestoresTheConversationAsANewChat() = runBlocking {
        seedConversation()
        val document = EtaConversationAnalysisRepository.export(context, "conv-1")

        val newId = EtaConversationAnalysisRepository.import(context, document)

        assertTrue(newId != "conv-1")
        val dao = EtaDatabase.get(context).conversationDao()
        val conversation = dao.conversationEntity(newId)
        assertNotNull(conversation)
        assertEquals("测试会话", conversation!!.title)
        // 设备相关的模型绑定不跟着走，留空回落到本机默认。
        assertEquals("", conversation.providerId)
        assertEquals("", conversation.modelId)
        assertEquals("[]", conversation.appliedRuntimeRunIdsJson)

        val restored = dao.messagesForConversation(newId)
        assertEquals(4, restored.size)
        assertEquals(listOf("user", "tool", "assistant", "user"), restored.map { it.type })
        assertEquals(100, restored[2].inputTokens)
        assertEquals(20, restored[2].outputTokens)
        assertEquals("read_file", restored[1].toolName)
        assertEquals("/etc/hosts", restored[1].argumentsSummary)
        // 历史落进检查点，导入后的会话能接着聊。
        val checkpoint = dao.contextCheckpoint(newId)
        assertNotNull(checkpoint)
        assertTrue(checkpoint!!.historyJson.contains("问题"))
    }

    @Test
    fun importDropsUnknownMessageTypesAndKeepsTheRest() = runBlocking {
        val document = EtaConversationAnalysisDocument(
            exportedAt = 1,
            conversation = EtaConversationAnalysisConversation(
                id = "conv-1",
                title = "带杂项",
                createdAt = 1,
                updatedAt = 1,
            ),
            messages = listOf(
                message("m-1", 0, "user", "问题"),
                message("m-2", 1, "brand-new-type", "看不懂的类型"),
                message("m-3", 2, "assistant", "回答"),
            ),
        )

        val newId = EtaConversationAnalysisRepository.import(context, document)

        val restored = EtaDatabase.get(context).conversationDao().messagesForConversation(newId)
        assertEquals(listOf("user", "assistant"), restored.map { it.type })
        assertEquals(listOf(0, 1), restored.map { it.sortIndex })
    }

    @Test
    fun exportOmitsImagesAndRuntimeIoUnlessAsked() = runBlocking {
        seedConversation()

        val plain = EtaConversationAnalysisRepository.export(context, "conv-1")
        assertTrue(plain.runtimeRuns.isEmpty())
        assertTrue(plain.messages.all { it.images.isEmpty() })

        val withRuntime = EtaConversationAnalysisRepository.export(
            context,
            "conv-1",
            EtaConversationAnalysisOptions(includeRawRuntimeIo = true, includeImages = true),
        )
        assertTrue(withRuntime.options.includeRawRuntimeIo)
        assertTrue(withRuntime.options.includeImages)
    }

    @Test
    fun exportFailsForAnUnknownConversation() = runBlocking {
        val failure = runCatching { EtaConversationAnalysisRepository.export(context, "missing") }
        assertTrue(failure.isFailure)
    }

    @Test
    fun exportPrefersTheHistoryFileOverTheCheckpoint() = runBlocking {
        seedConversation()
        writeHistoryFile("conv-1", """[{"role":"user","content":"问题"},{"role":"assistant","content":"答案"}]""")

        val document = EtaConversationAnalysisRepository.export(context, "conv-1")

        // 模型历史平时写在文件里，检查点只是兜底；导出要跟应用的加载顺序一致。
        assertEquals(2, document.history.size)
        assertEquals("答案", document.history.last().content)
    }

    @Test
    fun exportReadsTheHistoryFileWhenThereIsNoCheckpoint() = runBlocking {
        seedConversationWithoutCheckpoint()
        writeHistoryFile("conv-2", """[{"role":"user","content":"只有文件"}]""")

        val document = EtaConversationAnalysisRepository.export(context, "conv-2")

        assertEquals(1, document.history.size)
        assertEquals("只有文件", document.history.single().content)
    }

    private fun writeHistoryFile(conversationId: String, json: String) {
        val file = AgentConversationHistoryFiles.file(context, conversationId)
        file.parentFile?.mkdirs()
        file.writeText(json, Charsets.UTF_8)
    }

    private suspend fun seedConversation() {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(
            listOf(
                ConversationEntity(
                    id = "conv-1",
                    title = "测试会话",
                    thinkingEnabled = false,
                    appliedRuntimeRunIdsJson = "[\"run-1\"]",
                    createdAt = 1,
                    updatedAt = 2,
                    providerId = "provider",
                    modelId = "model",
                ),
            ),
        )
        dao.insertMessages(
            listOf(
                ConversationMessageEntity(
                    id = "user-1",
                    conversationId = "conv-1",
                    sortIndex = 0,
                    type = "user",
                    content = "问题",
                ),
                ConversationMessageEntity(
                    id = "run-1-tool-1-call-a",
                    conversationId = "conv-1",
                    sortIndex = 1,
                    type = "tool",
                    content = "cat /etc/hosts",
                    toolName = "read_file",
                    toolStatus = "Success",
                    argumentsSummary = "/etc/hosts",
                ),
                ConversationMessageEntity(
                    id = "assistant-run-1-1-0",
                    conversationId = "conv-1",
                    sortIndex = 2,
                    type = "assistant",
                    content = "答案",
                    inputTokens = 100,
                    outputTokens = 20,
                ),
                ConversationMessageEntity(
                    id = "user-2",
                    conversationId = "conv-1",
                    sortIndex = 3,
                    type = "user",
                    content = "还有呢",
                ),
            ),
        )
        dao.insertContextCheckpoints(
            listOf(
                ConversationContextCheckpointEntity(
                    conversationId = "conv-1",
                    historyJson = """[{"role":"user","content":"问题"}]""",
                ),
            ),
        )
    }

    private suspend fun seedConversationWithoutCheckpoint() {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(
            listOf(
                ConversationEntity(
                    id = "conv-2",
                    title = "没有检查点",
                    thinkingEnabled = false,
                    appliedRuntimeRunIdsJson = "[]",
                    createdAt = 1,
                    updatedAt = 2,
                    providerId = "provider",
                    modelId = "model",
                ),
            ),
        )
        dao.insertMessages(
            listOf(
                ConversationMessageEntity(
                    id = "user-2",
                    conversationId = "conv-2",
                    sortIndex = 0,
                    type = "user",
                    content = "问题",
                ),
            ),
        )
    }
}
