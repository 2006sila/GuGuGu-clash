package io.vpnshare.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * mihomo REST 客户端。映射 CMFA 的节点页 / 连接页 / 日志页所需能力。
 * 端点均已在 hub/route 目录下的 Go 源码里实测枚举：proxies、connections、configs、traffic、logs、version。
 */
object CoreApi {

    private const val BASE = "http://127.0.0.1:9090"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // 节点延迟接口本身要等 timeout 毫秒才返回，客户端读超时必须显著更大，
    // 否则每次都会卡在边界上超时（曾因此把「链路正常」误判为失败）。
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class Node(val name: String, val type: String, val delay: Int = -1, val alive: Boolean = true)
    data class Group(
        val name: String,
        val type: String,
        val now: String,
        val all: List<String>,
        val nodes: List<Node>
    )

    /** 最近一次请求失败原因，供 UI 展示（明文策略、连接被拒等都会体现在这里） */
    @Volatile
    var lastError: String = ""
        private set

    private fun get(path: String): String? = try {
        client.newCall(Request.Builder().url(BASE + path).build()).execute().use { resp ->
            if (resp.isSuccessful) {
                lastError = ""
                resp.body?.string()
            } else {
                lastError = "HTTP " + resp.code
                null
            }
        }
    } catch (e: Exception) {
        lastError = (e.message ?: e.toString()).take(160)
        null
    }

