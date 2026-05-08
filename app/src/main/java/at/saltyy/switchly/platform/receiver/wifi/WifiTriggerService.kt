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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.edit
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import at.saltyy.switchly.ui.MainActivity

class WifiTriggerService : Service() {

    private lateinit var cm: ConnectivityManager
    private lateinit var wifi: WifiManager
    private var cb: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private var retryCount = 0

    override fun onCreate() {
        super.onCreate()

        // We are started via ContextCompat.startForegroundService().
        // If we don't call startForeground() fast enough (or it throws), Android will crash the app.
        if (!ensureForegroundOrStop()) return

        cm = getSystemService(ConnectivityManager::class.java)
        wifi = applicationContext.getSystemService(WifiManager::class.java)

        // Android 12+ may redact Wi‑Fi SSID/BSSID unless the callback explicitly requests location-sensitive transport info.
        cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(
                FLAG_INCLUDE_LOCATION_INFO
            ) {
                override fun onAvailable(network: Network) {
                    cacheWifiFromActiveNetwork("available")
                    sendTick("available")
                    scheduleWifiRetryIfNeeded("available")
                }

                override fun onLost(network: Network) {
                    clearCachedWifi("lost")
                    sendTick("lost")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    cacheWifiFromCaps(caps, "capabilitiesChanged")
                    sendTick("capabilitiesChanged")
                    scheduleWifiRetryIfNeeded("capabilitiesChanged")
                }
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cacheWifiFromActiveNetwork("available")
                    sendTick("available")
                    scheduleWifiRetryIfNeeded("available")
                }

                override fun onLost(network: Network) {
                    clearCachedWifi("lost")
                    sendTick("lost")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    cacheWifiFromCaps(caps, "capabilitiesChanged")
                    sendTick("capabilitiesChanged")
                    scheduleWifiRetryIfNeeded("capabilitiesChanged")
                }
            }
        }

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        cm.registerNetworkCallback(req, cb!!)
        cacheWifiFromActiveNetwork("serviceStart")
        sendTick("serviceStart")
        scheduleWifiRetryIfNeeded("serviceStart")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null

        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return if (ensureForegroundOrStop()) START_STICKY else START_NOT_STICKY
    }

    /**
     * Returns true if we successfully entered the foreground.
     * If we can't (e.g. permission/policy), we stop ourselves to avoid
     * ForegroundServiceDidNotStartInTimeException.
     */
    private var foregroundStarted = false

    private fun ensureForegroundOrStop(): Boolean {
        if (foregroundStarted) return true

        val nm = getSystemService(NotificationManager::class.java)

        runCatching {
            nm?.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    getString(R.string.notif_channel_wifi_triggers_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_wifi_triggers_desc) }
            )
        }

        val notif = buildNotification()

        // Try a few startForeground variants. Some Android builds are picky about service type handling.
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
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

        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(this, 0, openAppIntent, piFlags)

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.app_blocking_white_24)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(getString(R.string.notif_wifi_schedules_title))
            .setContentText(getString(R.string.notif_wifi_schedules_content))
            .setContentIntent(contentPi)
            .build()
    }

    private fun sendTick(reason: String) {
        dbg("wifi tick: $reason")
        sendBroadcast(
            Intent(this, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_TICK
                putExtra("wifi_reason", reason)
            }
        )
    }

    private fun cacheWifiFromActiveNetwork(reason: String) {
        // "activeNetwork" is not guaranteed to be Wi-Fi (VPN/cellular can be active while Wi-Fi is connected).
        // Therefore we try active first, then fall back to scanning all networks for a Wi-Fi transport.
        val net = cm.activeNetwork
        if (net != null) {
            val caps = cm.getNetworkCapabilities(net)
            if (caps != null && cacheWifiFromCaps(caps, reason)) return
        }
        cacheWifiFromAnyWifiNetwork(reason)
    }

    private fun cacheWifiFromAnyWifiNetwork(reason: String) {
        val nets = allNetworksCompat(cm)
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            if (cacheWifiFromCaps(caps, "$reason(allNetworks)")) return
        }
    }

    private fun allNetworksCompat(cm: ConnectivityManager): Array<Network> {
        val value = runCatching {
            cm.javaClass.getMethod("getAllNetworks").invoke(cm)
        }.getOrNull()
        return (value as? Array<*>)?.filterIsInstance<Network>()?.toTypedArray() ?: emptyArray()
    }

    private fun cacheWifiFromCaps(caps: NetworkCapabilities, reason: String): Boolean {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false

        var ssid: String? = null
        var bssid: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = caps.transportInfo as? WifiInfo
            if (wifiInfo != null) {
                ssid = wifiInfo.ssid
                bssid = wifiInfo.bssid
            }

            // Some OEMs return null transportInfo even while connected to Wi-Fi.
            // Compatibility fallback to WifiManager connection info (still requires Location permission on modern Android).
            if (ssid.isNullOrBlank() && bssid.isNullOrBlank()) {
                readWifiInfoCompat()?.let { info ->
                    ssid = info.ssid
                    bssid = info.bssid
                }
            }
        } else {
            readWifiInfoCompat()?.let { info ->
                ssid = info.ssid
                bssid = info.bssid
            }
        }

        val cleanSsid = ssid
            ?.trim('"')
            ?.trim()
            ?.takeIf { s ->
                s.isNotBlank() &&
                    !s.equals("<unknown ssid>", ignoreCase = true) &&
                    !s.equals("unknown ssid", ignoreCase = true)
            }

        val cleanBssid = bssid
            ?.trim()
            ?.takeIf { b ->
                b.isNotBlank() && !b.equals("02:00:00:00:00:00", ignoreCase = true)
            }

        if (cleanSsid == null && cleanBssid == null) return false

        getSharedPreferences(PREFS_WIFI, MODE_PRIVATE).edit {
            if (cleanSsid != null) putString(KEY_LAST_SSID, cleanSsid) else remove(KEY_LAST_SSID)
            if (cleanBssid != null) putString(KEY_LAST_BSSID, cleanBssid) else remove(KEY_LAST_BSSID)
            putBoolean(KEY_NEEDS_LOCATION_HINT, false)
        }

        dbg("cached wifi ssid='${cleanSsid ?: "null"}' bssid='${cleanBssid ?: "null"}' ($reason)")
        retryCount = 0
        return true
    }

    private fun readWifiInfoCompat(): WifiInfo? {
        return try {
            wifiConnectionInfoCompat(wifi)
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun wifiConnectionInfoCompat(wifiManager: WifiManager): WifiInfo? {
        return runCatching {
            wifiManager.javaClass.getMethod("getConnectionInfo").invoke(wifiManager) as? WifiInfo
        }.getOrNull()
    }
    private fun scheduleWifiRetryIfNeeded(reason: String) {
        val hasWifiSchedules = at.saltyy.switchly.data.prefs.ScheduleStore
            .hasEnabledWifiSchedules(applicationContext)
        if (!hasWifiSchedules) return

        val prefs = getSharedPreferences(PREFS_WIFI, MODE_PRIVATE)
        val alreadySsid = prefs.getString(KEY_LAST_SSID, null)
        val alreadyBssid = prefs.getString(KEY_LAST_BSSID, null)
        if (!alreadySsid.isNullOrBlank() || !alreadyBssid.isNullOrBlank()) return

        if (retryCount >= 5) {
            Log.w(TAG, "Wi-Fi info still null after retries ($reason). Likely Location OFF/no Precise/permission.")
            prefs.edit {
                putBoolean(KEY_NEEDS_LOCATION_HINT, true)
            }
            return
        }

        val delays = longArrayOf(800, 1500, 2500, 4000, 6000)
        val delay = delays[retryCount.coerceIn(0, delays.lastIndex)]
        retryCount++

        handler.postDelayed({
            cacheWifiFromActiveNetwork("retry#$retryCount")
            sendTick("retry")
        }, delay)

        dbg("scheduled Wi-Fi retry in ${delay}ms ($reason)")
    }

    private fun clearCachedWifi(reason: String) {
        getSharedPreferences(PREFS_WIFI, MODE_PRIVATE).edit {
            remove(KEY_LAST_SSID)
            remove(KEY_LAST_BSSID)
        }
        dbg("cleared cached wifi (ssid/bssid) ($reason)")
    }

    private fun dbg(msg: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    companion object {
        private const val TAG = "WifiTriggerService"
        private const val NOTIF_CHANNEL_ID = "switchly_wifi_triggers"
        private const val NOTIF_ID = 23003
        private const val PREFS_WIFI = "switchly_wifi_cache"
        private const val KEY_LAST_SSID = "last_ssid"
        private const val KEY_LAST_BSSID = "last_bssid"
        private const val KEY_NEEDS_LOCATION_HINT = "wifi_needs_location_hint"
    }
}
