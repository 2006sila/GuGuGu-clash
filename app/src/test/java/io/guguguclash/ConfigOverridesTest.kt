package io.guguguclash

import io.guguguclash.profile.ConfigBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 受控段（DNS / tun / 嗅探 / 顶层）生成的回归测试。
 *  这些键名都已在内核二进制里核实过存在，配错会导致内核拒绝启动。 */
class ConfigOverridesTest {

    private val tpl = """
mixed-port: 7890
redir-port: 7892
tproxy-port: 7893
allow-lan: true
external-controller: 127.0.0.1:9090
tcp-concurrent: true
find-process-mode: off

tun:
  enable: true
  stack: mixed

dns:
  enable: true
  listen: 0.0.0.0:1053
  enhanced-mode: fake-ip

rules:
  - MATCH,PROXY
""".trimIndent()

    @Test
    fun dnsSectionIsRegenerated() {
        val out = ConfigBuilder.build(tpl, ConfigBuilder.Options(
            dns = ConfigBuilder.DnsOptions(
                defaultNameserver = listOf("223.5.5.5"),
                nameserver = listOf("https://223.5.5.5/dns-query"),
                fakeIpFilter = listOf("*.lan")
            )
        ))
        assertTrue(out.contains("enhanced-mode: fake-ip"))
        assertTrue(out.contains("use-hosts: true"))
        assertTrue(out.contains("- 223.5.5.5"))
        assertTrue(out.contains("- \"*.lan\""))
        // 没配 fallback 就不能出现 fallback 段（配错会导致节点域名解析超时）
        assertFalse(out.contains("fallback:"))
    }

    @Test
    fun fallbackOnlyEmittedWhenSet() {
        val out = ConfigBuilder.build(tpl, ConfigBuilder.Options(
            dns = ConfigBuilder.DnsOptions(
                fallback = listOf("https://1.2.3.4/dns-query"),
                fallbackGeoip = true,
                fallbackGeoipCode = "CN"
            )
        ))
        assertTrue(out.contains("fallback:"))
        assertTrue(out.contains("fallback-filter:"))
        assertTrue(out.contains("geoip-code: CN"))
    }

    @Test
    fun tunFollowsSwitchAndCarriesAppSplit() {
        val on = ConfigBuilder.build(tpl, ConfigBuilder.Options(
            proxyOwnTraffic = true,
            tun = ConfigBuilder.TunOptions(stack = "gvisor", includePackage = listOf("com.a", "com.b"))
        ))
        assertTrue(on.contains("enable: true"))
        assertTrue(on.contains("stack: gvisor"))
        assertTrue(on.contains("include-package:"))
        assertTrue(on.contains("- com.a"))

        val off = ConfigBuilder.build(tpl, ConfigBuilder.Options(proxyOwnTraffic = false))
        assertTrue(off.contains("enable: false"))
        assertFalse(off.contains("include-package:"))
    }

    @Test
    fun blacklistUsesExcludePackage() {
        val out = ConfigBuilder.build(tpl, ConfigBuilder.Options(
            tun = ConfigBuilder.TunOptions(excludePackage = listOf("com.x"))
        ))
        assertTrue(out.contains("exclude-package:"))
        assertFalse(out.contains("include-package:"))
    }

    @Test
    fun snifferOnlyWhenEnabled() {
        val on = ConfigBuilder.build(tpl, ConfigBuilder.Options(
            over = ConfigBuilder.OverrideOptions(sniffEnable = true, sniffTlsPorts = listOf("443"))
        ))
        assertTrue(on.contains("sniffer:"))
        assertTrue(on.contains("parse-pure-ip: true"))
        assertTrue(on.contains("TLS:"))
        assertFalse(ConfigBuilder.build(tpl, ConfigBuilder.Options()).contains("sniffer:"))
    }

    @Test
    fun scalarsAreRewritten() {
        val out = ConfigBuilder.build(tpl, ConfigBuilder.Options(
            over = ConfigBuilder.OverrideOptions(
                redirPort = 1111, tproxyPort = 2222, unifiedDelay = true,
                externalController = "127.0.0.1:9999", secret = "s3cr3t"
            )
        ))
        assertTrue(out.contains("redir-port: 1111"))
        assertTrue(out.contains("tproxy-port: 2222"))
        assertTrue(out.contains("unified-delay: true"))
        assertTrue(out.contains("external-controller: 127.0.0.1:9999"))
        assertTrue(out.contains("secret: s3cr3t"))
    }

    @Test
    fun rulesStillComeLast() {
        val out = ConfigBuilder.build(tpl, ConfigBuilder.Options())
        val ri = out.indexOf("rules:")
        assertTrue(ri > 0)
        assertTrue(out.substring(ri).contains("MATCH,PROXY"))
        // 受控段必须都在 rules 之前
        assertTrue(out.indexOf("dns:") < ri)
        assertTrue(out.indexOf("tun:") < ri)
    }
}
