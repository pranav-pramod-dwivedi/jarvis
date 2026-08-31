package com.pr4nav.jarvis.voice

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.pr4nav.jarvis.MainActivity
import com.pr4nav.jarvis.companion.JarvisOverlayService

/**
 * System Quick Settings Tile for instant access to JARVIS Assistant & Floating HUD.
 */
@RequiresApi(Build.VERSION_CODES.N)
class JarvisTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isHandsFree = VoiceAssistantPreferences.isHandsFreeEnabled(this)
        if (isHandsFree || JarvisOverlayService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "JARVIS Active"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "JARVIS HUD"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return

        if (Settings.canDrawOverlays(this)) {
            // Trigger floating HUD companion overlay directly
            JarvisOverlayService.showHud(this)
        } else {
            // Launch main app if overlay permission is missing
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivityAndCollapse(intent)
        }
        updateTileState()
    }
}
