package io.github.mangi.eta.agent.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import java.io.File
import java.net.ConnectException
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 远程 agent 连接诊断。
 *
 * 目的只有一个：把「连不上」这种没有信息量的状态，变成**能一眼看出断在哪一层**的结论。
 * 用户反馈「浏览器能打开 webui、App 却超时」时，真正需要区分的是这几种完全不同的故障：
 *
 * 1. **手机根本没到那个网段** —— 设备地址与服务器地址不同网段，路由不通；
 * 2. **TCP 根本连不上** —— 同一网段但 SYN 无响应（AP 隔离、防火墙、走了移动网络）；
 * 3. **TCP 通了但 HTTP 不对** —— 服务端拒绝/重定向/令牌不对；
 * 4. **HTTP 正常但 WS 握手失败** —— 路径、令牌或协议协商不对。
 *
 * 逐级探测（裸 TCP → HTTP → WebSocket）能把这四层分开，
 * 免得用户在一个笼统的「连接超时」上反复瞎猜。
 *
 * 另外多探一层 **局域网网关**：网关有应答而服务器没有，说明「局域网这条路是通的，
 * 是服务器那台机器不接受连接」——这跟「手机压根没在这个局域网里」是两码事，
 * 排查方向完全相反。这一层是区分「路由器/手机的问题」和「电脑防火墙的问题」的关键，
 * 也是唯一能从 App 内部当场自证的一处。
 *
 * 还有一层 **Wi-Fi 强制出站**：默认路由未必是 Wi-Fi——不少 ROM 的「智能网络切换」
 * 会把应用流量搬到移动网络，此时访问 `192.168.x.x` 必然超时，而浏览器可能仍在 Wi-Fi 上。
 * 「默认路由不通、绑 Wi-Fi 就通」这一对比就能直接指认根因。
 *
 * 最后一层 **出口路径**（[Route]）：用 UDP `connect` 查路由表，拿到内核为这条连接
 * 选中的源地址。它把「流量被改道」从猜测变成观测——源地址与服务器不同网段，
 * 就说明包压根没往服务器那个方向发，此时再谈「服务器为什么不回」都是空转。
 */
internal object RemoteConnectionProbe {

    private const val TCP_TIMEOUT_MS = 5_000

    /** 网关探测的单个端口超时。RST 是毫秒级回来的，只有「没人应答」才耗满。 */
    private const val GATEWAY_TIMEOUT_MS = 1_500
    private const val WS_TIMEOUT_MS = 12_000L

    /** 单个探测步骤的结果。 */
    sealed interface Step {
        data object Ok : Step

        /**
         * @param kind 失败性质（决定文案与后续动作）
         * @param detail 附带信息（主机名、HTTP 状态码、原始异常消息）
         */
        data class Failed(
            val kind: RemoteFailure.Kind,
            val detail: String? = null,
        ) : Step

        /** 前一步就断了，本步无从谈起。 */
        data object Skipped : Step
    }

    /** 活动网络的传输类型。只作为界面文案的取值来源，不承载文本。 */
    enum class Transport {
        Wifi,
        Cellular,
        Ethernet,
        Vpn,
        Other,

        /** 系统没报告活动网络。 */
        None,
    }

    /** 局域网网关对 TCP SYN 的反应。 */
    enum class Reach {
        /**
         * 有应答。
         *
         * 注意：**连不上也算有应答**——对方回了 RST，说明包已经在这个局域网里
         * 来回走了一趟。我们问的是「路通不通」，不是「端口开没开」。
         */
        Reachable,

        /** SYN 无人应答，包没能在这个局域网里往返。 */
        Silent,

        /** 没拿到网关地址，无从判断。 */
        Unknown,
    }

    /**
     * 系统 ARP 表里那台机器的状态。
     *
     * 这一层回答的是「那台机器在不在这个局域网里」，**和端口开没开无关**——
     * 也正因为如此，它不会被 Windows 防火墙的「静默丢弃」混淆：
     * 防火墙丢的是 IP 包，ARP 是链路层的，机器活着就一定会回。
     */
    enum class Arp {
        /** 解析到了 MAC：机器就在这个局域网里，包已经送到它网卡上。 */
        Complete,

