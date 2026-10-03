package io.guguguclash.core

import android.content.Context
import android.os.Build
import io.guguguclash.root.RootShell
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 内核与内置数据的安装。
 *
 * 两级来源：
 *   1) APK assets（构建期已打好，离线可用）
 *   2) 应用私有目录 files/prebuilt（App 运行时下载，或用户从 PC 导入）
 *
 * 安装路径固定为 /data/adb/guguguclash。App 无法直写该目录，也没有把 10MB 二进制
 * 经 stdin 灌进 su 会话，而是先落到私有目录再由 su 复制。
 */
object CoreInstaller {

    const val BASE = "/data/adb/guguguclash"
    const val BIN = BASE + "/bin"
    const val CONF = BASE + "/conf"
    const val RUN = BASE + "/run"
    const val CORE = BIN + "/mihomo"
    const val CONFIG = CONF + "/config.yaml"
    const val PROVIDERS = CONF + "/providers"
    const val RULESET = CONF + "/ruleset"

    val SUPPORTED = listOf("arm64-v8a", "armeabi-v7a")

    /** 运行目录的文件属组：shell(2000)。**不是**内核运行身份，内核以 root 跑 */
    private val GID = CoreManager.FILE_GROUP_GID

    data class Report(
        val ok: Boolean,
        val abi: String = "",
        val steps: List<String> = emptyList(),
        val error: String? = null,
        val coreOutput: String = ""
    )

    fun pickAbi(): String? {
        val supported = Build.SUPPORTED_ABIS.toList()
        return SUPPORTED.firstOrNull { supported.contains(it) }
            ?: SUPPORTED.firstOrNull { a -> supported.any { it.startsWith(a.substringBefore("-")) } }
    }

    fun installed(): Boolean = RootShell.run("[ -x " + CORE + " ] && echo yes").out.contains("yes")

    /**
     * 修权限。必须【每次启动】都跑，不能只在安装时跑：
     * 运行目录的属组与 /data/adb 的可穿越位会因重装、Magisk 更新等原因被重置，
     * 而安装逻辑在内核已存在时会整体跳过，导致改完权限也发不生效。
     */
    fun ensurePermissions(): RootShell.Result = RootShell.run(
        "chmod a+x /data/adb\n" +
        "chgrp -R " + GID + " '" + BASE + "' 2>/dev/null\n" +
        "chmod -R u+rwX,g+rX,o-rwx '" + BASE + "' 2>/dev/null\n" +
        "chmod 755 '" + CORE + "' 2>/dev/null\n" +
        "echo PERM_OK"
    )

