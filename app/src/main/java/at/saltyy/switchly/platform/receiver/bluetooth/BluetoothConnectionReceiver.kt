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

import at.saltyy.switchly.BuildConfig
import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver

/**
 * Receives Bluetooth connection/disconnection events, caches the latest state (name/address/connected) and forwards them to ScheduleReceiver as a generic tick.
 * Robust across Android versions and permission-safe.
 */
class BluetoothConnectionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        // Extract BluetoothDevice extra (API-level compatible)
        val device: BluetoothDevice? = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

        // Some profile broadcasts may not always include EXTRA_DEVICE reliably.
        // We still handle them if present; otherwise ignore safely.
        if (device == null) {
            Log.w(TAG, "Bluetooth event without device -> ignored (action=$action)")
            return
        }

        val isConnected: Boolean? = when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> false

            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> true
                    BluetoothProfile.STATE_DISCONNECTED -> false
                    else -> null
                }
            }

            else -> {
                if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring unrelated Bluetooth action: $action")
                return
            }
        }

        if (isConnected == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Bluetooth event ambiguous -> ignoring ($action)")
            return
        }

        val addr = resolveDeviceAddrSafe(device)
        val name = resolveDeviceNameSafe(context, device, fallback = addr)

        // IMPORTANT: use SAME PREF + SAME KEYS as ScheduleReceiver reads
        cacheBtState(context, name = name, addr = addr, connected = isConnected)

        if (BuildConfig.DEBUG) Log.d(TAG, "BT event: name='$name' addr='$addr' connected=$isConnected action=$action")

        // Forward event to ScheduleReceiver
        context.sendBroadcast(
            Intent(context, ScheduleReceiver::class.java).apply {
                this.action = ScheduleReceiver.ACTION_TICK
                putExtra("eventBtName", name)
                putExtra("eventBtAddr", addr)
                putExtra("eventBtConnected", isConnected)
                putExtra("bt_reason", action)
            }
        )
    }

    private fun cacheBtState(ctx: Context, name: String?, addr: String?, connected: Boolean) {
        ctx.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE).edit {
            putString(KEY_BT_NAME, name)
            putString(KEY_BT_ADDR, addr)
            putBoolean(KEY_BT_CONNECTED, connected)
        }
    }

    private fun resolveDeviceAddrSafe(device: BluetoothDevice): String {
        return try {
            device.address ?: "unknown"
        } catch (_: SecurityException) {
            "unknown"
        }
    }

    // device.name requires BLUETOOTH_CONNECT permission on Android 12+
    private fun resolveDeviceNameSafe(ctx: Context, device: BluetoothDevice, fallback: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return try {
                device.name ?: fallback
            } catch (_: SecurityException) {
                fallback
            }
        }

        val granted = ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) return fallback

        return try {
            device.name ?: fallback
        } catch (_: SecurityException) {
            fallback
        }
    }

    companion object {
        private const val TAG = "BluetoothConnectionRx"
        private const val PREFS_BT = "switchly_bt_cache"
        private const val KEY_BT_NAME = "last_bt_name"
        private const val KEY_BT_ADDR = "last_bt_addr"
        private const val KEY_BT_CONNECTED = "last_bt_connected"
    }
}
