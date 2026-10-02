package io.vpnshare.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.vpnshare.util.PermissionCheck
import io.vpnshare.R
import io.vpnshare.root.RootShell

/**
 * 权限自检。
 *
 * 存在的意义：这些权限缺了，功能不是报错而是**静默失效** ——
 * 通知权限没给，通知栏就是空的（我们踩过）；应用列表权限没给，
 * 应用分流页只剩自己一个 App；电池优化没加白名单，后台服务会被系统回收，
 * 表现为「共享莫名其妙断了」。用户很难自己定位到这一层。
 */
class PermissionActivity : BaseListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "权限检查"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---------------- 检查 ----------------

    // ---------------- 渲染 ----------------

    private fun render() {
        clear()
        val list = PermissionCheck.all(this)
        val okCount = list.count { it.granted }
        val missing = list.filterNot { it.granted }

        setSummary(
            okCount.toString() + " / " + list.size + " 项已就绪" +
                if (missing.isEmpty()) "\n所有权限都给了，功能不会因权限静默失效。"
                else "\n还有 " + missing.size + " 项没就绪：" +
                    missing.joinToString("、") { it.title } +
                    "\n点一行去处理；不确定的可以直接点「一键修复」。"
        )

        addSectionHeader("权限清单")
        for (it in list) {
            val row = addEntry(
                it.icon,
                it.title,
                it.desc,
                if (it.granted) "已就绪" else "待处理",
                onClick = { runFix(it.key) }
            )
            // 未就绪标红、已就绪标绿：一眼看出优先级
            row.findViewById<android.widget.TextView>(R.id.tvEntryTrail).setTextColor(
                if (it.granted) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
            )
        }

        if (missing.any { it.autoFixable }) {
            addSectionHeader("快捷操作")
            addEntry(R.drawable.ic_check_circle, "一键修复可自动处理的项", "会依次弹出系统授权框") {
                autoFixAll()
            }
        }

        addSectionHeader("说明")
        addDetail(
            "为什么需要这些",
            "这些权限有一个共同点：缺了之后程序不会报错，而是悄悄不干活。\n" +
                "例如通知权限没给，前台服务的通知就不会出现在通知栏；\n" +
                "你以为服务没在跑，其实它在正常共享 —— 只是没告诉你。"
        )
    }

    // ---------------- 修复动作 ----------------

    /** 按条目 key 分派修复动作。Item 本身不带闭包，是为了让检查逻辑能放进共用工具类。 */
    private fun runFix(key: String) {
        when (key) {
            "root" -> requestRoot()
            "notif" ->
                if (Build.VERSION.SDK_INT >= 33) requestPerms(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                else openNotifSettings()
            "applist" -> openAppDetails()
            "camera" -> requestPerms(arrayOf(android.Manifest.permission.CAMERA), REQ_CAMERA)
            "battery" -> requestBattery()
        }
    }

    private fun requestRoot() {
        Toast.makeText(this, "正在请求 root…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = runCatching { RootShell.run("id").out.contains("uid=0") }.getOrDefault(false)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this, "root 已授权", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("没拿到 root")
                        .setMessage(
                            "请在 Magisk / KernelSU / APatch 里给「咕咕咕clash」授予超级用户权限。\n\n" +
                                "授予后回到本页会自动刷新。"
                        )
                        .setPositiveButton(R.string.action_ok, null)
                        .show()
                }
                render()
            }
        }.start()
    }

    private fun requestPerms(perms: Array<String>, code: Int) {
        requestPermissions(perms, code)
    }

    private fun requestBattery() {
        @SuppressLint("BatteryLife")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", packageName, null))
        // 部分 ROM 没有这个 action，退回电池优化列表页
        if (runCatching { startActivity(direct) }.isFailure) {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openNotifSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }
    }

    private fun openAppDetails() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        }
    }

    private fun autoFixAll() {
        val todo = PermissionCheck.all(this).filterNot { it.granted }
        val perms = mutableListOf<String>()
        if (todo.any { it.key == "notif" } && Build.VERSION.SDK_INT >= 33) {
            perms += "android.permission.POST_NOTIFICATIONS"
        }
        if (todo.any { it.key == "camera" }) perms += "android.permission.CAMERA"
        if (perms.isNotEmpty()) requestPerms(perms.toTypedArray(), REQ_ALL)
        if (todo.any { it.key == "battery" }) requestBattery()
        if (todo.any { it.key == "root" }) requestRoot()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object {
        private const val REQ_NOTIF = 2001
        private const val REQ_CAMERA = 2002
        private const val REQ_ALL = 2003
    }
}