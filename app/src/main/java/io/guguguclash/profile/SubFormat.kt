package io.guguguclash.profile

import io.guguguclash.util.B64

enum class SubKind { CLASH_YAML, SHARE_LINKS, UNKNOWN }

data class SubDetection(
    val kind: SubKind,
    val proxies: List<String> = emptyList(),
    val reason: String = ""
)

/**
 * 订阅格式嗅探。纯函数，无 Android 依赖，便于 JVM 单测。
 * 判定顺序：先看 Clash YAML，再看 base64 分享链接，都不匹配则 UNKNOWN。
 */
object SubFormat {

    private val SCHEMES = listOf(
        "ss://", "ssr://", "vmess://", "vless://", "trojan://",
        "hysteria://", "hysteria2://", "hy2://", "tuic://",
        "snell://", "wireguard://", "socks://", "socks5://",
        "http://", "https://"
    )

    /** 顶层是否出现 Clash 配置必备的 proxies: / proxy-providers: 段 */
    fun looksLikeClashYaml(text: String): Boolean {
        var sawProxies = false
        for (raw in text.lineSequence()) {
            val line = raw.trimStart()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (raw == raw.trimStart()) {
                // 顶层键
                val key = line.substringBefore(':').trim()
                if (line.contains(':')) {
                    if (key == "proxies" || key == "proxy-providers" || key == "proxy-groups") {
                        sawProxies = true
                        break
                    }
                    if (key == "port" || key == "mixed-port" || key == "socks-port" || key == "rules") {
                        sawProxies = true
                        break
                    }
                }
            }
        }
        return sawProxies
    }

    /** 从任意文本中提取分享链接（支持明文与 base64 两种载体） */
    fun extractShareLinks(text: String): List<String> {
        val direct = scanLinks(text)
        if (direct.isNotEmpty()) return direct
        val trimmed = text.trim()
        if (B64.looksBase64(trimmed)) {
            val decoded = B64.decode(trimmed)
            if (decoded != null) return scanLinks(decoded)
        }
        return emptyList()
    }

    private fun scanLinks(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .filter { line -> SCHEMES.any { line.startsWith(it, ignoreCase = true) } }
            .toList()

    fun detect(text: String): SubDetection {
        if (text.isBlank()) return SubDetection(SubKind.UNKNOWN, reason = "内容为空")
        if (looksLikeClashYaml(text)) {
            return SubDetection(SubKind.CLASH_YAML, reason = "检测到 Clash 配置顶层字段")
        }
        val links = extractShareLinks(text)
        if (links.isNotEmpty()) {
            return SubDetection(SubKind.SHARE_LINKS, proxies = links, reason = "识别到 " + links.size + " 条分享链接")
        }
        return SubDetection(SubKind.UNKNOWN, reason = "既不是 Clash YAML，也不是可识别的分享链接列表")
    }

    /**
     * 分享链接列表 -> provider 文件内容。
     *
     * 必须写成【标准 v2ray base64 订阅】而不是 YAML。
     * 实测把链接包成 "proxies:\n  - ss://..." 会让内核两侧都失败：
     *   yaml: cannot unmarshal !!str `ss://...` into map[string]interface {}
     *   convert v2ray subscribe error: format invalid
     * 因为 type: file 的 provider 期望 Clash 结构的 map 列表，退回 v2ray 转换时
     * 又被 "proxies:" 和 "- " 前缀破坏。base64 形式两边都能吃。
     */
    fun linksToProviderFile(links: List<String>): String =
        io.guguguclash.util.B64.encode(links.joinToString("\n"))

    /**
     * 统计节点数。要认三种形态：
     *   1) 只有 proxies: 块的 provider 文件
     *   2) 完整 Clash 配置（机场现在多半给这个）
     *   3) base64 / 裸分享链接
     * 曾经的实现对完整配置走了兜底分支「数含 :// 的行」，结果数到的是
     * rule-providers 里的几个 URL —— 界面上就显示成「2 节点」。
     */
    fun countNodes(text: String): Int {
        val t = text.trim()
        if (t.isEmpty()) return 0

        // 1) + 2)：找 proxies: 块（可能在文件中间），数其下的 "- " 项
        val atStart = t.startsWith("proxies:")
        val mid = t.indexOf("\nproxies:")
        if (atStart || mid >= 0) {
            val body = if (atStart) t else t.substring(mid + 1)
            val lines = body.lines()
            var n = 0
            for (i in 1 until lines.size) {
                val l = lines[i]
                // 空行不算结束；遇到非缩进的顶层键就说明块结束了
                if (l.isNotEmpty() && !l.startsWith(" ") && !l.startsWith("\t") && !l.startsWith("#")) break
                if (l.trimStart().startsWith("- ")) n++
            }
            if (n > 0) return n
        }

        // 3) base64 分享链接
        val decoded = io.guguguclash.util.B64.decode(t)
        val raw = if (decoded != null && decoded.contains("://")) decoded else t
        return raw.lines().count {
            val s = it.trimStart()
            s.contains("://") && !s.startsWith("#")
        }
    }
}

/**
 * 订阅流量配额。机场通过响应头 Subscription-Userinfo 下发：
 *   upload=1109571285; download=25114233259; total=107374182400; expire=179288764
 * 单位是字节，expire 是 Unix 秒。字段可能缺，缺的当 0 处理。
 */
data class SubUserInfo(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expire: Long = 0
) {
    val used: Long get() = (upload + download).coerceAtLeast(0)
    val remaining: Long get() = (total - used).coerceAtLeast(0)
    val hasQuota: Boolean get() = total > 0
    val percent: Int get() = if (total <= 0) 0 else ((used * 100) / total).toInt().coerceIn(0, 100)

    /** 到期日；0 或异常值返回 null */
    fun expireDate(): String? {
        if (expire <= 0) return null
        // 有的机场给毫秒，有的给秒；按量级判断
        val ms = if (expire > 100000000000L) expire else expire * 1000
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ms))
        }.getOrNull()
    }

    fun isExpired(): Boolean {
        if (expire <= 0) return false
        val ms = if (expire > 100000000000L) expire else expire * 1000
        return ms < System.currentTimeMillis()
    }
}

/** 解析订阅头。解析不出任何字段就返回 null，调用方据此不显示。 */
fun parseUserInfo(raw: String): SubUserInfo? {
    if (raw.isBlank()) return null
    var up = 0L; var down = 0L; var total = 0L; var exp = 0L
    var any = false
    for (part in raw.split(';', ',')) {
        val kv = part.split('=')
        if (kv.size != 2) continue
        val k = kv[0].trim().lowercase()
        val v = kv[1].trim().toLongOrNull() ?: continue
        when (k) {
            "upload" -> { up = v; any = true }
            "download" -> { down = v; any = true }
            "total" -> { total = v; any = true }
            "expire" -> { exp = v; any = true }
        }
    }
    return if (any) SubUserInfo(up, down, total, exp) else null
}
