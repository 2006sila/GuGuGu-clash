package io.vpnshare.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.vpnshare.R
import io.vpnshare.core.CoreApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 供应商页：节点/规则供应商的更新时间、条目数与套餐用量，点一行立即更新。 */
class ProvidersActivity : BaseListActivity() {

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "供应商"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        Thread {
            val proxies = CoreApi.proxyProviders()
            val rules = CoreApi.ruleProviders()
            main.post { render(proxies, rules) }
        }.start()
    }

    private fun render(proxies: List<CoreApi.Provider>, rules: List<CoreApi.Provider>) {
        clear()
        setSummary("节点供应商 " + proxies.size + " 个，规则供应商 " + rules.size + " 个\n" +
            "点一行立即更新；长按节点供应商做健康检查")

        addButton("刷新") { refresh() }

        section("节点供应商", proxies, true)
        section("规则供应商", rules, false)
    }

    private fun section(title: String, list: List<CoreApi.Provider>, isProxy: Boolean) {
        addSectionHeader(title)
        if (list.isEmpty()) {
            addEntry(R.drawable.ic_providers, "（无）", "导入订阅后这里会出现供应商", "")
            return
        }
        for (p in list) {
            val sub = StringBuilder()
            sub.append(p.type).append(" / ").append(p.vehicleType)
            sub.append("    ").append(p.itemCount).append(" 条")
            sub.append("\n更新 ").append(fmtTime(p.updatedAt))
            if (isProxy && p.total > 0) {
                sub.append("   用量 ").append(fmtBytes(p.download + p.upload)).append(" / ").append(fmtBytes(p.total))
                if (p.expire > 0) sub.append("   到期 ").append(fmtExpire(p.expire))
            }
            val row = addEntry(R.drawable.ic_providers, p.name, sub.toString(), "") { doUpdate(p) }
            if (isProxy) row.setOnLongClickListener { doHealth(p); true }
        }
    }

    private fun doUpdate(p: CoreApi.Provider) {
        Toast.makeText(this, "更新中：" + p.name, Toast.LENGTH_SHORT).show()
        Thread {
            val ok = CoreApi.updateProvider(p.kind, p.name)
            main.post {
                Toast.makeText(this, if (ok) "已更新 " + p.name else "更新失败 " + p.name, Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun doHealth(p: CoreApi.Provider) {
        Toast.makeText(this, "健康检查中：" + p.name, Toast.LENGTH_SHORT).show()
        Thread {
            val ok = CoreApi.healthCheckProvider(p.name)
            main.post {
                Toast.makeText(this, if (ok) "健康检查已提交" else "健康检查失败", Toast.LENGTH_SHORT).show()
                refresh()
            }
        }.start()
    }

    private fun fmtTime(iso: String): String {
        if (iso.isBlank() || iso.startsWith("0001")) return "—"
        for (pat in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss'Z'")) {
            try {
                val f = SimpleDateFormat(pat, Locale.US)
                f.timeZone = TimeZone.getTimeZone("UTC")
                val d: Date? = f.parse(iso.substringBefore('.'))
                if (d != null) {
                    val out = SimpleDateFormat("MM-dd HH:mm", Locale.US)
                    out.timeZone = TimeZone.getDefault()
                    return out.format(d)
                }
            } catch (_: Exception) {
            }
        }
        return iso.take(16)
    }

    private fun fmtExpire(sec: Long): String = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(sec * 1000L))
    } catch (_: Exception) {
        "—"
    }

    private fun fmtBytes(n: Long): String = when {
        n <= 0 -> "0"
        n < 1048576 -> (n / 1024).toString() + " KB"
        n < 1073741824 -> String.format("%.1f MB", n / 1048576.0)
        else -> String.format("%.2f GB", n / 1073741824.0)
    }
}
