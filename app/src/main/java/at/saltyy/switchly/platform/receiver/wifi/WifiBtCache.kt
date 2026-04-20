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

import android.content.Context
import androidx.core.content.edit

object WifiBtCache {

    private const val PREF = "switchly_triggers_cache"
    private const val K_WIFI_SSID = "wifi_ssid"
    private const val K_WIFI_BSSID = "wifi_bssid"
    private const val K_WIFI_TS = "wifi_ts"
    private const val K_BT_NAME = "bt_name"
    private const val K_BT_ADDR = "bt_addr"
    private const val K_BT_TS = "bt_ts"

    data class Wifi(val ssid: String?, val bssid: String?, val ts: Long)
    data class Bt(val name: String?, val addr: String?, val ts: Long)

    fun setWifi(ctx: Context, ssid: String?, bssid: String?) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
            putString(K_WIFI_SSID, ssid)
            putString(K_WIFI_BSSID, bssid)
            putLong(K_WIFI_TS, System.currentTimeMillis())
        }
    }

    fun getWifi(ctx: Context): Wifi {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Wifi(
            p.getString(K_WIFI_SSID, null),
            p.getString(K_WIFI_BSSID, null),
            p.getLong(K_WIFI_TS, 0L)
        )
    }

    fun clearWifi(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
            remove(K_WIFI_SSID)
            remove(K_WIFI_BSSID)
            remove(K_WIFI_TS)
        }
    }

    fun setBt(ctx: Context, name: String?, addr: String?) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
            putString(K_BT_NAME, name)
            putString(K_BT_ADDR, addr)
            putLong(K_BT_TS, System.currentTimeMillis())
        }
    }

    fun getBt(ctx: Context): Bt {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Bt(
            p.getString(K_BT_NAME, null),
            p.getString(K_BT_ADDR, null),
            p.getLong(K_BT_TS, 0L)
        )
    }

    fun clearBt(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit {
            remove(K_BT_NAME)
            remove(K_BT_ADDR)
            remove(K_BT_TS)
        }
    }
}
