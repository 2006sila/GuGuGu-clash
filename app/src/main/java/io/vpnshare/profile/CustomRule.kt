package io.vpnshare.profile

/**
 * 用户自定义规则。
 *
 * 存储用「每行一条」的纯文本，好处是编辑直观、无需 JSON、出错能精确定位到某一行。
 * 规则语法直接用内核原生语法，不做任何翻译：
 *
 *   DOMAIN-SUFFIX,example.com,PROXY
 *   DOMAIN-KEYWORD,github,PROXY
 *   IP-CIDR,1.2.3.0/24,DIRECT,no-resolve
 *   GEOSITE,netflix,PROXY
 *   PROCESS-NAME,com.tencent.mm,DIRECT
 *
 * 也支持简写：只写一个域名 -> 自动补成 DOMAIN-SUFFIX,<域名>,DIRECT
 *
 * 之所以要校验：内核遇到写错的规则会直接拒绝启动（配置文件测试失败），
 * 与其让用户面对「内核起不来」，不如在这里标出第几行错、错在哪。
 */
object CustomRule {

    val TYPES = listOf(
        "DOMAIN", "DOMAIN-SUFFIX", "DOMAIN-KEYWORD", "DOMAIN-REGEX",
        "GEOSITE", "GEOIP",
        "IP-CIDR", "IP-CIDR6", "IP-SUFFIX", "SRC-IP-CIDR",
        "PROCESS-NAME", "PROCESS-PATH",
        "DST-PORT", "SRC-PORT", "NETWORK", "RULE-SET"
    )

    val ACTIONS = listOf("DIRECT", "PROXY", "REJECT")

    private val FLAGS = listOf("no-resolve", "src")

    data class Parsed(val line: String?, val error: String?) {
        val ok: Boolean get() = line != null
    }

    fun parse(raw: String): Parsed {
        val s = raw.trim()
        if (s.isEmpty() || s.startsWith("#")) return Parsed(null, null)

        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        // 简写：只写一个域名/IP
        if (parts.size == 1) {
            val v = parts[0]
            if (v.any { it.isWhitespace() }) return Parsed(null, "含空格：「" + s + "」")
            val host = v.removePrefix("+.").removePrefix(".")
            if (host.isEmpty()) return Parsed(null, "内容为空：" + s)
            return Parsed("DOMAIN-SUFFIX," + host + ",DIRECT", null)
        }

        val type = parts[0].uppercase()
        if (type !in TYPES) {
            return Parsed(null, "未知类型「" + parts[0] + "」，可用：" + TYPES.joinToString("/"))
        }
        val value = parts[1]
        if (value.any { it.isWhitespace() }) return Parsed(null, "匹配值含空格：「" + value + "」")

        val action = parts.getOrNull(2)?.uppercase() ?: "DIRECT"
        if (action !in ACTIONS) {
            return Parsed(null, "动作必须是 " + ACTIONS.joinToString("/") + "，收到「" + parts[2] + "」")
        }

        val flags = parts.drop(3).map { it.lowercase() }
        for (f in flags) {
            if (f !in FLAGS) {
                return Parsed(null, "不支持的附加参数「" + f + "」，可用：" + FLAGS.joinToString("/"))
            }
        }

        val sb = StringBuilder()
        sb.append(type).append(',').append(value).append(',').append(action)
        for (f in flags) sb.append(',').append(f)
        return Parsed(sb.toString(), null)
    }

    data class Result(val lines: List<String>, val errors: List<String>)

    fun parseAll(text: String): Result {
        val lines = ArrayList<String>()
        val errors = ArrayList<String>()
        val raw = text.split("\n")
        for (i in raw.indices) {
            val r = parse(raw[i])
            if (r.line != null) lines.add(r.line)
            else if (r.error != null) errors.add("第 " + (i + 1) + " 行：" + r.error)
        }
        return Result(lines, errors)
    }

    /**
     * 把「直连域名」并入自定义规则文本。
     *
     * 简写一条域名会被 parse 补成 DOMAIN-SUFFIX,<域名>,DIRECT —— 与旧「直连域名」生成的规则**完全一致**，
     * 所以直接追加域名本身即可（可读性也最好）。已存在同名规则时跳过，因此可以安全地反复调用。
     */
    fun mergeDirectDomains(existingText: String, domains: List<String>): String {
        val lines = parseAll(existingText).lines.toMutableList()
        val seen = lines.map { it.lowercase() }.toMutableSet()
        for (raw in domains) {
            val d = raw.trim().removePrefix("+.").removePrefix(".").lowercase()
            if (d.isEmpty() || d.any { it.isWhitespace() }) continue
            val line = "DOMAIN-SUFFIX," + d + ",DIRECT"
            // 去重要按小写比：seen 里存的是已有规则的小写形式，
            // 而 line 是混合大小写（DOMAIN-SUFFIX/DIRECT），直接 add 会比不中、产生重复规则
            if (seen.add(line.lowercase())) lines.add(line)
        }
        return lines.joinToString("\n")
    }

