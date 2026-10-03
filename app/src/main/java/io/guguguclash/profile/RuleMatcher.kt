package io.guguguclash.profile

/**
 * 规则命中查询。
 *
 * 排障最高频的问题就是「这个域名到底走了哪条规则」。内核不会告诉你，
 * 只能自己按 rules 的先后顺序模拟一遍匹配。
 *
 * 能本地判定的：DOMAIN / DOMAIN-SUFFIX / DOMAIN-KEYWORD / IP-CIDR / IP-CIDR6 / MATCH
 * 判不了的：GEOSITE / RULE-SET / GEOIP / PROCESS-NAME / DST-PORT / NETWORK
 *   —— 这些要么依赖 geo 数据、要么依赖请求上下文（进程名、端口），
 *   一律标成「无法判定」并单独列出来，绝不猜。猜错比不答更糟。
 */
object RuleMatcher {

    data class Hit(
        val index: Int,
        val raw: String,
        val type: String,
        val value: String,
        val action: String,
        val reason: String
    )

    data class Result(
        val target: String,
        val isIp: Boolean,
        val hits: List<Hit>,
        /** 无法本地判定的规则条数（它们可能排在命中之前，所以结论并非绝对） */
        val undecidable: Int,
        /** 第一条无法判定的规则出现的位置，用于提示「在它之前是确定的」 */
        val firstUndecidableAt: Int?,
        val finalAction: String?
    )

    /** 只解析 rules: 块的每一行，容忍行内注释与引号 */
    fun parseRules(block: String): List<String> =
        block.lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim().trim('"', '\'') }
            .filter { it.contains(',') }

    fun match(rulesBlock: String, rawTarget: String): Result {
        val target = rawTarget.trim().lowercase().trimEnd('.')
        val isIp = isIpLiteral(target)
        val hits = mutableListOf<Hit>()
        var undecidable = 0
        var firstUndecidable: Int? = null
        var fallback: String? = null

        for ((i, line) in parseRules(rulesBlock).withIndex()) {
            val parts = line.split(',').map { it.trim() }
            if (parts.size < 2) continue
            val type = parts[0].uppercase()
            // 规则格式是 类型,值,动作[,修饰符]。动作固定在第 3 位 ——
            // 不能取 last()：像 IP-CIDR,10.0.0.0/8,DIRECT,no-resolve 的末尾是
            // no-resolve 这种修饰符，取 last() 会把动作读成 "no-resolve"。
            val value = if (parts.size >= 3) parts[1] else ""
            val action = if (parts.size >= 3) parts[2] else parts[1]

            when (type) {
                "MATCH" -> {
                    fallback = action
                    hits += Hit(i, line, type, "", action, "兜底规则，命中一切")
                    break
                }
                "DOMAIN" -> if (!isIp && target == value.lowercase()) {
                    hits += Hit(i, line, type, value, action, "域名完全相等")
                    break
                }
                "DOMAIN-SUFFIX" -> {
                    val v = value.lowercase().removePrefix(".").removePrefix("+")
                    if (!isIp && (target == v || target.endsWith("." + v))) {
                        hits += Hit(i, line, type, value, action, "域名以 " + v + " 结尾")
                        break
                    }
                }
                "DOMAIN-KEYWORD" -> if (!isIp && target.contains(value.lowercase())) {
                    hits += Hit(i, line, type, value, action, "域名含关键字 " + value)
                    break
                }
                "IP-CIDR", "IP-CIDR6" -> if (isIp && cidrContains(value, target)) {
                    hits += Hit(i, line, type, value, action, "IP 落在 " + value + " 内")
                    break
                }
                "GEOSITE", "RULE-SET", "GEOIP", "PROCESS-NAME", "PROCESS-PATH",
                "DST-PORT", "SRC-PORT", "NETWORK", "SRC-IP-CIDR", "IN-TYPE", "IN-USER", "IN-NAME" -> {
                    undecidable++
                    if (firstUndecidable == null) firstUndecidable = i
                }
                else -> {
                    undecidable++
                    if (firstUndecidable == null) firstUndecidable = i
                }
            }
        }

        // 最终动作 = 真正命中的那条规则的动作；只有一路走到 MATCH 兜底时才用 fallback。
        // 曾经的写法只在「命中项本身是 MATCH」时取值，于是命中真实规则时反而返回 null，
        // 界面上显示成「未命中任何规则」—— 明明上面刚列出命中了第 2381 条。
        val matched = hits.firstOrNull { it.type != "MATCH" }
        val finalAction = matched?.action ?: fallback
        return Result(rawTarget.trim(), isIp, hits, undecidable, firstUndecidable, finalAction)
    }

    fun isIpLiteral(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains(':')) return s.matches(Regex("[0-9a-fA-F:]{2,45}"))
        val p = s.split('.')
        if (p.size != 4) return false
        return p.all { it.isNotEmpty() && it.length <= 3 && it.toIntOrNull()?.let { v -> v in 0..255 } == true }
    }

    /** IPv4 CIDR 判定；IPv6 用前缀长度做大致的字符串前缀比较 */
    fun cidrContains(cidr: String, ip: String): Boolean {
        val slash = cidr.indexOf('/')
        val net = if (slash > 0) cidr.substring(0, slash) else cidr
        val bits = if (slash > 0) cidr.substring(slash + 1).toIntOrNull() ?: 32 else 32
        if (ip.contains(':') || net.contains(':')) return ip.startsWith(net)   // IPv6 只做粗判
        val a = toLong(net) ?: return false
        val b = toLong(ip) ?: return false
        if (bits <= 0) return true
        if (bits > 32) return false
        val mask = ((1L shl bits) - 1) shl (32 - bits)
        return (a and mask) == (b and mask)
    }

    private fun toLong(ip: String): Long? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) {
            val n = x.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }
}