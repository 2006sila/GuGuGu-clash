package io.vpnshare

import io.vpnshare.tether.Emergency
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紧急清理脚本。这是「电脑断网时的最后一根稻草」，
 * 覆盖不全就等于按钮白点了，所以把覆盖范围钉死。
 */
class EmergencyTest {

    private val s = Emergency.SCRIPT

    @Test
    fun coversAllThreeHookPoints() {
        // 跳转可能挂在 nat/mangle 的 PREROUTING，以及 FORWARD 上，三处都要摘
        assertTrue(s.contains("-t nat -D PREROUTING"))
        assertTrue(s.contains("-t mangle -D PREROUTING"))
        assertTrue(s.contains("-D FORWARD"))
        assertTrue("IPv6 泄漏规则也要摘", s.contains("ip6tables -D FORWARD"))
    }

    @Test
    fun destroysAllCustomChains() {
        for (c in listOf("HS_NAT", "HS_MANGLE", "HS_BLOCK")) {
            assertTrue("缺少 flush " + c, s.contains("-F " + c))
            assertTrue("缺少 delete " + c, s.contains("-X " + c))
        }
    }

    @Test
    fun clearsPolicyRoute() {
        // 不清 fwmark 的话，内核自己的出站流量仍会被打进 table 100
        assertTrue(s.contains("ip rule del fwmark 2025 lookup 100"))
        assertTrue(s.contains("ip route flush table 100"))
    }

    @Test
    fun usesLockWaitToAvoidXtablesRace() {
        // 抢不到 xtables 锁会只清一半 —— 那比不清更糟（残留半套规则）。
        // 只校验**会改规则**的调用；末尾那条 -S 是只读查询，不需要锁。
        val mutating = s.lines().filter { l ->
            l.contains("iptables ") && listOf(" -F ", " -X ", " -D ", " -A ", " -I ").any { l.contains(it) }
        }
        assertTrue("应该有会改规则的调用", mutating.isNotEmpty())
        for (l in mutating) assertTrue("这行没等锁：" + l, l.contains("-w 5"))
    }

    @Test
    fun doesNotDependOnDetectedInterface() {
        // 不靠检测结果 —— 检测本身就可能因为环境异常而失败，
        // 紧急清理必须把所有候选接口都扫一遍
        for (i in listOf("wlan2", "rndis0", "usb0", "eth0", "bt-pan")) {
            assertTrue("缺少接口 " + i, s.contains(i))
        }
        assertFalse("不应包含接口探测逻辑", s.contains("ip route show"))
    }

    @Test
    fun reportsRemainingRules() {
        assertTrue("要能回报残留情况", s.contains("---REMAIN---"))
    }
}