"""Non-compiling guards for the lazy-conversation persistence boundary."""
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[4]
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta"

class ConversationMemoryContractTest(unittest.TestCase):
    def test_app_loads_selected_content_only(self):
        text = (SRC / "ui/app/AgentAppState.kt").read_text()
        # 启动水合改走 loadSuspending（IO 线程一次性发布），备份重载仍走阻塞式 load；
        # 两条路径都必须带 selectedOnly，只加载选中会话的内容。
        self.assertIn("AgentConversationStore.loadSuspending(appContext, selectedOnly = true)", text)
        self.assertIn("AgentConversationStore.load(appContext, selectedOnly = true)", text)
        self.assertNotIn("private val initialConversations", text)

    def test_placeholder_is_an_explicit_state_not_empty_messages(self):
        text = (SRC / "ui/model/AgentChatUiState.kt").read_text()
        self.assertIn("val conversationContentLoaded: Boolean = true", text)
        store = (SRC / "ui/app/AgentConversationStore.kt").read_text()
        self.assertIn("conversationContentLoaded = withContent", store)

    def test_unloaded_content_is_not_replaced(self):
        text = (SRC / "ui/app/AgentConversationStore.kt").read_text()
        save = text[text.index("suspend fun save("):text.index("fun searchStoredConversation(")]
        self.assertIn("if (!state.conversationContentLoaded) continue", save)
        # 内容重写入口改为按分页边界的 deleteMessagesFrom（0 = 历史全量重写）；
        # 未加载会话的守卫仍在任何消息删除之前。
        self.assertLess(save.index("if (!state.conversationContentLoaded) continue"), save.index("dao.deleteMessagesFrom("))
        self.assertNotIn("dao.deleteMessages()", save)
        self.assertNotIn("dao.insertConversations(", save)

    def test_parent_metadata_never_uses_replace(self):
        dao = (SRC / "data/db/ConversationDao.kt").read_text()
        self.assertIn("@Update(entity = ConversationEntity::class)", dao)
        self.assertIn("suspend fun updateConversationMetadata", dao)
        self.assertIn("@Insert(onConflict = OnConflictStrategy.IGNORE)", dao)

    def test_clean_content_eviction_follows_commit(self):
        text = (SRC / "ui/app/AgentAppState.kt").read_text()
        self.assertIn("saved[id] === state", text)
        self.assertIn("state.pendingImages.isNotEmpty()", text)
        self.assertIn("state.isPaused", text)
        self.assertIn(
            "releasePersistedConversationContent(snapshot.conversations, snapshot.contentConversationIds)",
            text,
        )

    def test_large_heap_is_app_manifest_policy(self):
        app = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot().find("application")
        self.assertEqual("true", app.get("{http://schemas.android.com/apk/res/android}largeHeap"))

    def test_queue_releases_payload_and_checks_worker_cancellation(self):
        text = (SRC / "ui/app/LatestConversationSaveQueue.kt").read_text()
        self.assertIn("batch.payload = null", text)
        self.assertIn("batch.release()", text)
        self.assertIn("currentCoroutineContext().ensureActive()", text)
        self.assertIn("activeContext?.ensureActive()", text)
        state = (SRC / "ui/app/AgentAppState.kt").read_text()
        save = state[state.index("private fun persistConversations("):state.index("private suspend fun writeConversationSnapshot(")]
        self.assertTrue("previous?.join()" not in save, "save queue must not retain predecessor snapshots")
        self.assertTrue("persistenceQueue.submit(snapshot, onSaved)" in save)

    def test_delete_all_commits_before_cache_or_ui_clear(self):
        text = (SRC / "ui/app/AgentAppState.kt").read_text()
        block = text[text.index("fun deleteAllConversations()"):text.index("fun deleteConversation(conversationId:")]
        self.assertIn("withConversationArchive", block)
        self.assertLess(block.index("AgentConversationStore.save("), block.index("conversationDrafts.clear()"))
        self.assertLess(block.index("AgentConversationStore.save("), block.index("chatImageCache.deleteConversation"))

    def test_view_model_borrows_process_host_but_not_service_leases(self):
        vm = (SRC / "ui/app/AgentAppViewModel.kt").read_text()
        host = (SRC / "ui/app/AgentSessionHost.kt").read_text()
        self.assertIn("AgentSessionHost.get(application).state", vm)
        self.assertIn("application.applicationContext", host)
        self.assertIn("SupervisorJob()", host)
        runtime = (SRC / "agent/runtime/AgentRuntimeService.kt").read_text()
        destroy = runtime[runtime.index("override fun onDestroy()"):runtime.index("private inner class IncomingHandler")]
        self.assertIn("val retiring = sessions.snapshot()", destroy)
        self.assertIn("stopWorker.close(retiring.map { session ->", destroy)
        self.assertIn('session.cancel("Agent Runtime 服务已停止")', destroy)
        self.assertIn("AgentChildRunControl.terminate(session, AgentChildControlPolicy.Reason.USER_CANCEL)", destroy)
        worker = (SRC / "agent/runtime/AgentRuntimeStopWorker.kt").read_text()
        self.assertIn("executor.shutdown()", worker)
        self.assertNotIn("executor.shutdownNow()", worker)
        self.assertIn("drainOwner", (SRC / "agent/runtime/AgentExecutionService.kt").read_text())

    def test_search_callback_is_suspend_and_captures_before_background_scan(self):
        state = (SRC / "ui/app/AgentAppState.kt").read_text()
        block = state[state.index("suspend fun searchHistory("):state.index("private fun currentConversationSearchScope()")]
        self.assertLess(block.index("val titles = conversationTitles"), block.index("runInterruptible(Dispatchers.IO)"))
        page = (SRC / "ui/screens/chat/MessageSearchScreen.kt").read_text()
        self.assertIn("onSearch: suspend (String)", page)

if __name__ == "__main__":
    unittest.main()
