package io.guguguclash.tether

import io.guguguclash.root.RootShell

/**
 * 紧急清理：把透明代理规则全部摘掉，让热点客户端立刻回到纯直连。
 *
 * 场景：出问题时电脑完全上不了网，而 App 的自动清理可能因为进程被强杀而没跑成。
 * 这个按钮不依赖任何状态判断，就是无条件把规则扫干净 —— 宁可多清，不可留着。
 *
 * 与 tproxy.sh 的 unapply 的区别：那个要读 HS_* 环境变量、要探测接口；
 * 这个把所有可能的共享接口都扫一遍，不猜、不问。
 */
object Emergency {

    /**
     * 候选名单：与 RuleBuilder / tproxy.sh 同一份（单一真源）。
     *
     * 接口此刻可能已经消失（热点关掉 / USB 拔掉 / 蓝牙断开），而规则仍然挂在那个接口上 ——
     * 这时只有「不依赖当前接口状态」的名单法能摘到。
     */
    private val STATIC_IFACES = RuleBuilder.CANDIDATE_IFACES

    /** 单个接口上的三处跳转（nat / mangle / FORWARD）＋ IPv6 阻断。 */
    private fun sweepOne(): String = buildString {
        append("  iptables -w 100 -t nat -D PREROUTING -i \"\$ifc\" -j HS_NAT 2>/dev/null\n")
        append("  iptables -w 100 -t mangle -D PREROUTING -i \"\$ifc\" -j HS_MANGLE 2>/dev/null\n")
        append("  iptables -w 100 -D FORWARD -i \"\$ifc\" -j HS_BLOCK 2>/dev/null\n")
        append("  ip6tables -w 100 -D FORWARD -i \"\$ifc\" -j DROP 2>/dev/null\n")
    }

    val SCRIPT: String = buildString {
        append("set +e\n")
        // ① 先按设备上真实存在的接口清一遍。动态枚举能覆盖 ap2 / swlan1 / wlan3 这类
        //    不在候选名单里的名字 —— 早期只扫固定名单，接口名对不上就摘不掉跳转，
        //    电脑会继续断网，而这正是这个按钮存在的意义。
        append("for ifc in \$(ip -o link show 2>/dev/null | awk -F': ' '{print \$2}' | cut -d'@' -f1 | grep -vw lo); do\n")
        append(sweepOne())
        append("done\n")
        // ② 再按候选名单清一遍：接口已经消失时，静态名单是唯一的补救。
        append("for ifc in ").append(STATIC_IFACES.joinToString(" ")).append("; do\n")
        append(sweepOne())
        append("done\n")
        // 再销毁自定义链
        append("iptables -w 100 -F HS_BLOCK 2>/dev/null; iptables -w 100 -X HS_BLOCK 2>/dev/null\n")
        append("iptables -w 100 -t nat -F HS_NAT 2>/dev/null; iptables -w 100 -t nat -X HS_NAT 2>/dev/null\n")
        append("iptables -w 100 -t mangle -F HS_MANGLE 2>/dev/null; iptables -w 100 -t mangle -X HS_MANGLE 2>/dev/null\n")
        // fwmark 策略路由：不清的话内核自己的出站流量仍会被送进这张表。
        // mark / 表号只从 RuleBuilder 取，避免和规则脚本各持一份常量。
        append("ip rule del fwmark ").append(RuleBuilder.FWMARK)
            .append(" lookup ").append(RuleBuilder.TABLE_ID).append(" 2>/dev/null\n")
        append("ip route flush table ").append(RuleBuilder.TABLE_ID).append(" 2>/dev/null\n")
        // 顺手停掉独立守护，否则它会以为出问题了反复清理
        append("pkill -f 'tproxy.sh watch' 2>/dev/null\n")
        append("echo ---REMAIN---\n")
        append("iptables -t nat -S PREROUTING 2>/dev/null\n")
        append("iptables -S FORWARD 2>/dev/null | head -n 5\n")
    }

    data class Result(val ok: Boolean, val remain: String, val error: String)

    fun run(): Result {
        val r = runCatching { RootShell.run(SCRIPT, timeoutSec = 25) }.getOrNull()
            ?: return Result(false, "", "无法执行 root 命令")
        val remain = r.out.substringAfter("---REMAIN---", "").trim()
        // 残留里还有 HS_ 就说明没清干净
        val ok = !remain.contains("HS_")
        return Result(ok, remain, r.err.trim())
    }
}
