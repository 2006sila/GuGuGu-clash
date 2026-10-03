package io.guguguclash.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.guguguclash.R
import io.guguguclash.prefs.Prefs
import io.guguguclash.service.ShareService
import io.guguguclash.service.ShareState

/**
 * 应用分流：决定哪些 App 走代理（对齐 CMFA 的 AccessControlActivity）。
 *
 * 落点是 tun 的 include-package / exclude-package —— 这两个键已在内核二进制中核实存在。
 * 注意：只在「手机自身代理」(tun) 开着时生效；热点客户端的流量来自电脑，
 * 与按包名分流无关。
 */
class AppSplitActivity : BaseListActivity() {

    private var mode = "off"
    private val picked = LinkedHashSet<String>()
    private var showSystem = false

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "应用分流"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val p = Prefs.load(this)
        mode = p.appSplitMode
        picked.clear()
        picked.addAll(p.appSplitPackages.split("\n").map { it.trim() }.filter { it.isNotEmpty() })
        render()
    }

    private fun render() {
        clear()
        setSummary(
            when (mode) {
                "whitelist" -> "白名单：只有勾选的 App 走代理（已选 " + picked.size + " 个）"
                "blacklist" -> "黑名单：勾选的 App 不走代理（已选 " + picked.size + " 个）"
                else -> "当前关闭。开启后只在「手机自身代理」打开时生效。"
            } + "\n改动需重启共享生效。"
        )

        addSectionHeader("模式")
        for ((v, label, sub) in listOf(
            Triple("off", "关闭分流", "所有 App 一视同仁"),
            Triple("whitelist", "白名单", "只有勾选的 App 走代理"),
            Triple("blacklist", "黑名单", "勾选的 App 直连，其余走代理")
        )) {
            addEntry(
                R.drawable.ic_shield, label, sub,
                if (mode == v) "当前" else ""
            ) {
                mode = v
                save()
                render()
            }
        }

        addSectionHeader("应用列表")
        addEntry(
            R.drawable.ic_settings, if (showSystem) "隐藏系统应用" else "显示系统应用",
            "系统应用通常不需要单独设置"
        ) { showSystem = !showSystem; render() }

        val pm = packageManager
        val apps = pm.getInstalledPackages(0).mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (sys && !showSystem) return@mapNotNull null
            Triple(pi.packageName, runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pi.packageName), sys)
        }.sortedBy { it.second }

        for ((pkg, label, sys) in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 18, 0, 18)
                isClickable = true
            }
            val cb = CheckBox(this).apply { isChecked = picked.contains(pkg); isClickable = false }
            row.addView(cb)
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@AppSplitActivity).apply {
                    text = label; textSize = 15f
                })
                addView(TextView(this@AppSplitActivity).apply {
                    text = pkg + if (sys) "  (系统)" else ""
                    textSize = 11f
                    setTextColor(0xFF888888.toInt())
                })
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener {
                if (picked.contains(pkg)) picked.remove(pkg) else picked.add(pkg)
                cb.isChecked = picked.contains(pkg)
                save()
                setSummary(
                    when (mode) {
                        "whitelist" -> "白名单：只有勾选的 App 走代理（已选 " + picked.size + " 个）"
                        "blacklist" -> "黑名单：勾选的 App 不走代理（已选 " + picked.size + " 个）"
                        else -> "当前关闭。先在上面选一个模式。"
                    } + "\n改动需重启共享生效。"
                )
            }
            container.addView(row)
        }

        if (mode != "off" && ShareState.running) {
            addSectionHeader("生效")
            addEntry(R.drawable.ic_power, "重启共享以生效", "当前共享正在运行，需要重启才会应用新分流") {
                ShareService.stop(this)
                ShareService.start(this)
                Toast.makeText(this, "正在重启共享…", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun save() {
        val p = Prefs.load(this)
        Prefs.save(this, p.copy(appSplitMode = mode, appSplitPackages = picked.joinToString("\n")))
    }
}
