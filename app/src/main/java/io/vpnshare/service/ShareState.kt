package io.vpnshare.service

import java.util.concurrent.CopyOnWriteArrayList

/** 进程内共享状态：服务写，UI / 磁贴读。 */
object ShareState {

    enum class Phase { IDLE, INSTALLING, STARTING_CORE, APPLYING_RULES, RUNNING, DEGRADED, STOPPING, ERROR }

    @Volatile var phase: Phase = Phase.IDLE
    @Volatile var coreVersion: String = ""
    @Volatile var iface: String = ""
    @Volatile var detail: String = ""
    @Volatile var offloadDisabled: Boolean? = null
    @Volatile var tcpOk: Boolean = false
    @Volatile var udpOk: Boolean = false
    @Volatile var dnsOk: Boolean = false

    // ---- 流量（由服务每 2 秒采样一次 /connections 的累计值算出）----
    @Volatile var upTotal: Long = 0
    @Volatile var downTotal: Long = 0
    @Volatile var upRate: Double = 0.0
    @Volatile var downRate: Double = 0.0

    val hasTraffic: Boolean get() = upTotal > 0 || downTotal > 0
    val totalBytes: Long get() = upTotal + downTotal

    /**
     * 其中**真正走代理节点**的部分（消耗机场配额的那部分）。
     *
     * 与 totalBytes 的差别不是小数目：热点客户端的流量在 iptables 层是全量导入内核的，
     * 内核内部再决定走节点还是直连。所以 totalBytes 含直连，通常比这个大得多。
     * 由服务在采样时用 ProxiedTraffic 增量累加得出，本次共享有效（服务启动清零）。
     */
    @Volatile var proxiedBytes: Long = 0

    // ---- 速率历史（由服务每 2 秒采样一次）----
    /**
     * 一条采样。速率单位是字节/秒。
     * 只存速率不存累计值：累计值重启会归零，画出来是断崖，没有参考意义。
     */
    data class Sample(val at: Long, val up: Double, val down: Double)

    private const val MAX_SAMPLES = 1800          // 2 秒一条 → 最多 1 小时
    private val history = ArrayDeque<Sample>()

    /** 记一条采样。由服务在算出速率后调用，不额外起线程。 */
    fun recordSample(up: Double, down: Double) {
        synchronized(history) {
            history.addLast(Sample(System.currentTimeMillis(), up.coerceAtLeast(0.0), down.coerceAtLeast(0.0)))
            while (history.size > MAX_SAMPLES) history.removeFirst()
        }
    }

    fun samples(): List<Sample> = synchronized(history) { history.toList() }

    /** 按分钟数保留；传 0 表示清空 */
    fun trimHistory(minutes: Int) {
        synchronized(history) {
            if (minutes <= 0) { history.clear(); return }
            val cut = System.currentTimeMillis() - minutes * 60_000L
            while (history.isNotEmpty() && history.first().at < cut) history.removeFirst()
        }
    }

    fun clearHistory() = synchronized(history) { history.clear() }

    private const val MAX_LOG = 400
    private val logs = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    val running: Boolean
        get() = phase == Phase.RUNNING || phase == Phase.DEGRADED

    fun log(line: String) {
        // 同时镜像到 logcat：这样 adb logcat 能拿到全部应用日志，不必只靠界面。
        runCatching { android.util.Log.i("VpnShare", line) }
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > MAX_LOG) logs.removeFirst()
        }
        for (l in listeners) runCatching { l(line) }
    }

    fun recent(n: Int = 200): List<String> = synchronized(logs) { logs.toList().takeLast(n) }

    fun clearLog() = synchronized(logs) { logs.clear() }

    fun addListener(l: (String) -> Unit) { listeners.add(l) }

    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }
}
