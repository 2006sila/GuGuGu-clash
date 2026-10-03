package io.guguguclash.profile

import android.content.Context
import io.guguguclash.core.CoreInstaller
import io.guguguclash.prefs.Prefs
import io.guguguclash.root.RootShell
import io.guguguclash.service.ShareState
import java.io.File

/**
 * 把「当前订阅 + 用户规则设置」合成为最终 config.yaml 并送达设备。
 * 合成后用内核自检（mihomo -t）验证，失败则拒绝启用，保留上一份可用配置。
 */
object ProfileRunner {

    data class Result(val ok: Boolean, val error: String? = null, val tested: Boolean = false, val nodeCount: Int = 0)

/**
     * 组装内核配置参数。原本内联在 prepareConfig 里，
     * 规则查询页也要用同一份 —— 抽出来避免两处各维护一份、慢慢漂移。
     */
    private fun buildOptions(
        p: Prefs.Data,
        providerFile: String,
        localRulesets: Boolean,
        subscriptionYaml: String?
    ): ConfigBuilder.Options {
            return ConfigBuilder.Options(
            providerPath = "./providers/" + providerFile,
            proxyOwnTraffic = p.proxyPhoneTraffic,
            enabled = p.ruleActions,
            customDirectDomains = p.customDirectDomains,
            // 自定义规则在这里统一校验：写错的行会被剔除并提示，
            // 否则内核会因一条错规则直接拒绝启动。
            customRules = CustomRule.parseAll(p.customRules).let { res ->
                if (res.errors.isNotEmpty()) {
                    ShareState.log("自定义规则有 " + res.errors.size + " 行无法识别，已跳过：" + res.errors.first())
                }
                res.lines
            },
            extraDomains = CustomRule.parseExtraDomains(p.extraDomains),
            localRulesetsPresent = localRulesets,
            adoptSubscriptionGroups = p.adoptSubscriptionGroups,
            adoptSubscriptionRules = p.adoptSubscriptionRules,
            subscriptionYaml = subscriptionYaml,

            // ---- DNS 全套 ----
            dns = ConfigBuilder.DnsOptions(
                enhancedMode = p.dnsEnhancedMode,
                listen = "0.0.0.0:" + p.dnsPort,
                defaultNameserver = splitList(p.dnsDefaultNameserver),
                nameserver = splitList(p.dnsNameserver),
                fallback = splitList(p.dnsFallback),
                fallbackGeoip = p.dnsFallbackGeoip,
                fallbackGeoipCode = p.dnsFallbackGeoipCode,
                fallbackIpcidr = splitList(p.dnsFallbackIpcidr),
                fakeIpFilter = splitList(p.dnsFakeIpFilter),
                hijack = p.dnsHijackAny,
                useHosts = p.dnsUseHosts,
                fakeIpFilterMode = p.dnsFakeIpFilterMode,
                nameServerPolicy = parsePolicy(p.dnsNameServerPolicy),
                domainFallback = splitList(p.dnsDomainFallback),
                appendSystemDns = p.dnsAppendSystemDns,
                preferH3 = p.dnsPreferH3
            ),
            // ---- tun / 应用分流 ----
            tun = ConfigBuilder.TunOptions(
                stack = p.tunStack,
                includePackage = if (p.appSplitMode == "whitelist") splitList(p.appSplitPackages) else emptyList(),
                excludePackage = if (p.appSplitMode == "blacklist") splitList(p.appSplitPackages) else emptyList()
            ),
            // ---- 顶层与嗅探覆写 ----
            over = ConfigBuilder.OverrideOptions(
                mode = p.mode,
                logLevel = p.logLevel,
                httpPort = p.httpPort,
                socksPort = p.socksPort,
                mixedPort = p.mixedPort,
                authentication = splitList(p.authentication),
                extAllowOrigins = splitList(p.extAllowOrigins),
                extAllowPrivateNetwork = p.extAllowPrivateNetwork,
                geoxGeoip = p.geoxGeoip,
                geoxMmdb = p.geoxMmdb,
                geoxGeosite = p.geoxGeosite,
                geoxAsn = p.geoxAsn,
                sniffParsePureIp = p.sniffParsePureIp,
                sniffForceDomain = splitList(p.sniffForceDomain),
                sniffSkipDomain = splitList(p.sniffSkipDomain),
                sniffSkipSrc = splitList(p.sniffSkipSrc),
                sniffSkipDst = splitList(p.sniffSkipDst),
                redirPort = p.redirPort,
                tproxyPort = p.tproxyPort,
                externalController = p.extController,
                secret = p.extSecret,
                tcpConcurrent = p.tcpConcurrent,
                unifiedDelay = p.unifiedDelay,
                findProcessMode = p.findProcessMode,
                geodataMode = p.geodataMode,
                sniffEnable = p.sniffEnable,
                sniffOverrideDest = p.sniffOverrideDest,
                sniffHttpPorts = splitList(p.sniffHttpPorts),
                sniffTlsPorts = splitList(p.sniffTlsPorts),
                sniffQuicPorts = splitList(p.sniffQuicPorts)
            )
        )
    }

