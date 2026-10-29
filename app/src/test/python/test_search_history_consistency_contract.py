from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta/ui'
ADAPTIVE = ROOT / 'adaptive'


def search_page_call_sites():
    """所有引用搜索页骨架的页面文件（排除骨架自身的定义文件）。"""
    for path in sorted(ROOT.rglob('*.kt')):
        if path.parent == ADAPTIVE:
            continue
        text = path.read_text(encoding='utf-8')
        if 'EtaSearchPage(' in text or 'EtaSearchScaffold(' in text:
            yield path, text


class SearchHistoryConsistencyContract(unittest.TestCase):
    """应用内每个独立搜索页都必须有同一套「搜索记录」。

    记录分两处落盘：IME 提交由 EtaSearchPage 按 historyKey 自动记；点开结果由各页
    在跳转前调 EtaSearchHistory.add 补记。少任何一处，实时过滤页（不按搜索键）或
    回车提交页（不点结果）就会出现「永远没有记录」的不一致。
    """

    def test_every_search_page_passes_a_history_key(self):
        missing = []
        for path, text in search_page_call_sites():
            if 'EtaSearchPage(' in text and 'historyKey =' not in text:
                missing.append(path.name)
        self.assertEqual([], missing, f'搜索页缺 historyKey：{missing}')

    def test_every_search_page_records_on_result_tap(self):
        missing = []
        for path, text in search_page_call_sites():
            if 'EtaSearchHistory' not in text:
                missing.append(path.name)
        self.assertEqual([], missing, f'搜索页没有任何搜索记录写入：{missing}')

    def test_history_keys_are_disjoint_per_page(self):
        keys = {}
        for path, text in search_page_call_sites():
            for name, value in re.findall(r'(?:private )?const val (\w*HISTORY\w*) = "([^"]+)"', text):
                keys.setdefault(value, []).append(path.name)
        duplicated = {key: files for key, files in keys.items() if len(files) > 1}
        self.assertEqual({}, duplicated, f'搜索历史分域键冲突：{duplicated}')

    def test_record_list_is_rendered_by_the_shared_helper(self):
        page = (ADAPTIVE / 'EtaSearchPage.kt').read_text(encoding='utf-8')
        # 记录列表只有一份实现：骨架与自绘顶栏的搜索页都走它，版式不会各写一套。
        self.assertIn('fun LazyListScope.EtaSearchHistoryItems(', page)
        self.assertIn('EtaSearchHistoryItems(\n                    history = history,', page)
        media = (ROOT / 'screens/search/MediaSearchScreen.kt').read_text(encoding='utf-8')
        self.assertIn('EtaSearchHistoryItems(', media)
        self.assertIn('EtaSearchHistory.clear(context, HISTORY_KEY)', media)

    def test_no_page_keeps_a_local_copy_of_the_history_list(self):
        # 旧版把组标题 / 记录行内联在骨架里；抽走之后不应再出现第二份同样的版式。
        page = (ADAPTIVE / 'EtaSearchPage.kt').read_text(encoding='utf-8')
        self.assertNotIn('item(key = "search_history_header")', page)
        self.assertNotIn('item(key = "search_history_$index")', page)


if __name__ == '__main__':
    unittest.main()
