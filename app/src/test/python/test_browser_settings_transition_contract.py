"""Source boundary contracts only; Compose/runtime tests remain necessary."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class BrowserSettingsTransitionContractTest(unittest.TestCase):
    def setUp(self):
        self.sheet = (ROOT / 'agent/browser/ported/ui/browser/BrowserSheet.kt').read_text()

    def test_settings_page_animates_like_a_pushed_page(self):
        # 设置页与浏览器共用同一个 BrowserTabPool，不能拆成独立路由；
        # 页内覆盖层必须补齐与 miuix NavDisplay 路由转场一致的入栈/出栈动画。
        self.assertIn('AnimatedVisibility(', self.sheet)
        self.assertIn('visible = showSettings', self.sheet)
        for required in ('slideInHorizontally', 'slideOutHorizontally', 'fadeIn', 'fadeOut',
                         'defaultSpatialSpec', 'defaultEffectsSpec', 'settingsProgress'):
            self.assertIn(required, self.sheet)
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
