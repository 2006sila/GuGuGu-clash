package io.vpnshare.profile

enum class RuleAction { DIRECT, PROXY, REJECT }

/**
 * 内置规则分类元数据。条目数为 meta-rules-dat 实测值，作为单测基准。
 */
data class RuleCategory(
    val key: String,
    val label: String,
    val geosite: String,
    val entries: Int,
    val defaultAction: RuleAction,
    val localRuleset: String? = null,
    val alsoGeoIpCn: Boolean = false
)

object RuleCatalog {

    val ALL: List<RuleCategory> = listOf(
        RuleCategory("private", "内网地址", "private", 130, RuleAction.DIRECT, "private.yaml"),
        RuleCategory("bilibili", "哔哩哔哩", "bilibili", 53, RuleAction.DIRECT, "bilibili.yaml"),
        RuleCategory("ads", "广告拦截", "category-ads-all", 910, RuleAction.REJECT, "category-ads-all.yaml"),
        // 注意 alsoGeoIpCn 保持 false：
        // 本地 DNS 只用国内 DoH，境外域名会被污染成 CN 段 IP，
        // 一旦挂上 GEOIP,CN,DIRECT，Google/GitHub 这类域名就会被误判成国内而直连，
        // 表现为「节点明明正常却上不去网」。国内直连一律靠域名规则 GEOSITE,cn。
        RuleCategory("cn", "中国大陆", "cn", 5069, RuleAction.DIRECT, null, alsoGeoIpCn = false)
    )

        val DEFAULT_ACTIONS: Map<String, RuleAction> = ALL.associate { it.key to it.defaultAction }
}
