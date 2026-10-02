package io.vpnshare.core

import android.content.Context
import io.vpnshare.prefs.Prefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * 内核与规则数据的运行时下载。
 *
 * 意义：构建期可以不打包任何二进制与 geo 数据，由 App 首次运行时拉到私有目录，
 * 再由 CoreInstaller 装到 /data/adb/vpnshare。这样仓库体积可控、CI 无需外网，
 * 也避免「assets 三个目录为空就无法使用」的死锁。
 *
 * 私有目录约定（root 可读）：
 *   files/prebuilt/core.gz
 *   files/prebuilt/geo/geosite.dat
 *   files/prebuilt/geo/geoip.metadb
 *   files/prebuilt/ruleset/<name>.yaml
 */
object CoreDownloader {

    const val PREBUILT = "prebuilt"
    const val CORE_GZ = PREBUILT + "/core.gz"
    const val GEO_DIR = PREBUILT + "/geo"
    const val RULESET_DIR = PREBUILT + "/ruleset"

    private const val MIHOMO_API = "https://api.github.com/repos/MetaCubeX/mihomo/releases/latest"
    private const val RULES_DAT = "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/"
    private const val RULESET_RAW = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite/"

    data class Step(val label: String, val ok: Boolean, val detail: String = "")

    // ---------------- 就绪判断 ----------------

    fun coreAssetReady(ctx: Context): Boolean {
        // 打包后路径是 mihomo/mihomo（aapt 会把 .gz 解压并去掉扩展名），
        // 未走 aapt 处理时可能是 mihomo/<abi>/mihomo.gz，两种都要认。
        val candidates = listOf("mihomo", "mihomo/arm64-v8a", "mihomo/armeabi-v7a")
        return candidates.any { dir ->
            runCatching {
                (ctx.assets.list(dir) ?: emptyArray()).any { it == "mihomo" || it.endsWith(".gz") }
            }.getOrDefault(false)
        }
    }

    fun geoAssetReady(ctx: Context): Boolean =
        runCatching { (ctx.assets.list("geo") ?: emptyArray()).any { it.endsWith(".dat") } }.getOrDefault(false)

    fun coreDownloaded(ctx: Context): Boolean = File(ctx.filesDir, CORE_GZ).let { it.exists() && it.length() > 1_000_000 }

    fun geoDownloaded(ctx: Context): Boolean =
        File(ctx.filesDir, GEO_DIR + "/geosite.dat").let { it.exists() && it.length() > 100_000 }

    fun rulesetsDownloaded(ctx: Context): Boolean =
        File(ctx.filesDir, RULESET_DIR).listFiles()?.any { it.name.endsWith(".yaml") } == true

    fun coreReady(ctx: Context) = coreAssetReady(ctx) || coreDownloaded(ctx)
    fun geoReady(ctx: Context) = geoAssetReady(ctx) || geoDownloaded(ctx)

    /** 尚未就绪的项目，供 UI 直接展示 */
    fun missing(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        if (!coreReady(ctx)) out += "内核二进制"
        if (!geoReady(ctx)) out += "geo 规则数据"
        if (!rulesetsDownloaded(ctx)) out += "本地规则集（可缺，缺失时规则退回 GEOSITE）"
        return out
    }

    // ---------------- 网络 ----------------

