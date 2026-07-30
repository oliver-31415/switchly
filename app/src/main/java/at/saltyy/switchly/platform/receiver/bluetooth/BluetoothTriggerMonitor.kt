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

package at.saltyy.switchly.platform.receiver.bluetooth

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.platform.receiver.logic.BluetoothTriggerReceiverLogic
import at.saltyy.switchly.ui.MainActivity
import java.util.concurrent.Executors

object BluetoothTriggerMonitor {

    private const val PREFS = "switchly_prefs_schedules"
    private const val KEY_SCHEDULES = "items"

    @Volatile private var listening = false
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    @Volatile private var cachedServiceIntent: Intent? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val syncExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SwitchlyBluetoothMonitor").apply { isDaemon = true }
    }

    private fun serviceIntent(context: Context): Intent {
        return cachedServiceIntent ?: synchronized(this) {
            cachedServiceIntent ?: Intent(context.applicationContext, BluetoothTriggerService::class.java).also {
                cachedServiceIntent = it
            }
        }
    }

    @Synchronized
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

    // Read schedules away from the main thread, then start or stop the foreground service from the main queue.
    // The start request is therefore made only after Application.onCreate() or a receiver callback can return.
    // Android starts the foreground-service deadline at the request, so starting from a worker during app startup can race a busy main thread.
    private fun scheduleSync(context: Context) {
        val ctx = context.applicationContext

        runCatching {
            syncExecutor.execute {
                val active = runCatching {
                    BluetoothTriggerReceiverLogic.hasActiveBluetoothSchedules(ScheduleStore.getAll(ctx))
                }.getOrNull()

                if (active == null) {
                    return@execute
                }

                mainHandler.post {
                    if (active) {
                        startService(ctx)
                    } else {
                        stopService(ctx)
                    }
                }
            }
        }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasBluetooth = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasBluetooth) {
                return false
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val hasFgsConnectedDevice = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasFgsConnectedDevice) {
                return false
            }
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
            .setContentTitle(context.getString(R.string.notif_enable_bt_triggers_title))
            .setContentText(context.getString(R.string.notif_enable_bt_triggers_text_fmt, context.getString(R.string.app_name)))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setDefaults(0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        runCatching { nm.notify(FALLBACK_NOTIF_ID, notif) }
    }

    private const val FALLBACK_CHANNEL_ID = "switchly_fallback_bt_silent"
    private const val FALLBACK_NOTIF_ID = 23002
}
