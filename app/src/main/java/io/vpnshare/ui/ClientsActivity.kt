package io.vpnshare.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.vpnshare.R
import io.vpnshare.service.ShareState
import io.vpnshare.tether.ClientMonitor

/** 客户端页：已连设备的 IP/MAC/接口，以及按设备屏蔽共享。 */
class ClientsActivity : BaseListActivity() {

    private val main = Handler(Looper.getMainLooper())
    private var current: List<ClientMonitor.Client> = emptyList()
    private var blocked: Set<String> = emptySet()
    private var iface: String? = null

    /** 防止上一轮还没回来就叠下一轮（ip neigh 走 su，慢的时候能到几百毫秒） */
    @Volatile private var busy = false

    /**
     * 每 5 秒自动刷新。
     * 设备上线/下线是随时发生的（电脑休眠、手机离得远），只在 onResume 刷一次
     * 会让用户对着过期列表手动点「刷新」。5 秒足够及时，也不至于把 su 调得太勤。
     */
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        title = "已连设备"
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(ticker)
    }

    private fun refresh() {
        if (busy) return
        busy = true
        val ifc = ShareState.iface.ifBlank { null }
        Thread {
            val clients = runCatching { ClientMonitor.list(ifc) }.getOrDefault(emptyList())
            val blk = runCatching { ClientMonitor.blocked() }.getOrDefault(emptySet())
            main.post {
                current = clients
                blocked = blk
                iface = ifc
                busy = false
                render()
            }
        }.start()
    }

    private fun render() {
        clear()
        setSummary("接口 " + (iface ?: "未检测到共享接口") +
            "\n在线 " + current.size + " 台，已屏蔽 " + blocked.size + " 台" +
            "\n点设备屏蔽或解除屏蔽（走 HS_BLOCK 链，停用共享时一并清理）")

        addButton("刷新") { refresh() }

        if (current.isEmpty()) {
            addEntry(R.drawable.ic_clients, "暂无设备", "确认热点已打开，且客户端与手机在同一网段", "")
            return
        }

        for (c in current) {
            val isBlocked = blocked.contains(c.ip)
            addEntry(
                iconRes = if (isBlocked) R.drawable.ic_warning else R.drawable.ic_clients,
                titleText = c.ip,
                subText = c.mac + "    " + c.iface + "    " + c.state,
                trailText = if (isBlocked) "已屏蔽" else ""
            ) {
                Thread {
                    val r = if (isBlocked) ClientMonitor.unblock(c.ip) else ClientMonitor.block(c.ip)
                    main.post {
                        Toast.makeText(
                            this@ClientsActivity,
                            (if (isBlocked) "已解除屏蔽 " else "已屏蔽 ") + c.ip + (if (r.ok) "" else "（失败）"),
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }.start()
            }
        }
    }
}
