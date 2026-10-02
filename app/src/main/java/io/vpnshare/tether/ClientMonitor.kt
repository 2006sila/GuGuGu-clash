package io.vpnshare.tether

import io.vpnshare.root.RootShell

/**
 * 热点客户端监控。
 *
 * 走 ip neigh（netlink 邻居表）而不是 netlink 事件监听：VPNHotspot 用 netlink 是为了
 * 无 root 场景下的实时上下线事件；我们始终有 root，轮询邻居表足够且实现短得多。
 * 屏蔽走 HS_BLOCK 链，该链由 tproxy.sh 创建与销毁，停用共享时一并清理。
 */
object ClientMonitor {

    data class Client(
        val ip: String,
        val mac: String,
        val iface: String,
        val state: String
    ) {
        /** 网关自身不是客户端 */
        val isGatewayLike: Boolean get() = ip.endsWith(".1")
    }

    /** 读取指定接口下的邻居；iface 为空则读取全部接口。 */
    fun list(iface: String? = null): List<Client> {
        val cmd = if (iface.isNullOrBlank()) "ip neigh show 2>/dev/null"
        else "ip neigh show dev " + iface + " 2>/dev/null"
        val r = RootShell.run(cmd, timeoutSec = 10)
        val out = r.out.ifBlank { arpFallback() }
        // 指定 iface 时必须把接口名传下去：ip neigh show dev X 的输出里**不含** dev 字段
        return parse(out, iface).filter { !it.isGatewayLike && it.mac != "00:00:00:00:00:00" }
    }

    /**
     * 兼容三种输出：
     *   192.168.43.100 dev ap0 lladdr aa:bb:cc:dd:ee:ff REACHABLE   ← ip neigh show
     *   192.168.43.100 lladdr aa:bb:cc:dd:ee:ff REACHABLE            ← ip neigh show dev ap0
     *   192.168.43.100  0x1  0x2  aa:bb:cc:dd:ee:ff  *  ap0         ← /proc/net/arp
     *
     * 第二种最容易踩：ip neigh show dev X 的输出里**不打印 dev 字段**（只有 4 个 token），
     * 早期解析器要求 dev 与 lladdr 同时出现、又要求 ≥6 个 token 才当 ARP 表处理，
     * 于是指定接口时每一行都被丢掉 —— 表现为「已连设备」页永远显示「暂无设备」。
     * 所以接口名要从调用方回传（[ifaceFallback]）。
     */
    fun parse(raw: String, ifaceFallback: String? = null): List<Client> {
        val out = LinkedHashMap<String, Client>()
        for (line in raw.lineSequence()) {
            val t = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (t.size < 3) continue
            val ip = t[0]
            if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(ip)) continue

            var mac = ""
            var iface = ""
            var state = ""
            val devIdx = t.indexOf("dev")
            val llIdx = t.indexOf("lladdr")
            if (llIdx >= 0 && devIdx >= 0 && devIdx + 1 < t.size && llIdx + 1 < t.size) {
                // ip neigh show：带 dev
                iface = t[devIdx + 1]
                mac = t[llIdx + 1]
                state = t.getOrElse(llIdx + 2) { "" }
            } else if (llIdx >= 0 && llIdx + 1 < t.size) {
                // ip neigh show dev X：不带 dev，接口名由调用方给出
                iface = ifaceFallback ?: ""
                mac = t[llIdx + 1]
                state = t.getOrElse(llIdx + 2) { "" }
            } else if (t.size >= 6) {
                // /proc/net/arp
                mac = t[3]
                iface = t[5]
                state = "ARP"
            }
            if (mac.isEmpty()) continue
            out[ip] = Client(ip, mac.lowercase(), iface, state.uppercase())
        }
        return out.values.toList()
    }

    private fun arpFallback(): String = RootShell.run("cat /proc/net/arp 2>/dev/null").out

    // ---------------- 屏蔽 ----------------

    fun blocked(): Set<String> {
        val r = RootShell.run("iptables -S " + RuleBuilder.CHAIN_BLOCK + " 2>/dev/null")
        return r.out.lineSequence()
            .mapNotNull { line ->
                Regex("-s (\\d{1,3}(?:\\.\\d{1,3}){3})").find(line)?.groupValues?.get(1)
            }
            .toSet()
    }

    fun block(ip: String): RootShell.Result = RootShell.run(
        "iptables -N " + RuleBuilder.CHAIN_BLOCK + " 2>/dev/null; " +
            "iptables -C " + RuleBuilder.CHAIN_BLOCK + " -s " + ip + " -j DROP 2>/dev/null || " +
            "iptables -A " + RuleBuilder.CHAIN_BLOCK + " -s " + ip + " -j DROP; echo ok"
    )

    fun unblock(ip: String): RootShell.Result = RootShell.run(
        "while iptables -D " + RuleBuilder.CHAIN_BLOCK + " -s " + ip + " -j DROP 2>/dev/null; do :; done; echo ok"
    )
}