    private fun send(method: String, path: String, body: String): Boolean = try {
        val b = if (body.isEmpty()) "".toRequestBody(null) else body.toRequestBody(JSON)
        client.newCall(Request.Builder().url(BASE + path).method(method, b).build()).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    /**
     * 断开内核里所有已有连接。
     *
     * 换网 / 网段变化后，旧连接绑定的本机地址已经失效，但内核会一直保留它们；
     * 客户端表现为「网页转圈然后超时」。box4magisk 与 Surfing v7 的做法是在网络变化
     * 的钩子里直接 DELETE /connections，这里对齐。
     */
    fun closeConnections(): Boolean = send("DELETE", "/connections", "")

    fun version(): String? = get("/version")?.let {
        runCatching { JSONObject(it).optString("version") }.getOrNull()
    }

    fun groups(): List<Group> {
        val raw = get("/proxies") ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val proxies = root.optJSONObject("proxies") ?: return emptyList()
        val out = mutableListOf<Group>()
        for (key in proxies.keys()) {
            val o = proxies.optJSONObject(key) ?: continue
            val all = o.optJSONArray("all") ?: continue
            val names = (0 until all.length()).map { all.optString(it) }
            val nodes = names.map { n ->
                val no = proxies.optJSONObject(n)
                Node(n, no?.optString("type") ?: "", no?.optJSONArray("history")?.let { h ->
                    if (h.length() > 0) h.optJSONObject(h.length() - 1)?.optInt("delay", -1) ?: -1 else -1
                } ?: -1)
            }
            out += Group(key, o.optString("type"), o.optString("now"), names, nodes)
        }
        return out
    }

    fun select(group: String, node: String): Boolean =
        send("PUT", "/proxies/" + encode(group), "{\"name\":\"" + jsonEscape(node) + "\"}")

    fun testDelay(node: String, url: String = "https://www.gstatic.com/generate_204", timeoutMs: Int = 5000): Int {
        val path = "/proxies/" + encode(node) + "/delay?timeout=" + timeoutMs + "&url=" + encode(url)
        val raw = get(path) ?: return -1
        return runCatching { JSONObject(raw).optInt("delay", -1) }.getOrDefault(-1)
    }

    /**
     * 整组测速，返回「节点名 -> 延迟(ms)」。
     * mihomo 的 /group/{name}/delay 会在内核内部并发测完所有节点再一次性返回，
     * 比在 App 里逐个请求快一个数量级，也不会把 REST 打爆。
     */
    fun groupDelayMap(group: String, url: String, timeoutMs: Int = 5000): Map<String, Int> {
        val raw = get("/group/" + encode(group) + "/delay?timeout=" + timeoutMs +
            "&url=" + java.net.URLEncoder.encode(url, "UTF-8")) ?: return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (k in o.keys()) out[k] = o.optInt(k, -1)
        return out
    }

        data class Conn(
        val id: String,
        val host: String,
        val rule: String,
        val chain: String,
        val download: Long,
        val upload: Long
    )

    /**
     * 找出「总控组」。
     * 机场配置里组名五花八门（♻️ 手动切换 / 🚀 节点选择 / PROXY…），
     * 但结构有个共性：别的组都指向它。所以按「被引用次数最多」来判定最稳。
     * GLOBAL 是内核自带的，它引用所有人，必须排除。
     */
    fun mainGroupName(): String? {
        val gs = groups().filterNot { it.name.equals("GLOBAL", true) }
        if (gs.isEmpty()) return null
        val refs = HashMap<String, Int>()
        for (g in gs) refs[g.now] = (refs[g.now] ?: 0) + 1
        val byRef = gs.maxByOrNull { refs[it.name] ?: 0 }
        if (byRef != null && (refs[byRef.name] ?: 0) > 0) return byRef.name
        return gs.maxByOrNull { it.nodes.size }?.name
    }

    /**
     * 在指定组里测速并切到最快的节点。
     * 只在「当前选中的不是真实节点」（DIRECT/REJECT/空）时才需要 —— 机场配置的默认值
     * 往往就是 DIRECT，不换的话共享出去的流量全部直连。
     * 返回被选中的节点名；没找到可用节点返回 null。
     */
    fun selectFastest(group: String, url: String = "https://www.gstatic.com/generate_204", timeoutMs: Int = 5000): String? {
        val g = groups().firstOrNull { it.name == group } ?: return null
        val realNames = g.nodes.filterNot { it.type in PSEUDO_TYPES }.map { it.name }.toSet()
        val map = groupDelayMap(group, url, timeoutMs)
        if (map.isEmpty()) return null
        val best = map
            .filter { it.value > 0 && realNames.contains(it.key) }   // 只在真实节点里挑
            .minByOrNull { it.value } ?: return null
        return if (select(group, best.key)) best.key else null
    }

    /**
     * 非真实代理的「伪节点」类型。
     * 这些在候选列表里也占一位，延迟还特别低（DIRECT 是本地直出，0ms），
     * 不排除的话「选最快」永远会选中 DIRECT，等于没选。
     */
    private val PSEUDO_TYPES = setOf(
        "Direct", "Reject", "Compatible", "Pass", "Dns",
        "Selector", "URLTest", "Fallback", "LoadBalance", "Relay"
    )

    /**
     * 活性检查结果。
     * @param node      现在实际生效的节点名（可能为空）
     * @param switched  是否发生了切换
     * @param measured  测速是否拿到了有效数据。false 表示测速本身失败，
     *                  此时**不做任何判断** —— 把好节点误换掉比不换更糟
     * @param available 测速中可用的真实节点数
     */
    data class AliveResult(val node: String, val switched: Boolean, val measured: Boolean, val available: Int)

    /**
     * 确保总控组指向一个**【活着】的真实节点**。
     *
     * 为什么需要：isRealNodeSelected 只看「选中的是不是真实节点类型」，不看它通不通。
     * 机场节点随时会挂，而总控组会一直指向那个已失效的节点 —— 于是被判定成
     * 「无需优选」，共享出去的流量全部挂在一个死节点上。
     * 实测过一次：电脑走代理的站点全部超时、出口 IP 取不到，而同一组里另外三十多个
     * 节点延迟都在 100ms 上下。
     *
     * 只做一次组测速：结果同时用于「判断当前节点活没活」和「挑最快的替代」。
     *
     * 安全性：组测速偶发会返回错误对象（实测遇到过），此时解析出来只有 "message" 一个键、
     * 没有任何正延迟。这种情况按「测速无效」处理并原样返回当前节点 ——
     * 宁可不换，也不能因为一次失败的测速把正在用的好节点换掉。
     */
    fun ensureAliveNode(group: String): AliveResult? {
        val g = groups().firstOrNull { it.name == group } ?: return null
        val real = g.nodes.filterNot { it.type in PSEUDO_TYPES }.map { it.name }.toSet()
        if (real.isEmpty()) return null

        val now = g.now
        val map = groupDelayMap(group, "https://www.gstatic.com/generate_204")
        // 只在真实节点里统计，DIRECT 那种 0ms 的本地直出不能算「可用节点」
        val alive = map.filter { it.value > 0 && real.contains(it.key) }
        if (alive.isEmpty()) {
            // 测速没拿到任何有效数据 —— 不判断、不切换
            return AliveResult(now, switched = false, measured = false, available = 0)
        }
        // 当前节点既是真实节点、又在测速里活着 → 保留（尊重用户的手动选择）
        if (real.contains(now) && alive.containsKey(now)) {
            return AliveResult(now, switched = false, measured = true, available = alive.size)
        }
        val best = alive.minByOrNull { it.value }?.key
            ?: return AliveResult(now, switched = false, measured = true, available = 0)
        val ok = select(group, best)
        return AliveResult(if (ok) best else now, switched = ok, measured = true, available = alive.size)
    }

    data class Totals(val up: Long, val down: Long)

    /**
     * 累计流量。mihomo 的 /connections 除了连接列表，还带回 uploadTotal / downloadTotal，
     * 这两个就是内核启动至今的累计字节数 —— 采样两次的差值即为实时速率，
     * 不必再开一条 /traffic 长连接。
     */
    fun totals(): Totals {
        val raw = get("/connections") ?: return Totals(0, 0)
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return Totals(0, 0)
        return Totals(o.optLong("uploadTotal"), o.optLong("downloadTotal"))
    }

    /** 一次 /connections 的完整结果：全局累计 + 连接明细 */
    data class Snapshot(val totals: Totals, val conns: List<Conn>)

    /**
     * 一次请求拿全。
     *
     * 流量采样既要算速率（totals）又要算梯子用量（逐条连接），
     * 拆成两次 /connections 会白白多一倍请求量与一次 JSON 解析。
     */
    fun snapshot(): Snapshot {
        val raw = get("/connections") ?: return Snapshot(Totals(0, 0), emptyList())
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return Snapshot(Totals(0, 0), emptyList())
        return Snapshot(
            Totals(root.optLong("uploadTotal"), root.optLong("downloadTotal")),
            parseConns(root)
        )
    }

    fun connections(): List<Conn> {
        val raw = get("/connections") ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        return parseConns(root)
    }

    private fun parseConns(root: JSONObject): List<Conn> {
        val arr = root.optJSONArray("connections") ?: return emptyList()
        val out = mutableListOf<Conn>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val meta = o.optJSONObject("metadata")
            val chain = o.optJSONArray("chains")
            val chainStr = if (chain == null) "" else (0 until chain.length()).joinToString(" > ") { chain.optString(it) }
            out += Conn(
                id = o.optString("id"),
                host = meta?.optString("host") ?: meta?.optString("destinationIP") ?: "",
                rule = o.optString("rule"),
                chain = chainStr,
                download = o.optLong("download"),
                upload = o.optLong("upload")
            )
        }
        return out
    }

