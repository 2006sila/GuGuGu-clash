package io.guguguclash

import android.app.Application
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局异常兜底。对齐 CMFA 的 AppCrashedActivity：
 * 崩了要能看到原因，而不是只有一个「已停止运行」。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // 主题：跟随系统 / 强制浅色 / 强制深色。
        // 必须在任何 Activity 起来之前设置，否则第一帧会是系统默认主题。
        runCatching {
            val mode = io.guguguclash.prefs.Prefs.load(this).themeMode
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val head = "时间：" + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) +
                    "\n线程：" + t.name +
                    "\n机型：" + Build.MANUFACTURER + " " + Build.MODEL +
                    "\n系统：Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")" +
                    "\n\n"
                File(filesDir, "crash.txt").writeText(head + sw.toString())
            }
            runCatching {
                startActivity(Intent(this, io.guguguclash.ui.CrashActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            prev?.uncaughtException(t, e)
        }
    }
}
