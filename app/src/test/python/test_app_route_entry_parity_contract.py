"""AppRoute 子类型与两份手工清单的源码契约（不依赖编译）。

三方必须一致：
1. `AppRoute.kt` 里声明的子类型全集；
2. `AgentAppRoot.kt` 里 `entry<AppRoute.X>` 的注册集；
3. `AgentNavigator.kt` 里 `appRouteTypes` 的 `AppRoute.X::class` 登记集。

任何一方漏掉/多出，都会在运行期表现为「该页白屏或落到 Unknown page」（miuix 的
`NavEntryBuilder.build()` 对未登记类型直接抛异常），所以用读源码的方式提前拦住。

已知盲区（改源码结构时请同步更新本文件）：
- 只扫描 `AppRoute.kt` 的 `sealed interface AppRoute` 段。若子类型被拆到别的文件、
  或出现嵌套 sealed 子层级、或改用非 `data object` / `data class` 声明，会漏检。
- 只扫描 `AgentAppRoot.kt` 的字面量 `entry<AppRoute.X>`。若改成辅助函数批量注册、
  动态构造，或把 entry DSL 抽到别的文件，会漏检。
- 只能证明「类型被登记」，不能证明 `entry<X>` 里渲染的是正确页面，也不校验
  `contentKey` 唯一性（那属于另一类运行期契约）。
- 注释必须先剥离：KDoc/行注释里出现 `entry<AppRoute.X>` 会被误当成已注册。
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
UI = ROOT / 'src/main/kotlin/io/github/mangi/eta/ui'
APP_ROUTE = UI / 'navigation/AppRoute.kt'
NAVIGATOR = UI / 'navigation/AgentNavigator.kt'
ROOT_SCREEN = UI / 'app/AgentAppRoot.kt'


def _strip_comments(source: str) -> str:
    source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
    return re.sub(r'//[^\n]*', '', source)


def declared_subtypes(source: str):
    body = _strip_comments(source).split('sealed interface AppRoute', 1)[1]
    body = body.split('enum class NewProviderType', 1)[0]
    return re.findall(r'^\s*data\s+(?:object|class)\s+(\w+)', body, re.M)


def entry_types(source: str):
    return re.findall(r'entry<\s*AppRoute\.(\w+)\s*>', _strip_comments(source))


def registered_types(source: str):
    return re.findall(r'AppRoute\.(\w+)::class', _strip_comments(source))


class AppRouteEntryParityContractTest(unittest.TestCase):
    def setUp(self):
        self.declared = declared_subtypes(APP_ROUTE.read_text(encoding='utf-8'))
        self.entries = entry_types(ROOT_SCREEN.read_text(encoding='utf-8'))
        self.registered = registered_types(NAVIGATOR.read_text(encoding='utf-8'))
        # 防真空通过：源码结构一变导致正则零命中时，后面的差集断言会全部「成立」。
        self.assertGreater(len(self.declared), 0, '未从 AppRoute.kt 抽到任何子类型，正则或源码结构已过期')
        self.assertGreater(len(self.entries), 0, '未从 AgentAppRoot.kt 抽到任何 entry<AppRoute.X>')
        self.assertGreater(len(self.registered), 0, '未从 AgentNavigator.kt 抽到任何 AppRoute.X::class')

    def test_every_declared_route_has_an_entry(self):
        missing = sorted(set(self.declared) - set(self.entries))
        self.assertEqual(
            missing, [],
            f'AppRoute 子类型缺少 entry 注册（会退到 Unknown page）：{", ".join(missing)}',
        )

    def test_no_entry_without_a_declared_route(self):
        stale = sorted(set(self.entries) - set(self.declared))
        self.assertEqual(
            stale, [],
            f'entry<AppRoute.X> 指向不存在的子类型（拼写错误或类型已删）：{", ".join(stale)}',
        )

    def test_every_declared_route_is_registered_in_app_route_types(self):
        missing = sorted(set(self.declared) - set(self.registered))
        self.assertEqual(
            missing, [],
            f'AppRoute 子类型未登记到 appRouteTypes（isKnownAppRoute 会判定不可渲染）：{", ".join(missing)}',
        )

    def test_no_stale_registration_in_app_route_types(self):
        stale = sorted(set(self.registered) - set(self.declared))
        self.assertEqual(
            stale, [],
            f'appRouteTypes 登记了不存在的子类型：{", ".join(stale)}',
        )

    def test_no_route_is_registered_twice(self):
        duplicates = sorted({name for name in self.entries if self.entries.count(name) > 1})
        self.assertEqual(
            duplicates, [],
            f'entry<AppRoute.X> 被重复注册（后注册会覆盖先注册）：{", ".join(duplicates)}',
        )


if __name__ == '__main__':
    unittest.main()
