"""Source boundary contracts only; Compose/runtime tests remain necessary."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class BrowserSettingsTransitionContractTest(unittest.TestCase):
    def setUp(self):
        self.sheet = (ROOT / 'agent/browser/ported/ui/browser/BrowserSheet.kt').read_text()

    def test_settings_page_animates_like_a_pushed_page(self):
        # 设置页与浏览器共用同一个 BrowserTabPool，不能拆成独立路由；
        # 页内覆盖层必须逐项对齐 miuix NavDisplay 程序化转场（docs/RULES.md §3.3）：
        # 位移与进度统一 tween(NavigationFallbackDurationMillis, NavProgrammaticEasing)，
        # 顶页全程不透明，effects 层补全（被盖页 scrim 压暗 + 进入页 leading 角圆角裁切）。
        self.assertIn('AnimatedVisibility(', self.sheet)
        self.assertIn('visible = showSettings', self.sheet)
        for required in ('slideInHorizontally', 'slideOutHorizontally',
                         'EtaMotion.NavigationFallbackDurationMillis', 'NavProgrammaticEasing',
                         'NavDisplayEffects.Default.dimAmount', 'rememberNavSystemCornerRadius',
                         'settingsProgress'):
            self.assertIn(required, self.sheet)
        # 顶页全程不透明：给顶页加淡入淡出会在运动中透出底页（§3.3 第 2 条）。
        self.assertNotIn('fadeIn', self.sheet)
        self.assertNotIn('fadeOut', self.sheet)
        self.assertIn('translationX = (if (rtl) 1f else -1f) * settingsProgress * size.width / 4f', self.sheet)
        self.assertIn('BrowserSettingsPage(', self.sheet)
        self.assertIn('tabPool = tabPool', self.sheet)
        # 不再用裸 if 硬切整页。
        self.assertNotIn('if (showSettings) {', self.sheet)

    def test_rtl_slide_is_mirrored(self):
        self.assertIn('LocalLayoutDirection.current == LayoutDirection.Rtl', self.sheet)
        enter = self.sheet.split('enter = slideInHorizontally', 1)[1]
        self.assertIn('if (rtl) -1f else 1f', enter)

    def test_back_still_dismisses_settings_before_leaving_browser(self):
        self.assertIn('BackHandler(enabled = showSettings) { showSettings = false }', self.sheet)


if __name__ == '__main__':
    unittest.main()
