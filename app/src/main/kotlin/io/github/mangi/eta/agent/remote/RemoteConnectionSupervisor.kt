package io.github.mangi.eta.agent.remote

import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Generic supervised WebSocket transport for a remote agent connection.
 *
 * Owns only the socket lifecycle: connect, keep-alive ping, exponential-backoff
 * reconnect (1s → 30s capped), and status transitions. It knows nothing about
 * frame semantics — raw text frames go to [onFrame] for the protocol adapter to
 * decode, and [send] hands bytes straight to the socket.
 *
 * Reconnection is lossless by protocol contract: on every (re)open the server
 * sends a full snapshot, so the adapter rebuilds state from scratch rather than
 * replaying or deduping deltas. A 401 at the upgrade is treated as terminal —
 * the token is wrong and retrying cannot fix it — so it surfaces [RemoteConnectionStatus.Failed]
 * and stops. The same terminal treatment applies to failures that cannot heal
 * on their own (unresolvable host, the OS refusing LAN access); recoverable ones
 * (timeouts, refused connections) keep retrying but carry the *kind* of failure
 * so the UI can say what is actually wrong — see [RemoteFailure]. Recoverable
 * failures give up after [MAX_RECONNECT_ATTEMPTS] attempts and surface Failed
 * too: reviving is the user's call (manual retry, or picking a session, both
 * re-open the whole connection with a fresh attempt counter).
 *
 * All callbacks are marshalled onto [scope] (Main.immediate) so the UI bridge
 * folds events on the main thread without its own synchronization.
 */
