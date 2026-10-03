package io.guguguclash.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import io.guguguclash.R
import io.guguguclash.core.CoreApi

/**
 * 节点页（对齐 CMFA 的 ProxyActivity）。
 *
 * 布局：策略组标签行 → 测速目标标签行 → 节点卡片网格（两列）。
 * · 卡片左边是节点名 + 类型，右边是大号延迟数字，选中项深蓝高亮
 * · 点测速目标（GOOGLE / BING / YANDEX…）会让内核并发测完整组再一次性回结果
 * · 右下角悬浮按钮 = 用当前目标重测一遍
 */
class ProxyActivity : BaseListActivity() {

    private val main = Handler(Looper.getMainLooper())

    private var groups: List<CoreApi.Group> = emptyList()
    private var current: String = ""
    private var version: String = ""
    private var testing = false

    /** 测速目标。第一条是「手动切换」，只展示选择状态、不发起测速。 */
    private val targets = listOf(
        // 测速必须走 https：unified-delay 的原理是减掉 TLS 握手耗时，
        // 配 http 反而算不出耗时、整组超时（实测 8006ms）。这类指标只能同协议内比较。
        Target("手动切换", null),
        Target("GOOGLE", "https://www.gstatic.com/generate_204"),
        Target("BING", "https://www.bing.com/generate_204"),
        Target("YANDEX", "https://yandex.com/generate_204"),
        Target("GITHUB", "https://github.com/generate_204")
    )
    private var activeTarget = 0

    /** 最近一次测速结果：节点名 -> 延迟(ms)。内核自己也会缓存，这里只用于立刻回显。 */
    private val delays = HashMap<String, Int>()

