package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

object BarcodeScanCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "barcode_scan_count_"

    private fun key(ymd: Int): String = PREFIX + ymd.toString()

    private fun readIntCompat(sp: android.content.SharedPreferences, key: String): Int {
        val v = sp.all[key] ?: return 0
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Number -> v.toInt()
            is String -> v.toLongOrNull()?.toInt() ?: v.toIntOrNull() ?: 0
            else -> 0
        }
    }

    fun incrementToday(ctx: Context, delta: Int = 1) {
        if (delta <= 0) return
        val ymd = todayYmdInt()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(ymd)
        val cur = readIntCompat(sp, k)
        sp.edit { putLong(k, (cur + delta).toLong()) }
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
