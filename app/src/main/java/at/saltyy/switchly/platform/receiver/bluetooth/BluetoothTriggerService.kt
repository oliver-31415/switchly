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
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import at.saltyy.switchly.platform.receiver.wifi.WifiBtCache
import at.saltyy.switchly.ui.MainActivity

class BluetoothTriggerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    private val br = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    cacheFromIntent(intent, reason = "connected")
                    sendTick(reason = "connected", eventBtConnected = true)
                    scheduleRetryIfNeeded(reason = "connected")
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    WifiBtCache.clearBt(applicationContext)
                    sendTick(reason = "disconnected", eventBtConnected = false)
                }

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state != BluetoothAdapter.STATE_ON) {
                        WifiBtCache.clearBt(applicationContext)
                        sendTick(reason = "btOff", eventBtConnected = false)
                    } else {
                        sendTick(reason = "btOn")
                        scheduleRetryIfNeeded(reason = "btOn")
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // We are started via ContextCompat.startForegroundService().
        // If we don't call startForeground() fast enough (or it throws), Android will crash the app.
        if (!ensureForegroundOrStop()) return

        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            this,
            br,
            f,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        cacheFromSystem(reason = "serviceStart")
        sendTick(reason = "serviceStart")
        scheduleRetryIfNeeded(reason = "serviceStart")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(br) }

        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (ensureForegroundOrStop()) START_STICKY else START_NOT_STICKY
    }

    /**
     * Returns true if we successfully entered the foreground.
     * If we can't (e.g. permission/policy), we stop ourselves to avoid ForegroundServiceDidNotStartInTimeException.
     */
    private var foregroundStarted = false

    private fun ensureForegroundOrStop(): Boolean {
        if (foregroundStarted) return true

        val nm = getSystemService(NotificationManager::class.java)

        runCatching {
            nm?.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    getString(R.string.notif_channel_bluetooth_triggers_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_bluetooth_triggers_desc)
                }
            )
        }

        val notif = buildNotification()

        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.recoverCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, 0)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        }.recoverCatching {
            startForeground(NOTIF_ID, notif)
        }.isSuccess

        if (!ok) {
            Log.e(TAG, "Failed to enter foreground. Stopping service to avoid process crash.")
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return false
        }

        foregroundStarted = true
        return true
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val contentPi = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.app_blocking_white_24)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.notif_bluetooth_schedules_title))
            .setContentText(getString(R.string.notif_bluetooth_schedules_content))
            .setContentIntent(contentPi)
            .build()
    }

    private fun sendTick(reason: String, eventBtConnected: Boolean? = null) {
        val cached = WifiBtCache.getBt(applicationContext)

        Log.d(
            TAG,
            "bt tick: $reason cachedName=${cached.name} cachedAddr=${cached.addr} connected=$eventBtConnected"
        )

        sendBroadcast(
            Intent(this, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_TICK
                putExtra("bt_reason", reason)
                putExtra("eventBtName", cached.name)
                putExtra("eventBtAddr", cached.addr)
                if (eventBtConnected != null) putExtra("eventBtConnected", eventBtConnected)
            }
        )
    }

    private fun cacheFromIntent(intent: Intent, reason: String) {
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device != null) cacheDevice(device, reason)
    }

    private fun cacheFromSystem(reason: String) {
        val bm = getSystemService(BluetoothManager::class.java)
        val adapter = bm?.adapter ?: return
        if (!adapter.isEnabled) return
        Log.d(TAG, "bt system start ($reason) - waiting for events")
    }

    private fun cacheDevice(device: BluetoothDevice, reason: String) {
        val addr = safeGetAddress(device)
        val name = safeGetName(device)

        WifiBtCache.setBt(applicationContext, name, addr)
        Log.d(TAG, "cached bt name='${name}' addr='${addr}' ($reason)")
        retryCount = 0
    }

    private fun safeGetAddress(device: BluetoothDevice): String? {
        return try {
            device.address
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeGetName(device: BluetoothDevice): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val granted = ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return null
                device.name
            } else {
                device.name
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun scheduleRetryIfNeeded(reason: String) {
        val cached = WifiBtCache.getBt(applicationContext)
        val haveAddr = !cached.addr.isNullOrBlank()
        val haveName = !cached.name.isNullOrBlank()
        if (haveAddr || haveName) return

        if (retryCount >= 5) {
            Log.w(TAG, "bt still missing after retries ($reason). Likely missing BLUETOOTH_CONNECT or BT off.")
            return
        }

        val delays = longArrayOf(700, 1200, 2000, 3500, 5000)
        val delay = delays[retryCount.coerceIn(0, delays.lastIndex)]
        retryCount++

        handler.postDelayed({
            sendTick(reason = "retry")
        }, delay)

        Log.d(TAG, "scheduled bt retry in ${delay}ms ($reason)")
    }

    companion object {
        private const val TAG = "BluetoothTriggerService"
        private const val NOTIF_CHANNEL_ID = "switchly_bt_triggers"
        private const val NOTIF_ID = 23001
    }
}
