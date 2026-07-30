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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import at.saltyy.switchly.data.prefs.NotificationBlockStore

/**
 * Shared permission and reliability checks used by onboarding and the full Permissions screen.
 * Keeping these checks in one place prevents the two screens from reporting different setup states.
 */
object PermissionSetupChecks {

    fun notificationsReady(context: Context, requireListenerAccess: Boolean): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            return false
        }
        return !requireListenerAccess || NotificationBlockStore.hasListenerAccess(context)
    }

    fun batteryOptimizationReady(context: Context): Boolean {
        return BatteryOptimizationCompat.isEffectivelyOk(context)
    }

    fun cameraReady(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.CAMERA)
    }

    fun hasFineLocation(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasCoarseLocation(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    fun hasBackgroundLocation(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
        return hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    fun hasNearbyWifiDevices(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        return hasPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES)
    }

    fun wifiAndLocationTriggersReady(context: Context): Boolean {
        return hasFineLocation(context) &&
            hasBackgroundLocation(context) &&
            hasNearbyWifiDevices(context)
    }

    fun bluetoothTriggersReady(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun scheduleTriggerPermissionsReady(context: Context): Boolean {
        return wifiAndLocationTriggersReady(context) && bluetoothTriggersReady(context)
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
