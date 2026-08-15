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

// Counts how often the user tried to open an app while it was blocked.
object BlockAttemptStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "blocked_attempt_" // blocked_attempt_yyyymmdd_pkg

    private fun key(ymd: Int, pkg: String): String = PREFIX + ymd.toString() + "_" + pkg

    private fun readIntCompat(sp: android.content.SharedPreferences, key: String): Int {
        val v = sp.all[key] ?: return 0
        return when (v) {
            is Int -> v
            is Long -> v.toInt()
            is Float -> v.toInt()
            is Double -> v.toInt()
            is Number -> v.toInt()
            is String -> v.toLongOrNull()?.toInt() ?: v.toIntOrNull() ?: 0
            else -> 0
        }
    }

    fun incrementToday(ctx: Context, pkg: String, delta: Int = 1) {
        if (pkg.isBlank() || delta <= 0) {
            return
        }
        val ymd = todayYmdInt()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(ymd, pkg)
        val cur = readIntCompat(sp, k)
        sp.edit { putLong(k, (cur + delta).toLong()) }
    }

    fun getToday(ctx: Context, pkg: String): Int {
        if (pkg.isBlank()) {
            return 0
        }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return readIntCompat(sp, key(todayYmdInt(), pkg))
    }

    fun getForLastNDays(ctx: Context, pkg: String, days: Int): Int {
        if (pkg.isBlank() || days <= 0) {
            return 0
        }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        var sum = 0
        for (i in 0 until days) {
            sum += readIntCompat(sp, key(ymdInt(cal), pkg))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getForMonth(ctx: Context, pkg: String, year: Int, month1Based: Int): Int {
        if (pkg.isBlank()) {
            return 0
        }
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
            sum += readIntCompat(sp, key(ymdInt(cal), pkg))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return sum
    }

    fun getForYear(ctx: Context, pkg: String, year: Int): Int {
        if (pkg.isBlank()) {
            return 0
        }
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
            sum += readIntCompat(sp, key(ymdInt(cal), pkg))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum
    }

    fun getForCurrentWeek(ctx: Context, pkg: String): Int {
        if (pkg.isBlank()) {
            return 0
        }
        val now = Calendar.getInstance()
        val currentDow = now.get(Calendar.DAY_OF_WEEK)
        val firstDow = now.firstDayOfWeek
        val diff = (7 + (currentDow - firstDow)) % 7
        return getForLastNDays(ctx, pkg, diff + 1)
    }

    fun getForCurrentMonth(ctx: Context, pkg: String): Int {
        val now = Calendar.getInstance()
        return getForMonth(ctx, pkg, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)
    }

    fun getForCurrentYear(ctx: Context, pkg: String): Int {
        val now = Calendar.getInstance()
        return getForYear(ctx, pkg, now.get(Calendar.YEAR))
    }

    fun getForDateRange(ctx: Context, pkg: String, startMs: Long, endMs: Long): Int {
        if (pkg.isBlank() || endMs < startMs) {
            return 0
        }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endDay = Calendar.getInstance().apply {
            timeInMillis = endMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var total = 0L
        while (!day.after(endDay)) {
            total += readIntCompat(sp, key(ymdInt(day), pkg)).toLong()
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getMapForDateRange(
        ctx: Context,
        startMs: Long,
        endMs: Long,
    ): Map<String, Int> {
        if (endMs < startMs) {
            return emptyMap()
        }
        val validDays = ymdValuesForRange(startMs, endMs)
        if (validDays.isEmpty()) {
            return emptyMap()
        }

        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val totals = linkedMapOf<String, Long>()
        for ((storedKey, storedValue) in prefs.all) {
            if (!storedKey.startsWith(PREFIX)) {
                continue
            }
            val suffix = storedKey.removePrefix(PREFIX)
            if (suffix.length <= 9 || suffix[8] != '_') {
                continue
            }
            val ymd = suffix.take(8).toIntOrNull() ?: continue
            if (ymd !in validDays) {
                continue
            }
            val packageName = suffix.substring(9)
            if (packageName.isBlank()) {
                continue
            }
            totals[packageName] = (totals[packageName] ?: 0L) + preferenceNumber(storedValue)
        }
        return totals.mapValues { (_, value) ->
            value.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }
    }

    fun getOverall(ctx: Context, pkg: String): Int {
        if (pkg.isBlank()) {
            return 0
        }
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0L
        val suffix = "_" + pkg
        for ((k, v) in sp.all) {
            if (k.startsWith(PREFIX) && k.endsWith(suffix)) {
                sum += when (v) {
                    is Int -> v.toLong()
                    is Long -> v
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull() ?: 0L
                    else -> 0L
                }
            }
        }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getTodayTotal(ctx: Context): Int = getForYmdTotal(ctx, todayYmdInt())

    fun getForLastNDaysTotal(ctx: Context, days: Int): Int {
        if (days <= 0) {
            return 0
        }
        val cal = Calendar.getInstance()
        var sum = 0
        for (i in 0 until days) {
            sum += getForYmdTotal(ctx, ymdInt(cal))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getForMonthTotal(ctx: Context, year: Int, month1Based: Int): Int {
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
        var sum = 0L
        while (cal.get(Calendar.MONTH) == targetMonth) {
            val ymd = ymdInt(cal)
            sum += sumForYmd(sp, ymd)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getForYearTotal(ctx: Context, year: Int): Int {
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
        var sum = 0L
        while (cal.get(Calendar.YEAR) == year) {
            val ymd = ymdInt(cal)
            sum += sumForYmd(sp, ymd)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun getOverallTotal(ctx: Context): Int {
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

    private fun getForYmdTotal(ctx: Context, ymd: Int): Int {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sumForYmd(sp, ymd).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun sumForYmd(sp: android.content.SharedPreferences, ymd: Int): Long {
        var sum = 0L
        val ymdStr = ymd.toString()
        // Key format: blocked_attempt_yyyymmdd_pkg
        for ((k, v) in sp.all) {
            if (!k.startsWith(PREFIX + ymdStr + "_")) continue
            sum += when (v) {
                is Int -> v.toLong()
                is Long -> v
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
        }
        return sum
    }

    private fun ymdValuesForRange(startMs: Long, endMs: Long): Set<Int> {
        val day = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endDay = Calendar.getInstance().apply {
            timeInMillis = endMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return buildSet {
            while (!day.after(endDay)) {
                add(ymdInt(day))
                day.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    private fun preferenceNumber(value: Any?): Long {
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
