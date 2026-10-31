package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AgentConversationHistoryFiles
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 展示分页与保存边界的存储层契约：窗口加载只取最后 N 行、保存以已加载后缀为边界、
 * 上翻页按实际 sort_index 前进，历史文件缺失时回退全量视图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AgentConversationWindowStoreTest {
    private lateinit var context: Context
    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    private fun chat(id: String, count: Int) = AgentChatHomeUiState(
        messages = List(count) { UserMessageUi("$id-$it", "payload-$id-$it") },
        history = listOf(AgentModelClient.ConversationMessage("user", "full history $id")),
        input = "", isStreaming = false, thinkingEnabled = false,
        providerId = "p", modelId = "m",
    )

    private fun save(vararg chats: Pair<String, Int>) = runBlocking {
        val states = chats.associate { (id, count) -> id to chat(id, count) }
        val titles = chats.associate { (id, _) -> id to id.uppercase() }
        val updatedAt = chats.mapIndexed { index, (id, _) -> id to (index + 1L) }.toMap()
        AgentConversationStore.save(context, chats.firstOrNull()?.first, states, titles, updatedAt)
    }

    @Test fun windowedLoadKeepsOlderRowsOnDiskAndFullHistory() = runBlocking {
        save("a" to 300)
        val dao = EtaDatabase.get(context).conversationDao()
        val windowed = requireNotNull(AgentConversationStore.loadConversation(context, "a", displayWindowRows = 40))
        assertEquals(40, windowed.messages.size)
        assertEquals(260, windowed.loadedFromSortIndex)
        assertEquals(260, windowed.earlierPersistedCount)
        assertEquals(300, dao.messageCount("a"))
        assertTrue(windowed.conversationContentLoaded)
        // 模型历史独立于展示分页：来自文件，保持完整。
        assertEquals("full history a", windowed.history.single().content)
        assertEquals("payload-a-260", (windowed.messages.first() as UserMessageUi).content)
        assertEquals("payload-a-299", (windowed.messages.last() as UserMessageUi).content)
    }

    @Test fun smallConversationIgnoresWindowAndStaysFullView() = runBlocking {
        save("a" to 10)
        val windowed = requireNotNull(AgentConversationStore.loadConversation(context, "a", displayWindowRows = 40))
        assertEquals(10, windowed.messages.size)
        assertEquals(0, windowed.loadedFromSortIndex)
        assertEquals(0, windowed.earlierPersistedCount)
    }

    @Test fun windowedSavePreservesRowsBelowBoundaryAndExtendsSuffix() = runBlocking {
        save("a" to 300)
        val dao = EtaDatabase.get(context).conversationDao()
        val windowed = requireNotNull(AgentConversationStore.loadConversation(context, "a", displayWindowRows = 40))
        val withReply = windowed.copy(
            messages = windowed.messages + UserMessageUi("a-300", "payload-a-300"),
            history = windowed.history + AgentModelClient.ConversationMessage("user", "payload-a-300"),
        )
        AgentConversationStore.save(context, "a", mapOf("a" to withReply), mapOf("a" to "A"),
            mapOf("a" to 9L), contentConversationIds = setOf("a"))
        // 边界之前的 260 行原样保留；后缀从边界起重写并接上新消息。
        assertEquals(301, dao.messageCount("a"))
        val stored = dao.messagesForConversation("a")
        val first = stored.first()
        assertEquals("a-0", first.id)
        assertEquals("payload-a-0", first.content)
        assertEquals(0, first.sortIndex)
        assertEquals("a-259", stored.first { it.sortIndex == 259 }.id)
        assertEquals("a-300", stored.last { it.sortIndex == 300 }.id)
    }

    @Test fun windowedSaveCompactsSkippedRowsInsideBoundaryOnly() = runBlocking {
        save("a" to 300)
        val dao = EtaDatabase.get(context).conversationDao()
        val windowed = requireNotNull(AgentConversationStore.loadConversation(context, "a", displayWindowRows = 40))
        // 窗口首行是未完成的空 assistant：序列化时跳过，边界内的行号重新压实，
        // 边界之外的记录不受影响。
        val withSkipped = windowed.copy(messages = listOf(
            AgentMessageUi(id = "a-blank", content = "", isStreaming = true),
        ) + windowed.messages.drop(1))
        AgentConversationStore.save(context, "a", mapOf("a" to withSkipped), mapOf("a" to "A"),
            mapOf("a" to 9L), contentConversationIds = setOf("a"))
        assertEquals(299, dao.messageCount("a"))
        val stored = dao.messagesForConversation("a")
        assertEquals("a-0", stored.first { it.sortIndex == 0 }.id)
        assertEquals("a-259", stored.first { it.sortIndex == 259 }.id)
        assertTrue(stored.none { it.id == "a-blank" })
        assertTrue(stored.none { it.sortIndex >= 300 })
    }

    @Test fun loadEarlierWalksBackwardsByActualSortIndexUntilExhausted() = runBlocking {
        save("a" to 300)
        var boundary = requireNotNull(
            AgentConversationStore.loadConversation(context, "a", displayWindowRows = 40)
        ).loadedFromSortIndex
        assertEquals(260, boundary)
        var remaining = 260
        while (boundary > 0) {
            val page = requireNotNull(
                AgentConversationStore.loadEarlierConversationWindow(context, "a", boundary, rows = 100)
            )
            assertEquals(minOf(100, remaining), page.messages.size)
            assertEquals(page.loadedFromSortIndex, boundary - page.messages.size)
            assertEquals(page.earlierPersistedCount, page.loadedFromSortIndex)
            assertEquals("payload-a-${page.loadedFromSortIndex}",
                (page.messages.first() as UserMessageUi).content)
            boundary = page.loadedFromSortIndex
            remaining = page.earlierPersistedCount
        }
        assertNull(AgentConversationStore.loadEarlierConversationWindow(context, "a", 0, rows = 100))
    }

    @Test fun missingHistoryFileFallsBackToFullLegacyView() = runBlocking {
        save("a" to 300)
        // 模型历史有文件和检查点两级兜底；两级都拿不到时展示行才承担旧版重建，必须全量读取。
        AgentConversationHistoryFiles.file(context, "a").delete()
        EtaDatabase.get(context).conversationDao().deleteContextCheckpoint("a")
        val full = requireNotNull(AgentConversationStore.loadConversation(context, "a", displayWindowRows = 40))
        assertEquals(300, full.messages.size)
        assertEquals(0, full.loadedFromSortIndex)
        assertEquals(0, full.earlierPersistedCount)
        // 展示行承担旧版历史重建：300 条用户消息全部回到模型历史。
        assertEquals(300, full.history.size)
    }
}