    /**
     * 当前生效的规则文本（含 rules: 头）。
     * 采纳模式取订阅原文的 rules 块，重建模式用同一套 Options 现算 ——
     * 保证规则查询的结果和内核实际加载的一致。
     */
    fun currentRuleLines(ctx: Context, p: Prefs.Data): String {
        val store = ProfileStore(ctx)
        val profile = store.current(p.currentProfileId)
        val original = profile?.let { store.originalText(it) }
        if (p.profileMode == "adopt" && original != null && ConfigBuilder.looksLikeFullConfig(original)) {
            ConfigBuilder.extractTopLevelBlock(original, "rules")?.let {
                if (it.isNotBlank()) return it
            }
        }
        val pd = migrateLegacyDirectDomains(ctx, p)
        val opts = buildOptions(pd, profile?.providerFile ?: "", countDeviceRulesets() > 0, original)
        return "rules:\n" + ConfigBuilder.ruleLines(opts).joinToString("\n")
    }

    fun prepareConfig(ctx: Context, p: Prefs.Data): Result {
        val store = ProfileStore(ctx)
        val profile = store.current(p.currentProfileId)
            ?: return Result(false, error = "尚未导入订阅：请点「订阅」粘贴订阅链接")
        if (store.isLocked(profile)) {
            return Result(false, error = "配置已加密：请先在「配置」页输入密码解锁")
        }
        // 旧「直连域名」并入自定义规则（幂等：只在这份数据上跑一次）
        val p0 = migrateLegacyDirectDomains(ctx, p)

        var providerYaml = store.providerText(profile)
            ?: return Result(false, error = "订阅数据缺失，请重新导入")

        // 节点过滤与重命名。重建模式下节点在 provider 文件里、组用 use:[provider] 引用，
        // 改名不会破坏任何引用；采纳模式的引用同步在下面单独处理。
        val nodeRules = NodeTransform.parseRules(p0.nodeInclude, p0.nodeExclude, p0.nodeRenameRules)
        if (!nodeRules.isEmpty) {
            val tr = NodeTransform.applyToProxiesBlock(providerYaml, nodeRules)
            if (tr.error != null) return Result(false, error = tr.error)
            providerYaml = tr.text
            ShareState.log("节点处理：保留 " + tr.kept + " 个，剔除 " + tr.dropped + " 个，改名 " + tr.renamed + " 个")
        }

        // 自愈：早期版本把分享链接包成了 YAML（"proxies:\n  - ss://..."），
        // 内核两侧都解析不了（yaml 反序列化失败 + v2ray 转换 format invalid），
        // 结果就是「内核跑起来了但一个节点都没有」。这里检测到该形态就地从原始订阅重建。
        if (profile.kind == SubKind.SHARE_LINKS && providerYaml.trimStart().startsWith("proxies:")) {
            val original = store.originalText(profile)
                ?: return Result(false, error = "订阅为旧格式且缺少原始内容，请重新导入")
            val links = SubFormat.extractShareLinks(original)
            if (links.isEmpty()) {
                return Result(false, error = "无法从原始订阅恢复节点，请重新导入")
            }
            providerYaml = SubFormat.linksToProviderFile(links)
            store.writePayload(profile, providerYaml, original)
            ShareState.log("已自动修复旧格式的订阅节点文件（" + links.size + " 条）")
        }

        val template = ctx.assets.open("base.template.yaml").bufferedReader().use { it.readText() }
        val localRulesets = countDeviceRulesets() > 0

        val opts = buildOptions(p0, profile.providerFile, localRulesets, store.originalText(profile))
        // 订阅处理方式（对齐 CMFA 的取舍）：
        //   adopt   = 订阅原文打补丁，保留它自带的策略组与规则
        //   rebuild = 只取节点，用我们内置的策略组与规则
        val rawSub = store.originalText(profile)
        val adopt = p0.profileMode == "adopt" && rawSub != null && ConfigBuilder.looksLikeFullConfig(rawSub)
        if (p0.profileMode == "adopt" && !adopt) {
            ShareState.log("订阅不是完整配置（只有节点列表），本次按重建模式处理")
        }
        val config = if (adopt) {
            val groups = io.guguguclash.profile.ConfigBuilder.countTopLevelEntries(rawSub!!, "proxy-groups")
            ShareState.log("采纳模式：以订阅原文为底，保留它自带的 " + groups + " 个策略组与规则")
            // 采纳模式下节点名会被 proxy-groups 逐个引用，过滤/改名必须同步改组列表，
            // 否则内核会因为「组引用了不存在的节点」直接拒绝启动。
            val tr = NodeTransform.applyToConfig(rawSub, nodeRules)
            if (tr.error != null) return Result(false, error = tr.error)
            if (!nodeRules.isEmpty) {
                ShareState.log("节点处理：保留 " + tr.kept + " 个，剔除 " + tr.dropped + " 个，改名 " + tr.renamed + " 个（已同步组引用）")
            }
            ConfigBuilder.buildAdopted(tr.text, opts)
        } else {
            ConfigBuilder.build(template, opts)
        }

        val stage = File(ctx.filesDir, "conf").apply { mkdirs() }
        val stageProviders = File(stage, "providers").apply { mkdirs() }
        File(stage, "config.yaml").writeText(config)
        File(stageProviders, profile.providerFile).writeText(providerYaml)

        val copyScript = buildString {
            append("set -e\n")
            append("mkdir -p ").append(CoreInstaller.CONF).append(" ").append(CoreInstaller.PROVIDERS).append("\n")
            append("cp -f '").append(stage.absolutePath).append("/config.yaml' '").append(CoreInstaller.CONFIG).append("'\n")
            append("cp -f '").append(stageProviders.absolutePath).append("/").append(profile.providerFile)
                .append("' '").append(CoreInstaller.PROVIDERS).append("/").append(profile.providerFile).append("'\n")
            append("chmod 644 '").append(CoreInstaller.CONFIG).append("'\n")
            append("echo copied\n")
        }
        val copied = RootShell.run(copyScript, timeoutSec = 30)
        if (!copied.ok || !copied.out.contains("copied")) {
            return Result(false, error = "配置写入设备失败：" + copied.combined)
        }

        val test = RootShell.run(
            "'" + CoreInstaller.CORE + "' -t -d '" + CoreInstaller.CONF + "' -f '" + CoreInstaller.CONFIG + "' 2>&1 | tail -n 20",
            timeoutSec = 30
        )
        val out = test.out.trim()
        val flagUnsupported = out.contains("flag provided but not defined") || out.contains("unknown flag")
        if (!flagUnsupported && !test.ok) {
            return Result(false, error = "配置自检未通过：\n" + out.take(600), tested = true)
        }
        // -t 只校验语法：provider 拉取失败时它照样返回 success（实测），
        // 所以必须显式检查内核日志里的 provider 报错，否则会「启动成功但零节点」。
        val providerError = Regex("initial proxy provider .* error").find(out)
        if (providerError != null) {
            val detail = out.substring(providerError.range.first).take(400)
            return Result(false, error = "订阅节点解析失败（内核 provider 报错）：\n" + detail, tested = true)
        }
        val nodes = SubFormat.countNodes(providerYaml)
        if (nodes == 0) {
            return Result(false, error = "订阅里没有解析出任何节点，请重新导入或更换 UA", tested = true)
        }
        return Result(true, tested = !flagUnsupported, nodeCount = nodes)
    }

