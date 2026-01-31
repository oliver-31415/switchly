package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

object EmergencyUnlockCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "emergency_unlock_count_" // emergency_unlock_count_yyyymmdd

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

    fun getToday(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return readIntCompat(sp, key(todayYmdInt()))
    }

    fun getForLastNDays(ctx: Context, days: Int): Int {
        if (days <= 0) return 0
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        var sum = 0
        for (i in 0 until days) {
            sum += readIntCompat(sp, key(ymdInt(cal)))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getForMonth(ctx: Context, year: Int, month1Based: Int): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetMonth = cal.get(Calendar.MONTH)
        var sum = 0
        while (cal.get(Calendar.MONTH) == targetMonth) {
            sum += readIntCompat(sp, key(ymdInt(cal)))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return sum
    }

    fun getForYear(ctx: Context, year: Int): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var sum = 0
        while (cal.get(Calendar.YEAR) == year) {
            sum += readIntCompat(sp, key(ymdInt(cal)))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum
    }

    fun getOverall(ctx: Context): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0L
        for ((k, v) in sp.all) {
            if (!k.startsWith(PREFIX)) continue
            sum += when (v) {
                is Int -> v.toLong()
                is Long -> v
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
