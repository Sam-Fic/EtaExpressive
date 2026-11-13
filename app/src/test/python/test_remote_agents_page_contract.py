"""Static wiring guards for the remote agents page; these do not compile Kotlin."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "src/main/res"

SCREEN = KOTLIN / "ui/screens/settings/RemoteAgentsScreen.kt"


def read(path):
    return path.read_text(encoding="utf-8")


class RemoteAgentsPageContractTest(unittest.TestCase):
    def test_page_matches_the_mcp_servers_layout(self):
        source = read(SCREEN)
        mcp = read(KOTLIN / "ui/screens/mcp/McpServersScreen.kt")
        # 版式与 MCP 服务器页对齐：单组分段列表 + 组标题计数 + 右下角扩展 FAB。
        for needle in (
            "EtaScaffoldPage(",
            "EtaSegmentedColumn(",
            "EtaSegmentedItemContainer {",
            "ListEmptyState(",
            "EtaExtendedFab(",
            "rememberFabExpandedState(listState)",
            "Icons.Rounded.Add",
        ):
            self.assertIn(needle, source)
            self.assertIn(needle, mcp)
        # 空态与新增入口的分工：空态只描述「还没有」，新增交给 FAB。
        self.assertIn("R.string.remote_agents_empty", source)
        self.assertIn("R.string.remote_agents_configured_servers, servers.size", source)
        self.assertIn("text = stringResource(R.string.remote_agents_add)", source)
        # 新增/编辑已页面化：FAB 只负责跳编辑器子页（null = 新建）。
        self.assertIn("onClick = { TouchHaptics.click(view); onOpenEditor(null) }", source)
        # 列表里不再重复放「添加」行：新增只有一个入口。
        self.assertNotIn('item(key = "add")', source)
        self.assertEqual(1, source.count("TouchHaptics.click(view); onOpenEditor(null)"))

    def test_page_keeps_its_own_behaviour(self):
        source = read(SCREEN)
        # 配置即状态：保存后由 DataStore 流重建连接，行状态点读协调器的实时状态。
        for needle in (
            "coordinator.statusOf(config.id)",
            "SettingsDataStore.setRemoteServers(updated)",
            "R.string.remote_agents_footer",
            "R.string.remote_agents_delete_message",
        ):
            self.assertIn(needle, source)
        # 底部风险提示是纯说明文字，不塞进分段卡片。
        self.assertNotIn("remote_agents_footer", source.split('item(key = "footer-note")', 1)[0])

    def test_group_padding_uses_shared_tokens(self):
        source = read(SCREEN)
        # 左右留白与组间距统一引用自适应层令牌，不手写 dp。
        self.assertIn("EtaGroupHorizontalPadding", source)
        self.assertIn("EtaSegmentedGroupSpacing", source)
        self.assertNotIn("padding(start = 16.dp", source)
        self.assertNotIn("bottom = 13.dp", source)

    def test_strings_exist_in_every_locale(self):
        keys = {
            "route_remote_agents",
            "remote_agents_empty",
            "remote_agents_empty_summary",
            "remote_agents_add",
            "remote_agents_configured_servers",
            "remote_agents_footer",
        }
        for locale in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            root = ET.parse(RES / locale / "strings.xml").getroot()
            names = [element.attrib["name"] for element in root.findall("string")]
            for key in keys:
                self.assertEqual(1, names.count(key), f"{locale}: {key}")
            count = next(
                element for element in root.findall("string")
                if element.attrib["name"] == "remote_agents_configured_servers"
            )
            self.assertEqual(1, "".join(count.itertext()).count("%1$d"))


if __name__ == "__main__":
    unittest.main()
