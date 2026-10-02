package io.vpnshare.profile

/**
 * 配置密码的内存保管。只活在进程里，不写 SharedPreferences、不进日志。
 * 进程被杀后需要重新输入 —— 这是加密的应有代价。
 */
object SecretVault {

    @Volatile
    private var password: String? = null

    fun unlock(pw: String) { password = pw }
    fun lock() { password = null }
    fun isUnlocked(): Boolean = !password.isNullOrEmpty()
    fun get(): String? = password

    /** 把明文按需加密；未解锁或未启用加密时原样返回 */
    fun seal(plain: String, encryptEnabled: Boolean): String {
        val pw = password
        if (!encryptEnabled || pw.isNullOrEmpty()) return plain
        return CryptoUtil.encrypt(plain, pw)
    }

    /** 解封；返回 null 表示「需要密码/密码错」 */
    fun open(text: String, encryptEnabled: Boolean): String? {
        if (!CryptoUtil.isEncrypted(text)) return text
        val pw = password ?: return null
        return runCatching { CryptoUtil.decrypt(text, pw) }.getOrNull()
    }
}