    fun install(ctx: Context, onStep: (String) -> Unit): Report {
        val steps = mutableListOf<String>()
        val abi = pickAbi() ?: return Report(false, error = "设备 ABI 不受支持: " + Build.SUPPORTED_ABIS.joinToString(","))

        val staging = File(ctx.filesDir, "stage")
        try {
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()

            onStep("准备内核")
            val coreFile = stageCore(ctx, abi, staging)
                ?: return Report(
                    false, abi, steps,
                    error = "内核未就绪。请执行其一：\n" +
                        "1) 主界面点「内核/数据」→ 在线下载\n" +
                        "2) PC 下载 mihomo-android-" + abi + "-*.gz 后点「从文件导入内核」\n" +
                        "3) 构建时用 scripts/fetch-core.ps1 打进 assets"
                )
            steps += "内核 " + (coreFile.length() / 1048576) + " MB（" + coreFile.parentFile?.name + "）"

            val geoCount = stageDir(ctx, "geo", CoreDownloader.GEO_DIR, File(staging, "geo"), steps, "geo 数据")
            val ruleCount = stageDir(ctx, "ruleset", CoreDownloader.RULESET_DIR, File(staging, "ruleset"), steps, "本地规则集")

            val template = File(staging, "base.template.yaml")
            ctx.assets.open("base.template.yaml").use { i -> template.outputStream().use { i.copyTo(it) } }

            onStep("复制到 " + BASE)
            val script = StringBuilder()
            script.append("set -e\n")
            script.append("mkdir -p '").append(BIN).append("' '").append(CONF).append("' '").append(RUN)
            script.append("' '").append(PROVIDERS).append("' '").append(RULESET).append("'\n")
            script.append("cp -f '").append(coreFile.absolutePath).append("' '").append(CORE).append("'\n")
            script.append("chmod 755 '").append(CORE).append("'\n")
            // 内核以 root 运行（复测结论见 CoreManager.FILE_GROUP_GID）。这里设权限是为了：
            //   a+x  : 保留 /data/adb 的可穿越位（部分 ROM / 工具链仍依赖它）
            //   chgrp: 运行目录归 shell 组（历史上为降权运行准备；实测 adb shell 仍然读不到
            //          /data/adb/guguguclash —— /data/adb 本身是 0700 root，a+x 只给了穿越权，
            //          排障要读运行目录得用 root，别指望 shell）
            //   u+rwX go-rwx: 只有 root 与 shell 组能读写，其他应用进不去，订阅凭据不外泄
            script.append("chmod a+x /data/adb\n")
            // 运行目录归 shell 组；其他应用（o）仍被拒，订阅凭据不外泄
            script.append("chgrp -R ").append(GID).append(" '" + BASE + "' 2>/dev/null\n")
            script.append("chmod -R u+rwX,g+rX,o-rwx '" + BASE + "' 2>/dev/null\n")
            script.append("chcon u:object_r:magisk_file:s0 '").append(CORE).append("' 2>/dev/null || true\n")
            if (geoCount > 0) {
                script.append("cp -f '").append(staging.absolutePath).append("/geo/'* '").append(CONF).append("/' 2>/dev/null || true\n")
            }
            if (ruleCount > 0) {
                script.append("cp -f '").append(staging.absolutePath).append("/ruleset/'* '").append(RULESET).append("/' 2>/dev/null || true\n")
            }
            script.append("cp -f '").append(template.absolutePath).append("' '").append(CONF).append("/template.base.yaml'\n")
            script.append("echo '--- core ---'\n")
            script.append("'").append(CORE).append("' -v 2>&1 | head -n 3\n")
            script.append("echo '--- conf ---'\n")
            script.append("ls '").append(CONF).append("'\n")

            val r = RootShell.run(script.toString(), timeoutSec = 120)
            if (!r.ok) return Report(false, abi, steps, "复制到 " + BASE + " 失败：" + r.combined)

            val versionSeen = r.out.contains("Mihomo") || Regex("v1\\.[0-9]").containsMatchIn(r.out)
            steps += "内核自检 " + (if (versionSeen) "OK" else "未见版本号（可能仍可用）")
            steps += "geo 文件 " + geoCount + " 个 / 规则集 " + ruleCount + " 个"
            return Report(true, abi, steps, null, r.out.trim())
        } catch (e: Exception) {
            return Report(false, abi, steps, e.message ?: e.toString())
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    /**
     * 优先 assets，其次私有目录下载件。
     *
     * 注意：aapt 对 assets 里的 .gz 有特殊行为 —— 打包时自动解压并去掉扩展名。
     * 实测 arm64 debug APK 里是 assets/mihomo/mihomo（61MB 原始 ELF，deflate 后 22.99MB），
     * 而不是 mihomo.gz。所以未压缩形态和 .gz 形态都必须兼容。
     */
    private fun stageCore(ctx: Context, abi: String, staging: File): File? {
        val out = File(staging, "mihomo")
        val rawPaths = listOf("mihomo/" + abi + "/mihomo", "mihomo/mihomo")
        for (p in rawPaths) {
            val ok = runCatching { ctx.assets.open(p).use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (ok.isSuccess && out.length() > 1_000_000) return out
        }
        val gzPaths = listOf("mihomo/" + abi + "/mihomo.gz", "mihomo/mihomo.gz")
        for (p in gzPaths) {
            val ok = runCatching { ctx.assets.open(p).use { i -> GZIPInputStream(i).use { gz -> out.outputStream().use { gz.copyTo(it) } } } }
            if (ok.isSuccess && out.length() > 1_000_000) return out
        }
        val downloaded = File(ctx.filesDir, CoreDownloader.CORE_GZ)
        if (downloaded.exists() && downloaded.length() > 1_000_000) {
            return runCatching {
                GZIPInputStream(downloaded.inputStream()).use { gz -> out.outputStream().use { gz.copyTo(it) } }
                if (out.length() > 1_000_000) out else null
            }.getOrNull()
        }
        return null
    }

    private fun stageDir(
        ctx: Context,
        assetDir: String,
        prebuiltDir: String,
        dest: File,
        steps: MutableList<String>,
        label: String
    ): Int {
        dest.mkdirs()
        var n = 0
        var bytes = 0L
        for (name in runCatching { ctx.assets.list(assetDir) ?: emptyArray() }.getOrDefault(emptyArray())) {
            runCatching {
                val f = File(dest, name)
                ctx.assets.open(assetDir + "/" + name).use { i -> f.outputStream().use { i.copyTo(it) } }
                bytes += f.length()
                n++
            }
        }
        val pb = File(ctx.filesDir, prebuiltDir)
        for (f in pb.listFiles().orEmpty()) {
            if (!f.isFile) continue
            if (File(dest, f.name).exists()) continue
            runCatching {
                f.copyTo(File(dest, f.name), overwrite = true)
                bytes += f.length()
                n++
            }
        }
        steps += label + ": " + n + " 个 / " + (bytes / 1048576) + " MB"
        return n
    }
}
