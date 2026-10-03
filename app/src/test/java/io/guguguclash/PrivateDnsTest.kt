package io.guguguclash

import io.guguguclash.tether.PrivateDns
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专用 DNS 的键探测与恢复判定。
 *
 * 键名这条是本机实测逼出来的：一加 PJZ110 / ColorOS 16 上 `private_dns_mode` 返回 null，
 * 真正存在的是 `private_dns_default_mode` —— 只认 AOSP 键的实现（box4magisk / Surfing v7
 * 也是只认它）在这台机器上是静默空转，日志里只会留一行 WARN。
 */
class PrivateDnsTest {

    private fun st(v: String) = PrivateDns.State("private_dns_mode", v)

    @Test
    fun offNeedsNoRestore() {
        assertTrue(PrivateDns.isOff("off"))
        assertFalse(PrivateDns.shouldRestore(st("off")))
    }

    @Test
    fun missingValueNeedsNoRestore() {
        assertFalse(PrivateDns.shouldRestore(null))
        assertFalse(PrivateDns.shouldRestore(PrivateDns.State("private_dns_mode", "")))
    }

    @Test
    fun opportunisticAndHostnameAreRestored() {
        assertTrue(PrivateDns.shouldRestore(st("opportunistic")))
        assertTrue(PrivateDns.shouldRestore(st("hostname:dns.example.com")))
    }

    @Test
    fun knowsVendorKeyOnColorOs() {
        assertTrue(PrivateDns.KEYS.contains("private_dns_mode"))
        assertTrue("ColorOS 16 实际用的是这个键", PrivateDns.KEYS.contains("private_dns_default_mode"))
    }
}
