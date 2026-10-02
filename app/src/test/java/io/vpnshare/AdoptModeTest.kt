package io.vpnshare

import io.vpnshare.profile.ConfigBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采纳模式（以订阅原文为底打补丁）的回归测试。
 *
 * 这里每一条都对应一个真实踩过的坑，不是凑数的：
 *  · 块键被当成标量删 → 缩进子行残留成顶层孤儿 → YAML 解析失败 → 起不来
 *  · secret 被删掉却不写回 → 用户设的外部控制密码被静默忽略
 */
class AdoptModeTest {

    /** 一份尽量贴近真实机场形态的订阅：带块结构、带 secret */
    private val sub = """
port: 7890
socks-port: 7891
mode: rule
log-level: info
secret: 'from-airport'
external-controller: '127.0.0.1:9990'
external-controller-cors:
    allow-origins: ['https://evil.example']
    allow-private-network: false
listeners:
    - name: tproxy-in
      type: tproxy
      port: 7895
proxies:
    - { name: '🇭🇰香港 01', type: ss, server: hk1.example.com, port: 443 }
proxy-groups:
    - { name: '♻️ 手动切换', type: select, proxies: ['🇭🇰香港 01'] }
rules:
    - MATCH,♻️ 手动切换
""".trimIndent()

    private fun adopt(secret: String = "") =
        ConfigBuilder.buildAdopted(sub, ConfigBuilder.Options(
            over = ConfigBuilder.OverrideOptions(secret = secret)
        ))

    // ---------------- listeners 块 ----------------

    @Test
    fun listenersBlockIsFullyRemoved() {
        // 曾经的 bug：listeners 在标量删除列表里，只删掉了「块头」那一行，
        // 缩进的子行全部残留成顶层孤儿，YAML 直接解析失败。
        val out = adopt()
        assertFalse("listeners 的子行不能残留", out.contains("tproxy-in"))
        assertFalse("listeners 的 type 行不能残留", out.contains("type: tproxy"))
        assertFalse("listeners 的 port 行不能残留", out.contains("7895"))
        assertFalse("块头本身也应消失", Regex("(?m)^listeners:").containsMatchIn(out))
    }

    // ---------------- external-controller-cors 块 ----------------

    @Test
    fun corsBlockIsReplacedExactlyOnce() {
        // 同一个根因的另一半：cors 也是块结构。
        // 早期实现先删块头（留孤儿），replaceBlock 又找不到块头 → 把新块追加到末尾，
        // 结果配置里 cors 出现两遍。
        val out = adopt()
        assertEquals("cors 块只能出现一次", 1, out.split("external-controller-cors:").size - 1)
        assertFalse("订阅自带的 cors 内容必须被替换掉", out.contains("evil.example"))
        assertTrue("我们自己的 cors 必须写进去", out.contains("allow-origins: [\"*\"]"))
    }

    /**
     * 找「孤儿缩进行」：往上回溯到第一个缩进更小的行，那一行必须是块头（以 : 结尾）
     * 或列表项（以 - 开头）；否则本行就是失去父级的残留 —— 这正是块头被单独删掉时的症状。
     *
     * 注意不能只看「前一行」：块内第二行往后的前一行本来就不以 : 结尾
     * （例如 allow-origins 后面跟 allow-private-network），那样会误报。
     */
    private fun findOrphans(text: String): List<String> {
        val lines = text.lines()
        val indentOf = { s: String -> s.takeWhile { it == ' ' }.length }
        val bad = mutableListOf<String>()
        for (i in lines.indices) {
            val l = lines[i]
            if (l.isEmpty() || !l.startsWith(" ") || l.trimStart().startsWith("#")) continue
            val ind = indentOf(l)
            var j = i - 1
            while (j >= 0 && (lines[j].isEmpty() || indentOf(lines[j]) >= ind)) j--
            val parent = if (j >= 0) lines[j].trimEnd() else null
            val parentOk = parent != null &&
                (parent.endsWith(":") || parent.trimStart().startsWith("-"))
            if (!parentOk) bad += l
        }
        return bad
    }

    @Test
    fun noOrphanIndentedLines() {
        val orphans = findOrphans(adopt())
        assertTrue("不应有失去父级的孤儿缩进行，实际: " + orphans, orphans.isEmpty())
    }

    @Test
    fun orphanDetectorActuallyCatchesTheOriginalSymptom() {
        // 反向验证，证明 findOrphans 不是「永远返回空」的假测试。
        //
        // 注意构造：必须让被删块头的前面是一行**普通标量**。如果前面恰好是另一个块头
        // （比如 external-controller-cors:），子行会被那个块「吸收」—— 同样是配置被污染，
        // 但形式上不是孤儿，检测器抓不到（那种情况由 listenersBlockIsFullyRemoved 覆盖）。
        val broken = "mode: rule\n" +
            "  - name: tproxy-in\n" +
            "    type: tproxy\n" +
            "proxies:\n" +
            "    - { name: 'a', type: ss }\n"
        assertTrue("检测器必须能识别孤儿行", findOrphans(broken).isNotEmpty())
    }

    // ---------------- secret ----------------

    @Test
    fun secretIsWrittenBackWhenUserSetsOne() {
        // 曾经的 bug：secret 在删除列表里，但 scalarMap 没有它 → 用户设的密码被静默忽略
        val out = adopt(secret = "my-strong-pass")
        assertTrue("用户设的 secret 必须写进配置", out.contains("secret: my-strong-pass"))
        assertFalse("订阅自带的 secret 必须被覆盖", out.contains("from-airport"))
        assertEquals("secret 只能出现一次", 1, out.split(Regex("(?m)^secret:")).size - 1)
    }

    @Test
    fun subscriptionSecretIsDroppedWhenUserLeavesItBlank() {
        val out = adopt()
        assertFalse("订阅自带的 secret 不应保留", out.contains("from-airport"))
        assertFalse("没设密码时不应写入 secret", Regex("(?m)^secret:").containsMatchIn(out))
    }

    // ---------------- 受控标量仍然生效 ----------------

    @Test
    fun controlledScalarsStillWin() {
        val out = ConfigBuilder.buildAdopted(sub, ConfigBuilder.Options(
            over = ConfigBuilder.OverrideOptions(mixedPort = 7890, redirPort = 7892, tproxyPort = 7893)
        ))
        // 订阅里的 port/socks-port 应被我们接管（默认 0 = 关闭）
        assertTrue(Regex("(?m)^port: 0$").containsMatchIn(out))
        assertTrue(Regex("(?m)^socks-port: 0$").containsMatchIn(out))
        assertTrue(Regex("(?m)^mixed-port: 7890$").containsMatchIn(out))
        assertEquals("external-controller 只能出现一次", 1, out.split(Regex("(?m)^external-controller:")).size - 1)
    }

    @Test
    fun subscriptionGroupsAndRulesSurvive() {
        // 采纳模式的全部意义：机场自带的策略组与规则必须原样保留
        val out = adopt()
        assertTrue(out.contains("♻️ 手动切换"))
        assertTrue(out.contains("hk1.example.com"))
        assertTrue(out.contains("MATCH,♻️ 手动切换"))
    }
}
