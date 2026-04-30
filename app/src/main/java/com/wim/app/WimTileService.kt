package com.wim.app

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile — tap to SUMMON the floating bubble.
 *
 * The previous behavior toggled recording from the QS shade, which was
 * confusing: the bubble itself is the recording affordance. The tile's
 * job now is just to bring the bubble back when it's been hidden
 * (drag-to-bottom or shake) so the user can resume from any app.
 */
class WimTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            startForegroundService(
                Intent(this, BubbleService::class.java).apply { action = "SHOW" }
            )
        } catch (_: Exception) {
            // BubbleService not running yet — fall through; the SHOW
            // action above will start it as a foreground service.
        }
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val visible = BubbleService.instance?.isBubbleVisible() == true
        tile.state = if (visible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "WiM"
        try {
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile_wm)
        } catch (_: Exception) {}
        tile.updateTile()
    }
}
