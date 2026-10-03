package io.guguguclash

import io.guguguclash.prefs.Prefs
import io.guguguclash.tether.RuleBuilder
import io.guguguclash.tether.TetherManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 共享接管的两个「静默出错」高危点：
 *  1. 端口监听判定 —— 判错会让内核明明活着却被判死（或反之），已经出过两次事故；
 *  2. 传给 tproxy.sh 的环境变量 —— 拼错一个名字，脚本读不到就整段规则失效，
 *     而且不报错（脚本里是 ${HS_XXX:-默认值} 的形式）。
 *
 * 第 2 点用一个交叉校验来钉死：脚本里出现的每个 $HS_XXX 必须都被 env() 导出过。
 */
class TetherRulesTest {

    // ---------------- 端口监听判定 ----------------

    /** 真机 netstat -ltn 抓到的原文 */
    private val realNetstat = """
tcp6       0      0 [::]:1053               [::]:*                  LISTEN
tcp6       0      0 [::]:7893               [::]:*                  LISTEN
tcp6       0      0 [::]:7892               [::]:*                  LISTEN
tcp6       0      0 [::]:7890               [::]:*                  LISTEN
tcp6       0      0 ::ffff:10.99.0.17:7892 ::ffff:10.99.0.1:62836 TIME_WAIT
""".trimIndent()

    @Test
    fun detectsPortsFromRealNetstat() {
        val s = TetherManager.parsePortStates(realNetstat, listOf(7890, 7892, 7893, 1053))
        assertEquals(true, s[7890])
        assertEquals(true, s[7892])
        assertEquals(true, s[7893])
        assertEquals(true, s[1053])
    }

    @Test
    fun doesNotMatchLongerPortByPrefix() {
        // 关键回归：78901 不能被当成 7890 在监听
        val s = TetherManager.parsePortStates("tcp6 0 0 [::]:78901 [::]:* LISTEN", listOf(7890))
        assertFalse("端口号前缀不能误匹配", s[7890] == true)
    }

    @Test
    fun doesNotConfuse53And1053() {
        // dnsPort 是 1053，但系统本身可能监听 53
        assertFalse(TetherManager.parsePortStates("tcp 0 0 0.0.0.0:1053 0.0.0.0:* LISTEN", listOf(53))[53] == true)
        // 反过来：只有 :53 时不能认为 1053 在监听
        assertFalse(TetherManager.parsePortStates("tcp 0 0 0.0.0.0:53 0.0.0.0:* LISTEN", listOf(1053))[1053] == true)
    }

    @Test
    fun reportsMissingPortAsDown() {
        // 出事场景：mixed(7890) 没绑上，其它都在 —— 早期只查 redir/tproxy/dns 会判成健康
        val partial = """
tcp6 0 0 [::]:7892 [::]:* LISTEN
tcp6 0 0 [::]:7893 [::]:* LISTEN
tcp6 0 0 [::]:1053 [::]:* LISTEN
""".trimIndent()
        val s = TetherManager.parsePortStates(partial, listOf(7890, 7892, 7893, 1053))
        assertEquals(false, s[7890])
        assertEquals(true, s[7892])
    }

    @Test
    fun emptyOutputMeansNothingListening() {
        val s = TetherManager.parsePortStates("", listOf(7890, 7892, 7893, 1053))
        assertTrue("空输出时所有端口都应是 false", s.values.none { it })
    }

    // ---------------- 端口集合必须与「查了哪些键」同源 ----------------

    /**
     * 回归：混合端口漏进集合时，state 里没有它的键，调用方读 state[mixedPort] 得到 null，
     * 「端口齐全」恒判 false → 每次启动都把健康内核杀掉重启（断流），
     * 而「发现残留内核就直接复用、不断网」的分支永远走不到。
     */
    @Test
    fun mixedPortIsPartOfThePortSet() {
        val p = Prefs.Data(mixedPort = 7890, redirPort = 7892, tproxyPort = 7893, dnsPort = 1053)
        assertEquals(listOf(7892, 7893, 1053, 7890), TetherManager.portsToCheck(p))
    }

    @Test
    fun zeroMixedPortMeansNotChecked() {
        val p = Prefs.Data(mixedPort = 0, redirPort = 7892, tproxyPort = 7893, dnsPort = 1053)
        assertEquals(3, TetherManager.portsToCheck(p).size)
        assertFalse(TetherManager.portsToCheck(p).contains(0))
    }

    @Test
    fun allPortsListeningRequiresMixedPortKey() {
        val p = Prefs.Data(mixedPort = 7890, redirPort = 7892, tproxyPort = 7893, dnsPort = 1053)
        assertTrue(
            TetherManager.allPortsListening(
                mapOf(7892 to true, 7893 to true, 1053 to true, 7890 to true), p
            )
        )
        // mixed 没绑上 → 不齐（避免复用孤儿内核）
        assertFalse(
            TetherManager.allPortsListening(
                mapOf(7892 to true, 7893 to true, 1053 to true, 7890 to false), p
            )
        )
        // 键压根不存在（旧实现的真实形状）→ 必须判不齐，绝不能因为「查不到」而当健康
        assertFalse(
            TetherManager.allPortsListening(mapOf(7892 to true, 7893 to true, 1053 to true), p)
        )
    }

    @Test
    fun portSetAndParserShareTheSameSource() {
        val p = Prefs.Data(mixedPort = 7890, redirPort = 7892, tproxyPort = 7893, dnsPort = 1053)
        val raw = """
tcp6 0 0 [::]:7892 [::]:* LISTEN
tcp6 0 0 [::]:7893 [::]:* LISTEN
tcp6 0 0 [::]:1053 [::]:* LISTEN
""".trimIndent()
        val state = TetherManager.parsePortStates(raw, TetherManager.portsToCheck(p))
        assertFalse(TetherManager.allPortsListening(state, p))
        assertEquals(true, state[7892])
        assertEquals(false, state[7890])
    }

