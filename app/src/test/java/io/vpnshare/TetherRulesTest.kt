package io.vpnshare

import io.vpnshare.tether.RuleBuilder
import io.vpnshare.tether.TetherManager
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
tcp6       0      0 ::ffff:10.61.80.17:7892 ::ffff:10.61.80.1:62836 TIME_WAIT
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
