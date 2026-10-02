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
    private val lines = StringBuilder()
    private var mode = "内核"

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
        lines.setLength(0)
        tv.text = ""
        start()
    }

    private val pollApp = object : Runnable {
        override fun run() {
            if (!running || mode != "应用") return
            val recent = ShareState.recent(300)
            lines.setLength(0)
            for (l in recent) lines.append(l).append('\n')
            tv.text = lines.toString()
            status.text = "应用日志（" + recent.size + " 行）"
            main.postDelayed(this, 1000)
        }
    }

    private fun start() {
        running = true
        if (mode == "应用") {
            main.post(pollApp)
            return
        }
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
                        main.post { appendStream(line) }
                    }
                }
            } catch (e: Exception) {
                if (running) main.post { status.text = "内核日志中断：" + (e.message ?: "") }
            }
            if (running) main.post { status.text = "内核日志流已结束" }
        }.start()
    }

    private fun appendStream(line: String) {
        lines.append(line).append('\n')
        if (lines.length > 200000) lines.delete(0, 50000)
        tv.text = lines.toString()
    }

    private fun stop() {
        running = false
        main.removeCallbacks(pollApp)
        runCatching { streamCall?.cancel() }
        streamCall = null
    }

    private fun copy() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("vpnshare-logs", lines.toString()))
        Toast.makeText(this, "已复制 " + lines.length + " 字符", Toast.LENGTH_SHORT).show()
    }
}
