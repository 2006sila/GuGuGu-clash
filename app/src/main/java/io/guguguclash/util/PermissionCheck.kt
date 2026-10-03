package io.guguguclash.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.guguguclash.R
import io.guguguclash.root.RootShell

/**
 * 权限自检。权限检查页与属性页共用同一份判定，避免两处逻辑漂移。
 *
 * 这些权限有个共性：缺了不会报错，而是**悄悄不干活** ——
 * 通知权限没给，通知栏就是空的；应用列表权限没给，分流页只剩自己一个 App；
 * 电池优化没进白名单，后台服务会被回收，表现为共享莫名其妙断开。
 */
object PermissionCheck {

    data class Item(
        val key: String,
        val icon: Int,
        val title: String,
        val desc: String,
        val granted: Boolean,
        /** true = 能一键弹系统授权框；false 只能跳系统页面手动开 */
        val autoFixable: Boolean,
        /** 是否计入主界面的「N 项待处理」（相机关乎扫码，不进主流程，不计入） */
        val critical: Boolean = true
    )

    fun has(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun rootOk(): Boolean = runCatching { RootShell.isRooted() }.getOrDefault(false)

    /**
     * 应用列表权限没法直接查（它是安装期权限），用「能列出多少个包」间接判断：
     * 被拒时 packageManager 只会返回自己一个 App。这比查权限位更贴近实际效果。
     */
    fun appListOk(ctx: Context): Boolean =
        runCatching { ctx.packageManager.getInstalledPackages(0).size >= 10 }.getOrDefault(false)

    fun batteryOk(ctx: Context): Boolean {
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    fun notifOk(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun cameraOk(ctx: Context): Boolean = has(ctx, android.Manifest.permission.CAMERA)

    fun all(ctx: Context): List<Item> {
        val sdk33 = Build.VERSION.SDK_INT >= 33
        return listOf(
            Item(
                "root", R.drawable.ic_shield, "root 权限",
                "装 iptables 规则、以 root 运行内核。没有它整个共享功能不可用。",
                rootOk(), true
            ),
            Item(
                "notif", R.drawable.ic_logs, "通知权限",
                "前台服务通知。缺了它通知栏看不到实时流量与运行状态（Android 13+ 需要）。",
                notifOk(ctx), sdk33
            ),
            Item(
                "applist", R.drawable.ic_clients, "读取应用列表",
                "应用分流要用它列出已安装应用。缺了它分流页只剩自己一个 App。",
                appListOk(ctx), false
            ),
            Item(
                "camera", R.drawable.ic_subscription, "相机权限",
                "扫描订阅二维码时使用。不用扫码导入的话可以不管。",
                cameraOk(ctx), true, critical = false
            ),
            Item(
                "battery", R.drawable.ic_core, "电池优化白名单",
                "不加白名单，系统会在后台回收服务，表现为共享莫名其妙断开。",
                batteryOk(ctx), true
            )
        )
    }

    /** 主界面用的计数：只算缺了会静默失效的那几项 */
    fun missingCritical(ctx: Context): Int = all(ctx).count { it.critical && !it.granted }
}