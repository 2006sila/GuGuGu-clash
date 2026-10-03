package io.guguguclash.ui

import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.guguguclash.R
import io.guguguclash.prefs.Prefs
import io.guguguclash.service.ShareService
import io.guguguclash.service.ShareState

/**
 * 网络 hub。CCMFA 把这类内容拆成 OverrideSettings / NetworkSettings / MetaFeatureSettings 三页，
 * 我们合成一页按主题分组 —— 页面数少了，但分组标题保证仍能一眼找到。
 *
 * 排序遵循全站统一原则：**开关类 → 配置类 → 只读信息 → 危险操作**。
 * 所以「手机自身代理」这种一按就变行为的开关在最上面，
 * 「控制接口」这种设定完就不动的在下面。
 *
 * 所有键名都已在内核二进制里核实存在，避免写出内核不认的配置导致起不来。
 */
class NetworkActivity : BaseListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "网络"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        clear()
        val p = Prefs.load(this)
        setSummary("这里的每一项都会写进内核配置。改动后需重启共享生效。\n不确定的先别动 —— 默认值已经调通。")

        // ---- 开关类：一按就改变行为，放最上面 ----
        addSectionHeader("开关")
        addEntry(
            R.drawable.ic_connections, "手机自身代理",
            if (p.proxyPhoneTraffic) "已开启：手机上的应用也走代理（会改路由）"
            else "已关闭：只有热点客户端走代理"
        ) {
            val next = !p.proxyPhoneTraffic
            Prefs.save(this, p.copy(proxyPhoneTraffic = next))
            android.widget.Toast.makeText(
                this,
                if (next) "已开启；重启共享生效" else "已关闭；重启共享生效",
                android.widget.Toast.LENGTH_LONG
            ).show()
            afterSave()
        }
        addEntry(
            R.drawable.ic_shield, "专用 DNS",
            if (p.managePrivateDns) "共享期间自动关闭（DoT 会绕过 DNS 劫持）"
            else "不处理（手机开着私人 DNS 时分流可能不准）"
        ) {
            val next = !p.managePrivateDns
            Prefs.save(this, p.copy(managePrivateDns = next))
            android.widget.Toast.makeText(
                this,
                if (next) "共享时自动关闭专用 DNS，停止时恢复原值；重启共享生效"
                else "已关闭该处理；重启共享生效",
                android.widget.Toast.LENGTH_LONG
            ).show()
            afterSave()
        }
        addEntry(
            R.drawable.ic_power, "开机自启",
            if (p.autoStartOnBoot) "已开启：开机后自动开始共享（需共享处于启用状态）"
            else "已关闭：开机后不会自动开始共享"
        ) {
            val next = !p.autoStartOnBoot
            Prefs.save(this, p.copy(autoStartOnBoot = next))
            android.widget.Toast.makeText(
                this,
                if (next) "开机后将自动开始共享" else "已关闭开机自启",
                android.widget.Toast.LENGTH_LONG
            ).show()
            afterSave()
        }
        addEntry(
            R.drawable.ic_shield, "代理 UDP",
            if (p.proxyUdp) "已开启：客户端的 UDP（含 QUIC）也走代理"
            else "已关闭：只代理 TCP，UDP 直连"
        ) {
            val next = !p.proxyUdp
            Prefs.save(this, p.copy(proxyUdp = next))
            android.widget.Toast.makeText(
                this,
                if (next) "UDP 也走代理；重启共享生效" else "UDP 直连；重启共享生效",
                android.widget.Toast.LENGTH_LONG
            ).show()
            afterSave()
        }
        addEntry(
            R.drawable.ic_shield, "阻断客户端 IPv6",
            if (p.blockIpv6) "已开启：客户端 IPv6 被丢弃，避免绕过代理"
            else "已关闭：客户端 IPv6 直连（可能绕过代理）"
        ) {
            val next = !p.blockIpv6
            Prefs.save(this, p.copy(blockIpv6 = next))
            android.widget.Toast.makeText(
                this,
                if (next) "客户端 IPv6 会被阻断；重启共享生效" else "客户端 IPv6 将直连；重启共享生效",
                android.widget.Toast.LENGTH_LONG
            ).show()
            afterSave()
        }
        addEntry(
            R.drawable.ic_connections, "流量嗅探",
            if (p.sniffEnable) "已开启：用我们的嗅探配置覆盖订阅自带的"
            else "未开启：保留订阅自带的嗅探配置"
        ) { sniffDialog() }

        // ---- 配置类：设完长期不变 ----
        addSectionHeader("运行")
        addEntry(R.drawable.ic_shield, "运行模式", when (p.mode) {
            "global" -> "全局：全部走代理（含热点客户端，忽略所有规则）"
            "direct" -> "直连：全部不走代理"
            else -> "规则：按规则分流"
        }) { modeDialog() }
        addEntry(R.drawable.ic_logs, "日志级别", p.logLevel + "（内核日志的详细程度）") { logLevelDialog() }
        addEntry(R.drawable.ic_settings, "监听端口", "混合 " + p.mixedPort + " · redir " + p.redirPort + " · tproxy " + p.tproxyPort + " · DNS " + p.dnsPort) { portDialog() }
        addEntry(R.drawable.ic_shield, "代理认证", if (p.authentication.isBlank()) "未设置（局域网内谁都能用）" else p.authentication.split("\n").size.toString() + " 组账号") { authDialog() }
        addEntry(
            R.drawable.ic_connections, "按网络自动切换",
            run {
                val n = io.guguguclash.service.NetworkRule.parse(p.networkRules).size
                if (n == 0) "未启用（可做到「回家自动切节点」）" else n.toString() + " 条规则"
            }
        ) { networkDialog() }

        addSectionHeader("DNS")
        addEntry(R.drawable.ic_providers, "DNS 服务器", "主 DNS " + p.dnsNameserver.split("\n").firstOrNull().orEmpty()) { dnsDialog() }
        addEntry(R.drawable.ic_providers, "DNS 分流策略", if (p.dnsNameServerPolicy.isBlank()) "未设置" else p.dnsNameServerPolicy.split("\n").size.toString() + " 条「域名=服务器」") { policyDialog() }
        addEntry(R.drawable.ic_providers, "fake-ip 过滤", p.dnsFakeIpFilterMode + " · " + p.dnsFakeIpFilter.split("\n").count { it.isNotBlank() }.toString() + " 条域名") { fakeIpDialog() }

        addSectionHeader("嗅探")
        addEntry(R.drawable.ic_connections, "嗅探例外", "强制/跳过域名 " + (p.sniffForceDomain.split("\n").count { it.isNotBlank() } + p.sniffSkipDomain.split("\n").count { it.isNotBlank() }).toString() + " 条") { sniffExDialog() }

        addSectionHeader("内核")
        addEntry(R.drawable.ic_core, "栈与并发", "栈 " + p.tunStack + " · TCP 并发 " + (if (p.tcpConcurrent) "开" else "关")) { coreDialog() }
        addEntry(R.drawable.ic_core, "进程匹配模式", p.findProcessMode) { processDialog() }
        addEntry(R.drawable.ic_core, "geo 数据源", if (listOf(p.geoxGeoip, p.geoxMmdb, p.geoxGeosite, p.geoxAsn).all { it.isBlank() }) "使用内置默认地址" else "已自定义") { geoxDialog() }

        addSectionHeader("外部控制")
        addEntry(R.drawable.ic_settings, "控制接口", p.extController + (if (p.extSecret.isBlank()) "" else " · 已设密码")) { extDialog() }
    }

    // ---------------- 网络联动 ----------------

    private fun networkDialog() {
        val p = Prefs.load(this)
        val cur = io.guguguclash.service.NetworkWatcher.currentIdentity(this)
        val box = column()
        box.addView(android.widget.TextView(this).apply {
            text = "每行一条：「网络标识 => 配置ID | 策略组名」。\n" +
                "网络标识可以是：\n" +
                "  mobile             移动数据\n" +
                "  wifi               任意 Wi-Fi（兜底）\n" +
                "  wifi:192.168.1.1   指定网关的 Wi-Fi\n\n" +
                "用网关而不是 Wi-Fi 名，是因为读 Wi-Fi 名需要位置权限；\n" +
                "网关同样能唯一区分网络，且不需要任何权限。\n\n" +
                "当前网络：" + (cur ?: "（识别不到）") + "\n" +
                "当前配置ID：" + p.currentProfileId
            textSize = 12f
        })
        val f = field(box, "联动规则", p.networkRules, multi = true)
        val pick = android.widget.Button(this).apply {
            text = "把当前网络填进去"
            setOnClickListener {
                val id = io.guguguclash.service.NetworkWatcher.currentIdentity(this@NetworkActivity)
                if (id == null) {
                    android.widget.Toast.makeText(this@NetworkActivity, "当前网络识别不到", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val line = id + " => " + Prefs.load(this@NetworkActivity).currentProfileId + " | "
                    f.setText((f.text.toString().trimEnd() + "\n" + line).trimStart('\n'))
                }
            }
        }
        box.addView(pick)
        AlertDialog.Builder(this)
            .setTitle("按网络自动切换")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(networkRules = f.text.toString()))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 节点 ----------------

    private fun modeDialog() {
        val p = Prefs.load(this)
        // 全局/直连是**内核级**开关，热点客户端（电脑）一起吃 —— 标签里必须写明，否则用户以为只影响手机
        val opts = arrayOf("规则（按分流规则，推荐）", "全局（全部走代理，含电脑）", "直连（全部不走代理，含电脑）")
        val cur = when (p.mode) { "global" -> 1; "direct" -> 2; else -> 0 }
        AlertDialog.Builder(this)
            .setTitle("运行模式")
            .setSingleChoiceItems(opts, cur) { d, which ->
                val mode = when (which) { 1 -> "global"; 2 -> "direct"; else -> "rule" }
                Prefs.save(this, Prefs.load(this).copy(mode = mode))
                d.dismiss()
                // 内核支持运行时切模式（PATCH /configs）：服务在跑就立刻生效，不必重启共享。
                // 切到全局时顺手给 GLOBAL 组兜一次节点 —— 它默认可能停在「直连」，那样全局就变成全直连。
                if (io.guguguclash.service.ShareState.running) {
                    Thread {
                        val ok = io.guguguclash.core.CoreApi.patchMode(mode)
                        if (ok && mode == "global") io.guguguclash.core.CoreApi.ensureAliveNode("GLOBAL")
                        runOnUiThread {
                            android.widget.Toast.makeText(
                                this,
                                if (ok) "已立即切换：" + opts[which] else "切换失败，重启共享后生效",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            afterSave()
                        }
                    }.start()
                } else {
                    afterSave()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun logLevelDialog() {
        val p = Prefs.load(this)
        val levels = arrayOf("info", "warning", "error", "debug", "silent")
        AlertDialog.Builder(this)
            .setTitle("日志级别")
            .setMessage("日志越详细越费电、越刷屏。排障时开 debug，平时 info 或 warning。")
            .setSingleChoiceItems(levels, levels.indexOf(p.logLevel).coerceAtLeast(0)) { d, which ->
                Prefs.save(this, Prefs.load(this).copy(logLevel = levels[which]))
                d.dismiss(); afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun portDialog() {
        val p = Prefs.load(this)
        val box = column()
        box.addView(TextView(this).apply {
            text = "填 0 表示不监听该端口。\n" +
                "混合/redir/tproxy/DNS 是热点共享用的，别随意改；\n" +
                "HTTP/SOCKS 是给本机其它程序当代理用的，不用可以留 0。"
            textSize = 12f
        })
        val fMixed = field(box, "混合端口（HTTP+SOCKS）", p.mixedPort.toString())
        val fRedir = field(box, "TCP redir 端口（热点 TCP）", p.redirPort.toString())
        val fTproxy = field(box, "UDP tproxy 端口（热点 UDP）", p.tproxyPort.toString())
        val fDns = field(box, "DNS 端口", p.dnsPort.toString())
        val fHttp = field(box, "HTTP 端口", p.httpPort.toString())
        val fSocks = field(box, "SOCKS 端口", p.socksPort.toString())
        val fIface = field(box, "热点接口覆盖（留空 = 自动检测）", p.ifaceOverride)
        AlertDialog.Builder(this)
            .setTitle("监听端口")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                fun num(e: EditText, def: Int, lo: Int) =
                    e.text.toString().toIntOrNull()?.coerceIn(lo, 65535) ?: def
                Prefs.save(this, Prefs.load(this).copy(
                    mixedPort = num(fMixed, p.mixedPort, 1),
                    redirPort = num(fRedir, p.redirPort, 0),
                    tproxyPort = num(fTproxy, p.tproxyPort, 0),
                    dnsPort = num(fDns, p.dnsPort, 0),
                    httpPort = num(fHttp, p.httpPort, 0),
                    socksPort = num(fSocks, p.socksPort, 0),
                    ifaceOverride = fIface.text.toString().trim()
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    private fun authDialog() {
        val p = Prefs.load(this)
        val box = column()
        box.addView(TextView(this).apply { text = "每行一组「用户名:密码」。留空则不鉴权。"; textSize = 12f })
        val f = field(box, "认证账号", p.authentication, multi = true)
        AlertDialog.Builder(this)
            .setTitle("代理认证")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(authentication = f.text.toString()))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun geoxDialog() {
        val p = Prefs.load(this)
        val box = column()
        box.addView(TextView(this).apply { text = "留空表示用内置默认地址。换成自建镜像可避免上游被墙。"; textSize = 12f })
        val f1 = field(box, "geoip.dat 地址", p.geoxGeoip)
        val f2 = field(box, "geoip.metadb 地址", p.geoxMmdb)
        val f3 = field(box, "geosite.dat 地址", p.geoxGeosite)
        val f4 = field(box, "GeoLite2-ASN.mmdb 地址", p.geoxAsn)
        AlertDialog.Builder(this)
            .setTitle("geo 数据源")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    geoxGeoip = f1.text.toString().trim(),
                    geoxMmdb = f2.text.toString().trim(),
                    geoxGeosite = f3.text.toString().trim(),
                    geoxAsn = f4.text.toString().trim()
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun sniffExDialog() {
        val p = Prefs.load(this)
        val box = column()
        val fForce = field(box, "强制采用嗅探结果的域名（每行一个）", p.sniffForceDomain, multi = true)
        val fSkip = field(box, "跳过嗅探的域名（每行一个）", p.sniffSkipDomain, multi = true)
        val fSrc = field(box, "跳过嗅探的来源地址（每行一个 CIDR）", p.sniffSkipSrc, multi = true)
        val fDst = field(box, "跳过嗅探的目标地址（每行一个 CIDR）", p.sniffSkipDst, multi = true)
        val cbPpi = check(box, "解析纯 IP 连接（parse-pure-ip）", p.sniffParsePureIp)
        AlertDialog.Builder(this)
            .setTitle("嗅探例外")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    sniffForceDomain = fForce.text.toString(),
                    sniffSkipDomain = fSkip.text.toString(),
                    sniffSkipSrc = fSrc.text.toString(),
                    sniffSkipDst = fDst.text.toString(),
                    sniffParsePureIp = cbPpi.isChecked
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 订阅处理 ----------------

    private fun dnsDialog() {
        val p = Prefs.load(this)
        val box = column()
        val fMain = field(box, "主 DNS（每行一个，可写 https:// 形式）", p.dnsNameserver, multi = true)
        val fDef = field(box, "默认 DNS（每行一个纯 IP，用于解析上面的 DoH 域名）", p.dnsDefaultNameserver, multi = true)
        val fFb = field(box, "回退 DNS（每行一个，留空=不启用）", p.dnsFallback, multi = true)
        val fCidr = field(box, "视为被污染的 IP 段（每行一个，命中则用回退）", p.dnsFallbackIpcidr, multi = true)
        val cbGeo = check(box, "回退过滤：按 IP 归属地判断", p.dnsFallbackGeoip)
        val fCode = field(box, "归属地代码", p.dnsFallbackGeoipCode)
        val fMode = field(box, "增强模式（fake-ip / redir-host）", p.dnsEnhancedMode)
        val cbHosts = check(box, "使用系统 hosts", p.dnsUseHosts)

        AlertDialog.Builder(this)
            .setTitle("DNS 设置")
            .setMessage("别把 1.1.1.1 / 8.8.8.8 填进回退 —— 国内连不上，会让节点域名解析超时。")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    dnsNameserver = fMain.text.toString(),
                    dnsDefaultNameserver = fDef.text.toString(),
                    dnsFallback = fFb.text.toString(),
                    dnsFallbackIpcidr = fCidr.text.toString(),
                    dnsFallbackGeoip = cbGeo.isChecked,
                    dnsFallbackGeoipCode = fCode.text.toString().trim().ifBlank { "CN" },
                    dnsEnhancedMode = if (fMode.text.toString().trim() == "redir-host") "redir-host" else "fake-ip",
                    dnsUseHosts = cbHosts.isChecked
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun fakeIpDialog() {
        val p = Prefs.load(this)
        val box = column()
        val f = field(box, "这些域名不做 fake-ip（每行一个，支持 *.lan / +.local 写法）", p.dnsFakeIpFilter, multi = true)
        val fMode = field(box, "过滤模式（blacklist / whitelist / rule）", p.dnsFakeIpFilterMode)
        AlertDialog.Builder(this)
            .setTitle("fake-ip 过滤")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    dnsFakeIpFilter = f.text.toString(),
                    dnsFakeIpFilterMode = fMode.text.toString().trim().ifBlank { "blacklist" }
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun policyDialog() {
        val p = Prefs.load(this)
        val box = column()
        box.addView(TextView(this).apply {
            text = "每行一条「域名=DNS服务器」，让特定域名走特定 DNS。\n例：\n  *.google.com=8.8.8.8\n  *.bilibili.com=114.114.114.114"
            textSize = 12f
        })
        val f = field(box, "分流策略", p.dnsNameServerPolicy, multi = true)
        AlertDialog.Builder(this)
            .setTitle("DNS 分流策略")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(dnsNameServerPolicy = f.text.toString()))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 嗅探 ----------------

    private fun sniffDialog() {
        val p = Prefs.load(this)
        val box = column()
        val cbOn = check(box, "启用流量嗅探（从 TLS/HTTP 握手里取域名）", p.sniffEnable)
        val cbOv = check(box, "嗅探到域名后覆盖目标（提升分流准确率）", p.sniffOverrideDest)
        val fHttp = field(box, "HTTP 端口", p.sniffHttpPorts)
        val fTls = field(box, "TLS 端口", p.sniffTlsPorts)
        val fQuic = field(box, "QUIC 端口", p.sniffQuicPorts)
        AlertDialog.Builder(this)
            .setTitle("嗅探设置")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    sniffEnable = cbOn.isChecked,
                    sniffOverrideDest = cbOv.isChecked,
                    sniffHttpPorts = fHttp.text.toString(),
                    sniffTlsPorts = fTls.text.toString(),
                    sniffQuicPorts = fQuic.text.toString()
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 内核与网络 ----------------

    private fun coreDialog() {
        val p = Prefs.load(this)
        val box = column()
        val fStack = field(box, "tun 栈（system / gvisor / mixed）", p.tunStack)
        val cbTcp = check(box, "TCP 并发（同时尝试多个出口，降低握手延迟）", p.tcpConcurrent)
        val cbUdl = check(box, "统一延迟（各出口用同一 URL 测速，更可比）", p.unifiedDelay)
        val cbGeo = check(box, "geodata 模式（用单个 dat 文件而非分离的 geosite/geoip）", p.geodataMode)
        AlertDialog.Builder(this)
            .setTitle("内核与网络")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    tunStack = fStack.text.toString().trim().ifBlank { "mixed" },
                    tcpConcurrent = cbTcp.isChecked,
                    unifiedDelay = cbUdl.isChecked,
                    geodataMode = cbGeo.isChecked
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun processDialog() {
        val p = Prefs.load(this)
        val opts = arrayOf("off（推荐，不反查进程）", "strict（每条连接都查，可能卡顿）", "always（始终查）")
        val cur = when (p.findProcessMode) { "strict" -> 1; "always" -> 2; else -> 0 }
        AlertDialog.Builder(this)
            .setTitle("进程匹配模式")
            .setSingleChoiceItems(opts, cur) { d, which ->
                Prefs.save(this, Prefs.load(this).copy(
                    findProcessMode = when (which) { 1 -> "strict"; 2 -> "always"; else -> "off" }
                ))
                d.dismiss()
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 外部控制 ----------------

    private fun extDialog() {
        val p = Prefs.load(this)
        val box = column()
        val fCtl = field(box, "监听地址（如 127.0.0.1:9090）", p.extController)
        val fSecret = field(box, "访问密码（留空=不鉴权）", p.extSecret)
        AlertDialog.Builder(this)
            .setTitle("外部控制接口")
            .setMessage("改地址会让 App 自己连不上内核的控制接口（节点/连接页失效），除非你知道自己在做什么。")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    extController = fCtl.text.toString().trim().ifBlank { "127.0.0.1:9090" },
                    extSecret = fSecret.text.toString().trim()
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 表单小工具 ----------------

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 8, 48, 8)
    }

    private fun scroll(v: android.view.View) = ScrollView(this).apply { addView(v) }

    private fun label(box: LinearLayout, t: String) {
        box.addView(TextView(this).apply { text = t; textSize = 12f; setPadding(0, 12, 0, 2) })
    }

    private fun field(box: LinearLayout, hint: String, value: String, multi: Boolean = false): EditText {
        label(box, hint)
        return EditText(this).apply {
            setText(value)
            textSize = 13f
            if (multi) { minLines = 3; gravity = android.view.Gravity.TOP } else inputType = InputType.TYPE_CLASS_TEXT
            box.addView(this)
        }
    }

    private fun check(box: LinearLayout, t: String, on: Boolean): CheckBox {
        val cb = CheckBox(this).apply { text = t; textSize = 13f; isChecked = on }
        box.addView(cb)
        return cb
    }

    private fun afterSave() {
        Toast.makeText(this, "已保存；重启共享后生效", Toast.LENGTH_SHORT).show()
        if (ShareState.running) {
            ShareService.stop(this)
            ShareService.start(this)
            Toast.makeText(this, "正在重启共享…", Toast.LENGTH_SHORT).show()
        }
        render()
    }
}
