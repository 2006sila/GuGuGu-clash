package io.guguguclash.profile

import android.content.Context
import io.guguguclash.prefs.Prefs
import java.io.File

/**
 * 订阅档案管理。索引用行式文本存储，节点文件按 id 落盘，避免引 JSON 库。
 * 格式： id|name|url|kind|updatedAt|userinfo
 */
class ProfileStore(private val ctx: Context) {

    companion object {
        /**
         * 用 URL 生成稳定 id，重复导入同一订阅会覆盖而不是堆叠。
         *
         * 放在伴生对象里而不是实例方法：它不依赖 Context，这样单元测试能直接调到，
         * 不必为了一个纯哈希函数去伪造 Android Context。
         */
        fun idFor(url: String): String {
            val clean = url.trim()
            var h = 1125899906842597L
            for (c in clean) h = 31 * h + c.code
            return "p" + (h and 0x7FFFFFFFFFFFFFFFL).toString(16)
        }
    }


    data class Profile(
        val id: String,
        val name: String,
        val url: String,
        val kind: SubKind,
        val updatedAt: Long,
        val userInfo: String = "",
        /**
         * 该配置自己的自动更新间隔（小时）。0 = 跟随全局设置。
         * 机场可以通过响应头 profile-update-interval 下发建议值（CMFA 也支持这个头），
         * 有值时优先用它，比全局一刀切更合理。
         */
        val intervalHours: Int = 0
    ) {
        val providerFile: String get() = id + ".yaml"
        val originalFile: String get() = id + ".original"
    }

    private val dir: File get() = File(ctx.filesDir, "profiles").apply { mkdirs() }
    private val index: File get() = File(dir, "index.txt")

    /** 是否开启了配置加密。加密只作用在节点文件上，索引保持明文以便列表可读 */
    private val enc: Boolean get() = Prefs.load(ctx).encryptProfiles

    fun all(): List<Profile> {
        if (!index.exists()) return emptyList()
        return index.readLines().mapNotNull { parse(it) }
    }

    fun current(id: String): Profile? {
        val list = all()
        if (list.isEmpty()) return null
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun providerText(p: Profile): String? {
        val f = File(dir, p.providerFile)
        if (!f.exists()) return null
        // 加密时 SecretVault.open 返回 null 表示「需要密码」，调用方据此提示解锁
        return SecretVault.open(f.readText())
    }

    fun originalText(p: Profile): String? {
        val f = File(dir, p.originalFile)
        if (!f.exists()) return null
        return SecretVault.open(f.readText())
    }

    /** 文件是密文但当前没有密码 —— 用于给用户一个准确提示，而不是「数据缺失」 */
    fun isLocked(p: Profile): Boolean {
        val f = File(dir, p.providerFile)
        return f.exists() && CryptoUtil.isEncrypted(f.readText()) && !SecretVault.isUnlocked()
    }

    fun upsert(p: Profile) {
        val list = all().filterNot { it.id == p.id }.toMutableList()
        list.add(p)
        index.writeText(list.joinToString("\n") { serialize(it) })
    }

    fun remove(id: String) {
        index.writeText(all().filterNot { it.id == id }.joinToString("\n") { serialize(it) })
        runCatching { File(dir, id + ".yaml").delete() }
        runCatching { File(dir, id + ".original").delete() }
    }

    fun writePayload(p: Profile, providerYaml: String, original: String) {
        File(dir, p.providerFile).writeText(SecretVault.seal(providerYaml, enc))
        File(dir, p.originalFile).writeText(SecretVault.seal(original, enc))
    }

    private fun serialize(p: Profile) = listOf(
        p.id, p.name.replace("|", "/"), p.url.replace("|", "/"), p.kind.name, p.updatedAt.toString(),
        p.userInfo, p.intervalHours.toString()
    ).joinToString("|")

    private fun parse(line: String): Profile? {
        val t = line.split("|")
        if (t.size < 5) return null
        val kind = runCatching { SubKind.valueOf(t[3]) }.getOrDefault(SubKind.UNKNOWN)
        // 第 7 个字段是后加的，老索引没有它 —— getOrElse 兜底，保证向后兼容
        return Profile(
            t[0], t[1], t[2], kind, t[4].toLongOrNull() ?: 0L, t.getOrElse(5) { "" },
            t.getOrElse(6) { "0" }.toIntOrNull() ?: 0
        )
    }
}
