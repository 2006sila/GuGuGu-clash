package io.vpnshare.ui



import android.content.Intent

import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.view.LayoutInflater
import android.view.View

import android.widget.ImageView
import android.widget.LinearLayout

import android.widget.TextView
import android.widget.Toast

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import io.vpnshare.R
import io.vpnshare.core.CoreDownloader
import io.vpnshare.prefs.Prefs

import io.vpnshare.profile.ProfileStore
import io.vpnshare.profile.CustomRule

import io.vpnshare.profile.SubImporter
import io.vpnshare.service.ShareService
import io.vpnshare.service.ShareState


/**
 * 主界面。视觉参照 ClashMetaForAndroid：顶部品牌行 + 一张大号状态卡（相当于它的
 * LargeActionCard，随运行状态换色换图标）+ 指标网格 + 分组入口列表。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var swEnable: MaterialSwitch
    private lateinit var cardAction: MaterialCardView
    private lateinit var ivActionIcon: ImageView
    private lateinit var tvActionTitle: TextView
    private lateinit var tvActionSub: TextView
    private lateinit var tvPhase: TextView

    private val statViews = LinkedHashMap<String, View>()
    private val trailViews = LinkedHashMap<String, TextView>()

    private val main = Handler(Looper.getMainLooper())
    private var suppress = false

    // ---- 后台统计缓存 ----
    //
    // 主界面 ticker 每 1 秒调一次 render()，而下面这三件事都很贵，绝不能放在里面：
    //   · PermissionCheck.missingCritical → RootShell.isRooted() **起一个 su 子进程并阻塞等待**
    //   · 同上还会 getInstalledPackages(0) 全量枚举应用列表
    //   · ProfileStore.providerText → 档案加密时会跑 **PBKDF2 12 万次迭代**（100-300ms 纯 CPU）
    // 放在 render() 里等于每秒在主线程烧掉几百毫秒 —— 掉帧、耗电，而且开着配置加密时尤其明显。
    // 现在改成：后台线程每 15 秒算一次，主线程只读缓存。
    @Volatile private var cachedMissingPerms = -1
    @Volatile private var cachedNodeCount = -1
    @Volatile private var statsBusy = false
    private var tickCount = 0

    private val ticker = object : Runnable {
        override fun run() {
            render()
            if (++tickCount % 15 == 0) refreshHeavyStats()
            main.postDelayed(this, 1000)
        }
    }

    /** 在后台线程刷新重活结果；主线程只读缓存，不阻塞 */
    private fun refreshHeavyStats() {
        if (statsBusy) return
        statsBusy = true
        Thread {
            val perms = runCatching {
                io.vpnshare.util.PermissionCheck.missingCritical(this)
            }.getOrDefault(-1)
            val nodes = runCatching {
                val store = ProfileStore(this)
                val prof = store.current(Prefs.load(this).currentProfileId)
                prof?.let { store.providerText(it)?.let { t -> io.vpnshare.profile.SubFormat.countNodes(t) } } ?: -1
            }.getOrDefault(-1)
            main.post {
                cachedMissingPerms = perms
                cachedNodeCount = nodes
                statsBusy = false
                render()
            }
        }.start()
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 深链冷启动：等界面就绪再弹确认框，否则对话框会挂在没有 window 的 Activity 上
        window.decorView.post { handleDeeplink(intent) }

        swEnable = findViewById(R.id.swEnable)
        cardAction = findViewById(R.id.cardAction)
        ivActionIcon = findViewById(R.id.ivActionIcon)
        tvActionTitle = findViewById(R.id.tvActionTitle)
        tvActionSub = findViewById(R.id.tvActionSub)
        tvPhase = findViewById(R.id.tvPhase)

        // 主题：单击循环三态。setDefaultNightMode 会让 Activity 自己重建，
        // 所以不需要手动刷新界面 —— 但也别在这里做别的 UI 操作，重建后就失效了。
        findViewById<android.widget.ImageButton>(R.id.btnTheme).setOnClickListener {
            val cur = Prefs.load(this).themeMode
            val next = when (cur) { "system" -> "light"; "light" -> "dark"; else -> "system" }
            Prefs.save(this, Prefs.load(this).copy(themeMode = next))
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                when (next) {
                    "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
            Toast.makeText(
                this,
                when (next) { "light" -> "已切到浅色"; "dark" -> "已切到深色"; else -> "已改为跟随系统" },
                Toast.LENGTH_SHORT
            ).show()
        }
        statViews["rules"] = findViewById(R.id.statRules)
        statViews["hint"] = findViewById(R.id.statHint)

        buildEntries()

        suppress = true
        swEnable.isChecked = Prefs.load(this).enabled || ShareState.running
        suppress = false
        // 开关画的是「上次的意愿」，不是「实际状态」。若意愿为开、但服务其实没在跑
        // （上次被系统回收 / 被 force-stop / 内核异常退出），就地把服务拉起来，
        // 让显示与真实一致。否则用户看到「开着却不生效」，必须先关再开才行。
        if (Prefs.load(this).enabled && !ShareState.running) {
            android.util.Log.i("VpnShare", "onCreate: 开关为开但服务未运行，自动恢复启动")
            ShareService.start(this)
        }
        swEnable.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            Prefs.save(this, Prefs.load(this).copy(enabled = checked))
            if (checked) ShareService.start(this) else ShareService.stop(this)
        }
        cardAction.setOnClickListener {
            val next = !(Prefs.load(this).enabled || ShareState.running)
            suppress = true
            swEnable.isChecked = next
            suppress = false
            Prefs.save(this, Prefs.load(this).copy(enabled = next))
            if (next) ShareService.start(this) else ShareService.stop(this)
        }

        render()
        // Android 13+ 通知是运行时权限。声明了不申请，前台服务的通知根本不会显示，
        // 用户就看不到流量与状态（实测「通知管理」一直是「不允许」）。这里主动申请一次。
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.app.ActivityCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 900
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeeplink(intent)
    }

    /**
     * 深链导入。刻意不静默导入 —— 链接可能来自任意网页，
     * 必须先把域名摆到用户面前让他确认。
     */
    private fun handleDeeplink(intent: Intent?) {
        val raw = intent?.dataString ?: return
        when (val r = io.vpnshare.util.DeepLink.parse(raw)) {
            is io.vpnshare.util.DeepLink.Result.Bad -> {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("链接无法识别")
                    .setMessage(r.reason + "\n\n正确的写法：\nvpnshare://import?url=<订阅地址>")
                    .setPositiveButton(R.string.action_ok, null)
                    .show()
            }
            is io.vpnshare.util.DeepLink.Result.Import -> {
                val host = io.vpnshare.util.DeepLink.hostOf(r.url)
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("导入这份订阅？")
                    .setMessage(
                        "来源：\n" + host + "\n\n" +
                            "完整地址：\n" + r.url + "\n\n" +
                            "只会新增为一份配置并切换过去，不会改动你已有的配置。"
                    )
                    .setPositiveButton("导入") { _, _ -> doDeeplinkImport(r.url) }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
    }

    private fun doDeeplinkImport(url: String) {
        Toast.makeText(this, "正在导入…", Toast.LENGTH_SHORT).show()
        Thread {
            val p = io.vpnshare.prefs.Prefs.load(this)
            val r = io.vpnshare.profile.SubImporter.import(this, url, p.userAgent, "")
            runOnUiThread {
                if (!r.ok) {
                    Toast.makeText(this, "导入失败：" + (r.message.ifBlank { "未知错误" }), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // 导入成功就直接切过去，深链的意图就是要用它
                r.profileId.takeIf { it.isNotBlank() }?.let { id ->
                    io.vpnshare.prefs.Prefs.save(this, io.vpnshare.prefs.Prefs.load(this).copy(currentProfileId = id))
                }
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    /**
     * 紧急恢复。电脑突然完全上不了网时，无条件把透明代理规则全摘掉。
     *
     * 刻意不判断任何状态：不检查服务是否在跑、不检查接口是否存在 ——
     * 出问题时这些判断本身可能就是不可信的，直接扫干净最可靠。
     * 副作用是手机自身的代理也会停（共享没了），这正是预期：先保证电脑能上网。
     */

    override fun onResume() {
        super.onResume()
        // 回到主界面就把重活刷一次（切配置、改权限后立刻反映）
        refreshHeavyStats()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    // ---------------- 入口列表 ----------------

    private class Entry(
        val key: String,
        val icon: Int,
        val title: Int,
        val sub: Int,
        val onClick: (MainActivity) -> Unit
    )

    // ---- 运行：每天都会点开的 ----
    private val entriesRuntime = listOf(
        Entry("nodes", R.drawable.ic_nodes, R.string.entry_nodes, R.string.sub_nodes) {
            it.startActivity(Intent(it, ProxyActivity::class.java))
        },
        Entry("connections", R.drawable.ic_connections, R.string.entry_connections, R.string.sub_connections) {
            it.startActivity(Intent(it, ConnectionsActivity::class.java))
        },
        Entry("clients", R.drawable.ic_clients, R.string.entry_clients, R.string.sub_clients) {
            it.startActivity(Intent(it, ClientsActivity::class.java))
        }
    )

    // ---- 配置：设一次放着不动的 ----
    private val entriesConfig = listOf(
        Entry("subscription", R.drawable.ic_subscription, R.string.entry_profiles, R.string.sub_profiles) {
            it.startActivity(Intent(it, ProfilesActivity::class.java))
        },
        Entry("split", R.drawable.ic_rules, R.string.entry_split, R.string.sub_split) {
            it.startActivity(Intent(it, SplitActivity::class.java))
        },
        Entry("network", R.drawable.ic_network, R.string.entry_network, R.string.sub_network) {
            it.startActivity(Intent(it, NetworkActivity::class.java))
        }
    )

    // ---- 排障：只在出问题时来 ----
    private val entriesAdvanced = listOf(
        Entry("diagnose", R.drawable.ic_diagnose, R.string.entry_diagnose, R.string.sub_diagnose) {
            it.startActivity(Intent(it, DiagnoseActivity::class.java))
        }
    )
    private fun buildEntries() {
        fill(R.id.groupRuntime, entriesRuntime)
        fill(R.id.groupConfig, entriesConfig)
        fill(R.id.groupAdvanced, entriesAdvanced)
    }

    private fun fill(containerId: Int, list: List<Entry>) {
        val container = findViewById<LinearLayout>(containerId)
        val inflater = LayoutInflater.from(this)
        for (e in list) {
            val row = inflater.inflate(R.layout.item_entry, container, false)
            row.findViewById<ImageView>(R.id.ivEntryIcon).setImageResource(e.icon)
            row.findViewById<TextView>(R.id.tvEntryTitle).setText(e.title)
            row.findViewById<TextView>(R.id.tvEntrySub).setText(e.sub)
            trailViews[e.key] = row.findViewById(R.id.tvEntryTrail)
            row.setOnClickListener { e.onClick(this) }
            container.addView(row)
        }
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        val p = Prefs.load(this)
        val running = ShareState.running
        val busy = ShareState.phase == ShareState.Phase.INSTALLING ||
            ShareState.phase == ShareState.Phase.STARTING_CORE ||
            ShareState.phase == ShareState.Phase.APPLYING_RULES ||
            ShareState.phase == ShareState.Phase.STOPPING

        tvPhase.text = phaseLabel()

        val containerColor: Int
        val iconRes: Int
        val titleRes: Int
        when {
            busy -> {
                containerColor = R.color.md_primary_container
                iconRes = R.drawable.ic_info
                titleRes = R.string.action_state_busy
            }
            ShareState.phase == ShareState.Phase.ERROR -> {
                containerColor = R.color.state_warn_container
                iconRes = R.drawable.ic_warning
                titleRes = R.string.action_state_error
            }
            running -> {
                containerColor = R.color.state_running_container
                iconRes = R.drawable.ic_check_circle
                titleRes = R.string.action_state_running
            }
            else -> {
                containerColor = R.color.state_stopped_container
                iconRes = R.drawable.ic_power
                titleRes = R.string.action_state_stopped
            }
        }
        cardAction.setCardBackgroundColor(getColor(containerColor))
        ivActionIcon.setImageResource(iconRes)
        ivActionIcon.imageTintList = android.content.res.ColorStateList.valueOf(
            getColor(
                when {
                    ShareState.phase == ShareState.Phase.ERROR -> R.color.state_warn_on_container
                    running -> R.color.state_running_on_container
                    else -> R.color.state_stopped_on_container
                }
            )
        )
        tvActionTitle.setText(titleRes)
        tvActionTitle.setTextColor(
            getColor(
                when {
                    ShareState.phase == ShareState.Phase.ERROR -> R.color.state_warn_on_container
                    running -> R.color.state_running_on_container
                    else -> R.color.state_stopped_on_container
                }
            )
        )
        tvActionSub.setTextColor(
            getColor(
                when {
                    ShareState.phase == ShareState.Phase.ERROR -> R.color.state_warn_on_container
                    running -> R.color.state_running_on_container
                    else -> R.color.state_stopped_on_container
                }
            )
        )
        tvActionSub.text = actionSubtitle(running)

        val sup = suppress
        suppress = true
        if (swEnable.isChecked != (p.enabled || running)) swEnable.isChecked = p.enabled || running
        suppress = sup

        // 指标
        val ruleCount = listOf(ShareState.tcpOk, ShareState.udpOk, ShareState.dnsOk).count { it }
        setStat("rules", getString(R.string.label_rules), ruleCount.toString() + " / 3")
        setStat("hint", getString(R.string.label_offload), when (ShareState.offloadDisabled) {
            true -> getString(R.string.value_offload_on)
            false -> getString(R.string.value_offload_off)
            null -> getString(R.string.value_offload_absent)
        })

        // 入口尾部文字。节点数走缓存 —— providerText 在档案加密时会跑 PBKDF2，
        // 每秒调一次等于每秒烧一次 CPU。
        val profile = ProfileStore(this).current(p.currentProfileId)
        val nodes = cachedNodeCount
        trailViews["nodes"]?.text = if (nodes >= 0) nodes.toString() + " 个" else ""

        // 连接入口显示实时速率：这是用户最常想知道的一件事，而且 ShareState 里现成有
        trailViews["connections"]?.text =
            if (running) "↓ " + io.vpnshare.util.Format.rate(ShareState.downRate) else ""

        // 订阅入口：优先显示剩余流量（比名字有用），没配额才退回名字
        val ui = profile?.userInfo?.let { io.vpnshare.profile.parseUserInfo(it) }
        trailViews["subscription"]?.text = when {
            ui == null -> profile?.name ?: ""
            ui.hasQuota -> "剩余 " + io.vpnshare.util.Format.bytes(ui.remaining)
            else -> "已用 " + io.vpnshare.util.Format.bytes(ui.used)
        }

        // 分流入口：规则改动很频繁，摘要比静态副标题有用
        trailViews["split"]?.text = run {
            val custom = io.vpnshare.profile.CustomRule.parseAll(p.customRules).lines.size
            when (p.appSplitMode) {
                "whitelist", "blacklist" -> "应用分流已开"
                else -> if (custom > 0) custom.toString() + " 条自定义" else ""
            }
        }

        // 网络入口：这是最常被切来切去的开关，状态必须一眼可见
        trailViews["network"]?.text = if (p.proxyPhoneTraffic) "手机也走代理" else ""

        // 诊断入口：权限缺失会造成「功能静默失效」，直接在主界面标红，别等用户自己翻。
        // 走缓存 —— 实时查会起 su 子进程 + 全量枚举应用列表。
        val missPerms = cachedMissingPerms
        trailViews["diagnose"]?.let { tv ->
            tv.text = if (missPerms <= 0) "" else missPerms.toString() + " 项待处理"
            tv.setTextColor(0xFFC62828.toInt())
        }
    }

    private fun phaseLabel(): String = when (ShareState.phase) {
        ShareState.Phase.IDLE -> getString(R.string.action_state_stopped)
        ShareState.Phase.RUNNING -> getString(R.string.action_state_running)
        ShareState.Phase.DEGRADED -> "降级"
        ShareState.Phase.ERROR -> getString(R.string.action_state_error)
        else -> getString(R.string.action_state_busy)
    }

    private fun actionSubtitle(running: Boolean): String {
        if (ShareState.phase == ShareState.Phase.ERROR && ShareState.detail.isNotBlank()) return ShareState.detail
        if (!running) {
            val missing = CoreDownloader.missing(this)
            return if (missing.isNotEmpty()) "缺 " + missing.joinToString("、")
            else getString(R.string.action_sub_stopped)
        }
        val parts = mutableListOf<String>()
        if (ShareState.iface.isNotBlank()) parts += "热点 " + ShareState.iface
        if (ShareState.coreVersion.isNotBlank()) parts += "内核 " + ShareState.coreVersion
        // 累计转发量（内核全局，含直连）+ 其中真正走节点、消耗机场配额的部分。
        // 括号里那个数才是「梯子用了多少」，两者通常差得很远。
        if (ShareState.hasTraffic) {
            parts += "已转发 " + io.vpnshare.util.Format.bytes(ShareState.totalBytes) +
                "（梯子 " + io.vpnshare.util.Format.bytes(ShareState.proxiedBytes) + "）"
        }
        return if (parts.isEmpty()) getString(R.string.action_state_running) else parts.joinToString("   ")
    }

    private fun setStat(key: String, label: String, value: String) {
        val root = statViews[key] ?: return
        root.findViewById<TextView>(R.id.tvStatLabel).text = label
        root.findViewById<TextView>(R.id.tvStatValue).text = value
    }

    // ---------------- 订阅 ----------------



    // ---------------- 内核与数据 ----------------







    // ---------------- 规则 / 设置 ----------------

    /**
     * 手机自身代理开关 = 内核 tun 开关。
     * 开启后手机会自己走代理，但 tun 会改系统默认路由，热点客户端的流量会被从
     * 路由层吸走——内核一异常，电脑就整段断网且删规则救不回来。所以默认关。
     */
}
