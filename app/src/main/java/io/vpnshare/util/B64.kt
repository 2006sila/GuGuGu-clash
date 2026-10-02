package io.vpnshare.util

/**
 * 纯 Kotlin Base64（标准 + URL-safe，容错空白），避免依赖 android.util.Base64，
 * 这样 JVM 单测可以直接覆盖订阅嗅探逻辑（minSdk 24 用不了 java.util.Base64）。
 */
object B64 {

    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun looksBase64(s: String): Boolean {
        val t = s.filterNot { it.isWhitespace() }
        if (t.length < 16) return false
        val alphabet = if (t.contains('-') || t.contains('_')) URL else STD
        var pad = 0
        for (c in t) {
            if (c == '=') { pad++; continue }
            if (alphabet.indexOf(c) < 0) return false
        }
        return pad <= 2
    }

    fun decode(s: String): String? {
        val t = s.filterNot { it.isWhitespace() }.trimEnd('=')
        if (t.isEmpty()) return null
        val alphabet = if (t.contains('-') || t.contains('_')) URL else STD
        var buffer = 0
        var bits = 0
        // 必须按字节累积再整体按 UTF-8 解码：把每个字节直接 toChar() 会把
        // 多字节字符（中文节点名）毁成拉丁字符乱码。
        val bytes = java.io.ByteArrayOutputStream()
        for (c in t) {
            val v = alphabet.indexOf(c)
            if (v < 0) return null
            buffer = ((buffer shl 6) or v) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                bytes.write((buffer shr bits) and 0xFF)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    fun encode(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(STD[b0 shr 2])
            if (b1 < 0) {
                out.append(STD[(b0 and 3) shl 4]).append("==")
            } else {
                out.append(STD[((b0 and 3) shl 4) or (b1 shr 4)])
                if (b2 < 0) {
                    out.append(STD[(b1 and 15) shl 2]).append('=')
                } else {
                    out.append(STD[((b1 and 15) shl 2) or (b2 shr 6)])
                    out.append(STD[b2 and 63])
                }
            }
            i += 3
        }
        return out.toString()
    }
}
