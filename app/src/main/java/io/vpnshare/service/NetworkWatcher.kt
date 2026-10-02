package io.vpnshare.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.vpnshare.prefs.Prefs
import io.vpnshare.profile.ProfileRunner

/**
 * 按网络环境自动切换配置。
 *
 * 默认关闭：networkRules 为空时完全不注册回调，不产生任何行为。
 *
 * 切换走的是「重新生成配置 + 热重载」，不是重启服务 —— 后者会短暂断掉热点客户端。
 * 热重载期间内核会重载规则，但不会杀掉进程，已建立的连接不会全断。
 */
object NetworkWatcher {

    @Volatile private var cb: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastApplied: String? = null

    /** 当前网络的标识：wifi:<网关> / wifi / mobile / null */
    fun currentIdentity(ctx: Context): String? = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        val n = cm.activeNetwork ?: return null
        val cap = cm.getNetworkCapabilities(n) ?: return null
        val isWifi = cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isMobile = cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val gw = gatewayOf(cm.getLinkProperties(n))
        NetworkRule.identityOf(isWifi, isMobile, gw)
    }.getOrNull()

    /**
     * 从链路属性里取默认网关。不需要任何权限 —— 这是它相对 SSID 的最大优势。
     */
    private fun gatewayOf(lp: LinkProperties?): String? {
        if (lp == null) return null
        for (r in lp.routes) {
            if (r.isDefaultRoute && r.gateway != null) return r.gateway?.hostAddress
        }
        return null
    }

    fun start(ctx: Context) {
        stop(ctx)
        val p = Prefs.load(ctx)
        val rules = NetworkRule.parse(p.networkRules)
        if (rules.isEmpty()) {
            ShareState.log("网络联动：未配置规则，不启用")
            return
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { apply(ctx) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { apply(ctx) }
            override fun onLost(network: Network) { apply(ctx) }
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
            cb = callback
            ShareState.log("网络联动：已启用，共 " + rules.size + " 条规则")
        }.onFailure { ShareState.log("WARN 网络联动注册失败：" + it.message) }
        // 注册后立刻按当前网络判一次，免得切完网络才想起来
        apply(ctx)
    }

    fun stop(ctx: Context) {
        cb?.let { c ->
            runCatching {
                ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(c)
            }
        }
        cb = null
        lastApplied = null
    }

    /** 读当前网络 → 匹配规则 → 需要的话切配置并热重载 */
    private fun apply(ctx: Context) {
        val p = Prefs.load(ctx)
        val rules = NetworkRule.parse(p.networkRules)
        if (rules.isEmpty()) return
        val id = currentIdentity(ctx) ?: return
        val hit = NetworkRule.match(rules, id) ?: return
        if (hit.profileId == p.currentProfileId && lastApplied == id) return

        ShareState.log(
            "网络联动：当前 " + id + "，切到配置 " + hit.profileId +
                (if (hit.group.isNotBlank()) " / 组 " + hit.group else "")
        )
        lastApplied = id
        runCatching {
            Prefs.save(ctx, Prefs.load(ctx).copy(currentProfileId = hit.profileId))
            val prep = ProfileRunner.prepareConfig(ctx, Prefs.load(ctx))
            if (!prep.ok) {
                ShareState.log("WARN 网络联动：新配置未通过自检，保留旧配置 —— " + (prep.error ?: ""))
                return
            }
            if (ProfileRunner.hotReload()) {
                ShareState.log("OK  网络联动：已热重载新配置（" + prep.nodeCount + " 节点）")
            } else {
                ShareState.log("WARN 网络联动：热重载失败，重启服务生效")
            }
            // 切策略组：等内核重载完再点，太快会点到旧进程
            if (hit.group.isNotBlank()) {
                val best = io.vpnshare.core.CoreApi.selectFastest(hit.group)
                ShareState.log(
                    if (best != null) "OK  网络联动：组「" + hit.group + "」已切到 " + best
                    else "WARN 网络联动：组「" + hit.group + "」没有可用节点"
                )
            }
        }.onFailure { ShareState.log("ERR 网络联动失败：" + it.message) }
    }
}