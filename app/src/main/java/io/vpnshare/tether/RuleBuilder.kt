package io.vpnshare.tether

/**
 * 接管规则的参数与脚本装配。规则本体在 assets/tproxy.sh（App 与命令行同一份），
 * 本类只负责生成 HS_* 环境变量并拼装调用串。规则顺序由单测直接校验脚本内容。
 */
object RuleBuilder {

    const val CHAIN_NAT = "HS_NAT"
    const val CHAIN_MANGLE = "HS_MANGLE"
    const val CHAIN_BLOCK = "HS_BLOCK"
    const val TABLE_ID = 100
    /** 与内核 tun.routing-mark(2024) 必须不同，见 assets/tproxy.sh 注释 */
    const val FWMARK = 2025

    val PRIVATE_NETS = listOf(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.168.0.0/16",
        "224.0.0.0/4", "240.0.0.0/4"
    )

    /**
     * 共享接口候选名单（清理用）。
     *
     * 接口此刻可能已经消失（热点关掉 / USB 拔掉 / 蓝牙断开），规则却仍挂在那个接口上 ——
     * 这时只有不依赖当前接口状态的名单法能摘到。检测侧用的是前缀通配
     * （ap / wlan / softap / swlan / rndis / usb / eth / bt-pan），所以名单多留几个常见编号。
     * tproxy.sh 与 Emergency 都从这一份拿，避免三处各写一份慢慢漂移。
     */
    val CANDIDATE_IFACES = listOf(
        "wlan0", "wlan1", "wlan2", "wlan3", "ap0", "ap1", "ap2", "softap0", "softap1",
        "swlan0", "swlan1", "rndis0", "rndis1", "usb0", "usb1", "eth0", "eth1", "bt-pan"
    )

    data class TetherConfig(
        val iface: String = "",
        val redirPort: Int = 7892,
        val tproxyPort: Int = 7893,
        val dnsPort: Int = 1053,
        val proxyUdp: Boolean = true,
        val blockIpv6: Boolean = true
    )

    fun env(cfg: TetherConfig, action: String): String = buildString {
        append("export HS_ACTION=").append(action).append('\n')
        append("export HS_IFACE='").append(cfg.iface.replace("'", "")).append("'\n")
        append("export HS_REDIR=").append(cfg.redirPort).append('\n')
        append("export HS_TPROXY=").append(cfg.tproxyPort).append('\n')
        append("export HS_DNS=").append(cfg.dnsPort).append('\n')
        append("export HS_UDP=").append(if (cfg.proxyUdp) 1 else 0).append('\n')
        append("export HS_BLOCK_V6=").append(if (cfg.blockIpv6) 1 else 0).append('\n')
        // mark / 路由表号由这里唯一供给（脚本里那两行只是命令行下的默认值），
        // 免得规则脚本与清理脚本各持一份常量、改一处漏一处。
        append("export HS_MARK=").append(FWMARK).append('\n')
        append("export HS_TABLE=").append(TABLE_ID).append('\n')
        // 候选接口名单：脚本用它兜「接口已消失」的清理
        append("export HS_IFACES='").append(CANDIDATE_IFACES.joinToString(" ")).append("'\n")
    }

    fun assemble(scriptAsset: String, cfg: TetherConfig, action: String): String =
        env(cfg, action) + scriptAsset

    /** UI 预览用：把脚本实际会执行的命令列出来 */
    fun previewCommands(cfg: TetherConfig): List<String> {
        val out = mutableListOf<String>()
        out += "iptables -t nat -N " + CHAIN_NAT
        out += "iptables -t nat -F " + CHAIN_NAT
        // DNS 劫持排在放行之前：客户端解析打的是热点网关，网关在私网放行表里
        out += "iptables -t nat -A " + CHAIN_NAT + " -p udp --dport 53 -j REDIRECT --to-ports " + cfg.dnsPort
        out += "iptables -t nat -A " + CHAIN_NAT + " -p tcp --dport 53 -j REDIRECT --to-ports " + cfg.dnsPort
        for (n in PRIVATE_NETS) out += "iptables -t nat -A " + CHAIN_NAT + " -d " + n + " -j RETURN"
        out += "iptables -t nat -A " + CHAIN_NAT + " -p tcp -j REDIRECT --to-ports " + cfg.redirPort
        out += "iptables -t nat -I PREROUTING -i <iface> -j " + CHAIN_NAT
        if (cfg.proxyUdp) {
            out += "iptables -t mangle -A " + CHAIN_MANGLE + " -p udp -j TPROXY --on-port " + cfg.tproxyPort + " --tproxy-mark " + FWMARK
            out += "ip rule add fwmark " + FWMARK + " lookup " + TABLE_ID
            out += "ip route add local 0.0.0.0/0 dev lo table " + TABLE_ID
        }
        if (cfg.blockIpv6) out += "ip6tables -I FORWARD -i <iface> -j DROP"
        return out
    }
}
