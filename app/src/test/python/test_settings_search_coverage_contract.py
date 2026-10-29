import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
UI = ROOT / "src/main/kotlin/io/github/mangi/eta/ui"

CATALOG = UI / "SettingsCatalog.kt"
SCREEN = UI / "SettingsScreen.kt"
SEARCH = UI / "SettingsSearchScreen.kt"

# 直接开系统设置页的入口：主页没有对应行（悬浮窗 / 无障碍走 Intent，不进设置主页），
# 因此不要求它们在主页挂锚点。
INTENT_ONLY_KEYS = {"OVERLAY_PERMISSION", "ACCESSIBILITY_TOOLS"}


def catalog_keys(source):
    return re.findall(r"key = SettingsKeys\.(\w+)", source)


def catalog_keywords(source):
    return re.findall(r"keywords = \"([^\"]*)\"", source)


class SettingsSearchCoverageContractTest(unittest.TestCase):
    """设置搜索的覆盖度契约。

    背景：搜索索引曾经是搜索页里手写的 27 条硬编码列表，与主页实际渲染的行完全脱钩——
    端侧动作模型、媒体语义搜索这类有页面的入口，以及全部开关 / 选择器 / 一次性动作都搜不到。
    现在索引是 `SettingsCatalog.kt` 里的一份总表，主页每一行挂同一个 `SettingsKeys` 锚点键。
    这组断言盯住两件事：新增设置项必须进总表，总表条目必须能在主页落地。
    """

    def test_search_index_shares_catalog_with_home_page(self):
        search = SEARCH.read_text()
        # 搜索页不再自带条目表，改为消费主页同一份目录。
        self.assertNotIn("SettingsSearchEntry(", search)
        self.assertIn("settingsCatalog(taskBackendInstalled = taskBackendInstalled)", search)
        self.assertIn("hit.entry.destination", search)

    def test_every_catalog_entry_has_keywords(self):
        catalog = CATALOG.read_text()
        keys = catalog_keys(catalog)
        keywords = catalog_keywords(catalog)
        self.assertTrue(keys)
        self.assertEqual(len(keys), len(keywords))
        for key, word in zip(keys, keywords):
            self.assertTrue(word.strip(), "设置项 %s 缺 keywords，同义词搜不到" % key)

    def test_catalog_keys_are_anchored_on_home_page(self):
        catalog = CATALOG.read_text()
        screen = SCREEN.read_text()
        anchored = set(re.findall(r"row\(key = SettingsKeys\.(\w+)", screen))
        for key in catalog_keys(catalog):
            if key in INTENT_ONLY_KEYS:
                continue
            self.assertIn(key, anchored, "目录项 %s 没有在主页挂锚点，命中后无处可滚" % key)

    def test_home_page_rows_only_use_declared_keys(self):
        catalog = CATALOG.read_text()
        screen = SCREEN.read_text()
        declared = set(catalog_keys(catalog))
        for key in re.findall(r"row\(key = SettingsKeys\.(\w+)", screen):
            self.assertIn(key, declared, "主页挂了未登记的锚点 %s，搜索会漏掉这一行" % key)

    def test_every_navigable_setting_page_is_in_catalog(self):
        catalog = CATALOG.read_text()
        screen = SCREEN.read_text()
        indexed = set(re.findall(r"AppRoute\.(\w+)", catalog))
        for route in re.findall(r"onNavigate\(AppRoute\.(\w+)", screen):
            if route == "SettingsSearch":
                continue
            self.assertIn(route, indexed, "主页能跳到 %s，但搜索目录没收录" % route)

    def test_search_page_routes_inline_rows_back_to_home_page(self):
        search = SEARCH.read_text()
        screen = SCREEN.read_text()
        # 行内控件没有子页：搜索结果回主页聚焦，主页按焦点键滚动并短暂高亮。
        self.assertIn("SettingsDestination.Inline ->\n", search)
        self.assertIn("onFocusInSettings(hit.entry.key)", search)
        self.assertIn("fun SettingsSearchScreen(", search)
        self.assertIn("onFocusInSettings: (String) -> Unit,", search)
        self.assertIn("focusKey: String? = null,", screen)
        self.assertIn("onFocusConsumed: () -> Unit = {},", screen)
        self.assertIn("class SettingsAnchorIndex", screen)
        self.assertIn("fun positionOf(key: String): Int?", screen)

    def test_focus_key_is_held_outside_the_route_stack(self):
        root = UI / "app" / "AgentAppRoot.kt"
        source = root.read_text()
        # 焦点键不放路由参数：否则回主页会多压一层设置页，返回栈变长。
        self.assertIn("var settingsFocusKey by rememberSaveable { mutableStateOf<String?>(null) }", source)
        self.assertIn("focusKey = settingsFocusKey,", source)
        self.assertIn("onFocusConsumed = { settingsFocusKey = null },", source)
        self.assertIn("settingsFocusKey = key", source)
        self.assertNotIn("data class Settings(", (UI / "navigation" / "AppRoute.kt").read_text())


if __name__ == "__main__":
    unittest.main()
