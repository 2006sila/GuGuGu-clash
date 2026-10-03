package io.guguguclash.profile

/**
 * 主配置合成。纯函数，无 Android 依赖，便于 JVM 单测。
 *
 * 硬规则（对应计划 D4）：订阅内容不得覆盖 tun / redir-port / tproxy-port / dns / geox-url。
 * 这些段只存在于 assets/base.template.yaml 中，本类只追加 proxy-providers / proxy-groups / rules。
 */
object ConfigBuilder {

    const val PROVIDER_NAME = "sub"
    const val GROUP_NAME = "PROXY"

    // ==================== 选项（对齐 CMFA 的设置面，键名均已在内核二进制中核实）====================

    /** DNS 全套。fallback 默认留空是有原因的：国内连不上 1.1.1.1/8.8.8.8，
     *  一旦配上且 fallback-filter 命中，节点域名解析就会超时。 */
    data class DnsOptions(
        val enhancedMode: String = "fake-ip",
        val listen: String = "0.0.0.0:1053",
        val ipv6: Boolean = false,
        val defaultNameserver: List<String> = listOf("223.5.5.5", "119.29.29.29"),
        val nameserver: List<String> = listOf(
            "https://223.5.5.5/dns-query", "https://1.12.12.12/dns-query", "https://doh.pub/dns-query"
        ),
        val fallback: List<String> = emptyList(),
        val fallbackGeoip: Boolean = false,
        val fallbackGeoipCode: String = "CN",
        val fallbackIpcidr: List<String> = emptyList(),
        val fakeIpFilter: List<String> = listOf("*.lan", "+.local", "*.localdomain"),
        val hijack: Boolean = true,
        val useHosts: Boolean = true,
        val useSystemHosts: Boolean = false,
        val respectRules: Boolean = false,
        val preferH3: Boolean = false,
        /** blacklist | whitelist | rule */
        val fakeIpFilterMode: String = "blacklist",
        /** 域名 -> DNS 服务器（内核的 nameserver-policy） */
        val nameServerPolicy: Map<String, String> = emptyMap(),
        /** 命中这些域名时采用 fallback 的结果 */
        val domainFallback: List<String> = emptyList(),
        val appendSystemDns: Boolean = false
    )

    /** tun 段。include/exclude-package 就是「应用分流」的落点。 */
    data class TunOptions(
        val stack: String = "mixed",
        val routingMark: Int = 2024,
        val includePackage: List<String> = emptyList(),
        val excludePackage: List<String> = emptyList()
    )

    /** 顶层与嗅探覆写 */
    data class OverrideOptions(
        val mode: String = "rule",
        val logLevel: String = "info",
        val httpPort: Int = 0,
        val socksPort: Int = 0,
        val authentication: List<String> = emptyList(),
        val extAllowOrigins: List<String> = listOf("*"),
        val extAllowPrivateNetwork: Boolean = true,
        val geoxGeoip: String = "",
        val geoxMmdb: String = "",
        val geoxGeosite: String = "",
        val geoxAsn: String = "",
        val bindAddress: String = "*",
        val allowLan: Boolean = true,
        val mixedPort: Int = 7890,
        val redirPort: Int = 7892,
        val tproxyPort: Int = 7893,
        val externalController: String = "127.0.0.1:9090",
        val secret: String = "",
        val tcpConcurrent: Boolean = true,
        val unifiedDelay: Boolean = false,
        val findProcessMode: String = "off",
        val geodataMode: Boolean = false,
        val sniffEnable: Boolean = false,
        val sniffOverrideDest: Boolean = true,
        val sniffHttpPorts: List<String> = listOf("80", "8080-8880"),
        val sniffTlsPorts: List<String> = listOf("443", "8443"),
        val sniffQuicPorts: List<String> = listOf("443", "8443"),
        val sniffParsePureIp: Boolean = true,
        val sniffForceDomain: List<String> = emptyList(),
        val sniffSkipDomain: List<String> = emptyList(),
        val sniffSkipSrc: List<String> = emptyList(),
        val sniffSkipDst: List<String> = emptyList()
    )

    data class Options(
        val providerPath: String = "./providers/sub.yaml",
        val enabled: Map<String, RuleAction> = RuleCatalog.DEFAULT_ACTIONS,
        val customDirectDomains: List<String> = emptyList(),
        /** 用户自定义规则（已校验过），优先级最高 */
        val customRules: List<String> = emptyList(),
        /** 给内置分类追加的域名：分类key -> 域名列表 */
        val extraDomains: Map<String, List<String>> = emptyMap(),
        /** true = 启用 tun，让手机自身流量也走代理（会改默认路由，影响热点客户端） */
        val proxyOwnTraffic: Boolean = false,
        val localRulesetsPresent: Boolean = false,
        val adoptSubscriptionGroups: Boolean = false,
        val adoptSubscriptionRules: Boolean = false,
        val subscriptionYaml: String? = null,
        val dns: DnsOptions = DnsOptions(),
        val tun: TunOptions = TunOptions(),
        val over: OverrideOptions = OverrideOptions()
    )