    // ---------------- 共享接口标识（换网段必须能看出来）----------------

    @Test
    fun tetherKeyCarriesAddressesSoSubnetChangesAreVisible() {
        val addrA = "5: wlan2    inet 10.99.0.170/24 brd 10.99.0.255 scope global wlan2"
        val a = TetherManager.tetherKeyOf("wlan2", addrA)
        assertEquals("wlan2|10.99.0.170", a)
        // 同一网络下多次读取必须稳定，否则每次都判「变化」→ 反复重装规则
        assertEquals(a, TetherManager.tetherKeyOf("wlan2", addrA))
        // 换了网段（接口名不变）必须能看出来，否则规则永远不会重建
        val addrB = "5: wlan2    inet 192.168.43.1/24 brd 192.168.43.255 scope global wlan2"
        assertTrue("地址变化必须体现在标识里", TetherManager.tetherKeyOf("wlan2", addrB) != a)
        assertEquals(null, TetherManager.tetherKeyOf(null, addrA))
        assertEquals(null, TetherManager.tetherKeyOf("", addrA))
    }

    // ---------------- tproxy.sh 环境变量交叉校验 ----------------

    @Test
    fun everyScriptVariableIsExportedByEnv() {
        val f = File("src/main/assets/tproxy.sh")
        assertTrue("找不到 tproxy.sh（路径 " + f.absolutePath + "）", f.exists())
        val script = f.readText()

        // 脚本里引用的所有 HS_ 变量
        val used = Regex("\\\$(HS_[A-Z0-9_]+)").findAll(script).map { it.groupValues[1] }.toSet()
        assertTrue("脚本里居然没用任何 HS_ 变量？", used.isNotEmpty())

        // 脚本「行首自我赋值」的变量由脚本自己掌管（HS_NAT=HS_NAT 这种，
        // 而且这种写法会覆盖掉环境里的值），不需要也不应该由 env() 提供。
        // 只有「读环境变量 + 默认值」的写法（[ -z "$HS_X" ] && HS_X=...）才必须由 env() 喂进来 ——
        // 那种写法一旦变量名对不上，脚本会静默用默认值，规则整段失效且不报错。
        val selfAssigned = Regex("^\\s*(HS_[A-Z0-9_]+)=", RegexOption.MULTILINE)
            .findAll(script).map { it.groupValues[1] }.toSet()

        val exported = RuleBuilder.env(RuleBuilder.TetherConfig(iface = "wlan2"), "start")
        val fromEnv = used.filterNot { it in selfAssigned }
        assertTrue("应该有若干变量是从环境读进来的，否则这个测试没意义", fromEnv.isNotEmpty())

        val missing = fromEnv.filterNot { exported.contains("export " + it + "=") }
        assertTrue(
            "脚本从环境读取但 env() 没有导出的变量（会静默使用默认值，规则整段失效）: " + missing,
            missing.isEmpty()
        )
        // 反过来也校验一下：env() 导出的变量脚本必须真的会用，避免留下无人消费的字段
        val unused = exported.split("\n").mapNotNull { Regex("export (HS_[A-Z0-9_]+)=").find(it)?.groupValues?.get(1) }
            .filterNot { it in used }
        assertTrue("env() 导出了脚本根本没用到的变量: " + unused, unused.isEmpty())
    }

    @Test
    fun envCarriesPortsAndFlags() {
        val e = RuleBuilder.env(
            RuleBuilder.TetherConfig(
                iface = "wlan2", redirPort = 7892, tproxyPort = 7893, dnsPort = 1053,
                proxyUdp = true, blockIpv6 = true
            ), "start"
        )
        assertTrue(e.contains("export HS_ACTION=start"))
        assertTrue(e.contains("export HS_IFACE='wlan2'"))
        assertTrue(e.contains("export HS_REDIR=7892"))
        assertTrue(e.contains("export HS_TPROXY=7893"))
        assertTrue(e.contains("export HS_DNS=1053"))
        assertTrue(e.contains("export HS_UDP=1"))
        assertTrue(e.contains("export HS_BLOCK_V6=1"))
        // mark / 表号必须由 App 注入，脚本里那份只是命令行默认值
        assertTrue(e.contains("export HS_MARK=" + RuleBuilder.FWMARK))
        assertTrue(e.contains("export HS_TABLE=" + RuleBuilder.TABLE_ID))
        assertTrue(e.contains("export HS_IFACES='"))
    }

    @Test
    fun envTurnsFlagsOffCorrectly() {
        val e = RuleBuilder.env(
            RuleBuilder.TetherConfig(iface = "ap0", proxyUdp = false, blockIpv6 = false), "stop"
        )
        assertTrue(e.contains("export HS_ACTION=stop"))
        assertTrue(e.contains("export HS_UDP=0"))
        assertTrue(e.contains("export HS_BLOCK_V6=0"))
    }

    @Test
    fun envStripsQuotesFromIfaceToPreventInjection() {
        // 接口名来自系统，但抄进单引号串里必须先剔掉单引号，
        // 否则一个带 ' 的值能把后面的脚本命令顶掉
        val e = RuleBuilder.env(RuleBuilder.TetherConfig(iface = "wl'an2"), "start")
        assertTrue(e.contains("export HS_IFACE='wlan2'"))
        assertFalse("不能把注入的单引号原样带进脚本", e.contains("wl'an2"))
    }
}
