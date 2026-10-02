package io.vpnshare.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
import io.vpnshare.R
import io.vpnshare.core.CoreApi
import io.vpnshare.util.Format
import com.google.android.material.card.MaterialCardView

/**
 * 连接页：实时连接、命中的规则与出口、单条关闭。
 * 这是判断「B 站到底走没走节点」最直接的证据面。
 */
class ConnectionsActivity : BaseListActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var running = true
    private var showAll = true

    /**
     * 流量曲线嵌在本页顶部。
     *
     * 曲线本来是一个独立页面，但它就是连接的统计视图 —— 和「当前有哪些连接」
     * 是同一件事的两个时间尺度。放同一页后，用户看连接时顺带就能看到趋势，
     * 主界面也少一个入口。
     *
     * 实例只建一次：render() 每 2 秒跑一次，每次都 new 一个 View 会造成无谓抖动。
     * clear() 之后把它重新挂到 index 0 即可。
     */
    private lateinit var chart: RateChartView
    private lateinit var chartCard: MaterialCardView

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
        chartCard = card
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
        clear()
        // 曲线挂回顶部。clear() 摘掉的是 chartCard（container 的子 view），
        // chart 始终是 chartCard 的子 view，不需要也不应该再动它 ——
        // 曾经在这里多写了一句 chartCard.removeView(chart)，结果卡片挂回去时里面是空的。
        container.addView(chartCard)
        chart.invalidate()

        val shown = if (showAll) conns else conns.filter { it.chain.isNotEmpty() && it.chain != "DIRECT" }
        val viaNode = conns.count { it.chain.isNotEmpty() && it.chain != "DIRECT" }
        setSummary("共 " + conns.size + " 条：经节点 " + viaNode + "，直连 " + (conns.size - viaNode) +
            "\n每 2 秒刷新；点一行关闭该连接")

        addButton(if (showAll) "只看经节点的" else "显示全部") {
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

        if (shown.isEmpty()) {
            addEntry(R.drawable.ic_connections, "暂无连接", "打开浏览器或播放视频后再回来看看", "")
            return
        }

        for (c in shown.sortedByDescending { it.download + it.upload }) {
            val out = c.chain.ifBlank { "DIRECT" }
            val isDirect = c.chain.isEmpty() || c.chain == "DIRECT"
            addDetail(
                titleText = c.host.ifBlank { "(未知目标)" },
                bodyText = "规则 " + c.rule + "    出口 " + out,
                trailText = "下行 " + fmt(c.download) + "   上行 " + fmt(c.upload),
                onClick = {
                    Thread {
                        CoreApi.closeConnection(c.id)
                        main.post { refresh() }
                    }.start()
                }
            )
            if (!isDirect) {
                // 经节点的连接给个视觉提示：用入口行图标的方式不方便，这里靠 trail 已能区分
            }
        }
    }

    private fun fmt(n: Long): String = when {
        n < 1024 -> n.toString() + " B"
        n < 1048576 -> (n / 1024).toString() + " KB"
        else -> String.format("%.2f MB", n / 1048576.0)
    }
}
