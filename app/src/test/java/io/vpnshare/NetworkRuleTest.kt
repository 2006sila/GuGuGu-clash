package io.vpnshare

import io.vpnshare.service.NetworkRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 按网络自动切换的规则解析与匹配。 */
class NetworkRuleTest {

    @Test
    fun parsesFullAndMinimalRules() {
        val r = NetworkRule.parse("""
            # 注释行忽略
            wifi:192.168.1.1 => pABC | ♻️ 手动切换
            mobile=>pXYZ
            随便一行没有箭头
        """.trimIndent())
        assertEquals(2, r.size)
        assertEquals("wifi:192.168.1.1", r[0].identity)
        assertEquals("pABC", r[0].profileId)
        assertEquals("♻️ 手动切换", r[0].group)
        assertEquals("mobile", r[1].identity)
        assertEquals("", r[1].group)
    }

    @Test
    fun identityIsCaseInsensitiveOnParse() {
        val r = NetworkRule.parse("WIFI:192.168.1.1 => pA")
        assertEquals("wifi:192.168.1.1", r[0].identity)
    }

    @Test
    fun specificGatewayBeatsGenericWifi() {
        val r = NetworkRule.parse("""
            wifi => pGeneric
            wifi:10.0.0.1 => pHome
        """.trimIndent())
        assertEquals("pHome", NetworkRule.match(r, "wifi:10.0.0.1")?.profileId)
        assertEquals("pGeneric", NetworkRule.match(r, "wifi:192.168.5.1")?.profileId)
    }

    @Test
    fun mobileMatchesOnlyMobile() {
        val r = NetworkRule.parse("mobile => pMobile")
        assertEquals("pMobile", NetworkRule.match(r, "mobile")?.profileId)
        assertNull(NetworkRule.match(r, "wifi:1.1.1.1"))
    }

    @Test
    fun noMatchReturnsNull() {
        val r = NetworkRule.parse("wifi:1.1.1.1 => pA")
        assertNull(NetworkRule.match(r, "wifi:2.2.2.2"))
        assertNull(NetworkRule.match(r, null))
        assertNull(NetworkRule.match(r, ""))
    }

    @Test
    fun skipsMalformedLines() {
        val r = NetworkRule.parse("""
            => pNoIdentity
            wifi:1.1.1.1 =>
            wifi:1.1.1.1 => |group
        """.trimIndent())
        assertEquals(0, r.size)
    }

    @Test
    fun buildsIdentity() {
        assertEquals("wifi:192.168.1.1", NetworkRule.identityOf(true, false, "192.168.1.1"))
        assertEquals("wifi", NetworkRule.identityOf(true, false, null))
        assertEquals("mobile", NetworkRule.identityOf(false, true, null))
        assertNull(NetworkRule.identityOf(false, false, null))
    }
}
