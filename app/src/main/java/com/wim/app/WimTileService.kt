package com.wim.app

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile — tap to start/stop recording from the notification shade.
 */
class WimTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, BubbleService::class.java).apply {
                action = "TOGGLE_RECORDING"
            }
            startForegroundService(intent)
        } catch (e: Exception) {
            // BubbleService not running — start it first
            try {
                startForegroundService(Intent(this, BubbleService::class.java).apply { action = "SHOW" })
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startForegroundService(Intent(this, BubbleService::class.java).apply { action = "TOGGLE_RECORDING" })
                }, 1000)
            } catch (_: Exception) {}
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ updateTile() }, 500)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val recording = BubbleService.instance?.isCurrentlyRecording() == true
        tile.state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (recording) "Recording..." else "WiM Record"
        tile.updateTile()
    }
}
