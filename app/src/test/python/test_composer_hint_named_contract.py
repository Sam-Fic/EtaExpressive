"""输入框空状态提示语的契约：名字跟着当前会话的助手走。

提示语原来是写死的「交给 Eta 去完成」。可这个会话未必由 Eta 在干活：本机可以换助手，
远程会话的助手更是由服务器固定（名字就是用户在远程智能体配置里起的那个）。
一行写死的名字在这种会话里就是错的信息，所以改成按名字取文案，并在拿不到名字时
退回不带名字的通用文案——而不是拿本机助手名冒名顶替。
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "src/main/res"

LOCALES = ("values", "values-b+zh+Hans", "values-b+zh+Hant")


def read(path):
    return path.read_text(encoding="utf-8")


class ComposerHintNameContractTest(unittest.TestCase):
    def setUp(self):
        self.composer = read(KOTLIN / "ui/components/AgentChatComposerM3.kt")

    def test_placeholder_goes_through_the_named_helper(self):
        placeholder = self.composer[self.composer.index("private fun M3ComposerTextField("):]
        placeholder = placeholder[: placeholder.index("lineLimits")]
        self.assertIn("composerHintText(", placeholder)
        # 占位符不再直接取写死名字的文案。
        self.assertNotIn("R.string.chat_input_hint)", placeholder.replace(" ", ""))

    def test_helper_prefers_remote_name_over_local_assistant(self):
        helper = self.composer[self.composer.index("private fun composerHintText("):]
        helper = helper[: helper.index("\n}\n")]
        # 远程名（非 null）优先；null 才用本机助手名。顺序写反会在远程会话里
        # 显示本机助手名——正是这次要修的症状。
        self.assertIn("(remoteAgentName ?: assistantName)", helper)
        # 名字为空串时退回通用文案，不能拼出「交给  去完成」。
        self.assertIn("R.string.chat_input_hint_named", helper)
        self.assertIn("R.string.chat_input_hint)", helper.replace(" ", ""))
        # 生成中的提示语同样要跟着名字走，否则「Eta 正在执行」会和助手页签自相矛盾。
        self.assertIn("R.string.chat_eta_working_named", helper)

    def test_text_field_reads_the_remote_name_and_forwards_the_assistant_name(self):
        field = self.composer[self.composer.index("private fun M3ComposerTextField("):]
        field = field[: field.index("lineLimits")]
        # 远程名由 CompositionLocal 就近读取（Root 那层已 provide），本机名由参数带入。
        self.assertIn("LocalRemoteAgentName.current", field)
        self.assertIn("assistantName = assistantName", field)

    def test_input_bar_resolves_assistant_name_from_profiles(self):
        bar = read(KOTLIN / "ui/components/AgentChatInputBar.kt")
        # 会话所属助手优先，其次仓库当前激活的助手，最后兜底第一条档案。
        self.assertIn("val composerAssistantName = remember(", bar)
        self.assertIn("firstOrNull { it.id == assistantId }", bar)
        self.assertIn("firstOrNull { it.id == repositoryActiveId }", bar)
        self.assertIn("assistantName = composerAssistantName", bar)

    def test_named_hints_exist_in_every_locale(self):
        for folder in LOCALES:
            strings = read(RES / folder / "strings.xml")
            for key in ("chat_input_hint_named", "chat_eta_working_named"):
                self.assertIn(f'name="{key}"', strings, folder)
            self.assertIn("%1$s", strings, folder)

    def test_unnamed_fallbacks_are_kept(self):
        # 没有名字可用时仍要有话可说：这两条通用文案不能为了新写法被删掉。
        for folder in LOCALES:
            strings = read(RES / folder / "strings.xml")
            self.assertIn('name="chat_input_hint"', strings, folder)
            self.assertIn('name="chat_eta_working"', strings, folder)


if __name__ == "__main__":
    unittest.main()
