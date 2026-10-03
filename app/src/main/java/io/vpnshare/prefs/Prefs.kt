package io.vpnshare.prefs

import android.content.Context
import io.vpnshare.profile.RuleAction
import io.vpnshare.profile.RuleCatalog

/**
 * 配置持久化。集合用简单分隔符序列化，避免引入 JSON 依赖（规则开关在 UI 上直接可读）。
 */
object Prefs {

    private const val NAME = "vpnshare"

    data class Data(
        val enabled: Boolean = false,
        val ifaceOverride: String = "",
        val redirPort: Int = 7892,
        val tproxyPort: Int = 7893,
        val dnsPort: Int = 1053,
        val proxyUdp: Boolean = true,
        val blockIpv6: Boolean = true,
        /** 默认关：内核若在开机时状态异常，会连带拖垮热点客户端的网络，确认稳定后再开 */
        val autoStartOnBoot: Boolean = false,
        /**
         * 手机自身流量是否也走代理（即是否启用内核 tun）。
         * 默认关：tun 会改系统默认路由，热点客户端的流量会被从路由层吸走，
         * 一旦内核异常，电脑就会整段断网且难以恢复。关掉后电脑只靠 iptables 规则，
         * 出问题删规则即可秒级恢复。代价是手机自己不再被代理。
         */
        val proxyPhoneTraffic: Boolean = false,
        val restoreOffloadOnStop: Boolean = false,
        /**
         * 共享期间关闭系统「专用 DNS」并在停止时恢复。
         * 打开专用 DNS 走 DoT，会绕过 53 端口的 DNS 劫持 —— 分流会乱。
         */
        val managePrivateDns: Boolean = true,
        /**
         * 上次被我们关掉的专用 DNS 原值（键名 + 值），落盘保存。
         *
         * 为什么必须落盘：只在内存里的话，共享期间进程被杀 / 手机重启会把原值弄丢，
         * 而开机自启重新 startAll 时读到的已经是 off，于是永远不会恢复 —— 用户原本的
         * 私人 DNS 设置就这么被静默改掉了。
         */
        val privateDnsSavedKey: String = "",
        val privateDnsSavedValue: String = "",
        val currentProfileId: String = "",
        val subUpdateHours: Int = 12,
        /**
         * 订阅 UA。这一项直接决定机场返回什么：
         *   用 mihomo/xxx        → 多数机场返回 base64 分享链接（只有节点）
         *   用 ClashMetaForAndroid/xxx → 返回完整 Clash 配置（带策略组、DNS 策略、完整规则）
         * 实测同一订阅：前者 14 KB、后者 1.6 MB。所以默认跟 CMFA 保持一致。
         */
        val userAgent: String = "ClashMetaForAndroid/2.11.35",
        val ruleActions: Map<String, RuleAction> = RuleCatalog.DEFAULT_ACTIONS,
        val customDirectDomains: List<String> = emptyList(),

        /** 用户自定义规则（每行一条，内核原生语法，见 profile/CustomRule.kt）。存原文，构建时再校验 */
        val customRules: String = "",

        /** 给内置分类追加的域名，格式：每行「分类key=域名1;域名2」 */
        val extraDomains: String = "",
        val adoptSubscriptionGroups: Boolean = false,
        val adoptSubscriptionRules: Boolean = false,
        /** 内核/规则数据下载时使用的 HTTP 代理，形如 http://192.168.43.1:7890；空=直连 */
        val downloadProxy: String = "",
        /** GitHub 加速前缀，形如 https://ghfast.top/ ；空=直连 */
        val ghMirror: String = "",

        /**
         * 订阅处理方式。
         *   rebuild = 只取节点，用我们内置的策略组与规则（默认，规则可控）
         *   adopt   = 对齐 CMFA：订阅原文打补丁，保留它自带的策略组与规则
         */
        val profileMode: String = "adopt",


        /**
         * 按网络环境自动切换。每行一条：
         *   网络标识 => 配置ID | 策略组名
         * 空 = 功能关闭。
         */
        val networkRules: String = "",

        /** 流量曲线保留多久（分钟）。0 = 关掉采样。 */
        val historyMinutes: Int = 60,

        // ===================== 节点过滤与重命名 =====================
        /** 换行分隔的关键字，命中才保留；空 = 全保留 */
        val nodeInclude: String = "",
        /** 换行分隔的关键字，命中就剔除 */
        val nodeExclude: String = "",
        /** 换行分隔的正则替换，格式 旧=>新 */
        val nodeRenameRules: String = "",

        /** system | light | dark —— 之前只能跟随系统，这里给显式选择 */
        val themeMode: String = "system",

        // ===================== 运行模式与日志（对齐 CMFA OverrideSettings）=====================
        /** rule | global | direct */
        val mode: String = "rule",
        /** info | warning | error | debug | silent */
        val logLevel: String = "info",
        /** 0 = 不监听该端口 */
        val httpPort: Int = 0,
        val socksPort: Int = 0,
        val mixedPort: Int = 7890,
        /** 代理认证，"用户名:密码"，多组用换行分隔；空=不鉴权 */
        val authentication: String = "",
        val bindAddress: String = "*",

        // ===================== DNS 细节 =====================
        /** blacklist | whitelist | rule —— fake-ip 过滤表的语义 */
        val dnsFakeIpFilterMode: String = "blacklist",
        /** 换行分隔的「域名=服务器」，对应内核的 nameserver-policy */
        val dnsNameServerPolicy: String = "",
        /** 换行分隔，命中这些域名时采用 fallback 的结果 */
        val dnsDomainFallback: String = "",
        val dnsPreferH3: Boolean = false,
        val dnsAppendSystemDns: Boolean = false,

        // ===================== geo 数据源（可换成自建镜像）=====================
        val geoxGeoip: String = "",
        val geoxMmdb: String = "",
        val geoxGeosite: String = "",
        val geoxAsn: String = "",

        // ===================== 嗅探精细化 =====================
        val sniffParsePureIp: Boolean = true,
        /** 换行分隔：这些域名强制走嗅探结果 */
        val sniffForceDomain: String = "",
        /** 换行分隔：这些域名跳过嗅探 */
        val sniffSkipDomain: String = "",
        val sniffSkipSrc: String = "",
        val sniffSkipDst: String = "",

        // ===================== 外部控制安全 =====================
        val extAllowOrigins: String = "*",
        val extAllowPrivateNetwork: Boolean = true,

        // ===================== 配置与加密 =====================
        /** 订阅文件加密落盘（PBKDF2 + AES-GCM，见 profile/CryptoUtil.kt） */
        val encryptProfiles: Boolean = false,

        // ===================== DNS 全套 =====================
        /** fake-ip | redir-host */
        val dnsEnhancedMode: String = "fake-ip",
        /** 换行分隔，必须是纯 IP，用于解析 DoH 域名自身 */
        val dnsDefaultNameserver: String = "223.5.5.5\n119.29.29.29",
        /** 换行分隔，主 DNS */
        val dnsNameserver: String = "https://223.5.5.5/dns-query\nhttps://1.12.12.12/dns-query\nhttps://doh.pub/dns-query",
        /** 换行分隔，回退 DNS；留空=不启用（国内不要填 1.1.1.1/8.8.8.8） */
        val dnsFallback: String = "",
        val dnsFallbackGeoip: Boolean = false,
        val dnsFallbackGeoipCode: String = "CN",
        /** 换行分隔：命中即视为被污染，改用 fallback */
        val dnsFallbackIpcidr: String = "",
        /** 换行分隔：这些域名不做 fake-ip */
        val dnsFakeIpFilter: String = "*.lan\n+.local\n*.localdomain\nlocalhost.ptlogin2.qq.com",
        val dnsHijackAny: Boolean = true,
        val dnsUseHosts: Boolean = true,
        val dnsForceMapping: Boolean = false,

        // ===================== 应用分流（tun 模式生效）=====================
        /** off | whitelist | blacklist */
        val appSplitMode: String = "off",
        /** 换行分隔的包名 */
        val appSplitPackages: String = "",

        // ===================== 覆写 =====================
        val sniffEnable: Boolean = false,
        val sniffOverrideDest: Boolean = true,
        val sniffHttpPorts: String = "80,8080-8880",
        val sniffTlsPorts: String = "443,8443",
        val sniffQuicPorts: String = "443,8443",
        /** system | gvisor | mixed */
        val tunStack: String = "mixed",
        val tcpConcurrent: Boolean = true,
        /**
         * 统一延迟。开启后只算 TCP 握手、剔除 TLS 耗时，各节点之间才可比。
         * 内核模板原本就是 true；我们一度按错误默认值写成 false，导致延迟数字明显偏大。
         */
        val unifiedDelay: Boolean = true,
        val findProcessMode: String = "off",
        val geodataMode: Boolean = false,

        // ===================== 外部控制 =====================
        val extController: String = "127.0.0.1:9090",
        val extSecret: String = ""
    )

