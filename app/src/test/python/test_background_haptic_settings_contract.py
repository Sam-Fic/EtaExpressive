"""Source contracts for the background haptic switch and its reasoning sub-option.

Run with Python unittest; no Android SDK is required. These guard wiring only,
not motor output, Activity lifecycle timing or Compose rendering.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[4]
KOTLIN = ROOT / "app/src/main/kotlin/io/github/mangi/eta"


def read(relative):
    return (KOTLIN / relative).read_text(encoding="utf-8")


def function_body(source, name):
    match = re.search(r"fun " + re.escape(name) + r"\(", source)
    if match is None:
        raise AssertionError(f"missing fun {name}")
    header_end = source.index(")", match.end())
    rest = source[header_end + 1:]
    # Expression-bodied functions (`fun x(): T = ...`) end at the next blank line.
    if re.match(r"\s*(:[^={]*)?=", rest):
        end = rest.find("\n\n")
        return rest if end < 0 else rest[:end]
    start = source.index("{", match.end())
    depth = 0
    for index in range(start, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError(f"unclosed fun {name}")


class BackgroundHapticSettingsContractTest(unittest.TestCase):
    def setUp(self):
        self.prefs = read("config/Prefs.kt")
        self.touch = read("ui/haptics/TouchHaptics.kt")
        self.streaming = read("ui/haptics/StreamingHaptics.kt")
        self.screen = read("ui/HapticsSettingsScreen.kt")
        self.app_state = read("ui/app/AgentAppState.kt")
        self.app = read("EtaApp.kt")

    def test_both_keys_default_on_and_stay_local(self):
        for key in ("HAPTIC_BACKGROUND", "HAPTIC_BACKGROUND_REASONING"):
            self.assertIn(f"{key} to true", self.prefs)
            local = self.prefs[self.prefs.index("LOCAL_AGENT_KEYS"):]
            self.assertIn(f"            {key},", local)

    def test_reasoning_option_depends_on_background(self):
        body = function_body(self.touch, "isBackgroundReasoningEnabled")
        self.assertIn("isBackgroundEnabled()", body)
        self.assertIn("HAPTIC_BACKGROUND_REASONING", body)
        self.assertIn("isMessageGenerationEnabled()", function_body(self.touch, "isBackgroundEnabled"))

    def test_background_gate_only_applies_when_app_is_hidden(self):
        body = function_body(self.streaming, "backgroundAllowed")
        self.assertLess(body.index("AppForeground.isForeground -> true"),
                        body.index("TouchHaptics.isBackgroundReasoningEnabled()"))
        self.assertIn("TouchHaptics.isBackgroundEnabled()", body)
        self.assertIn("backgroundAllowed(reasoning)", function_body(self.streaming, "noteBackgroundOutput"))
        self.assertIn("!backgroundAllowed()", function_body(self.streaming, "noteToolAppeared"))
        self.assertIn("AppForeground.install(this)", self.app)

    def test_reasoning_deltas_are_marked(self):
        self.assertIn("reasoning = event.kind == AgentEvent.AssistantBlockKind.THINKING", self.app_state)

    def test_screen_wires_reasoning_option_under_background_switch(self):
        # 后台页重构为分段列表后不再「展开/收起」：推理子项常驻组内第二行，
        # 依赖关系改由行级 enabled 表达（backgroundOn = generationOn && HAPTIC_BACKGROUND）。
        self.assertIn("R.string.haptics_background", self.screen)
        self.assertIn("enabled = backgroundOn", self.screen)
        self.assertIn("Prefs.Keys.HAPTIC_BACKGROUND_REASONING", self.screen)


if __name__ == "__main__":
    unittest.main()
