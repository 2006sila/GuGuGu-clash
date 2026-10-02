package io.vpnshare.core

import io.vpnshare.root.RootShell
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 内核进程管理。
 *
 * 刻意不 daemon 化：把 su 会话本身当作 supervisor，内核算作它的子进程，
 * App/服务死亡时内核随之终止，不留野进程。
 *
 * 两个踩过的坑，都体现在脚本里：
 *  1) 不能用 set -e —— 某些设备上 su 会话的 shell 重定向写文件会被 SELinux 拒
 *     （touch 能成功但 > 和 rm 被拒），set -e 会因此在 exec 之前就中止脚本，
 *     内核永远起不来，表现为「内核未在 8 秒内响应 REST API」。
 *  2) 不写 pid 文件 —— 同理，落盘动作反而成了失败点。清理改用 pkill 兜底。
 */
object CoreManager {

    private const val RUN_SCRIPT =
        "cd " + CoreInstaller.BASE + "\n" +
        "# 只有确实需要新起内核时才执行到这里（调用方已确认没有存活内核）。\n" +
        "# 这行曾在「重开 App」时把正在服务的旧内核杀掉，导致热点客户端断流。\n" +
        "pkill -f 'vpnshare/bin/mihomo' 2>/dev/null\n" +
        "sleep 0.3\n" +
        "exec " + CoreInstaller.CORE + " -d " + CoreInstaller.CONF

    private val lock = Any()

    /**
     * 内核运行身份。实测本机（一加 PJZ110 / ColorOS 16）：
     *   uid 10249（App 自己）→ 无网络：App 被 force-stop 后其 uid 的网络会被系统掐断，
     *                          内核借这个 uid 跑必然 dial 节点 i/o timeout / dns 解析失败
     *   uid 2000（shell）    → 网络完整可用，同一配置 0.49s 打通
     * 所以内核固定以 shell 身份运行；root 只用来装 iptables 规则。
     */
    const val RUN_AS_UID = 2000
    private var proc: Process? = null
    private val ring = ArrayDeque<String>()
    private var listener: ((String) -> Unit)? = null
    private var logFile: File? = null

    fun setLogListener(l: ((String) -> Unit)?) {
        synchronized(lock) { listener = l }
    }

    /** 内核 stdout/stderr 同时落盘，便于 adb 取证 */
    fun setLogFile(f: File?) {
        synchronized(lock) {
            logFile = f
            runCatching {
                f?.parentFile?.mkdirs()
                f?.writeText("")
            }
        }
    }

    fun isRunning(): Boolean = synchronized(lock) { proc?.isAlive == true }

    /**
     * 启动内核。
     *
     * runAsUid 非空时用 su <uid> 把内核降到该 uid 运行。
     * 必须这么做：实测本机（一加 PJZ110 / ColorOS 16）会丢弃 root 进程走移动数据的出站，
     * 内核以 root 跑就永远连不上代理节点（dial tcp ... i/o timeout），
     * 而降到 App 自己的 uid 后同一节点 0.5 秒就通。
     * tun 模式仍需 root，那种情况传 null。
     */
    fun start(onLog: (String) -> Unit, onExit: ((Int) -> Unit)? = null, runAsUid: Int? = null): Boolean {
        synchronized(lock) {
            if (proc?.isAlive == true) return true
            return try {
                val cmd = if (runAsUid != null && runAsUid > 0) listOf("su", runAsUid.toString()) else listOf("su")
                android.util.Log.i("VpnShare", "CoreManager.start: " + cmd.joinToString(" ") +
                    " (runAsUid=" + (runAsUid ?: -1) + ")，脚本=\n" + RUN_SCRIPT)
                val p = ProcessBuilder(cmd).start()
                proc = p
                p.outputStream.use {
                    it.write(RUN_SCRIPT.toByteArray())
                    it.write("\n".toByteArray())
                    it.flush()
                }
                Thread { pump(p.inputStream.bufferedReader(), onLog, "OUT") }.start()
                Thread { pump(p.errorStream.bufferedReader(), onLog, "ERR") }.start()
                if (onExit != null) {
                    Thread {
                        val code = runCatching { p.waitFor() }.getOrDefault(-1)
                        onExit(code)
                    }.start()
                }
                true
            } catch (e: Exception) {
                onLog("[启动失败] " + (e.message ?: "unknown"))
                false
            }
        }
    }

    private fun pump(reader: BufferedReader, onLog: (String) -> Unit, tag: String) {
        try {
            reader.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    android.util.Log.i("VpnShare", "core[" + tag + "] " + line)
                    val text = if (tag == "ERR") "[stderr] " + line else line
                    synchronized(lock) {
                        ring.addLast(text)
                        while (ring.size > 800) ring.removeFirst()
                    }
                    appendToFile(text)
                    onLog(text)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun appendToFile(line: String) {
        val f = synchronized(lock) { logFile } ?: return
        runCatching {
            if (f.exists() && f.length() > 1_500_000) f.writeText("")
            f.appendText(line + "\n")
        }
    }

    /**
     * 停止内核。
     *
     * **顺序很关键**：先按进程名强杀，再清理 proc 引用。
     *
     * 早期实现是反过来的 —— 先 destroy() 那个 su 进程、waitFor(3 秒)，最后才 pkill。
     * 实测那 3 秒是实打实等满的：mihomo 收到 SIGTERM 会走优雅关闭（等待连接收尾），
     * 期间一直不死，直到 destroyForcibly 才被补刀。用户体感就是「关个开关要等三秒」。
     *
     * 现在直接用 -9：我们已经在停整个共享了，没有任何东西需要它体面收尾。
     * pkill 的退出码不能作为「有没有杀到」的依据（进程不存在时也返回非 0），
     * 所以加一句 pidof 复核，结果写进日志便于排障。
     */
    fun stop(): RootShell.Result {
        val r = RootShell.run(
            "pkill -9 -f 'vpnshare/bin/mihomo' 2>/dev/null\n" +
                "sleep 0.1\n" +
                "if pidof mihomo >/dev/null 2>&1; then echo 'stopped=no'; else echo 'stopped=yes'; fi",
            timeoutSec = 6
        )
        synchronized(lock) {
            proc?.let {
                runCatching { it.destroy() }
                runCatching { it.waitFor(300, TimeUnit.MILLISECONDS) }
                runCatching { it.destroyForcibly() }
            }
            proc = null
            ring.clear()
        }
        return r
    }

    fun recentLog(lines: Int = 200): List<String> = synchronized(lock) { ring.toList().takeLast(lines) }

    fun pid(): String = RootShell.run("pidof mihomo 2>/dev/null || pgrep -f vpnshare/bin/mihomo 2>/dev/null").out.trim()

    /** 启动后探测外网连通性，用于决定是否需要降级为「仅接管热点」 */
    fun probeEgress(timeoutSec: Int = 12): Boolean {
        val r = RootShell.run(
            "curl -s -o /dev/null -w '%{http_code}' -m " + timeoutSec + " https://www.gstatic.com/generate_204 2>/dev/null || echo 000",
            timeoutSec = (timeoutSec + 5).toLong()
        )
        return r.out.trim().endsWith("204")
    }
}
