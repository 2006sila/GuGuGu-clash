package io.vpnshare

import io.vpnshare.util.DeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 深链解析。深链来自任意网页，宁可多拦，不可错放。 */
class DeepLinkTest {

    private fun url(r: DeepLink.Result): String? = (r as? DeepLink.Result.Import)?.url

    @Test
    fun parsesNormalLink() {
        val r = DeepLink.parse("vpnshare://import?url=https%3A%2F%2Fexample.com%2Fsub%3Ftoken%3Dabc")
        assertEquals("https://example.com/sub?token=abc", url(r))
    }

    @Test
    fun parsesUnencodedLink() {
        assertEquals("https://a.com/b", url(DeepLink.parse("vpnshare://import?url=https://a.com/b")))
    }

    @Test
    fun toleratesExtraParamsAndCase() {
        assertEquals("https://a.com/x", url(DeepLink.parse("VPNShare://IMPORT?name=t&URL=https://a.com/x&z=1")))
    }

    @Test
    fun rejectsWrongSchemeOrHost() {
        assertTrue(DeepLink.parse("https://import?url=https://a.com") is DeepLink.Result.Bad)
        assertTrue(DeepLink.parse("vpnshare://other?url=https://a.com") is DeepLink.Result.Bad)
        assertTrue(DeepLink.parse("vpnshare://import") is DeepLink.Result.Bad)
    }

    @Test
    fun rejectsNonHttpTarget() {
        // 不能让它当本地文件读取或自定义 scheme 跳板
        assertTrue(DeepLink.parse("vpnshare://import?url=file%3A%2F%2F%2Fetc%2Fpasswd") is DeepLink.Result.Bad)
        assertTrue(DeepLink.parse("vpnshare://import?url=ss%3A%2F%2Fabc") is DeepLink.Result.Bad)
    }

    @Test
    fun rejectsEmptyAndGarbage() {
        assertTrue(DeepLink.parse(null) is DeepLink.Result.Bad)
        assertTrue(DeepLink.parse("") is DeepLink.Result.Bad)
        assertTrue(DeepLink.parse("vpnshare://import?url=") is DeepLink.Result.Bad)
        assertTrue(DeepLink.parse("随便一串字") is DeepLink.Result.Bad)
    }

    @Test
    fun extractsHost() {
        assertEquals("example.com", DeepLink.hostOf("https://example.com/sub?token=1"))
    }
}