    fun client(ctx: Context): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
        val proxyText = Prefs.load(ctx).downloadProxy.trim()
        if (proxyText.isNotEmpty()) {
            runCatching {
                val u = java.net.URI(if (proxyText.contains("://")) proxyText else "http://" + proxyText)
                val port = if (u.port > 0) u.port else 7890
                b.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(u.host, port)))
            }
        }
        return b.build()
    }

    private fun url(ctx: Context, raw: String): String {
        val mirror = Prefs.load(ctx).ghMirror.trim()
        if (mirror.isEmpty()) return raw
        return mirror.trimEnd('/') + "/" + raw
    }

    // ---------------- 下载 ----------------

    fun downloadCore(ctx: Context, onStep: (Step) -> Unit): Step {
        val abi = CoreInstaller.pickAbi()
        if (abi == null) return emit(onStep, Step("下载内核", false, "不支持的 ABI"))
        val prefix = when (abi) {
            "arm64-v8a" -> "mihomo-android-arm64-v8-"
            "armeabi-v7a" -> "mihomo-android-armv7-"
            else -> return emit(onStep, Step("下载内核", false, "无对应资产: " + abi))
        }
        return try {
            val c = client(ctx)
            val head = c.newCall(Request.Builder().url(url(ctx, MIHOMO_API)).header("User-Agent", "vpnshare").build())
                .execute().use { it.body?.string() ?: "" }
            val root = runCatching { JSONObject(head) }.getOrNull()
                ?: return emit(onStep, Step("下载内核", false, "release 信息无法解析（可能需要配置下载代理或镜像）"))
            val tag = root.optString("tag_name")
            val assets = root.optJSONArray("assets")
                ?: return emit(onStep, Step("下载内核", false, "release 无 assets"))

            var target = ""
            for (i in 0 until assets.length()) {
                val o = assets.optJSONObject(i) ?: continue
                val n = o.optString("name")
                if (n.startsWith(prefix) && n.endsWith(".gz")) {
                    target = url(ctx, o.optString("browser_download_url"))
                    break
                }
            }
            if (target.isEmpty()) return emit(onStep, Step("下载内核", false, "未找到 " + prefix + "*.gz"))

            val dst = File(ctx.filesDir, CORE_GZ)
            val n = fetchTo(ctx, target, dst)
            if (n < 1_000_000) {
                dst.delete()
                return emit(onStep, Step("下载内核", false, "文件仅 " + n + " 字节，疑被拦截"))
            }
            emit(onStep, Step("下载内核", true, (n / 1048576).toString() + " MB (" + tag + ")"))
        } catch (e: Exception) {
            emit(onStep, Step("下载内核", false, e.message ?: e.toString()))
        }
    }

    fun downloadGeo(ctx: Context, lite: Boolean, onStep: (Step) -> Unit): Step {
        val dir = File(ctx.filesDir, GEO_DIR).apply { mkdirs() }
        val map = if (lite) listOf("geosite-lite.dat" to "geosite.dat", "geoip-lite.metadb" to "geoip.metadb")
        else listOf("geosite.dat" to "geosite.dat", "geoip.metadb" to "geoip.metadb")
        var total = 0L
        for ((src, out) in map) {
            try {
                val n = fetchTo(ctx, url(ctx, RULES_DAT + src), File(dir, out))
                if (n < 1024) return emit(onStep, Step("下载 geo 数据", false, src + " 过小"))
                total += n
            } catch (e: Exception) {
                return emit(onStep, Step("下载 geo 数据", false, src + ": " + (e.message ?: "")))
            }
        }
        return emit(onStep, Step("下载 geo 数据", true, (total / 1048576).toString() + " MB"))
    }

    fun downloadRulesets(ctx: Context, onStep: (Step) -> Unit): Step {
        val dir = File(ctx.filesDir, RULESET_DIR).apply { mkdirs() }
        val names = listOf("bilibili", "category-ads-all", "private")
        var ok = 0
        for (n in names) {
            try {
                val f = File(dir, n + ".yaml")
                if (fetchTo(ctx, url(ctx, RULESET_RAW + n + ".yaml"), f) > 100) ok++ else f.delete()
            } catch (_: Exception) {
            }
        }
        return emit(onStep, Step("下载本地规则集", ok > 0, ok.toString() + "/" + names.size + " 个（缺失时规则退回 GEOSITE）"))
    }

    fun bootstrap(ctx: Context, lite: Boolean, onStep: (Step) -> Unit): Boolean {
        val a = downloadCore(ctx, onStep)
        val b = downloadGeo(ctx, lite, onStep)
        val c = downloadRulesets(ctx, onStep)
        return a.ok && b.ok && c.ok
    }

    // ---------------- 本地导入 ----------------

    /** 导入 PC 下载好的 mihomo-android-*.gz；先验 gzip magic 再解压一次 */
    fun importCoreGz(ctx: Context, src: File): Step {
        return try {
            val magic = ByteArray(2)
            src.inputStream().use { it.read(magic) }
            if (magic[0] != 0x1f.toByte() || magic[1] != 0x8b.toByte()) {
                return Step("导入内核", false, "不是 gzip 文件，请提供 mihomo-android-*.gz")
            }
            GZIPInputStream(src.inputStream()).use { gz -> gz.read(ByteArray(16)) }
            val dst = File(ctx.filesDir, CORE_GZ)
            dst.parentFile?.mkdirs()
            src.inputStream().use { i -> dst.outputStream().use { i.copyTo(it) } }
            Step("导入内核", true, (dst.length() / 1048576).toString() + " MB")
        } catch (e: Exception) {
            Step("导入内核", false, e.message ?: e.toString())
        }
    }

    fun importRulesetDir(ctx: Context, src: File): Step {
        return try {
            val dir = File(ctx.filesDir, RULESET_DIR).apply { mkdirs() }
            var n = 0
            for (f in src.listFiles().orEmpty()) {
                if (f.isFile && f.name.endsWith(".yaml")) {
                    f.copyTo(File(dir, f.name), overwrite = true)
                    n++
                }
            }
            Step("导入规则集", n > 0, n.toString() + " 个文件")
        } catch (e: Exception) {
            Step("导入规则集", false, e.message ?: e.toString())
        }
    }

    // ---------------- 内部 ----------------

    private fun emit(onStep: (Step) -> Unit, s: Step): Step {
        onStep(s)
        return s
    }

    private fun fetchTo(ctx: Context, url: String, dst: File): Long {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parentFile, dst.name + ".part")
        if (tmp.exists()) tmp.delete()
        client(ctx).newCall(Request.Builder().url(url).header("User-Agent", "vpnshare").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            val body = resp.body ?: throw IllegalStateException("空响应")
            tmp.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) {
            tmp.copyTo(dst, overwrite = true)
            tmp.delete()
        }
        return dst.length()
    }
}
