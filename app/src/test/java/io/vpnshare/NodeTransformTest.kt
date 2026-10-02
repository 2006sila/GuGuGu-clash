package io.vpnshare

import io.vpnshare.profile.NodeTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节点过滤与重命名。
 * 采纳模式下改名字必须同步改组的引用，否则内核会因为「组引用了不存在的节点」拒绝启动 ——
 * 这是本模块最主要的风险点，测试围绕它展开。
 */
class NodeTransformTest {

    private val config = """
port: 7890
proxies:
    - { name: '🇭🇰香港 01', type: ss, server: a.com, port: 1 }
    - { name: '🇭🇰香港 02', type: ss, server: a.com, port: 2 }
    - { name: '🇯🇵日本 01', type: ss, server: b.com, port: 3 }
    - { name: '🇸🇬新加坡 01', type: ss, server: c.com, port: 4 }
proxy-groups:
    - { name: '♻️ 手动切换', type: select, proxies: [DIRECT, '🇭🇰香港 01', '🇭🇰香港 02', '🇯🇵日本 01', '🇸🇬新加坡 01'] }
rules:
    - MATCH,♻️ 手动切换
""".trimIndent()

    private fun rules(inc: String = "", exc: String = "", ren: String = "") =
        NodeTransform.parseRules(inc, exc, ren)

    @Test
    fun emptyRulesChangeNothing() {
        val r = NodeTransform.applyToConfig(config, rules())
        assertEquals(config, r.text)
        assertEquals(null, r.error)
    }

    @Test
    fun includeKeepsOnlyMatching() {
        val r = NodeTransform.applyToConfig(config, rules(inc = "香港"))
        assertEquals(2, r.kept)
        assertEquals(2, r.dropped)
        assertTrue(r.text.contains("🇭🇰香港 01"))
        assertFalse(r.text.contains("🇯🇵日本 01"))
    }

    @Test
    fun excludeRemovesMatching() {
        val r = NodeTransform.applyToConfig(config, rules(exc = "日本"))
        assertEquals(3, r.kept)
        assertEquals(1, r.dropped)
        assertFalse(r.text.contains("🇯🇵日本 01"))
    }

    @Test
    fun excludeWinsOverInclude() {
        // 同时写 include=香港 与 exclude=02，只剩下 01
        val r = NodeTransform.applyToConfig(config, rules(inc = "香港", exc = "02"))
        assertEquals(1, r.kept)
        assertTrue(r.text.contains("🇭🇰香港 01"))
        assertFalse(r.text.contains("🇭🇰香港 02"))
    }

    @Test
    fun renameUpdatesProxiesBlock() {
        val r = NodeTransform.applyToConfig(config, rules(ren = "🇭🇰香港 => 港"))
        assertTrue(r.text.contains("name: '港 01'"))
        assertEquals(2, r.renamed)
    }

    @Test
    fun renameAlsoUpdatesGroupReferences() {
        // 这是关键：只改 proxies 不改组，内核会拒绝启动
        val r = NodeTransform.applyToConfig(config, rules(ren = "🇭🇰香港 => 港"))
        assertTrue("组引用必须跟着改", r.text.contains("'港 01'"))
        assertFalse("组里不能残留旧名", r.text.contains("'🇭🇰香港 01'"))
    }

    @Test
    fun droppedNodesAreRemovedFromGroupReferences() {
        val r = NodeTransform.applyToConfig(config, rules(exc = "日本"))
        val groupLine = r.text.lines().first { it.contains("手动切换") && it.contains("proxies: [") }
        assertFalse("被丢掉的节点不能留在组里", groupLine.contains("日本"))
        assertTrue(groupLine.contains("香港 01"))
        assertTrue("DIRECT 不能被误删", groupLine.contains("DIRECT"))
    }

    @Test
    fun filteringEverythingOutIsReportedNotSilentlyApplied() {
        val r = NodeTransform.applyToConfig(config, rules(inc = "不存在的地区"))
        assertNotNull("全过滤必须报错，不能交给内核", r.error)
        assertEquals(0, r.kept)
    }

    @Test
    fun duplicateAfterRenameGetsSuffix() {
        // 把两个不同节点改成同一个名字，第二个必须自动加序号
        val r = NodeTransform.applyToConfig(config, rules(ren = "🇭🇰香港 0. => HK"))
        assertTrue(r.text.contains("name: 'HK'"))
        assertTrue("重名要加序号", r.text.contains("name: 'HK #2'"))
        // 组引用也要用同样的新名，否则组会指向不存在的节点
        assertTrue(r.text.contains("'HK'") && r.text.contains("'HK #2'"))
    }

    @Test
    fun supportsMultilineEntries() {
        val multi = """
proxies:
  - name: 香港 01
    type: ss
    server: a.com
  - name: 日本 01
    type: ss
    server: b.com
""".trimIndent()
        val r = NodeTransform.applyToProxiesBlock(multi, rules(inc = "香港"))
        assertEquals(1, r.kept)
        assertTrue(r.text.contains("香港 01"))
        assertFalse(r.text.contains("日本 01"))
    }

    @Test
    fun parseRulesIgnoresBlankAndComments() {
        val r = NodeTransform.parseRules("香港\n\n# 注释\n日本", "", "a=>b\n# 跳过\n=>空")
        assertEquals(listOf("香港", "日本"), r.include)
        assertEquals(1, r.renames.size)
        assertEquals("a" to "b", r.renames[0])
    }

    // ---------------- 改名不得误伤其它字段（回归） ----------------

    @Test
    fun renameDoesNotTouchServerField() {
        // 真实翻车场景：节点名就是 server 域名（不少机场这么命名）。
        // 早期用 text.replace(name, nn) 做整条全文替换 → server 也被改掉，
        // 节点从此永远连不上，且不报错、mihomo -t 自检也能过，极难排查。
        val cfg = """
proxies:
    - { name: 'hk1.example.com', type: ss, server: hk1.example.com, port: 443 }
proxy-groups:
    - { name: '手动切换', type: select, proxies: ['hk1.example.com'] }
""".trimIndent()
        val r = NodeTransform.applyToConfig(cfg, rules(ren = "hk1.example.com => 香港 01"))
        assertTrue("name 应被改掉", r.text.contains("name: '香港 01'"))
        assertTrue("server 必须保持原样", r.text.contains("server: hk1.example.com"))
        assertTrue("组引用应同步", r.text.contains("'香港 01'"))
    }

    @Test
    fun renameDoesNotTouchPortOrPassword() {
        // 改名规则命中数字时，不能把 port / password 里的同名字串一起改掉
        val cfg = """
proxies:
    - { name: '01', type: ss, server: a.com, port: 16401, password: 'pw-01-x' }
""".trimIndent()
        val r = NodeTransform.applyToProxiesBlock(cfg, rules(ren = "01 => X"))
        assertTrue("name 应改成 X", r.text.contains("name: 'X'"))
        assertTrue("port 不能被改", r.text.contains("port: 16401"))
        assertTrue("password 不能被改", r.text.contains("password: 'pw-01-x'"))
    }

    @Test
    fun renameHandlesUnquotedNameForm() {
        // 多行块里的 name 常常不带引号
        val cfg = """
proxies:
  - name: 香港 01
    type: ss
    server: hk1.example.com
""".trimIndent()
        val r = NodeTransform.applyToProxiesBlock(cfg, rules(ren = "香港 => HK"))
        assertTrue("无引号形态也要能改名", r.text.contains("name: HK 01"))
        assertTrue("server 不受影响", r.text.contains("server: hk1.example.com"))
    }
}
