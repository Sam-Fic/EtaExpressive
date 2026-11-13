"""Static wiring guards for the remote agent connection path.

这些用例只做文本级断言，不编译 Kotlin。它们守着的是一类特别安静的故障：
地址被当成主机名、异常从协程里逃出去、失败原因被界面吞掉——三种情况在
App 里都表现为同一个样子：一句「连接失败」和一个永远转圈的「重连中」。
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
KOTLIN = ROOT / "src/main/kotlin/io/github/mangi/eta"
RES = ROOT / "src/main/res"


def read(path):
    return path.read_text(encoding="utf-8")


def read_code(path):
    """去掉注释后的源码。

    断言「误导性文案不得再出现在运行期代码里」，但源码注释里往往要**写下来**
    说明这条文案错在哪、为什么删——那正是文档，不该被断言误伤。
    """
    source = read(path)
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"//[^\n]*", "", source)
    return source


class RemoteConnectionContractTest(unittest.TestCase):
    def test_address_parser_splits_host_port_and_token(self):
        source = read(KOTLIN / "agent/remote/RemoteServerAddress.kt")
        for needle in (
            "fun parse(raw: String): Parsed?",
            'name != "token"',
            "percentDecode",
            "bareHost",
        ):
            self.assertIn(needle, source)

    def test_settings_page_parses_address_into_port_and_token(self):
        # 「添加/编辑」弹窗已页面化，地址解析随表单搬进了 RemoteAgentEditorScreen。
        source = read(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertIn("RemoteServerAddress.parse(host)", source)
        self.assertIn("fun onAddressChanged(raw: String)", source)
        self.assertIn("parsed.port?.let { port = it.toString() }", source)
        self.assertIn("parsed.token?.let { token = it }", source)
        # 存进配置的是解析后的裸主机名，而不是用户粘进来的整条链接。
        self.assertIn("host = address.host", source)
        self.assertIn("authToken = token.trim().ifEmpty { address.token.orEmpty() }", source)
        # 界面把最终会连的地址回显出来，用户能确认自己填的被理解对了。
        self.assertIn("endpointPreview(", source)
        self.assertIn("R.string.remote_agents_resolved_endpoint", source)

    def test_failure_reason_reaches_every_status_surface(self):
        screen = read(KOTLIN / "ui/screens/settings/RemoteAgentsScreen.kt")
        composer = read(KOTLIN / "ui/components/AgentChatComposerM3.kt")
        pane = read(KOTLIN / "ui/components/ConversationSidePaneScaffold.kt")
        shared = read(KOTLIN / "ui/remote/RemoteStatusText.kt")
        # 失败性质挂在状态上，取文案只有一个入口——否则三种语言会各说各话。
        self.assertIn("status.kind", shared)
        self.assertIn("remoteStatusLabel(", shared)
        self.assertIn("remoteFailureText(", shared)
        # 三个状态出口都必须经过那个入口，不能自己拼一句「连接失败」。
        self.assertIn("remoteStatusLabel(status)", screen)
        self.assertIn("remoteStatusLabel(", pane)
        # 断线提示已并入输入框状态胶囊（RemoteStatusComposerPill），不再是顶部横幅。
        self.assertIn("RemoteStatusComposerPill(", composer)
        self.assertIn("remoteFailureText(", composer)

    def test_supervisor_never_lets_a_malformed_url_escape(self):
        source = read(KOTLIN / "agent/remote/RemoteConnectionSupervisor.kt")
        # URL 构造失败必须在这里吃掉：它一旦冒泡到 DataStore 收集协程，整条远程链路就哑了。
        self.assertIn("runCatching { Request.Builder().url(url).build() }", source)
        self.assertIn("RemoteConnectionStatus.Failed(", source)
        # 失败分类统一交给 RemoteFailure：能自愈的继续重连，不能自愈的停下报错。
        self.assertIn("RemoteFailure.kindOf(t)", source)
        self.assertIn("RemoteFailure.isTerminal(kind)", source)
        self.assertIn("failure.terminal", source)
        # 可自愈的失败重试到上限也必须判死（Failed），不能无限转圈；
        # 复活交给用户——胶囊重试钮或点会话行都会整颗重建监管器（计数归零）。
        self.assertIn("MAX_RECONNECT_ATTEMPTS", source)
        self.assertIn(
            "RemoteConnectionStatus.Failed(lastKind ?: RemoteFailure.Kind.Unknown, lastDetail)",
            source,
        )

    def test_failure_classification_splits_connect_and_read_timeout(self):
        # 老实现把两种超时一律写成「确认手机与服务器连的是同一个局域网」，
        # 那是替用户断言原因——用户明明同网段、浏览器也能打开。
        failure = read(KOTLIN / "agent/remote/RemoteFailure.kt")
        self.assertIn("ConnectTimeout", failure)
        self.assertIn("ReadTimeout", failure)
        # 区分点只能是 message：两者异常类型都是 SocketTimeoutException。
        self.assertIn("SocketTimeoutException", failure)
        self.assertIn("connect timed out", failure)
        supervisor = read(KOTLIN / "agent/remote/RemoteConnectionSupervisor.kt")
        self.assertNotIn("确认手机与服务器连的是同一个局域网", read_code(
            KOTLIN / "agent/remote/RemoteConnectionSupervisor.kt"))
        self.assertNotIn("确认手机与服务器连的是同一个局域网", read_code(
            KOTLIN / "agent/remote/RemoteFailure.kt"))
        self.assertNotIn("确认手机与服务器连的是同一个局域网", read_code(
            KOTLIN / "ui/remote/RemoteStatusText.kt"))

    def test_remote_traffic_bypasses_the_system_proxy(self):
        # 系统 Wi-Fi 上若配了 HTTP 代理，OkHttp 会把 192.168.x.x 也交给代理转发，
        # 而浏览器有「局域网不走代理」的例外规则——这正是「浏览器能开、App 超时」的成因。
        client = read(KOTLIN / "agent/remote/RemoteHttpClient.kt")
        self.assertIn("Proxy.NO_PROXY", client)
        supervisor = read(KOTLIN / "agent/remote/RemoteConnectionSupervisor.kt")
        adapter = read(KOTLIN / "agent/remote/pi/PiRemoteAdapter.kt")
        # 远程链路必须用局域网专用客户端，不能再走云端模型那个会跟随代理的客户端。
        self.assertIn("RemoteHttpClient.client", supervisor)
        self.assertIn("RemoteHttpClient.client", adapter)
        # 远程适配器不能再 import 云端模型那个客户端（它会跟随系统代理）。
        self.assertNotIn("import io.github.mangi.eta.agent.model.AgentHttpClient", adapter)
        self.assertNotIn("AgentHttpClient.client", read_code(
            KOTLIN / "agent/remote/pi/PiRemoteAdapter.kt"))

    def test_connect_timeout_triggers_a_wifi_bound_retry(self):
        # 默认路由被切到移动网络时，访问 192.168.x.x 必然超时；
        # 绑到 Wi-Fi 网络重试是唯一能从 App 内部自救的办法。
        client = read(KOTLIN / "agent/remote/RemoteHttpClient.kt")
        self.assertIn("preferWifiOnNextAttempt", client)
        # 必须用 Network.bindSocket：Android 的出接口按网络（fwmark）选，
        # 只 bind 源地址改不了出接口，那个「对照组」会跟着默认路由一起失败。
        self.assertIn("fun bindToWifi(socket: Socket): Boolean", client)
        self.assertIn("network.bindSocket(socket)", client)
        supervisor = read(KOTLIN / "agent/remote/RemoteConnectionSupervisor.kt")
        self.assertIn("RemoteHttpClient.preferWifiOnNextAttempt()", supervisor)

    def test_probe_splits_the_link_into_layers(self):
        probe = read(KOTLIN / "agent/remote/RemoteConnectionProbe.kt")
        self.assertIn("probeTcp(", probe)
        self.assertIn("probeHttp(", probe)
        self.assertIn("probeWebSocket(", probe)
        # 「默认路由不通、绑 Wi-Fi 就通」这一对照必须留着：它是唯一能自证的根因。
        self.assertIn("probeTcpViaWifi(", probe)
        self.assertIn("wifiRouteWorkaround", probe)
        # 诊断（测试连接）随「添加/编辑」页面化搬进了 RemoteAgentEditorScreen。
        screen = read(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertIn("RemoteConnectionProbe.run(", screen)
        self.assertIn("R.string.remote_probe_test", screen)
        self.assertIn("R.string.remote_probe_conclusion_wifi", screen)

    def test_timeout_kind_comes_from_the_phase_not_from_the_message(self):
        # 踩过的坑：ART 的 Socket.connect 超时文案是
        # "failed to connect to /x.x.x.x (port n) after 5000ms"，只按 message 猜
        # 会被判成「已连上但没回应」，把「包没到达」说成「对方不理我」。
        failure = read_code(KOTLIN / "agent/remote/RemoteFailure.kt")
        self.assertIn("Phase.Connect -> Kind.ConnectTimeout", failure)
        self.assertIn("Phase.Read -> Kind.ReadTimeout", failure)
        self.assertIn("failed to connect", failure)
        probe = read_code(KOTLIN / "agent/remote/RemoteConnectionProbe.kt")
        # 裸 TCP 那一步除了 connect 什么都没做，超时必定是连接阶段的。
        self.assertIn("RemoteFailure.kindOf(t, RemoteFailure.Phase.Connect)", probe)

    def test_probe_reports_the_lan_gateway_so_the_host_can_be_cleared(self):
        # 网关有应答而服务器没有 = 局域网本身通，问题在服务器那一条路线上。
        # 这是把「手机不在这个局域网」与「服务器不接受连接」分开的唯一判据。
        probe = read_code(KOTLIN / "agent/remote/RemoteConnectionProbe.kt")
        self.assertIn("probeGateway(", probe)
        self.assertIn("enum class Reach", probe)
        self.assertIn("gatewayReach", probe)
        self.assertIn("lanFineHostSilent", probe)
        # 收到 RST 也算「有应答」：我们问的是路通不通，不是端口开没开。
        self.assertIn("catch (e: ConnectException)", probe)

    def test_probe_measures_the_egress_route_instead_of_guessing(self):
        # 「拿一个关闭的端口去连、看会不会回 RST」这条判据已经删掉：
        # Linux 的 ufw default deny 与 Windows Defender 默认都是**静默丢弃**，
        # 未放行的端口和关闭的端口表现完全一样，「没有 RST」同时对应两种相反的原因。
        # 换成 UDP connect 查路由表：它不发包，只把内核选中的源地址报出来——
        # 源地址与服务器同网段 = 发对了，不同网段 = 被 VPN/移动数据改道。
        probe = read_code(KOTLIN / "agent/remote/RemoteConnectionProbe.kt")
        self.assertNotIn("probeHostAnswers(", probe)
        self.assertNotIn("CLOSED_PORT", probe)
        self.assertIn("DatagramSocket(", probe)
        self.assertIn("socket.localAddress", probe)
        self.assertIn("sealed interface Route", probe)
        self.assertIn("probeRoute(", probe)
        self.assertIn("routeDiverted", probe)
        self.assertIn("serverRoute", probe)
        screen = read_code(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertIn("R.string.remote_probe_route_chosen", screen)
        self.assertIn("R.string.remote_probe_conclusion_diverted", screen)

    def test_probe_always_renders_the_arp_verdict(self):
        # ARP 是唯一不受「防火墙静默丢弃」影响的判据。读不到表时也必须写出来，
        # 否则用户看到那行消失，会以为这一条压根没测。
        screen = read_code(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertIn("R.string.remote_probe_arp_unknown", screen)
        self.assertNotIn("private fun probeArpText(arp: RemoteConnectionProbe.Arp, host: String): String?",
                         screen)

    def test_probe_reads_arp_to_tell_host_side_from_path(self):
        # 端口探测分不清「包没到」和「防火墙静默丢弃」，因为两者表现一样。
        # ARP 在链路层：防火墙丢的是 IP 包，机器活着就一定会回 ARP。
        # 「有 MAC 却没回应」与「解析不到 MAC」把排查方向指向完全相反的两边。
        probe = read_code(KOTLIN / "agent/remote/RemoteConnectionProbe.kt")
        self.assertIn("enum class Arp", probe)
        self.assertIn('File("/proc/net/arp")', probe)
        self.assertIn("arpStateOf(", probe)
        self.assertIn("ARP_FLAG_COMPLETE", probe)
        self.assertIn("hostArp", probe)
        # 解析逻辑抽成纯函数，才能脱离设备单测（ARP 表格式错了结论就会反）。
        self.assertIn("fun parseArpTable(", probe)
        self.assertTrue(
            (ROOT / "src/test/kotlin/io/github/mangi/eta/agent/remote/"
                "RemoteConnectionProbeTest.kt").exists(),
            "ARP 解析缺单测",
        )
        # ARP 必须在 TCP 尝试之后读：内核正是被那次 connect 触发去解析 MAC 的。
        self.assertLess(probe.index("val tcp = probeTcp"), probe.index("val hostArp ="))
        screen = read_code(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertIn("probeArpText(", screen)
        self.assertIn("R.string.remote_probe_conclusion_host_on_lan", screen)
        self.assertIn("R.string.remote_probe_conclusion_host_missing", screen)

    def test_probe_report_puts_the_layers_above_the_environment(self):
        # 报告一屏放不下时，被挤到折叠线以下的行等于没写。
        # 「断在哪一层」必须紧跟结论，环境信息（网络/网卡/同网段）排最后。
        screen = read_code(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertLess(
            screen.index("rows.forEach"),
            screen.index("probeGatewayText(report)"),
        )
        self.assertLess(
            screen.index("rows.forEach"),
            screen.index("R.string.remote_probe_network,"),
        )

    def test_probe_report_does_not_waste_rows(self):
        # 前一层断掉时 HTTP/长连接必然「未执行」，写出来只占位置；
        # 报告一屏放不下，关键判据就会被挤到折叠线以下——这个坑踩过一次。
        screen = read_code(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertIn("if (report.http !is RemoteConnectionProbe.Step.Skipped)", screen)
        # 长连接层改叫 stream（随协议是 WebSocket 或 SSE），不再叫 websocket。
        self.assertIn("if (report.stream !is RemoteConnectionProbe.Step.Skipped)", screen)

    def test_pi_adapter_builds_urls_with_okhttp(self):
        source = read(KOTLIN / "agent/remote/pi/PiRemoteAdapter.kt")
        self.assertIn("HttpUrl.Builder()", source)
        self.assertIn('.addPathSegment("ws")', source)
        self.assertIn('addQueryParameter("token", token)', source)
        # 旧的字符串拼接遇到整条链接会拼出 ws://http://… 这种废地址。
        self.assertNotIn('StringBuilder("ws://")', source)

    def test_local_network_permission_is_judged_by_app_op(self):
        # targetSdk 必须 ≥ 37：只有到 37 系统才会把 ACCESS_LOCAL_NETWORK 当运行时权限
        # 暴露在系统设置里。停在 36 的真机形态是「系统照样按 app-op 静默丢包，
        # 但权限页里根本没有这一项」——用户完全无从自助。
        manifest = ET.parse(ROOT / "src/main/AndroidManifest.xml").getroot()
        # 注意：带 xmlns 的清单里属性键是 {http://schemas.android.com/apk/res/android}name，
        # 取 "android:name" 会一直拿到空串——旧版就是这么写着、并且一直是假绿。
        declared = [
            element.attrib.get("{http://schemas.android.com/apk/res/android}name", "")
            for element in manifest.findall("uses-permission")
        ]
        self.assertIn("android.permission.ACCESS_LOCAL_NETWORK", declared)
        build = read(ROOT / "build.gradle.kts")
        self.assertIn("targetSdk = 37", build)

        # 判定必须同时看权限位与 app-op：
        # 权限位 granted + app-op ignore 是真实存在的一种状态（本地网络被静默丢弃）。
        permission = read(KOTLIN / "agent/device/LocalNetworkPermission.kt")
        self.assertIn("android:access_local_network", permission)
        self.assertIn("unsafeCheckOpNoThrow", permission)
        self.assertIn("MODE_IGNORED", permission)
        # 「尚未授予」与「被 app-op 拒绝」是两件不同的事，处置方式相反：
        # 前者可以申请，后者申请了也不会弹窗。
        self.assertIn("NOT_GRANTED", permission)
        self.assertIn("fun canRequest", permission)
        self.assertIn("fun blocksConnection", permission)

    def test_local_network_has_entry_points_outside_the_probe_report(self):
        # 只把状态藏在「测试连接」报告里等于没有入口：用户不会为了排查先去跑一次诊断。
        screen = read(KOTLIN / "ui/screens/settings/RemoteAgentsScreen.kt")
        self.assertIn('item(key = "local-network")', screen)
        self.assertIn("lanPermissionLauncher.launch(LocalNetworkPermission.PERMISSION)", screen)
        # 申请结果回来要先刷新状态、再决定跑不跑探测——被拒时跑出来只会是
        # 一次与「服务器不回应」无法区分的超时。这段随探测流程搬进了编辑器子页。
        editor = read(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        self.assertIn("!LocalNetworkPermission.blocksConnection(lanState)", editor)

        # 权限总览页也要有一项，否则用户只在远程智能体页才看得到。
        state = read(KOTLIN / "ui/app/AgentAppState.kt")
        self.assertIn('id = "local_network"', state)
        root = read(KOTLIN / "ui/app/AgentAppRoot.kt")
        self.assertIn('"local_network" ->', root)

    def test_lan_permission_points_users_at_the_name_the_system_actually_uses(self):
        # 真机实测（小米 2304FPN6DC / Android 17 / HyperOS）：这项权限在系统权限页里
        # 叫「附近设备」（Nearby devices），不叫「本地网络」。撤销 ACCESS_LOCAL_NETWORK
        # 后那一行的对勾消失、恢复后回来，确认是同一项。
        # 文案若只说「本地网络」，用户进去找不到——这坑踩过一次。
        screen = read(KOTLIN / "ui/screens/settings/RemoteAgentsScreen.kt")
        # 跳转要直接落到权限列表，而不是「应用信息」页（用户还得再找一层）。
        self.assertIn("android.settings.APP_PERMISSIONS_SETTINGS", screen)
        self.assertIn("fun openAppPermissionSettings", screen)

        for folder, alias in (
            ("values", "Nearby devices"),
            ("values-b+zh+Hans", "附近设备"),
            ("values-b+zh+Hant", "附近裝置"),
        ):
            strings = read(ROOT / f"src/main/res/{folder}/strings.xml")
            self.assertIn(alias, strings, f"{folder} 必须写出系统里的叫法 {alias}")
            self.assertIn("remote_local_network_dialog_naming", strings)

    def test_lan_blocked_surfaces_before_every_other_conclusion(self):
        # 被系统拦时，网关照样有应答、ARP 照样读不到——所有下游判据都会指向
        # 「服务器不回应」。这一条必须排在结论链最前面，否则整份报告把人带偏。
        # 结论链随诊断报告搬进了 RemoteAgentEditorScreen。
        screen = read(KOTLIN / "ui/screens/settings/RemoteAgentEditorScreen.kt")
        blocked = screen.index("report.lanBlockedBySystem ->")
        for later in (
            "report.wifiRouteWorkaround ->",
            "report.routeDiverted ->",
            "report.hostArp == RemoteConnectionProbe.Arp.Complete ->",
            "report.lanFineHostSilent ->",
        ):
            self.assertLess(
                blocked,
                screen.index(later),
                f"{later} 必须排在「被系统拦」之后",
            )
        self.assertIn("remote_probe_local_network_denied", screen)
        # 兜底入口（应用信息页）在列表页那个共用的 openAppPermissionSettings 里。
        self.assertIn("ACTION_APPLICATION_DETAILS_SETTINGS", read(
            KOTLIN / "ui/screens/settings/RemoteAgentsScreen.kt"))

    def test_new_strings_exist_in_every_locale(self):
        keys = {
            "remote_agents_field_host_hint",
            "remote_agents_resolved_endpoint",
            "remote_agents_error_host_invalid",
        }
        for locale in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            root = ET.parse(RES / locale / "strings.xml").getroot()
            names = [element.attrib["name"] for element in root.findall("string")]
            for key in keys:
                self.assertEqual(1, names.count(key), f"{locale}: {key}")
            endpoint = next(
                element for element in root.findall("string")
                if element.attrib["name"] == "remote_agents_resolved_endpoint"
            )
            self.assertEqual(1, "".join(endpoint.itertext()).count("%1$s"))

    def test_failure_and_probe_strings_exist_in_every_locale(self):
        # 失败文案按性质一一对应，三种语言必须齐全——缺一个语言键就会在运行时崩。
        kinds = {
            "remote_failure_connect_timeout": 0,
            "remote_failure_read_timeout": 0,
            "remote_failure_refused": 0,
            "remote_failure_unreachable": 0,
            "remote_failure_unknown_host": 1,
            "remote_failure_malformed_address": 1,
            "remote_failure_permission": 0,
            "remote_failure_cleartext": 0,
            "remote_failure_unauthorized": 1,
            "remote_failure_not_found": 1,
            "remote_failure_unsupported_protocol": 1,
            # 「强制走 Wi-Fi」没跑成必须单独成一句，否则它会显示成和连接超时一模一样，
            # 用户据此认定「换 Wi-Fi 也不行」——而那条实验根本没做。
            "remote_failure_bind_failed": 0,
            "remote_failure_unknown": 1,
        }
        probes = {
            "remote_probe_test": 0,
            "remote_probe_title": 0,
            "remote_probe_running": 0,
            "remote_probe_device_ip": 1,
            "remote_probe_server": 2,
            "remote_probe_same_subnet_yes": 0,
            "remote_probe_same_subnet_no": 0,
            "remote_probe_same_subnet_unknown": 0,
            "remote_probe_step_tcp": 0,
            "remote_probe_step_tcp_wifi": 0,
            "remote_probe_step_http": 0,
            "remote_probe_step_ws": 0,
            "remote_probe_step_ok": 1,
            "remote_probe_step_failed": 2,
            "remote_probe_step_skipped": 1,
            "remote_probe_conclusion_ok": 0,
            "remote_probe_conclusion_wifi": 0,
            "remote_probe_conclusion_failed": 2,
            # 网关应答与否是区分「手机不在这个局域网」与「服务器不接受连接」的判据。
            "remote_probe_conclusion_host_silent": 3,
            "remote_probe_conclusion_lan_broken": 0,
            # 出口被改道（源地址不在服务器网段）是另一种结论，必须单独一条。
            "remote_probe_conclusion_diverted": 2,
            # ARP 判据：有 MAC 与没有 MAC 指向完全相反的两边，必须各有文案。
            "remote_probe_arp_complete": 1,
            "remote_probe_arp_incomplete": 1,
            "remote_probe_arp_missing": 1,
            "remote_probe_arp_unknown": 1,
            "remote_probe_conclusion_host_on_lan": 3,
            "remote_probe_conclusion_host_missing": 1,
            # 出口路径：内核选中的网卡与源地址。
            "remote_probe_route_chosen": 2,
            "remote_probe_route_unavailable": 1,
            "remote_probe_network": 1,
            "remote_probe_network_wifi": 0,
            "remote_probe_network_cellular": 0,
            "remote_probe_network_ethernet": 0,
            "remote_probe_network_vpn": 0,
            "remote_probe_network_other": 0,
            "remote_probe_network_none": 0,
            "remote_probe_vpn_active": 0,
            "remote_probe_interfaces": 1,
            "remote_probe_gateway_reachable": 1,
            "remote_probe_gateway_silent": 1,
            "remote_probe_gateway_unknown": 0,
            "remote_probe_checklist": 0,
        }
        for locale in ("values", "values-b+zh+Hans", "values-b+zh+Hant"):
            root = ET.parse(RES / locale / "strings.xml").getroot()
            elements = {element.attrib["name"]: element for element in root.findall("string")}
            for key, placeholders in {**kinds, **probes}.items():
                self.assertIn(key, elements, f"{locale}: {key}")
                text = "".join(elements[key].itertext())
                self.assertEqual(placeholders, text.count("%"), f"{locale}: {key}")
                # Android 资源里裸撇号会导致 aapt 报错。
                self.assertNotIn("'", text, f"{locale}: {key}")


class RemoteDraftContractTest(unittest.TestCase):
    """远程输入草稿的归属：Composer 只交正文，清空与失败回填都在 coordinator。"""

    def test_remote_send_clears_draft_and_restores_undelivered_text(self):
        coordinator = read_code(KOTLIN / "ui/remote/RemoteCoordinator.kt")
        holder = read_code(KOTLIN / "ui/remote/RemoteSessionStateHolder.kt")
        # 首页与对话页两个 action 入口必须走同一个提交函数：漏一个就漏一页。
        self.assertIn("private fun submitText(target: ChatTarget.Remote, text: String)", coordinator)
        self.assertEqual(coordinator.count("submitText(target, action.text)"), 2)
        self.assertNotIn("connection.holder.sendMessage(action.text)", coordinator)
        # 只有 holder 接下这次提交才清输入框：连接没就绪时不该让用户白敲一遍。
        self.assertIn(
            "fun sendMessage(text: String, onDeliveryFailed: (String) -> Unit = {}): Boolean",
            holder,
        )
        self.assertIn('if (accepted) field.setTextAndPlaceCursorAtEnd("")', coordinator)
        # 同步判死与异步帧没发出去都要把原文还回去，且不覆盖期间新敲的字。
        self.assertEqual(holder.count("onDeliveryFailed(text)"), 2)
        self.assertIn(
            "if (field.text.isEmpty()) field.setTextAndPlaceCursorAtEnd(undelivered)",
            coordinator,
        )


if __name__ == "__main__":
    unittest.main()
