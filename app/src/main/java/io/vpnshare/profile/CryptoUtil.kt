package io.vpnshare.profile

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置加密。CMFA 用的是 Age（X25519 + ChaCha20-Poly1305），
 * 那套算法 Java 侧没有官方实现，引第三方库体积大且未经审计；
 * 这里用平台自带的 PBKDF2-HMAC-SHA256 + AES-256-GCM 达到同等目的：
 * 「订阅文件落盘是密文，没有密码解不开」。
 *
 * 密文格式（Base64）:  VSE1 | salt(16) | iv(12) | ciphertext+tag
 * 头部之所以要 mark，是为了能一眼区分密文与明文，避免把老数据当成密文去解。
 */
object CryptoUtil {

    private const val MAGIC = "VSE1"
    private const val ITER = 120_000
    private const val KEY_BITS = 256
    private const val SALT = 16
    private const val IV = 12
    private const val TAG_BITS = 128

    // 自带 Base64：android.util.Base64 是框架类，JVM 单元测试里跑不了；
    // 标准库的 java.util.Base64 又要 API 26，而 minSdk 是 24。自己实现最省事。
    private const val B64C = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun b64Encode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(B64C[b0 shr 2])
            sb.append(B64C[((b0 and 0x03) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(B64C[((b1 and 0x0f) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)]) else sb.append('=')
            if (b2 >= 0) sb.append(B64C[b2 and 0x3f]) else sb.append('=')
            i += 3
        }
        return sb.toString()
    }

    private fun b64Decode(s: String): ByteArray {
        val clean = s.filter { it != '\n' && it != '\r' && it != ' ' }
        val out = java.io.ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val v = B64C.indexOf(ch)
            if (v < 0) continue
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xff)
            }
        }
        return out.toByteArray()
    }

    fun isEncrypted(text: String): Boolean = text.trimStart().startsWith(MAGIC + ":")

    private fun derive(password: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITER, KEY_BITS)
        return SecretKeySpec(f.generateSecret(spec).encoded, "AES")
    }

    fun encrypt(plain: String, password: String): String {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val all = ByteArray(salt.size + iv.size + ct.size)
        System.arraycopy(salt, 0, all, 0, salt.size)
        System.arraycopy(iv, 0, all, salt.size, iv.size)
        System.arraycopy(ct, 0, all, salt.size + iv.size, ct.size)
        return MAGIC + ":" + b64Encode(all)
    }

    /** 密码错会抛 AEADBadTagException —— 调用方必须区分「密码错」与「数据坏」 */
    fun decrypt(text: String, password: String): String {
        val body = text.trimStart().removePrefix(MAGIC + ":")
        val all = b64Decode(body)
        require(all.size > SALT + IV) { "密文长度不足" }
        val salt = all.copyOfRange(0, SALT)
        val iv = all.copyOfRange(SALT, SALT + IV)
        val ct = all.copyOfRange(SALT + IV, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}
