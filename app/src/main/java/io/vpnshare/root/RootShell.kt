package io.vpnshare.root

import java.util.concurrent.TimeUnit

/**
 * root 命令执行。移植自 Mygod/VPNHotspot 的 root/RootManager.kt 思路（Apache-2.0）：
 * 统一 su 会话、显式超时、stdout/stderr 并发读取避免死锁。
 */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String) {
        val ok: Boolean get() = code == 0
        val combined: String get() = (out + (if (err.isNotBlank()) "[stderr] " + err else "")).trim()
    }

    fun run(script: String, timeoutSec: Long = 40): Result {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder("su").start()
            val sbOut = StringBuilder()
            val sbErr = StringBuilder()
            val tOut = Thread { proc!!.inputStream.bufferedReader().use { sbOut.append(it.readText()) } }
            val tErr = Thread { proc!!.errorStream.bufferedReader().use { sbErr.append(it.readText()) } }
            tOut.start()
            tErr.start()
            proc.outputStream.use {
                it.write(script.toByteArray())
                it.write("\nexit\n".toByteArray())
                it.flush()
            }
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroy()
                Result(-1, sbOut.toString(), "timeout after " + timeoutSec + "s")
            } else {
                tOut.join(2000)
                tErr.join(2000)
                Result(proc.exitValue(), sbOut.toString(), sbErr.toString())
            }
        } catch (e: Exception) {
            Result(-1, "", e.message ?: e.toString())
        } finally {
            proc?.destroy()
        }
    }

    fun runScript(vararg lines: String, timeoutSec: Long = 40): Result =
        run(lines.joinToString("\n"), timeoutSec)

    fun isRooted(): Boolean = run("id").out.contains("uid=0")

    /** 取 su 实现标识，仅用于日志与排障 */
    fun suVendor(): String = run("su -v 2>/dev/null || echo unknown").out.trim()
}
