package io.guguguclash.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.guguguclash.R
import io.guguguclash.core.CoreApi
import io.guguguclash.service.ShareState

/**
 * 连接页：实时连接、命中的规则与出口、单条关闭。
 * 这是判断「B 站到底走没走节点」最直接的证据面。
 */
class ConnectionsActivity : BaseListActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var running = true
    private var showAll = true

    private lateinit var chart: RateChartView
    private lateinit var toggleBtn: MaterialButton
    private lateinit var emptyRow: View

    /**
     * 连接行容器。与 container 分开是为了能独立做差异更新。
     *
     * 改动原因：原来每 2 秒 clear()（= removeAllViews）+ 逐条重新 inflate。
     * 连接多的时候（几十条）等于每 2 秒造几十个新 View 并丢掉旧的，持续触发
     * GC 与整棵子树的重新测量。现在按连接 id 复用：只有「新增 / 消失 / 顺序变化」
     * 才动 view 树，其余情况只原地改文字。
     */
    private lateinit var rowsBox: LinearLayout
    private val rows = HashMap<String, View>()

    /** 上一次渲染的顺序。与本次相同说明结构没变，可以跳过重挂。 */
    private var lastOrder: List<String> = emptyList()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "连接"
        super.onCreate(savedInstanceState)
        showAll = getSharedPreferences("ui", MODE_PRIVATE).getBoolean("showAll", true)
        buildStaticUi()
    }

    /**
     * 一次性搭好静态骨架：曲线卡 + 两个按钮 + 空态行 + 行容器。
     * render() 之后只做差异更新，不再重建这些。
     */
    private fun buildStaticUi() {
        // 曲线嵌在本页顶部：它就是连接的统计视图，和「当前有哪些连接」是
        // 同一件事的两个时间尺度。放同一页后，看连接时顺带就能看到趋势。
        chart = RateChartView(this)
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (150 * resources.displayMetrics.density).toInt()
            ).apply { bottomMargin = resources.getDimensionPixelSize(R.dimen.card_gap) }
            addView(chart, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        container.addView(card)

        // 按钮保留引用，刷新时只改文案，不重建
        toggleBtn = addButton("只看经节点的") {
            showAll = !showAll
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("showAll", showAll).apply()
            refresh()
        }
        addButton("关闭全部连接") {
            Thread {
                CoreApi.closeAllConnections()
                main.post {
                    Toast.makeText(this, "已关闭全部", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            }.start()
        }

        emptyRow = addEntry(
            R.drawable.ic_connections, "暂无连接",
            "打开浏览器或播放视频后再回来看看", ""
        )
        emptyRow.visibility = View.GONE

        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(rowsBox)
    }

    override fun onResume() {
        super.onResume()
        running = true
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        running = false
        main.removeCallbacks(ticker)
    }

    private fun refresh() {
        Thread {
            val conns = CoreApi.connections()
            main.post { render(conns) }
        }.start()
    }

    private fun render(conns: List<CoreApi.Conn>) {
        val shown = if (showAll) conns else conns.filter { it.chain.isNotEmpty() && it.chain != "DIRECT" }
        val viaNode = conns.count { it.chain.isNotEmpty() && it.chain != "DIRECT" }
        setSummary("共 " + conns.size + " 条：经节点 " + viaNode + "，直连 " + (conns.size - viaNode) +
            " · 本次梯子 " + io.guguguclash.util.Format.bytes(ShareState.proxiedBytes) +
            "\n每 2 秒刷新；点一行关闭该连接")
        toggleBtn.text = if (showAll) "只看经节点的" else "显示全部"
        chart.invalidate()

        val want = shown.sortedByDescending { it.download + it.upload }
        val wantIds = want.map { it.id }

        // 1) 回收已经消失的连接行
        val gone = rows.keys.filter { it !in wantIds }
        if (gone.isNotEmpty()) {
            for (id in gone) rows.remove(id)?.let { rowsBox.removeView(it) }
        }

        // 2) 成员或顺序变了才重挂。重挂的是复用对象，不重新 inflate。
        if (wantIds != lastOrder) {
            rowsBox.removeAllViews()
            for (c in want) {
                rowsBox.addView(rows.getOrPut(c.id) { inflateRow(c.id) })
            }
            lastOrder = wantIds
        }

        // 3) 原地更新文字
        for (c in want) {
            val v = rows[c.id] ?: continue
            setText(v, R.id.tvDetailTitle, c.host.ifBlank { "(未知目标)" })
            setText(v, R.id.tvDetailBody, "规则 " + c.rule + "    出口 " + c.chain.ifBlank { "DIRECT" })
            setText(v, R.id.tvDetailTrail, "下行 " + fmt(c.download) + "   上行 " + fmt(c.upload))
        }

        val empty = want.isEmpty()
        emptyRow.visibility = if (empty) View.VISIBLE else View.GONE
        rowsBox.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /** 只在文字真的变了才 setText，避免每 2 秒对每一行都触发一次 requestLayout */
    private fun setText(row: View, id: Int, s: String) {
        val tv = row.findViewById<TextView>(id)
        if (tv.text.toString() != s) tv.text = s
    }

    private fun inflateRow(id: String): View =
        layoutInflater.inflate(R.layout.item_detail, rowsBox, false).apply {
            setOnClickListener {
                Thread {
                    CoreApi.closeConnection(id)
                    main.post { refresh() }
                }.start()
            }
        }

    private fun fmt(n: Long): String = when {
        n < 1024 -> n.toString() + " B"
        n < 1048576 -> (n / 1024).toString() + " KB"
        else -> String.format("%.2f MB", n / 1048576.0)
    }
}
