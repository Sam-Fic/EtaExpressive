package io.github.mangi.eta.agent.remote

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ARP 表解析。
 *
 * 这一层是整份诊断里唯一不受「防火墙静默丢弃」影响的判据——
 * 防火墙丢的是 IP 包，ARP 在链路层，机器活着就一定会回。
 * 所以解析错了，结论就会把用户引到完全相反的方向。
 */
class RemoteConnectionProbeTest {

    private val header = "IP address       HW type     Flags       HW address            Mask     Device"

    @Test
    fun `a complete entry means the machine is on this lan`() {
        val lines = listOf(
            header,
            "192.168.31.1     0x1         0x2         a4:2b:8c:11:22:33     *        wlan0",
            "192.168.31.107   0x1         0x2         b8:27:eb:44:55:66     *        wlan0",
        )
        assertEquals(
            RemoteConnectionProbe.Arp.Complete,
            RemoteConnectionProbe.parseArpTable(lines, "192.168.31.107"),
        )
    }

    @Test
    fun `an unresolved entry means nobody answered the arp request`() {
        val lines = listOf(
            header,
            "192.168.31.107   0x1         0x0         00:00:00:00:00:00     *        wlan0",
        )
        assertEquals(
            RemoteConnectionProbe.Arp.Incomplete,
            RemoteConnectionProbe.parseArpTable(lines, "192.168.31.107"),
        )
    }

    @Test
    fun `an absent entry is different from an unresolved one`() {
        val lines = listOf(
            header,
            "192.168.31.1     0x1         0x2         a4:2b:8c:11:22:33     *        wlan0",
        )
        assertEquals(
            RemoteConnectionProbe.Arp.Missing,
            RemoteConnectionProbe.parseArpTable(lines, "192.168.31.107"),
        )
    }

    @Test
    fun `an empty table is missing, not unknown`() {
        assertEquals(
            RemoteConnectionProbe.Arp.Missing,
            RemoteConnectionProbe.parseArpTable(listOf(header), "192.168.31.107"),
        )
    }

    @Test
    fun `garbage flags do not become a positive claim`() {
        val lines = listOf(
            header,
            "192.168.31.107   0x1         zz          00:00:00:00:00:00     *        wlan0",
        )
        assertEquals(
            RemoteConnectionProbe.Arp.Unknown,
            RemoteConnectionProbe.parseArpTable(lines, "192.168.31.107"),
        )
    }

    @Test
    fun `a prefix match must not be mistaken for the host`() {
        // 192.168.31.107 与 192.168.31.10 只差一位，按行首做前缀匹配会张冠李戴。
        val lines = listOf(
            header,
            "192.168.31.10    0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0",
        )
        assertEquals(
            RemoteConnectionProbe.Arp.Missing,
            RemoteConnectionProbe.parseArpTable(lines, "192.168.31.107"),
        )
    }
}
