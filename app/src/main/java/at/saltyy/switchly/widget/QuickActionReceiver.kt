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

package at.saltyy.switchly.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.settings.PermissionsActivity

class QuickActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FOCUS_NOW -> handleFocusNow(context)
            ACTION_PAUSE_SWITCHLY_15 -> handlePauseSwitchly(context, 15)
            ACTION_PAUSE_SWITCHLY_30 -> handlePauseSwitchly(context, 30)
            ACTION_PAUSE_SWITCHLY_60 -> handlePauseSwitchly(context, 60)
        }
    }

    companion object {
        const val ACTION_FOCUS_NOW = "at.saltyy.switchly.action.FOCUS_NOW"
        const val ACTION_PAUSE_SWITCHLY_15 = "at.saltyy.switchly.action.PAUSE_SWITCHLY_15"
        const val ACTION_PAUSE_SWITCHLY_30 = "at.saltyy.switchly.action.PAUSE_SWITCHLY_30"
        const val ACTION_PAUSE_SWITCHLY_60 = "at.saltyy.switchly.action.PAUSE_SWITCHLY_60"

        fun refreshWidgets(context: Context) {
            PauseBlockerWidgetProvider.refreshAll(context)
            FocusNowWidgetProvider.refreshAll(context)
            ScannerWidgetProvider.refreshAll(context)
        }

        fun handleFocusNow(context: Context): Boolean {
            if (!ensureProtectionReady(context)) {
                refreshWidgets(context)
                return false
            }

            val canEnable = AutomationModeStore.isButtonEnableAllowed(context)
            if (!canEnable) {
                Toast.makeText(context, context.getString(R.string.mode_blocked_tile_action), Toast.LENGTH_SHORT).show()
                refreshWidgets(context)
                return false
            }

            val tempDisableRemaining = SwitchModeStore.getTemporaryRemainingMillis(context)
            val tempEnableRemaining = SwitchModeStore.getTemporaryEnableRemainingMillis(context)
            val currentlyEnabled = SwitchModeStore.isEnabled(context)
            if (currentlyEnabled && tempDisableRemaining <= 0L && tempEnableRemaining <= 0L) {
                Toast.makeText(context, context.getString(R.string.widget_focus_already_active), Toast.LENGTH_SHORT).show()
                refreshWidgets(context)
                return false
            }

            SwitchModeStore.setEnabled(context, true)
            AppLogStore.append(context, "Profiles", "Manual toggle action=enable profile=${ProfileStore.getCurrent(context)}")
            BlockingRuntime.ensureRunning(context)
            Toast.makeText(context, context.getString(R.string.widget_focus_now_applied), Toast.LENGTH_SHORT).show()
            refreshWidgets(context)
            return true
        }

        fun handlePauseSwitchly(context: Context, minutes: Int): Boolean {
            if (!ensureProtectionReady(context)) {
                refreshWidgets(context)
                return false
            }

            val baseEnabled = SwitchModeStore.isBaseEnabled(context)
            val tempDisableRemaining = SwitchModeStore.getTemporaryRemainingMillis(context)
            if (!baseEnabled && tempDisableRemaining <= 0L) {
                Toast.makeText(context, context.getString(R.string.widget_pause_already_disabled), Toast.LENGTH_SHORT).show()
                refreshWidgets(context)
                return false
            }

            if (!AutomationModeStore.isTileAllowed(context)) {
                Toast.makeText(context, context.getString(R.string.mode_blocked_tile_action), Toast.LENGTH_SHORT).show()
                refreshWidgets(context)
                return false
            }

            val requireNfc = SwitchModeStore.isNfcRequiredForDisable(context)
            val emergencyActive = EmergencyBypassStore.isActive(context)
            if (requireNfc && !emergencyActive) {
                Toast.makeText(context, context.getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                refreshWidgets(context)
                return false
            }

            SwitchModeStore.setTemporarilyDisabled(context, minutes * 60_000L)
            AppLogStore.append(context, "Profiles", "Manual toggle action=temp_disable profile=${ProfileStore.getCurrent(context)} duration=${minutes * 60_000L}ms")
            BlockingRuntime.ensureRunning(context)
            Toast.makeText(
                context,
                context.resources.getQuantityString(R.plurals.widget_pause_applied_fmt, minutes, minutes),
                Toast.LENGTH_SHORT
            ).show()
            refreshWidgets(context)
            return true
        }

        private fun ensureProtectionReady(context: Context): Boolean {
            if (BlockingRuntime.isAccessibilityActive(context)) return true

            Toast.makeText(context, context.getString(R.string.widget_action_requires_permissions), Toast.LENGTH_SHORT).show()
            runCatching {
                context.startActivity(
                    Intent(context, PermissionsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            return false
        }
    }
}
