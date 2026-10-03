package io.guguguclash.service

/**
 * 梯子流量计：只统计**真正走代理节点**的字节数。
 *
 * 与 ShareState.totalBytes（内核全局累计）的区别：
 * 热点客户端的流量是在 iptables 层全量重定向进内核的，内核先接住、再决定走节点还是直连。
 * 所以 totalBytes 里直连占了相当大一部分（实测下一个 36 MB 的国内文件，totalBytes 涨 36 MB，
 * 但机场配额一点没动）。这个类只挑「出口是真实节点」的连接来累加。
 *
 * ## 为什么必须增量累加，不能对活跃连接求和
 *
 * 连接一关闭就从内核的 /connections 里消失，它的字节数再也拿不到。
 * 实测同一时刻活跃连接合计只有 0.04 MB，而内核全局累计是 91.66 MB —— 差两千倍。
 * 所以按连接 id 记录「上次看到的字节」，每轮只加增量；
 * 连接消失时它的字节早已在之前的轮次里全部计入，既不丢也不重。
 *
 * 全部是纯函数，不碰 Android，可直接单测。
 */
object ProxiedTraffic {

    /** 一轮采样里需要的一条连接（对应 CoreApi.Conn 的子集） */
    data class Item(val id: String, val chain: String, val upload: Long, val download: Long) {
        val bytes: Long get() = upload + download
    }

    /**
     * 累加器状态。
     * @param last  连接 id → 上次看到的累计字节
     * @param total 自本次共享开始，走节点的累计字节
     */
    data class State(val last: Map<String, Long> = emptyMap(), val total: Long = 0L) {
        companion object { val EMPTY = State() }
    }

    /** 初始状态（也挂在对象上，调用方写 ProxiedTraffic.EMPTY 即可） */
    val EMPTY = State.EMPTY

    /**
     * 累加一轮。
     *
     * 空输入直接返回原状态（不清 last、不推进 total）：一次采样失败会返回空列表，
     * 若此时清掉 last，下一轮同样的连接会被当成新连接、把全部字节再加一遍 —— 重复计数。
     */
    fun account(state: State, conns: List<Item>): State {
        if (conns.isEmpty()) return state
        var total = state.total
        val now = HashMap<String, Long>(conns.size)
        for (c in conns) {
            val cur = c.bytes
            now[c.id] = cur
            if (!isProxied(c.chain)) continue
            val prev = state.last[c.id]
            // 首次见到该连接：把当前值整笔计入（它的历史我们无从得知）
            // 之后只加增量；coerceAtLeast 防御内核重启导致的计数回退
            total += if (prev == null) cur.coerceAtLeast(0L) else (cur - prev).coerceAtLeast(0L)
        }
        // 只保留本轮出现的 id，已关闭的连接自然淘汰
        return State(now, total)
    }

    /**
     * chain 的首段就是真实出口，形如 "🇭🇰香港 01 > ♻️ 手动切换 > 🌏 国外网站"。
     * DIRECT / REJECT 不消耗机场配额。
     */
    fun isProxied(chain: String): Boolean {
        val first = chain.substringBefore(" > ").trim()
        return first.isNotEmpty() && first != "DIRECT" && first != "REJECT"
    }
}
