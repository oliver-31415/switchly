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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.platform.receiver.location.LocationTriggerMonitor
import at.saltyy.switchly.theme.AccentColor

/**
 * Triggered after an app update (ACTION_PACKAGE_REPLACED).
 * If Switchly is active but missing the Accessibility service, show a friendly notification that links directly to the Accessibility settings.
 */
class PostUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_REPLACED) return

        runCatching { LocationTriggerMonitor.ensureStarted(ctx.applicationContext) }

        val enabled = SwitchModeStore.isEnabled(ctx)
        if (!enabled) return

        val needAccessibility = !BlockingRuntime.isAccessibilityActive(ctx)
        if (!needAccessibility) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
            .setSmallIcon(R.drawable.app_blocking_white_24)
            .setColor(AccentColor.getAccentColorInt(ctx))
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(message)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        nm.notify(43, notif)
    }
}
