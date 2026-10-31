"""Guards for "refresh the conversation title every turn" (标题模型页开关).

这些断言守的是「删掉一行照样能编译、Kotlin 单测覆盖不到」的接线：
序号校验、同标题不落库、开关只在标题页可见、三份 locale 都有文案。
"""
import pathlib
import unittest
import xml.etree.ElementTree as ET

APP = pathlib.Path(__file__).resolve().parents[3]
SRC = APP / "src/main/kotlin/io/github/mangi/eta"


class ConversationTitleFollowContractTest(unittest.TestCase):
    def test_follow_switch_only_shows_on_title_model_page(self):
        screen = (SRC / "ui/ModelFeatureSettingsScreen.kt").read_text(encoding="utf-8")
        block = screen[screen.index('item(key = "title_follow"'):screen.index("item(visible = selection.custom)")]
        self.assertIn("R.string.model_feature_title_follow", block)
        # 辅助视觉页没有「每轮刷新」概念，开关不能在那边泄露。
        self.assertIn("visible = feature == ModelFeature.TITLE", block)

    def test_register_stamps_monotonic_request_seq(self):
        state = (SRC / "ui/app/AgentAppState.kt").read_text(encoding="utf-8")
        block = state[
            state.index("private fun registerPendingConversationTitle("):
            state.index("private fun consumePendingConversationTitle(")
        ]
        self.assertIn("titleRequestSeq[conversationId] = seq", block)
        self.assertIn("follow = follow", block)

    def test_late_turn_result_cannot_overwrite_newer_turn(self):
        state = (SRC / "ui/app/AgentAppState.kt").read_text(encoding="utf-8")
        block = state[
            state.index("private fun consumePendingConversationTitle("):
            state.index("private fun runTitleAnswer(")
        ]
        # 唯一的安全阀：只有最新一轮的请求可以回写。
        self.assertIn("titleRequestSeq[conversationId] == pending.seq", block)
        self.assertIn("mayApplyRefresh(", block)
        # 主题没变时模型原样返回旧标题：不写内存、不落库。
        self.assertIn("if (title == pending.expectedTitle) return@launch", block)

    def test_regenerate_and_resume_are_not_a_new_turn(self):
        state = (SRC / "ui/app/AgentAppState.kt").read_text(encoding="utf-8")
        # 重生成 2 处 + 断线续写 + 压缩后续写：prompt 是占位串，不能当本轮用户素材。
        self.assertEqual(4, state.count("refreshTitleAfterTurn = false"))

    def test_generate_keeps_backwards_compatible_previous_title_default(self):
        model = (SRC / "agent/model/ConversationTitleModel.kt").read_text(encoding="utf-8")
        signature = model[model.index("fun generate("):model.index("internal fun buildMessages(")]
        self.assertIn('previousTitle: String = ""', signature)

    def test_follow_switch_copy_exists_in_every_locale(self):
        for folder in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            root = ET.parse(APP / "src/main/res" / folder / "strings.xml").getroot()
            names = {element.attrib["name"] for element in root}
            self.assertIn("model_feature_title_follow", names)
            self.assertIn("model_feature_title_follow_summary", names)


if __name__ == "__main__":
    unittest.main()