        /** 表里有条目但没有 MAC：ARP 请求发出去了，没人应答。 */
        Incomplete,

        /** 表里根本没有这条记录。 */
        Missing,

        /** 读不到 ARP 表（新系统可能限制），不下结论。 */
        Unknown,
    }

    /**
     * 内核为某条连接选中的出站路径。
     *
     * 这是整份诊断里**唯一能证明「包到底从哪个网卡发出去的」**的一层：
     * 用一个 `DatagramSocket` 对目标做一次 `connect`（只查路由表，不发包），
     * 再读 `localAddress`——那就是内核为这个目的地选好的源地址。
     *
     * 于是「系统把流量改道到 VPN/移动数据」这种猜测第一次可以被**观测**取代：
     * 源地址与服务器同网段 = 走的是对的网卡；不同网段 = 被改道了。
     */
    sealed interface Route {
        /** @param source 内核选中的源地址 @param iface 该地址所属网卡名（查不到为 null） */
        data class Chosen(val source: String, val iface: String?) : Route

        /** 查不到路由（内核直接报错，或没拿到源地址）。 */
        data class Unavailable(val detail: String) : Route
    }

    /** 一次完整诊断的报告。 */
    data class Report(
        /** 设备自身的 IPv4 地址（活动网络 + 所有网卡）。 */
        val deviceIpv4: List<String>,
        /** 带网卡名的地址列表，用来一眼看出有没有 VPN/移动数据在抢路由。 */
        val interfaces: List<String>,
        /** 活动网络的传输类型。 */
        val transport: Transport,
        /** 系统里是否有 VPN 网络在跑。 */
        val vpnActive: Boolean,
        /** 服务器地址。 */
        val serverHost: String,
        val serverPort: Int,
        /** 服务器地址是 IPv4 字面量（而非域名）时才判断同网段。 */
        val serverIsIpv4Literal: Boolean,
        /** 设备与服务器是否在同一 /24 网段；无法判断时为 null。 */
        val sameSubnet: Boolean?,
        /** 局域网网关地址。 */
        val gateway: String?,
        /** 网关是否应答。 */
        val gatewayReach: Reach,
        /**
         * 内核为「服务器这条连接」选中的出口。
         *
         * 这一条替掉了原先那个「拿一个关闭的端口去连、看会不会回 RST」的判据——
         * 那条判据在默认丢包的防火墙下（Linux 的 `ufw default deny`、Windows Defender）
         * 会被彻底混淆：未放行的端口和关闭的端口一样不回拒绝，于是「没有 RST」
         * 同时对应两种相反的原因，据此写结论就是替用户瞎猜。
         */
        val serverRoute: Route,
        /** 网关的出口。两条连路走同一个网络，才说明局域网这条路是一致的。 */
        val gatewayRoute: Route?,
        /**
         * ARP 表里那台机器的状态——回答「机器在不在这个局域网里」。
         *
         * 比端口探测更可靠：防火墙丢的是 IP 包，ARP 在链路层，机器活着就一定会回。
         *
         * **注意**：Android 10 起 `/proc/net/arp` 就不对应用开放，实测恒为
         * [Arp.Unknown]。所以结论不能建立在这一行上，[localNetwork] 才是新的主力判据。
         */
        val hostArp: Arp,
        /**
         * 系统层面是否允许本应用访问局域网。
         *
         * 这一条能解释「浏览器打得开、本应用超时」这种最让人抓狂的现象：
         * 被拦时包在本机协议栈里就被静默丢弃，线路上一包都看不到，
         * 任何对端排查（抓包、防火墙、监听地址）都是白费。
         */
        val localNetwork: LocalNetworkPermission.AccessState,
        /** 默认路由下的 TCP 结果。 */
        val tcp: Step,
        /** 绑到 Wi-Fi 网络后的 TCP 结果；默认路由本来就通时为 null（不必测）。 */
        val tcpViaWifi: Step?,
        val http: Step,
        val websocket: Step,
    ) {
        /** 第一处失败即为根因；全通过说明链路本身没问题。 */
        val firstFailure: Step.Failed?
            get() = listOf(tcp, tcpViaWifi, http, websocket)
                .filterNotNull()
                .firstNotNullOfOrNull { it as? Step.Failed }

        /**
         * 系统把本应用的局域网出口掐了。
         *
         * 判据：TCP 握手失败 + 目标是局域网地址 + 权限闸门没开（未授予或被 app-op 拒绝）。
         * 这条**优先于其他所有结论**：被拦的时候，网关、ARP、对端全都无从谈起。
         */
        val lanBlockedBySystem: Boolean
            get() = brokenAtTcp &&
                LocalNetworkPermission.isLanAddress(serverHost) &&
                LocalNetworkPermission.blocksConnection(localNetwork)

        /** TCP 这一层就没通（后面的 HTTP/WS 全部没机会跑）。 */
        val brokenAtTcp: Boolean
            get() = tcp is Step.Failed

        /**
         * 默认路由不通、但绑到 Wi-Fi 就通了。
         *
         * 这是「默认路由被切到移动网络」的指纹——是这类问题里唯一
         * 能从 App 内部自证的一处，也是最值得让用户先看的一行。
         */
        val wifiRouteWorkaround: Boolean
            get() = tcp is Step.Failed && tcpViaWifi is Step.Ok

        /**
         * 局域网本身是通的，只是服务器那一端没接受连接。
         *
         * 判据：TCP 握手失败，但网关有应答。这条一旦成立，
         * 「手机不在这个局域网」就彻底排除了，问题必然在服务器那一条路线上
         * （电脑防火墙静默丢包、服务只监听了 127.0.0.1、端口写错）。
         */
        val lanFineHostSilent: Boolean
            get() = brokenAtTcp && gatewayReach == Reach.Reachable

        /**
         * 这条连接被内核送到了另一个网络。
         *
         * 判据：内核为它选中的源地址与服务器不在同一 /24。典型成因是 VPN 接管了路由，
         * 或系统把该应用的流量切到了移动数据。此时包**根本没往服务器那个方向发**，
         * 后面所有「服务器为什么不回」的猜测都谈不上——而这一点是能被观测到的，
         * 不必再让用户去猜。
         */
        val routeDiverted: Boolean
            get() {
                if (!brokenAtTcp || !serverIsIpv4Literal) return false
                val chosen = serverRoute as? Route.Chosen ?: return false
                return !chosen.source.inSameSubnetAs(serverHost)
            }

        /** 内核选中的源地址与服务器同网段：包确实朝服务器那个方向发出去了。 */
        val routeOnSameSubnet: Boolean
            get() {
                if (!serverIsIpv4Literal) return false
                val chosen = serverRoute as? Route.Chosen ?: return false
                return chosen.source.inSameSubnetAs(serverHost)
            }
    }

