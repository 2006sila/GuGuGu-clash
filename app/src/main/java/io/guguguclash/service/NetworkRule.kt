package io.guguguclash.service

/**
 * 按网络环境自动切换的规则。
 *
 * 格式（每行一条）：
 *   网络标识 => 配置ID | 策略组名
 *
 * 网络标识：
 *   mobile            移动数据
 *   wifi              任意 Wi-Fi
 *   wifi:192.168.1.1  指定网关的 Wi-Fi
 *
 * 为什么用网关而不是 Wi-Fi 名：
 *   Android 8.1 起读 SSID/BSSID 需要位置权限。为了「换个网络自动切节点」
 *   而多要一个敏感权限不划算 —— 网关地址同样能唯一区分网络，且不用任何权限。
 *
 * 策略组名可省略（留空表示只切配置、不动选中的节点）。
 */
object NetworkRule {

    data class Rule(val identity: String, val profileId: String, val group: String)

    fun parse(text: String): List<Rule> {
        val out = mutableListOf<Rule>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf("=>")
            if (i <= 0) continue
            val id = t.substring(0, i).trim().lowercase()
            val rest = t.substring(i + 2).trim()
            if (id.isEmpty() || rest.isEmpty()) continue
            val parts = rest.split('|')
            val profile = parts.getOrElse(0) { "" }.trim()
            val group = parts.getOrElse(1) { "" }.trim()
            if (profile.isEmpty()) continue
            out += Rule(id, profile, group)
        }
        return out
    }

    /**
     * 匹配。优先级：具体网关 > 泛化 wifi > mobile。
     * 泛化的写在后面也能命中，但具体的一定优先 —— 否则「家里」会被「任意 Wi-Fi」吃掉。
     */
    fun match(rules: List<Rule>, identity: String?): Rule? {
        if (identity.isNullOrBlank()) return null
        val id = identity.lowercase()
        // 1) 精确匹配
        rules.firstOrNull { it.identity == id }?.let { return it }
        // 2) 泛化：identity 是 wifi:xxx 时，允许 "wifi" 兜底
        if (id.startsWith("wifi:")) {
            rules.firstOrNull { it.identity == "wifi" }?.let { return it }
        }
        return null
    }

    /** 由当前的网络信息推出标识。net = android 的 Network，读不到返回 null */
    fun identityOf(isWifi: Boolean, isMobile: Boolean, gateway: String?): String? = when {
        isWifi && !gateway.isNullOrBlank() -> "wifi:" + gateway
        isWifi -> "wifi"
        isMobile -> "mobile"
        else -> null
    }
}