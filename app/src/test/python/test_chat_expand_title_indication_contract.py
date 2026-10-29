"""展开行统一走官方 M3 Expressive SegmentedListItem 可展开气泡。

Source contracts, not a runtime rendering or performance test. 思考与工具行都通过
`AgentWorkSegmentedBubble`（官方 `SegmentedListItemWithExpansionSample` 的标准结构）
渲染：父项 `SegmentedListItem(onClick = ...)` 提供整行命中区、涟漪与 `stateDescription`
语义，展开箭头由气泡统一给出；行内不再自绘披露标题，也不再挂第二个点击区。
"""
from pathlib import Path
import re
import unittest

from test_agent_chat_viewport_contract import balanced_end, code_only


ROOT = Path(__file__).resolve().parents[3]
CHAT = ROOT / "src/main/kotlin/io/github/mangi/eta/ui/components/ChatMessageItem.kt"
BUBBLE = ROOT / "src/main/kotlin/io/github/mangi/eta/ui/components/AgentWorkSegmentedBubble.kt"


def function_body(source, name):
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
    if match is None:
        raise AssertionError(f"Missing function {name}")
    start = source.index("(", match.start())
    end = balanced_end(source, start, "(", ")")
    start = source.index("{", end)
    return source[start + 1:balanced_end(source, start, "{", "}")]


def call_args(source, name):
    match = re.search(rf"\b{re.escape(name)}\s*\(", source)
    if match is None:
        raise AssertionError(f"Missing call {name}")
    opening = source.index("(", match.start())
    end = balanced_end(source, opening, "(", ")")
    return match.start(), end + 1, source[opening + 1:end]


def clickable_calls(source):
    for match in re.finditer(r"\.clickable\s*\(", source):
        opening = source.index("(", match.start())
        end = balanced_end(source, opening, "(", ")")
        yield match.start(), end + 1, source[opening + 1:end]


class ChatExpandTitleIndicationContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = code_only(CHAT.read_text(encoding="utf-8"))
        cls.bubble = code_only(BUBBLE.read_text(encoding="utf-8"))

    def bubble_call(self, name):
        body = function_body(self.source, name)
        calls = re.findall(r"\bAgentWorkSegmentedBubble\s*\(", body)
        self.assertEqual(len(calls), 1, f"Expected exactly one segmented bubble call in {name}")
        start, end, args = call_args(body, "AgentWorkSegmentedBubble")
        self.assertRegex(args, r"\bonToggle\s*=")
        # 官方 SegmentedListItem 自带涟漪与语义，这里不应再自己挂一个压制反馈的点击区。
        self.assertNotRegex(args, r"\bindication\s*=\s*null\b")
        self.assertNotIn(".clickable", args)
        return body, start, end, args

    def toggle_body(self, body):
        match = re.search(r"val toggle:\s*\(\)\s*->\s*Unit\s*=\s*\{", body)
        self.assertIsNotNone(match, "Missing toggle lambda")
        start = body.index("{", match.end() - 1)
        return body[start + 1:balanced_end(body, start, "{", "}")]

    def test_thinking_row_keeps_expansion_callback(self):
        body, _, _, args = self.bubble_call("ThinkingRow")
        self.assertRegex(args, r"\bonToggle\s*=\s*toggle\b")
        callback = self.toggle_body(body)
        self.assertIn("manuallyExpanded = true", callback)
        self.assertIn("expandedByTap = !expanded", callback)
        self.assertIn("expanded = !expanded", callback)
        self.assertIn("StreamPerformanceDiagnostics.markToggle", callback)

    def test_toggle_stays_confined_to_the_segmented_parent_row(self):
        # 折叠/展开只由官方 SegmentedListItem 父项触发，展开正文不再叠加第二个点击区；
        # 与工具行、提问卡片保持一致。
        body, call_start, _, _ = self.bubble_call("ThinkingRow")
        self.assertNotIn(".clickable", body)
        self.assertNotIn("pointerInput", body)
        details = body.index("val thinkingDetails")
        self.assertLess(details, call_start, "Details content must be defined before the bubble call")

    def test_tool_row_keeps_expansion_callback(self):
        body, _, _, args = self.bubble_call("ToolActivityInline")
        self.assertRegex(args, r"\bonToggle\s*=\s*if\s*\(\s*hasDetails\s*\)\s*toggle\s*else\s*null\b")
        callback = self.toggle_body(body)
        self.assertIn("expandedByTap = !isExpanded", callback)
        self.assertIn("isExpanded = !isExpanded", callback)
        self.assertIn("StreamPerformanceDiagnostics.markToggle", callback)

    def test_tool_row_is_only_toggleable_with_details(self):
        body, _, _, args = self.bubble_call("ToolActivityInline")
        # 没有详情时整行不可点击（onToggle = null），与详情内容的 hasDetails 守卫同源。
        self.assertRegex(args, r"\bonToggle\s*=\s*if\s*\(\s*hasDetails\s*\)\s*toggle\s*else\s*null\b")
        self.assertIn("if (!hasDetails) {", body)

    def test_bubble_is_the_official_sample_structure(self):
        # SegmentedListItemWithExpansionSample：父项 onClick + segmentedShapes(itemCount)
        # 动态形状 + AnimatedVisibility 配官方 motionScheme.fastSpatialSpec + stateDescription。
        self.assertIn("SegmentedListItem(", self.bubble)
        self.assertIn("onClick = onToggle,", self.bubble)
        self.assertIn("ListItemDefaults.segmentedShapes(index = 0, count = 1)", self.bubble)
        # 圆角 morph：展开/收起时父项下沿圆角随弹簧在 16dp 外圆与 4dp 内圆之间过渡。
        self.assertIn("animateDpAsState", self.bubble)
        self.assertIn("RoundedCornerShape(", self.bubble)
        self.assertIn("Spring.DampingRatioNoBouncy", self.bubble)
        self.assertIn("expandVertically(StandardExpandSpec)", self.bubble)
        self.assertIn("shrinkVertically(StandardExpandSpec)", self.bubble)
        self.assertIn("Spring.DampingRatioNoBouncy", self.bubble)
        self.assertIn("stateDescription", self.bubble)
        self.assertIn("ListItemDefaults.SegmentedGap", self.bubble)

    def test_no_click_indication_suppression_is_left_in_the_chat_item(self):
        suppressed = [args for _, _, args in clickable_calls(self.source)
                      if re.search(r"\bindication\s*=\s*null\b", args)]
        self.assertEqual([], suppressed)
        self.assertNotIn("LocalIndication", self.source)
        self.assertNotIn("WithoutPressRipple", self.source)
        self.assertNotIn("LocalRippleConfiguration", self.source)


if __name__ == "__main__":
    unittest.main()
