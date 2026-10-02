package io.vpnshare.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.vpnshare.R
import io.vpnshare.prefs.Prefs
import io.vpnshare.profile.CustomRule
import io.vpnshare.profile.RuleCatalog
import io.vpnshare.service.ShareState

/**
 * 分流 hub：回答「什么流量走哪里」。
 *
 * 原先「规则」「应用分流」「节点过滤」「规则查询」是主界面四个平级入口，
 * 但它们其实是同一个问题的四个切面（按域名分、按 App 分、按节点分、查结果），
 * 合成一页后既能一眼看全，也不用在主界面占四行。
 *
 * 排序：规则 → 节点 → 应用（从通用到具体）
 */
class SplitActivity : BaseListActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "分流"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        clear()
        val p = Prefs.load(this)
        setSummary("决定什么流量走代理、走哪个节点。\n改动后需重启共享生效。")

        // 规则：内置 + 自定义合一，因为它们是同一件事的两个层次
        addSectionHeader("规则")
        val on = RuleCatalog.ALL.count { p.ruleActions[it.key] == io.vpnshare.profile.RuleAction.PROXY }
        val direct = RuleCatalog.ALL.count { p.ruleActions[it.key] == io.vpnshare.profile.RuleAction.DIRECT }
        val blocked = RuleCatalog.ALL.count { p.ruleActions[it.key] == io.vpnshare.profile.RuleAction.REJECT }
        addEntry(
            R.drawable.ic_rules, "内置分类",
            on.toString() + " 类走代理 · " + direct + " 类直连 · " + blocked + " 类拦截"
        ) { startActivity(Intent(this, RulesActivity::class.java)) }
        val custom = CustomRule.parseAll(p.customRules).lines.size
        addEntry(
            R.drawable.ic_rules, "自定义规则",
            if (custom == 0) "未添加（优先级高于内置分类）" else custom.toString() + " 条（优先级高于内置分类）"
        ) { startActivity(Intent(this, RulesActivity::class.java)) }

        // 节点：决定「走哪个节点」
        addSectionHeader("节点")
        val nodeSummary = buildString {
            val inc = p.nodeInclude.split('\n').count { it.isNotBlank() }
            val exc = p.nodeExclude.split('\n').count { it.isNotBlank() }
            val ren = p.nodeRenameRules.split('\n').count { it.isNotBlank() }
            if (inc + exc + ren == 0) append("未设置（保留全部节点、不改名）")
            else {
                if (inc > 0) append("只留 ").append(inc).append(" 类关键字")
                if (exc > 0) append(if (isNotEmpty()) " · " else "").append("剔除 ").append(exc).append(" 类关键字")
                if (ren > 0) append(if (isNotEmpty()) " · " else "").append(ren).append(" 条改名规则")
            }
        }
        addEntry(R.drawable.ic_nodes, "节点过滤与重命名", nodeSummary) { nodeTransformDialog() }

        // 自定义直连域名原在「设置」弹窗里，和分流规则是同一类东西，挪过来
        val directCount = p.customDirectDomains.count { it.isNotBlank() }
        addEntry(
            R.drawable.ic_rules, "直连域名",
            if (directCount == 0) "未添加（这些域名不走代理）" else directCount.toString() + " 个域名不走代理"
        ) { directDomainDialog() }

        // 应用：决定「哪个 App 走」
        addSectionHeader("应用")
        val split = when (p.appSplitMode) {
            "whitelist" -> "白名单：只代理选中应用"
            "blacklist" -> "黑名单：选中应用不走代理"
            else -> "已关闭"
        }
        addEntry(R.drawable.ic_clients, "应用分流", split + "（需手机自身代理开启）") {
            startActivity(Intent(this, AppSplitActivity::class.java))
        }

        // 排障：查结果。放在最后因为它不改变行为，只是回答「为什么」
        addSectionHeader("查证")
        addEntry(R.drawable.ic_diagnose, "规则查询", "输入域名或 IP，看命中哪条规则") {
            startActivity(Intent(this, RuleMatchActivity::class.java))
        }
    }

    private fun nodeTransformDialog() {
        val p = Prefs.load(this)
        val box = column()
        box.addView(android.widget.TextView(this).apply {
            text = "过滤：每行一个关键字，节点名含该关键字即算命中（不分大小写）。\n" +
                "改名：每行一条「正则=>新写法」，例：\n" +
                "  ^【.*?】 => （去掉方括号前缀）\n" +
                "  香港直连 => HK-Direct\n\n" +
                "注意：改完名字后，订阅自带策略组里的引用会一并同步，不会出现空引用。"
            textSize = 12f
        })
        val fInc = field(box, "只保留含这些关键字的节点（留空=全保留）", p.nodeInclude, multi = true)
        val fExc = field(box, "剔除含这些关键字的节点（优先于上面的保留）", p.nodeExclude, multi = true)
        val fRen = field(box, "重命名规则（正则=>新写法）", p.nodeRenameRules, multi = true)
        AlertDialog.Builder(this)
            .setTitle("节点过滤与重命名")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    nodeInclude = fInc.text.toString(),
                    nodeExclude = fExc.text.toString(),
                    nodeRenameRules = fRen.text.toString()
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 外观 ----------------

    private fun directDomainDialog() {
        val p = Prefs.load(this)
        val box = column()
        box.addView(TextView(this).apply {
            text = "每行一个域名，命中即直连。等价于 DOMAIN-SUFFIX 规则，但不用手写语法。"
            textSize = 12f
        })
        val f = field(box, "直连域名", p.customDirectDomains.joinToString("\n"), multi = true)
        AlertDialog.Builder(this)
            .setTitle("直连域名")
            .setView(scroll(box))
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.save(this, Prefs.load(this).copy(
                    customDirectDomains = f.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }
                ))
                afterSave()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------------- 表单辅助 ----------------

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(8))
    }

    private fun scroll(v: android.view.View) = ScrollView(this).apply { addView(v) }

    private fun label(box: LinearLayout, t: String) {
        box.addView(TextView(this).apply {
            text = t
            textSize = 12f
            setPadding(0, dp(10), 0, dp(2))
        })
    }

    private fun field(box: LinearLayout, hint: String, value: String, multi: Boolean = false): EditText {
        label(box, hint)
        return EditText(this).apply {
            setText(value)
            inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT
            if (multi) { minLines = 3; maxLines = 8 }
        }.also { box.addView(it) }
    }

    private fun check(box: LinearLayout, t: String, on: Boolean): CheckBox =
        CheckBox(this).apply { text = t; isChecked = on }.also { box.addView(it) }

    private fun afterSave() {
        if (ShareState.running) {
            Toast.makeText(this, "已保存；重启共享后生效", Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}