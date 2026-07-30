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
import androidx.core.content.edit
import java.util.Calendar

// Daily app-launch counters independent from attempt-limit enforcement.
object AppLaunchCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "app_launch_count_" // app_launch_count_yyyymmdd_pkg

    @Synchronized
    fun incrementToday(context: Context, packageName: String): Int {
        if (packageName.isBlank()) {
            return 0
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(todayYmd(), packageName)
        val next = readInt(prefs, key) + 1
        prefs.edit { putInt(key, next) }
        return next
    }

    // Uses max rather than addition so repeated UsageStats backfills cannot double-count.
    @Synchronized
    fun mergeForDay(context: Context, ymd: Int, packageName: String, count: Int) {
        if (packageName.isBlank() || count <= 0) {
            return
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(ymd, packageName)
        val current = readInt(prefs, key)
        if (count > current) {
            prefs.edit { putInt(key, count) }
        }
    }

    fun getMapForDateRange(context: Context, startMs: Long, endMs: Long): Map<String, Int> {
        if (endMs <= startMs) {
            return emptyMap()
        }
        val wantedDays = linkedSetOf<Int>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= endMs) {
            wantedDays += ymd(cal)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = linkedMapOf<String, Int>()
        prefs.all.keys.forEach { key ->
            if (!key.startsWith(PREFIX)) {
                return@forEach
            }
            val rest = key.removePrefix(PREFIX)
            if (rest.length < 10) {
                return@forEach
            }
            val day = rest.take(8).toIntOrNull()
            if (day == null) {
                return@forEach
            }
            if (day !in wantedDays || rest.getOrNull(8) != '_') {
                return@forEach
            }
            val pkg = rest.substring(9)
            if (pkg.isBlank()) {
                return@forEach
            }
            val value = readInt(prefs, key)
            if (value > 0) {
                out[pkg] = (out[pkg] ?: 0) + value
            }
        }
        return out
    }

    fun getForDateRange(context: Context, packageName: String, startMs: Long, endMs: Long): Int =
        getMapForDateRange(context, startMs, endMs)[packageName] ?: 0

    fun getTotalToday(context: Context): Int = totalForDays(context, setOf(todayYmd()))

    fun getTotalForLastNDays(context: Context, days: Int): Int {
        if (days <= 0) return 0
        val wantedDays = linkedSetOf<Int>()
        val calendar = Calendar.getInstance()
        repeat(days) {
            wantedDays += ymd(calendar)
            calendar.add(Calendar.DAY_OF_YEAR, -1)
        }
        return totalForDays(context, wantedDays)
    }

    fun getTotalForMonth(context: Context, year: Int, month1Based: Int): Int {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val month = calendar.get(Calendar.MONTH)
        val wantedDays = linkedSetOf<Int>()
        while (calendar.get(Calendar.MONTH) == month) {
            wantedDays += ymd(calendar)
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return totalForDays(context, wantedDays)
    }

    fun getTotalForYear(context: Context, year: Int): Int {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val wantedDays = linkedSetOf<Int>()
        while (calendar.get(Calendar.YEAR) == year) {
            wantedDays += ymd(calendar)
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        return totalForDays(context, wantedDays)
    }

    fun getTotalOverall(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var total = 0L
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(PREFIX)) total += numericValue(value)
        }
        return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun totalForDays(context: Context, wantedDays: Set<Int>): Int {
        if (wantedDays.isEmpty()) return 0
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var total = 0L
        prefs.all.forEach { (key, value) ->
            val day = dayFromKey(key) ?: return@forEach
            if (day in wantedDays) total += numericValue(value)
        }
        return total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }

    private fun dayFromKey(key: String): Int? {
        if (!key.startsWith(PREFIX)) return null
        val separator = key.indexOf('_', startIndex = PREFIX.length)
        if (separator <= PREFIX.length) return null
        return key.substring(PREFIX.length, separator).toIntOrNull()
    }

    private fun numericValue(value: Any?): Long = when (value) {
        is Int -> value.toLong()
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLongOrNull() ?: 0L
        else -> 0L
    }.coerceAtLeast(0L)

    private fun key(ymd: Int, packageName: String): String = "$PREFIX${ymd}_$packageName"

    private fun readInt(prefs: android.content.SharedPreferences, key: String): Int = when (val raw = prefs.all[key]) {
        is Int -> raw
        is Long -> raw.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        is Number -> raw.toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        is String -> raw.toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        else -> 0
    }

    private fun todayYmd(): Int = ymd(Calendar.getInstance())

    private fun ymd(cal: Calendar): Int =
        cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
}
