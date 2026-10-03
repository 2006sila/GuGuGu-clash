package io.guguguclash

import io.guguguclash.profile.ProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 档案 id 由订阅 URL 推导。
 *
 * 这个函数决定「重复导入同一订阅是覆盖还是堆叠」：不稳定就会每次导入都多出一份，
 * 用户的配置列表会越用越乱。它不依赖 Context，所以放在伴生对象里以便直接测试。
 */
class ProfileIdTest {

    @Test
    fun sameUrlYieldsSameId() {
        val a = ProfileStore.idFor("https://airport.example/sub?token=abc")
        val b = ProfileStore.idFor("https://airport.example/sub?token=abc")
        assertEquals("同一订阅必须得到同一个 id，否则重复导入会堆叠", a, b)
    }

    @Test
    fun differentUrlYieldsDifferentId() {
        assertNotEquals(
            ProfileStore.idFor("https://a.example/sub"),
            ProfileStore.idFor("https://b.example/sub")
        )
    }

    @Test
    fun trimsSurroundingWhitespace() {
        // 从剪贴板/扫码进来的 URL 常带首尾空白或换行
        assertEquals(
            ProfileStore.idFor("https://a.example/sub"),
            ProfileStore.idFor("  https://a.example/sub\n")
        )
    }

    @Test
    fun idIsFilesystemSafe() {
        // id 会直接当文件名用（id.yaml / id.original）
        val id = ProfileStore.idFor("https://a.example/sub?token=/../etc/passwd")
        assertTrue("id 不能含路径分隔符: " + id, !id.contains("/") && !id.contains("\\"))
        assertTrue("id 不能含点号开头的相对路径: " + id, !id.startsWith("."))
        assertTrue("id 应以 p 开头: " + id, id.startsWith("p"))
    }

    @Test
    fun handlesEmptyAndOddInput() {
        // 不该抛；空串也要给个稳定值
        assertEquals(ProfileStore.idFor(""), ProfileStore.idFor(""))
        assertTrue(ProfileStore.idFor("").startsWith("p"))
        assertTrue(ProfileStore.idFor("🀄️表情符号").startsWith("p"))
    }

    @Test
    fun idsNeverGoNegative() {
        // 哈希被掩码成非负数，否则 id 里会出现 '-' 号
        for (u in listOf("a", "b", "https://x.example/1", "🀄️", "very-long-url-" + "x".repeat(500))) {
            val id = ProfileStore.idFor(u)
            assertTrue("id 里不应出现负号: " + id, !id.contains("-"))
        }
    }
}
