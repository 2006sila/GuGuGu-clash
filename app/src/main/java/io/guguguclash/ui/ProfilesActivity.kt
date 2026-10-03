package io.guguguclash.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.ScrollView
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.guguguclash.R
import io.guguguclash.prefs.Prefs
import io.guguguclash.profile.ProfileStore
import io.guguguclash.profile.SecretVault
import io.guguguclash.profile.SubImporter
import io.guguguclash.service.ShareService
import io.guguguclash.service.ShareState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配置（订阅）管理。对齐 CMFA 的 ProfilesActivity：
 * 多份配置并存 · 四种导入方式 · 激活切换 · 更新 · 重命名 · 删除 · 加密解锁。
 */
class ProfilesActivity : BaseListActivity() {

    private val store by lazy { ProfileStore(this) }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text.isNullOrBlank()) return@registerForActivityResult
        // 二维码里通常是订阅链接；也可能是整份配置文本
        if (text.startsWith("http://") || text.startsWith("https://")) importFromUrl(text.trim())
        else importFromText(text, "扫码导入")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "订阅"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        clear()
        val p = Prefs.load(this)
        val list = store.all()

        val encTip = if (p.encryptProfiles && !SecretVault.isUnlocked())
            "\n⚠ 配置已加密：点顶部「解锁」输入密码后才能使用" else ""
        setSummary("共 " + list.size + " 份配置。点一行设为当前，长按可重命名/更新/删除。" + encTip)

        if (p.encryptProfiles) {
            addEntry(
                R.drawable.ic_shield, if (SecretVault.isUnlocked()) "已解锁" else "解锁配置",
                if (SecretVault.isUnlocked()) "点这里立即上锁" else "输入密码以解密订阅文件"
            ) {
                if (SecretVault.isUnlocked()) { SecretVault.lock(); toast("已上锁"); render() } else askPassword()
            }
        }

        addSectionHeader("配置列表")
        if (list.isEmpty()) {
            addDetail("还没有配置", "点下面的按钮添加订阅：支持链接、剪贴板、文件和扫码。")
        }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        for (pf in list) {
            val active = pf.id == p.currentProfileId
            val nodes = store.originalText(pf)?.let { io.guguguclash.profile.SubFormat.countNodes(it) } ?: 0
            val u = io.guguguclash.profile.parseUserInfo(pf.userInfo)
            val body = StringBuilder()
            // 顺序按「有用程度」排：流量 > 到期 > 更新情况 > 链接。
            // 之前把 URL 放最前，它一占三行就把后面的流量挤出了 maxLines。
            if (u != null) {
                if (u.hasQuota) {
                    body.append("已用 ").append(io.guguguclash.util.Format.bytes(u.used))
                        .append(" / ").append(io.guguguclash.util.Format.bytes(u.total))
                        .append("　剩余 ").append(io.guguguclash.util.Format.bytes(u.remaining))
                        .append("（").append(u.percent).append("%）")
                } else {
                    body.append("已用 上传 ").append(io.guguguclash.util.Format.bytes(u.upload))
                        .append("　下载 ").append(io.guguguclash.util.Format.bytes(u.download))
                }
                u.expireDate()?.let { d ->
                    body.append("\n").append(if (u.isExpired()) "已到期 " else "到期 ").append(d)
                }
            }
            val effHours = pf.intervalHours.takeIf { it > 0 } ?: p.subUpdateHours
            body.append("\n更新于 ").append(if (pf.updatedAt > 0) df.format(Date(pf.updatedAt)) else "从未")
                .append("　·　自动更新 ").append(
                    if (effHours <= 0) "已关闭"
                    else "每 " + effHours + " 小时" + (if (pf.intervalHours > 0) "（本配置）" else "")
                )
            if (pf.url.isNotBlank()) body.append("\n").append(pf.url)
            val row = addEntry(
                R.drawable.ic_subscription,
                (if (active) "● " else "") + pf.name,
                body.toString(),
                if (nodes > 0) nodes.toString() + " 节点" else ""
            ) { activate(pf) }
            // 流量信息有多行，item_entry 的副标题限一行会被截断，这里放开
            row.findViewById<android.widget.TextView>(R.id.tvEntrySub).apply {
                maxLines = 12
                ellipsize = null
            }
            row.setOnLongClickListener { menu(pf); true }
        }

        addSectionHeader("添加")
        addEntry(R.drawable.ic_subscription, "＋ 从链接导入", "粘贴机场订阅地址（http/https）") { askUrl() }
        addEntry(R.drawable.ic_subscription, "＋ 从剪贴板导入", "已复制的订阅链接或配置文本") { fromClipboard() }
        addEntry(R.drawable.ic_subscription, "＋ 从文件导入", "选择 yaml / txt 配置文件") { fromFile() }
        addEntry(R.drawable.ic_subscription, "＋ 扫码导入", "扫描订阅二维码（链接或整份配置）") { scan() }

        addSectionHeader("更新")
        addEntry(R.drawable.ic_logs, "更新全部配置", "依次重新拉取每一份订阅") { updateAll() }
        addEntry(
            R.drawable.ic_settings, "自动更新间隔",
            if (p.subUpdateHours <= 0) "已关闭" else "每 " + p.subUpdateHours + " 小时（全局默认）"
        ) { intervalDialog(null) }

        // 供应商原先在主界面单独占一行，但它就是「这份订阅的用量与更新时间」——
        // 和订阅列表是同一件事的两个视角，合并后主界面省一行。
        addSectionHeader("供应商")
        addEntry(R.drawable.ic_providers, "供应商信息", "订阅供应商的更新时间与用量") {
            startActivity(Intent(this, ProvidersActivity::class.java))
        }

        addSectionHeader("高级")
        addEntry(
            R.drawable.ic_subscription, "订阅 UA",
            p.userAgent.ifBlank { "未设置" } + "（决定机场返回什么格式）"
        ) { uaDialog() }
        addEntry(
            R.drawable.ic_subscription, "下载与镜像",
            (if (p.downloadProxy.isBlank()) "下载代理：直连" else "下载代理：已设") +
                " · " + (if (p.ghMirror.isBlank()) "镜像：无" else "镜像：已设")
        ) { downloadDialog() }
        addEntry(
            R.drawable.ic_subscription, "订阅处理方式",
            if (p.profileMode == "adopt") "采纳订阅：保留它自带的策略组与规则（同 CMFA）"
            else "重建：只取节点，用内置策略组与规则"
        ) { profileModeDialog() }
    }

    // ---------------- 动作 ----------------

    private fun activate(pf: ProfileStore.Profile) {
        val p = Prefs.load(this)
        if (pf.id == p.currentProfileId) { toast("已经是当前配置"); return }
        Prefs.save(this, p.copy(currentProfileId = pf.id))
        toast("已切换到「" + pf.name + "」")
        if (ShareState.running) {
            toast("正在用新配置重启共享…")
            ShareService.stop(this)
            ShareService.start(this)
        }
        render()
    }

    private fun menu(pf: ProfileStore.Profile) {
        val items = arrayOf("激活", "重命名", "更新", "复制链接", "设置更新间隔", "删除")
        AlertDialog.Builder(this)
            .setTitle(pf.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> activate(pf)
                    1 -> rename(pf)
                    2 -> update(pf)
                    3 -> copyToClipboard(pf.url)
                    4 -> intervalDialog(pf)
                    5 -> confirmDelete(pf)
                }
            }
            .show()
    }

    private fun rename(pf: ProfileStore.Profile) {
        val f = EditText(this).apply { setText(pf.name); hint = "配置名称" }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(pad(f))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val n = f.text.toString().trim()
                if (n.isNotEmpty()) { store.upsert(pf.copy(name = n)); toast("已重命名"); render() }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun update(pf: ProfileStore.Profile) {
        if (pf.url.isBlank()) { toast("本地导入的配置无法在线更新，请重新导入"); return }
        val p = Prefs.load(this)
        toast("正在更新…")
        Thread {
            val r = SubImporter.update(this, pf, p.userAgent)
            runOnUiThread {
                toast(if (r.ok) "更新成功，" + r.nodeCount + " 个节点" else "更新失败：" + r.message)
                if (r.ok && pf.id == Prefs.load(this).currentProfileId && ShareState.running) {
                    ShareService.stop(this); ShareService.start(this)
                }
                render()
            }
        }.start()
    }

    private fun updateAll() {
        val list = store.all().filter { it.url.isNotBlank() }
        if (list.isEmpty()) { toast("没有可在线上更新的配置"); return }
        val p = Prefs.load(this)
        toast("开始更新 " + list.size + " 份…")
        Thread {
            var ok = 0
            for (pf in list) if (SubImporter.update(this, pf, p.userAgent).ok) ok++
            runOnUiThread {
                toast("更新完成 " + ok + "/" + list.size)
                if (ShareState.running) { ShareService.stop(this); ShareService.start(this) }
                render()
            }
        }.start()
    }

    /**
     * 设置自动更新间隔。pf 为 null 时改全局默认，否则只改这一份配置。
     * 机场如果下发了 profile-update-interval 头，导入时会自动填进来（优先级高于全局）。
     */
    private fun intervalDialog(pf: ProfileStore.Profile?) {
        val p = Prefs.load(this)
        val cur = if (pf == null) p.subUpdateHours else pf.intervalHours
        val f = EditText(this).apply {
            setText(cur.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "小时"
        }
        AlertDialog.Builder(this)
            .setTitle(if (pf == null) "全局自动更新间隔" else "「" + pf.name + "」的更新间隔")
            .setMessage(
                if (pf == null)
                    "单位：小时。填 0 表示关闭自动更新。\n建议 6～24 小时；太频繁会被机场限速乃至封号。\n\n单份配置可在长按菜单里单独设置，优先于全局。"
                else
                    "单位：小时。填 0 表示跟随全局设置（当前全局 " + p.subUpdateHours + " 小时）。"
            )
            .setView(pad(f))
            .setPositiveButton(R.string.action_save) { _, _ ->
                val h = (f.text.toString().toIntOrNull() ?: 12).coerceIn(0, 720)
                if (pf == null) {
                    Prefs.save(this, Prefs.load(this).copy(subUpdateHours = h))
                    toast(if (h <= 0) "已关闭自动更新" else "全局间隔：每 " + h + " 小时")
                } else {
                    store.upsert(pf.copy(intervalHours = h))
                    toast(if (h <= 0) "该配置改为跟随全局" else "该配置：每 " + h + " 小时")
                }
                // 间隔改了要让服务重新排程
                if (ShareState.running) {
                    ShareService.stop(this)
                    ShareService.start(this)
                    toast("正在重启共享以应用新的更新计划…")
                }
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 订阅 UA。这一项直接决定机场给什么：给 mihomo 系 UA 多半只回 base64 节点列表 */
    private fun uaDialog() {
        val p = Prefs.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        box.addView(TextView(this).apply {
            text = "机场按 User-Agent 决定返回什么：\n" +
                "  mihomo/xxx              多数机场只给 base64 节点列表（约 14 KB）\n" +
                "  ClashMetaForAndroid/…   给完整 Clash 配置（约 1.6 MB，含策略组与规则）\n\n" +
                "想用「采纳模式」保留机场自带策略组，就必须用后者。"
            textSize = 12f
        })
        val f = EditText(this).apply { setText(p.userAgent); textSize = 14f }
        box.addView(f)
        AlertDialog.Builder(this)
            .setTitle("订阅 UA")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.action_save) { _, _ ->
                val v = f.text.toString().trim()
                Prefs.save(this, Prefs.load(this).copy(userAgent = v.ifBlank { "ClashMetaForAndroid/2.11.35" }))
                toast("已保存；下次更新订阅生效")
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 下载代理与镜像前缀：手机直连不了 GitHub 时，内核与 geo 数据得靠它们下 */
    private fun downloadDialog() {
        val p = Prefs.load(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        box.addView(TextView(this).apply {
            text = "下载内核与 geo 数据时用。都留空则直连 GitHub。"
            textSize = 12f
        })
        box.addView(TextView(this).apply { text = "下载代理"; textSize = 12f })
        val fProxy = EditText(this).apply { setText(p.downloadProxy); textSize = 14f; hint = "http://192.168.43.1:7890" }
        box.addView(fProxy)
        box.addView(TextView(this).apply { text = "GitHub 镜像前缀"; textSize = 12f })
        val fMirror = EditText(this).apply { setText(p.ghMirror); textSize = 14f; hint = "https://ghproxy.example/" }
        box.addView(fMirror)
        AlertDialog.Builder(this)
            .setTitle("下载与镜像")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    downloadProxy = fProxy.text.toString().trim(),
                    ghMirror = fMirror.text.toString().trim()
                ))
                toast("已保存")
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 订阅处理方式：重建 vs 采纳。对齐 CMFA 的取舍。 */
    private fun profileModeDialog() {
        val p = Prefs.load(this)
        val opts = arrayOf(
            "采纳订阅 · 保留它自带的策略组与规则（同 CMFA）",
            "重建 · 只取节点，用内置策略组与规则"
        )
        val cur = if (p.profileMode == "adopt") 0 else 1
        AlertDialog.Builder(this)
            .setTitle("订阅处理方式")
            .setMessage(
                "很多机场的订阅里定义了好几个策略组（例如「手动切换」「GOOGLE」「BING」）。\n" +
                    "重建模式会把它们全丢掉，只留一个 PROXY 组；\n" +
                    "采纳模式保留订阅原文，只覆写端口/DNS/tun 这些受控段。\n\n" +
                    "注意：采纳模式下国内直连依赖订阅自带的规则；规则不全的话，\n" +
                    "可能不如重建模式省梯子流量。"
            )
            .setSingleChoiceItems(opts, cur) { d, which ->
                Prefs.save(this, Prefs.load(this).copy(profileMode = if (which == 0) "adopt" else "rebuild"))
                d.dismiss()
                toast("已保存；重启共享生效")
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(pf: ProfileStore.Profile) {
        AlertDialog.Builder(this)
            .setTitle("删除「" + pf.name + "」？")
            .setMessage("节点文件会一并删除，无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                store.remove(pf.id)
                val p = Prefs.load(this)
                if (p.currentProfileId == pf.id) Prefs.save(this, p.copy(currentProfileId = ""))
                toast("已删除")
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 导入 ----------------

    private fun askUrl() {
        val f = EditText(this).apply {
            hint = "https://example.com/sub?token=..."
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("从链接导入")
            .setView(pad(f))
            .setPositiveButton("导入") { _, _ ->
                val u = f.text.toString().trim()
                if (u.isBlank()) toast("请输入订阅链接") else importFromUrl(u)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun importFromUrl(url: String) {
        val p = Prefs.load(this)
        toast("正在下载订阅…")
        Thread {
            val r = SubImporter.import(this, url, p.userAgent)
            runOnUiThread {
                toast(if (r.ok) "导入成功，" + r.nodeCount + " 个节点" else "导入失败：" + r.message)
                if (r.ok) {
                    Prefs.save(this, Prefs.load(this).copy(currentProfileId = r.profileId))
                    if (ShareState.running) { ShareService.stop(this); ShareService.start(this) }
                }
                render()
            }
        }.start()
    }

    private fun fromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isBlank()) { toast("剪贴板是空的"); return }
        if (text.startsWith("http://") || text.startsWith("https://")) importFromUrl(text)
        else importFromText(text, "剪贴板导入")
    }

    private fun fromFile() {
        // 用系统文件选择器挑配置；内容读出来交给统一导入
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_FILE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val text = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) { toast("读取文件失败"); return }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "文件导入"
        importFromText(text, name)
    }

    private fun importFromText(text: String, name: String) {
        Thread {
            val r = SubImporter.importText(this, "local://" + System.currentTimeMillis(), text, "", name)
            runOnUiThread {
                toast(if (r.ok) "导入成功，" + r.nodeCount + " 个节点" else "导入失败：" + r.message)
                if (r.ok) {
                    Prefs.save(this, Prefs.load(this).copy(currentProfileId = r.profileId))
                    if (ShareState.running) { ShareService.stop(this); ShareService.start(this) }
                }
                render()
            }
        }.start()
    }

    private fun scan() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("对准订阅二维码")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        scanLauncher.launch(opts)
    }

    // ---------------- 密码 ----------------

    private fun askPassword() {
        val f = EditText(this).apply {
            hint = "配置密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("解锁配置")
            .setMessage("密码只在本次运行内保留，不会写入磁盘。")
            .setView(pad(f))
            .setPositiveButton("解锁") { _, _ ->
                val pw = f.text.toString()
                if (pw.isEmpty()) { toast("密码不能为空"); return@setPositiveButton }
                SecretVault.unlock(pw)
                // 用第一份配置试解，判断密码是否正确
                val first = store.all().firstOrNull()
                if (first != null && store.providerText(first) == null) {
                    SecretVault.lock()
                    toast("密码不正确")
                } else {
                    toast("已解锁")
                    if (ShareState.running) { ShareService.stop(this); ShareService.start(this) }
                }
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 杂项 ----------------

    private fun pad(v: android.view.View): android.view.View =
        android.widget.FrameLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(v)
        }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun copyToClipboard(s: String) {
        if (s.isBlank()) { toast("没有链接"); return }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("sub", s))
        toast("已复制")
    }

    companion object { private const val REQ_FILE = 1001 }
}
