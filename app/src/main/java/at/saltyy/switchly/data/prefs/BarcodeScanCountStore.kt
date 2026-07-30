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

// Persists and retrieves barcode scan count state.
object BarcodeScanCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "barcode_scan_count_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(ymd: Int): String = PREFIX + ymd

    fun getToday(context: Context): Int {
        return readIntCompat(prefs(context), key(todayYmdInt()))
    }

    fun getForLastNDays(context: Context, days: Int): Int {
        if (days <= 0) {
            return 0
        }

        val sharedPreferences = prefs(context)
        val calendar = Calendar.getInstance()
        var sum = 0L

        repeat(days) {
            sum += readLongCompat(sharedPreferences, key(ymdInt(calendar)))
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }

        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getForMonth(context: Context, year: Int, month1Based: Int): Int {
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
        var sum = 0L

        while (calendar.get(Calendar.MONTH) == targetMonth) {
            sum += readLongCompat(sharedPreferences, key(ymdInt(calendar)))
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }

        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getForYear(context: Context, year: Int): Int {
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
        var sum = 0L

        while (calendar.get(Calendar.YEAR) == year) {
            sum += readLongCompat(sharedPreferences, key(ymdInt(calendar)))
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getOverall(context: Context): Int {
        var sum = 0L
        for ((storedKey, storedValue) in prefs(context).all) {
            if (!storedKey.startsWith(PREFIX)) {
                continue
            }
            sum += valueAsLong(storedValue)
        }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    @Synchronized
    fun incrementToday(context: Context, delta: Int = 1) {
        if (delta <= 0) {
            return
        }

        val sharedPreferences = prefs(context)
        val key = key(todayYmdInt())
        val current = readLongCompat(sharedPreferences, key)
        sharedPreferences.edit { putLong(key, current + delta.toLong()) }
    }

    private fun readIntCompat(sharedPreferences: SharedPreferences, key: String): Int {
        return readLongCompat(sharedPreferences, key)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun readLongCompat(sharedPreferences: SharedPreferences, key: String): Long {
        return valueAsLong(sharedPreferences.all[key])
    }

    private fun valueAsLong(value: Any?): Long {
        return when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
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
