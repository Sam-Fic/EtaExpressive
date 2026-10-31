"""Source contract: compression rows keep their themed title/summary/chevron colors.

The screen itself passes title/summary strings into the shared adaptive rows, so the
颜色/排版契约现在落在 `adaptive/EtaRows.kt`（官方 SegmentedListItem 令牌）与
`adaptive/EtaSegmentedList.kt`（supportingContentColor）里。
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
        # 模型行箭头是 EtaClickableRow 里的官方 KeyboardArrowRight，次级前景 + 无语义描述。
        call = self.call_for(self.rows, "Icon", "Icons.AutoMirrored.Rounded.KeyboardArrowRight")
        self.assertRegex(
            call,
            r"\btint\s*=\s*MaterialTheme\.colorScheme\.onSurfaceVariant\b",
        )
        self.assertRegex(call, r"\bcontentDescription\s*=\s*null\b")

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
        self.assertIn("supportingContent = summary?.let { { Text(text = it) } },", row)


if __name__ == "__main__":
    unittest.main()
