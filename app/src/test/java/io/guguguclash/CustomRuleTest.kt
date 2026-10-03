package io.guguguclash

import io.guguguclash.profile.CustomRule
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
    fun extraDomainsRoundTripUsesCatalogKeysOnly() {
        // 规则页「长按分类 → 追加域名」靠这两个函数配对：读 parseExtraDomains / 存 extraDomainsText
        val map = CustomRule.parseExtraDomains("bilibili=www.biliintl.com;biliintl.com\nnot-in-catalog=x.com")
        assertEquals(listOf("www.biliintl.com", "biliintl.com"), map["bilibili"])
        val again = CustomRule.parseExtraDomains(CustomRule.extraDomainsText(map))
        assertEquals(listOf("www.biliintl.com", "biliintl.com"), again["bilibili"])
        assertTrue("不在内置分类里的 key 不该写回文本", again["not-in-catalog"] == null)
    }

    @Test
    fun mergeDirectDomainsAppendsAsDirectRules() {
        // 「直连域名」并入自定义规则：产物必须与手写 DOMAIN-SUFFIX,x,DIRECT 完全一致
        val merged = CustomRule.mergeDirectDomains("DOMAIN-KEYWORD,github,PROXY", listOf("example.com", "+.foo.cn"))
        val lines = merged.split("\n")
        assertEquals(3, lines.size)
        assertEquals("DOMAIN-KEYWORD,github,PROXY", lines[0])
        assertEquals("DOMAIN-SUFFIX,example.com,DIRECT", lines[1])
        assertEquals("DOMAIN-SUFFIX,foo.cn,DIRECT", lines[2])
    }

    @Test
    fun mergeDirectDomainsIsIdempotent() {
        // 与手写规则重复、或迁移跑两次，都不能产生第二条
        val once = CustomRule.mergeDirectDomains("DOMAIN-SUFFIX,example.com,DIRECT", listOf("example.com"))
        assertEquals(1, once.split("\n").size)
        val twice = CustomRule.mergeDirectDomains(once, listOf("Example.COM"))
        assertEquals(1, twice.split("\n").size)
    }

    @Test
    fun mergeDirectDomainsSkipsJunk() {
        val merged = CustomRule.mergeDirectDomains("", listOf("  ", "bad domain", "ok.com"))
        val lines = merged.split("\n").filter { it.isNotEmpty() }
        assertEquals(1, lines.size)
        assertEquals("DOMAIN-SUFFIX,ok.com,DIRECT", lines[0])
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
