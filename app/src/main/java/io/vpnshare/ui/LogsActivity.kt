package io.vpnshare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import io.vpnshare.R
import io.vpnshare.service.ShareState
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 日志页：内核 /logs 流式日志 + 应用日志。
 * /logs 是 chunked 流式端点，必须用 readTimeout=0 的独立客户端，否则会一直读到超时。
 *
 * ## 为什么要攒批刷新
 *
 * 早期实现是「每来一行 → lines.toString() → tv.text = ...」。两个问题：
 *  1. 每次都要把整个缓冲区（上限 20 万字符）拷成字符串再让 TextView 全文重排，
 *     日志越长每次越贵 —— 总代价是 O(N²)，开几分钟必卡；
 *  2. 每一行还额外 post 一个 Runnable 到主线程。
 *
 * 现在改成：流线程只往 pending 缓冲里追加（加锁，不碰 UI），
 * 主线程每 250ms 合并一次并只保留尾部若干行，整页刷新从「每行一次」降到「每秒 4 次」。
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var tv: TextView
    private lateinit var status: TextView
    private val main = Handler(Looper.getMainLooper())
    private var running = true
    private var streamCall: Call? = null
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 已显示的内容（内核模式），只保留尾部 */
    private val lines = StringBuilder()
    /** 流线程写入、主线程取走的待刷缓冲 */
    private val pending = StringBuilder()
    private var mode = "内核"
    private var lastAppText = ""

    private companion object {
        /** 最多保留的行数与字符数。日志页是给人看的，不是归档 */
        const val MAX_LINES = 800
        const val MAX_CHARS = 80_000
        const val KEEP_CHARS = 60_000
        /** 攒批间隔 */
        const val FLUSH_MS = 250L
    }

    private val flush = object : Runnable {
        override fun run() {
            if (!running) return
            if (mode == "内核") {
                val chunk = synchronized(pending) {
                    if (pending.isEmpty()) return@synchronized ""
                    val s = pending.toString()
                    pending.setLength(0)
                    s
                }
                if (chunk.isNotEmpty()) {
                    lines.append(chunk)
                    trim()
                    tv.text = lines.toString()
                }
            }
            main.postDelayed(this, FLUSH_MS)
        }
    }

    private val pollApp = object : Runnable {
        override fun run() {
            if (!running || mode != "应用") return
            val recent = ShareState.recent(300)
            val text = recent.joinToString("\n")
            // 内容没变就不要 setText —— 每秒一次无谓的全文重排同样白烧
            if (text != lastAppText) {
                lastAppText = text
                tv.text = text
                status.text = "应用日志（" + recent.size + " 行）"
            }
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        title = "日志"

        val bar = findViewById<MaterialToolbar>(R.id.toolbar)
        bar.title = "日志"
        bar.setNavigationOnClickListener { finish() }
        tv = findViewById(R.id.tvLog)
        status = findViewById(R.id.tvStatus)
        findViewById<android.view.View>(R.id.btnKernelLog).setOnClickListener { switchTo("内核") }
        findViewById<android.view.View>(R.id.btnAppLog).setOnClickListener { switchTo("应用") }
        findViewById<android.view.View>(R.id.btnCopy).setOnClickListener { copy() }
    }

    override fun onResume() {
        super.onResume()
        start()
    }

    override fun onPause() {
        super.onPause()
        stop()
    }

    private fun switchTo(m: String) {
        stop()
        mode = m
        synchronized(pending) { pending.setLength(0) }
        lines.setLength(0)
        lastAppText = ""
        tv.text = ""
        start()
    }

    /**
     * /logs 每行是一个 JSON 对象：{"type":"info","payload":"[TCP] ... match ... using ..."}
     *
     * 直接显示原文就是一堆带转义的 JSON，可读性极差 —— 而这一页存在的意义就是给人看。
     * 取出 payload；解析失败就原样返回，不至于把内容吞掉。
     */
    private fun unwrap(raw: String): String {
        val s = raw.trim()
        if (!s.startsWith("{")) return raw
        return runCatching {
            val p = org.json.JSONObject(s).optString("payload", "")
            if (p.isEmpty()) raw else p
        }.getOrDefault(raw)
    }

    /** 只留尾部：按字符上限截断（行数上限靠日志行普遍较短时自然满足） */
    private fun trim() {
        if (lines.length > MAX_CHARS) lines.delete(0, lines.length - KEEP_CHARS)
        // 行数上限：从头部删到剩余 MAX_LINES 行
        var n = 0
        for (i in 0 until lines.length) if (lines[i] == '\n') n++
        if (n > MAX_LINES) {
            var seen = 0
            var cut = 0
            for (i in 0 until lines.length) {
                if (lines[i] == '\n') {
                    seen++
                    if (n - seen <= MAX_LINES) { cut = i + 1; break }
                }
            }
            if (cut > 0) lines.delete(0, cut)
        }
    }

    private fun start() {
        running = true
        if (mode == "应用") {
            main.post(pollApp)
            return
        }
        main.postDelayed(flush, FLUSH_MS)
        status.text = "正在连接内核日志流…"
        val call = streamClient.newCall(Request.Builder().url("http://127.0.0.1:9090/logs?level=info").build())
        streamCall = call
        Thread {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post { status.text = "内核日志不可用：HTTP " + resp.code }
                        return@use
                    }
                    main.post { status.text = "内核日志流已连接" }
                    val src = resp.body?.source() ?: return@use
                    while (running) {
                        val line = src.readUtf8Line() ?: break
                        // 解包成可读文本再进缓冲，不逐行 post 到主线程
                        val text = unwrap(line)
                        synchronized(pending) { pending.append(text).append('\n') }
                    }
                }
            } catch (e: Exception) {
                if (running) main.post { status.text = "内核日志中断：" + (e.message ?: "") }
            }
            if (running) main.post { status.text = "内核日志流已结束" }
        }.start()
    }

    private fun stop() {
        running = false
        main.removeCallbacks(flush)
        main.removeCallbacks(pollApp)
        runCatching { streamCall?.cancel() }
        streamCall = null
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val text = if (mode == "应用") lastAppText else lines.toString()
        cm?.setPrimaryClip(ClipData.newPlainText("vpnshare-logs", text))
        Toast.makeText(this, "已复制 " + text.length + " 字符", Toast.LENGTH_SHORT).show()
    }
}
