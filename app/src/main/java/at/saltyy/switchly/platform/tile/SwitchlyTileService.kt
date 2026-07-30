/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.platform.tile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.entry.QuickActionIconFactory

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
        val canChange = if (currentlyEnabled) {
            AutomationModeStore.isTileAllowed(ctx)
        } else {
            AutomationModeStore.isTileAllowed(ctx) || AutomationModeStore.isButtonEnableAllowed(ctx)
        }

        if (!canChange) {
            val messageRes = if (currentlyEnabled && AutomationModeStore.isButtonEnableAllowed(ctx)) {
                R.string.mode_blocked_button_disable_enable_only
            } else {
                R.string.mode_blocked_tile_action
            }
            Toast.makeText(
                applicationContext,
                getString(messageRes),
                Toast.LENGTH_SHORT
            ).show()
            refreshTile()
            return
        }

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

        val target = !currentlyEnabled
        if (SwitchModeStore.setEnabled(ctx, target, allowNfcBypass = false)) {
            AppLogStore.append(
                ctx,
                "Profiles",
                "Manual toggle action=${if (target) "enable" else "disable"} profile=${ProfileStore.getCurrent(ctx)}"
            )
        }
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

            // Use a rendered monochrome bitmap so System UI does not need to resolve app-theme colors.
            icon = QuickActionIconFactory.createTileIcon(
                this@SwitchlyTileService,
                R.drawable.qs_switchly_24
            )
            updateTile()
        }
    }
}
