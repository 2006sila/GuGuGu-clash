package io.guguguclash

import io.guguguclash.profile.CryptoUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoUtilTest {

    @Test
    fun roundTrip() {
        val plain = "proxies:\n  - name: 香港01\n    type: ss"
        val enc = CryptoUtil.encrypt(plain, "pw123")
        assertTrue(CryptoUtil.isEncrypted(enc))
        assertNotEquals(plain, enc)
        assertEquals(plain, CryptoUtil.decrypt(enc, "pw123"))
    }

    @Test
    fun differentIvEachTime() {
        val a = CryptoUtil.encrypt("same", "pw")
        val b = CryptoUtil.encrypt("same", "pw")
        assertNotEquals(a, b)   // 随机 salt/iv：相同明文也必须密文不同
    }

    @Test(expected = Exception::class)
    fun wrongPasswordFails() {
        CryptoUtil.decrypt(CryptoUtil.encrypt("secret", "right"), "wrong")
    }

    @Test
    fun plainTextIsNotMistakenForCipher() {
        assertFalse(CryptoUtil.isEncrypted("proxies:\n  - ss://xxx"))
        assertFalse(CryptoUtil.isEncrypted(""))
        assertTrue(CryptoUtil.isEncrypted(CryptoUtil.encrypt("x", "p")))
    }
}