    fun closeConnection(id: String): Boolean = send("DELETE", "/connections/" + encode(id), "")

    fun closeAllConnections(): Boolean = send("DELETE", "/connections", "")

    /** PUT /configs?force=true，body 指定新的配置文件路径，实现热重载 */
    fun reload(path: String): Boolean =
        send("PUT", "/configs?force=true", "{\"path\":\"" + jsonEscape(path) + "\"}")

    fun patchMode(mode: String): Boolean =
        send("PATCH", "/configs", "{\"mode\":\"" + jsonEscape(mode) + "\"}")

    /**
     * 出口探测：请求 generate_204。App 自身的流量会被内核 tun 捕获，
     * 所以这条能端到端验证「代理节点是否真的可用」，而不只是「内核活着」。
     */
    /**
     * 出口探测。
     *
     * viaProxyPort > 0 时显式走内核的 http/socks 混合端口。
     * 这很关键：tun 关掉后 App 自身的流量【不】经过内核，直接请求 gstatic 必然被墙，
     * 探测会误判成「节点不可用」，进而把刚装好的热点规则回滚掉 —— 表现为
     * 「服务显示已回滚、电脑拿不到代理」。
     */
    /**
     * 从 /proxies/{组}/delay 的响应解析延迟。
     *
     * 返回 -1 表示「不可用」：无正延迟、解析失败、或返回的是错误对象
     * （实测内核在测不动时会返回 {"message":"An error occurred in the delay test"}）。
     * 抽成纯函数是为了能测 —— 三级探测的判定逻辑曾经因为组名写死而整体失效，
     * 这类"看着在工作其实没工作"的错误只有测试能挡住。
     */
    fun nodeDelayOf(raw: String?): Int {
        if (raw.isNullOrBlank()) return -1
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return -1
        val d = o.optInt("delay", -1)
        return if (d > 0) d else -1
    }