    /**
     * 一次性迁移：把旧的「直连域名」并入「自定义规则」。
     *
     * 两者最终生成的是同一条 DOMAIN-SUFFIX,<域名>,DIRECT（见 ConfigBuilder.ruleLines），
     * 留两个入口只会让人困惑（在「直连域名」加过 A 站，又在自定义规则里加同一条，会生效两次吗？）。
     * 迁完清空旧字段，之后每次调用直接返回 —— 幂等。
     */
    private fun migrateLegacyDirectDomains(ctx: Context, p: Prefs.Data): Prefs.Data {
        val legacy = p.customDirectDomains.map { it.trim() }.filter { it.isNotEmpty() }
        if (legacy.isEmpty()) return p
        val merged = p.copy(
            customRules = CustomRule.mergeDirectDomains(p.customRules, legacy),
            customDirectDomains = emptyList()
        )
        Prefs.save(ctx, merged)
        ShareState.log("已把 " + legacy.size + " 个「直连域名」并入自定义规则（原本就是同一条规则）")
        return merged
    }

    /** 逗号或换行分隔都接受：端口写 80,8080-8880，DNS 写一行一个，两种习惯都不用改 */
    /** 把「域名=服务器」逐行文本转成 nameserver-policy 需要的 map */
    private fun parsePolicy(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in s.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf('=')
            if (i <= 0) continue
            val k = t.substring(0, i).trim()
            val v = t.substring(i + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
        }
        return out
    }

    private fun splitList(s: String): List<String> =
        s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun countDeviceRulesets(): Int {
        val r = RootShell.run("ls " + CoreInstaller.RULESET + " 2>/dev/null | wc -l")
        return r.out.trim().toIntOrNull() ?: 0
    }

    /** 热重载：不重启内核，直接让正在运行的实例读取新配置 */
    fun hotReload(): Boolean = io.guguguclash.core.CoreApi.reload(CoreInstaller.CONFIG)
}
