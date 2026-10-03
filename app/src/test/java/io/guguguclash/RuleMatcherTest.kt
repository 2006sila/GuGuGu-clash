package io.guguguclash

import io.guguguclash.profile.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 规则命中查询。这是排障功能，答案错比不答更糟，所以「判不了」必须如实说。 */
class RuleMatcherTest {

    private val rules = """
rules:
  - DOMAIN,exact.com,PROXY
  - DOMAIN-SUFFIX,bilibili.com,DIRECT
  - DOMAIN-KEYWORD,google,DIRECT
  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve
  - IP-CIDR,1.1.1.0/24,PROXY
  - GEOSITE,cn,DIRECT
  - MATCH,Final
""".trimIndent()

    @Test
    fun domainExactWins() {
        val r = RuleMatcher.match(rules, "exact.com")
        assertEquals("PROXY", r.hits.single().action)
        assertEquals("DOMAIN", r.hits.single().type)
    }

    @Test
    fun domainSuffixMatchesSubdomainOnly() {
        assertEquals("DIRECT", RuleMatcher.match(rules, "live.bilibili.com").hits.single().action)
        assertEquals("DIRECT", RuleMatcher.match(rules, "bilibili.com").hits.single().action)
        // 不能把「notbilibili.com」也算进去 —— 后缀匹配必须带点边界
        assertFalse(RuleMatcher.match(rules, "notbilibili.com").hits.any { it.type == "DOMAIN-SUFFIX" })
    }

    @Test
    fun keywordMatches() {
        assertEquals("DIRECT", RuleMatcher.match(rules, "www.google.com.hk").hits.single().action)
    }

    @Test
    fun ipCidrMatches() {
        assertEquals("DIRECT", RuleMatcher.match(rules, "10.1.2.3").hits.single().action)
        assertEquals("PROXY", RuleMatcher.match(rules, "1.1.1.9").hits.single().action)
    }

    @Test
    fun ipCidrBoundaryIsExact() {
        // 1.1.1.0/24 只覆盖 1.1.1.0-1.1.1.255
        assertTrue(RuleMatcher.cidrContains("1.1.1.0/24", "1.1.1.255"))
        assertFalse(RuleMatcher.cidrContains("1.1.1.0/24", "1.1.2.0"))
        assertTrue(RuleMatcher.cidrContains("10.0.0.0/8", "10.255.255.255"))
        assertFalse(RuleMatcher.cidrContains("10.0.0.0/8", "11.0.0.1"))
    }

    @Test
    fun fallsThroughToMatch() {
        val r = RuleMatcher.match(rules, "unknown-domain.net")
        assertEquals("Final", r.finalAction)
        assertEquals("MATCH", r.hits.single().type)
    }

    @Test
    fun countsUndecidableRules() {
        val r = RuleMatcher.match(rules, "unknown-domain.net")
        // GEOSITE 判不了，必须如实计数并给出位置
        assertEquals(1, r.undecidable)
        assertTrue(r.firstUndecidableAt != null)
    }

    @Test
    fun domainDoesNotMatchIpRules() {
        val r = RuleMatcher.match(rules, "10.1.2.3")
        assertTrue(r.isIp)
        // 域名类规则不应被 IP 命中
        assertFalse(r.hits.any { it.type.startsWith("DOMAIN") })
    }

    @Test
    fun detectsIpLiteral() {
        assertTrue(RuleMatcher.isIpLiteral("1.2.3.4"))
        assertTrue(RuleMatcher.isIpLiteral("2001:db8::1"))
        assertFalse(RuleMatcher.isIpLiteral("example.com"))
        assertFalse(RuleMatcher.isIpLiteral("999.1.1.1"))
        assertFalse(RuleMatcher.isIpLiteral(""))
    }

    @Test
    fun toleratesCommentsAndQuotes() {
        val odd = """
rules:
  - "DOMAIN-SUFFIX,quoted.com,DIRECT"
  - DOMAIN-SUFFIX, spaced.com , PROXY
""".trimIndent()
        assertEquals("DIRECT", RuleMatcher.match(odd, "a.quoted.com").hits.single().action)
        assertEquals("PROXY", RuleMatcher.match(odd, "a.spaced.com").hits.single().action)
    }

    @Test
    fun finalActionIsTheMatchedRuleNotNull() {
        // 曾经的 bug：只有命中项本身是 MATCH 时才取 finalAction，
        // 命中真实规则时反而返回 null，界面显示「未命中任何规则」。
        val r = RuleMatcher.match(rules, "live.bilibili.com")
        assertEquals("DIRECT", r.finalAction)
    }

    @Test
    fun finalActionFallsBackToMatchWhenNothingHits() {
        val r = RuleMatcher.match(rules, "nothing-matches.example")
        assertEquals("Final", r.finalAction)
    }

    @Test
    fun finalActionIsNullWhenNoMatchRuleExists() {
        val noFallback = """
rules:
  - DOMAIN-SUFFIX,only.com,DIRECT
""".trimIndent()
        assertNull(RuleMatcher.match(noFallback, "other.com").finalAction)
        assertEquals("DIRECT", RuleMatcher.match(noFallback, "a.only.com").finalAction)
    }
}
