"""Source contract: compression rows keep their themed title/summary/chevron colors.

The screen itself passes title/summary strings into the shared adaptive rows, so the
颜色/排版契约现在落在 `adaptive/EtaRows.kt`（官方 SegmentedListItem 令牌）、
`adaptive/EtaSegmentedList.kt`（supportingContentColor）与
`adaptive/EtaChevrons.kt`（EtaTrailingChevron 默认色）里。
"""
from pathlib import Path
import re
import unittest


class ContextCompressionSettingsColorsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        app = Path(__file__).resolve().parents[3]
        ui = app / "src/main/kotlin/io/github/mangi/eta/ui"
        cls.source = (ui / "ContextCompressionSettingsScreen.kt").read_text(encoding="utf-8")
        cls.rows = (ui / "adaptive/EtaRows.kt").read_text(encoding="utf-8")
        cls.segmented = (ui / "adaptive/EtaSegmentedList.kt").read_text(encoding="utf-8")
        cls.chevrons = (ui / "adaptive/EtaChevrons.kt").read_text(encoding="utf-8")

    def call_for(self, text, component, marker):
        # Inspect only the matching call: a correctly colored sibling must not
        # mask an uncolored title or an icon that inherits LocalContentColor.
        for match in re.finditer(rf"\b{component}\(", text):
            depth = 1
            for end in range(match.end(), len(text)):
                char = text[end]
                if char == "(":
                    depth += 1
                elif char == ")":
                    depth -= 1
                if depth == 0:
                    call = text[match.start():end + 1]
                    if marker in call:
                        return call
                    break
        self.fail(f"No {component} call found for {marker}")

    def test_card_titles_use_primary_foreground_and_keep_typography(self):
        # 三处标题现在经 EtaSwitchRow / EtaClickableRow 传入共享分段行。
        for resource in ("ui_auto_compress_context_title", "ui_compress_model_title"):
            with self.subTest(resource=resource):
                self.assertIn(f"R.string.{resource}", self.source)
        row = self.rows.split("fun EtaSwitchRow(", 1)[1].split("fun EtaClickableRow(", 1)[0]
        # 标题排版/前景不再逐处覆盖，直接吃官方 SegmentedListItem 令牌
        # （ItemLabelTextFont = bodyLarge + onSurface 默认内容色）。
        self.assertIn("content = { Text(text = title) },", row)
        self.assertNotIn("contentColor =", row)
        self.assertNotRegex(row, r"Text\([^\n]*color\s*=")

    def test_model_chevron_uses_secondary_foreground(self):
        # 模型行箭头收敛为共享 EtaTrailingChevron（EtaChevrons.kt）：官方 AutoMirrored
        # KeyboardArrowRight，默认次级前景 onSurfaceVariant、contentDescription 默认留空
        # （导航动作由整行播报，不重复念）；宽屏左栏选中行临时换亮底时才改吃配套前景色。
        self.assertIn("EtaTrailingChevron()", self.rows)
        chevron = self.chevrons.split("fun EtaTrailingChevron(", 1)[1].split("fun EtaDownChevron(", 1)[0]
        self.assertIn("Icons.AutoMirrored.Rounded.KeyboardArrowRight", chevron)
        self.assertIn("contentDescription: String? = null", chevron)
        self.assertIn("tint: Color = MaterialTheme.colorScheme.onSurfaceVariant", chevron)
        icon = chevron.split("Icon(", 1)[1]
        # Icon 本体不另行覆盖颜色 / 描述：默认参数原样透传（亮底覆盖优先）。
        self.assertIn("contentDescription = contentDescription", icon)
        self.assertIn("tint = selectedContentColor ?: tint", icon)

    def test_descriptions_keep_secondary_foreground(self):
        # 行摘要（含 “未选择模型”）统一走分段行的 supportingContentColor 令牌。
        for resource in ("ui_auto_compress_context_summary", "model_not_selected"):
            with self.subTest(resource=resource):
                self.assertIn(f"R.string.{resource}", self.source)
        self.assertIn(
            "supportingContentColor = contentColor ?: MaterialTheme.colorScheme.onSurfaceVariant",
            self.segmented,
        )
        row = self.rows.split("fun EtaSwitchRow(", 1)[1].split("fun EtaClickableRow(", 1)[0]
        # 开关行摘要走 when 双态：关闭态 summaryOff 取代开启态 summary，
        # 两态文案都交给分段行的 supportingContentColor 令牌（onSurfaceVariant）。
        self.assertIn("supportingContent = when {", row)
        self.assertIn("!checked && summaryOff != null -> ({ Text(text = summaryOff) })", row)
        self.assertIn("summary != null -> ({ Text(text = summary) })", row)
        # 「未选择模型」等可点行摘要同样走 supportingContent 槽。
        clickable = self.rows.split("fun EtaClickableRow(", 1)[1]
        self.assertIn("supportingContent = summaryText?.let { { Text(text = it) } },", clickable)


if __name__ == "__main__":
    unittest.main()
