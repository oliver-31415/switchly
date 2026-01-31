package at.saltyy.switchly.platform.receiver.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.platform.receiver.wifi.WifiTriggerMonitor
import at.saltyy.switchly.platform.receiver.bluetooth.BluetoothTriggerMonitor
import at.saltyy.switchly.data.prefs.SchedulePlanner

/**
 * Triggered after an app update (ACTION_MY_PACKAGE_REPLACED).
 * If Switchly is active but missing the Accessibility service, show a friendly notification that links directly to the Accessibility settings.
 */
class PostUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appCtx = ctx.applicationContext

        // Restore schedule alarms + device triggers after updates (OEMs sometimes clear PendingIntents/alarms).
        runCatching { SchedulePlanner.updateNextAlarm(appCtx) }
        runCatching { SchedulePlanner.notifyNextChanged(appCtx) }
        runCatching { WifiTriggerMonitor.ensureStarted(appCtx) }
        runCatching { BluetoothTriggerMonitor.ensureStarted(appCtx) }

        // Ensure prefs/runtime initialized
        SwitchModeStore.ensureInit(appCtx)

        val enabled = SwitchModeStore.isEnabled(appCtx)
        if (!enabled) return

        val needAccessibility = !BlockingRuntime.isAccessibilityActive(appCtx)
        if (!needAccessibility) return

        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "post_update"

        // minSdk 27 -> always create channel
        val channel = NotificationChannel(
            chId,
            ctx.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)

        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pi = PendingIntent.getActivity(
            ctx,
            0,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val message = ctx.getString(R.string.pref_open_accessibility_summary)

        val notif = NotificationCompat.Builder(ctx, chId)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setColor(ctx.getColor(R.color.switchly_green))
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(43, notif)
    }
}
