package io.vpnshare.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import io.vpnshare.R
import io.vpnshare.prefs.Prefs
import io.vpnshare.profile.CustomRule
import io.vpnshare.profile.RuleAction
import io.vpnshare.profile.RuleCatalog
import io.vpnshare.service.ShareState

/**
 * 规则页。一个页面管完所有规则，两份能力合并：
 *  · 内置分类：点一下循环切换 直连 → 走代理 → 拦截 → 关闭
 *  · 自定义规则：只需填「域名 / IP 段 / 关键字」，再选走法，匹配方式程序自动判断
 * 长按任意一行可删除自定义规则。
 */
class RulesActivity : BaseListActivity() {

    private val actions = listOf("DIRECT" to "直连", "PROXY" to "走代理", "REJECT" to "拦截")

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "规则"
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
        setSummary("点一行切换或编辑，长按删除。\n自定义规则排在内置规则之前，优先级最高。")

        addSectionHeader("内置分类")
        for (cat in RuleCatalog.ALL) {
            val action = p.ruleActions[cat.key]
            addEntry(
                R.drawable.ic_rules,
                cat.label,
                "内置 " + cat.entries + " 条 · 点一下切换走法",
                if (action == null) "已关闭" else CustomRule.actionLabel(action.name)
            ) { cycleAction(cat.key) }
        }

        addSectionHeader("自定义规则")
        val entries = editableRules(p.customRules)
        if (entries.isEmpty()) {
            addDetail(
                "还没有自定义规则",
                "点这一行添加。只需要填一个域名或 IP 段，再选「直连 / 走代理 / 拦截」。",
                "＋ 添加"
            ) { editRule(null, null) }
        }
        for (e in entries) {
            val d = CustomRule.describe(e.second)
            val row = addEntry(R.drawable.ic_rules, d.value, d.kind, CustomRule.actionLabel(d.action)) {
                editRule(e.first, e.second)
            }
            row.setOnLongClickListener { confirmDelete(e.first); true }
        }
        addEntry(R.drawable.ic_providers, "＋ 添加自定义规则", "填一个域名或 IP 段，再选走法") { editRule(null, null) }
        addEntry(R.drawable.ic_rules, "怎么填？看格式说明", "域名 / IP 段 / 关键字 / 包名，程序自动判断匹配方式") { showHelp() }
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("怎么填")
            .setMessage(
                "只需要一行填一个「要匹配什么」：\n\n" +
                    "  example.com      普通域名（含子域名）\n" +
                    "  github           域名里带这个字的都算\n" +
                    "  1.2.3.0/24       IP 段\n" +
                    "  com.tencent.mm   某个 App 的包名\n\n" +
                    "然后选一个走法：直连 / 走代理 / 拦截。\n\n" +
                    "匹配方式（域名后缀、IP 段、关键字…）由程序自动判断，不用你选。\n\n" +
                    "进阶：直接写完整规则也行，例如\n" +
                    "  GEOSITE,netflix,PROXY\n" +
                    "  DOMAIN-KEYWORD,github,PROXY\n" +
                    "只要内容里带逗号，就按完整规则处理。"
            )
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    // ---------------- 内置分类动作 ----------------

    private fun cycleAction(key: String) {
        val p = Prefs.load(this)
        val cur = p.ruleActions[key]
        val next: RuleAction? = when (cur) {
            RuleAction.DIRECT -> RuleAction.PROXY
            RuleAction.PROXY -> RuleAction.REJECT
            RuleAction.REJECT -> null
            null -> RuleAction.DIRECT
        }
        val m = LinkedHashMap(p.ruleActions)
        if (next == null) m.remove(key) else m[key] = next
        Prefs.save(this, p.copy(ruleActions = m))
        Toast.makeText(
            this,
            RuleCatalog.ALL.firstOrNull { it.key == key }?.label + " → " +
                (if (next == null) "已关闭" else CustomRule.actionLabel(next.name)),
            Toast.LENGTH_SHORT
        ).show()
        if (ShareState.running) Toast.makeText(this, "重启共享后生效", Toast.LENGTH_SHORT).show()
        render()
    }

    // ---------------- 自定义规则的增删改 ----------------

    /** 返回「原文行 -> 规范化规则」；跳过空行与注释 */
    private fun editableRules(raw: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (line in raw.split("\n")) {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) continue
            val r = CustomRule.parse(s)
            val parsedLine = r.line
            if (parsedLine != null) out.add(s to parsedLine)
        }
        return out
    }

    private fun editRule(original: String?, normalized: String?) {
        val d = normalized?.let { CustomRule.describe(it) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }
        box.addView(TextView(this).apply {
            text = "填一个域名、IP 段、关键字或包名；带逗号则按完整规则处理。"
            textSize = 12f
        })
        val f = EditText(this).apply {
            setText(d?.value ?: "")
            hint = "example.com"
            textSize = 14f
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(f)

        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        actions.forEachIndexed { i, pair ->
            val rb = RadioButton(this).apply {
                id = 1000 + i
                text = pair.second
                isChecked = (d?.action ?: "DIRECT") == pair.first
            }
            group.addView(rb)
        }
        box.addView(group)

        AlertDialog.Builder(this)
            .setTitle(if (original == null) "添加规则" else "编辑规则")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.action_save) { _, _ ->
                val picked = actions.getOrNull(group.checkedRadioButtonId - 1000)?.first ?: "DIRECT"
                val res = CustomRule.build(f.text.toString(), picked)
                if (res.line == null) {
                    Toast.makeText(this, res.error ?: "无法识别", Toast.LENGTH_LONG).show()
                } else {
                    val p = Prefs.load(this)
                    val newLine = res.line
                    val newRaw = if (original == null) {
                        if (p.customRules.isBlank()) newLine else p.customRules.trimEnd() + "\n" + newLine
                    } else {
                        p.customRules.split("\n").joinToString("\n") { if (it.trim() == original) newLine else it }
                    }
                    Prefs.save(this, p.copy(customRules = newRaw))
                    Toast.makeText(this, "已保存；重启共享后生效", Toast.LENGTH_SHORT).show()
                    render()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(original: String) {
        AlertDialog.Builder(this)
            .setTitle("删除这条规则？")
            .setMessage(original)
            .setPositiveButton("删除") { _, _ ->
                val p = Prefs.load(this)
                val kept = p.customRules.split("\n").filter { it.trim() != original }
                Prefs.save(this, p.copy(customRules = kept.joinToString("\n")))
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