    fun build(template: String, opts: Options): String {
        // 所有受控段（端口/DNS/tun/嗅探/外部控制）都由 applyOverrides 重新生成，
        // 订阅内容一律不得覆盖它们。
        val head = applyOverrides(cutAtRules(template), opts)
        val sb = StringBuilder()
        sb.append(head.trimEnd()).append("\n\n")
        if (opts.localRulesetsPresent) {
            sb.append(ruleProvidersBlock()).append('\n')
        }
        sb.append(providersBlock(opts)).append('\n')
        sb.append(groupsBlock(opts)).append('\n')
        sb.append(rulesBlock(opts))
        return sb.toString()
    }

    /** 规则段之前的全部内容（tun/dns/geo 等受控段都在这里） */
    private fun cutAtRules(template: String): String {
        val lines = template.lines()
        val idx = lines.indexOfFirst { it.trim() == "rules:" }
        return if (idx >= 0) lines.subList(0, idx).joinToString("\n") else template
    }

    fun providersBlock(opts: Options): String = buildString {
        append("proxy-providers:\n")
        append("  ").append(PROVIDER_NAME).append(":\n")
        append("    type: file\n")
        append("    path: ").append(opts.providerPath).append('\n')
        append("    health-check: { enable: true, url: \"https://www.gstatic.com/generate_204\", interval: 300 }\n")
    }

    fun groupsBlock(opts: Options): String {
        val adopted = if (opts.adoptSubscriptionGroups && opts.subscriptionYaml != null)
            extractTopLevelBlock(opts.subscriptionYaml, "proxy-groups") else null
        if (adopted != null && adopted.isNotBlank()) {
            return "# 以下策略组来自订阅（用户已开启「采纳订阅自带的策略组」）\n" + adopted.trimEnd() + "\n"
        }
        return buildString {
            append("proxy-groups:\n")
            append("  - { name: ").append(GROUP_NAME).append(", type: select, use: [")
                .append(PROVIDER_NAME).append("] }\n")
        }
    }

    fun rulesBlock(opts: Options): String = buildString {
        append("rules:\n")
        for (r in ruleLines(opts)) append(r).append('\n')
    }

    fun ruleLines(opts: Options): List<String> {
        val out = mutableListOf<String>()

        // 0) 用户自定义规则优先级最高 —— 用户明确写下的意图应当压过内置分类。
        for (r in opts.customRules) {
            out += "  - " + r
        }

        // 1) 自定义直连域名
        for (d in opts.customDirectDomains.map { it.trim() }.filter { it.isNotEmpty() }) {
            out += "  - DOMAIN-SUFFIX," + d.removePrefix("+.").removePrefix(".") + ",DIRECT"
        }

        // 2) 内置分类，顺序即优先级
        for (cat in RuleCatalog.ALL) {
            val action = opts.enabled[cat.key] ?: continue
            if (action == RuleAction.PROXY && cat.alsoGeoIpCn) {
                // 中国大陆整体走代理时不追加 GEOIP 直连
            }
            val line = if (opts.localRulesetsPresent && cat.localRuleset != null) {
                "  - RULE-SET," + cat.key + "-local," + action.name
            } else {
                "  - GEOSITE," + cat.geosite + "," + action.name
            }
            out += line
            // 用户为该分类追加的域名：紧跟在其 RULE-SET 之后，动作跟随该分类，
            // 这样「编辑内置规则」不需要改动内核数据文件（那会在更新时被覆盖）。
            for (d in opts.extraDomains[cat.key].orEmpty()) {
                out += "  - DOMAIN-SUFFIX," + d + "," + action.name
            }
        }

        // 3) 可选追加订阅自带规则。
        // 必须排在兜底 MATCH 之前，否则永远不可达（兜底会先把一切吃掉）。
        if (opts.adoptSubscriptionRules && opts.subscriptionYaml != null) {
            val block = extractTopLevelBlock(opts.subscriptionYaml, "rules")
            if (block != null && block.isNotBlank()) {
                for (raw in block.lines().drop(1)) {
                    // 先剥掉列表符号再判断，否则 "- MATCH,PROXY" 逃过跳过检查
                    val l = raw.trim().removePrefix("-").trim()
                    if (l.isEmpty()) continue
                    if (l.startsWith("MATCH,")) continue
                    out += "  - " + l
                }
            }
        }

        // 4) 纯 IP 连接按国籍判国内。
        // 必须带 no-resolve：不带的话内核为了匹配这条会去解析域名，
        // 而本地 DNS 被污染，境外域名会解析成 CN 段 IP → 被误判成国内直连 → 网站上不去。
        // 带 no-resolve 后只有「本来就拿着 IP」的连接才查国籍（这类多为国内 CDN / 硬编码 IP），
        // 带域名的连接一律跳过此条，交给上面的域名规则与兜底 MATCH。
        if (opts.enabled["cn"] == RuleAction.DIRECT) {
            out += "  - GEOIP,CN,DIRECT,no-resolve"
        }

        // 5) 兜底永远最后
        out += "  - MATCH," + GROUP_NAME
        return out
    }

