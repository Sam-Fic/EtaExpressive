from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[4]
MAIN = ROOT / 'app/src/main/kotlin/io/github/mangi/eta'

class ThreePageHotpathContract(unittest.TestCase):
    def test_disabled_follow_does_not_read_layout_and_keeps_two_frame_drain(self):
        s = (MAIN / 'ui/components/AgentChatBody.kt').read_text()
        # 严格贴底：禁用时仍然不发布局读取；单纯手指按着不再算中断，
        # 否则按住期间补偿停摆，上提会累积到超过尾部留白后整列掉下去。
        gate = s.index('if (!shouldFollowBottom) return@snapshotFlow InactiveBottomFollowLayout')
        self.assertLess(gate, s.index('val layoutInfo = scrollState.layoutInfo', gate))
        self.assertNotIn('if (!shouldFollowBottom || pointerDown.value) return@snapshotFlow', s)
        settling = s[s.index('var isBottomSettling'):s.index('var initialBottomPositionPending')]
        self.assertEqual(2, settling.count('withFrameNanos { }'))
        self.assertIn('snapshotFlow { !scrollState.canScrollForward }.first { it }', settling)

    def test_light_frames_explicitly_skip_heavy_capture_and_output(self):
        s = (MAIN / 'ui/components/StreamPerformanceDiagnostics.kt').read_text()
        lightweight = s.split('if (!firstDraw && !severe) {')[1].split('} else')[0]
        self.assertIn('detailCaptured = false, sourceWindowUnknown = true', lightweight)
        self.assertNotIn('protectFrame', lightweight)
        emitted = s.split('if (!frame.detailCaptured) {')[1].split('val messages')[0]
        self.assertIn('capture=notSampled evidenceIncomplete=true', emitted)
        self.assertIn('return@forEachIndexed', emitted)
        data = (MAIN / 'ui/components/BoundedStreamDiagnostics.kt').read_text()
        record = data.split('data class DiagnosticFrameRecord(')[1].split(') {')[0]
        self.assertIn('val detailCaptured: Boolean', record)

    def test_settings_summary_reads_are_local_and_permission_labels_registered(self):
        s = (MAIN / 'ui/SettingsScreen.kt').read_text()
        helper = s.split('private fun SettingsProviderEntry(')[1]
        outside = s.split('private fun SettingsProviderEntry(')[0]
        for flow in ('providersFlow()', 'selectedProviderIdFlow()', 'selectedModelIdFlow()'):
            self.assertIn(flow, helper)
            self.assertNotIn(flow, outside)
        self.assertIn('SettingsProviderEntry(currentProviderId, currentModelId)', outside)
        labels = (MAIN / 'ui/components/BoundedStreamDiagnostics.kt').read_text()
        for name in ('overlay', 'accessibility', 'protection', 'assistant'):
            for phase in ('initial', 'resume'):
                label = f'"settings.permission.{name}.{phase}"'
                self.assertIn(label, labels)
        # 主页权限分组只保留「强制无障碍」开关的状态读取。
        # 组合期只读 SP 缓存（isEnabledCached），Settings.Global 的 binder 往返挪到
        # LaunchedEffect 的 Dispatchers.IO 回填，主线程不再吃进转场起步帧；
        # .initial 打点随组合期 binder 读一起移除，ON_RESUME 刷新保留 .resume 打点。
        protection = s[s.index('var accessibilityProtectionEnabled'):s.index('val initialPreferences = remember')]
        self.assertIn('AccessibilityProtectionClient.isEnabledCached(context)', protection)
        self.assertIn('withContext(Dispatchers.IO)', protection)
        self.assertIn('"settings.permission.protection.resume"', protection)
        self.assertNotIn('settings.permission.overlay', s)
        self.assertNotIn('settings.permission.accessibility', s)
        # 悬浮窗/无障碍状态改由权限页呈现；助手活跃状态随四个 hook 分组迁到系统接管子页。
        takeover = (MAIN / 'ui/SystemTakeoverSettingsScreen.kt').read_text()
        for phase in ('initial', 'resume'):
            self.assertIn(f'"settings.permission.assistant.{phase}"', takeover)

    def test_manage_batch_keeps_latest_row_delete_callback(self):
        s = (MAIN / 'ui/screens/chat/ManageChatsScreen.kt').read_text()
        # 行尾删除按钮只把目标记进本地状态，真正的删除在确认对话框里执行；
        # 对话框存活期间父级回调可能重建，因此确认时必须取最新的一份。
        self.assertIn('itemsIndexed(', s)
        self.assertIn('remember(nextOrder, byId)', s)
        self.assertIn('val currentOnDelete by rememberUpdatedState(onDeleteConversation)', s)
        self.assertIn('currentOnDelete(conversation)', s)
        # 左滑删除已移除：管理页不再依赖 SwipeToDismissBox / 抬手势。
        self.assertNotIn('SwipeToDismissBox', s)
        self.assertNotIn('manageChatDeleteDrag', s)

    def test_summary_sink_lifecycle_and_loss_accounting_are_wired(self):
        logger = (MAIN / 'core/AppFileLogger.kt').read_text()
        for operation in ('close', 'clear', 'flush'):
            self.assertIn(f'diagnosticSummarySink?.{operation}()', logger)
        self.assertIn('FileLogSink(directory, DIAGNOSTIC_SUMMARY_FILE).files()', logger)
        stream = (MAIN / 'ui/components/StreamPerformanceDiagnostics.kt').read_text()
        self.assertEqual(2, stream.count('essentialOutputDropped=${output.essentialDropped}'))
