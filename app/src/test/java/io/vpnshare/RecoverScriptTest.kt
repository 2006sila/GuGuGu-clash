package io.vpnshare

import io.vpnshare.tether.RuleBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 命令行兜底脚本 scripts/recover.sh 的交叉校验。
 *
 * 它是独立副本（shell 读不到 Kotlin 常量），所以常量与接口名单只能靠测试钉住：
 * 早前它自己带了一份 fwmark 2025 / table 100 的字面量，改 RuleBuilder 而忘了同步的话，
 * 紧急恢复会「清了一半」—— 那比不清更糟（残留半套规则）。
 */
class RecoverScriptTest {

    private val f = File("../scripts/recover.sh")
    private val s = if (f.exists()) f.readText() else ""

    @Test
    fun scriptExists() {
        assertTrue("找不到 scripts/recover.sh（cwd=" + File(".").absolutePath + "）", f.exists())
    }

    @Test
    fun fwmarkAndTableMatchRuleBuilder() {
        assertTrue(
            "recover.sh 的 fwmark 与 RuleBuilder 不一致",
            s.contains("fwmark " + RuleBuilder.FWMARK + " lookup " + RuleBuilder.TABLE_ID)
        )
        assertTrue(
            "recover.sh 清理的路由表与 RuleBuilder 不一致",
            s.contains("ip route flush table " + RuleBuilder.TABLE_ID)
        )
    }

    @Test
    fun sweepsSameCandidateIfaces() {
        for (n in RuleBuilder.CANDIDATE_IFACES) {
            assertTrue("recover.sh 的候选名单缺少 " + n, s.contains(n))
        }
    }

    @Test
    fun prefersOnDeviceScriptThenFallsBackInline() {
        // 优先调用 App 落盘的那份（规则逻辑单一真源），没有才走内联清扫
        assertTrue(s.contains("/data/adb/vpnshare/run/tproxy.sh"))
        assertTrue(s.contains("HS_NAT"))
        assertTrue(s.contains("HS_MANGLE"))
    }

    @Test
    fun everyMutatingIptablesCallWaitsForXtableLock() {
        // 只校验会改规则的调用；末尾那条 iptables -S 是只读查询，不需要锁。
        // （脚本里还有 netstat 式的报告行，同样不动规则。）
        val mutating = s.lines().filter { l ->
            l.contains("iptables ") && listOf(" -F ", " -X ", " -D ", " -A ", " -I ").any { l.contains(it) }
        }
        assertTrue("应该有会改规则的调用", mutating.isNotEmpty())
        for (l in mutating) assertTrue("这行没等锁：\n" + l, l.contains("-w 100"))
    }
}