    fun load(ctx: Context): Data {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        return Data(
            enabled = p.getBoolean("enabled", false),
            ifaceOverride = p.getString("iface", "") ?: "",
            redirPort = p.getInt("redir", 7892),
            tproxyPort = p.getInt("tproxy", 7893),
            dnsPort = p.getInt("dns", 1053),
            proxyUdp = p.getBoolean("udp", true),
            blockIpv6 = p.getBoolean("v6", true),
            autoStartOnBoot = p.getBoolean("boot", false),
            proxyPhoneTraffic = p.getBoolean("phoneProxy", false),
            restoreOffloadOnStop = p.getBoolean("restoreOffload", false),
        managePrivateDns = p.getBoolean("managePrivateDns", true),
        privateDnsSavedKey = p.getString("privateDnsKey", "") ?: "",
        privateDnsSavedValue = p.getString("privateDnsValue", "") ?: "",
            currentProfileId = p.getString("profile", "") ?: "",
            subUpdateHours = p.getInt("subHours", 12),
            // 一次性迁移：老版本存的是 mihomo UA，会让机场只吐 base64，改成 ClashMeta 系 UA
            userAgent = if (p.getBoolean("migUa", false))
                (p.getString("ua", "ClashMetaForAndroid/2.11.35") ?: "ClashMetaForAndroid/2.11.35")
            else "ClashMetaForAndroid/2.11.35",
            ruleActions = decodeActions(p.getString("rules", "") ?: ""),
            customRules = p.getString("customRules", "") ?: "",
            extraDomains = p.getString("extraDomains", "") ?: "",
            customDirectDomains = decodeList(p.getString("custom", "") ?: ""),
            adoptSubscriptionGroups = p.getBoolean("adoptGroups", false),
            adoptSubscriptionRules = p.getBoolean("adoptRules", false),
            downloadProxy = p.getString("dlProxy", "") ?: "",
            ghMirror = p.getString("ghMirror", "") ?: "",

            // 配合上面的 UA：拿到的是完整配置，采纳它自带的分流规则最省心
            // 一次性迁移：这个键最早是按「重建」默认值落盘的，
            // 而配合 ClashMeta 系 UA 拿到的是完整配置，采纳才是对的。

            networkRules = p.getString("netRules", "") ?: "",
            historyMinutes = p.getInt("histMin", 60),
            nodeInclude = p.getString("nodeInc", "") ?: "",
            nodeExclude = p.getString("nodeExc", "") ?: "",
            nodeRenameRules = p.getString("nodeRen", "") ?: "",
            themeMode = p.getString("themeMode", "system") ?: "system",
            mode = p.getString("mode", "rule") ?: "rule",
            logLevel = p.getString("logLevel", "info") ?: "info",
            httpPort = p.getInt("httpPort", 0),
            socksPort = p.getInt("socksPort", 0),
            mixedPort = p.getInt("mixedPort", 7890),
            authentication = p.getString("auth", "") ?: "",
            bindAddress = p.getString("bindAddr", "*") ?: "*",
            dnsFakeIpFilterMode = p.getString("dnsFim", "blacklist") ?: "blacklist",
            dnsNameServerPolicy = p.getString("dnsNsp", "") ?: "",
            dnsDomainFallback = p.getString("dnsDomFb", "") ?: "",
            dnsPreferH3 = p.getBoolean("dnsH3", false),
            dnsAppendSystemDns = p.getBoolean("dnsSys", false),
            geoxGeoip = p.getString("geoxGeoip", "") ?: "",
            geoxMmdb = p.getString("geoxMmdb", "") ?: "",
            geoxGeosite = p.getString("geoxGeosite", "") ?: "",
            geoxAsn = p.getString("geoxAsn", "") ?: "",
            sniffParsePureIp = p.getBoolean("sniffPpi", true),
            sniffForceDomain = p.getString("sniffForce", "") ?: "",
            sniffSkipDomain = p.getString("sniffSkip", "") ?: "",
            sniffSkipSrc = p.getString("sniffSkipSrc", "") ?: "",
            sniffSkipDst = p.getString("sniffSkipDst", "") ?: "",
            extAllowOrigins = p.getString("extOrigins", "*") ?: "*",
            extAllowPrivateNetwork = p.getBoolean("extPrivNet", true),
            profileMode = if (p.getBoolean("migMode", false))
                (p.getString("profileMode", "adopt") ?: "adopt")
            else "adopt",
            encryptProfiles = p.getBoolean("encProfiles", false),
            dnsEnhancedMode = p.getString("dnsMode", "fake-ip") ?: "fake-ip",
            dnsDefaultNameserver = p.getString("dnsDefault", "223.5.5.5\n119.29.29.29") ?: "223.5.5.5",
            dnsNameserver = p.getString("dnsNs", "https://223.5.5.5/dns-query\nhttps://doh.pub/dns-query") ?: "",
            dnsFallback = p.getString("dnsFb", "") ?: "",
            dnsFallbackGeoip = p.getBoolean("dnsFbGeoip", false),
            dnsFallbackGeoipCode = p.getString("dnsFbCode", "CN") ?: "CN",
            dnsFallbackIpcidr = p.getString("dnsFbCidr", "") ?: "",
            dnsFakeIpFilter = p.getString("dnsFakeFilter", "*.lan\n+.local\n*.localdomain") ?: "",
            dnsHijackAny = p.getBoolean("dnsHijack", true),
            dnsUseHosts = p.getBoolean("dnsHosts", true),
            dnsForceMapping = p.getBoolean("dnsForceMap", false),
            appSplitMode = p.getString("splitMode", "off") ?: "off",
            appSplitPackages = p.getString("splitPkgs", "") ?: "",
            sniffEnable = p.getBoolean("sniff", false),
            sniffOverrideDest = p.getBoolean("sniffOv", true),
            sniffHttpPorts = p.getString("sniffHttp", "80,8080-8880") ?: "",
            sniffTlsPorts = p.getString("sniffTls", "443,8443") ?: "",
            sniffQuicPorts = p.getString("sniffQuic", "443,8443") ?: "",
            tunStack = p.getString("tunStack", "mixed") ?: "mixed",
            tcpConcurrent = p.getBoolean("tcpConc", true),
            // 一次性迁移：老版本存下的 false 是错的（模板本来就是 true），首次加载修正为 true
            unifiedDelay = if (p.getBoolean("migUdl", false)) p.getBoolean("uniDelay", true) else true,
            findProcessMode = p.getString("findProc", "off") ?: "off",
            geodataMode = p.getBoolean("geoMode", false),
            extController = p.getString("extCtl", "127.0.0.1:9090") ?: "127.0.0.1:9090",
            extSecret = p.getString("extSecret", "") ?: ""
        )
    }

