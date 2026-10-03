package io.guguguclash.tether

import android.content.Context
import io.guguguclash.core.CoreInstaller
import io.guguguclash.prefs.Prefs
import io.guguguclash.root.RootShell
import io.guguguclash.service.ShareState

/**
 * 接管总控：装配 assets/tproxy.sh 并执行 apply / status / cleanup。
 * 规则本体在脚本里，本类只负责参数注入、环境准备与结果解析。
 */
object TetherManager {

    /**
     * 落盘后的脚本路径。
     *
     * 必须以「脚本文件 + 动作」的方式执行，而不是把脚本文本灌进 su 的 stdin：
     * 脚本里的独立守护会用 $0 把自己重新拉起来，stdin 注入时 $0 是 su 会话的
     * shell 名（sh），守护会去打开名为 sh 的文件并立刻失败 —— 那条兜底从来没生效过。
     */
    val SCRIPT_PATH: String get() = CoreInstaller.RUN + "/tproxy.sh"

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

    /**
     * 共享接口的「身份」：接口名 + 该接口上的 IPv4 地址集合。
     *
     * 为什么要带地址：换热点或机场侧改了 DHCP 段时，接口名可能完全不变，
     * 变的只是本机地址 —— 只盯接口名的话这种变化不会触发重装规则，
     * 直到客户端连不上才被发现（对应 tproxy.sh 里那条「本机地址一律放行」）。
     */
    fun tetherKey(p: Prefs.Data): String? {
        val iface = detectIface(p) ?: return null
        val addrs = RootShell.run("ip -o -4 addr show dev '" + iface + "' 2>/dev/null", timeoutSec = 10).out
        return tetherKeyOf(iface, addrs)
    }

    /** 纯函数便于单测：接口名 + 地址集合（排序，保证同一网络下稳定可比） */
    fun tetherKeyOf(iface: String?, addrOutput: String): String? {
        if (iface.isNullOrBlank()) return null
        val ips = TetherDetector.parseAddrs(addrOutput).map { it.ip }.sorted()
        return iface + "|" + ips.joinToString(",")
    }

    /**
     * 把装配好的脚本落盘到设备，返回可交给 sh 执行的路径；失败返回 null。
     * 落盘失败时调用方退回 stdin 注入：规则照装，只是独立守护起不来。
     */
    private fun deploy(ctx: Context, p: Prefs.Data, action: String): String? = runCatching {
        val text = RuleBuilder.assemble(script(ctx), config(p), action)
        val stage = java.io.File(ctx.filesDir, "tproxy.sh")
        stage.writeText(text)
        val r = RootShell.run(
            "mkdir -p '" + CoreInstaller.RUN + "' && " +
                "cp -f '" + stage.absolutePath + "' '" + SCRIPT_PATH + "' && " +
                "chmod 700 '" + SCRIPT_PATH + "' && echo deployed",
            timeoutSec = 20
        )
        if (r.out.contains("deployed")) SCRIPT_PATH else null
    }.getOrNull()

    private fun exec(ctx: Context, p: Prefs.Data, action: String): RootShell.Result {
        val path = deploy(ctx, p, action)
        if (path != null) return RootShell.run("sh '" + path + "' " + action, timeoutSec = 45)
        ShareState.log("WARN 脚本落盘失败，退回 stdin 注入（独立守护不会启动）")
        return RootShell.run(RuleBuilder.assemble(script(ctx), config(p), action), timeoutSec = 45)
    }

    fun apply(ctx: Context, p: Prefs.Data): RootShell.Result = exec(ctx, p, "on")

    fun cleanup(ctx: Context, p: Prefs.Data): RootShell.Result = exec(ctx, p, "off")

    fun status(ctx: Context, p: Prefs.Data): RootShell.Result = exec(ctx, p, "status")

    /** 需要检查监听的端口集合。抽成纯函数，便于单测钉死 —— 这类判断错了是静默事故。 */
    fun portsToCheck(p: Prefs.Data): List<Int> = buildList {
        add(p.redirPort)
        add(p.tproxyPort)
        add(p.dnsPort)
        // mixed 端口必须查：出口探测正是走它。漏掉它会同时出两种错：
        //   ① 状态表里没有 mixedPort 这个键，调用方读 state[mixedPort] 得到 null，
        //      「端口齐全」永远判 false → 每次启动都把健康内核杀掉重启（断流）；
        //   ② 只查 redir/tproxy/dns 时，一个「mixed 没绑上」的孤儿内核会被判健康并复用。
        // http/socks 是可选监听（Prefs 默认 0），用户设了端口但内核没绑时不该拖累复用判定，
        // 所以这里刻意不纳入。
        if (p.mixedPort > 0) add(p.mixedPort)
    }.distinct()

    /** 全部必需端口都在监听。state 由 [listeningPortsDetailed] 给出。 */
    fun allPortsListening(state: Map<Int, Boolean>, p: Prefs.Data): Boolean =
        portsToCheck(p).all { state[it] == true }

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
        val ports = portsToCheck(p)
        val r = RootShell.run("netstat -ltn 2>/dev/null; ss -ltn 2>/dev/null", timeoutSec = 20)
        val out = r.out
        val state = parsePortStates(out, ports)
        val kept = out.lineSequence()
            .filter { line -> ports.any { line.contains(":" + it) } }
            .joinToString(" / ")
        return PortReport(state, kept.ifBlank { out.take(300) })
    }

    fun ensureDirs(): RootShell.Result = RootShell.run(
        "mkdir -p " + CoreInstaller.RULESET + " " + CoreInstaller.PROVIDERS + " " + CoreInstaller.RUN + " && echo ok"
    )
}