    /** 若使用本地规则集，需要同时输出 rule-providers 段 */
    fun ruleProvidersBlock(): String = buildString {
        append("rule-providers:\n")
        for (cat in RuleCatalog.ALL) {
            val file = cat.localRuleset ?: continue
            append("  ").append(cat.key).append("-local:\n")
            append("    type: file\n")
            append("    behavior: domain\n")
            append("    format: yaml\n")
            append("    path: ./ruleset/").append(file).append('\n')
        }
    }

    /** 抽取 YAML 顶层块（key: 到下一个顶层键之前），不做完整 YAML 解析 */
    fun extractTopLevelBlock(yaml: String, key: String): String? {
        val lines = yaml.lines()
        var start = -1
        for (i in lines.indices) {
            val l = lines[i]
            if (l == l.trimStart() && l.trimStart().startsWith(key + ":")) { start = i; break }
        }
        if (start < 0) return null
        val out = mutableListOf(lines[start])
        for (i in start + 1 until lines.size) {
            val l = lines[i]
            val isTop = l.isNotEmpty() && l == l.trimStart() && !l.startsWith("#")
            if (isTop) break
            out += l
        }
        return out.joinToString("\n")
    }

    // ==================== 受控段生成 ====================

    /**
     * 按选项重写受控段。只认我们生成的键，订阅自带内容在这之前已被切掉。
     * 之所以要重写而不是往模板里塞变量：模板是给人看的，选项是给用户调的，
     * 两者混在一起改起来必错。
     */
    fun applyOverrides(head: String, opts: Options): String {
        var t = head
        val o = opts.over
        t = setScalar(t, "mixed-port", o.mixedPort.toString())
        t = setScalar(t, "redir-port", o.redirPort.toString())
        t = setScalar(t, "tproxy-port", o.tproxyPort.toString())
        t = setScalar(t, "allow-lan", o.allowLan.toString())
        t = setScalar(t, "bind-address", "\"" + o.bindAddress + "\"")
        t = setScalar(t, "external-controller", o.externalController)
        t = setScalar(t, "tcp-concurrent", o.tcpConcurrent.toString())
        t = setScalar(t, "unified-delay", o.unifiedDelay.toString())
        t = setScalar(t, "find-process-mode", o.findProcessMode)
        t = setScalar(t, "geodata-mode", o.geodataMode.toString())
        t = setScalar(t, "mode", o.mode)
        t = setScalar(t, "log-level", o.logLevel)
        // mihomo 的键名：HTTP 代理是 port，其余照旧。0 = 不监听。
        t = setScalar(t, "port", o.httpPort.toString())
        t = setScalar(t, "socks-port", o.socksPort.toString())
        if (o.authentication.isNotEmpty()) {
            t = replaceBlock(t, "authentication", "authentication:\n" +
                o.authentication.joinToString("\n") { "  - " + it })
        }
        if (o.geoxGeoip.isNotBlank() || o.geoxMmdb.isNotBlank() ||
            o.geoxGeosite.isNotBlank() || o.geoxAsn.isNotBlank()
        ) {
            val sb = StringBuilder("geox-url:\n")
            if (o.geoxGeoip.isNotBlank()) sb.append("  geoip: \"").append(o.geoxGeoip).append("\"\n")
            if (o.geoxMmdb.isNotBlank()) sb.append("  mmdb: \"").append(o.geoxMmdb).append("\"\n")
            if (o.geoxGeosite.isNotBlank()) sb.append("  geosite: \"").append(o.geoxGeosite).append("\"\n")
            if (o.geoxAsn.isNotBlank()) sb.append("  asn: \"").append(o.geoxAsn).append("\"\n")
            t = replaceBlock(t, "geox-url", sb.toString().trimEnd())
        }
        t = replaceBlock(t, "external-controller-cors", "external-controller-cors:\n" +
            "  allow-origins: [" + o.extAllowOrigins.joinToString(", ") { "\"" + it + "\"" } + "]\n" +
            "  allow-private-network: " + o.extAllowPrivateNetwork)
        if (o.secret.isNotBlank()) {
            t = when {
                t.lines().any { it.startsWith("secret:") } -> setScalar(t, "secret", o.secret)
                t.contains("external-controller-cors:") ->
                    t.replace("external-controller-cors:", "secret: " + o.secret + "\nexternal-controller-cors:")
                // 模板里没有 cors 段时，退一步插在 external-controller 后面
                else -> t.replace(
                    Regex("(?m)^(external-controller:.*)$"),
                    "$1\nsecret: " + o.secret
                )
            }
        } else {
            t = t.lines().filterNot { it.startsWith("secret:") }.joinToString("\n")
        }
        // patchProfile（对齐 CMFA）：让用户选的节点在重启后保留。
        // 机场配置通常不带这一段，缺了它内核每次都回落到「第一个候选」（往往是 DIRECT），
        // 表现为「共享开着但电脑还是直连」。
        t = replaceBlock(t, "profile", profileBlock())
        t = replaceBlock(t, "dns", dnsBlock(opts.dns))
        t = replaceBlock(t, "tun", tunBlock(opts))
        t = if (o.sniffEnable) {
            if (t.lines().any { it.startsWith("sniffer:") }) replaceBlock(t, "sniffer", snifferBlock(o))
            else t.replace("\ndns:", "\n" + snifferBlock(o) + "\n\ndns:")
        } else {
            t.lines().filterNot { it.startsWith("sniffer:") }.joinToString("\n")
        }
        return t
    }

