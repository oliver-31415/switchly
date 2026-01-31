package at.saltyy.switchly.platform.receiver.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutostartStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.platform.receiver.bluetooth.BluetoothTriggerMonitor
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

        // Restore monitors (these should NOT force-start FGS)
        runCatching { WifiTriggerMonitor.ensureStarted(ctx) }
        runCatching { BluetoothTriggerMonitor.ensureStarted(ctx) }

        // Ensure prefs/runtime initialized
        SwitchModeStore.ensureInit(ctx)

        val enabled = SwitchModeStore.isEnabled(ctx)
        val autostart = AutostartStore.isEnabled(ctx)
        val hasA11y = BlockingRuntime.isAccessibilityActive(ctx)

        Log.d(TAG, "BOOT_COMPLETED received -> enabled=$enabled autostart=$autostart accessibility=$hasA11y")

        // Restore time schedule alarms
        runCatching { SchedulePlanner.updateNextAlarm(ctx) }
        runCatching { SchedulePlanner.notifyNextChanged(ctx) }

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
