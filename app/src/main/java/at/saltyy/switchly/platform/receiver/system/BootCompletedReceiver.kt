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

package at.saltyy.switchly.platform.receiver.system

import at.saltyy.switchly.BuildConfig
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutostartStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.platform.receiver.bluetooth.BluetoothTriggerMonitor
import at.saltyy.switchly.platform.receiver.location.LocationTriggerMonitor
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import at.saltyy.switchly.platform.receiver.wifi.WifiTriggerMonitor
import at.saltyy.switchly.util.ProtectionStatusNotifier

/**
 * Receives system boot events and restores Switchly runtime state.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val ctx = context.applicationContext

        // Restore trigger monitors.
        // Wi-Fi/Bluetooth defer and coalesce their FGS sync until this receiver callback has returned, so Android can create the service immediately.
        runCatching { WifiTriggerMonitor.ensureStarted(ctx) }
        runCatching { BluetoothTriggerMonitor.ensureStarted(ctx) }
        runCatching { LocationTriggerMonitor.ensureStarted(ctx) }

        // Ensure prefs/runtime initialized
        SwitchModeStore.ensureInit(ctx)

        val enabled = SwitchModeStore.isEnabled(ctx)
        val autostart = AutostartStore.isEnabled(ctx)
        val hasA11y = BlockingRuntime.isAccessibilityActive(ctx)

        if (BuildConfig.DEBUG) Log.d(TAG, "BOOT_COMPLETED received -> enabled=$enabled autostart=$autostart accessibility=$hasA11y")

        // Restore time schedule alarms
        runCatching { SchedulePlanner.updateNextAlarm(ctx) }
        runCatching { SchedulePlanner.notifyNextChanged(ctx) }

        // Immediate watchdog re-eval after boot: if we are currently inside an active
        // schedule window, re-assert the desired state now (instead of waiting for the next boundary).
        runCatching {
            ctx.sendBroadcast(
                Intent(ctx, ScheduleReceiver::class.java).apply {
                    this.action = ScheduleReceiver.ACTION_TICK
                    putExtra("time_reason", "boot_completed")
                    putExtra("alarm_reason", "boot_watchdog")
                }
            )
        }

        // Start runtime only if it can actually function
        if (!enabled || !autostart) return

        if (hasA11y) {
            runCatching {
                BlockingRuntime.ensureRunning(ctx)
            }.onFailure {
                Log.w(TAG, "Blocking runtime start blocked after boot: ${it.message}")
            }
        }

        // If Switchly is enabled but Accessibility is OFF after boot,
        // show a persistent warning notification so the user can fix it.
        runCatching { ProtectionStatusNotifier.refresh(ctx) }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