    /** 内置分类的追加域名。存储格式：每行「分类key=域名1;域名2」 */
    fun parseExtraDomains(text: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (line in text.split("\n")) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val eq = s.indexOf('=')
            if (eq <= 0) continue
            val key = s.substring(0, eq).trim()
            if (key.isEmpty()) continue
            for (piece in s.substring(eq + 1).split(';', ',', ' ')) {
                val d = piece.trim().removePrefix("+.").removePrefix(".").lowercase()
                if (d.isEmpty() || d.any { it.isWhitespace() }) continue
                out.getOrPut(key) { ArrayList() }.add(d)
            }
        }
        return out
    }

    /** 把追加域名还原成可编辑文本（只输出内置分类里存在的 key） */
    fun extraDomainsText(map: Map<String, List<String>>): String {
        val sb = StringBuilder()
        for (cat in RuleCatalog.ALL) {
            val v = map[cat.key] ?: continue
            if (v.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(cat.key).append('=').append(v.joinToString(";"))
        }
        return sb.toString()
    }

    // ---------------- 给「规则」页用的简化接口 ----------------
    // 目标：用户只填「一个域名 / 一个 IP 段 / 一个关键字」，再选「直连 / 走代理 / 拦截」，
    // 匹配方式由程序自动判断，不需要记内核语法。

    data class Simple(val raw: String, val value: String, val action: String, val kind: String)

    /** 把一行规则拆成界面能显示的样子 */
    fun describe(raw: String): Simple {
        val s = raw.trim()
        val parts = s.split(",").map { it.trim() }
        if (parts.size >= 3) {
            val type = parts[0].uppercase()
            val v = parts[1]
            val a = parts[2].uppercase()
            return Simple(s, v, a, kindLabel(type))
        }
        // 简写形式
        val host = s.removePrefix("+.").removePrefix(".")
        return Simple(s, host, "DIRECT", "域名后缀")
    }

    fun kindLabel(type: String): String = when (type.uppercase()) {
        "DOMAIN" -> "完整域名"
        "DOMAIN-SUFFIX" -> "域名后缀"
        "DOMAIN-KEYWORD" -> "域名关键字"
        "DOMAIN-REGEX" -> "域名正则"
        "IP-CIDR", "IP-CIDR6" -> "IP 段"
        "IP-SUFFIX" -> "IP 前缀"
        "SRC-IP-CIDR" -> "来源 IP 段"
        "GEOSITE" -> "内置域名分类"
        "GEOIP" -> "IP 归属地"
        "PROCESS-NAME" -> "应用包名"
        "PROCESS-PATH" -> "应用路径"
        "DST-PORT" -> "目标端口"
        "SRC-PORT" -> "来源端口"
        "NETWORK" -> "网络类型"
        "RULE-SET" -> "规则集"
        else -> type
    }

    fun actionLabel(action: String): String = when (action.uppercase()) {
        "DIRECT" -> "直连"
        "PROXY" -> "走代理"
        "REJECT" -> "拦截"
        else -> action
    }

    /**
     * 由「内容 + 动作」生成一条规则。
     * 内容里带逗号时视为用户直接写了完整规则，原样校验后使用（留给进阶用户）。
     */
    fun build(value: String, action: String): Parsed {
        val v = value.trim()
        if (v.isEmpty()) return Parsed(null, "内容不能为空")
        if (v.contains(',')) return parse(v)

        val a = action.uppercase()
        if (a !in ACTIONS) return Parsed(null, "动作必须是 " + ACTIONS.joinToString("/"))

        val body = v.replace(" ", "")
        if (body.contains('/')) return parse("IP-CIDR," + body + "," + a + ",no-resolve")
        val host = body.removePrefix("+.").removePrefix(".")
        if (host.isEmpty()) return Parsed(null, "内容不能为空")
        return if (host.contains('.')) parse("DOMAIN-SUFFIX," + host + "," + a)
        else parse("DOMAIN-KEYWORD," + host + "," + a)
    }
}
