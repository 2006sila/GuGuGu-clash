package io.vpnshare.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.vpnshare.prefs.Prefs

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val p = Prefs.load(context)
        if (!p.enabled || !p.autoStartOnBoot) return
        runCatching { ShareService.start(context) }
    }
}
