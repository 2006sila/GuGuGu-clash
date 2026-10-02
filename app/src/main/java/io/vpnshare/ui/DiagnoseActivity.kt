package io.vpnshare.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.vpnshare.R
import io.vpnshare.prefs.Prefs
import io.vpnshare.service.ShareService
import io.vpnshare.service.ShareState
import io.vpnshare.util.PermissionCheck

/**
 * 诊断 hub：出问题时来这里。
 *
 * 原先「权限检查」「属性」「内核与规则数据」「紧急恢复」「日志」是主界面五个平级入口，
 * 但它们只在出问题时才用得上 —— 平时占着主界面的位置，真出问题时又要在两屏里找。
 * 收成一页后，主界面腾出五行，这五项反而更集中。
 *
 * 排序遵循全站原则：**只读信息 → 修复动作 → 危险操作**。
 * 「紧急恢复」永远在最后 —— 它是有副作用的操作（会停掉共享）。
 */
class DiagnoseActivity : BaseListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "诊断"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        clear()

        val missing = PermissionCheck.missingCritical(this)
        setSummary(
            "出问题时来这里。\n" +
                if (missing == 0) "当前没有发现需要处理的项目。"
                else "有 " + missing + " 项待处理，见下面第一条。"
        )

        // ---- 只读信息：先让用户看到现状 ----
        addSectionHeader("现状")
        addEntry(
            R.drawable.ic_diagnose, "运行属性",
            "内核 " + (ShareState.coreVersion.ifBlank { "未运行" }) + " · 热点 " + (ShareState.iface.ifBlank { "—" })
        ) { startActivity(Intent(this, PropertiesActivity::class.java)) }
        addEntry(R.drawable.ic_core, "内核与规则数据", "下载或从文件导入内核与 geo 数据") {
            startActivity(Intent(this, BootstrapActivity::class.java))
        }

        // ---- 修复动作：能直接解决问题的 ----
        addSectionHeader("处理")
        addEntry(
            R.drawable.ic_shield, "权限检查",
            if (missing == 0) "已就绪" else missing.toString() + " 项待处理"
        ) { startActivity(Intent(this, PermissionActivity::class.java)) }
        addEntry(R.drawable.ic_logs, "日志", "内核流式日志与应用日志") {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        // ---- 危险操作：有副作用，放最后 ----
        addSectionHeader("应急")
        addEntry(
            R.drawable.ic_warning, "紧急恢复",
            "电脑上不了网时点这里，摘掉所有共享规则"
        ) { emergencyDialog() }
    }

    private fun emergencyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("紧急恢复")
            .setMessage(
                "会立即做三件事：\n\n" +
                    "1. 摘掉热点/USB/蓝牙共享上的全部透明代理规则\n" +
                    "2. 销毁自定义链，清掉策略路由\n" +
                    "3. 停止共享服务\n\n" +
                    "完成后电脑会回到「纯直连」，能正常上网。\n" +
                    "想恢复代理，重新打开上面的开关即可。\n\n" +
                    "什么时候用：电脑突然完全上不了网，而 App 的状态还显示正常。"
            )
            .setPositiveButton("立即恢复") { _, _ ->
                Toast.makeText(this, "正在清理…", Toast.LENGTH_SHORT).show()
                Thread {
                    // 先停服务，否则它可能立刻把规则又装回去
                    runCatching { ShareService.stop(this) }
                    Thread.sleep(500)
                    val r = io.vpnshare.tether.Emergency.run()
                    runOnUiThread {
                        if (r.ok) {
                            Toast.makeText(this, "已清理完毕，电脑应已恢复直连", Toast.LENGTH_LONG).show()
                        } else {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("清理后仍有残留")
                                .setMessage(
                                    "规则可能没摘干净。剩余内容：\n\n" +
                                        (r.remain.take(600).ifBlank { r.error.ifBlank { "（无输出）" } })
                                )
                                .setPositiveButton(R.string.action_ok, null)
                                .show()
                        }
                        render()
                    }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
