package io.vpnshare

import io.vpnshare.tether.RuleBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 直接对 assets/tproxy.sh 做结构与顺序断言 —— 这是真正会在设备上以 root 执行的规则本体。
 */
class RuleScriptTest {

    private val script = File("src/main/assets/tproxy.sh").readText()

    @Test
    fun scriptExistsAndIsPosix() {
        assertTrue(script.startsWith("#!/system/bin/sh"))
        assertFalse("不应含 CR", script.contains("\r"))
        assertTrue(script.contains("case \"\$HS_ACTION\" in"))
    }

    @Test
    fun natChainReturnsPrivateNetsBeforeRedirect() {
        val natBlock = script.substringAfter("-t nat -N \$HS_NAT").substringBefore("echo \"TCP")
        val lastReturn = natBlock.lastIndexOf("-j RETURN")
        val dnsRedirect = natBlock.indexOf("--dport 53 -j REDIRECT")
        val tcpRedirect = natBlock.indexOf("-p tcp -j REDIRECT")
        assertTrue("私网 RETURN 必须先于 DNS 重定向", lastReturn in 0 until dnsRedirect)
        assertTrue("DNS 重定向必须先于全局 TCP 重定向", dnsRedirect < tcpRedirect)
    }

    @Test
    fun udp53IsExcludedFromTproxy() {
        val mangle = script.substringAfter("-t mangle -N \$HS_MANGLE")
        val dnsReturn = mangle.indexOf("--dport 53 -j RETURN")
        val tproxy = mangle.indexOf("TPROXY --on-port")
        assertTrue(dnsReturn in 0 until tproxy)
    }

    @Test
    fun privateNetListMatchesBuilderConstant() {
        val line = script.lines().firstOrNull { it.startsWith("HS_NETS=") }
        assertTrue("脚本缺少 HS_NETS 定义", line != null)
        for (net in RuleBuilder.PRIVATE_NETS) {
            assertTrue("HS_NETS 缺少 " + net, line!!.contains(net))
        }
        // 两条链都必须对 HS_NETS 做 RETURN，否则内网流量会被劫持
        assertEquals(1, script.split("-t nat -A \$HS_NAT -d \"\$n\" -j RETURN").size - 1)
        assertEquals(1, script.split("-t mangle -A \$HS_MANGLE -d \"\$n\" -j RETURN").size - 1)
    }

    @Test
    fun tproxyMarkMatchesBuilderConstant() {
        assertTrue(script.contains("--tproxy-mark \$HS_MARK"))
        assertTrue(script.contains("HS_MARK=2025"))
        assertEquals(2025, RuleBuilder.FWMARK)
        // 绝不能与内核 tun.routing-mark(2024) 撞号
        assertTrue("TPROXY mark 不能等于内核 routing-mark", RuleBuilder.FWMARK != 2024)
        assertTrue(script.contains("HS_TABLE=100"))
        assertEquals(100, RuleBuilder.TABLE_ID)
    }

    @Test
    fun cleanupRemovesChainsRulesAndRoutes() {
        val unapply = script.substringAfter("hs_unapply() {").substringBefore("hs_status() {")
        for (expected in listOf(
            "-t nat -D PREROUTING",
            "-t mangle -D PREROUTING",
            "-t nat -X \$HS_NAT",
            "-t mangle -X \$HS_MANGLE",
            "ip rule del fwmark",
            "ip route flush table"
        )) {
            assertTrue("清理缺少: " + expected, unapply.contains(expected))
        }
    }

    @Test
    fun blockChainIsCreatedAndDestroyed() {
        assertTrue(script.contains("HS_BLOCK=HS_BLOCK"))
        // 归一化：脚本里每条 iptables 都带 -w 5（等 xtables 锁），
        // 不去掉的话子串断言会被这个参数打断。
        val norm = script.replace("-w 5 ", "")
        val apply = norm.substringAfter("hs_apply() {").substringBefore("hs_unapply() {")
        assertTrue("apply 未创建屏蔽链", apply.contains("iptables -N \$HS_BLOCK"))
        assertTrue("apply 未把屏蔽链挂到 FORWARD", apply.contains("-I FORWARD -i \"\$IFACE\" -j \$HS_BLOCK"))
        val unapply = norm.substringAfter("hs_unapply() {").substringBefore("hs_status() {")
        assertTrue("unapply 未摘掉 FORWARD 跳转", unapply.contains("-D FORWARD -i \"\$ifc\" -j \$HS_BLOCK"))
        assertTrue("unapply 未销毁屏蔽链", unapply.contains("iptables -F \$HS_BLOCK"))
        assertTrue("unapply 未删除屏蔽链", unapply.contains("iptables -X \$HS_BLOCK"))
        assertTrue("RuleBuilder 常量与脚本不一致", script.contains(RuleBuilder.CHAIN_BLOCK))
    }

    @Test
    fun noUnresolvedTemplatePlaceholders() {
        assertFalse(script.contains("@@"))
        assertFalse(script.contains("TODO"))
        assertFalse("不应出现字面 \\n", script.contains("\\n"))
        assertTrue("接口覆盖应走 HS_IFACE 环境变量", script.contains("if [ -n \"\$HS_IFACE\" ]"))
    }

    @Test
    fun envCarriesAllSettings() {
        val cfg = RuleBuilder.TetherConfig(iface = "ap0", redirPort = 7892, tproxyPort = 7893, dnsPort = 1053)
        val env = RuleBuilder.env(cfg, "on")
        for (k in listOf("HS_ACTION=on", "HS_IFACE='ap0'", "HS_REDIR=7892", "HS_TPROXY=7893", "HS_DNS=1053", "HS_UDP=1", "HS_BLOCK_V6=1")) {
            assertTrue("env 缺少 " + k, env.contains(k))
        }
    }

    @Test
    fun previewListsOrderedCommands() {
        val cmds = RuleBuilder.previewCommands(RuleBuilder.TetherConfig(proxyUdp = true, blockIpv6 = true))
        val firstRedirect = cmds.indexOfFirst { it.contains("REDIRECT --to-ports 7892") }
        assertTrue(firstRedirect > 0)
        assertTrue(cmds.any { it.contains("ip rule add fwmark 2025 lookup 100") })
        assertTrue(cmds.any { it.contains("ip route add local 0.0.0.0/0 dev lo table 100") })
    }

    @Test
    fun everyIptablesCallWaitsForXtableLock() {
        // 抢不到 /system/etc/xtables.lock 会导致规则只装一半，
        // 表现为「内核和端口都正常，但热点客户端就是不通」。必须每条都带 -w。
        val offenders = script.lines().filter { l ->
            l.contains("iptables ") && !l.contains("iptables -w ") &&
                !l.trimStart().startsWith("#")
        }
        assertTrue("这些 iptables 调用没等锁：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