    private data class Target(val label: String, val url: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "节点"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------- 数据 ----------------

    private fun refresh() {
        Thread {
            val v = CoreApi.version()
            val gs = CoreApi.groups()
            main.post {
                version = v ?: ""
                groups = gs
                render()
            }
        }.start()
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        clear()
        fab.visibility = View.GONE

        if (version.isEmpty()) {
            setSummary("内核未运行，或 REST 接口未就绪（127.0.0.1:9090）")
            return
        }
        if (groups.isEmpty()) {
            setSummary("内核 " + version + "：暂无策略组，请先在「配置」页导入订阅")
            return
        }
        if (current.isEmpty() || groups.none { it.name == current }) {
            // 内核会同时给出 GLOBAL（全局，只有 DIRECT/REJECT/PROXY 三个虚拟项）
            // 和 PROXY（真正的节点组）。默认要落在有真实节点的那个组上，
            // 否则用户点进来看不到自己的 50 个节点。
            // 采纳模式下的组名由机场决定，五花八门（♻️ 手动切换 / 🚀 节点选择 / PROXY…），
            // 但总控组有个共性：候选节点最多、且名字里带「手动/选择/PROXY/节点」。
            // GLOBAL 是内核自带的，永远别作为默认 —— 它混进了 DIRECT/REJECT，没有参考价值。
            val real = groups.filterNot { it.name.equals("GLOBAL", true) }
            current = real.firstOrNull { g ->
                val n = g.name
                n.contains("手动") || n.contains("选择") || n.contains("节点")|| n.equals("PROXY", true)
            }?.name
                ?: real.maxByOrNull { it.nodes.size }?.name
                ?: groups.first().name
        }
        val g = groups.first { it.name == current }

        setSummary(
            "当前组 " + g.name + "   使用中 " + g.now + "   共 " + g.nodes.size + " 个节点\n" +
                (if (testing) "测速中…" else "点卡片切换，长按测单节点延迟；点上方测速目标整组测速")
        )

        // ① 策略组标签行
        if (groups.size > 1) {
            addSectionHeader("策略组")
            val ordered = groups.sortedWith(
                compareByDescending<CoreApi.Group> { it.name.equals("PROXY", true) }.thenByDescending { it.nodes.size }
            )
            container.addView(hScroll(tabRow(ordered.map { it.name }, current) { name ->
                current = name; render()
            }))
        }

        // ② 测速目标标签行
        addSectionHeader("测速目标")
        container.addView(hScroll(tabRow(targets.map { it.label }, targets[activeTarget].label) { label ->
            val idx = targets.indexOfFirst { it.label == label }
            if (idx >= 0) onTargetPicked(idx)
        }))

        // ③ 卡片网格
        addSectionHeader("节点（" + g.nodes.size + "）")
        container.addView(grid(g))

        // ④ 一键优选
        addSectionHeader("快捷")
        addEntry(R.drawable.ic_logs, "延迟历史", "每个节点最近几次测速，带趋势") { delayHistoryDialog() }
        addEntry(R.drawable.ic_check_circle, "自动选最快节点", "测完当前组并切到延迟最低的那个") {
            toast("正在测速并优选…")
            Thread {
                val best = CoreApi.selectFastest(g.name)
                main.post {
                    toast(if (best != null) "已切换到 " + best else "该组没有可用节点")
                    refresh()
                }
            }.start()
        }

        // ⑤ 悬浮测速按钮
        fab.visibility = View.VISIBLE
        fab.setImageResource(R.drawable.ic_power)
        fab.setOnClickListener {
            if (testing) { toast("正在测速…"); return@setOnClickListener }
            val url = targets[activeTarget].url ?: targets[1].url!!
            runTest(current, url)
        }
    }

    private fun hScroll(v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(v)
    }

    /** 一行可点标签，选中项加粗高亮 */
    private fun tabRow(labels: List<String>, selected: String, onPick: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (l in labels) {
            val on = l == selected
            row.addView(MaterialButton(this, null, R.style.Widget_GuGuGuClash_Button_Tonal).apply {
                text = l
                isAllCaps = false
                textSize = 13f
                // 显式指定配色：主题里 tonal 按钮本身就是深色，再叠 alpha 会变成
                // 「深底深字」看不清。选中=品牌深蓝+白字，未选中=浅底+灰字。
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(
                        this@ProxyActivity,
                        if (on) R.color.md_primary else R.color.node_card_bg
                    )
                )
                setTextColor(
                    if (on) 0xFFFFFFFF.toInt()
                    else ContextCompat.getColor(this@ProxyActivity, R.color.delay_unknown)
                )
                setOnClickListener { onPick(l) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) })
        }
        return row
    }