    /**
     * 跑一次完整诊断。
     *
     * @param context 用于读取活动网络的本地地址
     * @param host 服务器主机名或 IP
     * @param port 服务器端口
     * @param token 访问令牌（可为空——服务端可能关闭了鉴权）
     */
    suspend fun run(
        context: Context,
        host: String,
        port: Int,
        token: String,
    ): Report = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val deviceIps = deviceIpv4(manager)
        val serverIp = host.toInet4()
        val sameSubnet = serverIp?.let { server ->
            deviceIps.any { it.inSameSubnetAs(server.hostAddress.orEmpty()) }
        }
        val gateway = gatewayOf(manager)
        // 直接用这里已经拿到的 manager 去找 Wi-Fi 网络，不绕回全局单例：
        // 单例要是没注入（或注入晚了），这一行会静默消失，而它恰恰是关键判据之一。
        val wifiNetwork = RemoteHttpClient.wifiNetwork(manager)

        val tcp = probeTcp(host, port)
        // 默认路由连不上时，才值得再从 Wi-Fi 网络发起一次对比。
        val tcpViaWifi = if (tcp is Step.Failed && wifiNetwork != null) {
            probeTcpViaWifi(host, port, wifiNetwork)
        } else {
            null
        }
        // 网关只在 TCP 失败时才有必要探——TCP 通了就说明路本来就是通的。
        val gatewayReach = if (tcp is Step.Failed && gateway != null) {
            probeGateway(gateway)
        } else if (gateway == null) {
            Reach.Unknown
        } else {
            Reach.Reachable
        }
        // 出口路径只查路由表、不发包，却能直接回答「这条连接到底从哪个网卡出去」——
        // 是「被改道」与「发对了但没人应」的分界线。
        val serverRoute = probeRoute(host, port)
        val gatewayRoute = gateway?.let { probeRoute(it, GATEWAY_PORTS[0]) }
        // ARP 必须放在 TCP 尝试之后读：内核正是被刚才那次 connect 触发去解析 MAC 的。
        val hostArp = if (tcp is Step.Failed) arpStateOf(host) else Arp.Unknown
        val http = if (tcp is Step.Ok) probeHttp(host, port, token) else Step.Skipped
        val websocket = if (http is Step.Ok) probeWebSocket(host, port, token) else Step.Skipped

