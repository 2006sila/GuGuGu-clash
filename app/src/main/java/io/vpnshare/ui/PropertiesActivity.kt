package io.vpnshare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import io.vpnshare.R
import io.vpnshare.core.CoreApi
import io.vpnshare.core.CoreInstaller
import io.vpnshare.core.CoreManager
import io.vpnshare.prefs.Prefs
import io.vpnshare.profile.ConfigBuilder
import io.vpnshare.profile.ProfileStore
import io.vpnshare.profile.parseUserInfo
import io.vpnshare.service.ShareState
import io.vpnshare.util.Format
import io.vpnshare.util.PermissionCheck

/**
 * 属性页：一屏看完「当前到底生效了什么」。
 *
 * 对齐 CMFA 的 PropertiesDesign。价值在于排障时用户能把整段复制出来，
 * 而不是被反复追问「你 DNS 设的啥」「端口多少」。
 *
 * 数据来源刻意分成两半：本地设置（Prefs）与内核实际生效值（REST /configs），
 * 两者不一致时正是问题所在，所以并排展示。
 */
class PropertiesActivity : BaseListActivity() {

    private val report = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "属性"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun row(section: String, k: String, v: String) {
        report.append(k).append("：").append(v).append('\n')
    }

    private fun render() {
        clear()
        report.setLength(0)
        val p = Prefs.load(this)
        val ctx: Context = this

        // ---- 身份 ----
        val verName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        val coreRunning = ShareState.running
        val coreVer = ShareState.coreVersion.ifBlank { "未运行" }

        addSectionHeader("身份")
        addDetail("应用", verName, "咕咕咕clash")
        addDetail("内核", coreVer, if (coreRunning) "运行中" else "已停止")
        addDetail("设备", Build.MANUFACTURER + " " + Build.MODEL, "Android " + Build.VERSION.RELEASE)
        row("应用版本", "v", verName)
        row("内核版本", "v", coreVer)
        row("设备", "v", Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)

        // ---- 共享 ----
        addSectionHeader("共享")
        addDetail("状态", phaseLabel(), if (coreRunning) "转发 " + Format.bytes(ShareState.totalBytes) else "")
        addDetail("热点接口", ShareState.iface.ifBlank { "—" }, "")
        addDetail("接管规则", (listOf(ShareState.tcpOk, ShareState.udpOk, ShareState.dnsOk).count { it }).toString() + " / 3", "")
        row("共享状态", "v", phaseLabel())
        row("热点接口", "v", ShareState.iface.ifBlank { "—" })
        row("已转发", "v", Format.bytes(ShareState.totalBytes))

        // ---- 端口 ----
        addSectionHeader("端口")
        addDetail("混合端口", p.mixedPort.toString(), if (p.mixedPort == 0) "未监听" else "")
        addDetail("重定向 / TPROXY", p.redirPort.toString() + " / " + p.tproxyPort.toString(), "热点共享用")
        addDetail("DNS", p.dnsPort.toString(), "")
        addDetail("HTTP / SOCKS", p.httpPort.toString() + " / " + p.socksPort.toString(), "0 = 不监听")
        row("端口", "v", "mixed=" + p.mixedPort + " redir=" + p.redirPort + " tproxy=" + p.tproxyPort + " dns=" + p.dnsPort + " http=" + p.httpPort + " socks=" + p.socksPort)

        // ---- 运行 ----
        addSectionHeader("运行")
        addDetail("运行模式", when (p.mode) { "global" -> "全局"; "direct" -> "直连"; else -> "规则" }, "日志 " + p.logLevel)
        addDetail("订阅处理", if (p.profileMode == "adopt") "采纳订阅（保留自带策略组与规则）" else "重建（只取节点）", "")
        addDetail("主题", when (p.themeMode) { "light" -> "强制浅色"; "dark" -> "强制深色"; else -> "跟随系统" }, "")
        addDetail("订阅 UA", p.userAgent, "决定机场返回什么格式")
        row("运行模式", "v", p.mode + " / 日志 " + p.logLevel)
        row("订阅处理", "v", p.profileMode)
        row("订阅 UA", "v", p.userAgent)

        // ---- DNS ----
        addSectionHeader("DNS")
        addDetail("模式", p.dnsEnhancedMode, "fake-ip 段 198.18.0.1/16")
        addDetail("上游", p.dnsNameserver.replace('\n', ' '), "")
        addDetail("回退", if (p.dnsFallback.isBlank()) "未设置" else p.dnsFallback.replace('\n', ' '), "")
        addDetail("fake-ip 过滤", p.dnsFakeIpFilterMode + " · " + p.dnsFakeIpFilter.split('\n').count { it.isNotBlank() }.toString() + " 条", "")
        row("DNS", "v", p.dnsEnhancedMode + " / " + p.dnsNameserver.replace('\n', ' '))

        // ---- TUN 与嗅探 ----
        addSectionHeader("TUN 与嗅探")
        addDetail("TUN", if (p.proxyPhoneTraffic) "已开启" else "未开启", "栈 " + p.tunStack)
        addDetail("手机自身代理", if (p.proxyPhoneTraffic) "开" else "关", "关时热点客户端仍走代理")
        addDetail("流量嗅探", if (p.sniffEnable) "已开启" else "未开启", if (p.sniffEnable) "HTTP " + p.sniffHttpPorts.replace('\n', ',') else "")
        row("TUN", "v", p.proxyPhoneTraffic.toString() + " / 栈 " + p.tunStack)
        row("嗅探", "v", p.sniffEnable.toString())

        // ---- 订阅 ----
        addSectionHeader("订阅")
        val store = ProfileStore(this)
        val prof = store.current(p.currentProfileId)
        if (prof == null) {
            addDetail("配置", "尚未导入", "")
            row("订阅", "v", "未导入")
        } else {
            addDetail("名称", prof.name, "")
            val ui = parseUserInfo(prof.userInfo)
            if (ui != null && ui.hasQuota) {
                addDetail("流量", "已用 " + Format.bytes(ui.used) + " / " + Format.bytes(ui.total), "剩余 " + Format.bytes(ui.remaining))
                ui.expireDate()?.let { addDetail("到期", it, if (ui.isExpired()) "已过期" else "") }
                row("订阅流量", "v", "已用 " + Format.bytes(ui.used) + " / " + Format.bytes(ui.total) + "，剩余 " + Format.bytes(ui.remaining))
            }
            val raw = store.originalText(prof)
            val nodes = raw?.let { io.vpnshare.profile.SubFormat.countNodes(it) } ?: 0
            val groups = raw?.let { ConfigBuilder.countTopLevelEntries(it, "proxy-groups") } ?: 0
            addDetail("规模", nodes.toString() + " 节点 · " + groups.toString() + " 策略组", "")
            row("订阅规模", "v", nodes.toString() + " 节点 / " + groups.toString() + " 策略组")
        }

        // ---- 权限 ----
        addSectionHeader("权限")
        val perms = PermissionCheck.all(this)
        val okN = perms.count { it.granted }
        addDetail("已就绪", okN.toString() + " / " + perms.size, perms.filterNot { it.granted }.joinToString("、") { it.title })
        row("权限", "v", okN.toString() + "/" + perms.size)
        addEntry(R.drawable.ic_shield, "打开权限检查", "逐项处理并用系统授权框修复") {
            startActivity(android.content.Intent(this, PermissionActivity::class.java))
        }

        addSectionHeader("导出")
        addButton("复制全部属性") {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("vpnshare-properties", report.toString()))
            Toast.makeText(this, "已复制，可直接粘贴给他人排障", Toast.LENGTH_SHORT).show()
        }
    }

    private fun phaseLabel(): String = when (ShareState.phase) {
        ShareState.Phase.IDLE -> "已停止"
        ShareState.Phase.INSTALLING -> "正在准备内核"
        ShareState.Phase.STARTING_CORE -> "正在启动内核"
        ShareState.Phase.APPLYING_RULES -> "正在装规则"
        ShareState.Phase.RUNNING -> "运行中"
        ShareState.Phase.DEGRADED -> "降级运行"
        ShareState.Phase.STOPPING -> "正在停止"
        ShareState.Phase.ERROR -> "出错"
    }
}