    /** 两列卡片网格。奇数个时补一个占位，避免最后一张被拉满整行。 */
    private fun grid(g: CoreApi.Group): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        g.nodes.forEachIndexed { i, n ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })
            }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i % 2 == 0) lp.marginEnd = dp(4) else lp.marginStart = dp(4)
            row!!.addView(card(n, n.name == g.now), lp)
        }
        if (g.nodes.size % 2 == 1) {
            row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(4) })
        }
        return grid
    }

    /** 单张节点卡片：左名称+类型，右大号延迟 */
    private fun card(n: CoreApi.Node, selected: Boolean): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(10), dp(12))
            background = ContextCompat.getDrawable(
                this@ProxyActivity,
                if (selected) R.drawable.bg_node_card_selected else R.drawable.bg_node_card
            )
            isClickable = true
            setOnClickListener { select(current, n.name) }
            setOnLongClickListener { testOne(n.name); true }
        }

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(TextView(this).apply {
            text = n.name
            textSize = 14f
            // 节点名带国旗 emoji 和「直连 0.1x」这类后缀，一行放不下会被截成
            // 「香港 直连 0…」。允许折两行。
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF212121.toInt())
        })
        left.addView(TextView(this).apply {
            text = n.type.ifBlank { "节点" }
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (selected) 0xCCFFFFFF.toInt() else 0xFF888888.toInt())
        })
        card.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val d = delays[n.name] ?: n.delay
        card.addView(TextView(this).apply {
            text = if (d >= 0) {
                // 延迟后面跟一个趋势箭头：↘ 变快、↗ 变慢、→ 稳定、无历史则不显示
                val arrow = when (io.guguguclash.profile.DelayHistory.trend(filesDir, n.name)) {
                    -1 -> " ↘"
                    1 -> " ↗"
                    0 -> " →"
                    else -> ""
                }
                d.toString() + arrow
            } else "—"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(
                when {
                    selected -> 0xFFFFFFFF.toInt()
                    d < 0 -> ContextCompat.getColor(this@ProxyActivity, R.color.delay_unknown)
                    d < 150 -> ContextCompat.getColor(this@ProxyActivity, R.color.delay_fast)
                    d < 350 -> ContextCompat.getColor(this@ProxyActivity, R.color.delay_mid)
                    else -> ContextCompat.getColor(this@ProxyActivity, R.color.delay_slow)
                }
            )
        })
        return card
    }

    // ---------------- 动作 ----------------

    private fun onTargetPicked(idx: Int) {
        activeTarget = idx
        val t = targets[idx]
        if (t.url == null) { render(); return }   // 「手动切换」不测速
        if (testing) { toast("正在测速…"); return }
        runTest(current, t.url)
    }

    private fun runTest(group: String, url: String) {
        testing = true
        render()
        Thread {
            val map = CoreApi.groupDelayMap(group, url, 5000)
            main.post {
                testing = false
                delays.clear()
                delays.putAll(map)
                // 落一条历史：单次延迟会被抖动骗到，趋势才有参考价值
                io.guguguclash.profile.DelayHistory.recordAll(filesDir, map)
                io.guguguclash.profile.DelayHistory.save(filesDir)
                val ok = map.count { it.value > 0 }
                toast("测速完成：" + ok + "/" + map.size + " 个可用")
                render()
            }
        }.start()
    }

    private fun select(group: String, node: String) {
        Thread {
            val ok = CoreApi.select(group, node)
            main.post {
                toast(if (ok) "已切换到 " + node else "切换失败")
                if (ok) refresh()
            }
        }.start()
    }

    private fun testOne(node: String) {
        toast("测速中：" + node)
        Thread {
            val d = CoreApi.testDelay(node)
            main.post {
                if (d > 0) delays[node] = d
                toast(node + " -> " + (if (d < 0) "超时" else d.toString() + " ms"))
                render()
            }
        }.start()
    }

    /** 延迟历史：按趋势排序，最近变慢的排前面 —— 那才是需要你处理的节点 */
    private fun delayHistoryDialog() {
        val all = io.guguguclash.profile.DelayHistory.all(filesDir)
        if (all.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("延迟历史")
                .setMessage("还没有测速记录。长按节点卡片测单个，或点上方测速目标测整组。")
                .setPositiveButton(R.string.action_ok, null)
                .show()
            return
        }
        val rows = all.entries
            .filter { it.value.isNotEmpty() }
            .sortedByDescending { io.guguguclash.profile.DelayHistory.trend(filesDir, it.key) ?: 0 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        for ((name, list) in rows) {
            val t = io.guguguclash.profile.DelayHistory.trend(filesDir, name)
            val arrow = when (t) { -1 -> "↘ 变快"; 1 -> "↗ 变慢"; 0 -> "→ 稳定"; else -> "样本少" }
            box.addView(TextView(this).apply {
                text = name + "\n" + list.joinToString(" / ") + " ms　" + arrow +
                    "　均 " + list.average().toInt() + " / 最低 " + list.min() + " / 最高 " + list.max()
                textSize = 13f
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        AlertDialog.Builder(this)
            .setTitle("延迟历史（按趋势排序）")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.action_ok, null)
            .setNeutralButton("清空") { _, _ ->
                io.guguguclash.profile.DelayHistory.clear(filesDir)
                toast("已清空延迟历史")
            }
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