    /** 整组测速的结果：可用节点数与返回条目数 */
    data class GroupProbe(val available: Int, val total: Int)

    /**
     * 从 /group/{组}/delay 的响应统计可用节点数。
     *
     * 只要还有 >=1 个节点有正延迟，就说明「链路本身是通的，只是当前节点坏了」——
     * 这一级存在的全部意义就是别让一个坏节点把整条链路判死。
     * 注意 DIRECT 那种本地直出也会返回正延迟，调用方需自行确认组内是否含伪节点。
     */
    fun groupProbeOf(raw: String?): GroupProbe {
        if (raw.isNullOrBlank()) return GroupProbe(0, 0)
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return GroupProbe(0, 0)
        var n = 0
        for (k in o.keys()) if (o.optInt(k, -1) > 0) n++
        return GroupProbe(n, o.length())
    }

    fun probeEgress(timeoutSec: Int = 8, viaProxyPort: Int = 0): Int {
        // 注意单位：本参数是【秒】。曾经按毫秒传（probeEgress(8) 被当成 8ms），
        // 导致延迟接口 timeout=8ms、OkHttp 也 8ms，探测永远瞬间失败。
        val timeoutMs = timeoutSec * 1000
        val probeUrl = "https://www.gstatic.com/generate_204"

        // 组名绝不能写死。
        // CMFA 的默认策略组叫 PROXY，但机场自己命名的组五花八门 ——
        // 本项目实测的机场叫「♻️ 手动切换」，另有 45 个组没有一个是 PROXY。
        // 早期这里硬编码 /proxies/PROXY 与 /group/PROXY，两个请求都 404，
        // 于是所谓「三级降级探测」的前两级从未生效，每次探测都退化成第三级的
        // 「单发直连」—— 探测对节点状态完全失明。
        // 实测代价：节点已死、同组另外 34 个节点全好时，探测仍报 connection closed，
        // 判定整条链路不通并回滚规则。
        val group = mainGroupName()
        if (group != null) {
            val g = encode(group)

            // 第一优先：内核自己测「当前选中节点」。
            run {
                val raw = probeGet("/proxies/" + g + "/delay?timeout=" + timeoutMs +
                    "&url=" + java.net.URLEncoder.encode(probeUrl, "UTF-8"))
                if (nodeDelayOf(raw) > 0) return 204
            }

            // 第二优先：整组测速。50 个节点里只要有 1 个能通，链路就是可用的，
            // 用户手动切到那个可用节点即可（节点页已经能看到每个节点的延迟）。
            // 这一级存在的意义：别让一个坏节点把整条链路判死。
            run {
                val raw = probeGet("/group/" + g + "/delay?timeout=" + timeoutMs +
                    "&url=" + java.net.URLEncoder.encode(probeUrl, "UTF-8"))
                val gp = groupProbeOf(raw)
                if (gp.available > 0) {
                    lastError = ""
                    return 204
                }
                if (raw != null) lastError = "整组 " + gp.total + " 个节点全部不通"
            }
        } else {
            lastError = "取不到总控策略组，跳过节点级探测"
        }

        // 兜底：tun 模式下 App 自身流量已被代理，直接请求一次即可
        return try {
            val b = OkHttpClient.Builder()
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (viaProxyPort > 0) {
                b.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", viaProxyPort)))
            }
            b.build().newCall(Request.Builder().url(probeUrl).build()).execute().use { it.code }
        } catch (e: Exception) {
            lastError = "出口探测失败：" + (e.message ?: "").take(120)
            -1
        }
    }