    fun save(ctx: Context, d: Data) {
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", d.enabled)
            .putString("iface", d.ifaceOverride)
            .putInt("redir", d.redirPort)
            .putInt("tproxy", d.tproxyPort)
            .putInt("dns", d.dnsPort)
            .putBoolean("udp", d.proxyUdp)
            .putBoolean("v6", d.blockIpv6)
            .putBoolean("boot", d.autoStartOnBoot)
            .putString("customRules", d.customRules)
            .putString("extraDomains", d.extraDomains)
            .putBoolean("phoneProxy", d.proxyPhoneTraffic)
            .putBoolean("restoreOffload", d.restoreOffloadOnStop)
        .putBoolean("managePrivateDns", d.managePrivateDns)
        .putString("privateDnsKey", d.privateDnsSavedKey)
        .putString("privateDnsValue", d.privateDnsSavedValue)
            .putString("profile", d.currentProfileId)
            .putInt("subHours", d.subUpdateHours)
            .putString("ua", d.userAgent)
            .putBoolean("migUa", true)
            .putString("rules", encodeActions(d.ruleActions))
            .putString("custom", encodeList(d.customDirectDomains))
            .putBoolean("adoptGroups", d.adoptSubscriptionGroups)
            .putBoolean("adoptRules", d.adoptSubscriptionRules)
            .putString("dlProxy", d.downloadProxy)
            .putString("ghMirror", d.ghMirror)


            .putString("netRules", d.networkRules)
            .putInt("histMin", d.historyMinutes)
            .putString("nodeInc", d.nodeInclude)
            .putString("nodeExc", d.nodeExclude)
            .putString("nodeRen", d.nodeRenameRules)
            .putString("themeMode", d.themeMode)
            .putString("mode", d.mode)
            .putString("logLevel", d.logLevel)
            .putInt("httpPort", d.httpPort)
            .putInt("socksPort", d.socksPort)
            .putInt("mixedPort", d.mixedPort)
            .putString("auth", d.authentication)
            .putString("bindAddr", d.bindAddress)
            .putString("dnsFim", d.dnsFakeIpFilterMode)
            .putString("dnsNsp", d.dnsNameServerPolicy)
            .putString("dnsDomFb", d.dnsDomainFallback)
            .putBoolean("dnsH3", d.dnsPreferH3)
            .putBoolean("dnsSys", d.dnsAppendSystemDns)
            .putString("geoxGeoip", d.geoxGeoip)
            .putString("geoxMmdb", d.geoxMmdb)
            .putString("geoxGeosite", d.geoxGeosite)
            .putString("geoxAsn", d.geoxAsn)
            .putBoolean("sniffPpi", d.sniffParsePureIp)
            .putString("sniffForce", d.sniffForceDomain)
            .putString("sniffSkip", d.sniffSkipDomain)
            .putString("sniffSkipSrc", d.sniffSkipSrc)
            .putString("sniffSkipDst", d.sniffSkipDst)
            .putString("extOrigins", d.extAllowOrigins)
            .putBoolean("extPrivNet", d.extAllowPrivateNetwork)
            .putString("profileMode", d.profileMode)
            .putBoolean("migMode", true)
            .putBoolean("encProfiles", d.encryptProfiles)
            .putString("dnsMode", d.dnsEnhancedMode)
            .putString("dnsDefault", d.dnsDefaultNameserver)
            .putString("dnsNs", d.dnsNameserver)
            .putString("dnsFb", d.dnsFallback)
            .putBoolean("dnsFbGeoip", d.dnsFallbackGeoip)
            .putString("dnsFbCode", d.dnsFallbackGeoipCode)
            .putString("dnsFbCidr", d.dnsFallbackIpcidr)
            .putString("dnsFakeFilter", d.dnsFakeIpFilter)
            .putBoolean("dnsHijack", d.dnsHijackAny)
            .putBoolean("dnsHosts", d.dnsUseHosts)
            .putBoolean("dnsForceMap", d.dnsForceMapping)
            .putString("splitMode", d.appSplitMode)
            .putString("splitPkgs", d.appSplitPackages)
            .putBoolean("sniff", d.sniffEnable)
            .putBoolean("sniffOv", d.sniffOverrideDest)
            .putString("sniffHttp", d.sniffHttpPorts)
            .putString("sniffTls", d.sniffTlsPorts)
            .putString("sniffQuic", d.sniffQuicPorts)
            .putString("tunStack", d.tunStack)
            .putBoolean("tcpConc", d.tcpConcurrent)
            .putBoolean("uniDelay", d.unifiedDelay)
            .putBoolean("migUdl", true)
            .putString("findProc", d.findProcessMode)
            .putBoolean("geoMode", d.geodataMode)
            .putString("extCtl", d.extController)
            .putString("extSecret", d.extSecret)
            .apply()
    }

    fun encodeActions(m: Map<String, RuleAction>): String =
        RuleCatalog.ALL.mapNotNull { c -> m[c.key]?.let { c.key + "=" + it.name } }.joinToString(";")

    fun decodeActions(s: String): Map<String, RuleAction> {
        if (s.isBlank()) return RuleCatalog.DEFAULT_ACTIONS
        val out = LinkedHashMap<String, RuleAction>()
        for (part in s.split(";")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val a = runCatching { RuleAction.valueOf(kv[1]) }.getOrNull() ?: continue
            out[kv[0]] = a
        }
        return if (out.isEmpty()) RuleCatalog.DEFAULT_ACTIONS else out
    }

    private fun encodeList(l: List<String>): String =
        l.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun decodeList(s: String): List<String> =
        s.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}
