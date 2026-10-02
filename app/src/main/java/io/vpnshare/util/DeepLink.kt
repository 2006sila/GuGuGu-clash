package io.vpnshare.util

import java.net.URLDecoder

/**
 * 深链解析：`vpnshare://import?url=<编码后的订阅地址>`
 *
 * 刻意只吃 String 而不是 android.net.Uri —— 这样能在纯 JVM 单测里跑，
 * 不必为了几行解析引入 Robolectric。
 */
object DeepLink {

    const val SCHEME = "vpnshare"
    const val HOST = "import"

    sealed class Result {
        data class Import(val url: String) : Result()
        data class Bad(val reason: String) : Result()
    }

    /**
     * 解析。任何不合规都返回 Bad 并带原因，调用方据此提示用户，
     * 绝不「猜一个链接」去导入 —— 深链可能来自任意网页。
     */
    fun parse(raw: String?): Result {
        if (raw.isNullOrBlank()) return Result.Bad("空链接")
        val lower = raw.lowercase()
        if (!lower.startsWith(SCHEME + "://")) return Result.Bad("不是 " + SCHEME + " 链接")

        var rest = raw.substring((SCHEME + "://").length)
        // 去掉 fragment
        rest = rest.substringBefore('#')
        val host = rest.substringBefore('/').substringBefore('?')
        if (!host.equals(HOST, ignoreCase = true)) return Result.Bad("未知的深链类型：" + host)

        val query = rest.substringAfter('?', "")
        if (query.isBlank()) return Result.Bad("缺少 url 参数")

        var value: String? = null
        for (kv in query.split('&')) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            if (kv.substring(0, i).equals("url", ignoreCase = true)) {
                value = kv.substring(i + 1)
                break
            }
        }
        if (value.isNullOrBlank()) return Result.Bad("缺少 url 参数")

        val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
            ?: return Result.Bad("url 参数编码损坏")

        val d = decoded.trim()
        if (d.length > 4096) return Result.Bad("链接过长")
        if (!d.startsWith("http://") && !d.startsWith("https://")) {
            return Result.Bad("只接受 http/https 订阅地址")
        }
        return Result.Import(d)
    }

    /** 从链接里取主机名，用于确认框展示 —— 让用户看清要连的是哪台服务器 */
    fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)
}