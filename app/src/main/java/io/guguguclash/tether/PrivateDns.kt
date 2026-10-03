package io.guguguclash.tether

import io.guguguclash.root.RootShell

/**
 * 系统「专用 DNS」(private_dns_mode) 的开关。
 *
 * 为什么必须处理：Android 打开专用 DNS 后走 DoT/DoH，直接绕过我们 53 端口上的 DNS 劫持，
 * 表现为「规则都对、端口都在听，但客户端分流全乱」。
 * box4magisk / Surfing v7 都是无条件把它关掉，这里按 Prefs 开关来做，
 * 并且记下原值、停止共享时恢复。
 *
 * 取值实测：off / opportunistic / hostname:xxx。读不到返回 null。
 *
 * **键名各 ROM 不同**（本机实测教训）：一加 PJZ110 / ColorOS 16 / Android 16 上只有
 * `private_dns_default_mode`，AOSP 的 `private_dns_mode` 返回 null ——
 * 只认后者的写法（box4magisk / Surfing v7 也是只认它）在这台机器上等于空转。
 */
object PrivateDns {

    const val OFF = "off"

    /** 已实测存在的键名。顺序即优先级：先 AOSP，再厂商私有。 */
    val KEYS = listOf("private_dns_mode", "private_dns_default_mode")

    /** 键名 + 当前值。恢复时要写回同一个键，所以两者一起带着走。 */
    data class State(val key: String, val value: String)

    fun current(): State? {
        for (k in KEYS) {
            val out = RootShell.run("settings get global " + k).out.trim()
            if (out.isNotEmpty() && out != "null") return State(k, out)
        }
        return null
    }

    fun set(key: String, mode: String): RootShell.Result =
        RootShell.run("settings put global " + key + " " + mode + " && echo ok")

    fun isOff(value: String?): Boolean = value == OFF

    /**
     * 之前是 off、或压根读不到值时不必恢复。
     * 注意判据用「值」而不是「键」：键存在但值就是 off 时，写回一次也是无意义的动作。
     */
    fun shouldRestore(saved: State?): Boolean = saved != null && !isOff(saved.value) && saved.value.isNotBlank()
}
