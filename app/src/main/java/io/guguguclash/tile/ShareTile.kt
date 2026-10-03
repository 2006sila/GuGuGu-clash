package io.guguguclash.tile

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.guguguclash.prefs.Prefs
import io.guguguclash.service.ShareService
import io.guguguclash.service.ShareState

class ShareTile : TileService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        main.post { paint() }
    }

    override fun onClick() {
        val ctx = applicationContext
        val p = Prefs.load(ctx)
        if (ShareState.running || p.enabled) ShareService.stop(ctx) else ShareService.start(ctx)
        main.postDelayed({ paint() }, 600)
    }

    private fun paint() {
        val t = qsTile ?: return
        val on = ShareState.running || Prefs.load(applicationContext).enabled
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "咕咕咕clash"
        t.updateTile()
    }
}
