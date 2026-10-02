package io.vpnshare

import io.vpnshare.tether.TetherDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TetherDetectorTest {

    private val addrSample = """
        1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever preferred_lft forever
        2: rmnet_data0    inet 10.55.1.7/24 scope global rmnet_data0\       valid_lft forever preferred_lft forever
        3: wlan0    inet 192.168.31.66/24 brd 192.168.31.255 scope global wlan0\       valid_lft forever preferred_lft forever
        4: ap0    inet 192.168.43.1/24 brd 192.168.43.255 scope global ap0\       valid_lft forever preferred_lft forever
    """.trimIndent()

    @Test
    fun parsesAddresses() {
        val list = TetherDetector.parseAddrs(addrSample)
        assertEquals(4, list.size)
        assertEquals("ap0", list[3].name)
        assertEquals("192.168.43.1", list[3].ip)
        assertEquals(24, list[3].prefix)
    }

    @Test
    fun parsesDefaultDev() {
        val route = "default via 192.168.31.1 dev wlan0 proto dhcp src 192.168.31.66 metric 303"
        assertEquals("wlan0", TetherDetector.parseDefaultDev(route))
        assertNull(TetherDetector.parseDefaultDev("192.168.31.0/24 dev wlan0 scope link"))
    }

    @Test
    fun picksHotspotGatewayOverClientWifiAndMobile() {
        val list = TetherDetector.parseAddrs(addrSample)
        assertEquals("ap0", TetherDetector.detect(list, "rmnet_data0"))
        // wlan0 是默认出口时也不应被选中
        assertEquals("ap0", TetherDetector.detect(list, "wlan0"))
    }

    @Test
    fun handlesUsbRndisNonDotOneAddress() {
        val usb = """
            1: lo    inet 127.0.0.1/8 scope host lo
            5: rndis0    inet 192.168.42.129/24 brd 192.168.42.255 scope global rndis0
        """.trimIndent()
        val list = TetherDetector.parseAddrs(usb)
        assertEquals("rndis0", TetherDetector.detect(list, "rmnet_data0"))
    }

    @Test
    fun overrideWins() {
        val list = TetherDetector.parseAddrs(addrSample)
        assertEquals("wlan2", TetherDetector.detect(list, "rmnet_data0", "wlan2"))
    }

    @Test
    fun returnsNullWhenNothingLooksLikeTether() {
        val onlyMobile = """
            1: lo    inet 127.0.0.1/8 scope host lo
            2: rmnet_data0    inet 10.55.1.7/24 scope global rmnet_data0
        """.trimIndent()
        assertNull(TetherDetector.detect(TetherDetector.parseAddrs(onlyMobile), "rmnet_data0"))
    }

    @Test
    fun privateRangeClassification() {
        assertTrue(TetherDetector.isPrivate("192.168.43.1"))
        assertTrue(TetherDetector.isPrivate("10.0.0.1"))
        assertTrue(TetherDetector.isPrivate("172.16.5.5"))
        assertTrue(TetherDetector.isPrivate("172.31.255.254"))
        assertTrue(!TetherDetector.isPrivate("172.32.0.1"))
        assertTrue(!TetherDetector.isPrivate("8.8.8.8"))
    }
}
