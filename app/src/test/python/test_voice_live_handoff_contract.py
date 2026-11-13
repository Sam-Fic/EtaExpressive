"""浮窗关闭后，仍在跑的 run 必须交给主程序继续流式呈现，而不是等终态归档一次性冒出来。

原来关闭浮窗只把运行与浮窗 UI 解绑：run 继续在 Runtime 里推进，但应用内没有任何 UI 订阅它，
用户进主程序只看到一片空白，直到 run 结束、归档落库、下次切前台才补上全过程。

本文件钉住交接链路：
- 浮窗侧：run 开跑时备好交接信息（runId / 首轮输入 / 档位 / 预览图），终态清空，
  窗口消失（用户关闭 / 让位前台操作 / 服务销毁）时发布一次；
- 主程序侧：只在 run 仍活跃时接管（过期通告交给归档路径），首轮用户消息按归档同一口径补进
  会话，之后的事件走既有的 attach → 投影 → 终态落库通路，不另开渲染路径。
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class VoiceLiveHandoffContract(unittest.TestCase):
    def setUp(self):
        self.state = (ROOT / 'ui/app/AgentAppState.kt').read_text()
        self.overlay = (ROOT / 'agent/voice/EtaAssistantOverlayService.kt').read_text()
        self.root = (ROOT / 'ui/app/AgentAppRoot.kt').read_text()

    def test_handoff_bus_carries_identity_and_replays(self):
        bus = (ROOT / 'agent/runtime/AgentLiveRunHandoff.kt').read_text()
        self.assertIn('internal object AgentLiveRunHandoff', bus)
        # 只发身份与首轮输入：事件仍走 Runtime 的 attach，总线不复制事件流。
        self.assertIn('val runId: String', bus)
        self.assertIn('val payload: AgentExternalArchivePayload', bus)
        # replay：主程序可能晚于浮窗关闭才构造，过期通告由订阅方按「是否仍活跃」丢弃。
        self.assertIn('replay = 2', bus)
        self.assertIn('_notices.tryEmit(notice)', bus)

    def test_overlay_prepares_handoff_per_run_and_clears_it_on_terminal(self):
        submit = self.overlay.split('private fun submitPrompt(', 1)[1].split('runtimeClient.run(', 1)[0]
        self.assertIn('activeRunHandoff = AgentLiveRunHandoff.Notice(', submit)
        self.assertIn('payload = payload', submit)
        self.assertIn('userImagePreviews = previewImages', submit)
        self.assertIn('source = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE', submit)
        # 终态清空：已跑完的单没有实时流可交，结果走归档导入。
        terminal = self.overlay.split('val shouldStopAfterResult', 1)[1].split('runJob = job', 1)[0]
        self.assertIn('if (activeRunHandoff?.runId == runId) activeRunHandoff = null', terminal)

    def test_overlay_hands_off_whenever_the_window_disappears(self):
        helper = self.overlay.split('private fun handoffActiveRun()', 1)[1].split(
            'private fun removeWindow(', 1)[0]
        self.assertIn('activeRunHandoff?.takeIf { it.runId == activeRunId } ?: return', helper)
        self.assertIn('AgentLiveRunHandoff.publish(handoff)', helper)

        dismiss = self.overlay.split('private fun dismissAndStop()', 1)[1].split(
            'scope.launch(Dispatchers.Main.immediate)', 1)[0]
        self.assertIn('handoffActiveRun()', dismiss)
        # 必须先发布再清 activeRunId：helper 用 runId 判「这一单是不是还在跑」。
        self.assertLess(dismiss.index('handoffActiveRun()'), dismiss.index('activeRunId = null'))

        hide = self.overlay.split('private fun hideForForegroundOperation(', 1)[1].split(
            'private fun handoffActiveRun()', 1)[0]
        self.assertIn('handoffActiveRun()', hide)

        destroy = self.overlay.split('override fun onDestroy()', 1)[1].split(
            'private fun showEntry()', 1)[0]
        self.assertIn('handoffActiveRun()', destroy)

    def test_main_app_adopts_only_still_running_runs(self):
        self.assertIn('observeLiveRunHandoffs()\n', self.state)
        adopt = self.state.split('private fun adoptLiveExternalRun(', 1)[1].split(
            'private suspend fun awaitRunActive(', 1)[0]
        # 过期通告（replay 或用户过一阵才打开应用）不能拿半截状态占位，交给归档路径。
        self.assertIn('if (!awaitRunActive(notice.runId)) return@launch', adopt)
        active = self.state.split('private suspend fun awaitRunActive(', 1)[1].split(
            'private fun bindLiveExternalRun(', 1)[0]
        self.assertIn('client.queryActiveRun()', active)
        self.assertIn('AgentRuntimeClient.ActiveRunQuery.Known', active)

    def test_adoption_reuses_the_attach_pipeline_and_dedupes_user_message(self):
        body = self.state.split('private fun bindLiveExternalRun(', 1)[1].split(
            'fun consumeLiveExternalConversationRequest(', 1)[0]
        # 与「派发子 run 挂到工作会话」同一条通路：attach → 事件投影 → 终态落库。
        self.assertIn('startAttachedRun(runId, conversationId)', body)
        self.assertIn('archiveConversationId(notice.source, payload.conversationKey)', body)
        # 同一单交代两次不能叠两条同 id 的用户消息（列表 key 会冲突）。
        self.assertIn('existingState.messages.filterNot { it.id == "user-$runId" }', body)
        self.assertIn('images = notice.userImagePreviews', body)
        # 一次性主页钉子：用户下次进主页落在这一单上。
        self.assertIn('liveExternalConversationRequest = conversationId', body)

    def test_root_pins_home_entry_without_forcing_navigation(self):
        body = self.root.split('LaunchedEffect(agentState.liveExternalConversationRequest)', 1)[1].split(
            '// 助手浮窗口述「去配置」', 1)[0]
        self.assertIn('homeEntryPinnedConversationId = conversationId', body)
        self.assertIn('agentState.consumeLiveExternalConversationRequest(conversationId)', body)
        # 用户可能正在别的页面做事：只留钉子，不抢导航。
        self.assertNotIn('navigator.replace', body)
        self.assertNotIn('navigator.push', body)


if __name__ == '__main__':
    unittest.main()
