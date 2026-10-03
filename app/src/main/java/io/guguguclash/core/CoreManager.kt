package io.guguguclash.core

import io.guguguclash.root.RootShell
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 内核进程管理。
 *
 * 刻意不 daemon 化：把 su 会话本身当作 supervisor，内核算作它的子进程。
 * 注意 App 被 force-stop 时 su 会话可能被一起带走、而内核与 iptables 规则留在内核里，
 * 所以启动流程必须自带「发现残留内核」与「先清规则」两步（见 ShareService.startAll）。
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
        "pkill -f 'guguguclash/bin/mihomo' 2>/dev/null\n" +
        "sleep 0.3\n" +
        "exec " + CoreInstaller.CORE + " -d " + CoreInstaller.CONF

    private val lock = Any()

    /**
     * 运行目录的**文件属组**（shell=2000），不是内核的运行身份。
     *
     * 只用于给 /data/adb/guguguclash 设组权限。内核固定以 root 运行，实测本机
     * （一加 PJZ110 / ColorOS 16）三种身份的复测结论：
     *   root     → 网络 ✅ + 能建 redir/tproxy 监听 ✅    ← 唯一可行
     *   uid 2000 → 网络 ✅ 但建 redir/tproxy 报 operation not permitted ✗
     *   App uid  → 被系统掐断网络（force-stop 后尤其明显）✗
     * 早期文档曾写成相反结论（当时误判 root 的移动数据出站被丢弃），已推翻；
     * 调用方 ShareService 传的就是 runAsUid = null，改这里前先读那段注释。
     */
    const val FILE_GROUP_GID = 2000
    private var proc: Process? = null
    private var logFile: File? = null

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

    /**
     * 内核是不是还活着。
     *
     * 判活的**纯逻辑**（便于单测）：
     *   有子进程句柄 → 只看它；
     *   没有句柄（复用了 App 进程重建前留下的内核）→ 看外部信号（pgrep / REST）。
     *
     * 为什么要区分：修好「端口齐全就复用」之后，复用路径不会调 start()，
     * proc 一直是 null，旧实现 isRunning() 恒 false —— 巡检立刻判「内核已退出」
     * 并把刚起来的共享停掉（实测：RUNNING 后 3 秒内自停，还连报三次，电脑直接回到直连）。
     */
    fun alive(procAlive: Boolean?, adopted: Boolean, restUp: Boolean): Boolean =
        procAlive ?: (adopted && restUp)

    /** 复用来的外部内核（不是我们的子进程），判活要走 REST */
    @Volatile private var adopted = false

    fun markAdopted(v: Boolean) {
        adopted = v
    }

    fun isRunning(): Boolean {
        val p = synchronized(lock) { proc }
        p?.let { return it.isAlive }
        if (!adopted) return false

        // 复用来的内核没有句柄，只能靠外部信号。两条独立信号取或：
        //   ① pgrep 找得到进程（su 下的进程名匹配，最权威）
        //   ② REST 还应答
        // 只有两条都说「不在」才判死。**不能只用 REST**：实测设备负载很高时
        // REST 会偶发超时，judged dead 一次就把整个共享停掉（电脑当场回到直连），
        // 而那台机器上核心其实活得好好的。
        val found = pgrepCore()
        val restUp = if (found) false else CoreApi.version() != null
        return alive(null, adopted = true, restUp = found || restUp)
    }

    /** 用全路径匹配，避免误判用户自己跑的其它 mihomo（例如 CMFA） */
    private fun pgrepCore(): Boolean = runCatching {
        RootShell.run("pgrep -f 'guguguclash/bin/mihomo' >/dev/null 2>&1 && echo yes", timeoutSec = 8)
            .out.contains("yes")
    }.getOrDefault(false)

    /**
     * 启动内核。
     *
     * runAsUid 非空时用 su <uid> 降权启动；当前调用方一律传 null（root），
     * 因为 redir / tproxy 监听需要 root 权限（见 FILE_GROUP_GID 的复测结论）。
     * 参数保留只是为了排障时能快速对比两种身份。
     */
    fun start(onLog: (String) -> Unit, onExit: ((Int) -> Unit)? = null, runAsUid: Int? = null): Boolean {
        synchronized(lock) {
            if (proc?.isAlive == true) return true
            adopted = false
            return try {
                val cmd = if (runAsUid != null && runAsUid > 0) listOf("su", runAsUid.toString()) else listOf("su")
                android.util.Log.i("GuGuGu-clash", "CoreManager.start: " + cmd.joinToString(" ") +
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

    /**
     * 内核日志要不要镜像到 logcat。
     *
     * **不镜像 info 级的每连接行**：log-level=info 时内核每建立一条连接就打一行，
     * 实测几秒就能把 logcat 里 GuGuGu-clash 这个 tag 的历史全部挤掉，
     * 结果真正有用的启动 / 规则 / 探测日志一条都查不到（排障时踩过）。
     * 文件（core.log）与内存环照旧全量保留 —— 查连接该去那儿看。
     */
    fun shouldMirrorToLogcat(line: String): Boolean {
        if (!line.contains("level=info")) return true
        return !(line.contains("-->") || line.contains("[TCP]") || line.contains("[UDP]"))
    }

    private fun pump(reader: BufferedReader, onLog: (String) -> Unit, tag: String) {
        try {
            reader.use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    // 内核输出由这里负责写 logcat（带 core[OUT]/core[ERR] 前缀便于区分来源）；
                    // 回调侧用 ShareState.logCore，它不再镜像 logcat，避免同一条写两遍。
                    // info 级的「每连接一行」不镜像，见 shouldMirrorToLogcat 的说明。
                    if (shouldMirrorToLogcat(line)) {
                        android.util.Log.i("GuGuGu-clash", "core[" + tag + "] " + line)
                    }
                    val text = if (tag == "ERR") "[stderr] " + line else line
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
            "pkill -9 -f 'guguguclash/bin/mihomo' 2>/dev/null\n" +
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
            adopted = false
        }
        return r
    }

        fun pid(): String = RootShell.run("pidof mihomo 2>/dev/null || pgrep -f guguguclash/bin/mihomo 2>/dev/null").out.trim()
    // 出口探测只有一份实现：CoreApi.probeEgress（走内核 REST 的三级降级）。
    // 这里曾经有个同名同参的 curl 版本，没人调用且极易改错对象，已删除。
}
