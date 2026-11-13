"""IME source contracts: asynchronous stream/tool events must not hide the keyboard.

These check production wiring and window policy, not real-device IME behavior.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

APP = Path(__file__).resolve().parents[3]
KOTLIN = APP / "src/main/kotlin/io/github/mangi/eta/ui"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def code_only(source):
    return re.sub(
        r'//[^\n]*|/\*.*?\*/|""".*?"""|"(?:\\.|[^"\\])*"',
        lambda match: " " * len(match.group()), source, flags=re.DOTALL,
    )


def block_after(source, pattern):
    match = re.search(pattern, source)
    if match is None:
        raise AssertionError(f"Block not found: {pattern}")
    start = source.index("{", match.start())
    depth = 0
    for index in range(start, len(source)):
        depth += (source[index] == "{") - (source[index] == "}")
        if depth == 0:
            return source[start + 1:index]
    raise AssertionError("Unbalanced production block")


class ChatImeLifecycleContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.chat = code_only((KOTLIN / "components/AgentChatBody.kt").read_text())
        cls.activity = code_only((KOTLIN / "MainActivity.kt").read_text())
        cls.manifest = ET.parse(APP / "src/main/AndroidManifest.xml").getroot()

    def test_no_pending_submit_flag_survives_into_stream_updates(self):
        self.assertNotIn("sentFromKeyboard", self.chat)

    def test_keyboard_hide_is_synchronous_and_confined_to_user_actions(self):
        submit = block_after(self.chat, r"onSubmit\s*=\s*\{\s*text\s*->")
        hides = re.findall(r"keyboard\s*\?\.\s*hide\s*\(\s*\)", self.chat)
        # 收起键盘只允许两处显式用户动作：发送提交、进入语音模式（语音输入法的录音
        # 会话会与语音对话抢同一支麦克风）。流式/工具等异步事件仍然一律不收起键盘。
        self.assertEqual(len(hides), 2, "Keyboard hides must stay on explicit user actions only")
        self.assertRegex(submit, r"keyboard\s*\?\.\s*hide\s*\(\s*\)")
        self.assertLess(submit.index("keyboard"), submit.index("onSubmit(text)"))
        self.assertNotIn("LaunchedEffect", submit)
        self.assertNotIn("launch {", submit[:submit.index("onSubmit(text)")])
        # 语音入口同样同步收起，不借协程延迟。
        voice = block_after(self.chat, r"onStartVoiceMode\s*=\s*\{")
        self.assertRegex(voice, r"keyboard\s*\?\.\s*hide\s*\(\s*\)")
        self.assertNotIn("LaunchedEffect", voice)

    def test_drawer_keeps_its_explicit_hide_and_clear_focus_path(self):
        effect = block_after(self.chat, r"LaunchedEffect\s*\(\s*isDrawerOpen\s*\)\s*\{")
        opened = block_after(effect, r"if\s*\(\s*isDrawerOpen\s*\)\s*\{")
        self.assertIn("hideChatInputIme(focusManager, keyboard, view)", opened)
        self.assertEqual(self.chat.count("hideChatInputIme(focusManager, keyboard, view)"), 1)

    def test_main_activity_keeps_resize_without_forcing_keyboard_hidden(self):
        call = re.search(r"window\.setSoftInputMode\s*\((.*?)\n\s*\)", self.activity, re.DOTALL)
        self.assertIsNotNone(call)
        mode = call.group(1)
        self.assertIn("SOFT_INPUT_ADJUST_RESIZE", mode)
        self.assertIn("SOFT_INPUT_STATE_UNCHANGED", mode)
        self.assertNotIn("SOFT_INPUT_STATE_ALWAYS_HIDDEN", mode)

    def test_manifest_window_policy_matches_main_activity(self):
        main = next(item for item in self.manifest.findall("application/activity")
                    if item.get(ANDROID + "name") == ".ui.MainActivity")
        mode = set(main.get(ANDROID + "windowSoftInputMode").split("|"))
        self.assertEqual(mode, {"adjustResize", "stateUnchanged"})


if __name__ == "__main__":
    unittest.main()