    /** 设置顶层标量键。受控段的语义是「一律以选项为准」，所以键不存在时要补上，而不是忽略。 */
    private fun setScalar(text: String, key: String, value: String): String {
        var found = false
        val out = text.lines().joinToString("\n") { l ->
            if (l.startsWith(key + ":") && !l.startsWith(" ") && !l.startsWith("#")) {
                found = true
                key + ": " + value
            } else l
        }
        return if (found) out else out.trimEnd() + "\n" + key + ": " + value
    }

    /** 替换顶层块：从 "key:" 行起，吃掉其后所有缩进行 */
    private fun replaceBlock(text: String, key: String, block: String): String {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith(key + ":") && !it.startsWith(" ") && !it.startsWith("#") }
        if (start < 0) return text.trimEnd() + "\n" + block + "\n"
        var end = start + 1
        while (end < lines.size && (lines[end].startsWith(" ") || lines[end].startsWith("\t"))) end++
        val out = ArrayList<String>()
        out.addAll(lines.subList(0, start))
        out.addAll(block.lines())
        out.addAll(lines.subList(end, lines.size))
        return out.joinToString("\n")
    }

    /** 选择持久化。store-selected 记住手动选的节点，store-fake-ip 记住 fake-ip 映射。 */
    fun profileBlock(): String =
        "profile:\n  store-selected: true\n  store-fake-ip: true"

    fun dnsBlock(o: DnsOptions): String {
        val sb = StringBuilder("dns:\n")
        sb.append("  enable: true\n")
        sb.append("  listen: ").append(o.listen).append('\n')
        sb.append("  ipv6: ").append(o.ipv6).append('\n')
        sb.append("  enhanced-mode: ").append(o.enhancedMode).append('\n')
        if (o.enhancedMode == "fake-ip") {
            sb.append("  fake-ip-range: 198.18.0.1/16\n")
            if (o.fakeIpFilter.isNotEmpty()) {
                sb.append("  fake-ip-filter:\n")
                o.fakeIpFilter.forEach { sb.append("    - \"").append(it).append("\"\n") }
            }
        }
        sb.append("  use-hosts: ").append(o.useHosts).append('\n')
        sb.append("  use-system-hosts: ").append(o.useSystemHosts).append('\n')
        if (o.enhancedMode == "fake-ip" && o.fakeIpFilterMode != "blacklist") {
            // 内核支持三种语义：黑名单（默认，表内不做 fake-ip）、白名单（只有表内做）、
            // 规则模式（交给 rules 判断）。不填就是黑名单。
            sb.append("  fake-ip-filter-mode: ").append(o.fakeIpFilterMode).append('\n')
        }
        if (o.nameServerPolicy.isNotEmpty()) {
            // 内核的 nameserver-policy 是 map：{'*.google.com': 8.8.8.8}
            sb.append("  nameserver-policy: { ")
            sb.append(o.nameServerPolicy.entries.joinToString(", ") { "'" + it.key + "': " + it.value })
            sb.append(" }\n")
        }
        if (o.appendSystemDns) sb.append("  use-system-hosts: true\n")
        if (o.respectRules) sb.append("  respect-rules: true\n")
        if (o.preferH3) sb.append("  prefer-h3: true\n")
        if (o.defaultNameserver.isNotEmpty()) {
            sb.append("  default-nameserver:\n")
            o.defaultNameserver.forEach { sb.append("    - ").append(it).append('\n') }
        }
        if (o.nameserver.isNotEmpty()) {
            sb.append("  nameserver:\n")
            o.nameserver.forEach { sb.append("    - ").append(it).append('\n') }
        }
        if (o.fallback.isNotEmpty()) {
            sb.append("  fallback:\n")
            o.fallback.forEach { sb.append("    - ").append(it).append('\n') }
        }
        if (o.fallback.isNotEmpty() && (o.fallbackGeoip || o.fallbackIpcidr.isNotEmpty() || o.domainFallback.isNotEmpty())) {
            sb.append("  fallback-filter:\n")
            sb.append("    geoip: ").append(o.fallbackGeoip).append('\n')
            if (o.fallbackGeoip) sb.append("    geoip-code: ").append(o.fallbackGeoipCode).append('\n')
            if (o.fallbackIpcidr.isNotEmpty()) {
                sb.append("    ipcidr:\n")
                o.fallbackIpcidr.forEach { sb.append("      - ").append(it).append('\n') }
            }
            if (o.domainFallback.isNotEmpty()) {
                sb.append("    domain:\n")
                o.domainFallback.forEach { sb.append("      - ").append(it).append('\n') }
            }
        }
        return sb.toString().trimEnd()
    }

    fun tunBlock(opts: Options): String {
        val t = opts.tun
        val sb = StringBuilder("tun:\n")
        sb.append("  enable: ").append(opts.proxyOwnTraffic).append('\n')
        sb.append("  stack: ").append(t.stack).append('\n')
        sb.append("  auto-route: true\n")
        sb.append("  auto-detect-interface: true\n")
        sb.append("  routing-mark: ").append(t.routingMark).append('\n')
        if (opts.dns.hijack) sb.append("  dns-hijack:\n    - any:53\n")
        if (t.includePackage.isNotEmpty()) {
            sb.append("  include-package:\n")
            t.includePackage.forEach { sb.append("    - ").append(it).append('\n') }
        }
        if (t.excludePackage.isNotEmpty()) {
            sb.append("  exclude-package:\n")
            t.excludePackage.forEach { sb.append("    - ").append(it).append('\n') }
        }
        return sb.toString().trimEnd()
    }

    fun snifferBlock(o: OverrideOptions): String {
        val sb = StringBuilder("sniffer:\n")
        sb.append("  enable: true\n")
        sb.append("  override-destination: ").append(o.sniffOverrideDest).append('\n')
        sb.append("  parse-pure-ip: ").append(o.sniffParsePureIp).append('\n')
        if (o.sniffForceDomain.isNotEmpty()) {
            sb.append("  force-domain:\n")
            o.sniffForceDomain.forEach { sb.append("    - ").append(it).append('\n') }
        }
        if (o.sniffSkipDomain.isNotEmpty()) {
            sb.append("  skip-domain:\n")
            o.sniffSkipDomain.forEach { sb.append("    - ").append(it).append('\n') }
        }
        if (o.sniffSkipSrc.isNotEmpty()) {
            sb.append("  skip-src-address:\n")
            o.sniffSkipSrc.forEach { sb.append("    - ").append(it).append('\n') }
        }
        if (o.sniffSkipDst.isNotEmpty()) {
            sb.append("  skip-dst-address:\n")
            o.sniffSkipDst.forEach { sb.append("    - ").append(it).append('\n') }
        }
        sb.append("  sniff:\n")
        sb.append("    HTTP:\n      ports: [").append(o.sniffHttpPorts.joinToString(", ")).append("]\n")
        sb.append("    TLS:\n      ports: [").append(o.sniffTlsPorts.joinToString(", ")).append("]\n")
        sb.append("    QUIC:\n      ports: [").append(o.sniffQuicPorts.joinToString(", ")).append("]\n")
        return sb.toString().trimEnd()
    }

    // ==================== 采纳模式（对齐 CMFA 的 processors 链）====================

    /**
     * 以订阅原文为底，只打补丁，不重建。
     *
     * CMFA 就是这么干的：订阅原样落盘，内核读它，Kotlin 侧只做几处覆写。
     * 好处是订阅自带的一切（proxy-groups / rules / rule-providers / proxy-providers）
     * 全部保留 —— 机场往往在配置里定义了好几个 URL-Test 组（手动切换 / GOOGLE / BING / YANDEX…），
     * 重建式处理会把这些组全丢掉。
     *
     * 覆写清单对齐 CMFA 的 processors：
     *   patchExternalController → 清掉订阅里的外部控制，避免抢我们的 9090
     *   patchGeneral            → 清 interface / routing-mark，交给内核自己决定
     *   patchListeners          → 删掉订阅里的 tproxy/redir/tun listeners（它们的写法我们控制不了）
     *   patchTun                → tun 是否启用由我们的开关决定
     *   其余（dns/端口/嗅探/栈）→ 套我们自己的受控段
     */
    /**
     * 采纳模式：以订阅原文为底打补丁，保留它自带的策略组与规则。
     *
     * **性能是这个函数的头号约束。** 输入是 1.65 MB / 3 万行的完整机场配置。
     * 早期实现沿用了重建模式的写法 —— 每个 setScalar / replaceBlock 各自做一遍
     * lines() + joinToString()，一次生成要跑二十多遍全量重建，实测耗时 15 秒。
     * 用户体感就是「开关按下去半天没反应」，而其中真正花在内核自检上的只有 1.2 秒。
     *
     * 现在统一改成：**解析一次 → 在 MutableList 上原地改 → 最后拼一次**。
     */
    fun buildAdopted(subscriptionYaml: String, opts: Options, warnings: MutableList<String>? = null): String {
        val o = opts.over
        val L = subscriptionYaml.lines().toMutableList()

        // 1) 先摘掉会被我们覆写的顶层标量（只含标量键）
        for (k in REPLACED_TOP_KEYS) removeTopScalarInPlace(L, k)
        // 块结构单独走块删除：标量删除只吃掉块头，会把子行留成孤儿
        removeTopBlockInPlace(L, "listeners")

        // 2) 受控块：有就替换，没有就补在末尾（YAML 顶层键顺序不敏感）
        replaceBlockInPlace(L, "profile", profileBlock())
        replaceBlockInPlace(L, "dns", dnsBlock(opts.dns))
        replaceBlockInPlace(L, "tun", tunBlock(opts))
        if (o.sniffEnable) {
            replaceBlockInPlace(L, "sniffer", snifferBlock(o))
        }
        // 关着就**不动**订阅自带的 sniffer 段。早前这里是 removeTopBlockInPlace：用户什么都没做，
        // 只是切到采纳模式，就把机场配好的嗅探整段删掉（功能少了还不报错）。
        // 想彻底接管嗅探，就在「流量嗅探」对话框里打开开关 —— 那时用我们生成的配置覆盖它。
        replaceBlockInPlace(L, "external-controller-cors", corsBlockInPlace(o))
        if (o.authentication.isNotEmpty()) {
            replaceBlockInPlace(L, "authentication", "authentication:\n" +
                o.authentication.joinToString("\n") { "  - " + it })
        }
        val geox = geoxBlock(o)
        if (geox != null) replaceBlockInPlace(L, "geox-url", geox)

        // 3) 标量：已有的就地覆盖，缺的最后统一补到 rules: 之前
        val scalars = scalarMap(o)
        for (i in L.indices) {
            val l = L[i]
            if (l.isEmpty() || l.startsWith(" ") || l.startsWith("\t") || l.startsWith("#")) continue
            val colon = l.indexOf(':')
            if (colon <= 0) continue
            val k = l.substring(0, colon)
            val v = scalars[k] ?: continue
            L[i] = k + ": " + v
        }
        insertBeforeRulesInPlace(L, scalars)

        // 4) 用户规则：采纳模式也必须生效（详见 injectUserRulesInPlace 的说明）
        injectUserRulesInPlace(L, opts, subscriptionYaml, warnings)

        return L.joinToString("\n")
    }

    /**
     * 把用户自己的规则插到订阅自带 rules 的**最前面**（用户意图优先级最高，与重建模式一致）。
     *
     * 早前采纳模式完全不注入用户规则：界面上「内置分类走法 / 自定义规则 / 给分类追加域名」都能编辑，
     * 但生成出来的配置里一条都没有（真机实测：给「哔哩哔哩」追加的域名没出现在 config.yaml 里）。
     *
     * 两个细节：
     *  · 缩进沿用订阅自己的写法（机场多用 4 空格），否则 YAML 直接解析失败；
     *  · 「走代理」要落到订阅真实存在的组上（取它自己 rules 里 MATCH 后面的组名），
     *    否则内核会因为「组不存在」拒绝加载整份配置 —— 那比规则不生效更糟。
     */
    private fun injectUserRulesInPlace(
        L: MutableList<String>,
        opts: Options,
        subscriptionYaml: String,
        warnings: MutableList<String>?
    ) {
        val all = userRuleLines(opts)
        if (all.isEmpty()) return                  // 用户没写规则，什么都不用做

        val target = adoptedProxyTarget(subscriptionYaml)
        val prelude = if (target == null) {
            // 找不到可用的组名时，宁可不注入「走代理」规则，也不能让整份配置起不来
            warnings?.add("订阅里找不到可用的策略组名，「走代理」规则未能注入（直连/拦截规则不受影响）")
            all.filterNot { it.split(",").getOrNull(2)?.trim()?.uppercase() == "PROXY" }
        } else {
            userRuleLines(opts, target)
        }
        if (prelude.isEmpty()) return

        val ri = L.indexOfFirst { it.trim() == "rules:" }
        if (ri < 0) {
            // 以前这里是静默 return：订阅没有 rules 段时，用户写的规则一条都不会生效却毫无提示
            warnings?.add(
                "订阅里没有 rules: 段，你写的 " + prelude.size + " 条规则无处插入、本次已忽略" +
                    "（可改用「重建模式」，或把规则写成订阅自带的形式）"
            )
            return
        }

        var indent = "  "
        for (i in ri + 1 until L.size) {
            val l = L[i]
            if (l.isBlank()) continue
            if (l.trimStart().startsWith("- ")) { indent = l.substring(0, l.indexOf("- ")); break }
            if (l.firstOrNull() != ' ' && l.firstOrNull() != '\t') break   // 已经到下一个顶层键
        }
        val lines = prelude.map { indent + "- " + it }
        L.addAll(ri + 1, lines)
    }

    /** 用户自己的规则行（不含缩进与列表符号） */
    fun userRuleLines(opts: Options, proxyTarget: String = "PROXY"): List<String> {
        val out = mutableListOf<String>()
        for (r in opts.customRules) out += retarget(r, proxyTarget)
        for (d in opts.customDirectDomains.map { it.trim() }.filter { it.isNotEmpty() }) {
            out += "DOMAIN-SUFFIX," + d.removePrefix("+.").removePrefix(".") + ",DIRECT"
        }
        for (cat in RuleCatalog.ALL) {
            val action = opts.enabled[cat.key] ?: continue
            val target = if (action == RuleAction.PROXY) proxyTarget else action.name
            for (d in opts.extraDomains[cat.key].orEmpty()) out += "DOMAIN-SUFFIX," + d + "," + target
        }
        return out
    }

    /** 把规则里的 PROXY 动作换成实际存在的组名（其它动作原样） */
    private fun retarget(rule: String, proxyTarget: String): String {
        if (proxyTarget == "PROXY") return rule
        val parts = rule.split(",").map { it.trim() }.toMutableList()
        if (parts.size >= 3 && parts[2].uppercase() == "PROXY") parts[2] = proxyTarget
        return parts.joinToString(",")
    }

    /**
     * 采纳模式下「走代理」该指向谁：订阅自带 rules 里 MATCH 后面的那个组名（机场都把总控组写在那儿），
     * 退一步取 proxy-groups 里的第一个组名；都找不到返回 null。
     */
    fun adoptedProxyTarget(subscriptionYaml: String?): String? {
        if (subscriptionYaml.isNullOrBlank()) return null
        extractTopLevelBlock(subscriptionYaml, "rules")?.let { block ->
            for (raw in block.lines().drop(1)) {
                val l = raw.trim().removePrefix("-").trim()
                if (l.startsWith("MATCH,")) {
                    val name = l.substringAfter("MATCH,").trim().trim('\'', '"')
                    if (name.isNotEmpty()) return name
                }
            }
        }
        extractTopLevelBlock(subscriptionYaml, "proxy-groups")?.let { block ->
            for (raw in block.lines().drop(1)) {
                val l = raw.trim()
                if (!l.startsWith("- ")) continue
                val name = Regex("name:\\s*'?\"?([^'\"\\n,}]+)").find(l)?.groupValues?.get(1)?.trim()
                if (!name.isNullOrEmpty()) return name
            }
        }
        return null
    }

    /**
     * 这些顶层**标量**键一律由我们接管，订阅里原有的先删掉再按需补回。
     *
     * 只放标量键。块结构（listeners / external-controller-cors）绝不能放进来 ——
     * removeTopScalarInPlace 只删「块头」那一行，缩进的子行会全部残留成顶层孤儿，
     * YAML 直接解析失败。块键必须走 removeTopBlockInPlace / replaceBlockInPlace。
     * 这个坑造成过「订阅带 listeners 就起不来」，所以在此写死约束。
     */
    private val REPLACED_TOP_KEYS = listOf(
        "external-controller", "external-controller-tls", "external-ui",
        "interface-name", "routing-mark",
        "mixed-port", "redir-port", "tproxy-port", "port", "socks-port",
        "allow-lan", "bind-address", "tcp-concurrent", "unified-delay",
        "find-process-mode", "geodata-mode", "mode", "log-level", "secret"
    )

    /** 受控标量的键值表。顺序即补进配置时的顺序，别随意调。 */
    private fun scalarMap(o: OverrideOptions): LinkedHashMap<String, String> = linkedMapOf(
        "mixed-port" to o.mixedPort.toString(),
        "redir-port" to o.redirPort.toString(),
        "tproxy-port" to o.tproxyPort.toString(),
        "allow-lan" to o.allowLan.toString(),
        "bind-address" to ("\"" + o.bindAddress + "\""),
        "external-controller" to o.externalController,
        "tcp-concurrent" to o.tcpConcurrent.toString(),
        "unified-delay" to o.unifiedDelay.toString(),
        "find-process-mode" to o.findProcessMode,
        "geodata-mode" to o.geodataMode.toString(),
        "mode" to o.mode,
        "log-level" to o.logLevel,
        // mihomo 的键名：HTTP 代理是 port。0 = 不监听。
        "port" to o.httpPort.toString(),
        "socks-port" to o.socksPort.toString()
    ).apply {
        // secret 特殊：它同时出现在 REPLACED_TOP_KEYS 里（保证订阅自带的被清掉），
        // 所以只有用户真的设了密码才写回；留空时保持「没有 secret」的状态。
        // 早期漏了这一段，导致采纳模式下用户在「外部控制」设的密码被静默忽略。
        if (o.secret.isNotBlank()) put("secret", o.secret)
    }

    private fun corsBlockInPlace(o: OverrideOptions): String =
        "external-controller-cors:\n" +
            "  allow-origins: [" + o.extAllowOrigins.joinToString(", ") { "\"" + it + "\"" } + "]\n" +
            "  allow-private-network: " + o.extAllowPrivateNetwork

    private fun geoxBlock(o: OverrideOptions): String? {
        if (o.geoxGeoip.isBlank() && o.geoxMmdb.isBlank() &&
            o.geoxGeosite.isBlank() && o.geoxAsn.isBlank()
        ) return null
        val sb = StringBuilder("geox-url:\n")
        if (o.geoxGeoip.isNotBlank()) sb.append("  geoip: \"").append(o.geoxGeoip).append("\"\n")
        if (o.geoxMmdb.isNotBlank()) sb.append("  mmdb: \"").append(o.geoxMmdb).append("\"\n")
        if (o.geoxGeosite.isNotBlank()) sb.append("  geosite: \"").append(o.geoxGeosite).append("\"\n")
        if (o.geoxAsn.isNotBlank()) sb.append("  asn: \"").append(o.geoxAsn).append("\"\n")
        return sb.toString().trimEnd()
    }

    // ---------- 原地操作：全部作用于 MutableList<String>，不重建字符串 ----------

    private fun removeTopScalarInPlace(L: MutableList<String>, key: String) {
        L.removeAll { it.startsWith(key + ":") && !it.startsWith(" ") && !it.startsWith("#") }
    }

    private fun removeTopBlockInPlace(L: MutableList<String>, key: String) {
        val s = L.indexOfFirst { it.startsWith(key + ":") && !it.startsWith(" ") && !it.startsWith("#") }
        if (s < 0) return
        var e = s + 1
        while (e < L.size && (L[e].startsWith(" ") || L[e].startsWith("\t"))) e++
        L.subList(s, e).clear()
    }

    private fun replaceBlockInPlace(L: MutableList<String>, key: String, block: String) {
        val s = L.indexOfFirst { it.startsWith(key + ":") && !it.startsWith(" ") && !it.startsWith("#") }
        val blockLines = block.lines()
        if (s < 0) {
            L.add("")
            L.addAll(blockLines)
            return
        }
        var e = s + 1
        while (e < L.size && (L[e].startsWith(" ") || L[e].startsWith("\t"))) e++
        L.subList(s, e).clear()
        L.addAll(s, blockLines)
    }

    /** 缺的标量补在 rules: 之前；已有的一律不动 */
    private fun insertBeforeRulesInPlace(L: MutableList<String>, scalars: Map<String, String>) {
        val have = HashSet<String>()
        for (l in L) {
            if (l.isEmpty() || l.startsWith(" ") || l.startsWith("#")) continue
            val c = l.indexOf(':')
            if (c > 0) have.add(l.substring(0, c))
        }
        val add = scalars.filterKeys { it !in have }.map { (k, v) -> k + ": " + v }
        if (add.isEmpty()) return
        val r = L.indexOfFirst { it.trim() == "rules:" }
        if (r < 0) L.addAll(add) else L.addAll(r, add)
    }
    /** 数一个顶层列表块里有几项（用于日志，告诉用户采纳了多少组） */
    fun countTopLevelEntries(text: String, key: String): Int {
        val block = extractTopLevelBlock(text, key) ?: return 0
        return block.lines().drop(1).count { it.trimStart().startsWith("- ") }
    }

    /** 订阅是不是一份完整的 Clash 配置（而不是只有节点列表） */
    fun looksLikeFullConfig(text: String): Boolean {
        val hasProxies = Regex("(?m)^proxies:").containsMatchIn(text)
        val hasGroups = Regex("(?m)^proxy-groups:").containsMatchIn(text)
        val hasRules = Regex("(?m)^rules:").containsMatchIn(text)
        val hasProviders = Regex("(?m)^(proxy|rule)-providers:").containsMatchIn(text)
        // 有 proxies 再加任意一项，就认定是完整配置
        return hasProxies && (hasGroups || hasRules || hasProviders)
    }
}
