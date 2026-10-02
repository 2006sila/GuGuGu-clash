package io.vpnshare

import io.vpnshare.profile.CustomRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomRuleTest {

    @Test
    fun shorthandBecomesDomainSuffixDirect() {
        assertEquals("DOMAIN-SUFFIX,example.com,DIRECT", CustomRule.parse("example.com").line)
        assertEquals("DOMAIN-SUFFIX,example.com,DIRECT", CustomRule.parse("+.example.com").line)
        assertEquals("DOMAIN-SUFFIX,example.com,DIRECT", CustomRule.parse("  .example.com  ").line)
    }

    @Test
    fun fullRuleKeptAsIs() {
        assertEquals("DOMAIN-SUFFIX,example.com,PROXY",
            CustomRule.parse("DOMAIN-SUFFIX,example.com,PROXY").line)
        assertEquals("DOMAIN-KEYWORD,github,PROXY",
            CustomRule.parse("domain-keyword,github,proxy").line)   // 大小写归一
        assertEquals("IP-CIDR,1.2.3.0/24,DIRECT,no-resolve",
            CustomRule.parse("IP-CIDR,1.2.3.0/24,DIRECT,no-resolve").line)
    }

    @Test
    fun actionDefaultsToDirect() {
        assertEquals("GEOSITE,netflix,DIRECT", CustomRule.parse("GEOSITE,netflix").line)
    }

    @Test
    fun badTypeIsRejectedWithReason() {
        val r = CustomRule.parse("DOMAIN-FOO,example.com,DIRECT")
        assertNull(r.line)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("未知类型"))
    }

    @Test
    fun badActionIsRejected() {
        val r = CustomRule.parse("DOMAIN-SUFFIX,example.com,BLOCK")
        assertNull(r.line)
        assertTrue(r.error!!.contains("动作"))
    }

    @Test
    fun badFlagIsRejected() {
        val r = CustomRule.parse("IP-CIDR,1.2.3.0/24,DIRECT,wat")
        assertNull(r.line)
        assertTrue(r.error!!.contains("附加参数"))
    }

    @Test
    fun blankAndCommentLinesAreSkippedSilently() {
        assertNull(CustomRule.parse("").line)
        assertNull(CustomRule.parse("   ").error)
        assertNull(CustomRule.parse("# 这是注释").line)
        assertNull(CustomRule.parse("# 这是注释").error)
    }

    @Test
    fun parseAllReportsLineNumbers() {
        val res = CustomRule.parseAll(
            "example.com\n" +
            "DOMAIN-FOO,bar.com,DIRECT\n" +
            "# 注释\n" +
            "DOMAIN-SUFFIX,ok.com,PROXY"
        )
        assertEquals(2, res.lines.size)
        assertEquals(1, res.errors.size)
        assertTrue("应报第 2 行", res.errors[0].startsWith("第 2 行"))
    }

    @Test
    fun extraDomainsRoundTrip() {
        val map = CustomRule.parseExtraDomains("bilibili=Example.com;+.foo.com\nads=track.bar")
        assertEquals(listOf("example.com", "foo.com"), map["bilibili"])
        assertEquals(listOf("track.bar"), map["ads"])
        val text = CustomRule.extraDomainsText(map)
        assertTrue(text.contains("bilibili=example.com;foo.com"))
        assertTrue(text.contains("ads=track.bar"))
    }

    @Test
    fun extraDomainsIgnoresGarbage() {
        val map = CustomRule.parseExtraDomains("没有等号\n=空key\ncn=\nok=good.com")
        assertEquals(1, map.size)
        assertEquals(listOf("good.com"), map["ok"])
    }
}
