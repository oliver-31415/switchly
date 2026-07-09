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

package at.saltyy.switchly.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.settings.PermissionsActivity

// Shows a persistent warning notification when Switchly is enabled but the Accessibility service is not active (meaning blocking is currently not enforced).
object ProtectionStatusNotifier {

    private const val CHANNEL_ID = "protection_status_silent"
    private const val NOTIF_ID = 9001

    fun refresh(context: Context) {
        val ctx = context.applicationContext

        val enabled = SwitchModeStore.isEnabled(ctx)
        val hasBlockedApps = ProfileStore.getProfiles(ctx)
            .any { ProfileStore.getSelectedForProfileMode(ctx, it).isNotEmpty() }

        val accessibilityActive = BlockingRuntime.isAccessibilityActive(ctx)

        val shouldWarn = enabled && hasBlockedApps && !accessibilityActive

        if (shouldWarn) {
            show(ctx)
        } else {
            cancel(ctx)
        }
    }

    private fun show(ctx: Context) {
        // Android 13+ requires the runtime POST_NOTIFICATIONS permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) return
        }

        // If user disabled notifications in system settings, don't try to show.
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // minSdk is 27, so NotificationChannel is always available.
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = ctx.getString(R.string.protection_inactive_text)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val openPermissions = Intent(ctx, PermissionsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            ctx,
            0,
            openPermissions,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.lock_24)
            .setContentTitle(ctx.getString(R.string.protection_inactive_title))
            .setContentText(ctx.getString(R.string.protection_inactive_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setDefaults(0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Explicitly handle SecurityException (permission can change at runtime).
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
        } catch (_: SecurityException) {
        }
    }

    private fun cancel(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
    }
}
