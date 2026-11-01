package io.github.mangi.eta.agent.remote

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远程服务器地址解析。
 *
 * 用户手里通常是 pi-remote 给的完整 WebUI 链接（带 `?token=`）。这份用例把
 * 各种粘贴姿势都钉住：解析出的主机名必须是裸主机名（不含 scheme/路径），
 * 端口与令牌要能自动拆出来——拆错了就会拼出 `ws://http://…` 这种废地址，
 * 连接必然失败，而失败原因还会被上层吞掉。
 */
class RemoteServerAddressTest {

    @Test
    fun `bare ip yields host without port`() {
        val parsed = RemoteServerAddress.parse("192.168.31.107")!!
        assertEquals("192.168.31.107", parsed.host)
        assertNull(parsed.port)
        assertNull(parsed.token)
    }

    @Test
    fun `host and port are split`() {
        val parsed = RemoteServerAddress.parse("192.168.31.107:8800")!!
        assertEquals("192.168.31.107", parsed.host)
        assertEquals(8800, parsed.port)
    }

    @Test
    fun `http webui link yields host port and token`() {
        val parsed = RemoteServerAddress.parse(
            "http://192.168.31.107:8800?token=4f94995bca6d23da7749cf8c0d52f0da",
        )!!
        assertEquals("192.168.31.107", parsed.host)
        assertEquals(8800, parsed.port)
        assertEquals("4f94995bca6d23da7749cf8c0d52f0da", parsed.token)
    }

    @Test
    fun `ws link with path is accepted`() {
        val parsed = RemoteServerAddress.parse(
            "ws://192.168.31.107:8800/ws?token=4f94995bca6d23da7749cf8c0d52f0da",
        )!!
        assertEquals("192.168.31.107", parsed.host)
        assertEquals(8800, parsed.port)
        assertEquals("4f94995bca6d23da7749cf8c0d52f0da", parsed.token)
    }

    @Test
    fun `https scheme and trailing slash are tolerated`() {
        val parsed = RemoteServerAddress.parse("https://pi.example.com/")!!
        assertEquals("pi.example.com", parsed.host)
        assertNull(parsed.port)
    }

    @Test
    fun `token survives percent encoding`() {
        val parsed = RemoteServerAddress.parse("http://192.168.31.107:8800?token=a%2Bb")!!
        assertEquals("a+b", parsed.token)
    }

    @Test
    fun `other query parameters do not become the token`() {
        val parsed = RemoteServerAddress.parse("http://192.168.31.107:8800?foo=bar")!!
        assertNull(parsed.token)
    }

    @Test
    fun `ipv6 literal keeps its address and loses the brackets`() {
        val parsed = RemoteServerAddress.parse("http://[fe80::1]:8800")!!
        assertEquals("fe80::1", parsed.host)
        assertEquals(8800, parsed.port)
    }

    @Test
    fun `blank and malformed input are rejected`() {
        assertNull(RemoteServerAddress.parse(""))
        assertNull(RemoteServerAddress.parse("   "))
        assertNull(RemoteServerAddress.parse("http://"))
        assertNull(RemoteServerAddress.parse("192.168.31.107:0"))
        assertNull(RemoteServerAddress.parse("192.168.31.107:70000"))
        assertNull(RemoteServerAddress.parse("192.168.31.107:abc"))
        assertNull(RemoteServerAddress.parse("not a host"))
    }

    @Test
    fun `bare host strips ipv6 brackets`() {
        assertEquals("::1", RemoteServerAddress.bareHost("[::1]"))
        assertEquals("192.168.31.107", RemoteServerAddress.bareHost("192.168.31.107"))
    }
}

/**
 * 连接失败的归类。
 *
 * 分类的价值在于「该不该继续重试」：地址写错、系统拒绝局域网访问，重试一万次
 * 也没用，必须停下来把原因告诉用户；超时、连接被拒则可能只是服务器没起来，
 * 该继续退避重连。
 */
class RemoteConnectionFailureClassificationTest {

    @Test
    fun `unresolvable host is terminal`() {
        val failure = RemoteConnectionSupervisor.classify(UnknownHostException("http"))
        assertTrue(failure.terminal)
    }

    @Test
    fun `timeout keeps retrying`() {
        val failure = RemoteConnectionSupervisor.classify(SocketTimeoutException("connect timed out"))
        assertFalse(failure.terminal)
    }

    @Test
    fun `refused connection keeps retrying`() {
        val failure = RemoteConnectionSupervisor.classify(ConnectException("Connection refused"))
        assertFalse(failure.terminal)
    }

    @Test
    fun `permission refusal is terminal`() {
        val failure = RemoteConnectionSupervisor.classify(
            RuntimeException("Failed to connect: EPERM (Operation not permitted)"),
        )
        assertTrue(failure.terminal)
    }

    @Test
    fun `nested cause is inspected`() {
        val failure = RemoteConnectionSupervisor.classify(
            RuntimeException("wrapper", UnknownHostException("http")),
        )
        assertTrue(failure.terminal)
    }

    @Test
    fun `unknown failure keeps retrying`() {
        val failure = RemoteConnectionSupervisor.classify(RuntimeException("boom"))
        assertFalse(failure.terminal)
    }
}
