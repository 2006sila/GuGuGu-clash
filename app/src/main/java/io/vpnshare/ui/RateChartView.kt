package io.vpnshare.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import io.vpnshare.service.ShareState
import io.vpnshare.util.Format

/**
 * 速率曲线。自绘，不引第三方图表库。
 *
 * 画法：下载在上、上传在下，共用一条时间轴。上下分开比双线叠放更好读 ——
 * 下载通常比上传大一个数量级，叠在一起上传会被压成一条贴地的线。
 */
class RateChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val grid = Paint().apply { color = 0x1F000000; strokeWidth = 1f }
    private val axis = Paint().apply { color = 0x3F000000; strokeWidth = 2f }
    private val downLine = Paint().apply { color = 0xFF1976D2.toInt(); strokeWidth = 3f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val downFill = Paint().apply { color = 0x261976D2; style = Paint.Style.FILL }
    private val upLine = Paint().apply { color = 0xFFE65100.toInt(); strokeWidth = 3f; isAntiAlias = true; style = Paint.Style.STROKE }
    private val upFill = Paint().apply { color = 0x26E65100; style = Paint.Style.FILL }
    private val label = Paint().apply { color = 0xFF888888.toInt(); textSize = 26f; isAntiAlias = true }

    /** 0 = 全部；否则只画最近这么多分钟 */
    var windowMinutes: Int = 0
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        var s = ShareState.samples()
        if (windowMinutes > 0) {
            val cut = System.currentTimeMillis() - windowMinutes * 60_000L
            s = s.filter { it.at >= cut }
        }

        val mid = h / 2f
        // 网格 + 中线
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, grid)
        }
        canvas.drawLine(0f, mid, w, mid, axis)

        if (s.size < 2) {
            canvas.drawText("等待采样…（共享运行后每 2 秒记一条）", 16f, mid - 12f, label)
            return
        }

        val maxDown = s.maxOf { it.down }.coerceAtLeast(1.0)
        val maxUp = s.maxOf { it.up }.coerceAtLeast(1.0)
        val dx = w / (s.size - 1).toFloat()

        // 下载：中线向上
        val dp = Path()
        val df = Path()
        df.moveTo(0f, mid)
        s.forEachIndexed { i, p ->
            val x = dx * i
            val y = mid - (p.down / maxDown * (mid - 8f)).toFloat()
            if (i == 0) dp.moveTo(x, y) else dp.lineTo(x, y)
            df.lineTo(x, y)
        }
        df.lineTo(w, mid); df.close()
        canvas.drawPath(df, downFill)
        canvas.drawPath(dp, downLine)

        // 上传：中线向下
        val up = Path()
        val uf = Path()
        uf.moveTo(0f, mid)
        s.forEachIndexed { i, p ->
            val x = dx * i
            val y = mid + (p.up / maxUp * (mid - 8f)).toFloat()
            if (i == 0) up.moveTo(x, y) else up.lineTo(x, y)
            uf.lineTo(x, y)
        }
        uf.lineTo(w, mid); uf.close()
        canvas.drawPath(uf, upFill)
        canvas.drawPath(up, upLine)

        // 峰值标注：曲线的高度必须有刻度，否则只能看个大概形状
        canvas.drawText("↓ 峰 " + Format.rate(maxDown), 12f, 30f, label)
        canvas.drawText("↑ 峰 " + Format.rate(maxUp), 12f, h - 12f, label)
        canvas.drawText(s.size.toString() + " 个采样", w - 170f, h - 12f, label)
    }
}