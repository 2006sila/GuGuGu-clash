package io.guguguclash

import io.guguguclash.tether.RuleBuilder
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
    fun dnsHijackComesBeforeEveryBypass() {
        // 曾经的顺序是「私网放行 → DNS 劫持」，被这个测试钉住过，结果是线上真出事：
        // 客户端解析打的是热点网关地址（10.x），落在私网放行表里 → DNS 绕过内核 →
        // 内核拿不到域名 → 共享出去的流量只能按 IP 分流，域名规则/广告拦截全失效。
        // 实测：电脑访问 www.github.com，内核里 match 到最后那条 MATCH、host 为空。
        val natBlock = script.substringAfter("-t nat -N \$HS_NAT").substringBefore("echo \"TCP")
        val dnsRedirect = natBlock.indexOf("--dport 53 -j REDIRECT")
        val firstReturn = natBlock.indexOf("-j RETURN")
        val tcpRedirect = natBlock.indexOf("-p tcp -j REDIRECT")
        assertTrue("nat 链里没有 DNS 劫持", dnsRedirect >= 0)
        assertTrue("DNS 劫持必须排在所有放行（RETURN）之前", firstReturn < 0 || dnsRedirect < firstReturn)
        assertTrue("DNS 劫持必须先于全局 TCP 重定向", dnsRedirect < tcpRedirect)
    }

    /**
     * UDP DNS **故意**不做 TPROXY —— 两条链看似"不一致"，其实是分工：
     *  · nat 决定「谁来做 DNS」：53 重定向到内核 DNS 口（$HS_DNS），且排在所有 RETURN 之前；
     *  · mangle 决定「哪些 UDP 去代理」：53 必须放行，否则查询会被劫到代理入口（$HS_TPROXY），
     *    内核 DNS 收不到查询 → fake-ip 失效 → 客户端只能按 IP 分流。
     *
     * 真机复核（2026-10）：直接查热点网关自身地址的 53 端口，返回的仍是 fake-ip（198.18.x），
     * 说明「网关地址的 UDP 查询会落到系统 dnsmasq」的说法不成立 —— nat 的 53 排在 RETURN 之前。
     * 别为了"与 nat 链对齐"把这条 RETURN 后置或删掉。
     */
    @Test
    fun udp53IsExcludedFromTproxy() {
        val mangle = script.substringAfter("-t mangle -N \$HS_MANGLE")
        val dnsReturn = mangle.indexOf("--dport 53 -j RETURN")
        val tproxy = mangle.indexOf("TPROXY --on-port")
        assertTrue("mangle 链里找不到 UDP 53 放行", dnsReturn >= 0)
        assertTrue("UDP 53 放行必须排在 TPROXY 之前", dnsReturn in 0 until tproxy)

        // TPROXY 那条不能自己带上 53（否则等于把 DNS 抢到代理口）
        val tproxyLine = mangle.lines().firstOrNull { it.contains("TPROXY --on-port") } ?: ""
        assertFalse("TPROXY 行不该匹配 --dport 53：" + tproxyLine.trim(), tproxyLine.contains("--dport 53"))

        // nat 那条必须指向 DNS 口，而不是代理口
        val natBlock = script.substringAfter("-t nat -N \$HS_NAT").substringBefore("echo \"TCP")
        val dnsLine = natBlock.lines().firstOrNull { it.contains("--dport 53 -j REDIRECT") } ?: ""
        assertTrue("nat 的 53 重定向必须指向 DNS 口：" + dnsLine.trim(), dnsLine.contains("\$HS_DNS"))
        assertFalse("nat 的 53 重定向不该指向代理口", dnsLine.contains("\$HS_TPROXY"))
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
        // 归一化：脚本里每条 iptables 都带 -w 100（等 xtables 锁），
        // 不去掉的话子串断言会被这个参数打断。
        val norm = script.replace("-w 100 ", "")
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
    fun watcherRestartsItselfByScriptPathAndOwnsItsPid() {
        // 独立守护是「App 被 force-stop 后唯一还能摘规则的人」。它曾静默失效两次：
        //   ① 用 $0 重启自己，而 App 是灌 stdin 执行的，$0 是 shell 名 → 守护根本起不来；
        //   ② 用父进程的 $! 写 pid → setsid 中间进程的 pid 或守护自己，
        //      后者会在清理做到一半时把自己杀掉，规则残留。
        assertTrue("守护必须用脚本真实路径重启自己", script.contains("setsid sh \"\$HS_SELF\" watch"))
        assertTrue("HS_SELF 只接受绝对路径", script.contains("case \"\$HS_SELF\" in"))
        assertTrue("守护必须自己写 pid", script.contains("echo \$\$ > \"\$HS_WATCH_PID\""))
        assertFalse("不能再用 \$! 写 pid", script.contains("echo \$! > \"\$HS_WATCH_PID\""))
        assertTrue("自身 pid 不能被杀", script.contains("\"\$pid\" != \"\$\$\""))
        assertTrue("守护清理时必须跳过停守护", script.contains("hs_unapply keep-watch"))
        assertTrue("hs_unapply 必须识别 keep-watch", script.contains("[ \"\$1\" != \"keep-watch\" ]"))
    }

    @Test
    fun markAndTableAreInjectableAndDefaultInScript() {
        // mark/表号必须能由 App 注入：否则规则脚本、紧急恢复、命令行脚本各持一份常量
        assertTrue(script.contains("[ -z \"\$HS_MARK\" ] && HS_MARK=2025"))
        assertTrue(script.contains("[ -z \"\$HS_TABLE\" ] && HS_TABLE=100"))
        val env = RuleBuilder.env(RuleBuilder.TetherConfig(), "on")
        assertTrue(env.contains("export HS_MARK=2025"))
        assertTrue(env.contains("export HS_TABLE=100"))
    }

    @Test
    fun applyChecksCoreBeforeWritingAnyRule() {
        val apply = script.substringAfter("hs_apply() {").substringBefore("hs_unapply() {")
        val check = apply.indexOf("pgrep -f 'guguguclash/bin/mihomo'")
        val firstRule = apply.indexOf("iptables -w 100 -t nat -N")
        assertTrue("apply 里没有内核存活检查", check >= 0)
        assertTrue("必须先确认内核活着再写规则", firstRule >= 0 && check < firstRule)
    }

    @Test
    fun localAddressesAreBypassedAfterDnsHijack() {
        // 换热点 / 改 DHCP 段时接口名可能不变、只有本机地址变，所以要把真实地址放行兜住。
        // 但它必须排在 DNS 劫持**之后** —— 本机地址里就有热点网关，网关的 53 要先被劫走。
        val nat = script.substringAfter("-t nat -N \$HS_NAT").substringBefore("echo \"TCP")
        val localLoop = nat.indexOf("for ipa in \$(ip -4 a")
        val dnsRedirect = nat.indexOf("--dport 53 -j REDIRECT")
        assertTrue("nat 链里没有本机地址放行", localLoop >= 0)
        assertTrue("本机地址放行必须排在 DNS 劫持之后", dnsRedirect < localLoop)
        assertTrue("放行要幂等（先 -C 再 -A）", nat.contains("-C \$HS_NAT -d \"\$ipa\""))
        assertTrue("mangle 链也要放行本机地址", script.contains("-t mangle -C \$HS_MANGLE -d \"\$ipa\""))
    }

    @Test
    fun unapplyAlsoSweepsCandidateInterfaces() {
        // 接口消失（热点关掉 / USB 拔掉）后规则仍挂在它上面，只有候选名单能摘到；
        // 名单由 RuleBuilder 注入，脚本里只保留命令行默认值。
        val unapply = script.substringAfter("hs_unapply() {").substringBefore("hs_status() {")
        assertTrue("unapply 没有扫候选名单", unapply.contains("for ifc in \$HS_IFACES; do"))
        assertTrue("脚本缺少 HS_IFACES 默认值", script.contains("HS_IFACES=\"wlan0"))
        val env = RuleBuilder.env(RuleBuilder.TetherConfig(), "on")
        for (n in RuleBuilder.CANDIDATE_IFACES) {
            assertTrue("env 注入的名单缺少 " + n, env.contains(n))
        }
    }

    @Test
    fun everyIptablesCallWaitsForXtableLock() {
        // 抢不到 /system/etc/xtables.lock 会导致规则只装一半，
        // 表现为「内核和端口都正常，但热点客户端就是不通」。必须每条都带 -w。
        // 两模块（box4magisk / Surfing v7）也是全篇 -w 100：热点开关与网络抖动时
        // 锁竞争激烈，-w 5 秒经常不够。
        val offenders = script.lines().filter { l ->
            l.contains("iptables ") && !l.contains("iptables -w ") &&
                !l.trimStart().startsWith("#")
        }
        assertTrue("这些 iptables 调用没等锁：\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
