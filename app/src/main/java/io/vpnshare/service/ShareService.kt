package io.vpnshare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.vpnshare.R
import io.vpnshare.core.CoreApi
import io.vpnshare.core.CoreInstaller
import io.vpnshare.core.CoreManager
import io.vpnshare.prefs.Prefs
import io.vpnshare.profile.ProfileRunner
import io.vpnshare.profile.ProfileStore
import io.vpnshare.profile.SubImporter
import io.vpnshare.tether.TetherManager
import io.vpnshare.tether.TetherOffload
import io.vpnshare.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：内核守护 + 接管规则维护 + 设备状态自愈。
 *
 * 开关语义（对应计划 P4）：
 *   START -> 安装内核(如需) -> 写配置 -> 起内核 -> 等 API -> 关 offload -> 装规则 -> 定时巡检
 *   STOP  -> 清规则 -> 停内核 -> 可选恢复 offload
 */
class ShareService : Service() {

    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "vpnshare-work") }
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean(false)
    private var cm: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    /** 上次装规则时的「接口 + 地址」标识（见 TetherManager.tetherKey） */
    private var lastTetherKey: String = ""
    /** 共享接口连续判空的次数，用于「永久消失就摘规则」，见 refreshTether */
    private var ifaceGoneStreak = 0
    /** 上次自动重装规则的时间，用于去抖 */
    private var lastApplyAt = 0L
    /** 已经排过一次「内核已退出 → stopAll」，避免回调连击时重复排队 */
    @Volatile private var stopQueued = false

    override fun onBind(intent: Intent?): IBinder? = null

    /** 供 UI 直接触发刷新，服务没在跑时不必以 startForegroundService 拉起 */
    fun refreshNow() {
        work.execute { if (ShareState.running) refreshTether() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        Log.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        // 必须先 startForeground：本服务由 startForegroundService() 拉起，若 5 秒内
        // 没有调用 startForeground，系统会抛 ForegroundServiceDidNotStartInTimeException
        // 并直接杀掉进程。之前 ACTION_REFRESH 分支漏了这一步，点「检测接口」必崩。
        startForegroundCompat(
            when (action) {
                ACTION_STOP -> "正在停止…"
                ACTION_REFRESH -> "刷新共享状态"
                else -> "正在启动…"
            }
        )
        Log.i(TAG, "onStartCommand action=" + action)

        when (action) {
            ACTION_STOP -> {
                stopping.set(true)
                work.execute { stopAll() }
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                work.execute {
                    if (ShareState.running) {
                        refreshTether()
                    } else {
                        Log.i(TAG, "刷新时服务未在运行，直接退出")
                        stopForegroundCompat()
                        stopSelf()
                    }
                }
            }
            else -> {
                stopping.set(false)
                work.execute { startAll() }
            }
        }
        return START_STICKY
    }

    // ---------------- 启动流程 ----------------

    /** 启动流程的并发闸：见 startAll 开头说明 */
    private val startLock = Any()
    @Volatile private var starting = false

    private fun startAll() {
        // 并发闸：onCreate 的自动恢复与 onStartCommand 可能同时进来，
        // 两个 startAll 并行时，后一个的启动脚本会 pkill 掉前一个刚建的内核，
        // 前一个随后收到 onExit 并把服务整个停掉 —— 表现为「开了又自己停」。
        synchronized(startLock) {
            if (starting || ShareState.phase == ShareState.Phase.RUNNING) {
                Log.i(TAG, "startAll 已有实例在执行/已运行，忽略重复请求")
                return
            }
            starting = true
        }
        val ctx = applicationContext
        val p = Prefs.load(ctx)
        try {
        // 先摘掉可能残留的热点规则：App 被 force-stop / 覆盖安装时不会走 onDestroy，
        // 残留的规则会让热点客户端指向一个已经不存在的内核而直接断网；
        // 而完整启动流程（准备配置、拉起内核、等 REST）要好几秒，
        // 所以必须在最前面清干净，让客户端立刻回到直连。
        runCatching { TetherManager.cleanup(ctx, p) }
            if (!io.vpnshare.root.RootShell.isRooted()) {
                fail("未获得 root 权限：请在 Magisk / KernelSU / APatch 中授予本应用 su")
                return
            }
            ShareState.phase = ShareState.Phase.INSTALLING
            notifyState("检查内核")
            Log.i(TAG, "startAll: rooted=true, installed=" + CoreInstaller.installed())
            if (!CoreInstaller.installed()) {
                ShareState.log("首次运行：安装内核与内置数据")
                val rep = CoreInstaller.install(ctx) { step -> ShareState.log("  " + step) }
                rep.steps.forEach { ShareState.log("  " + it) }
                if (!rep.ok) {
                    fail("内核安装失败：" + (rep.error ?: "未知"))
                    return
                }
            } else {
                ShareState.log("内核已就绪：" + CoreInstaller.CORE)
            }

            ShareState.phase = ShareState.Phase.STARTING_CORE
            notifyState("启动内核")
            TetherManager.ensureDirs()
            // 内核以 root 运行；这一步是为了修 /data/adb 的可穿越位与运行目录的组权限
            val perm = CoreInstaller.ensurePermissions()
            Log.i(TAG, "ensurePermissions: " + perm.out.trim() + " err=" + perm.err.trim())

            // 自愈：currentProfileId 可能因为异常退出、误操作或旧版本遗留而变空。
            // 此时 ProfileStore.current() 会静默回退到第一份配置 —— 服务能跑，
            // 但界面会显示「没有选中的配置」，两边对不上。这里把它补回去。
            if (p.currentProfileId.isBlank()) {
                val resolved = io.vpnshare.profile.ProfileStore(ctx).current("")
                if (resolved != null) {
                    ShareState.log("WARN 未记录当前配置，已自动选回「" + resolved.name + "」")
                    Prefs.save(ctx, Prefs.load(ctx).copy(currentProfileId = resolved.id))
                }
            }
            val profile = ProfileRunner.prepareConfig(ctx, p)
            if (!profile.ok) {
                fail(profile.error ?: "配置生成失败")
                return
            }

            CoreManager.setLogFile(java.io.File(filesDir, "core.log"))
            // 内核一律以 root 运行。三种身份实测结论：
            //   root     → 网络 ✅ + 能建 redir/tproxy 监听 ✅  → 唯一可行的
            //   uid 2000 → 网络 ✅ 但建 redir/tproxy 报 operation not permitted ✗
            //   App uid  → 被系统掐断网络 ✗
            // 启动前先确认内核是不是已经在跑。App 进程被系统重建后记不住这件事，
            // 而启动脚本开头会 pkill 旧内核 —— 若此时热点规则仍然生效，
            // 就会造成数秒断网（实测把用户电脑断过）。所以在跑就一律复用。
            // 复用有两个前提，缺一不可：
            //   1) REST 有响应；2) redir / tproxy / dns 三个端口真的在监听。
            // 只看 ① 会复用「半死的孤儿内核」—— App 被 force-stop 时留下的内核进程
            // 仍然应答 REST，但它的 tproxy/dns 监听可能已经没了。症状是
            // 「服务进入 RUNNING 后立刻报内核已退出」，而且每次重启都复现。
            val restUp = io.vpnshare.core.CoreApi.version() != null
            val portsUp = restUp && portsListening(p)
            if (restUp && !portsUp) {
                ShareState.log("发现残留内核但监听不全，先清理再重启（避免复用坏状态）")
                runCatching { CoreManager.stop() }
                Thread.sleep(1000)
            }
            val coreAlreadyUp = restUp && portsUp
            if (coreAlreadyUp) {
                ShareState.log("内核已在运行且端口齐全，直接复用（不重启、不断网）")
                // 复用意味着没有 proc 句柄：必须告诉 CoreManager 走 REST 判活，
                // 否则巡检会把它当死内核，三秒内把共享停掉。
                CoreManager.markAdopted(true)
            } else if (!CoreManager.start(
                    onLog = { line -> ShareState.logCore(line) },
                    runAsUid = null,
                    onExit = { code ->
                        if (ShareState.running) {
                            ShareState.log("ERR 内核进程已退出，退出码 " + code)
                            fail("内核进程意外退出（exit " + code + "），详见日志")
                        }
                    }
                )
            ) {
                fail("内核启动失败（su 会话无法建立）")
                return
            }

            // 冷启动时内核要加载 geo 数据，给足 20 秒
            var ver = ""
            var lastErr = ""
            for (i in 0 until 50) {
                Thread.sleep(400)
                try {
                    val v = CoreApi.version()
                    if (!v.isNullOrEmpty()) {
                        ver = v
                        break
                    }
                    lastErr = CoreApi.lastError
                } catch (e: Exception) {
                    lastErr = e.message ?: ""
                }
                if (!CoreManager.isRunning()) {
                    fail("内核进程在启动过程中已退出，详见日志")
                    return
                }
            }
            if (ver.isEmpty()) {
                fail("内核未在 20 秒内响应 REST API（" + (lastErr.ifBlank { "无错误详情" }) + "），请查看日志")
                return
            }
            ShareState.coreVersion = ver
            Log.i(TAG, "内核 REST 就绪，版本 " + ver)
            ShareState.log("内核就绪，版本 " + ver)

            ShareState.phase = ShareState.Phase.APPLYING_RULES
            notifyState("应用接管规则")

            val off = TetherOffload.current()
            ShareState.offloadDisabled = (off == 1)
            if (off == null) {
                ShareState.log("WARN 该 ROM 无 tether_offload_disabled，若客户端不通请手动关闭开发者选项里的网络共享硬件加速")
            } else if (off != 1) {
                val r = TetherOffload.setDisabled(true)
                ShareState.log(if (r.ok) "已关闭网络共享硬件加速" else "WARN 关闭硬件加速失败：" + r.combined)
                ShareState.offloadDisabled = r.ok
            }

            // 系统「专用 DNS」走 DoT，会直接绕过 53 端口的 DNS 劫持 —— 一起关掉，原值留着恢复。
            // 抄自 box4magisk / Surfing v7（它们是无条件关，这里按开关来）。
            //
            // 原值**落盘**（Prefs.privateDnsSavedKey/Value），不用内存字段：
            // 内存版在「共享期间进程被杀 / 手机重启」时会丢，而开机自启重新 startAll 读到的
            // 已经是 off，用户原本的设置就永远回不来了。
            var prefs = p
            val leftover = if (p.privateDnsSavedKey.isNotBlank() && p.privateDnsSavedValue.isNotBlank())
                io.vpnshare.tether.PrivateDns.State(p.privateDnsSavedKey, p.privateDnsSavedValue) else null

            if (leftover != null && !p.managePrivateDns) {
                // 用户后来把这个功能关了，但我们之前确实改过系统设置 —— 先还回去再撒手
                if (io.vpnshare.tether.PrivateDns.shouldRestore(leftover) &&
                    io.vpnshare.tether.PrivateDns.set(leftover.key, leftover.value).ok
                ) {
                    ShareState.log("专用 DNS 已按上次记录恢复（" + leftover.key + "=" + leftover.value + "）")
                    prefs = prefs.copy(privateDnsSavedKey = "", privateDnsSavedValue = "")
                }
            } else if (p.managePrivateDns) {
                // 有历史记录就沿用它；没有才现场读一次系统值
                val saved = leftover ?: io.vpnshare.tether.PrivateDns.current()
                when {
                    saved == null -> ShareState.log(
                        "WARN 该 ROM 没有专用 DNS 设置项（试过 " +
                            io.vpnshare.tether.PrivateDns.KEYS.joinToString("/") +
                            "），若客户端 DNS 不生效请手动关掉「私人 DNS」"
                    )
                    io.vpnshare.tether.PrivateDns.isOff(saved.value) -> {
                        // 本来就是 off：没有可恢复的东西，把历史记录清掉
                        prefs = prefs.copy(privateDnsSavedKey = "", privateDnsSavedValue = "")
                    }
                    else -> {
                        val r = io.vpnshare.tether.PrivateDns.set(saved.key, io.vpnshare.tether.PrivateDns.OFF)
                        ShareState.log(
                            if (r.ok) "已关闭系统专用 DNS（" + saved.key + "=" + saved.value + "，停止共享时恢复）"
                            else "WARN 关闭专用 DNS 失败：" + r.combined
                        )
                        if (r.ok) prefs = prefs.copy(privateDnsSavedKey = saved.key, privateDnsSavedValue = saved.value)
                    }
                }
            }

            // 关键安全闸：先确认「内核真的能出网」，再决定要不要接管热点。
            // 否则一旦节点/DNS 有问题，热点客户端的流量会被 REDIRECT 进一个出口不通的
            // 内核，表现为「手机上规则一装，电脑就断网」。
            applyRulesAndVerify(p)

            ShareState.phase = ShareState.Phase.RUNNING
            Log.i(TAG, "服务已进入 RUNNING，iface=" + ShareState.iface)
            // 开始流量采样：通知栏与首页卡片都靠它
            startTrafficPolling()

            // 先装规则再探测，而且连探 3 次才判定失败。
            // 反过来（先探测再装）太脆：节点冷启动偶发超时会让规则永不安装，
            // 而热点的 UDP/DNS 转发恰恰依赖这些规则。
            // 真不通才回滚，保证「一旦影响上网就自动退回直连」。
            work.execute {
                var code = -1
                // tun 关时 App 自身不被代理，必须显式经内核的 7890 端口探测，
                // 否则会把「一切正常」误判成失败并回滚规则。
                val probePort = if (Prefs.load(applicationContext).proxyPhoneTraffic) 0 else 7890
                // 只探 2 次、每次 8 秒：把「可能影响上网」的窗口压到 20 秒以内
                for (attempt in 1..2) {
                    code = io.vpnshare.core.CoreApi.probeEgress(8, probePort)
                    if (code == 204) break
                    ShareState.log("出口探测第 " + attempt + " 次未通过（" + code + "）" +
                        (if (attempt < 2) "，2 秒后重试" else ""))
                    if (attempt < 2) Thread.sleep(2000)
                }
                if (code == 204) {
                    ShareState.log("出口探测通过（204），代理链路正常")
                } else {
                    ShareState.log("WARN 连续 3 次出口探测未通过（" + code + "）：" +
                        io.vpnshare.core.CoreApi.lastError.ifBlank { "节点或 DNS 不可用" })
                    // 只删 iptables 是不够的：内核的 tun 用 auto-route 改了默认路由，
                    // 热点转发的流量是从【路由层面】进 tun 的，与 iptables 无关。
                    // 内核还在跑 = 电脑还在被吞流量。必须连内核一起停掉，
                    // tun 与它加的路由才会随之消失，电脑立刻回到纯热点直连。
                    if (Prefs.load(applicationContext).proxyPhoneTraffic) {
                        // tun 开着：必须停内核才能拆掉 tun 与它改的路由
                        ShareState.log("正在彻底停止内核以恢复电脑上网…")
                        stopAll()
                    } else {
                        // tun 关着：只删热点规则即可，秒级恢复，不必动内核
                        runCatching { TetherManager.cleanup(applicationContext, Prefs.load(applicationContext)) }
                        ShareState.tcpOk = false
                        ShareState.udpOk = false
                        ShareState.dnsOk = false
                        // 进入降级态而不是继续假装 RUNNING：
                        // 规则其实已经摘掉了，UI 必须如实反映，否则用户看到「运行中」而电脑是直连。
                        ShareState.phase = ShareState.Phase.DEGRADED
                        ShareState.log("已取消热点接管：电脑恢复直连（不影响上网）。手机自身代理仍可用。")
                        ShareState.log("进入降级态，每 60 秒自动重试；出口恢复后会自动重新接管，无需手动关开")
                        scheduleRecovery()
                    }
                }
            }
            registerNetworkCallback()
            scheduleWatchdog()
            scheduleSubUpdate()

            // 按网络自动切换。没配规则时它自己就会跳过，不会注册任何回调。
            io.vpnshare.service.NetworkWatcher.start(applicationContext)
            Prefs.save(ctx, prefs.copy(enabled = true))
            notifyState("咕咕咕clash 已启用")

            // 机场配置的总控组默认常常指向 DIRECT —— 不换成真实节点的话，
            // 共享给电脑的流量会全部直连（用户看到的就是「开着但没走代理」）。
            // 这里兜一次：测速并切到最快节点。用户之后可以随时在节点页自己换。
            work.execute {
                    val g = io.vpnshare.core.CoreApi.mainGroupName()
                    if (g == null) {
                        ShareState.log("未找到总控策略组，跳过自动优选")
                    } else {
                        // 判据要点：不能只判断「选中的是不是真实节点」，还要判断它**还活着没**。
                        // 只看类型的话，一个已失效的节点会让这里判定「无需优选」，
                        // 共享出去的流量就全部挂死在那个节点上（电脑表现为走代理的站点全超时）。
                        val r = io.vpnshare.core.CoreApi.ensureAliveNode(g)
                    when {
                        r == null ->
                            ShareState.log("WARN 总控组「" + g + "」不可用，共享流量会走不通")
                        !r.measured ->
                            ShareState.log("总控组「" + g + "」节点测速未取到有效数据，保持当前节点「" + r.node + "」不动")
                        r.switched ->
                            ShareState.log("总控组「" + g + "」原节点已失效，自动切换到「" + r.node + "」（可用 " + r.available + " 个）")
                        else ->
                            ShareState.log("总控组「" + g + "」当前节点「" + r.node + "」存活（可用 " + r.available + " 个），未改动")
                    }
                }
            }   // 关闭 work.execute（原 runCatching 已随本次重写移除）
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        } finally {
            // 无论成功失败都必须复位，否则一次失败后再也无法启动
            starting = false
            stopQueued = false
        }
    }

    private fun applyRulesAndVerify(p: Prefs.Data) {
        val ctx = applicationContext
        // 上一次若被 force-stop，规则会残留（不走 onDestroy）。先清一遍再装。
        runCatching { TetherManager.cleanup(ctx, p) }
        val r = TetherManager.apply(ctx, p)
        r.out.lines().filter { it.isNotBlank() }.forEach { ShareState.log(it) }
        if (r.err.isNotBlank()) ShareState.log("[stderr] " + r.err)

        val det = TetherManager.detectIface(p)
        ShareState.iface = det ?: ""
        if (det == null) {
            ShareState.log("提示：当前没有检测到热点/USB 共享接口，热点打开后会自动补装规则")
        }
        lastTetherKey = TetherManager.tetherKey(p) ?: ""
        ifaceGoneStreak = 0

        val rep = TetherManager.listeningPortsDetailed(p)
        ShareState.tcpOk = rep.state[p.redirPort] == true
        ShareState.udpOk = rep.state[p.tproxyPort] == true
        ShareState.dnsOk = rep.state[p.dnsPort] == true
        ShareState.log("端口检查 " + p.redirPort + "=" + ShareState.tcpOk + " " +
            p.tproxyPort + "=" + ShareState.udpOk + " " + p.dnsPort + "=" + ShareState.dnsOk)
        if (ShareState.tcpOk && ShareState.udpOk && ShareState.dnsOk) {
            ShareState.log("内核已在监听 redir / tproxy / dns 三个端口")
        } else {
            ShareState.log("未监听到端口的原始输出：" + rep.raw.take(300))
        }
    }

    /**
     * 内核该监听的端口是不是都在。
     *
     * 判据全部放在 [TetherManager.allPortsListening]：端口集合和「查了哪些键」必须同源。
     * 这里曾经自己拼条件、去读 state[mixedPort]，而 state 的键只有 redir/tproxy/dns ——
     * 取到 null 恒判 false，于是「发现存活内核也一律先杀掉再重启」，每次启动都断一次流。
     */
    private fun portsListening(p: Prefs.Data): Boolean = runCatching {
        val rep = TetherManager.listeningPortsDetailed(p)
        TetherManager.allPortsListening(rep.state, p)
    }.getOrDefault(false)

    private fun refreshTether() {
        val ctx = applicationContext
        val p = Prefs.load(ctx)
        if (!ShareState.running) return
        try {
            // 用「接口 + 本机地址」当标识：换热点 / 改 DHCP 段时接口名可能不变，只有地址会变
            val key = TetherManager.tetherKey(p)
            if (key != lastTetherKey) {
                if (key == null) {
                    // 接口没了：第一次只记一笔，不动规则 —— 热点一闪（切换热点瞬间）
                    // 不值得拆了再装一遍。
                    // 但**永久消失**（用户关掉热点）时规则会一直留在内核里：独立守护只在
                    // 「内核没了」时才动手，而内核还活着，它不会管。所以连续两次巡检（约 2 分钟）
                    // 仍判空就主动摘一次，接口回来自动补装。
                    lastTetherKey = ""
                    ShareState.iface = ""
                    ifaceGoneStreak++
                    when (ifaceGoneStreak) {
                        1 -> ShareState.log("共享接口已消失（规则随接口失效，接口回来时自动补装）")
                        2 -> {
                            ShareState.log("共享接口持续不存在，主动摘除规则（接口回来时自动补装）")
                            val r = TetherManager.cleanup(ctx, p)
                            r.out.lines().filter { it.isNotBlank() }.forEach { ShareState.log(it) }
                        }
                        else -> { /* 已经摘过，安静等接口回来 */ }
                    }
                } else {
                    ifaceGoneStreak = 0
                    val iface = key.substringBefore("|")
                    ShareState.log("共享接口/网段变化：" + lastTetherKey.ifBlank { "无" } + " -> " + iface)
                    when {
                        ShareState.phase == ShareState.Phase.DEGRADED -> {
                            // 降级态下不能在这里补规则：出口本来就不通，装上只会让电脑断网。
                            // 自愈重试（scheduleRecovery）通过后会把规则装回去，并在那里刷新标识。
                            ShareState.log("当前为降级态（出口不通），暂不装规则；自愈通过后会自动接管")
                        }
                        !autoApplyAllowed() -> {
                            // 去抖 / 开机稳定期：本轮跳过，且**不刷新标识**，
                            // 让 60 秒巡检再试一次（否则这次变化会被永久忽略）
                            ShareState.log("本轮暂缓装规则（去抖/开机稳定期），下一次巡检会补上")
                        }
                        else -> {
                            val r = TetherManager.apply(ctx, p)
                            r.out.lines().filter { it.isNotBlank() }.forEach { ShareState.log(it) }
                            lastTetherKey = key
                            // 换网后旧连接的源地址已失效，内核却还留着它们 —— 客户端会一直转圈。
                            // box4magisk / Surfing v7 的做法就是在网络变化钩子里 DELETE /connections。
                            if (CoreApi.closeConnections()) {
                                ShareState.log("已断开内核里的旧连接（换网后旧连接已失效）")
                            }
                        }
                    }
                    ShareState.iface = iface
                }
            }
            if (!CoreManager.isRunning() && ShareState.running) {
                // 不在这里悄悄重启：内核中途死掉时，tun 的路由改动可能处于半残状态，
                // 继续留着会持续吞热点流量。宁可停服务让用户重新开一次。
                // 只排一次：网络回调常常连着来，重复排队会让 stopAll 跑三遍（日志里能看到）。
                if (!stopQueued) {
                    stopQueued = true
                    ShareState.log("检测到内核已退出，停止服务以恢复电脑上网")
                    work.execute { stopAll() }
                }
            }
        } catch (e: Exception) {
            ShareState.log("巡检异常：" + (e.message ?: ""))
        }
    }

    /**
     * 自动重装规则是否放行。两条护栏都抄自 box4magisk / Surfing v7 的 inotify 脚本：
     *   ① 开机稳定期：开机后 60 秒内网络事件会成串来，这期间装规则等于拿客户端做实验；
     *   ② 去抖：5 秒内只装一次，避免回调连击把客户端闪断。
     * 只影响「自动补装」，startAll / 自愈 / 手动刷新都不受它限制。
     */
    private fun autoApplyAllowed(): Boolean {
        val up = android.os.SystemClock.elapsedRealtime()
        if (up < BOOT_STABLE_MS) {
            ShareState.log("开机 " + (up / 1000) + " 秒，未过稳定期（" + (BOOT_STABLE_MS / 1000) + " 秒）")
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastApplyAt < APPLY_DEBOUNCE_MS) return false
        lastApplyAt = now
        return true
    }

    // ---------------- 停止流程 ----------------

    private fun stopAll() {
        val ctx = applicationContext
        val p = Prefs.load(ctx)
        try {
            stopTrafficPolling()
            stopNetworkWatcher()
        ShareState.phase = ShareState.Phase.STOPPING
            notifyState("正在清理规则")
            unregisterNetworkCallback()
            main.removeCallbacksAndMessages(null)

            val r = TetherManager.cleanup(ctx, p)
            r.out.lines().filter { it.isNotBlank() }.forEach { ShareState.log(it) }

            val killResult = CoreManager.stop()
            // pkill 的退出码不可靠，用脚本里的 pidof 复核结果判断
            val killed = killResult.out.contains("stopped=yes")
            ShareState.log(if (killed) "内核已停止" else "WARN 内核未确认停止：" + killResult.combined.take(120))

            if (p.restoreOffloadOnStop) {
                TetherOffload.setDisabled(false)
                ShareState.log("已恢复网络共享硬件加速设置")
            }

            // 专用 DNS 只有在「本来就不是 off」时才恢复，免得把用户没设过的状态写回去。
            // 原值来自落盘记录（见 startAll），所以重启/被杀之后这一轮停止照样能还回去。
            var afterDns = p
            val pv = if (p.privateDnsSavedKey.isNotBlank() && p.privateDnsSavedValue.isNotBlank())
                io.vpnshare.tether.PrivateDns.State(p.privateDnsSavedKey, p.privateDnsSavedValue) else null
            if (io.vpnshare.tether.PrivateDns.shouldRestore(pv)) {
                val res = io.vpnshare.tether.PrivateDns.set(pv!!.key, pv.value)
                ShareState.log(
                    if (res.ok) "已恢复系统专用 DNS（" + pv.key + "=" + pv.value + "）"
                    else "WARN 专用 DNS 恢复失败"
                )
            }
            if (p.privateDnsSavedKey.isNotBlank() || p.privateDnsSavedValue.isNotBlank()) {
                afterDns = p.copy(privateDnsSavedKey = "", privateDnsSavedValue = "")
            }

            Prefs.save(ctx, afterDns.copy(enabled = false))
            ShareState.phase = ShareState.Phase.IDLE
            ShareState.tcpOk = false
            ShareState.udpOk = false
            ShareState.dnsOk = false
            ShareState.iface = ""
            lastTetherKey = ""
        } catch (e: Exception) {
            ShareState.log("停止异常：" + (e.message ?: ""))
        } finally {
            stopQueued = false
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, "fail: " + msg)
        ShareState.phase = ShareState.Phase.ERROR
        ShareState.detail = msg
        ShareState.log("ERR " + msg)
        val ctx = applicationContext
        runCatching { CoreManager.stop() }
        runCatching { TetherManager.cleanup(ctx, Prefs.load(ctx)) }
        notifyState("启动失败")
        stopForegroundCompat()
        stopSelf()
    }

    // ---------------- 网络状态监听 ----------------

    private fun registerNetworkCallback() {
        if (netCallback != null) return
        val c = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        cm = c
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = work.execute { refreshTether() }
            override fun onLost(network: Network) = work.execute { refreshTether() }
            // 换热点 / 改 DHCP 段时接口名可能不变，只有链路属性（地址、路由）会变。
            // 只挂 onAvailable/onLost 会漏掉这一类变化 —— 对应模块们用 inotifyd 盯
            // /data/misc/net 的写事件，这里用 Android 原生回调达到同样效果。
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) =
                work.execute { refreshTether() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                work.execute { refreshTether() }
        }
        netCallback = cb
        runCatching { c.registerDefaultNetworkCallback(cb) }
    }

    private fun unregisterNetworkCallback() {
        val c = cm ?: return
        netCallback?.let { runCatching { c.unregisterNetworkCallback(it) } }
        netCallback = null
    }

    /** 订阅自动更新：成功且自检通过才热重载，失败保留旧配置 */
    /**
     * 订阅自动更新的调度。
     *
     * 失败会重试：机场偶尔 502/超时很常见，早期失败就直接 return 等下一个整周期，
     * 结果是「12 小时内机场抖一下，订阅一整天没更新」，而用户完全不知道。
     * 现在失败后 30 分钟重试，连续失败 SUB_MAX_RETRY 次才退回正常周期。
     */
    private fun scheduleSubUpdate() {
        // 间隔取值顺序：该配置自己的（机场下发或用户单设）→ 全局设置。
        val p = Prefs.load(applicationContext)
        val prof = ProfileStore(applicationContext).current(p.currentProfileId)
        val hours = prof?.intervalHours?.takeIf { it > 0 } ?: p.subUpdateHours
        if (hours <= 0) {
            ShareState.log("订阅自动更新已关闭")
            return
        }
        val normalDelay = hours * 3600_000L
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!ShareState.running) return
                work.execute {
                    val ok = runSubUpdate()
                    if (ok) {
                        subFailStreak = 0
                        main.postDelayed(this, normalDelay)
                    } else if (subFailStreak < SUB_MAX_RETRY) {
                        subFailStreak++
                        ShareState.log("订阅更新失败，30 分钟后重试（第 " + subFailStreak + "/" + SUB_MAX_RETRY + " 次）")
                        main.postDelayed(this, SUB_RETRY_MS)
                    } else {
                        ShareState.log("订阅更新连续失败 " + SUB_MAX_RETRY + " 次，改为等下一个正常周期")
                        subFailStreak = 0
                        main.postDelayed(this, normalDelay)
                    }
                }
            }
        }, normalDelay)
        ShareState.log("订阅自动更新已启用：每 " + hours + " 小时")
    }

    /** @return 整条链路（下载 → 生成 → 自检 → 热重载）是否全部成功 */
    fun runSubUpdate(): Boolean {
        val ctx = applicationContext
        val p = Prefs.load(ctx)
        val profile = ProfileStore(ctx).current(p.currentProfileId)
        if (profile == null) {
            ShareState.log("跳过订阅更新：尚未导入订阅")
            return false
        }
        ShareState.log("开始更新订阅：" + profile.name)
        val r = SubImporter.update(ctx, profile, p.userAgent)
        ShareState.log((if (r.ok) "OK  " else "ERR ") + r.message)
        if (!r.ok) return false

        val prep = ProfileRunner.prepareConfig(ctx, Prefs.load(ctx))
        if (!prep.ok) {
            ShareState.log("ERR 新配置未通过自检，保留旧配置：" + (prep.error ?: ""))
            return false
        }
        val reloaded = ProfileRunner.hotReload()
        ShareState.log(if (reloaded) "OK  已热重载新配置（" + prep.nodeCount + " 节点）" else "ERR 热重载失败，重启服务生效")
        return reloaded
    }

    /**
     * 降级自愈。
     *
     * 背景：出口探测失败时会摘掉热点规则让电脑回到直连。但早期**摘完就不管了** ——
     * 看门狗只在共享接口变化时才重装规则，于是「开机瞬间节点抖一下」会让共享永久停摆，
     * 而 UI 还显示运行中。用户只能自己发现并手动关开一次。
     *
     * 现在：降级态下每 60 秒重试一次，顺序是「先修节点、再探出口、然后装回规则」。
     * 死节点是降级最常见的原因，所以先跑一次 ensureAliveNode。
     */
    private fun scheduleRecovery() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (ShareState.phase != ShareState.Phase.DEGRADED) return
                work.execute {
                    if (ShareState.phase != ShareState.Phase.DEGRADED) return@execute
                    // 1) 先看总控组的节点还活着没
                    runCatching {
                        val g = io.vpnshare.core.CoreApi.mainGroupName()
                        val r = if (g != null) io.vpnshare.core.CoreApi.ensureAliveNode(g) else null
                        if (r != null && r.switched) {
                            ShareState.log("降级重试：原节点已失效，已切到「" + r.node + "」（可用 " + r.available + " 个）")
                        } else if (r != null && !r.measured) {
                            ShareState.log("降级重试：节点测速未取到数据，保持当前节点不动")
                        }
                    }
                    // 2) 再探出口
                    val port = if (Prefs.load(applicationContext).proxyPhoneTraffic) 0 else 7890
                    var code = -1
                    for (attempt in 1..2) {
                        code = io.vpnshare.core.CoreApi.probeEgress(8, port)
                        if (code == 204) break
                        if (attempt < 2) Thread.sleep(2000)
                    }
                    // 3) 通了就把规则装回去。
                    // 装之前必须再确认服务仍在运行：用户可能在这 20 多秒的探测期间关掉了开关。
                    // 单线程 work 队列保证了 stopAll 不会插在中间，但那样会在停止后白装一次
                    // 再被立刻拆掉 —— 既浪费，也短暂地把电脑流量导向正在退出的内核。
                    if (code == 204 && !ShareState.running) {
                        ShareState.log("降级重试：探测期间服务已停止，放弃本次自愈")
                        return@execute
                    }
                    if (code == 204) {
                        ShareState.log("降级重试：出口已恢复（204），正在重新接管热点…")
                        val p = Prefs.load(applicationContext)
                        applyRulesAndVerify(p)
                        if (ShareState.tcpOk && ShareState.udpOk && ShareState.dnsOk) {
                            ShareState.phase = ShareState.Phase.RUNNING
                            ShareState.log("OK 已恢复共享：电脑重新走代理")
                            runCatching { notifyState("咕咕咕clash 已恢复共享") }
                        } else {
                            ShareState.log("WARN 规则未能装回（热点可能未开），60 秒后再试")
                            main.postDelayed(this, 60_000)
                        }
                    } else {
                        ShareState.log("降级重试：出口仍不通（" + code + "），60 秒后再试")
                        main.postDelayed(this, 60_000)
                    }
                }
            }
        }, 60_000)
    }

    private fun scheduleWatchdog() {
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!ShareState.running) return
                work.execute { refreshTether() }
                main.postDelayed(this, 60_000)
            }
        }, 60_000)
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, "咕咕咕clash 服务", NotificationManager.IMPORTANCE_LOW)
        ch.description = "内核守护与共享规则维护"
        nm?.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vpnshare)
            .setContentTitle("咕咕咕clash")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF1E4376.toInt())
            .setContentIntent(pi)

        // 有流量时按 CMFA 的三段式显示：标题=应用、副标题=累计、正文=实时速率。
        // 副标题在支持它的 ROM 上单独一行，不支持时系统会自动并入正文，不会丢信息。
        if (ShareState.hasTraffic) {
            b.setSubText(io.vpnshare.util.Format.pair(ShareState.upTotal, ShareState.downTotal))
            b.setContentText(io.vpnshare.util.Format.ratePair(ShareState.upRate, ShareState.downRate))
        } else {
            b.setContentText(text)
        }
        return b.build()
    }

    // ---------------- 流量采样 ----------------

    private val trafficHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var trafficRunnable: Runnable? = null
    @Volatile private var lastUp = -1L
    @Volatile private var lastDown = -1L
    @Volatile private var lastAt = 0L

    /** 订阅更新连续失败次数；成功后清零 */
    private var subFailStreak = 0

    /** 梯子流量累加器状态。只在采样线程读写，不需要额外同步。 */
    private var proxiedState = io.vpnshare.service.ProxiedTraffic.EMPTY

    /**
     * 每 2 秒采样一次内核的累计字节数，差值即为实时速率。
     * 用轮询而不是订阅 /traffic 流：内核重启时流会断，轮询天然自愈。
     */
    private fun startTrafficPolling() {
        stopTrafficPolling()
        // 注意：这里不能顺手 stopNetworkWatcher()。它是「停止流程」的一部分，
        // 放在「启动流程」里会关掉刚注册好的网络联动监听（早期批量改动时误加过一行）。
        // 目前靠 startAll 里 NetworkWatcher.start 的调用顺序侥幸无害，但那是脆弱的顺序依赖。
        lastUp = -1L; lastDown = -1L; lastAt = 0L
        proxiedState = io.vpnshare.service.ProxiedTraffic.EMPTY
        ShareState.proxiedBytes = 0
        val r = object : Runnable {
            override fun run() {
                if (!ShareState.running) { trafficRunnable = null; return }
                Thread {
                    val snap = io.vpnshare.core.CoreApi.snapshot()
                    val t = snap.totals
                    // 同一次响应里顺带累加梯子流量，不再多发一次 /connections
                    proxiedState = io.vpnshare.service.ProxiedTraffic.account(
                        proxiedState,
                        snap.conns.map {
                            io.vpnshare.service.ProxiedTraffic.Item(it.id, it.chain, it.upload, it.download)
                        }
                    )
                    ShareState.proxiedBytes = proxiedState.total
                    val now = System.currentTimeMillis()
                    if (lastAt > 0 && lastUp >= 0 && now > lastAt) {
                        val dt = (now - lastAt) / 1000.0
                        // 内核重启会让累计值归零，此时不产生负速率
                        val du = t.up - lastUp
                        val dd = t.down - lastDown
                        if (du >= 0 && dd >= 0) {
                            ShareState.upRate = du / dt
                            ShareState.downRate = dd / dt
                        }
                    }
                    lastUp = t.up; lastDown = t.down; lastAt = now
                    ShareState.upTotal = t.up
                    ShareState.downTotal = t.down
                    // 同一个循环里顺手记一条历史，不额外起线程
                    ShareState.recordSample(ShareState.upRate, ShareState.downRate)
                    ShareState.trimHistory(Prefs.load(applicationContext).historyMinutes)
                    runCatching { notifyState(lastNotifText) }
                }.start()
                trafficHandler.postDelayed(this, 2000)
            }
        }
        trafficRunnable = r
        trafficHandler.postDelayed(r, 1500)
    }

    private fun stopNetworkWatcher() {
        runCatching { io.vpnshare.service.NetworkWatcher.stop(applicationContext) }
    }

    private fun stopTrafficPolling() {
        trafficRunnable?.let { trafficHandler.removeCallbacks(it) }
        trafficRunnable = null
        ShareState.upRate = 0.0
        ShareState.downRate = 0.0
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    @Volatile private var lastNotifText = "启动中"

    private fun notifyState(text: String) {
        lastNotifText = text
        val nm = getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.notify(NOTIF_ID, buildNotification(text)) }
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        if (ShareState.running && !stopping.get()) {
            // 被系统杀掉：留下进程内状态，重启后由 START_STICKY 或开机接收器接管
            ShareState.log("服务被销毁，等待系统重启")
        }
        super.onDestroy()
    }

    companion object {
        /** 订阅更新失败后的重试间隔与最大次数 */
        private const val SUB_RETRY_MS = 30 * 60_000L
        private const val SUB_MAX_RETRY = 3

        /** 自动重装规则的最小间隔：网络抖动时回调会连着来，连着装规则会把客户端闪断 */
        private const val APPLY_DEBOUNCE_MS = 5_000L
        /** 开机后这段时间内不自动重装规则：开机网络风暴期间规则还没稳（对应模块的 BOOT_STABLE_FLAG） */
        private const val BOOT_STABLE_MS = 60_000L

        @Volatile
        private var instance: ShareService? = null

        const val ACTION_START = "io.vpnshare.action.START"
        const val ACTION_STOP = "io.vpnshare.action.STOP"
        const val ACTION_REFRESH = "io.vpnshare.action.REFRESH"
        private const val TAG = "VpnShare"
        private const val CHANNEL = "vpnshare.service"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        /** 服务已在跑就直接刷新；否则走普通 startService，绝不用 startForegroundService */
        fun refresh(ctx: Context) {
            val s = instance
            if (s != null) {
                s.refreshNow()
                return
            }
            runCatching {
                ctx.startService(Intent(ctx, ShareService::class.java).setAction(ACTION_REFRESH))
            }
        }

        private fun send(ctx: Context, action: String) {
            val i = Intent(ctx, ShareService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
