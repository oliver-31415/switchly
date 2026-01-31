package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit
import java.util.Calendar

/**
 * Tracks whether a usage-limited app has reached its limit for today.
 * Once reached, we consider the app "blocked" until the next day reset (or until the user uses emergency bypass / temporary allow).
 */
object LimitReachedStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "limit_reached_" // limit_reached_yyyymmdd_pkg

    private fun key(ymd: Int, pkg: String): String = PREFIX + ymd.toString() + "_" + pkg

    fun markReachedToday(ctx: Context, pkg: String) {
        if (pkg.isBlank()) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putBoolean(key(todayYmdInt(), pkg), true) }
    }

    fun isReachedToday(ctx: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(key(todayYmdInt(), pkg), false)
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
