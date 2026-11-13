"""远程协议可插拔的契约：接第二个协议只改注册表一处，界面自己长出入口。

现在的实现只有 pi-remote。以后要接 opencode 之类，得避免三件最容易留下的事：
① 适配器在别处被 `if (protocolId == "pi")` 就地 new 出来——那就再也插不进第二个；
② 界面把协议写死成 pi，用户没有选的地方；
③ 接口把「协议本来就没有的能力」写成抽象方法，新适配器被迫补一堆空实现，
   空实现一多就分不清哪个是「协议确实忽略」、哪个是「忘了写」。
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "src/main/res"

LOCALES = ("values", "values-b+zh+Hans", "values-b+zh+Hant")


def read(rel):
    return (KOTLIN / rel).read_text(encoding="utf-8")


class RemoteProtocolCatalogContractTest(unittest.TestCase):
    def test_adapters_are_registered_in_exactly_one_place(self):
        catalog = read("agent/remote/RemoteProtocols.kt")
        # 唯一的构造点：接入新协议就是在这个列表里加一行。
        self.assertIn("PiRemoteAdapter(),", catalog)
        self.assertIn("RemoteProtocolRegistry", catalog)
        # 界面层不许自己 new 适配器，也不许按协议 id 特判。
        coordinator = read("ui/remote/RemoteCoordinator.kt")
        self.assertNotIn("PiRemoteAdapter", coordinator)
        self.assertIn("RemoteProtocolRegistry.adapterFor(config.protocolId)", coordinator)
        self.assertEqual(
            sum(1 for p in KOTLIN.rglob("*.kt") if "PiRemoteAdapter()" in p.read_text(encoding="utf-8")),
            1,
            "适配器只能在 RemoteProtocolRegistry 里被构造一次",
        )

    def test_selectable_protocols_are_the_ones_the_registry_can_open(self):
        catalog = read("agent/remote/RemoteProtocols.kt")
        # 目录里写了、注册表里没写（适配器还没动工）的协议不能出现在选项里：
        # 摆一个点了还是连不上的选项比不摆更糟。
        self.assertIn("descriptors.filter { RemoteProtocolRegistry.has(it.id) }", catalog)
        # 未知 id 回显自身，而不是留空让人以为没配协议。
        self.assertIn("descriptor(id)?.displayName ?: id", catalog)

    def test_optional_session_commands_have_sane_defaults(self):
        protocol = read("agent/remote/RemoteAgentProtocolAdapter.kt")
        # 只有这三件事是每个协议都必须能做的；其余按 capabilities 分档。
        self.assertIn("suspend fun sendMessage(", protocol)
        self.assertIn("val status: StateFlow<RemoteConnectionStatus>", protocol)
        self.assertIn("fun close()", protocol)
        # 没有会话概念的协议可以整段不写，默认语义是「这条命令被丢掉」。
        self.assertIn("suspend fun newSession(): Boolean = false", protocol)
        self.assertIn("suspend fun switchSession(path: String) = Unit", protocol)
        self.assertIn("suspend fun fetchHistory(before: String?) = Unit", protocol)
        self.assertIn("suspend fun setModel(spec: String) = Unit", protocol)
        # 附件带返回值：默认实现必须抛错而不是返回空串，否则调用方会把空 id 当成真 id。
        self.assertIn("UnsupportedOperationException", protocol)

    def test_capabilities_are_reachable_from_the_coordinator(self):
        # 第二个协议接进来时，界面靠这一处决定「切模型 / 会话列表 / 附件」显不显示，
        # 而不是各页面自己判断协议 id。
        coordinator = read("ui/remote/RemoteCoordinator.kt")
        self.assertIn("fun capabilitiesOf(serverId: String)", coordinator)
        self.assertIn("RemoteProtocolRegistry.capabilitiesOf(it.protocolId)", coordinator)


class RemoteProtocolPickerContractTest(unittest.TestCase):
    def setUp(self):
        # 协议选择与保存随「添加/编辑」页面化搬进了 RemoteAgentEditorScreen；
        # 列表页只剩服务器行上的协议名展示。
        self.screen = read("ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.list = read("ui/screens/settings/RemoteAgentsScreen.kt")

    def test_save_uses_the_selected_protocol_not_a_hardcoded_pi(self):
        self.assertIn("protocolId = selectedProtocolId", self.screen)
        self.assertNotIn('protocolId = "pi"', self.screen)
        # 编辑已有服务器时必须把它的协议带进表单，否则一保存就被改写。
        self.assertIn("selectedProtocolId = config.protocolId", self.screen)

    def test_single_available_protocol_stays_a_plain_row(self):
        # 协议行现在是行内下拉（EtaSegmentedDropdownRow，选中值显示在行尾），
        # 但选项仍来自注册表的 selectable()：第二个协议注册进来后自动多一行，
        # 不用再改界面。
        self.assertIn("val protocolOptions = RemoteProtocols.selectable()", self.screen)
        self.assertIn("EtaSegmentedDropdownRow(", self.screen)
        self.assertIn("protocolOptions.map { it.displayName }", self.screen)

    def test_unsupported_protocol_is_editable_not_rewritten(self):
        # 认不出的协议也要能点开选择器——那是用户唯一的改法；并且要说明为什么连不上。
        self.assertIn("R.string.remote_agents_protocol_unsupported", self.screen)
        self.assertIn("!protocolKnown", self.screen)

    def test_server_row_shows_the_protocol_name(self):
        self.assertIn("RemoteProtocols.displayNameOf(config.protocolId)", self.list)

    def test_unknown_protocol_never_silently_falls_back(self):
        # 不认识的协议必须报 UnsupportedProtocol，不能悄悄按 pi 去连——
        # 那会把「这台服务器不是 pi」错报成「连不上」。
        coordinator = read("ui/remote/RemoteCoordinator.kt")
        self.assertIn("RemoteFailure.Kind.UnsupportedProtocol", coordinator)

    def test_protocol_strings_exist_in_every_locale(self):
        for folder in LOCALES:
            strings = (RES / folder / "strings.xml").read_text(encoding="utf-8")
            self.assertIn('name="remote_agents_protocol_title"', strings, folder)
            self.assertIn('name="remote_agents_protocol_unsupported"', strings, folder)
            self.assertIn("%1$s", strings, folder)


if __name__ == "__main__":
    unittest.main()
