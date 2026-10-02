package io.vpnshare.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import io.vpnshare.R
import java.io.File

/** 崩溃详情页：把堆栈完整展示出来，可一键复制。 */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.title = "应用崩溃了"
        val container = findViewById<android.widget.LinearLayout>(R.id.container)

        val body = runCatching { File(filesDir, "crash.txt").readText() }.getOrDefault("(读不到崩溃信息)")

        container.addView(android.widget.TextView(this).apply {
            setText(body)
            textSize = 11f
            setPadding(0, 24, 0, 24)
        })

        container.addView(MaterialButton(this, null, R.style.Widget_VpnShare_Button_Tonal).apply {
            text = "复制崩溃信息"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("crash", body))
                Toast.makeText(this@CrashActivity, "已复制", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
