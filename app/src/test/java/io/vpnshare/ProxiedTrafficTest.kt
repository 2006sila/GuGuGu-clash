package io.vpnshare

import io.vpnshare.service.ProxiedTraffic
import io.vpnshare.service.ProxiedTraffic.Item
import io.vpnshare.service.ProxiedTraffic.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 梯子流量计的累加逻辑。
 *
 * 重点验证的是「不能对活跃连接求和」这件事：连接关闭后从内核列表消失，
 * 单看某一轮会严重低估，所以必须按 id 做增量累加。
 */
class ProxiedTrafficTest {

    private val node = "🇭🇰香港 01 > ♻️ 手动切换 > 🌏 国外网站"
    private val direct = "DIRECT > 🌏 国内网站"
    private val reject = "REJECT"

    // ---------------- chain 判定 ----------------

    @Test
    fun identifiesProxiedChain() {
        assertTrue(ProxiedTraffic.isProxied(node))
        assertFalse(ProxiedTraffic.isProxied(direct))
        assertFalse(ProxiedTraffic.isProxied(reject))
        assertFalse("空 chain 不算走节点", ProxiedTraffic.isProxied(""))
    }

    @Test
    fun onlyLooksAtFirstChainElement() {
        // 首段是出口。国内网站那层排在后面，不影响判定。
        assertTrue(ProxiedTraffic.isProxied("🇯🇵日本 02 > 🌏 国内网站"))
        assertFalse(ProxiedTraffic.isProxied("DIRECT > 🇭🇰香港 01"))
    }

    // ---------------- 累加 ----------------

    @Test
    fun countsNewProxiedConnectionInFull() {
        val s = ProxiedTraffic.account(State.EMPTY, listOf(Item("a", node, 100, 900)))
        assertEquals(1000, s.total)
    }

    @Test
    fun ignoresDirectAndReject() {
        val s = ProxiedTraffic.account(State.EMPTY, listOf(
            Item("a", direct, 5000, 5000),
            Item("b", reject, 5000, 5000)
        ))
        assertEquals("直连与拦截都不消耗配额", 0, s.total)
    }

    @Test
    fun addsOnlyTheDeltaOnRepeatedPolls() {
        // 同一连接连续三轮：只有新增部分计入，不能重复累加
        var s = ProxiedTraffic.account(State.EMPTY, listOf(Item("a", node, 100, 900)))
        s = ProxiedTraffic.account(s, listOf(Item("a", node, 300, 1700)))
        s = ProxiedTraffic.account(s, listOf(Item("a", node, 300, 1700)))  // 无变化
        assertEquals(2000, s.total)
    }

    @Test
    fun keepsBytesOfClosedConnections() {
        // 核心回归：连接关闭后从列表消失，但它的字节必须已经计入。
        // 「对活跃连接求和」的做法在这里会丢掉整整 5 MB。
        var s = ProxiedTraffic.account(State.EMPTY, listOf(Item("a", node, 0, 5_000_000)))
        assertEquals(5_000_000, s.total)

        s = ProxiedTraffic.account(s, emptyList())              // 采样失败/列表暂空
        assertEquals("空输入不能清掉已累计的值", 5_000_000, s.total)

        s = ProxiedTraffic.account(s, listOf(Item("b", node, 0, 1000)))   // a 已关闭
        assertEquals(5_001_000, s.total)
        assertEquals("a 应从 last 里淘汰", setOf("b"), s.last.keys)
    }

    @Test
    fun emptyInputDoesNotResetLastToAvoidDoubleCounting() {
        // 若空输入把 last 清空，下一轮同一个连接会被当成新连接、整笔再加一次
        var s = ProxiedTraffic.account(State.EMPTY, listOf(Item("a", node, 0, 1000)))
        s = ProxiedTraffic.account(s, emptyList())
        s = ProxiedTraffic.account(s, listOf(Item("a", node, 0, 1000)))
        assertEquals("不能重复计数", 1000, s.total)
    }

    @Test
    fun directBytesDoNotBleedIntoProxied() {
        var s = ProxiedTraffic.account(State.EMPTY, listOf(
            Item("a", node, 0, 1000),
            Item("b", direct, 0, 999_999)
        ))
        s = ProxiedTraffic.account(s, listOf(
            Item("a", node, 0, 1500),
            Item("b", direct, 0, 1_999_998)
        ))
        assertEquals("只算 a 的 1500，b 的直连增量不计", 1500, s.total)
    }

    @Test
    fun clampsNegativeDeltaOnCounterReset() {
        // 同 id 的计数回退（内核重启理论上会换 id，但防御一下）不能产生负数
        var s = ProxiedTraffic.account(State.EMPTY, listOf(Item("a", node, 0, 5000)))
        s = ProxiedTraffic.account(s, listOf(Item("a", node, 0, 1000)))
        assertEquals(5000, s.total)
    }

    @Test
    fun handlesManyConnectionsAcrossPolls() {
        var s = State.EMPTY
        // 三轮，每轮 10 条走节点 + 10 条直连，逐轮增长
        for (round in 1..3) {
            val list = mutableListOf<Item>()
            for (i in 1..10) list += Item("p$i", node, 0, (i * 1000L * round))
            for (i in 1..10) list += Item("d$i", direct, 0, (i * 5000L * round))
            s = ProxiedTraffic.account(s, list)
        }
        // 走节点部分：第 1 轮首次计入 sum(1000..10000)=55000；之后每轮增量同样 55000
        assertEquals(165_000, s.total)
        assertEquals(20, s.last.size)
    }

    // ---------------- 与 totalBytes 的关系 ----------------

    @Test
    fun proxiedIsSubsetOfGlobalTotal() {
        // 场景：电脑下了一个国内大文件（直连）+ 一个国外文件（走节点）
        // 全局累计两者都算，梯子只算后者 —— 这正是这个功能的意义
        var s = ProxiedTraffic.account(State.EMPTY, listOf(
            Item("cn", direct, 0, 36_000_000),
            Item("us", node, 0, 3_000_000)
        ))
        assertEquals(3_000_000, s.total)
        assertTrue("必须明显小于全局累计", s.total < 39_000_000)
    }
}
