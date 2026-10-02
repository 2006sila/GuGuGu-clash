package io.vpnshare.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import io.vpnshare.R
import io.vpnshare.prefs.Prefs
import io.vpnshare.profile.ProfileRunner
import io.vpnshare.profile.RuleMatcher

/**
 * 规则命中查询。
 *
 * 排障最高频的问题：「这个域名到底走了哪条规则」。内核不会回答，
 * 只能按 rules 的先后顺序自己模拟一遍。
 *
 * 结论的可信度必须如实交代：GEOSITE / RULE-SET / GEOIP 这些依赖 geo 数据，
 * 本地判不了。如果它们排在命中规则之前，实际结果可能不同 —— 这一点必须写出来，
 * 否则用户会把它当成内核的真实行为。
 */
class RuleMatchActivity : BaseListActivity() {

    private var target = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "规则查询"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        clear()
        val ctx: Context = this

        if (target.isBlank()) {
            setSummary(
                "输入域名或 IP，查出它会命中哪条规则、落到哪个策略组。\n" +
                    "结果按内核的匹配顺序给出；判不了的规则会单独标出。"
            )
            addButton("输入域名或 IP 查询") { inputDialog() }
            addSectionHeader("可以试试")
            addDetail("域名", "比如 bilibili.com / www.google.com", "点上面按钮")
            addDetail("IP", "比如 8.8.8.8 / 10.0.0.1", "点上面按钮")
            addSectionHeader("说明")
            addDetail(
                "为什么有的规则判不了",
                "GEOSITE / RULE-SET / GEOIP 需要读取 ipcidr、geosite 等数据文件，\n" +
                    "PROCESS-NAME 需要知道发起请求的进程，DST-PORT 需要知道端口 ——\n" +
                    "这些在离线查询里都拿不到，所以标成「无法判定」而不是猜一个答案。"
            )
            return
        }

        val p = Prefs.load(ctx)
        val rulesText = runCatching { ProfileRunner.currentRuleLines(ctx, p) }.getOrNull()
        if (rulesText.isNullOrBlank()) {
            setSummary("还没拿到规则。请先导入订阅并启动一次共享。")
            addButton("重新输入") { inputDialog() }
            return
        }

        val r = RuleMatcher.match(rulesText, target)
        val total = RuleMatcher.parseRules(rulesText).size
        setSummary(
            "查询：" + r.target + "　（" + (if (r.isIp) "按 IP 判定" else "按域名判定") + "）\n" +
                "配置共 " + total + " 条规则"
        )
        addButton("换个目标") { inputDialog() }

        addSectionHeader("匹配结果")
        if (r.hits.isEmpty()) {
            addDetail("没有命中任何可判定的规则", "系统里也没有 MATCH 兜底 —— 配置可能不完整。", "")
        } else {
            for (h in r.hits) {
                addDetail(
                    "第 " + (h.index + 1) + " 条 · " + h.type,
                    h.raw + "\n" + h.reason,
                    "→ " + h.action
                )
            }
        }

        addSectionHeader("结论")
        val fin = r.finalAction
        addDetail(
            "最终动作",
            if (fin == null) "未命中任何规则（含 MATCH）—— 请检查配置是否完整" else fin,
            if (fin != null && (fin.equals("DIRECT", true) || fin.equals("REJECT", true))) "直出，不走代理" else "走策略组"
        )
        if (r.undecidable > 0) {
            val before = r.firstUndecidableAt ?: 0
            val hitAt = r.hits.firstOrNull()?.index ?: Int.MAX_VALUE
            addDetail(
                "可信度提示",
                "在你命中的位置之前，有 " + r.undecidable + " 条规则本地判不了\n（GEOSITE / RULE-SET / GEOIP / PROCESS 等，需要 geo 数据或请求上下文）。\n" +
                    if (before < hitAt)
                        "其中最早的一条排在第 " + (before + 1) + " 条，在你命中的规则之前 ——\n" +
                            "内核很可能先命中它，所以上面的结论仅供参考。"
                    else
                        "它们都排在命中规则之后，不影响本次结论。"
                ,
                ""
            )
        } else {
            addDetail("可信度", "命中之前没有无法判定的规则，结论与内核行为一致。", "")
        }
    }

    private fun inputDialog() {
        val f = EditText(this).apply {
            hint = "域名或 IP"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(target)
        }
        AlertDialog.Builder(this)
            .setTitle("查什么")
            .setView(f)
            .setPositiveButton("查询") { _, _ ->
                target = f.text.toString().trim()
                render()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}