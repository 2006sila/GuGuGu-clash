package io.vpnshare

import io.vpnshare.profile.SubFormat
import io.vpnshare.profile.SubKind
import io.vpnshare.util.B64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubFormatTest {

    private val clashYaml = """
        port: 7890
        proxies:
          - { name: a, type: ss, server: 1.2.3.4, port: 443, cipher: aes-128-gcm, password: x }
          - { name: b, type: trojan, server: 5.6.7.8, port: 443, password: y }
        proxy-groups:
          - { name: PROXY, type: select, proxies: [a, b] }
        rules:
          - MATCH,PROXY
    """.trimIndent()

    private val links = listOf(
        "ss://YWVzLTEyOC1nY206cGFzcw@1.2.3.4:443#node-a",
        "vmess://eyJ2IjoiMiIsInBzIjoibm9kZS1iIn0=",
        "trojan://pass@5.6.7.8:443#node-c"
    )

    @Test
    fun detectsClashYaml() {
        assertEquals(SubKind.CLASH_YAML, SubFormat.detect(clashYaml).kind)
    }

    @Test
    fun detectsPlainShareLinks() {
        val d = SubFormat.detect(links.joinToString("\n"))
        assertEquals(SubKind.SHARE_LINKS, d.kind)
        assertEquals(3, d.proxies.size)
    }

    @Test
    fun detectsBase64ShareLinks() {
        val d = SubFormat.detect(B64.encode(links.joinToString("\n")))
        assertEquals(SubKind.SHARE_LINKS, d.kind)
        assertEquals(3, d.proxies.size)
    }

    @Test
    fun detectsBase64ShareLinksWithBlankLines() {
        val payload = "\n\n" + links.joinToString("\n\n") + "\n\n"
        val d = SubFormat.detect(B64.encode(payload))
        assertEquals(SubKind.SHARE_LINKS, d.kind)
        assertEquals(3, d.proxies.size)
    }

    @Test
    fun rejectsGarbage() {
        val d = SubFormat.detect("这不是订阅，只是一段中文文本。" + "x".repeat(50))
        assertEquals(SubKind.UNKNOWN, d.kind)
    }

    @Test
    fun rejectsEmpty() {
        assertEquals(SubKind.UNKNOWN, SubFormat.detect("   ").kind)
    }

    @Test
    fun doesNotMistakeYamlForBase64() {
        assertTrue(!B64.looksBase64(clashYaml.take(64)))
    }

    @Test
    fun linksToProviderFileIsStandardV2RayBase64() {
        val out = SubFormat.linksToProviderFile(links)
        // 必须是 base64 而不是 YAML：写成 YAML 列表会让内核两侧解析都失败
        assertTrue("不应是 YAML 包装", !out.startsWith("proxies:"))
        assertTrue("应为 base64", B64.looksBase64(out))
        val decoded = B64.decode(out)!!
        assertTrue(decoded.startsWith("ss://"))
        assertTrue(decoded.contains("vmess://"))
        assertEquals(3, decoded.lines().count { it.contains("://") })
        // 计数函数要能识别这个形态
        assertEquals(3, SubFormat.countNodes(out))
    }

    @Test
    fun countNodesHandlesClashYamlProvider() {
        val providerYaml = "proxies:\n" +
            "  - { name: a, type: ss, server: 1.2.3.4, port: 443, cipher: aes-128-gcm, password: x }\n" +
            "  - { name: b, type: trojan, server: 5.6.7.8, port: 443, password: y }\n"
        assertEquals(2, SubFormat.countNodes(providerYaml))
        assertEquals(0, SubFormat.countNodes(""))
    }

    @Test
    fun base64RoundTripAndUrlSafe() {
        val s = "ss://a@1.2.3.4:443#中文节点"
        assertEquals(s, B64.decode(B64.encode(s)))
        val urlSafe = B64.encode(s).replace('+', '-').replace('/', '_')
        assertEquals(s, B64.decode(urlSafe))
    }

    @Test
    fun fullConfigCountsProxiesNotProviderUrls() {
        // 完整机场配置：proxies 块在中间，末尾还有一堆带 URL 的 rule-providers。
        // 曾经的实现会数到那些 URL，把 50 个节点显示成「2 节点」。
        val cfg = """
port: 7890
mixed-port: 7893
proxies:
    - { name: 'a', type: ss, server: x.com, port: 1 }
    - { name: 'b', type: ss, server: x.com, port: 2 }
    - { name: 'c', type: vmess, server: y.com, port: 3 }
rule-providers:
    r1:
        url: https://example.com/a.yaml
    r2:
        url: https://example.com/b.yaml
rules:
    - MATCH,PROXY
""".trimIndent()
        assertEquals(3, SubFormat.countNodes(cfg))
    }

    @Test
    fun providerFileStillCounts() {
        val p = "proxies:\n  - { name: 'a' }\n  - { name: 'b' }\n"
        assertEquals(2, SubFormat.countNodes(p))
    }

    @Test
    fun shareLinksStillCount() {
        val links = "ss://aaa\nvmess://bbb\ntrojan://ccc"
        // 裸链接（非 base64）也要能数
        assertEquals(3, SubFormat.countNodes(links))
    }
}
