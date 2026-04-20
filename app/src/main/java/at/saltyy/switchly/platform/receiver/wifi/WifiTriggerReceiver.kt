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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.WifiRuleStore
import at.saltyy.switchly.data.prefs.WifiTriggerStateStore
import at.saltyy.switchly.platform.receiver.logic.WifiTriggerReceiverLogic

class WifiTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
            WifiManager.WIFI_STATE_CHANGED_ACTION -> handle(context)
            else -> Log.d(TAG, "Ignoring unrelated action=${intent.action}")
        }
    }

    private fun handle(context: Context) {
        if (SwitchModeStore.hasActiveTemporaryOverride(context)) {
            Log.d(TAG, "Temporary override active, skipping Wi-Fi profile apply/revert")
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Location permission missing, skipping Wi-Fi rule check")
            return
        }

        if (!isLocationEnabled(context)) {
            Log.d(TAG, "Location is disabled, skipping Wi-Fi rule check")
            return
        }

        val rules = WifiRuleStore.getAll(context)
        val enabledRules = rules.filter { it.enabled }

        val ssid = currentSsid(context)
        if (ssid == null) {
            revertIfAppliedByWifi(context)
            return
        }

        val match = WifiTriggerReceiverLogic.matchProfileForSsid(ssid, enabledRules)
        if (match == null) {
            revertIfAppliedByWifi(context)
            return
        }

        Log.d(TAG, "Matched Wi-Fi rule for SSID=$ssid -> profile=${match.profile}")
        ProfileStore.setCurrent(context, match.profile)
        WifiTriggerStateStore.setLastAppliedProfile(context, match.profile)
    }

    private fun revertIfAppliedByWifi(context: Context) {
        val lastApplied = WifiTriggerStateStore.getLastAppliedProfile(context) ?: return
        val current = ProfileStore.getCurrent(context)

        if (current != null && current == lastApplied) {
            val fallback = ProfileStore.getProfiles(context).firstOrNull() ?: return
            Log.d(TAG, "Wi-Fi no longer matches; reverting profile $current -> $fallback")
            ProfileStore.setCurrent(context, fallback)
        }
        WifiTriggerStateStore.clear(context)
    }

    private fun currentSsid(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (caps.transportInfo as? WifiInfo)?.ssid
            } else {
                val wifiManager =
                    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiManager.connectionInfo?.ssid
            } ?: return null

            if (raw == WifiManager.UNKNOWN_SSID) return null
            if (raw.equals("<unknown ssid>", ignoreCase = true)) return null

            raw.trim('"').trim().ifBlank { null }
        } catch (se: SecurityException) {
            Log.w(TAG, "Missing permission while reading SSID", se)
            null
        } catch (e: Exception) {
            Log.e(TAG, "currentSsid failed", e)
            null
        }
    }

    private fun isLocationEnabled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                lm.isLocationEnabled
            } else {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.LOCATION_MODE
                ) != Settings.Secure.LOCATION_MODE_OFF
            }
        } catch (_: Exception) {
            true
        }
    }

    companion object {
        private const val TAG = "WifiTriggerReceiver"
    }
}
