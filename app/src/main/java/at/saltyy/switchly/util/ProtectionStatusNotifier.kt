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

/**
 * Shows a persistent warning notification when Switchly is enabled but the Accessibility service is not active (meaning blocking is currently not enforced).
 */
object ProtectionStatusNotifier {

    private const val CHANNEL_ID = "protection_status"
    private const val NOTIF_ID = 9001

    fun refresh(context: Context) {
        val ctx = context.applicationContext

        val enabled = SwitchModeStore.isEnabled(ctx)
        val hasBlockedApps = ProfileStore.getProfiles(ctx)
            .any { ProfileStore.getBlockedForProfile(ctx, it).isNotEmpty() }

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
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(ctx.getString(R.string.protection_inactive_title))
            .setContentText(ctx.getString(R.string.protection_inactive_text))
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
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
