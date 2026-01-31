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
