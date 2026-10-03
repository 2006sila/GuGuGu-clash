package io.guguguclash

import io.guguguclash.tether.ClientMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 邻居表解析回归测试。
 *
 * 三种输出形态都要覆盖，尤其是第二种 —— ip neigh show dev X 的输出里
 * **不打印 dev 字段**。早期解析器要求 dev 与 lladdr 同时出现，导致指定接口时
 * 每一行都被丢掉，「已连设备」页永远显示「暂无设备」。
 */
class ClientMonitorTest {

    private val devForm = "10.61.80.127 dev wlan2 lladdr d4:ab:61:7e:be:63 REACHABLE"

    /** 本机真机实测输出：ip neigh show dev wlan2 */
    private val devLessForm = "10.61.80.127 lladdr d4:ab:61:7e:be:63 REACHABLE"

    /** /proc/net/arp 形态 */
    private val arpForm = "10.61.80.127     0x1         0x2         d4:ab:61:7e:be:63     *        wlan2"

    @Test
    fun parsesDevLessFormWithIfaceFallback() {
        val c = ClientMonitor.parse(devLessForm, "wlan2")
        assertEquals(1, c.size)
        assertEquals("10.61.80.127", c[0].ip)
        assertEquals("d4:ab:61:7e:be:63", c[0].mac)
        assertEquals("wlan2", c[0].iface)
        assertEquals("REACHABLE", c[0].state)
    }

    @Test
    fun parsesDevForm() {
        val c = ClientMonitor.parse(devForm)
        assertEquals(1, c.size)
        assertEquals("10.61.80.127", c[0].ip)
        assertEquals("wlan2", c[0].iface)
        assertEquals("REACHABLE", c[0].state)
    }

    @Test
    fun parsesArpForm() {
        val c = ClientMonitor.parse(arpForm)
        assertEquals(1, c.size)
        assertEquals("d4:ab:61:7e:be:63", c[0].mac)
        assertEquals("wlan2", c[0].iface)
        assertEquals("ARP", c[0].state)
    }

    @Test
    fun skipsIpv6Lines() {
        // 真机上同一个客户端会同时出现 IPv4 与两条 IPv6 邻居，IPv6 必须跳过而不是误解析
        val raw = listOf(
            "10.61.80.127 lladdr d4:ab:61:7e:be:63 REACHABLE",
            "fe80::d3c8:9f9a:4b3:60ae lladdr d4:ab:61:7e:be:63 STALE",
            "2409:895a:3869:4cef:21be:ba6d:eb65:ea2e lladdr d4:ab:61:7e:be:63 REACHABLE"
        ).joinToString("\n")
        val c = ClientMonitor.parse(raw, "wlan2")
        assertEquals("只应解析出 IPv4 那一台", 1, c.size)
        assertEquals("10.61.80.127", c[0].ip)
    }

    @Test
    fun deduplicatesSameIp() {
        val raw = devForm + "\n" + devLessForm
        assertEquals(1, ClientMonitor.parse(raw, "wlan2").size)
    }

    @Test
    fun ignoresGarbageLines() {
        val raw = listOf("", "   ", "not-an-ip lladdr aa:bb:cc:dd:ee:ff STALE", "ip").joinToString("\n")
        assertTrue(ClientMonitor.parse(raw, "wlan2").isEmpty())
    }

    // ---------------- 本机地址过滤（替代早期的「.1 后缀」猜测） ----------------

    @Test
    fun parsesLocalAddressesFromIpAddrOutput() {
        // 真机输出：ip -o -4 addr show（每行一条，注意 wlan2 那行末尾设备会带一个反斜杠）
        val raw = listOf(
            "119: wlan2    inet 10.61.80.170/24 brd 10.61.80.255 scope global wlan2\\       valid_lft forever preferred_lft forever",
            "1: lo    inet 127.0.0.1/8 scope host lo",
            "24: rmnet_data0    inet 10.0.0.5/24 scope global rmnet_data0"
        ).joinToString("\n")
        val set = ClientMonitor.parseLocalAddresses(raw)
        assertEquals(setOf("10.61.80.170", "127.0.0.1", "10.0.0.5"), set)
    }

    @Test
    fun filterDropsLocalAddressesAndZeroMac() {
        // 关键回归：早期用「IP 以 .1 结尾即网关」来排除，在网关不是 .1 的机型上失效，
        // 反过来还会把恰好分到 x.x.x.1 的真实客户端隐藏掉。现在按内核给的本机地址排除。
        val clients = listOf(
            ClientMonitor.Client("10.61.80.170", "aa:bb:cc:dd:ee:ff", "wlan2", "REACHABLE"), // 本机自己
            ClientMonitor.Client("10.61.80.1", "aa:bb:cc:dd:ee:01", "wlan2", "REACHABLE"),  // 不该再被当网关
            ClientMonitor.Client("10.61.80.127", "d4:ab:61:7e:be:63", "wlan2", "REACHABLE"),
            ClientMonitor.Client("10.61.80.9", "00:00:00:00:00:00", "wlan2", "FAILED")     // 全零 MAC
        )
        val kept = ClientMonitor.filterClients(clients, setOf("10.61.80.170"))
        assertEquals(2, kept.size)
        assertEquals(listOf("10.61.80.1", "10.61.80.127"), kept.map { it.ip })
    }
}
