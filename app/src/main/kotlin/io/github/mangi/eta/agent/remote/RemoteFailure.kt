package io.github.mangi.eta.agent.remote

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 底层网络异常 → **失败性质**。
 *
 * 这里必须讲清楚一个坑：**`SocketTimeoutException` 同时表示两件完全不同的事**，
 * 而两者的异常类型完全一样，只能靠 message 区分（已用 OkHttp 5.4 实测确认）：
 *
 * | 场景 | 异常类型 | message |
 * |---|---|---|
 * | TCP 握手没完成（SYN 无回应） | `SocketTimeoutException` | `connect timed out` |
 * | TCP 已建立、服务器迟迟不回数据 | `SocketTimeoutException` | `read timed out`（ART 上可能是裸的 `timeout`） |
 * | 目标端口没人监听 | `ConnectException` | `Connection refused` |
 *
 * 之前一视同仁地写成「连接超时：确认手机与服务器连的是同一个局域网」——
 * 那是在**替用户断言原因**：用户明明和服务器连的是同一个局域网、浏览器也能打开
 * WebUI，却一直被告知去检查局域网，最后只能得出「这程序写错了」的结论。
 *
 * 所以这里的规矩是：
 *
 * 1. 只陈述**已经观测到的事实**（断在哪一层、失败是什么性质）；
 * 2. 可能的原因用「可能」列出，并给出**下一步能做什么**；
 * 3. 绝不把猜测写成结论。
 *
 * 分类结果只在这里产生一次，UI 层按 [Kind] 取本地化文案（见
 * `ui/remote/RemoteStatusText.kt`），不再在各个界面各写一份中文。
 */
object RemoteFailure {

    /**
     * 异常发生时**正在做哪个动作**。
     *
     * 这是区分两种超时的唯一可靠依据。`SocketTimeoutException` 的 message 在
     * 不同 JDK / ART 版本上措辞并不统一——实测 Android 上裸 `Socket.connect()`
     * 超时抛出的是 `failed to connect to /192.168.31.107 (port 8800) after 5000ms`，
     * 既没有 `connect timed out` 也没有 `timeout` 字样。只靠字符串匹配会把它
     * 误判成「已连上但服务器没回应」，把一个「包没到达」的问题说成「对方不理我」，
     * 从而把用户引向完全错误的排查方向。
     *
     * 所以凡是调用方**自己知道**处在哪个阶段的，必须显式传进来，不要让它猜。
     */
    enum class Phase {
        /** 正在建立 TCP 连接（`Socket.connect` / OkHttp 的 connectTimeout）。 */
        Connect,

        /** TCP 已建立，正在等对端数据（OkHttp 的 readTimeout）。 */
        Read,

        /** 调用方也不知道，退回按 message 猜。 */
        Unknown,
    }

    /** 失败性质。与界面文案一一对应，不承载任何本地化文本。 */
    enum class Kind {
        /** TCP 握手没完成：包没有到达服务器。 */
        ConnectTimeout,

        /** TCP 已建立，服务器没在超时内回应。 */
        ReadTimeout,

        /** 目标端口没有服务在监听。 */
        Refused,

        /** 设备没有到服务器网段的路由。 */
        Unreachable,

        /** 主机名解析失败。 */
        UnknownHost,

        /** 地址本身写得不对（非法主机名、缺端口等）。 */
        MalformedAddress,

        /** 系统以权限为由拒绝了这次连接。 */
        PermissionDenied,

        /** 明文 HTTP 被系统网络安全策略拦截。 */
        CleartextBlocked,

        /** 令牌被服务端拒绝（HTTP 401/403）。 */
        Unauthorized,

        /** 服务端没有这条路径（HTTP 404）。 */
        NotFound,

        /** 配置里写了个不认识的协议。 */
        UnsupportedProtocol,

        /**
         * 「强制走 Wi-Fi」那条对照压根没跑起来：socket 没能绑到 Wi-Fi 网络。
         *
         * 必须单独成一类，否则它会被显示成和「连接超时」一模一样的一行，
         * 用户据此认定「换 Wi-Fi 也不行」——而那是一条根本没做的实验。
         */
        BindFailed,

        /**
         * 系统层面拦住了本应用访问局域网（Android 16+ 的「本地网络访问」）。
         *
         * 实测（小米 2304FPN6DC / Android 17 / targetSdk 36）：`ACCESS_LOCAL_NETWORK`
         * 权限位显示 `granted`，但同名 app-op 是 `ignore`，于是**发往局域网的包在
         * 本机协议栈里被静默丢弃**——socket 一直停在 `SYN-SENT`，线路上一包都看不到，
         * iptables 也没有任何丢弃计数。表现和「服务器不回应」完全一样，
         * 而同一台手机上的浏览器、adb shell 都正常，因为它们是别的 uid。
         *
         * 这类失败**重试一百次也没用**，只能由用户去系统设置里放行。
         */
        LocalNetworkBlocked,

