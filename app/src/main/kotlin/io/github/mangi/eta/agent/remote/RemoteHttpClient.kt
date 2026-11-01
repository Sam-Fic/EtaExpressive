package io.github.mangi.eta.agent.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * 局域网远程 agent 专用客户端。
 *
 * **不能复用 [io.github.mangi.eta.agent.model.AgentHttpClient]**：那个客户端服务于云端
 * 模型 API，需要跟随系统代理与长读超时；而远程 agent 走的是局域网地址，两者要求正好相反：
 *
 * - **绕过代理**：系统 Wi-Fi 上若配了 HTTP 代理（或装了代理类 App），OkHttp 会把
 *   `http://192.168.x.x:8800` 也交给代理转发——代理通常根本不认识这条局域网地址，
 *   于是连接被拖到超时。浏览器自带「局域网地址不走代理」的例外规则，所以浏览器能开、
 *   App 却超时。这个差异必须在客户端层面抹平。
 * - **连接超时收紧**：局域网可达性是「毫秒级」的，连不上多半是地址错/被拦，
 *   20 秒才报错会让用户干等。8 秒足够区分「慢」和「不通」。
 * - **不复用模型流量的拦截器**：Provider 相关的请求头对远程 agent 没有意义。
 */
internal object RemoteHttpClient {

    private const val CONNECT_TIMEOUT_MS = 8_000L
    private const val READ_TIMEOUT_MS = 30_000L
    private const val WRITE_TIMEOUT_MS = 120_000L

    /** 池内空闲连接的养命时长；必须小于已知对端（Node 默认）的 5 秒 keep-alive。 */
    private const val POOL_KEEP_ALIVE_SECONDS = 3L

    /**
     * 应用上下文，只用来查网络。
     *
     * 在 [io.github.mangi.eta.EtaApp.onCreate] 里注入。缺失时所有「绑 Wi-Fi」的能力
     * 静默退化为默认路由——这是可接受的降级，不该让远程连接因为拿不到 Context 而崩。
     */
    @Volatile private var appContext: Context? = null

