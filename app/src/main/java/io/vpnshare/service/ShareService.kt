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
import android.net.Network
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
    private var lastIface: String = ""

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
            // 内核将以 App uid 运行，每次启动都要确保它能穿越 /data/adb 并读写运行目录
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

            CoreManager.setLogListener { line -> ShareState.log(line) }
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
            } else if (!CoreManager.start(
                    onLog = { line -> ShareState.log(line) },
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
                        ShareState.log("已取消热点接管：电脑恢复直连（不影响上网）。手机自身代理仍可用。")
                    }
                }
            }
            registerNetworkCallback()
            scheduleWatchdog()
            scheduleSubUpdate()

            // 按网络自动切换。没配规则时它自己就会跳过，不会注册任何回调。
            io.vpnshare.service.NetworkWatcher.start(applicationContext)
            Prefs.save(ctx, p.copy(enabled = true))
            notifyState("咕咕咕clash 已启用")

            // 机场配置的总控组默认常常指向 DIRECT —— 不换成真实节点的话，
            // 共享给电脑的流量会全部直连（用户看到的就是「开着但没走代理」）。
            // 这里兜一次：测速并切到最快节点。用户之后可以随时在节点页自己换。
            work.execute {
                runCatching {
                    val g = io.vpnshare.core.CoreApi.mainGroupName()
                    if (g == null) {
                        ShareState.log("未找到总控策略组，跳过自动优选")
                    } else if (io.vpnshare.core.CoreApi.isRealNodeSelected(g)) {
                        ShareState.log("总控组「" + g + "」已选中真实节点，跳过自动优选")
                    } else {
                        ShareState.log("总控组「" + g + "」当前是直连，正在自动优选最快节点…")
                        val best = io.vpnshare.core.CoreApi.selectFastest(g)
                        if (best != null) ShareState.log("已自动切换到「" + best + "」")
                        else ShareState.log("WARN 自动优选失败：该组没有可用节点")
                    }
                }
            }
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        } finally {
            // 无论成功失败都必须复位，否则一次失败后再也无法启动
            starting = false
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
        lastIface = det ?: ""

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
     * 内核该监听的端口是不是都在。必须连 **mixed 端口** 一起查：
     * 出口探测正是走它，只查 redir/tproxy/dns 的话，一个「redir/tproxy/dns 正常但
     * mixed 没绑上」的孤儿内核会被判为健康而复用 —— 随后探测超时、服务判定内核已死、
     * 整个共享被回滚。这个坑踩过两次。
     */
    private fun portsListening(p: Prefs.Data): Boolean = runCatching {
        val rep = TetherManager.listeningPortsDetailed(p)
        val mixedOk = p.mixedPort <= 0 || rep.state[p.mixedPort] == true
        rep.state[p.redirPort] == true && rep.state[p.tproxyPort] == true &&
            rep.state[p.dnsPort] == true && mixedOk
    }.getOrDefault(false)

    private fun refreshTether() {
        val ctx = applicationContext
        val p = Prefs.load(ctx)
        if (!ShareState.running) return
        try {
            val det = TetherManager.detectIface(p)
            if (det != lastIface) {
                ShareState.log("共享接口变化：" + lastIface + " -> " + (det ?: "无"))
                val r = TetherManager.apply(ctx, p)
                r.out.lines().filter { it.isNotBlank() }.forEach { ShareState.log(it) }
                lastIface = det ?: ""
                ShareState.iface = det ?: ""
            }
            if (!CoreManager.isRunning() && ShareState.running) {
                // 不在这里悄悄重启：内核中途死掉时，tun 的路由改动可能处于半残状态，
                // 继续留着会持续吞热点流量。宁可停服务让用户重新开一次。
                ShareState.log("检测到内核已退出，停止服务以恢复电脑上网")
                work.execute { stopAll() }
            }
        } catch (e: Exception) {
            ShareState.log("巡检异常：" + (e.message ?: ""))
        }
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

            CoreManager.setLogListener(null)
            val killResult = CoreManager.stop()
            // pkill 的退出码不可靠，用脚本里的 pidof 复核结果判断
            val killed = killResult.out.contains("stopped=yes")
            ShareState.log(if (killed) "内核已停止" else "WARN 内核未确认停止：" + killResult.combined.take(120))

            if (p.restoreOffloadOnStop) {
                TetherOffload.setDisabled(false)
                ShareState.log("已恢复网络共享硬件加速设置")
            }

            Prefs.save(ctx, p.copy(enabled = false))
            ShareState.phase = ShareState.Phase.IDLE
            ShareState.tcpOk = false
            ShareState.udpOk = false
            ShareState.dnsOk = false
            ShareState.iface = ""
            lastIface = ""
        } catch (e: Exception) {
            ShareState.log("停止异常：" + (e.message ?: ""))
        } finally {
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
    private fun scheduleSubUpdate() {
        // 间隔取值顺序：该配置自己的（机场下发或用户单设）→ 全局设置。
        val p = Prefs.load(applicationContext)
        val prof = ProfileStore(applicationContext).current(p.currentProfileId)
        val hours = prof?.intervalHours?.takeIf { it > 0 } ?: p.subUpdateHours
        if (hours <= 0) {
            ShareState.log("订阅自动更新已关闭")
            return
        }
        val delay = hours * 3600_000L
        main.postDelayed(object : Runnable {
            override fun run() {
                if (!ShareState.running) return
                work.execute { runSubUpdate() }
                main.postDelayed(this, delay)
            }
        }, delay)
        ShareState.log("订阅自动更新已启用：每 " + hours + " 小时")
    }

    fun runSubUpdate() {
        val ctx = applicationContext
        val p = Prefs.load(ctx)
        val profile = ProfileStore(ctx).current(p.currentProfileId)
        if (profile == null) {
            ShareState.log("跳过订阅更新：尚未导入订阅")
            return
        }
        ShareState.log("开始更新订阅：" + profile.name)
        val r = SubImporter.update(ctx, profile, p.userAgent)
        ShareState.log((if (r.ok) "OK  " else "ERR ") + r.message)
        if (!r.ok) return

        val prep = ProfileRunner.prepareConfig(ctx, Prefs.load(ctx))
        if (!prep.ok) {
            ShareState.log("ERR 新配置未通过自检，保留旧配置：" + (prep.error ?: ""))
            return
        }
        ShareState.log(if (ProfileRunner.hotReload()) "OK  已热重载新配置（" + prep.nodeCount + " 节点）" else "ERR 热重载失败，重启服务生效")
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
        val r = object : Runnable {
            override fun run() {
                if (!ShareState.running) { trafficRunnable = null; return }
                Thread {
                    val t = io.vpnshare.core.CoreApi.totals()
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
