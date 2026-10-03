package io.guguguclash

import io.guguguclash.core.CoreApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出口探测的层级判定。
 *
 * 这层逻辑曾经整体失效过：probeEgress 把组名写死成 PROXY，而机场的组叫
 * 「♻️ 手动切换」，两个请求全 404，于是所谓「三级降级」一直退化成第三级的
 * 单发直连 —— 节点已死、同组另外 34 个节点全好时仍判定整条链路不通并回滚规则。
 *
 * 所以这里用真机抓到的响应原文逐一钉死判定规则。
 */
class ProbeTiersTest {

    // ---------------- 第一级：当前节点 ----------------

    @Test
    fun parsesNodeDelayFromRealResponse() {
        assertEquals(135, CoreApi.nodeDelayOf("""{"delay":135}"""))
    }

    @Test
    fun nodeDelayRejectsNonPositive() {
        assertEquals(-1, CoreApi.nodeDelayOf("""{"delay":0}"""))
        assertEquals(-1, CoreApi.nodeDelayOf("""{"delay":-1}"""))
    }

    @Test
    fun nodeDelayRejectsErrorObject() {
        // 真机实测：内核测不动这个节点时返回的就是这个
        assertEquals(-1, CoreApi.nodeDelayOf("""{"message":"An error occurred in the delay test"}"""))
    }

    @Test
    fun nodeDelayRejectsGarbageAndNull() {
        assertEquals(-1, CoreApi.nodeDelayOf(null))
        assertEquals(-1, CoreApi.nodeDelayOf(""))
        assertEquals(-1, CoreApi.nodeDelayOf("   "))
        assertEquals(-1, CoreApi.nodeDelayOf("not json at all"))
        assertEquals(-1, CoreApi.nodeDelayOf("""{}"""))
    }

    // ---------------- 第二级：整组 ----------------

    @Test
    fun groupProbeCountsAliveNodesFromRealResponse() {
        // 真机抓到的整组测速原文（截取前几条）
        val raw = """{"DIRECT":64,"🇦🇺澳大利亚":435,"🇩🇪德国":264,"🇫🇷法国":259,"🇭🇰香港 02":135,"🇭🇰香港 03":131}"""
        val gp = CoreApi.groupProbeOf(raw)
        assertEquals(6, gp.total)
        assertEquals(6, gp.available)
    }

    @Test
    fun groupProbeIgnoresNonPositive() {
        val gp = CoreApi.groupProbeOf("""{"a":100,"b":0,"c":-1,"d":50}""")
        assertEquals(4, gp.total)
        assertEquals(2, gp.available)
    }

    @Test
    fun groupProbeOnAllDeadReportsZeroAvailable() {
        // 这一条是「整组 N 个节点全部不通」日志的触发条件
        val gp = CoreApi.groupProbeOf("""{"a":0,"b":0,"c":-1}""")
        assertEquals(3, gp.total)
        assertEquals(0, gp.available)
        assertTrue("全部不通时 available 必须是 0", gp.available == 0)
    }

    @Test
    fun groupProbeOnErrorObjectYieldsNoAlive() {
        // 关键回归：组名写错时内核返回错误对象，此时必须判「无可用节点」，
        // 而不是把 "message" 这个键当成一个节点
        val gp = CoreApi.groupProbeOf("""{"message":"An error occurred in the delay test"}""")
        assertEquals(1, gp.total)
        assertEquals(0, gp.available)
    }

    @Test
    fun groupProbeHandlesNullAndEmpty() {
        assertEquals(0, CoreApi.groupProbeOf(null).available)
        assertEquals(0, CoreApi.groupProbeOf(null).total)
        assertEquals(0, CoreApi.groupProbeOf("").available)
        assertEquals(0, CoreApi.groupProbeOf("""{}""").available)
        assertEquals(0, CoreApi.groupProbeOf("nonsense").available)
    }

    @Test
    fun oneAliveNodeIsEnoughToKeepLinkUsable() {
        // 第二级存在的全部意义：别让一个坏节点把整条链路判死。
        // 场景 = 出事那天的原样：当前节点死，但同组还有一堆好节点。
        val gp = CoreApi.groupProbeOf("""{"🇭🇰香港 直连 0.1x":0,"🇯🇵日本 02":105,"🇸🇬新加坡 01":107}""")
        assertEquals(3, gp.total)
        assertEquals(2, gp.available)
        assertTrue("只要有 1 个活节点，探测就该放行", gp.available > 0)
    }
}
