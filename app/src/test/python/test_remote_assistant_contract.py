"""远程会话的助手列表契约：只有一个，且换不了。

远程会话的助手在服务器那一侧，本机没有它的列表、也没有切换通道。照本机的助手列表
摆一屏能点的行，点下去什么都不会变（请求仍由服务器那个助手处理）——比不给列表更糟。
这几个用例守的是这条：远程时必须只显示用户给远程起的名字，并且那一行不可点。
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "src/main/res"


def read(path):
    return path.read_text(encoding="utf-8")


class RemoteAssistantListContractTest(unittest.TestCase):
    def test_remote_agent_name_is_a_composition_local(self):
        # 从 Root 到选择器中间隔了五层，每层只做转发；传一个名字不值得加五个参数。
        source = read(KOTLIN / "ui/remote/RemoteChatLocals.kt")
        self.assertIn("val LocalRemoteAgentName = staticCompositionLocalOf<String?>", source)
        # null = 本机；空串 = 远程但没名字。两者不能混为一谈，否则远程会话会
        # 列出一整套本机助手。
        self.assertIn("null", source)

    def test_conversation_route_provides_the_remote_agent_name(self):
        root = read(KOTLIN / "ui/app/AgentAppRoot.kt")
        # 会话只由主页承载（独立对话页已删），这一处必须 provide，否则远程会话
        # 仍会列出本机助手。
        self.assertEqual(
            root.count("LocalRemoteAgentName provides"),
            1,
            "主页必须 provide LocalRemoteAgentName",
        )
        self.assertIn("fun remoteAgentNameOf(", root)
        # 名字查不到时返回空串（不是 null），把「远程但没名字」与「本机」区分开。
        self.assertIn("?.name?.trim().orEmpty()", root)

    def test_picker_replaces_local_assistants_when_remote(self):
        picker = read(KOTLIN / "ui/components/AgentChatComposerPickerM3.kt")
        self.assertIn("LocalRemoteAgentName.current", picker)
        self.assertIn("RemoteAssistantTab(name = remoteAgentName)", picker)
        # 远程分支必须排在本地列表之前，且本地列表只在 remoteAgentName == null 时出现。
        remote_branch = picker.index("remoteAgentName != null")
        local_branch = picker.index("M3AssistantPickerTab(")
        self.assertLess(remote_branch, local_branch)

    def test_remote_assistant_row_is_not_switchable(self):
        picker = read(KOTLIN / "ui/components/AgentChatComposerPickerM3.kt")
        start = picker.index("private fun RemoteAssistantTab(")
        body = picker[start:start + 1600]
        # 没有 onClick → 行不响应点击、不出涟漪，也就没有「点了没反应」的假象。
        self.assertIn("onClick = null", body)
        self.assertNotIn("onAssistantSelected", body)
        # 名字为空时用通用称呼，不留一行空白。
        self.assertIn("R.string.route_remote_agents", body)
        # 说明为什么换不了，否则用户会以为按钮坏了。
        self.assertIn("R.string.assistant_remote_locked", body)

    def test_remote_mode_hides_assistant_management_entry(self):
        picker = read(KOTLIN / "ui/components/AgentChatComposerPickerM3.kt")
        # 页签行尾的「配置」图标在远程助手页签下要收起来：助手管理页改的是本机助手，
        # 和当前远程会话无关。
        self.assertIn("!(remoteAgentName != null && tabIndex == 1)", picker)

    def test_lock_notice_exists_in_every_locale(self):
        for folder in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            strings = read(RES / folder / "strings.xml")
            self.assertIn('name="assistant_remote_locked"', strings, folder)


if __name__ == "__main__":
    unittest.main()
