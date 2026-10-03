package io.vpnshare.service


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

    private const val MAX_LOG = 400
    private val logs = ArrayDeque<String>()

    val running: Boolean
        get() = phase == Phase.RUNNING || phase == Phase.DEGRADED

    fun log(line: String) = log(line, mirrorToLogcat = true)

    /**
     * 内核输出的日志行。
     *
     * 不再镜像到 logcat —— CoreManager.pump() 已经带 core[OUT] / core[ERR] 前缀写过一次了。
     * 早期两处各写一遍，实测每条内核日志在 logcat 里出现两次（7 组配对、0 个单个），
     * 等于把 logcat 环形缓冲的可用历史砍掉一半，而排障恰恰最依赖那段历史。
     */
    fun logCore(line: String) = log(line, mirrorToLogcat = false)

    private fun log(line: String, mirrorToLogcat: Boolean) {
        // 应用自己的日志镜像到 logcat：这样 adb logcat 能拿到全部应用日志，不必只靠界面。
        // 内核日志由 CoreManager.pump() 负责写（带 core[OUT] 前缀，便于区分来源）。
        if (mirrorToLogcat) runCatching { android.util.Log.i("VpnShare", line) }
        synchronized(logs) {
            logs.addLast(line)
            while (logs.size > MAX_LOG) logs.removeFirst()
        }
    }

    fun recent(n: Int = 200): List<String> = synchronized(logs) { logs.toList().takeLast(n) }

}