        val report = Report(
            deviceIpv4 = deviceIps,
            interfaces = interfaceList(),
            transport = transportOf(manager),
            vpnActive = RemoteHttpClient.hasVpn(manager),
            serverHost = host,
            serverPort = port,
            serverIsIpv4Literal = serverIp != null,
            sameSubnet = sameSubnet,
            gateway = gateway,
            gatewayReach = gatewayReach,
            serverRoute = serverRoute,
            gatewayRoute = gatewayRoute,
            hostArp = hostArp,
            localNetwork = LocalNetworkPermission.accessState(context),
            tcp = tcp,
            tcpViaWifi = tcpViaWifi,
            http = http,
            websocket = websocket,
        )
        // 诊断出「默认路由不通、绑 Wi-Fi 就通」，或发现出口被改道到别的网络时，
        // 直接把这个结论用到真实连接上：让用户看完报告后重连就能通，
        // 不必等他再手动试几次撞上重试逻辑。
        if (report.wifiRouteWorkaround || report.routeDiverted) {
            RemoteHttpClient.preferWifiOnNextAttempt()
        }
        report
    }

    /**
     * 第一层：裸 TCP 连接。
     *
     * 刻意绕开 OkHttp——这一层只回答「包能不能送到那个 IP:端口」，
     * 把网络可达性和 HTTP 协议行为彻底分开。
     *
     * **必须传 [RemoteFailure.Phase.Connect]**：这里除了 `Socket.connect` 什么也没做，
     * 超时只可能发生在握手阶段。不传的话就退化成按异常 message 猜，
     * 而 ART 的措辞是 `failed to connect to /x.x.x.x (port n) after 5000ms`，
     * 会被误判成「已连上但没回应」，直接误导用户去查错误的方向。
     */
    private suspend fun probeTcp(host: String, port: Int): Step = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            }
            Step.Ok
        } catch (t: Throwable) {
            Step.Failed(
                RemoteFailure.kindOf(t, RemoteFailure.Phase.Connect),
                RemoteFailure.detailOf(t),
            )
        }
    }

    /**
     * 第一层的对照组：把 socket 绑到 Wi-Fi 网络之后再连。
     *
     * 用 `Network.bindSocket()` 而不是绑源地址——Android 的出接口按网络选，
     * 绑源地址改不了出接口（详见 [RemoteHttpClient.bindToWifi]）。
     */
    private suspend fun probeTcpViaWifi(
        host: String,
        port: Int,
        network: Network,
    ): Step = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                if (!RemoteHttpClient.bindToWifi(socket, network)) {
                    // 绑定失败意味着这条对照根本没跑，绝不能让它显示成「连接超时」——
                    // 那会让用户以为「换 Wi-Fi 也不行」，而实验压根没做。
                    return@withContext Step.Failed(
                        RemoteFailure.Kind.BindFailed,
                        "Network.bindSocket 未成功",
                    )
                }
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            }
            Step.Ok
        } catch (t: Throwable) {
            Step.Failed(
                RemoteFailure.kindOf(t, RemoteFailure.Phase.Connect),
                RemoteFailure.detailOf(t),
            )
        }
    }

    /**
     * 查内核为 `host:port` 这条连接选中的出口。
     *
     * 做法：用一个 UDP socket 对目标做 `connect`。UDP 的 `connect` **不发任何包**，
     * 只做一次路由查表并把选中的源地址记在 socket 上；随后读 `localAddress` 即可。
     * 于是不需要 root、不需要抓包，就能拿到「内核打算从哪个 IP 发出去」这个事实。
     *
     * 为什么要它：`Socket.connect` 超时只说明「没收到 SYN-ACK」，这既可能是
     * 包发错了网络（VPN/移动数据接管路由），也可能是包发对了但被对端或链路吞了。
     * 源地址与服务器同网段 ⇒ 发对了；不同网段 ⇒ 被改道。两者处置完全相反。
     */
    private suspend fun probeRoute(host: String, port: Int): Route = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port))
                val source = socket.localAddress?.hostAddress
                    ?: return@withContext Route.Unavailable("内核未返回源地址")
                Route.Chosen(source, interfaceOf(source))
            }
        } catch (t: Throwable) {
            // detailOf 可能拿不到 message（比如 SecurityException 只带类型），兜底用类名，
            // 绝不能让这行变成空串——用户看到「查不到路由：」后面什么都没有等于没说。
            Route.Unavailable(RemoteFailure.detailOf(t) ?: t.javaClass.simpleName)
        }
    }

    /** 某个本地地址属于哪张网卡。查不到就返回 null（不猜）。 */
    private fun interfaceOf(address: String): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .firstOrNull { nic ->
                nic.inetAddresses.toList().any { it.hostAddress == address }
            }
            ?.name
    }.getOrNull()

    /**
     * 读系统 ARP 表，看那台机器的 MAC 有没有解析出来。
     *
     * 这是整份诊断里唯一不受「防火墙静默丢弃」影响的判据：防火墙丢的是 IP 包，
     * ARP 在链路层，机器活着就一定会回。于是：
     *
     * - **有 MAC + TCP 没回应** ＝ 包已经送到它网卡上，是那台机器自己没应答（防火墙/服务）；
     * - **没有 MAC** ＝ 这个 IP 在局域网里没人认领（关机、不在这个网段、AP 隔离）。
     *
     * 两者排查方向完全相反，而且都不能靠端口探测推出来。新系统可能限制读取，读不到就
     * 返回 [Arp.Unknown]，不给结论——宁可少说，也不替用户猜。
     */
    private fun arpStateOf(host: String): Arp = try {
        val table = File("/proc/net/arp")
        if (!table.canRead()) Arp.Unknown else parseArpTable(table.readLines(), host)
    } catch (t: Throwable) {
        Arp.Unknown
    }

    /**
     * 解析 `/proc/net/arp` 的文本内容。抽成纯函数便于单测。
     *
     * 每行格式：`IP 地址  类型  Flags  MAC 掩码  设备  `，Flags 是十六进制，
     * 0x2（ATF_COM）表示条目完整、MAC 可用；0x0 表示问过了但没人应答。
     */
    internal fun parseArpTable(lines: List<String>, host: String): Arp {
        val fields = lines.drop(1)
            .map { it.trim().split(WHITESPACE) }
            .firstOrNull { it.firstOrNull() == host } ?: return Arp.Missing
        val flags = fields.getOrNull(2)?.toIntOrNull(16) ?: return Arp.Unknown
        return if ((flags and ARP_FLAG_COMPLETE) != 0) Arp.Complete else Arp.Incomplete
    }

    /**
     * 网关可达性。
     *
     * 判定标准只有一条：**能不能从这个局域网收到任何回应**。
     * 因此「连接被拒绝」（对方回 RST）同样算 [Reach.Reachable]——
     * RST 是路由器亲自发的包，比什么都更能证明链路通畅。
     *
     * 依次试 80 / 443 / 53：路由器管理页、HTTPS、DNS 三处几乎总有一处会应答。
     */
    private suspend fun probeGateway(gateway: String): Reach = withContext(Dispatchers.IO) {
        for (port in GATEWAY_PORTS) {
            if (tryGatewayPort(gateway, port) == Reach.Reachable) return@withContext Reach.Reachable
        }
        Reach.Silent
    }

    private fun tryGatewayPort(gateway: String, port: Int): Reach = try {
        Socket().use { it.connect(InetSocketAddress(gateway, port), GATEWAY_TIMEOUT_MS) }
        Reach.Reachable
    } catch (e: ConnectException) {
        // 收到 RST：包送到了，只是这个端口没服务。
        Reach.Reachable
    } catch (e: Throwable) {
        Reach.Silent
    }

    /** 第二层：HTTP GET 根路径，确认服务进程活着且令牌策略符合预期。 */
    private suspend fun probeHttp(host: String, port: Int, token: String): Step = withContext(Dispatchers.IO) {
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(RemoteServerAddress.bareHost(host))
            .port(port)
            .addPathSegment("")
            .apply { if (token.isNotEmpty()) addQueryParameter("token", token) }
            .build()
        try {
            RemoteHttpClient.client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                when (response.code) {
                    200 -> Step.Ok
                    401, 403 -> Step.Failed(RemoteFailure.Kind.Unauthorized, response.code.toString())
                    404 -> Step.Failed(RemoteFailure.Kind.NotFound, response.code.toString())
                    else -> Step.Failed(RemoteFailure.Kind.Unknown, "HTTP ${response.code}")
                }
            }
        } catch (t: Throwable) {
            Step.Failed(RemoteFailure.kindOf(t, RemoteFailure.Phase.Read), RemoteFailure.detailOf(t))
        }
    }

    /** 第三层：WebSocket 握手，验证 `/ws` 路径与令牌确实能换到 101。 */
    private suspend fun probeWebSocket(host: String, port: Int, token: String): Step =
        withContext(Dispatchers.IO) {
            val url = HttpUrl.Builder()
                .scheme("http")
                .host(RemoteServerAddress.bareHost(host))
                .port(port)
                .addPathSegment("ws")
                .apply { if (token.isNotEmpty()) addQueryParameter("token", token) }
                .build()
            val outcome = withTimeoutOrNull(WS_TIMEOUT_MS) {
                // 类型必须写死成 Step：两条 resume 分支分别传 Step.Ok 与 Step.Failed，
                // 让推断器自己挑会挑成 Step.Ok，另一条分支就编不过了。
                suspendCancellableCoroutine<Step> { continuation ->
                    // 超时取消协程时，OkHttp 的 WebSocket 不会跟着取消——得手动收掉，
                    // 否则每次诊断都在后台留一条挂着的连接。
                    var socket: WebSocket? = null
                    continuation.invokeOnCancellation { runCatching { socket?.cancel() } }
                    val listener = object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            if (continuation.isActive) {
                                continuation.resumeWith(Result.success(Step.Ok))
                            }
                            webSocket.cancel()
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) = Unit
                        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (!continuation.isActive) return
                            val code = response?.code
                            val kind = when (code) {
                                401, 403 -> RemoteFailure.Kind.Unauthorized
                                404 -> RemoteFailure.Kind.NotFound
                                // 走到这里 TCP 早就通了，任何超时都是「连上了但对方不说话」。
                                else -> RemoteFailure.kindOf(t, RemoteFailure.Phase.Read)
                            }
                            val detail = code?.toString() ?: RemoteFailure.detailOf(t)
                            continuation.resumeWith(Result.success(Step.Failed(kind, detail)))
                        }
                    }
                    socket = RemoteHttpClient.client.newWebSocket(
                        Request.Builder().url(url).build(),
                        listener,
                    )
                }
            }
            outcome ?: Step.Failed(
                RemoteFailure.Kind.ReadTimeout,
                "握手 ${WS_TIMEOUT_MS / 1000} 秒内没有收到 101 响应",
            )
        }

    /** 活动网络的传输类型。 */
    private fun transportOf(manager: ConnectivityManager?): Transport = runCatching {
        val connectivity = manager ?: return Transport.None
        val network = connectivity.activeNetwork ?: return Transport.None
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return Transport.None
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.Cellular
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.Ethernet
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> Transport.Vpn
            else -> Transport.Other
        }
    }.getOrDefault(Transport.None)

    /** 活动网络的默认网关（IPv4）。 */
    private fun gatewayOf(manager: ConnectivityManager?): String? = runCatching {
        val connectivity = manager ?: return null
        val network = connectivity.activeNetwork ?: return null
        connectivity.getLinkProperties(network)?.routes
            ?.firstOrNull { it.gateway is Inet4Address && it.destination.prefixLength == 0 }
            ?.gateway
            ?.hostAddress
    }.getOrNull()

    /**
     * 设备自身的 IPv4 地址。
     *
     * 优先取**活动网络**的本地地址——那才是 App 流量实际会用的地址；
     * 再补上所有网卡，避免活动网络信息缺失时什么都看不到。
     */
    private fun deviceIpv4(manager: ConnectivityManager?): List<String> {
        val fromActive = runCatching {
            val network = manager?.activeNetwork
            val properties: LinkProperties? = network?.let { manager?.getLinkProperties(it) }
            properties?.linkAddresses
                ?.mapNotNull { it.address as? Inet4Address }
                ?.map { it.hostAddress }
                .orEmpty()
        }.getOrDefault(emptyList())
        val fromInterfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
        }.getOrDefault(emptyList())
        return (fromActive + fromInterfaces).distinct()
    }

    /**
     * 带网卡名的地址列表，例如 `wlan0 192.168.31.162`、`rmnet_data0 100.108.242.39`。
     *
     * 网卡名本身就有信息量：出现 `tun0`/`ppp0` 说明有 VPN 在接管路由，
     * 出现 `rmnet` 说明移动数据也在线——这些正是「明明连了 Wi-Fi 却到不了局域网」
     * 的常见背景，用户看一眼就能对上。
     */
    private fun interfaceList(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic ->
                nic.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { "${nic.name} ${it.hostAddress}" }
            }
    }.getOrDefault(emptyList())

    /**
     * 把主机名或字面量解析成 IPv4；不是 IPv4 字面量时返回 null。
     *
     * 刻意**不做 DNS 解析**：`InetAddress.getByName` 对一个解析不了的名字会
     * 干等几十秒，而这一层只想知道「服务器地址是不是写的 IP」——
     * 写成字面量判断既快又不会拖住诊断。
     */
    private fun String.toInet4(): Inet4Address? {
        if (isEmpty() || any { it != '.' && !it.isDigit() }) return null
        return runCatching { InetAddress.getByName(this) as? Inet4Address }.getOrNull()
    }

    /** 是否落在同一个 /24 网段。 */
    private fun String.inSameSubnetAs(other: String): Boolean {
        val a = split('.').take(3)
        val b = other.split('.').take(3)
        return a.size == 3 && b.size == 3 && a == b
    }

    private val GATEWAY_PORTS = intArrayOf(80, 443, 53)

    /** `/proc/net/arp` 里 flags 的 ATF_COM 位：条目完整、MAC 有效。 */
    private const val ARP_FLAG_COMPLETE = 0x2

    private val WHITESPACE = Regex("\\s+")
}
