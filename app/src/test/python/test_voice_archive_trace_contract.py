"""系统语音浮窗关闭后，主程序必须能重建完整工作过程（思考 / 工具卡），而不是只剩结论文字。

外部入口的 run 会同时留下两份记录：
- 归档（AgentRunArchiveStore）：完整事件流，是唯一能还原思考 / 工具卡的来源；
- outbox（AgentRuntimeResultStore）：只有最终 result，折叠后是一条纯文本助手消息。

这两条路径的处理顺序与「已导入」判据决定了中间过程能否保留，本文件把它们钉住。
另外钉住终态通告：归档落库要通知主程序重跑恢复，否则「用户已在主程序里、run 在浮窗里
跑完」这一段没有生命周期事件可用，过程要等到下一次切前台才出现。
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class VoiceArchiveTraceContract(unittest.TestCase):
    def setUp(self):
        self.state = (ROOT / 'ui/app/AgentAppState.kt').read_text()

    def test_every_recovery_entry_imports_archive_first(self):
        # 归档必须先导入：反序会让 recover 先用 result 折出「只有最终答复」的助手消息，
        # 随后归档因 alreadyImported 早退并被删除，思考 / 工具卡永久丢失。
        # 两个入口都要成立——冷启动（init 的构造恢复）与回前台（refreshRuntimeResults）。
        blocks = {
            'init': self.state.split('awaitConversationsHydrated()', 1)[1].split(
                'private fun ownerContext(', 1)[0],
            'resume': self.state.split('fun refreshRuntimeResults()', 1)[1].split(
                'fun refreshMemory(', 1)[0],
        }
        for name, body in blocks.items():
            with self.subTest(entry=name):
                self.assertIn('importArchivedExternalRuns()', body)
                self.assertIn('recoverRuntimeRuns()', body)
                self.assertLess(
                    body.index('importArchivedExternalRuns()'),
                    body.index('recoverRuntimeRuns()'),
                    f'{name} 入口的归档导入必须早于 outbox 折叠',
                )

    def test_archive_import_preloads_target_conversations(self):
        # 归档是往已有会话追加内容：不先把 Room 里的历史读进内存，持久化会用空态覆盖它。
        body = self.state.split('private suspend fun importArchivedExternalRuns()', 1)[1].split(
            'suspend fun openAssistantConversation(', 1)[0]
        self.assertIn('preloadArchivedConversations(', body)
        self.assertIn('archiveConversationId(archived.handoff.source, payload.conversationKey)', body)
        # 预加载要把「完全不在内存」的会话也装进来，否则语音浮窗新建的会话仍会撞空态。
        preload = self.state.split('private suspend fun preloadArchivedConversations(', 1)[1].split(
            'private suspend fun preloadConversationsForRecovery(', 1)[0]
        self.assertIn('conversationContentLoaded != true', preload)
        self.assertIn('AgentConversationStore.loadConversation(appContext, id)', preload)

    def test_work_trace_forces_reimport_over_folded_text(self):
        # 只凭「已有完成的助手消息」判定已导入是不够的：recover 折叠出的纯文本也会命中。
        # 归档有可见工作轨迹、会话里该 run 一条都没有时，必须重放。
        body = self.state.split('private fun importExternalRun(', 1)[1].split(
            'private fun AgentEvent.isVisibleWorkTrace(', 1)[0]
        self.assertIn('val archivedHasWorkTrace = archivedRun.events.any { it.isVisibleWorkTrace() }', body)
        self.assertIn('message.id.startsWith("$runId-thinking-")', body)
        self.assertIn('message.id.startsWith("$runId-tool-")', body)
        self.assertIn('&& !(archivedHasWorkTrace && !stateHasWorkTrace)', body)

    def test_reimport_replays_events_in_batch_and_dedupes_user_message(self):
        body = self.state.split('private fun importExternalRun(', 1)[1].split(
            'private fun AgentEvent.isVisibleWorkTrace(', 1)[0]
        # 逐事件 apply 会让每个增量各自走一遍列表投影与摘要刷新；批量回放自带
        # resetForReplay（清掉该 run 的旧投影），同时保证重导不叠加思考 / 工具卡。
        self.assertIn('restoreRunEvents(runId, archivedRun.events)', body)
        self.assertNotIn('archivedRun.events.forEach { event -> applyRunEvent(', body)
        # 重导时上一条 user-$runId 仍在列表里：同 id 两条会让 LazyColumn key 冲突。
        self.assertIn('existingState.messages.filterNot { it.id == "user-$runId" }', body)

    def test_applied_outbox_result_is_still_acknowledged(self):
        # 归档先导入后，recover 对同一 run 会被判 alreadyApplied（无新消息），
        # 但 outbox 条目仍必须清掉，否则每次启动都重读同一份结果。
        self.assertIn('|| acknowledgeAfterSave.isNotEmpty()', self.state)

    def test_archive_landing_notifies_the_ui_bus(self):
        # 归档在 run 终态写一次，所以「归档落库」本身就是终态信号：主程序正开着时要靠它
        # 立刻重跑恢复，否则内容要等到下一次切前台。
        store = (ROOT / 'agent/runtime/AgentRunArchiveStore.kt').read_text()
        self.assertIn('val archived: SharedFlow<String> = _archived', store)
        body = store.split('fun add(context: Context, run: ArchivedRun)', 1)[1].split(
            'fun list(context: Context)', 1)[0]
        self.assertIn('_archived.tryEmit(archiveRunId)', body)
        # 通告必须在事务之后：订阅方收到就立刻读库，早发会读不到刚写的事件流。
        self.assertGreater(
            body.index('_archived.tryEmit(archiveRunId)'),
            body.index('dao.replaceArchivedRun('),
            '归档通告必须在落库之后发出',
        )

    def test_main_app_listens_for_terminal_archives(self):
        # 订阅方挂一次，收到通告只触发恢复；是否有内容由读库判定。
        self.assertIn('observeRuntimeArchiveCompletions()\n', self.state)
        body = self.state.split('private fun observeRuntimeArchiveCompletions()', 1)[1].split(
            'fun refreshMemory(', 1)[0]
        self.assertIn('AgentRunArchiveStore.archived.collect', body)
        self.assertIn('refreshRuntimeResults()', body)
        # 构造期的早到通告要等会话表水合：空种子会把归档导入误判成新会话并写歪持久化。
        self.assertIn('awaitConversationsHydrated()', body)

    def test_running_recovery_absorbs_new_requests_instead_of_dropping(self):
        # 「已在恢复中就直接 return」会让紧跟 ON_RESUME 恢复之后的终态落库被吞掉；
        # 恢复必须单飞但不丢请求：pending 标志 + 跑完再补一遍。
        body = self.state.split('fun refreshRuntimeResults()', 1)[1].split(
            'fun refreshMemory(', 1)[0]
        self.assertIn('runtimeRefreshPending.set(true)', body)
        self.assertIn('pumpRuntimeRecovery()', body)
        self.assertIn('while (runtimeRefreshPending.compareAndSet(true, false))', body)
        # 关闸后进来的请求撞上 CAS：补泵一次，否则它要等下一次外部触发。
        self.assertIn('if (runtimeRefreshPending.get()) pumpRuntimeRecovery()', body)


if __name__ == '__main__':
    unittest.main()
