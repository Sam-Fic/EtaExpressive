package io.github.mangi.eta.agent.remote

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败性质的判定。
 *
 * 这里钉住的是一条**踩过的坑**：`SocketTimeoutException` 同时表示
 * 「TCP 根本没连上」和「连上了但服务器没回数据」，异常类型一模一样，
 * 只能靠 message 区分。以前一视同仁地报成「连接超时：确认手机与服务器连的是
 * 同一个局域网」——用户明明同网段、浏览器也能打开，却一直被要求去检查局域网，
 * 最后只能认为「这程序写错了」。
 *
 * 判定点全部用 OkHttp 5.4 / ART 的**真实**措辞构造，避免"看起来对"的用例通过、
 * 真机上却对不上。
 */
class RemoteFailureTest {

    @Test
    fun `okhttp connect timeout is a connect timeout`() {
        // OkHttp 5.4 在 JVM 上的原话。
        val failure = SocketTimeoutException("Connect timed out")
        assertEquals(RemoteFailure.Kind.ConnectTimeout, RemoteFailure.kindOf(failure))
    }

    @Test
    fun `art connect timeout is a connect timeout`() {
        // ART 的原话是全小写。
        val failure = SocketTimeoutException("connect timed out")
        assertEquals(RemoteFailure.Kind.ConnectTimeout, RemoteFailure.kindOf(failure))
    }

    @Test
    fun `read timeout is not a connect timeout`() {
        val failure = SocketTimeoutException("Read timed out")
        assertEquals(RemoteFailure.Kind.ReadTimeout, RemoteFailure.kindOf(failure))
    }

    @Test
    fun `bare timeout on art is a read timeout`() {
        // ART 的读超时有时就是裸的 "timeout"——不能因为不含 "connect" 就当成别的。
        val failure = SocketTimeoutException("timeout")
        assertEquals(RemoteFailure.Kind.ReadTimeout, RemoteFailure.kindOf(failure))
    }

    @Test
    fun `refused connection is unwrapped from the okhttp wrapper`() {
        // OkHttp 会把底层异常包一层：外层是 "Failed to connect to …"，
        // 真正的原因 "Connection refused" 在 cause 里——只看最外层会判错。
        val wrapped = ConnectException("Failed to connect to /192.168.31.107:8800").apply {
            initCause(ConnectException("Connection refused: getsockopt"))
        }
        assertEquals(RemoteFailure.Kind.Refused, RemoteFailure.kindOf(wrapped))
    }

    @Test
    fun `unreachable network is not a refusal`() {
        val failure = ConnectException("Network is unreachable")
        assertEquals(RemoteFailure.Kind.Unreachable, RemoteFailure.kindOf(failure))
    }

    @Test
    fun `unknown host yields the host name as detail`() {
        val failure = UnknownHostException("Unable to resolve host \"pi.example.com\"")
        assertEquals(RemoteFailure.Kind.UnknownHost, RemoteFailure.kindOf(failure))
        assertEquals("pi.example.com", RemoteFailure.detailOf(failure))
    }

    @Test
    fun `cleartext policy is detected`() {
        val failure = IOException(
            "CLEARTEXT communication to 192.168.31.107 not permitted by network security policy",
        )
        assertEquals(RemoteFailure.Kind.CleartextBlocked, RemoteFailure.kindOf(failure))
        assertTrue(RemoteFailure.isTerminal(RemoteFailure.Kind.CleartextBlocked))
    }

    @Test
    fun `permission refusal is detected`() {
        val failure = IOException("Failed to connect: EPERM (Operation not permitted)")
        assertEquals(RemoteFailure.Kind.PermissionDenied, RemoteFailure.kindOf(failure))
        assertTrue(RemoteFailure.isTerminal(RemoteFailure.Kind.PermissionDenied))
    }

    @Test
    fun `art bare socket connect timeout is a connect timeout`() {
        // 真机上踩到的原话：既没有 "connect timed out" 也没有 "timeout" 字样。
        // 只按 message 猜会落到 ReadTimeout，于是「包没到达服务器」被报成
        // 「服务器已接受连接但不回应」——把用户引向完全错误的方向。
        val failure = SocketTimeoutException(
            "failed to connect to /192.168.31.107 (port 8800) after 5000ms",
        )
        assertEquals(
            RemoteFailure.Kind.ConnectTimeout,
            RemoteFailure.kindOf(failure, RemoteFailure.Phase.Connect),
        )
        // 不传阶段也要能靠措辞认出来。
        assertEquals(RemoteFailure.Kind.ConnectTimeout, RemoteFailure.kindOf(failure))
    }

    @Test
    fun `phase wins over an ambiguous message`() {
        // 裸 "timeout" 的措辞看不出阶段，此时以调用方已知的阶段为准：
        // 裸 TCP 那一步除了 connect 什么都没做，超时必定是连接阶段的。
        assertEquals(
            RemoteFailure.Kind.ConnectTimeout,
            RemoteFailure.kindOf(SocketTimeoutException("timeout"), RemoteFailure.Phase.Connect),
        )
        assertEquals(
            RemoteFailure.Kind.ReadTimeout,
            RemoteFailure.kindOf(SocketTimeoutException("timeout"), RemoteFailure.Phase.Read),
        )
    }

    @Test
    fun `recoverable failures keep retrying`() {
        // 超时与拒绝都可能是服务器暂时不在：该继续退避重连，不能直接判死。
        assertFalse(RemoteFailure.isTerminal(RemoteFailure.Kind.ConnectTimeout))
        assertFalse(RemoteFailure.isTerminal(RemoteFailure.Kind.ReadTimeout))
        assertFalse(RemoteFailure.isTerminal(RemoteFailure.Kind.Refused))
        assertFalse(RemoteFailure.isTerminal(RemoteFailure.Kind.Unreachable))
    }

    @Test
    fun `configuration failures are terminal`() {
        // 地址写错、令牌错、路径不存在——重试一万次也没用，必须停下来报错。
        assertTrue(RemoteFailure.isTerminal(RemoteFailure.Kind.UnknownHost))
        assertTrue(RemoteFailure.isTerminal(RemoteFailure.Kind.MalformedAddress))
        assertTrue(RemoteFailure.isTerminal(RemoteFailure.Kind.Unauthorized))
        assertTrue(RemoteFailure.isTerminal(RemoteFailure.Kind.NotFound))
        assertTrue(RemoteFailure.isTerminal(RemoteFailure.Kind.UnsupportedProtocol))
    }

    @Test
    fun `supervisor classification carries the kind through`() {
        val failure = RemoteConnectionSupervisor.classify(SocketTimeoutException("Connect timed out"))
        assertEquals(RemoteFailure.Kind.ConnectTimeout, failure.kind)
        assertFalse(failure.terminal)
    }

    @Test
    fun `cause chain is walked to the root`() {
        val failure = RemoteConnectionSupervisor.classify(
            RuntimeException("wrapper", UnknownHostException("Unable to resolve host \"pi.example.com\"")),
        )
        assertEquals(RemoteFailure.Kind.UnknownHost, failure.kind)
        assertTrue(failure.terminal)
    }
}
