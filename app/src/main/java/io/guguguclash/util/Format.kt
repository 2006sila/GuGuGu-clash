package io.guguguclash.util

import java.util.Locale

/** 字节/速率格式化。对齐 CMFA 的显示风格：331.46 KiB、1.52 KiB/s。 */
object Format {

    fun bytes(n: Long): String {
        if (n < 1024) return n.toString() + " B"
        val units = arrayOf("KiB", "MiB", "GiB", "TiB")
        var v = n.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format(Locale.US, "%.2f %s", v, units[i])
    }

    fun rate(bytesPerSec: Double): String = bytes(bytesPerSec.toLong().coerceAtLeast(0)) + "/s"

    /** 通知栏用的紧凑形式 */
    fun pair(up: Long, down: Long): String = bytes(up) + "↑  " + bytes(down) + "↓"

    fun ratePair(up: Double, down: Double): String = rate(up) + "↑  " + rate(down) + "↓"
}