    /**
     * 「下一跳优先走 Wi-Fi」开关。
     *
     * 默认路由未必是 Wi-Fi：不少 ROM 的「智能网络切换」会把应用的流量搬到移动网络，
     * 此时访问 `192.168.x.x` 必然超时（移动网络到不了局域网），而浏览器可能仍在 Wi-Fi 上。
     * 连接超时后把它打开，下一次尝试把 socket 绑到 Wi-Fi 网络上，强制从 Wi-Fi 出。
     */
    private val preferWifi = AtomicBoolean(false)

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 系统是否允许本应用访问局域网。
     *
     * 没注入 Context 时返回 [LocalNetworkPermission.AccessState.UNKNOWN]——
     * 猜成「允许」会把一次本该说清楚的拦截继续报成「服务器不回应」。
     */
    fun localNetworkState(): LocalNetworkPermission.AccessState =
        appContext?.let { LocalNetworkPermission.accessState(it) }
            ?: LocalNetworkPermission.AccessState.UNKNOWN

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            // 连接池空闲寿命必须压在对端 keep-alive 之下。opencode（Node/Bun http）
            // 回 `Keep-Alive: timeout=5`——5 秒空闲就断，而 OkHttp 池默认养 5 分钟：
            // 复用一条对端已关闭的死连接会直接「unexpected end of stream」，且本客户端
            // 关掉了自动重试（见上，为了不悄悄重发聊天帧），死连接会原样炸到调用方。
            // 把池缩到 3 秒，从源头消灭死连接复用；局域网 TCP 握手亚毫秒级，
            // 多握一次手的代价可以忽略。
            .connectionPool(ConnectionPool(5, POOL_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
            // 局域网地址永远不走代理——代理既不认识它，也不该经它转发。
            .proxy(Proxy.NO_PROXY)
            .socketFactory(LanSocketFactory())
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()
                if (original.header("User-Agent") == null) {
                    builder.header("User-Agent", "Eta-Android")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    /**
     * 标记「下一次连接尝试优先走 Wi-Fi」。
     *
     * 由连接监管器在判定为 TCP 连接超时后调用：能在移动网络超时、换 Wi-Fi 就通，
     * 说明卡点是默认路由而不是地址本身。
     */
    fun preferWifiOnNextAttempt() {
        preferWifi.set(true)
    }

    /** 是否已处在「优先走 Wi-Fi」状态。 */
    fun isPreferringWifi(): Boolean = preferWifi.get()

    fun connectivityManager(): ConnectivityManager? =
        appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** 系统当前的 Wi-Fi 网络；没连 Wi-Fi 时返回 null。 */
    fun wifiNetwork(): Network? = wifiNetwork(connectivityManager())

    /**
     * 从给定的 [ConnectivityManager] 里找 Wi-Fi 网络。
     *
     * 单独给一个接收 manager 的重载，是因为调用方（诊断探针）本来就已经持有 Context：
     * 让它绕回全局单例，只要注入晚了或没注入，就会静默退化成「没有 Wi-Fi」——
     * 而这一层恰恰是排查路由问题的关键，不能因为初始化时序而悄悄消失。
     */
    fun wifiNetwork(manager: ConnectivityManager?): Network? = runCatching {
        val connectivity = manager ?: return null
        // 活动网络本来就是 Wi-Fi 时直接用它：allNetworks 里可能还挂着别的 Wi-Fi
        // 网络对象，挑错一个就等于绑定到一个根本没连上的网络。
        val active = connectivity.activeNetwork
        if (active != null &&
            connectivity.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        ) {
            return active
        }
        connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }.getOrNull()

    /** 系统当前有没有 VPN 网络在跑（VPN 会改变到局域网的路由）。 */
    fun hasVpn(): Boolean = hasVpn(connectivityManager())

    fun hasVpn(manager: ConnectivityManager?): Boolean = runCatching {
        val connectivity = manager ?: return false
        connectivity.allNetworks.any { network ->
            connectivity.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    /**
     * 把 [socket] 绑到 Wi-Fi 网络上，返回是否真的绑上了。
     *
     * **必须用 `Network.bindSocket()`，不能用 `socket.bind(源地址)`。**
     * Android 的出接口是按网络（netId + fwmark）选的，源地址绑定只改了源 IP，
     * socket 仍归属于建它时的那个网络：移动数据是默认网络时，绑了 Wi-Fi 的源地址
     * 照样从移动数据发出去——表现就是「对照组和默认路由一起失败」，
     * 让人误以为局域网本身不通。
     *
     * 绑不上（没 Wi-Fi、权限不足、socket 已连接）时返回 false，
     * 调用方按默认路由走——最坏情况就是原来的行为。
     */
    fun bindToWifi(socket: Socket): Boolean = bindToWifi(socket, wifiNetwork())

    /** 绑到指定网络上；[network] 为 null 时什么都不做并返回 false。 */
    fun bindToWifi(socket: Socket, network: Network?): Boolean {
        if (network == null) return false
        return runCatching {
            network.bindSocket(socket)
            true
        }.getOrDefault(false)
    }

    /**
     * 建 socket 时按需绑到 Wi-Fi 网络。
     *
     * 四个重载都走同一条「先绑后连」的路径：OkHttp 对 DIRECT 连接用的是无参
     * `createSocket()`（随后自己 connect），但各版本调用点不完全一致；
     * 只在一个重载里绑定的话，换个重载就等于没绑——这个开关一开就是用来救命的，
     * 不能看运气。
     */
    private class LanSocketFactory : SocketFactory() {
        override fun createSocket(): Socket {
            val socket = Socket()
            bindWifi(socket)
            return socket
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val socket = Socket()
            bindWifi(socket)
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(host: String, port: Int): Socket {
            val socket = Socket()
            bindWifi(socket)
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket {
            val socket = Socket()
            bindWifi(socket)
            if (!socket.isBound) {
                runCatching { socket.bind(InetSocketAddress(localHost, localPort)) }
            }
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        override fun createSocket(
            host: InetAddress,
            port: Int,
            localHost: InetAddress,
            localPort: Int,
        ): Socket {
            val socket = Socket()
            bindWifi(socket)
            if (!socket.isBound) {
                runCatching { socket.bind(InetSocketAddress(localHost, localPort)) }
            }
            socket.connect(InetSocketAddress(host, port))
            return socket
        }

        /** 开关打开时把 socket 绑到 Wi-Fi 网络；没开或绑不上就什么都不做。 */
        private fun bindWifi(socket: Socket) {
            if (!preferWifi.get()) return
            bindToWifi(socket)
        }
    }
}
