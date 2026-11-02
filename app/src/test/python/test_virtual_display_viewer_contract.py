"""Virtual display viewer copy and route contracts (source scans only)."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "src/main/res"
LOCALES = {
    "en": RES / "values/virtual_display_ui.xml",
    "zh-Hans": RES / "values-b+zh+Hans/virtual_display_ui.xml",
    "zh-Hant": RES / "values-b+zh+Hant/virtual_display_ui.xml",
}


class VirtualDisplayViewerContractTest(unittest.TestCase):
    def keys(self, path):
        root = ET.parse(path).getroot()
        return [node.attrib["name"] for node in root.findall("string")]

    def test_viewer_locales_share_exactly_the_same_keys(self):
        key_lists = {locale: self.keys(path) for locale, path in LOCALES.items()}
        en = key_lists["en"]
        self.assertGreaterEqual(len(en), 30)
        self.assertEqual(len(en), len(set(en)), "en has duplicate keys")
        for locale, names in key_lists.items():
            self.assertEqual(len(names), len(set(names)), locale + " has duplicate keys")
            self.assertEqual(set(en), set(names), locale + " keys differ from en")
        for key in (
            "vd_screen_title",
            "vd_screen_entry_title",
            "agent_task_surface_group_title",
            "vd_screen_empty_summary",
            "vd_screen_handoff_working",
            "vd_screen_stage_description",
        ):
            self.assertIn(key, en)

    def test_shell_title_and_frame_diagnostic_mapping_cover_viewer(self):
        shell = (MAIN / "ui/app/AgentAppShell.kt").read_text()
        self.assertIn("is AppRoute.VirtualDisplay ->", shell)
        self.assertIn("R.string.vd_screen_title", shell)
        frames = (MAIN / "ui/app/FrameDiagnosticRoute.kt").read_text()
        self.assertIn("AppRoute.VirtualDisplay ->", frames)


if __name__ == "__main__":
    unittest.main()