        /** 没归到以上任何一类。 */
        Unknown,
    }

    /**
     * 判定失败性质。
     *
     * 走完整条 cause 链再下结论：OkHttp 会把底层异常包一层
     * （`ConnectException: Failed to connect to …` ← `ConnectException: Connection refused`），
     * 只看最外层会丢掉真正的原因。
     *
     * @param phase 调用方已知的动作阶段；[Phase.Unknown] 时才按 message 猜。
     */
    fun kindOf(t: Throwable, phase: Phase = Phase.Unknown): Kind {
        val chain = causeChain(t)
        val messages = chain.map { it.message.orEmpty() }
        val joined = messages.joinToString(" ").lowercase()

        if (chain.any { it is UnknownHostException }) return Kind.UnknownHost
        if (joined.contains("cleartext") || joined.contains("network security policy")) {
            return Kind.CleartextBlocked
        }
        if (joined.contains("eperm") ||
            joined.contains("operation not permitted") ||
            joined.contains("permission denied")
        ) {
            return Kind.PermissionDenied
        }
        if (chain.any { it is SocketTimeoutException }) {
            return when (phase) {
                Phase.Connect -> Kind.ConnectTimeout
                Phase.Read -> Kind.ReadTimeout
                Phase.Unknown -> if (looksLikeConnectTimeout(messages)) {
                    Kind.ConnectTimeout
                } else {
                    Kind.ReadTimeout
                }
            }
        }
        if (joined.contains("connection refused")) return Kind.Refused
        if (joined.contains("network is unreachable") || joined.contains("no route to host")) {
            return Kind.Unreachable
        }
        if (chain.any { it is NoRouteToHostException || it is PortUnreachableException }) {
            return Kind.Unreachable
        }
        if (chain.any { it is ConnectException }) return Kind.Refused
        return Kind.Unknown
    }

    /**
     * 只在不知道阶段时用的兜底判断。
     *
     * 覆盖两种实测措辞：OkHttp 的 `connect timed out`，以及 Android 裸
     * `Socket.connect` 的 `failed to connect to /1.2.3.4 (port 8000) after 5000ms`。
     */
    private fun looksLikeConnectTimeout(messages: List<String>): Boolean = messages.any { text ->
        val lower = text.lowercase()
        lower.contains("connect timed out") ||
            lower.contains("connect timeout") ||
            lower.contains("failed to connect")
    }

    /**
     * 附带信息：主机名、HTTP 状态码或原始异常消息。
     *
     * 文案模板里用 `%1$s` 之类的占位符接它，不同语言可以有不同语序。
     */
    fun detailOf(t: Throwable): String? {
        val chain = causeChain(t)
        chain.firstOrNull { it is UnknownHostException }?.let { return hostOf(it) }
        return chain.firstNotNullOfOrNull { it.message?.takeIf { message -> message.isNotBlank() } }
    }

    /** 失败是不是重试也救不回来。 */
    fun isTerminal(kind: Kind): Boolean = when (kind) {
        Kind.UnknownHost,
        Kind.MalformedAddress,
        Kind.PermissionDenied,
        Kind.CleartextBlocked,
        Kind.Unauthorized,
        Kind.NotFound,
        Kind.UnsupportedProtocol,
        // 系统不让本应用碰局域网：重连多少次都是同一个结果，只能等用户放行。
        Kind.LocalNetworkBlocked,
        -> true
        else -> false
    }

    /** 因果链，最外层在前。带环保护。 */
    private fun causeChain(t: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        val seen = mutableSetOf<Throwable>()
        var current: Throwable? = t
        while (current != null && seen.add(current)) {
            chain += current
            current = current.cause
        }
        return chain
    }

    /**
     * 从 `UnknownHostException` 的消息里抠出主机名。
     *
     * ART 的消息是「无法解析主机 "xxx"」/「Unable to resolve host "xxx"」，
     * 取引号里的内容；没有引号就退到最后一个空格之后。
     */
    private fun hostOf(error: Throwable): String {
        val message = error.message.orEmpty().trim()
        if (message.isEmpty()) return "未知主机"
        val quoted = Regex("\"([^\"]+)\"").find(message)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted
        return message.substringAfterLast(' ').ifBlank { message }
    }
}
