package at.saltyy.switchly.platform.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore

class SwitchlyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggleAndRefresh() }
        } else {
            toggleAndRefresh()
        }
    }

    private fun toggleAndRefresh() {
        val ctx = this
        val currentlyEnabled = SwitchModeStore.isEnabled(ctx)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)

        // Disable only via NFC, when lock is enabled
        val emergencyActive = EmergencyBypassStore.isActive(ctx)

        // Disable only via NFC, when lock is enabled (unless Emergency Bypass is active)
        if (currentlyEnabled && requireNfc && !emergencyActive) {
            Toast.makeText(
                applicationContext,
                getString(R.string.toast_disable_requires_nfc),
                Toast.LENGTH_SHORT
            ).show()
            refreshTile()
            return
        }

        SwitchModeStore.setEnabled(ctx, !currentlyEnabled)
        refreshTile()
    }

    private fun refreshTile() {
        val enabled = SwitchModeStore.isEnabled(this)
        qsTile?.apply {
            // State
            state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

            // Label
            label = if (enabled) {
                getString(R.string.qs_label_on)
            } else {
                getString(R.string.qs_label_off)
            }
            
            // App-Icon
            icon = Icon.createWithResource(
                this@SwitchlyTileService,
                R.drawable.app_blocking_white_24
            )
            updateTile()
        }
    }
}
