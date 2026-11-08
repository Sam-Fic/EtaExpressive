"""「从屏幕边缘横移返回上一页」的源码契约（不依赖编译）。

实现走 miuix 0.9.4 自带的 NavSwipeDismiss：开关 → MainActivity 传值 →
AgentAppRoot 算方向 → 逐条 entry 传 swipeDismiss。任一环断了，表现都是
「手势没反应」或「只有部分页面有反应」，所以用读源码的方式提前拦住。

签名依据（写实现时用 AAR 内 classes.jar 的 javap 核过，改库版本要重新核）：
  NavEntryBuilder.entry(Function1, NavTransition, NavSwipeDirection, Map, Function3)
  第 3 位是 Kotlin 参数 swipeDismiss；entry$default 未传该位时写 aconst_null，
  NavDisplayLayout 只从栈顶 entry 的 metadata 取方向 ⇒ 漏一条就少一页。

已知盲区（改源码结构时请同步更新本文件）：
- 只认字面量 `entry<AppRoute.X>(swipeDismiss = ...)`；若改成辅助函数批量注册会漏检。
- 不校验运行期手势真的能触发、也不校验与首页抽屉的仲裁结果（属于真机验收）。
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / 'src/main/kotlin/io/github/mangi/eta'
UI = MAIN / 'ui'
RES = ROOT / 'src/main/res'
APP_ROOT = UI / 'app/AgentAppRoot.kt'
ACTIVITY = UI / 'MainActivity.kt'
APPEARANCE_SCREEN = UI / 'AppearanceSettingsScreen.kt'
APPEARANCE_MODEL = MAIN / 'data/model/AppearanceSettings.kt'
DATA_STORE = MAIN / 'data/datastore/SettingsDataStore.kt'

ENTRY_RE = re.compile(r'entry<\s*AppRoute\.(\w+)\s*>\s*(\([^)]*\))?')


def _strip_comments(source: str) -> str:
    source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
    return re.sub(r'//[^\n]*', '', source)


class SwipeBackDismissContractTest(unittest.TestCase):
    def setUp(self):
        self.root_source = _strip_comments(APP_ROOT.read_text(encoding='utf-8'))
        self.activity_source = _strip_comments(ACTIVITY.read_text(encoding='utf-8'))
        self.screen_source = _strip_comments(APPEARANCE_SCREEN.read_text(encoding='utf-8'))
        self.model_source = _strip_comments(APPEARANCE_MODEL.read_text(encoding='utf-8'))
        self.store_source = _strip_comments(DATA_STORE.read_text(encoding='utf-8'))

    def test_every_route_entry_declares_swipe_dismiss(self):
        entries = ENTRY_RE.findall(self.root_source)
        self.assertGreater(len(entries), 0, '未抽到任何 entry，正则或源码结构已过期')
        missing = [name for name, args in entries
                   if not args or (
                       'swipeDismiss = navSwipeDismiss' not in args
                       # Home 是唯一合法的例外：固定为 None，见下面的栈底测试。
                       and not (name == 'Home' and 'NavSwipeDirection.None' in args)
                   )]
        self.assertEqual(
            missing, [],
            f'这些路由没传 swipeDismiss，对应页面会没有横移返回：{", ".join(missing)}',
        )

    def test_stack_bottom_home_keeps_the_drawer_gesture(self):
        # 首页左边缘要留给会话抽屉，且栈底没有「上一页」可回；
        # 给它手势只会和抽屉抢拖拽，或让 popRoute 的双击退出守卫弹提示。
        self.assertIn(
            'entry<AppRoute.Home>(swipeDismiss = NavSwipeDirection.None)',
            self.root_source,
        )
        self.assertNotIn('pushRoute(AppRoute.Home)', self.root_source)

    def test_direction_is_computed_outside_builder_and_handles_rtl(self):
        self.assertIn('val navSwipeDismiss = when', self.root_source)
        self.assertIn('NavSwipeDirection.None', self.root_source)
        self.assertIn('NavSwipeDirection.LeftToRight', self.root_source)
        self.assertIn('NavSwipeDirection.RightToLeft', self.root_source)
        self.assertIn('LayoutDirection.Rtl', self.root_source)
        # 方向必须在 NavDisplay 之前算好，不能在 builder 内读 State.value。
        self.assertLess(
            self.root_source.index('val navSwipeDismiss'),
            self.root_source.index('NavDisplay('),
        )

    def test_setting_is_wired_from_activity_to_root(self):
        self.assertIn('swipeBackEnabled = appearance.swipeBackEnabled', self.activity_source)
        self.assertIn('swipeBackEnabled: Boolean = true', self.root_source)
        self.assertIn('!swipeBackEnabled -> NavSwipeDirection.None', self.root_source)

    def test_setting_is_persisted_and_defaults_on(self):
        self.assertIn('val swipeBackEnabled: Boolean = true', self.model_source)
        self.assertIn('booleanPreferencesKey("appearance_swipe_back_enabled")', self.store_source)
        self.assertIn('swipeBackEnabled = this[APPEARANCE_SWIPE_BACK_ENABLED] ?: true',
                      self.store_source)
        self.assertIn('this[APPEARANCE_SWIPE_BACK_ENABLED] = settings.swipeBackEnabled',
                      self.store_source)

    def test_switch_row_is_outside_the_api34_gate(self):
        # 横移返回不依赖系统版本；若被塞进 predictive back 的 API 34 分支，低版本会整行消失。
        # 注释已剥离，边界用下一组的 item key 而不是分组编号注释。
        block = self.screen_source.split('m3_back_gestures', 1)[1].split(
            'item(key = "m3_icon")', 1,
        )[0]
        self.assertIn('R.string.appearance_swipe_back)', block)
        self.assertIn('appearance.swipeBackEnabled', block)
        self.assertIn('onSetSwipeBack', block)
        predictive = block.index('R.string.appearance_predictive_back)')
        swipe = block.index('R.string.appearance_swipe_back)')
        self.assertLess(predictive, swipe, '顺序应与 KernelSU 主题页一致：预测性返回在上')

        # 找到 API 门槛的那个 if 块，用花括号配对算出它的闭合位置；
        # 横移返回开关行必须落在闭合之后，否则低版本不显示。
        gate = block.index('if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)')
        opening = block.index('{', gate)
        depth = 0
        closing = None
        for pos in range(opening, len(block)):
            if block[pos] == '{':
                depth += 1
            elif block[pos] == '}':
                depth -= 1
                if depth == 0:
                    closing = pos
                    break
        self.assertIsNotNone(closing, 'API 门槛的 if 块没闭合，源码结构已过期')
        self.assertNotIn('appearance_swipe_back', block[opening:closing],
                         '横移返回被塞进 API 34 门槛里了')
        self.assertGreater(swipe, closing, '横移返回开关行必须排在 API 门槛之后')

    def test_all_locales_have_the_two_strings(self):
        for locale in ('values', 'values-b+zh+Hans', 'values-b+zh+Hant'):
            xml = (RES / locale / 'strings.xml').read_text(encoding='utf-8')
            self.assertIn('name="appearance_swipe_back"', xml, f'{locale} 缺标题')
            self.assertIn('name="appearance_swipe_back_summary"', xml, f'{locale} 缺说明')


if __name__ == '__main__':
    unittest.main()
