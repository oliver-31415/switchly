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

package at.saltyy.switchly.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar

/**
 * Counts how often a specific app was blocked.
 */
object BlockCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "blocked_count_" // blocked_count_yyyymmdd_pkg

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(ymd: Int, pkg: String): String = PREFIX + ymd + "_" + pkg

    fun getToday(context: Context, pkg: String): Int {
        if (pkg.isBlank()) return 0
        return readIntCompat(prefs(context), key(todayYmdInt(), pkg))
    }

    fun getForLastNDays(context: Context, pkg: String, days: Int): Int {
        if (pkg.isBlank() || days <= 0) return 0

        val sharedPreferences = prefs(context)
        val calendar = Calendar.getInstance()
        var sum = 0

        repeat(days) {
            sum += readIntCompat(sharedPreferences, key(ymdInt(calendar), pkg))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        return sum
    }

    fun getForMonth(context: Context, pkg: String, year: Int, month1Based: Int): Int {
        if (pkg.isBlank()) return 0

        val sharedPreferences = prefs(context)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetMonth = calendar.get(Calendar.MONTH)
        var sum = 0

        while (calendar.get(Calendar.MONTH) == targetMonth) {
            sum += readIntCompat(sharedPreferences, key(ymdInt(calendar), pkg))
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        return sum
    }

    fun getForYear(context: Context, pkg: String, year: Int): Int {
        if (pkg.isBlank()) return 0

        val sharedPreferences = prefs(context)
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var sum = 0

        while (calendar.get(Calendar.YEAR) == year) {
            sum += readIntCompat(sharedPreferences, key(ymdInt(calendar), pkg))
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return sum
    }

    fun getOverall(context: Context, pkg: String): Int {
        if (pkg.isBlank()) return 0

        var sum = 0L
        val suffix = "_" + pkg
        for ((key, value) in prefs(context).all) {
            if (!key.startsWith(PREFIX) || !key.endsWith(suffix)) continue
            sum += when (value) {
                is Int -> value.toLong()
                is Long -> value
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun incrementToday(context: Context, pkg: String, delta: Int = 1) {
        if (pkg.isBlank() || delta <= 0) return

        val sharedPreferences = prefs(context)
        val key = key(todayYmdInt(), pkg)
        val current = readIntCompat(sharedPreferences, key)
        sharedPreferences.edit { putLong(key, (current + delta).toLong()) }
    }

    private fun readIntCompat(sharedPreferences: SharedPreferences, key: String): Int {
        return when (val value = sharedPreferences.all[key]) {
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toLongOrNull()?.toInt() ?: value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(calendar: Calendar): Int {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return (year * 10000) + (month * 100) + day
    }
}
