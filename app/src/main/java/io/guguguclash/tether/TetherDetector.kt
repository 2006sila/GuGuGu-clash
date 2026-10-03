package io.guguguclash.tether

/**
 * 共享接口检测。解析 ip 命令输出为纯数据，便于 JVM 单测。
 * 思路参考 Mygod/VPNHotspot 的 net/TetheringManagerCompat.kt / TetherStates.kt（Apache-2.0）。
 */
object TetherDetector {

    data class Iface(val name: String, val ip: String, val prefix: Int)

    private val NAME_PREFIXES = listOf("ap", "wlan", "softap", "swlan", "rndis", "usb", "bt-pan", "eth")

    fun nameLooksTether(name: String): Boolean =
        NAME_PREFIXES.any { name.startsWith(it) }

    fun isPrivate(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4) return false
        val a = p[0].toIntOrNull() ?: return false
        val b = p[1].toIntOrNull() ?: return false
        return when {
            a == 10 -> true
            a == 192 && b == 168 -> true
            a == 172 && b in 16..31 -> true
            a == 100 && b in 64..127 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }

    /** 解析 ip -o -4 addr show 的输出 */
    fun parseAddrs(output: String): List<Iface> {
        val list = mutableListOf<Iface>()
        for (raw in output.lineSequence()) {
            val t = raw.trim().split(Regex("\\s+"))
            if (t.size < 4) continue
            val name = t[1].trimEnd(':')
            val cidr = t[3]
            val slash = cidr.indexOf('/')
            if (slash < 0) continue
            val ip = cidr.substring(0, slash)
            val prefix = cidr.substring(slash + 1).toIntOrNull() ?: continue
            list += Iface(name, ip, prefix)
        }
        return list
    }

    /**
     * 解析 ip route show table local_network 的输出，取共享接口。
     * 这是 AOSP 给 tethering 用的专用路由表，比按网段/命名猜准得多：
     * 实测某 ColorOS 机型热点网段是 10.61.80.0/24、网关是 .170 而不是 .1，
     * 而且主表没有默认路由（策略路由），靠启发式只能蒙。
     */
    fun parseLocalNetworkIfaces(output: String): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in output.lineSequence()) {
            val t = raw.trim().split(Regex("\\s+"))
            val i = t.indexOf("dev")
            if (i >= 0 && i + 1 < t.size) out += t[i + 1]
        }
        return out.toList()
    }

    /** 解析 ip route show default 的输出，取默认出口接口名 */
    fun parseDefaultDev(output: String): String? {
        for (raw in output.lineSequence()) {
            val t = raw.trim().split(Regex("\\s+"))
            if (t.isEmpty() || t[0] != "default") continue
            for (i in t.indices) {
                if (t[i] == "dev" && i + 1 < t.size) return t[i + 1]
            }
        }
        return null
    }

    /**
     * 选出承担共享网关角色的接口。
     * 优先级：手动覆盖 > 私有网段且以 .1 结尾的非默认接口 > 任意候选
     */
    fun detect(addrs: List<Iface>, defaultDev: String?, override: String? = null): String? {
        if (!override.isNullOrBlank()) return override
        var fallback: String? = null
        for (a in addrs) {
            if (a.name == "lo") continue
            if (a.name == defaultDev) continue
            if (!nameLooksTether(a.name)) continue
            if (!isPrivate(a.ip)) continue
            if (a.ip.endsWith(".1") && a.prefix == 24) return a.name
            if (fallback == null) fallback = a.name
        }
        return fallback
    }
}
