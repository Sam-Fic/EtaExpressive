import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
UI = ROOT / "src/main/kotlin/io/github/mangi/eta/ui"
SECTIONS = UI / "screens/virtualdisplay/VirtualDisplaySections.kt"


class VirtualDisplaySettingsUiTest(unittest.TestCase):
    def test_recovery_is_only_nested_under_task_preferences(self):
        settings = (UI / "SettingsScreen.kt").read_text()
        task = (UI / "AgentTaskPreferenceScreen.kt").read_text()
        self.assertNotIn("VirtualDisplayRecoveryPreference", settings)
        self.assertNotIn("vd_recovery_title", settings)
        # 入口始终可见：进程内虚拟屏不依赖 Magisk 后端模块。
        self.assertIn("row(key = SettingsKeys.AGENT_TASK_PREFERENCE)", settings)
        self.assertIn("onNavigate(AppRoute.AgentTaskPreference)", settings)
        self.assertNotIn("if (taskBackendInstalled == true)", settings)
        self.assertNotIn("onOpenRecovery", task)
        self.assertNotIn("VirtualDisplayRecoveryControls(", task)
        self.assertIn("virtual-display-entry", task)
        self.assertIn("onOpenVirtualDisplay", task)
        self.assertNotIn("moduleInstalled != true", task)

    def test_task_preference_page_is_material3_with_radio_group(self):
        task = (UI / "AgentTaskPreferenceScreen.kt").read_text()
        for text in (
            "EtaScaffoldPage(",
            "EtaSegmentedRow(",
            "import androidx.compose.material3.RadioButton",
            "selectableGroup()",
            "Modifier.selectable(",
            "role = Role.RadioButton",
            "EtaSegmentedColumn(",
        ):
            self.assertIn(text, task)
        self.assertNotIn("MiuixScaffoldPage", task)
        # 选择语义不变：可选项仍由 allowsPersist 决定，整行点击仍写同一份持久化值。
        self.assertIn("AgentTaskSurface.allowsPersist(mode)", task)
        self.assertIn("AgentTaskSurface.save(mode)", task)
        self.assertIn("AgentTaskSurface.stored()", task)

    def test_recovery_is_inline_controls_not_separate_page(self):
        page = SECTIONS.read_text()
        self.assertNotIn("Scaffold(", page)
        self.assertNotIn("TopAppBar(", page)
        self.assertIn("Row(", page)
        self.assertIn("FlowRow(", page)
        # 恢复组重排（2f5a57211）：预览两钮与「刷新」自带触感（3 处 click），恢复/强制重置
        # 改为 EtaSegmentedRow 动作行（触感由行自带，不重复触发）；强制重置改走标准
        # EtaDialog 二次确认，不再有内联两段武装确认。按钮文字不允许折行。
        self.assertEqual(3, page.count("TouchHaptics.click(view)"))
        self.assertEqual(3, page.count("maxLines = 1"))
        self.assertEqual(1, page.count("FlowRow("))
        self.assertIn("EtaSegmentedColumn(", page)
        self.assertIn("EtaSegmentedItemContainer {", page)
        self.assertNotIn("Card(", page)
        self.assertNotIn("surfaceContainerHigh", page)
        self.assertIn("VirtualDisplayWebPreview.openWithManualClose(context)", page)
        self.assertNotIn("VirtualDisplayWebPreview.open(context)", page)
        self.assertNotIn("vd_preview_control_open", page)
        self.assertNotIn("WindowDialog", page)
        # 禁止直用 AlertDialog 组件（标准对话框走 EtaDialog/EtaDialogActions 封装）。
        self.assertNotIn("AlertDialog(", page)
        self.assertIn("EtaDialogActions(", page)
        self.assertIn("LaunchedEffect(Unit) { refresh() }", page)
        self.assertIn('snapshot.optBoolean("recoverable")', page)
        self.assertIn("VirtualDisplaySession::recoverAndFinishManually", page)
        self.assertNotIn("VirtualDisplayWebPreview.stop()", page)
        for action in ("vd_preview_open", "vd_preview_revoke", "vd_recovery_refresh", "vd_recovery_action",
                       "vd_recovery_force_reset", "vd_recovery_force_reset_confirm", "vd_recovery_force_reset_summary"):
            self.assertIn("R.string." + action, page)
        self.assertIn('snapshot?.optBoolean("busy")', page)
        self.assertIn("busy -> R.string.vd_recovery_busy", page)
        self.assertIn("val showWeb = installed == true || webPaired", page)
        preview_button = page.split("FlowRow(", 1)[1].split("R.string.vd_preview_open", 1)[0]
        self.assertIn("enabled = installed == true && !working,", preview_button)
        self.assertNotIn('optBoolean("present")', preview_button)
        self.assertNotIn('optBoolean("recoverable")', preview_button)
        root = (UI / "app/AgentAppRoot.kt").read_text()
        self.assertIn("entry<AppRoute.VirtualDisplayRecovery>", root)
        self.assertNotIn("VirtualDisplayRecoveryScreen(onBack = ::popRoute)", root)
        self.assertNotIn("taskRecoveryWorking", root)
        self.assertIn("onOpenVirtualDisplay = { pushRoute(AppRoute.VirtualDisplay) }", root)

    def test_task_preference_routes_share_the_global_swipe_dismiss(self):
        root = (UI / "app/AgentAppRoot.kt").read_text()
        # 边缘横移返回由全局开关统一驱动：方向在 NavDisplay builder 外算好，
        # 逐条 entry 显式传值；不再给单个页面挂 taskPreferenceSwipeDismiss 这类特例。
        self.assertIn("val navSwipeDismiss = when", root)
        self.assertIn("NavSwipeDirection.None", root)
        self.assertNotIn("taskPreferenceSwipeDismiss", root)
        for route in ("AgentTaskPreference", "VirtualDisplayRecovery", "VirtualDisplay"):
            self.assertIn(
                f"entry<AppRoute.{route}>(swipeDismiss = navSwipeDismiss) {{", root,
            )
        self.assertIn("VirtualDisplayScreen(onBack = ::popRoute)", root)

    def test_installation_is_rechecked_on_resume_not_live_backend_status(self):
        source = (UI / "TaskBackendInstallation.kt").read_text()
        self.assertIn("Lifecycle.State.RESUMED", source)
        self.assertIn("value = null", source)
        self.assertIn("AgentTaskSurface.moduleInstalled()", source)
        self.assertNotIn("inspect_virtual_backend", source)
        self.assertIn("awaitCancellation()", source)

    def test_leaving_page_keeps_pairing_and_revocation_is_explicit(self):
        page = SECTIONS.read_text()
        cleanup = page.split("DisposableEffect(Unit) {", 1)[1].split("val snapshot", 1)[0]
        self.assertIn("onDispose {", cleanup)
        self.assertIn("onWorkingChanged(false)", cleanup)
        self.assertNotIn("VirtualDisplayWebPreview.", cleanup)
        self.assertNotIn("VirtualDisplayWebPreview.stop()", page)
        self.assertIn("if (installed == false && !working)", page)
        self.assertIn("if (!stillInstalled)", page)
        revoke_button = page.split("R.string.vd_preview_open", 1)[1].split("R.string.vd_preview_revoke", 1)[0]
        self.assertIn("enabled = webPaired && !working", revoke_button)
        self.assertIn("VirtualDisplayWebPreview.revoke(context)", revoke_button)
        self.assertIn("webPaired = false", revoke_button)
        self.assertNotIn("Lifecycle.Event.ON_PAUSE", page)
        self.assertNotIn("Lifecycle.Event.ON_STOP", page)


if __name__ == "__main__":
    unittest.main()
