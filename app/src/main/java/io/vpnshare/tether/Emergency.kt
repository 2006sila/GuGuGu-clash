package io.vpnshare.tether

import io.vpnshare.root.RootShell

/**
 * 紧急清理：把透明代理规则全部摘掉，让热点客户端立刻回到纯直连。
 *
 * 场景：出问题时电脑完全上不了网，而 App 的自动清理可能因为进程被强杀而没跑成。
 * 这个按钮不依赖任何状态判断，就是无条件把规则扫干净 —— 宁可多清，不可留着。
 *
 * 与 tproxy.sh 的 unapply 的区别：那个要读 HS_* 环境变量、要探测接口；
 * 这个把设备上**所有可能的共享接口**都扫一遍，不猜、不问。
 */
object Emergency {

    /** 共享接口候选。热点、USB 共享、蓝牙共享、以太网全覆盖，不依赖检测结果。 */
    private val IFACES = listOf(
        "wlan0", "wlan1", "wlan2", "ap0", "ap1", "softap0",
        "swlan0", "rndis0", "usb0", "usb1", "eth0", "bt-pan"
    )

    val SCRIPT: String = buildString {
        append("set +e\n")
        append("for ifc in ").append(IFACES.joinToString(" ")).append("; do\n")
        // 逐接口摘跳转。规则可能挂在 nat/mangle/FORWARD 三处，都要摘。
        append("  iptables -w 5 -t nat -D PREROUTING -i \$ifc -j HS_NAT 2>/dev/null\n")
        append("  iptables -w 5 -t mangle -D PREROUTING -i \$ifc -j HS_MANGLE 2>/dev/null\n")
        append("  iptables -w 5 -D FORWARD -i \$ifc -j HS_BLOCK 2>/dev/null\n")
        append("  ip6tables -D FORWARD -i \$ifc -j DROP 2>/dev/null\n")
        append("done\n")
        // 再销毁自定义链
        append("iptables -w 5 -F HS_BLOCK 2>/dev/null; iptables -w 5 -X HS_BLOCK 2>/dev/null\n")
        append("iptables -w 5 -t nat -F HS_NAT 2>/dev/null; iptables -w 5 -t nat -X HS_NAT 2>/dev/null\n")
        append("iptables -w 5 -t mangle -F HS_MANGLE 2>/dev/null; iptables -w 5 -t mangle -X HS_MANGLE 2>/dev/null\n")
        // fwmark 策略路由：不清的话内核自己的出站流量仍会被送进 table 100
        append("ip rule del fwmark 2025 lookup 100 2>/dev/null\n")
        append("ip route flush table 100 2>/dev/null\n")
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