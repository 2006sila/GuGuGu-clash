package io.vpnshare.tether

import android.content.Context
import io.vpnshare.core.CoreInstaller
import io.vpnshare.prefs.Prefs
import io.vpnshare.root.RootShell

/**
 * 接管总控：装配 assets/tproxy.sh 并执行 apply / status / cleanup。
 * 规则本体在脚本里，本类只负责参数注入、环境准备与结果解析。
 */
object TetherManager {

    fun script(ctx: Context): String =
        ctx.assets.open("tproxy.sh").bufferedReader().use { it.readText() }

    fun config(p: Prefs.Data): RuleBuilder.TetherConfig = RuleBuilder.TetherConfig(
        iface = p.ifaceOverride,
        redirPort = p.redirPort,
        tproxyPort = p.tproxyPort,
        dnsPort = p.dnsPort,
        proxyUdp = p.proxyUdp,
        blockIpv6 = p.blockIpv6
    )

    /** 读设备当前接口状态（不经脚本，便于日志与 UI 展示） */
    fun detectIface(p: Prefs.Data): String? {
        if (!p.ifaceOverride.isBlank()) return p.ifaceOverride
        // 标记不能用 # 开头：# 在 shell 里是注释，echo #LN# 只会输出空行，
        // 导致三段输出全都解析不到（接口检测恒为 null）。
        val r = RootShell.run(
            "echo ZZMARK_LN; ip route show table local_network 2>/dev/null; " +
                "echo ZZMARK_ROUTE; ip route show default 2>/dev/null; " +
                "echo ZZMARK_ADDR; ip -o -4 addr show 2>/dev/null"
        )
        val lnPart = r.out.substringAfter("ZZMARK_LN", "").substringBefore("ZZMARK_ROUTE")
        val routePart = r.out.substringAfter("ZZMARK_ROUTE", "").substringBefore("ZZMARK_ADDR")
        val addrPart = r.out.substringAfter("ZZMARK_ADDR", "")

        // 首选 AOSP tethering 专用表
        val fromTable = TetherDetector.parseLocalNetworkIfaces(lnPart)
            .firstOrNull { it != "lo" }
        if (fromTable != null) return fromTable

        return TetherDetector.detect(
            TetherDetector.parseAddrs(addrPart),
            TetherDetector.parseDefaultDev(routePart),
            null
        )
    }

    fun apply(ctx: Context, p: Prefs.Data): RootShell.Result {
        val script = RuleBuilder.assemble(script(ctx), config(p), "on")
        return RootShell.run(script, timeoutSec = 45)
    }

    fun cleanup(ctx: Context, p: Prefs.Data): RootShell.Result {
        val script = RuleBuilder.assemble(script(ctx), config(p), "off")
        return RootShell.run(script, timeoutSec = 45)
    }

    fun status(ctx: Context, p: Prefs.Data): RootShell.Result {
        val script = RuleBuilder.assemble(script(ctx), config(p), "status")
        return RootShell.run(script, timeoutSec = 30)
    }

    /** 规则是否已生效：nat 链存在且挂到了至少一个接口上 */
    fun isActive(): Boolean {
        val r = RootShell.run(
            "iptables -t nat -S " + RuleBuilder.CHAIN_NAT + " >/dev/null 2>&1 && " +
            "iptables -t nat -S PREROUTING 2>/dev/null | grep -q " + RuleBuilder.CHAIN_NAT + " && echo yes"
        )
        return r.out.contains("yes")
    }

    /** 内核是否在监听 redir / tproxy / dns 端口 */
    /**
     * 逐个端口独立检查监听状态。
     * 不用 "(a || b) | grep" 这种写法：子 shell + 管道在部分 ROM 的 su 会话里
     * 会直接语法报错（syntax error: unexpected '('），导致三项全判为未监听。
     */
    data class PortReport(val state: Map<Int, Boolean>, val raw: String)

    /**
     * 把 netstat/ss 的原始输出取回来，在 Kotlin 侧判断。
     * 不在 shell 里写 if/elif/grep 组合：经 su 会话执行时曾出现整段被判为未监听，
     * 而同样的脚本用 adb su 手跑却是三项全过 —— 与其猜 shell 差异，不如把原文拿回来自己判。
     */
    /**
     * 从 netstat/ss 的输出判断这些端口是否在监听。
     *
     * 抽成纯函数是因为这类判断错了会静默出事，而且已经出过两次：
     * 一次是只查 redir/tproxy/dns 没查 mixed，导致一个「其它端口正常但 mixed 没绑上」
     * 的孤儿内核被判健康并复用，随后探测超时、服务把整个共享回滚；
     * 另一次是端口号前缀互相误匹配（7890 与 78901）导致假阳性。
     */
    fun parsePortStates(raw: String, ports: List<Int>): Map<Int, Boolean> =
        ports.associateWith { port -> Regex(":" + port + "(\\s|$)").containsMatchIn(raw) }

    fun listeningPortsDetailed(p: Prefs.Data): PortReport {
        val ports = listOf(p.redirPort, p.tproxyPort, p.dnsPort)
        val r = RootShell.run("netstat -ltn 2>/dev/null; ss -ltn 2>/dev/null", timeoutSec = 20)
        val out = r.out
        val state = parsePortStates(out, ports)
        val kept = out.lineSequence()
            .filter { line -> ports.any { line.contains(":" + it) } }
            .joinToString(" / ")
        return PortReport(state, kept.ifBlank { out.take(300) })
    }

    fun listeningPorts(p: Prefs.Data): Map<Int, Boolean> = listeningPortsDetailed(p).state

    fun ensureDirs(): RootShell.Result = RootShell.run(
        "mkdir -p " + CoreInstaller.RULESET + " " + CoreInstaller.PROVIDERS + " " + CoreInstaller.RUN + " && echo ok"
    )
}
