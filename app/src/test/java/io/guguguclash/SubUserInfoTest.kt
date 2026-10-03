package io.guguguclash

import io.guguguclash.profile.parseUserInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 订阅流量头的解析。机场格式不完全统一，这里把边界都钉住。 */
class SubUserInfoTest {

    @Test
    fun parsesTypicalHeader() {
        // 真实抓到的样例
        // 注意 expire 是 10 位秒级时间戳。之前抓包时用了 head -c 100 被截成 9 位，
        // 算出 1975 年 —— 这个坑正是这条用例要钉住的。
        val u = parseUserInfo("upload=1109571285; download=25114233259; total=107374182400; expire=1792887640")!!
        assertEquals(1109571285L, u.upload)
        assertEquals(25114233259L, u.download)
        assertEquals(107374182400L, u.total)
        assertEquals(1109571285L + 25114233259L, u.used)
        assertEquals(107374182400L - u.used, u.remaining)
        assertTrue(u.hasQuota)
        assertEquals(24, u.percent)   // (1.03+23.4)/100 GB ≈ 24%
        // expire 是秒，秒级时间戳应被识别
        assertTrue(u.expireDate()!!.startsWith("2026-"))
        assertFalse(u.isExpired())
    }

    @Test
    fun toleratesSpacingAndMissingFields() {
        val u = parseUserInfo("total=1000")!!
        assertEquals(0L, u.upload)
        assertEquals(0L, u.download)
        assertEquals(1000L, u.total)
        assertTrue(u.hasQuota)
        assertEquals(0, u.percent)
        assertNull(u.expireDate())
    }

    @Test
    fun noQuotaFallsBackToUsed() {
        val u = parseUserInfo("upload=100; download=200")!!
        assertFalse(u.hasQuota)
        assertEquals(300L, u.used)
        assertEquals(0, u.percent)
    }

    @Test
    fun blankOrGarbageReturnsNull() {
        assertNull(parseUserInfo(""))
        assertNull(parseUserInfo("   "))
        assertNull(parseUserInfo("hello world"))
        assertNull(parseUserInfo("upload=abc; download=xyz"))
    }

    @Test
    fun acceptsMillisecondExpire() {
        // 有的机场给毫秒，量级判断要能区分
        val sec = parseUserInfo("total=1; expire=1792887640")!!
        val ms = parseUserInfo("total=1; expire=1792887640000")!!
        assertEquals(sec.expireDate(), ms.expireDate())
    }

    @Test
    fun overQuotaNeverNegative() {
        val u = parseUserInfo("upload=900; download=900; total=1000")!!
        assertEquals(0L, u.remaining)
        assertEquals(100, u.percent)   // 超额时封顶 100%，不会出现 180%
    }

    @Test
    fun expiredIsDetected() {
        val u = parseUserInfo("total=1; expire=1000000000")!!   // 2001 年
        assertTrue(u.isExpired())
    }
}
