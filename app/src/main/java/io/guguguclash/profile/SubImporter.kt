package io.guguguclash.profile

import android.content.Context
import io.guguguclash.prefs.Prefs
import io.guguguclash.service.ShareState
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 订阅导入：拉取 -> 嗅探格式 -> 归一化为 provider 载体 -> 落盘。
 * 主配置由 ProfileRunner 生成，订阅内容永远不会写进受控段。
 */
object SubImporter {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Result(
        val ok: Boolean,
        val profileId: String = "",
        val kind: SubKind = SubKind.UNKNOWN,
        val nodeCount: Int = 0,
        val userInfo: String = "",
        val message: String = ""
    )

    fun import(ctx: Context, url: String, ua: String = "mihomo/v1.19.32", name: String = ""): Result {
        val target = url.trim()
        if (target.isEmpty()) return Result(false, message = "订阅地址为空")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return Result(false, message = "订阅地址必须以 http:// 或 https:// 开头")
        }
        return try {
            val req = Request.Builder().url(target).header("User-Agent", ua).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result(false, message = "HTTP " + resp.code + "（可尝试修改 UA）")
                }
                val body = resp.body?.string() ?: return Result(false, message = "响应为空")
            // 机场按 UA 返回不同格式，体积差两个数量级；记一行方便判断 UA 有没有生效
            ShareState.log("订阅响应 " + body.length + " 字节，UA=" + ua)
                val userInfo = resp.header("subscription-userinfo") ?: ""
            // 机场可以下发建议的更新间隔（小时）。CMFA 也认这个头，并规定最小 15 分钟。
            val intervalHeader = resp.header("profile-update-interval") ?: ""
                var intervalHours = 0
            if (intervalHeader.isNotBlank()) {
                val h = intervalHeader.trim().toIntOrNull()
                if (h != null) {
                    intervalHours = h
                    ShareState.log("机场建议更新间隔 " + h + " 小时")
                }
            }
            commit(ctx, target, body, userInfo, name, intervalHours)
            }
        } catch (e: Exception) {
            Result(false, message = "下载失败：" + (e.message ?: e.toString()))
        }
    }

    /** 本地文件 / 剪贴板导入 */
    /** 单份订阅内容的字符上限 */
    private const val MAX_BODY_CHARS = 3 * 1024 * 1024

    fun importText(ctx: Context, url: String, text: String, userInfo: String = "", name: String = ""): Result =
        commit(ctx, url.ifBlank { "local://" + System.currentTimeMillis() }, text, userInfo, name)

    private fun commit(ctx: Context, url: String, body: String, userInfo: String, name: String, intervalHours: Int = 0): Result {
        // 体积上限：扫码/深链/剪贴板都可能塞进任意内容。正常机场配置 1-2 MB 顶天，
        // 3 MB 已经是非常离谱的余量；再大就拒掉，别把几百 MB 的东西整段落盘。
        if (body.length > MAX_BODY_CHARS) {
            return Result(false, message = "内容过大（" + (body.length / 1048576) + " MB），已拒绝导入")
        }
        val det = SubFormat.detect(body)
        val store = ProfileStore(ctx)
        val id = ProfileStore.idFor(url)

        val providerYaml = when (det.kind) {
            SubKind.CLASH_YAML -> {
                val block = ConfigBuilder.extractTopLevelBlock(body, "proxies")
                    ?: return Result(false, message = "这是 Clash 配置但没有 proxies 段")
                block
            }
            SubKind.SHARE_LINKS -> SubFormat.linksToProviderFile(det.proxies)
            SubKind.UNKNOWN -> return Result(false, message = det.reason)
        }

        val count = SubFormat.countNodes(providerYaml)
        if (count == 0) return Result(false, message = "解析出 0 个节点，订阅可能已过期或需要更换 UA")

        val label = name.ifBlank {
            runCatching { java.net.URI(url).host ?: "订阅" }.getOrDefault("订阅")
        }
        val profile = ProfileStore.Profile(
            id = id,
            name = label,
            url = url,
            kind = det.kind,
            updatedAt = System.currentTimeMillis(),
            userInfo = userInfo,
            intervalHours = intervalHours
        )
        store.writePayload(profile, providerYaml, body)
        store.upsert(profile)

        val p = Prefs.load(ctx)
        if (p.currentProfileId.isBlank()) Prefs.save(ctx, p.copy(currentProfileId = id))

        return Result(true, id, det.kind, count, userInfo, "导入 " + count + " 个节点（" + det.kind.name + "）")
    }

    /**
     * 更新已有订阅。
     * commit 只在格式可识别且解析出节点之后才落盘，因此下载/解析失败时旧文件完好，
     * 当前正在运行的配置不受影响。
     */
    fun update(ctx: Context, profile: ProfileStore.Profile, ua: String): Result {
        val r = import(ctx, profile.url, ua, profile.name)
        return if (r.ok) r else r.copy(message = r.message + "（已保留上一份可用配置）")
    }
}
