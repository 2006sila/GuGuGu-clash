package io.vpnshare.tether

import io.vpnshare.root.RootShell

/**
 * 控制 Android 全局设置 tether_offload_disabled。
 * 移植自 Mygod/VPNHotspot 的 net/TetherOffloadManager.kt（Apache-2.0）。
 * Android 10+ 共享硬件卸载会绕过 netfilter，不关掉则规则对部分流量无效。
 */
object TetherOffload {

    const val KEY = "tether_offload_disabled"

    /** null 表示该 ROM 没有这个 key */
    fun current(): Int? {
        val out = RootShell.run("settings get global " + KEY).out.trim()
        if (out.isEmpty() || out == "null") return null
        return out.toIntOrNull()
    }

    fun supported(): Boolean = current() != null

    fun setDisabled(disabled: Boolean): RootShell.Result =
        RootShell.run("settings put global " + KEY + " " + (if (disabled) 1 else 0) + " && echo ok")
}
