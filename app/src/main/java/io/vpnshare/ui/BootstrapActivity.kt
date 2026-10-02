package io.vpnshare.ui

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import io.vpnshare.R
import io.vpnshare.core.CoreDownloader
import io.vpnshare.prefs.Prefs
import io.vpnshare.service.ShareState
import java.io.File

/**
 * 内核与规则数据。
 *
 * 原先是一个 AlertDialog（三个按钮挤在一起，说不清每个按钮干什么）。
 * 改成页面后信息量可以铺开：先告诉你缺什么、当前走不走代理，再给操作。
 *
 * 场景：内核二进制或 geo 数据没装全 / 损坏 / 需要更新时用。
 * 国内的常见障碍是直连不上 GitHub，所以「下载代理」「镜像前缀」必须在同一屏可见。
 */
class BootstrapActivity : BaseListActivity() {

    private val main = Handler(Looper.getMainLooper())

    private val pickCore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCore(uri)
    }
    private val pickGeo = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importGeo(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "内核与规则数据"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        clear()
        val missing = CoreDownloader.missing(this)
        val p = Prefs.load(this)

        // 只读信息在前：先让用户知道自己缺什么、下载会走哪条路
        addSectionHeader("状态")
        addDetail(
            "缺失项",
            if (missing.isEmpty()) "无，已就绪" else missing.joinToString("、"),
            if (missing.isEmpty()) "" else "需要下载"
        )
        addDetail("下载代理", p.downloadProxy.ifBlank { "直连" }, if (p.downloadProxy.isBlank()) "直连 GitHub" else "")
        addDetail("镜像前缀", p.ghMirror.ifBlank { "无" }, "")

        addSectionHeader("下载")
        addButton("在线下载（完整）") { doBootstrap(false) }
        addButton("在线下载（精简 geo，省流量）") { doBootstrap(true) }

        addSectionHeader("从文件导入")
        addDetail(
            "手机直连不了 GitHub 时用这个",
            "在电脑上下载好内核或 geo 文件，拷进手机后用下面按钮选中。\n" +
                "内核文件：mihomo-*.gz\n" +
                "geo 文件：geosite.dat、geoip.metadb"
        )
        addButton("选择内核文件") {
            pickCore.launch(arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*"))
        }
        addButton("选择 geo 文件（可多选）") { pickGeo.launch(arrayOf("*/*")) }

        addSectionHeader("提示")
        addDetail(
            "下载走哪条路",
            "若填了「下载代理」，下载会经该代理；否则走「镜像前缀」；都没有就直连 GitHub。\n" +
                "这两项在「订阅 → 高级」里改。"
        )
    }

    private fun doBootstrap(lite: Boolean) {
        ShareState.log("开始下载内核与规则数据" + (if (lite) "（精简版 geo）" else ""))
        val app = applicationContext
        val act = this
        Thread {
            val ok = CoreDownloader.bootstrap(app, lite) { step ->
                ShareState.log((if (step.ok) "OK  " else "ERR ") + step.label + "  " + step.detail)
            }
            main.post {
                Toast.makeText(app, if (ok) "内核与数据已就绪" else "部分项目失败，见日志", Toast.LENGTH_LONG).show()
                act.render()
            }
        }.start()
    }

    private fun importCore(uri: Uri) {
        val app = applicationContext
        Thread {
            val step = try {
                val tmp = File(cacheDir, "core-import.gz")
                contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                    ?: return@Thread
                CoreDownloader.importCoreGz(app, tmp)
            } catch (e: Exception) {
                CoreDownloader.Step("导入内核", false, e.message ?: "")
            }
            ShareState.log((if (step.ok) "OK  " else "ERR ") + step.label + "  " + step.detail)
            main.post { Toast.makeText(app, step.detail, Toast.LENGTH_LONG).show() }
        }.start()
    }

    private fun importGeo(uris: List<Uri>) {
        val app = applicationContext
        Thread {
            val dir = File(filesDir, CoreDownloader.GEO_DIR).apply { mkdirs() }
            var n = 0
            for (u in uris) {
                val name = queryName(u) ?: continue
                val target = when {
                    name.startsWith("geosite") && name.endsWith(".dat") -> "geosite.dat"
                    name.startsWith("geoip") && (name.endsWith(".metadb") || name.endsWith(".dat")) -> "geoip.metadb"
                    else -> continue
                }
                contentResolver.openInputStream(u)?.use { i -> File(dir, target).outputStream().use { i.copyTo(it) } }
                n++
                ShareState.log("导入 " + name + " -> " + target)
            }
            main.post {
                Toast.makeText(app, "导入 " + n + " 个 geo 文件", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun queryName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}