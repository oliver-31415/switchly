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
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.settings.PermissionsActivity

/**
 * Shows a persistent warning when Switchly is enabled but Accessibility is genuinely unavailable.
 * Accessibility heartbeats can briefly look stale while a device wakes from deep sleep, after an app update, or while Android reconnects the service. 
 * Those short transitions must not produce a warning. 
 * We therefore remember the first unhealthy observation and verify it again after a grace period before notifying the user.
 */
object ProtectionStatusNotifier {

    private const val CHANNEL_ID = "protection_status_silent"
    private const val NOTIF_ID = 9001

    private const val PREFS_NAME = "protection_status_notifier"
    private const val KEY_INACTIVE_SINCE_MS = "accessibility_inactive_since_ms"
    private const val KEY_WARNING_CONFIRMED = "accessibility_warning_confirmed"
    private const val KEY_LAST_SUPPRESSED_LOG_MS = "accessibility_last_suppressed_log_ms"
    private const val WARNING_GRACE_MS = 20_000L
    private const val SUPPRESSED_LOG_THROTTLE_MS = 6 * 60 * 60 * 1_000L

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var pendingVerification: Runnable? = null

    /**
     * Called from the live Accessibility heartbeat.
     * This is intentionally cheap in the normal case and immediately clears a pending/visible warning if the service recovered between verification checks.
     */
    fun onAccessibilityHeartbeat(context: Context) {
        val ctx = context.applicationContext
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasPendingOrConfirmedWarning =
            prefs.contains(KEY_INACTIVE_SINCE_MS) || prefs.getBoolean(KEY_WARNING_CONFIRMED, false)
        if (!hasPendingOrConfirmedWarning) {
            return
        }

        recover(
            ctx,
            ProtectionState(
                shouldMonitor = true,
                accessibilityActive = true
            )
        )
    }

    fun refresh(context: Context) {
        val ctx = context.applicationContext
        val state = readState(ctx)

        if (!state.shouldMonitor || state.accessibilityActive) {
            recover(ctx, state)
            return
        }

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val storedSince = prefs.getLong(KEY_INACTIVE_SINCE_MS, 0L)
        val inactiveSince = if (storedSince > 0L && storedSince <= now) {
            storedSince
        } else {
            prefs.edit { putLong(KEY_INACTIVE_SINCE_MS, now) }
            now
        }

        val remaining = (WARNING_GRACE_MS - (now - inactiveSince)).coerceAtLeast(0L)
        if (remaining == 0L) {
            verifyAndUpdate(ctx)
        } else {
            scheduleVerification(ctx, remaining)
        }
    }

    private fun scheduleVerification(ctx: Context, delayMs: Long) {
        synchronized(lock) {
            if (pendingVerification != null) {
                return
            }

            val task = Runnable {
                synchronized(lock) {
                    pendingVerification = null
                }
                verifyAndUpdate(ctx)
            }
            pendingVerification = task
            handler.postDelayed(task, delayMs)
        }
    }

    private fun verifyAndUpdate(ctx: Context) {
        synchronized(lock) {
            val state = readState(ctx)
            if (!state.shouldMonitor || state.accessibilityActive) {
                recover(ctx, state)
                return
            }

            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_INACTIVE_SINCE_MS)) {
                return
            }
            if (!prefs.getBoolean(KEY_WARNING_CONFIRMED, false)) {
                val diagnostics = BlockingRuntime.getRuntimeDiagnostics(ctx)
                AppLogStore.append(
                    ctx,
                    "Protection",
                    "accessibility_warning_confirmed settingsEnabled=${diagnostics.accessibilityEnabledInSettings} " +
                        "runtimeActive=${diagnostics.accessibilityActive} heartbeatAgeMs=${diagnostics.heartbeatAgeMs} " +
                        "graceMs=$WARNING_GRACE_MS"
                )
                prefs.edit { putBoolean(KEY_WARNING_CONFIRMED, true) }
            }
            show(ctx)
        }
    }

    private fun recover(ctx: Context, state: ProtectionState) {
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hadPendingCandidate = prefs.getLong(KEY_INACTIVE_SINCE_MS, 0L) > 0L

            pendingVerification?.let { handler.removeCallbacks(it) }
            pendingVerification = null
            prefs.edit {
                remove(KEY_INACTIVE_SINCE_MS)
                remove(KEY_WARNING_CONFIRMED)
            }
            cancel(ctx)

            if (hadPendingCandidate && state.shouldMonitor && state.accessibilityActive) {
                val now = System.currentTimeMillis()
                val lastSuppressedLog = prefs.getLong(KEY_LAST_SUPPRESSED_LOG_MS, 0L)
                if (now - lastSuppressedLog >= SUPPRESSED_LOG_THROTTLE_MS) {
                    val diagnostics = BlockingRuntime.getRuntimeDiagnostics(ctx)
                    AppLogStore.append(
                        ctx,
                        "Protection",
                        "accessibility_warning_suppressed reason=recovered settingsEnabled=${diagnostics.accessibilityEnabledInSettings} " + "heartbeatAgeMs=${diagnostics.heartbeatAgeMs}"
                    )
                    prefs.edit { putLong(KEY_LAST_SUPPRESSED_LOG_MS, now) }
                }
            }
        }
    }

    private fun readState(ctx: Context): ProtectionState {
        val enabled = SwitchModeStore.isEnabled(ctx)
        val hasBlockedApps = ProfileStore.getProfiles(ctx)
            .any { ProfileStore.getSelectedForProfileMode(ctx, it).isNotEmpty() }

        return ProtectionState(
            shouldMonitor = enabled && hasBlockedApps,
            accessibilityActive = BlockingRuntime.isAccessibilityActive(ctx)
        )
    }

    private fun show(ctx: Context) {
        // Android 13+ requires the runtime POST_NOTIFICATIONS permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                return
            }
        }

        // If user disabled notifications in system settings, don't try to show.
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            return
        }

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

    private data class ProtectionState(
        val shouldMonitor: Boolean,
        val accessibilityActive: Boolean
    )
}
