package io.github.mangi.eta.agent.remote

/**
 * 用户手里拿到的通常是 pi-remote 打印/二维码给出的完整 WebUI 链接：
 *
 * ```
 * http://192.168.31.107:8800?token=4f94995bca6d23da7749cf8c0d52f0da
 * ```
 *
 * 把它整段塞进「主机」字段，连接层会拼出 `ws://http://192.168.31.107:8800…`
 * 这种废地址——主机名被当成 `http`，于是永远 UnknownHostException，而界面上只剩
 * 一句没有原因的「连接失败」。这里把 IP、host:port、http(s) 链接、ws 链接统一
 * 拆成 (host, port, token)，让设置页能自动回填，用户不必自己拆链接。
 *
 * 解析是纯函数：不碰网络、不碰 Context，方便直接单测。
 */
internal object RemoteServerAddress {

    /**
     * @param host 裸主机名/IP（IPv6 不带方括号），可直接交给 OkHttp 的 `HttpUrl.Builder.host`
     * @param port 链接里显式写的端口；没写则为 null，由调用方决定默认值
     * @param token 链接里 `token=` 查询参数；没有则为 null
     */
    data class Parsed(
        val host: String,
        val port: Int?,
        val token: String?,
    )

    /**
     * 解析用户输入的地址。无法解析时返回 null——调用方据此给出行内错误，
     * 而不是把一段垃圾存进配置里等连接时再炸。
     */
    fun parse(raw: String): Parsed? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        // 去掉 scheme：http:// https:// ws:// wss://（大小写不敏感）。
        val schemeEnd = text.indexOf("://")
        if (schemeEnd > 0) text = text.substring(schemeEnd + 3)

        // 去掉 fragment。
        val hash = text.indexOf('#')
        if (hash >= 0) text = text.substring(0, hash)

        // 切出 query，顺带把 token 摘出来。
        var token: String? = null
        val question = text.indexOf('?')
        if (question >= 0) {
            token = extractToken(text.substring(question + 1))
            text = text.substring(0, question)
        }

        // 去掉路径：/、/ws、/ws/ 都不要。
        val slash = text.indexOf('/')
        if (slash >= 0) text = text.substring(0, slash)
        if (text.isEmpty()) return null

        val (host, portText) = splitHostPort(text)
        if (!isValidHost(host)) return null
        val bare = bareHost(host)
        val port = when {
            portText == null -> null
            else -> {
                val value = portText.toIntOrNull() ?: return null
                if (value !in 1..65535) return null
                value
            }
        }
        return Parsed(
            host = bare,
            port = port,
            token = token?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 从 authority 里拆出主机与端口。
     *
     * `[::1]:8800` → `("[::1]", "8800")`；`::1` → `("::1", null)`；
     * `192.168.1.10:8800` → `("192.168.1.10", "8800")`。
     */
    private fun splitHostPort(authority: String): Pair<String, String?> {
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return authority to null
            val host = authority.substring(0, close + 1)
            val rest = authority.substring(close + 1)
            if (!rest.startsWith(":")) return host to null
            return host to rest.substring(1)
        }
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        // 多个冒号 = 裸 IPv6 字面量，后面跟的是地址本身而不是端口。
        if (authority.indexOf(':') != colon) return authority to null
        return authority.substring(0, colon) to authority.substring(colon + 1)
    }

    /**
     * 从 query 串里取 `token=`。pi-remote 只认这一个参数名。
     *
     * 手写解析而不是用 `URLDecoder`：URLDecoder 会把 `+` 当空格，而 query 里
     * `+` 是合法字符，十六进制令牌虽然不含 `+`，但不该在这里做这个假设。
     */
    private fun extractToken(query: String): String? {
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = pair.substringBefore('=', pair)
            if (name != "token") continue
            val value = if (pair.contains('=')) pair.substringAfter('=') else ""
            return percentDecode(value)
        }
        return null
    }

    /** 最小化的百分号解码：只处理 %XX，其余原样保留。 */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    out.append(decoded.toChar())
                    index += 3
                    continue
                }
            }
            out.append(char)
            index += 1
        }
        return out.toString()
    }

    /**
     * 主机名合法性：非空、无空白、无 scheme 残留、无路径残留。
     *
     * 这里不追求 RFC 1123 全套校验——只要能挡住「把整条 URL 当主机名」这种
     * 明显的误填，剩下的交给 OkHttp 的 `HttpUrl.Builder.host` 兜底。
     */
    private fun isValidHost(host: String): Boolean {
        if (host.isEmpty()) return false
        if (host.any { it.isWhitespace() }) return false
        if (host.contains('/') || host.contains('?') || host.contains('#')) return false
        if (host.contains("://")) return false
        // IPv6 字面量（带或不带方括号）单独放行，其余按主机名/IP 校验。
        if (host.startsWith("[") && host.endsWith("]")) {
            val inner = host.substring(1, host.length - 1)
            return inner.isNotEmpty() && inner.all { it.isDigit() || it in "abcdefABCDEF:." }
        }
        if (host.contains(':')) return host.all { it.isDigit() || it in "abcdefABCDEF:." }
        if (host.length > 253) return false
        return host.all { it.isLetterOrDigit() || it in ".-_" }
    }

    /**
     * 去掉 IPv6 的方括号——OkHttp 的 `HttpUrl.Builder.host` 要的是裸字面量，
     * 它自己会补方括号。存进配置的 host 一律是裸形式。
     */
    fun bareHost(host: String): String =
        if (host.length > 1 && host.startsWith("[") && host.endsWith("]")) {
            host.substring(1, host.length - 1)
        } else {
            host
        }
}
