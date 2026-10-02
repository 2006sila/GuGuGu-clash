package io.vpnshare

import io.vpnshare.profile.ConfigBuilder
import io.vpnshare.profile.RuleAction
import io.vpnshare.profile.RuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConfigBuilderTest {

    private val template = File("src/main/assets/base.template.yaml").readText()

    private fun opts(
        enabled: Map<String, RuleAction> = RuleCatalog.DEFAULT_ACTIONS,
        custom: List<String> = emptyList(),
        local: Boolean = false,
        adoptGroups: Boolean = false,
        adoptRules: Boolean = false,
        subYaml: String? = null,
        phoneProxy: Boolean = false
    ) = ConfigBuilder.Options(
        enabled = enabled, customDirectDomains = custom, localRulesetsPresent = local,
        adoptSubscriptionGroups = adoptGroups, adoptSubscriptionRules = adoptRules,
        subscriptionYaml = subYaml, proxyOwnTraffic = phoneProxy
    )

    @Test
    fun tunFollowsPhoneProxySwitch() {
        // 默认关：热点共享才不会被 tun 的路由改动拖死
        val off = ConfigBuilder.build(template, opts(phoneProxy = false))
        assertTrue("默认必须关掉 tun", off.contains("tun:\n  enable: false"))
        val on = ConfigBuilder.build(template, opts(phoneProxy = true))
        assertTrue("开关打开时应启用 tun", on.contains("tun:\n  enable: true"))
    }

    @Test
    fun templateIsValidOnItsOwn() {
        assertTrue(template.contains("redir-port: 7892"))
        assertTrue(template.contains("tproxy-port: 7893"))
        assertTrue(template.contains("listen: 0.0.0.0:1053"))
        assertTrue(template.contains("routing-mark: 2024"))
    }

    @Test
    fun bilibiliDirectIsOnByDefault() {
        val out = ConfigBuilder.build(template, opts())
        val rules = ConfigBuilder.ruleLines(opts())
        assertTrue(rules.any { it.contains("bilibili") && it.endsWith("DIRECT") })
        assertTrue(out.contains("GEOSITE,bilibili,DIRECT"))
        assertTrue(out.contains("GEOSITE,cn,DIRECT"))
        assertTrue(out.contains("MATCH,PROXY"))
        // 只能是带 no-resolve 的形式：不带的话 DNS 污染会把境外域名误判成国内
        assertTrue("应有 GEOIP,CN,DIRECT,no-resolve", out.contains("GEOIP,CN,DIRECT,no-resolve"))
        assertFalse("不能出现不带 no-resolve 的 GEOIP,CN,DIRECT",
            Regex("GEOIP,CN,DIRECT(?!,no-resolve)").containsMatchIn(out))
    }

    @Test
    fun subscriptionCannotOverrideControlledSections() {
        val evilSub = """
            tun:
              enable: false
            redir-port: 1
            tproxy-port: 2
            dns:
              listen: 0.0.0.0:1
            rules:
              - MATCH,DIRECT
        """.trimIndent()

        val built = ConfigBuilder.build(template, opts(subYaml = evilSub))
        assertTrue(built.contains("redir-port: 7892"))
        assertTrue(built.contains("tproxy-port: 7893"))
        assertTrue(built.contains("enable: true"))
        assertTrue(built.contains("listen: 0.0.0.0:1053"))
        // 受控段只出现一次，且值来自模板
        assertEquals(1, built.lines().count { it.trim() == "redir-port: 7892" })
    }

    @Test
    fun adoptSubscriptionRulesKeepsBuiltinDirectFirst() {
        val sub = """
            rules:
              - DOMAIN-SUFFIX,example.com,PROXY
              - MATCH,PROXY
        """.trimIndent()
        val rules = ConfigBuilder.ruleLines(opts(adoptRules = true, subYaml = sub))
        val biliIdx = rules.indexOfFirst { it.contains("bilibili") }
        val subIdx = rules.indexOfFirst { it.contains("example.com") }
        assertTrue("内置直连必须早于订阅规则", biliIdx in 0 until subIdx)
        // 订阅的 MATCH 不被带入，只有内置的那一条
        assertEquals(1, rules.count { it.contains("MATCH") })
        // 兜底必须排在最后，否则订阅规则不可达
        assertTrue("兜底 MATCH 必须在最后", rules.last().contains("MATCH,"))
    }

    @Test
    fun adoptSubscriptionGroupsReplacesGeneratedGroup() {
        val sub = """
            proxy-groups:
              - { name: 自定义, type: select, proxies: [a, b] }
            rules:
              - MATCH,PROXY
        """.trimIndent()
        val withAdopt = ConfigBuilder.build(template, opts(adoptGroups = true, subYaml = sub))
        assertTrue(withAdopt.contains("name: 自定义"))
        val without = ConfigBuilder.build(template, opts())
        assertTrue(without.contains("name: PROXY, type: select, use: [sub]"))
        assertFalse(without.contains("name: 自定义"))
    }

    @Test
    fun customDirectDomainsComeFirst() {
        val o = opts(custom = listOf("+.example.com", "foo.cn"))
        val rules = ConfigBuilder.ruleLines(o)
        assertTrue(rules[0].contains("example.com") && rules[0].endsWith("DIRECT"))
        assertTrue(rules[1].contains("foo.cn"))
        val biliIdx = rules.indexOfFirst { it.contains("bilibili") }
        assertTrue(biliIdx > 1)
    }

    @Test
    fun localRulesetsSwitchToRuleSetLines() {
        val local = ConfigBuilder.ruleLines(opts(local = true))
        assertTrue(local.any { it.contains("RULE-SET,private-local,DIRECT") })
        assertTrue(local.any { it.contains("RULE-SET,bilibili-local,DIRECT") })
        assertTrue(local.any { it.contains("RULE-SET,ads-local,REJECT") })
        // cn 没有本地规则集，仍走 GEOSITE
        assertTrue(local.any { it.contains("GEOSITE,cn,DIRECT") })

        val providers = ConfigBuilder.ruleProvidersBlock()
        assertTrue(providers.contains("bilibili.yaml"))
        assertTrue(providers.contains("category-ads-all.yaml"))
    }

    @Test
    fun disablingCategoryRemovesItsRule() {
        val enabled = RuleCatalog.DEFAULT_ACTIONS.toMutableMap()
        enabled["ads"] = RuleAction.DIRECT
        val rules = ConfigBuilder.ruleLines(opts(enabled = enabled))
        assertTrue(rules.any { it.contains("category-ads-all") && it.endsWith("DIRECT") })
        assertFalse(rules.any { it.contains("category-ads-all") && it.endsWith("REJECT") })

        enabled.remove("bilibili")
        val rules2 = ConfigBuilder.ruleLines(opts(enabled = enabled))
        assertFalse(rules2.any { it.contains("bilibili") })
    }

    @Test
    fun everyEnabledCategoryProducesExactlyOneRule() {
        val rules = ConfigBuilder.ruleLines(opts(local = false))
        for (cat in RuleCatalog.ALL) {
            val hits = rules.count { it.contains(cat.geosite) }
            assertEquals("category " + cat.key, 1, hits)
        }
        val localRules = ConfigBuilder.ruleLines(opts(local = true))
        for (cat in RuleCatalog.ALL) {
            val expected = if (cat.localRuleset != null) 1 else 0
            val hits = localRules.count { it.contains("," + cat.key + "-local,") }
            assertEquals("local category " + cat.key, expected, hits)
        }
    }
}