internal class RemoteConnectionSupervisor(
    private val scope: CoroutineScope,
    private val connectUrl: () -> String?,
    private val onFrame: (String) -> Unit,
    private val onStatus: (RemoteConnectionStatus) -> Unit,
) {
    private val client by lazy {
        // 必须用 [RemoteHttpClient]：局域网地址不该经系统 HTTP 代理转发
        // （浏览器有「局域网不走代理」的例外规则，OkHttp 没有），
        // 也不该用云端模型那套 20 秒连接超时。
        RemoteHttpClient.client.newBuilder()
            // WebSocket 是长连接，读超时给足余量：ping 每 20 秒一次，
            // 服务端要在读超时内回 pong，否则连接会被误判为断开。
            .readTimeout(WS_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    @Volatile private var attempt = 0

    /** 最近一次可重连失败的性质；重连期间一直挂在状态上，让用户知道卡在哪。 */
    @Volatile private var lastKind: RemoteFailure.Kind? = null
    @Volatile private var lastDetail: String? = null
    private var reconnectJob: Job? = null

    /** Begin connecting. Idempotent-ish: a fresh start resets the backoff. */
    fun start() {
        stopped = false
        attempt = 0
        connect()
    }

    /** Send a raw text frame. Returns false when not currently connected. */
    fun send(text: String): Boolean = socket?.send(text) ?: false

    /** Intentional teardown: no reconnect is scheduled afterwards. */
    fun stop() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        lastKind = null
        lastDetail = null
        val current = socket
        socket = null
        runCatching { current?.close(NORMAL_CLOSE, "client closed") }
        postStatus(RemoteConnectionStatus.Disconnected)
    }

    private fun connect() {
        if (stopped) return
        val url = runCatching { connectUrl() }.getOrNull()
        if (url.isNullOrBlank()) {
            postStatus(RemoteConnectionStatus.Failed(RemoteFailure.Kind.MalformedAddress))
            return
        }
        // 主机名非法会让 OkHttp 直接抛 IllegalArgumentException。这个异常必须在这里
        // 吃掉：它一旦冒泡到协调器的 DataStore 收集协程，整条远程链路就此哑掉，
        // 界面上只剩一行永远不更新的「连接中」。
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull()
        if (request == null) {
            AndroidAgentLogger.warn("Remote WS address malformed: $url")
            postStatus(RemoteConnectionStatus.Failed(RemoteFailure.Kind.MalformedAddress, url))
            return
        }
        postStatus(
            if (attempt == 0) RemoteConnectionStatus.Connecting
            else RemoteConnectionStatus.Reconnecting(attempt, lastKind, lastDetail),
        )
        socket = client.newWebSocket(request, listener)
    }

    private fun scheduleReconnect(
        kind: RemoteFailure.Kind? = null,
        detail: String? = null,
    ) {
        if (stopped) return
        attempt += 1
        val currentAttempt = attempt
        if (kind != null) lastKind = kind
        if (detail != null) lastDetail = detail
        if (currentAttempt > MAX_RECONNECT_ATTEMPTS) {
            // 重试上限：自动重连就此收手，判成「断开」（Failed 挂着最后一次失败的性质）。
            // 再往下只是无限转圈；复活交给用户——状态胶囊上的重试钮、或点一个会话行，
            // 都会整颗拆掉本监管器重开连接（attempt 随之归零）。
            reconnectJob?.cancel()
            reconnectJob = null
            AndroidAgentLogger.warn("Remote WS reconnect gave up after $currentAttempt attempts: $lastKind ($lastDetail)")
            postStatus(RemoteConnectionStatus.Failed(lastKind ?: RemoteFailure.Kind.Unknown, lastDetail))
            return
        }
        postStatus(RemoteConnectionStatus.Reconnecting(currentAttempt, lastKind, lastDetail))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoffMs(currentAttempt))
            connect()
        }
    }

    private fun postStatus(status: RemoteConnectionStatus) {
        scope.launch(Dispatchers.Main.immediate) { onStatus(status) }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            socket = webSocket
            lastKind = null
            lastDetail = null
            postStatus(RemoteConnectionStatus.Connected)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch(Dispatchers.Main.immediate) { onFrame(text) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            scope.launch(Dispatchers.Main.immediate) { onFrame(bytes.utf8()) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(code, reason) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!stopped) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (stopped) return
            socket = null
            // 服务端已经回了状态码的失败，先按状态码归类——它比异常消息准得多。
            val code = response?.code
            if (code != null) {
                val kind = when (code) {
                    HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> RemoteFailure.Kind.Unauthorized
                    HTTP_NOT_FOUND -> RemoteFailure.Kind.NotFound
                    else -> null
                }
                if (kind != null) {
                    AndroidAgentLogger.warn("Remote WS rejected: HTTP $code")
                    postStatus(RemoteConnectionStatus.Failed(kind, code.toString()))
                    return
                }
            }
            val failure = classify(t, runCatching { connectUrl() }.getOrNull())
            val kind = failure.kind
            val detail = failure.detail
            AndroidAgentLogger.warn("Remote WS failure: ${t.javaClass.simpleName} -> $kind ($detail)")
            // TCP 都没连上时，下一次尝试把 socket 绑到 Wi-Fi 源地址上：
            // 部分 ROM 的「智能网络切换」会把应用流量搬到移动网络，
            // 此时访问 192.168.x.x 必然超时，而浏览器可能仍在 Wi-Fi 上。
            if (kind == RemoteFailure.Kind.ConnectTimeout) {
                RemoteHttpClient.preferWifiOnNextAttempt()
            }
            if (failure.terminal) {
                // 重试救不回来的错误直接终态：一直「重连中」只会让用户以为在慢慢好起来。
                postStatus(RemoteConnectionStatus.Failed(kind, detail))
                return
            }
            scheduleReconnect(kind, detail)
        }
    }

    /**
     * 一次连接失败的归类。
     *
     * @param kind 失败性质
     * @param detail 附带信息（主机名、原始异常消息等）
     * @param terminal true 表示重试也不会变好（地址写错、令牌错、系统策略拦截），应当停下并报错
     */
    internal data class Failure(
        val kind: RemoteFailure.Kind,
        val detail: String?,
        val terminal: Boolean,
    )

    companion object {
        private const val PING_INTERVAL_SECONDS = 20L
        private const val WS_READ_TIMEOUT_SECONDS = 120L
        private const val NORMAL_CLOSE = 1000
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val MIN_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** 自动重连的尝试上限；到顶判死（Failed），复活只能靠用户手动重试或切换会话。 */
        private const val MAX_RECONNECT_ATTEMPTS = 5

        /** Exponential backoff capped at [MAX_BACKOFF_MS]; attempt is 1-based. */
        internal fun backoffMs(attempt: Int): Long {
            val shift = (attempt - 1).coerceIn(0, 5)
            return (MIN_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
        }

        /**
         * 把底层异常翻成失败性质。
         *
         * 分类只在 [RemoteFailure] 里做一次：它知道 `SocketTimeoutException`
         * 到底是「TCP 没连上」还是「连上了没等到数据」，而这两件事的处置完全相反。
         */
        internal fun classify(t: Throwable, url: String? = null): Failure {
            var kind = RemoteFailure.kindOf(t)
            // TCP 握手超时 + 目标是局域网 + 系统的本地网络闸门是关的 → 归到「被系统拦」。
            //
            // 这一条不能省：被拦时异常就是普通的 `SocketTimeoutException`，
            // 与「服务器不回应」在类型和措辞上完全一样，只能靠这两个外部事实分开。
            // 判错方向的代价很大——用户会去对端反复抓包、改防火墙，而问题在手机上。
            if (kind == RemoteFailure.Kind.ConnectTimeout) {
                val host = url?.let { runCatching { it.toHttpUrlOrNull()?.host }.getOrNull() }
                if (host != null &&
                    LocalNetworkPermission.isLanAddress(host) &&
                    LocalNetworkPermission.blocksConnection(RemoteHttpClient.localNetworkState())
                ) {
                    kind = RemoteFailure.Kind.LocalNetworkBlocked
                }
            }
            return Failure(
                kind = kind,
                detail = RemoteFailure.detailOf(t),
                terminal = RemoteFailure.isTerminal(kind),
            )
        }
    }
}
