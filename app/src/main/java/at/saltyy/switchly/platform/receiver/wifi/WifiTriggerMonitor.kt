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

package at.saltyy.switchly.platform.receiver.wifi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object WifiTriggerMonitor {

    private const val PREFS = "switchly_prefs_schedules"
    private const val KEY_SCHEDULES = "items"

    @Volatile private var listening = false
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var cachedServiceIntent: Intent? = null
    private val initialSyncScheduled = AtomicBoolean(false)
    private val syncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SwitchlyWifiMonitor").apply { isDaemon = true }
    }

    private fun serviceIntent(context: Context): Intent {
        return cachedServiceIntent ?: synchronized(this) {
            cachedServiceIntent ?: Intent(context.applicationContext, WifiTriggerService::class.java).also {
                cachedServiceIntent = it
            }
        }
    }

    fun ensureStarted(context: Context) {
        val ctx = context.applicationContext

        if (!listening) {
            listening = true
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_SCHEDULES) scheduleSync(ctx)
            }
            listener = l
            sp.registerOnSharedPreferenceChangeListener(l)
        }

        scheduleSync(ctx)
    }

    /**
     * Starting a foreground service directly from Application.onCreate() or a receiver delays service creation until that callback returns, while Android's foreground deadline is already running.
     * Defer and coalesce the initial sync so the service can be created immediately after the start request.
     */
    private fun scheduleSync(context: Context) {
        val ctx = context.applicationContext
        if (!initialSyncScheduled.compareAndSet(false, true)) return

        try {
            syncExecutor.execute {
                try {
                    sync(ctx)
                } finally {
                    initialSyncScheduled.set(false)
                }
            }
        } catch (_: Throwable) {
            initialSyncScheduled.set(false)
        }
    }

    private fun sync(context: Context) {
        val ctx = context.applicationContext
        val schedules = ScheduleStore.getAll(ctx)
        val active = schedules.any { it.enabled && !it.wifiSsid.isNullOrBlank() }

        if (active) startService(ctx) else stopService(ctx)
    }

    private fun startService(context: Context) {
        val ctx = context.applicationContext
        if (!hasForegroundServicePrerequisites(ctx)) {
            stopService(ctx)
            postEnableNotification(ctx)
            return
        }

        val intent = serviceIntent(ctx)

        val started = runCatching {
            ContextCompat.startForegroundService(ctx, intent)
        }.isSuccess

        if (!started) {
            postEnableNotification(ctx)
        }
    }

    private fun hasForegroundServicePrerequisites(context: Context): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasFgsLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFgsLocation) return false
        }

        return true
    }

    private fun stopService(context: Context) {
        context.applicationContext.stopService(serviceIntent(context))

        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(FALLBACK_NOTIF_ID)
        }
    }

    private fun postEnableNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(
                    FALLBACK_CHANNEL_ID,
                    context.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(context, 0, openIntent, piFlags)

        val notif = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.app_blocking_white_24)
            .setContentTitle(context.getString(R.string.notif_enable_wifi_triggers_title))
            .setContentText(
                context.getString(
                    R.string.notif_enable_wifi_triggers_text_fmt,
                    context.getString(R.string.app_name)
                )
            )
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDefaults(0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching { nm.notify(FALLBACK_NOTIF_ID, notif) }
    }

    private const val FALLBACK_CHANNEL_ID = "switchly_fallback_wifi_silent"
    private const val FALLBACK_NOTIF_ID = 23004
}
