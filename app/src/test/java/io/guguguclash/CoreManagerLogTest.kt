package io.guguguclash

import io.guguguclash.core.CoreManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核日志的 logcat 降噪。
 *
 * 实测：log-level=info 时内核每建立一条连接打一行，几秒内就把 logcat 里 GuGuGu-clash
 * 这个 tag 的历史全部挤出去 —— 排障时打开 logcat 只看得到连接日志，
 * 启动 / 规则安装 / 出口探测那些真正有用的行一条不剩。
 */
class CoreManagerLogTest {

    @Test
    fun connectionLinesAreNotMirrored() {
        val tcp = "time=\"2026-10-03T10:39:20+08:00\" level=info msg=\"[TCP] 10.98.0.127:57075 --> 39.136.117.190:443 match IPCIDR(39.136.64.0/18) using 国内网站[DIRECT]\""
        assertFalse("每连接一条的 info 日志不该进 logcat", CoreManager.shouldMirrorToLogcat(tcp))
        val udp = "level=info msg=\"[UDP] 10.98.0.127:49954 --> 121.62.22.156:486 match IPCIDR using DIRECT\""
        assertFalse(CoreManager.shouldMirrorToLogcat(udp))
    }

    @Test
    fun livenessPrefersChildProcessThenRestForAdoptedCore() {
        // 有子进程句柄：只看它（不管 REST 通不通）
        assertTrue(CoreManager.alive(true, adopted = false, restUp = false))
        assertFalse(CoreManager.alive(false, adopted = true, restUp = true))
        // 复用的外部内核（没有句柄）：看外部信号（pgrep 或 REST，取或）
        assertTrue(CoreManager.alive(null, adopted = true, restUp = true))
        // 两条信号都说不在才算死 —— 单条抖动不该把整个共享停掉
        assertFalse("pgrep 与 REST 都说不在才算死", CoreManager.alive(null, adopted = true, restUp = false))
        // 既没句柄也没标记复用：当作没在跑
        assertFalse(CoreManager.alive(null, adopted = false, restUp = true))
    }

    @Test
    fun warningsErrorsAndStartupLinesAreMirrored() {
        assertTrue(CoreManager.shouldMirrorToLogcat("level=warning msg=\"dns: cannot resolve\""))
        assertTrue(CoreManager.shouldMirrorToLogcat("level=error msg=\"initial proxy provider error\""))
        assertTrue(CoreManager.shouldMirrorToLogcat("Start initial configuration in progress"))
        assertTrue(CoreManager.shouldMirrorToLogcat("[stderr] something happened"))
    }
}