    /** 探测专用的 REST 读取：读超时放宽，避免和接口自身的 timeout 撞在边界上 */
    private fun probeGet(path: String): String? = try {
        probeClient.newCall(Request.Builder().url(BASE + path).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()
            else { lastError = "HTTP " + resp.code; null }
        }
    } catch (e: Exception) {
        lastError = "探测请求失败：" + (e.message ?: "").take(100)
        null
    }

    // /traffic 是 chunked 流式端点，普通 GET 会一直读到超时。
    // 流量曲线改由 ui/ConnectionsActivity 用独立的短超时流式客户端实现（下一阶段）。

    // ---------------- 供应商 ----------------
    // 端点实测自 hub/route/provider.go：
    //   GET  /providers/proxies                    列表（{"providers":{...}}）
    //   GET  /providers/proxies/{name}             单个
    //   PUT  /providers/proxies/{name}             立即更新（204）
    //   GET  /providers/proxies/{name}/healthcheck 健康检查（204）
    //   GET  /providers/rules                      列表
    //   PUT  /providers/rules/{name}               更新
    // 字段名实测自 adapter/provider/provider.go 的 providerForApi 与 subscription_info.go
    // （SubscriptionInfo 无 json tag，序列化为大写 Upload/Download/Total/Expire）。

    data class Provider(
        val name: String,
        val kind: String,
        val type: String,
        val vehicleType: String,
        val itemCount: Int,
        val updatedAt: String,
        val upload: Long,
        val download: Long,
        val total: Long,
        val expire: Long
    )

    fun proxyProviders(): List<Provider> = providers("proxies")
    fun ruleProviders(): List<Provider> = providers("rules")

    private fun providers(kind: String): List<Provider> {
        val raw = get("/providers/" + kind) ?: return emptyList()
        return parseProviders(kind, raw)
    }

    /** 纯函数，便于 JVM 单测；规格来自 adapter/provider/provider.go 的 providerForApi */
    fun parseProviders(kind: String, raw: String): List<Provider> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val obj = root.optJSONObject("providers") ?: return emptyList()
        val out = mutableListOf<Provider>()
        for (name in obj.keys()) {
            val o = obj.optJSONObject(name) ?: continue
            val proxies = o.optJSONArray("proxies")
            val si = o.optJSONObject("subscriptionInfo")
            out += Provider(
                name = name,
                kind = kind,
                type = o.optString("type"),
                vehicleType = o.optString("vehicleType"),
                itemCount = proxies?.length() ?: o.optInt("ruleCount", 0),
                updatedAt = o.optString("updatedAt"),
                upload = si?.optLong("Upload", 0L) ?: 0L,
                download = si?.optLong("Download", 0L) ?: 0L,
                total = si?.optLong("Total", 0L) ?: 0L,
                expire = si?.optLong("Expire", 0L) ?: 0L
            )
        }
        return out.sortedBy { it.name }
    }

    fun updateProvider(kind: String, name: String): Boolean =
        send("PUT", "/providers/" + kind + "/" + encode(name), "")

    fun healthCheckProvider(name: String): Boolean =
        get("/providers/proxies/" + encode(name) + "/healthcheck") != null

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun jsonEscape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
