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

/**
 * Tracks how long Switchly's watcher/service was running.
 * We keep a "currently running since" timestamp, and a per-day accumulated runtime.
 */
object SwitchlyRuntimeStore {
    private const val PREFS = "switchly_prefs"

    private const val KEY_RUNNING_SINCE = "switchly_runtime_running_since"
    private const val PREFIX_DAY = "switchly_runtime_ms_" // switchly_runtime_ms_yyyymmdd

    private fun dayKey(ymd: Int): String = PREFIX_DAY + ymd.toString()

    fun markStarted(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getLong(KEY_RUNNING_SINCE, 0L) > 0L) return // already running
        sp.edit { putLong(KEY_RUNNING_SINCE, System.currentTimeMillis()) }
    }

    fun markStopped(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = sp.getLong(KEY_RUNNING_SINCE, 0L)
        if (since <= 0L) return
        sp.edit { putLong(KEY_RUNNING_SINCE, 0L) }

        val now = System.currentTimeMillis()
        addRuntimeRange(ctx, since, now)
    }

    /**
     * Adds runtime spanning potentially multiple days by splitting at local midnights.
     * This prevents attributing "overnight" runtime entirely to the stop day.
     */
    private fun addRuntimeRange(ctx: Context, startMs: Long, endMs: Long) {
        if (endMs <= startMs) return

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var curStart = startMs

        while (curStart < endMs) {
            val cal = Calendar.getInstance().apply { timeInMillis = curStart }
            val ymd = ymdInt(cal)

            // Next midnight
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val nextMidnight = cal.timeInMillis

            val sliceEnd = minOf(endMs, nextMidnight)
            val delta = (sliceEnd - curStart).coerceAtLeast(0L)
            if (delta > 0L) {
                val k = dayKey(ymd)
                val cur = sp.getLong(k, 0L)
                sp.edit { putLong(k, cur + delta) }
            }

            curStart = sliceEnd
        }
    }

    // Returns runtime for today. If service is currently running, includes elapsed time so far.
    fun getRuntimeMsToday(ctx: Context): Long {
        val ymd = todayYmdInt()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = sp.getLong(dayKey(ymd), 0L)
        val since = sp.getLong(KEY_RUNNING_SINCE, 0L)

        if (since <= 0L) return base

        val now = System.currentTimeMillis()
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // If the service has been running since before today's midnight, roll over the previous part into the correct day buckets and continue counting from midnight.
        if (since < todayStart) {
            addRuntimeRange(ctx, since, todayStart)
            sp.edit { putLong(KEY_RUNNING_SINCE, todayStart) }
            return base + (now - todayStart).coerceAtLeast(0L)
        }

        return base + (now - since).coerceAtLeast(0L)
    }

    fun getRuntimeMsForLastNDays(ctx: Context, days: Int): Long {
        if (days <= 0) return 0L
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        var sum = 0L
        for (i in 0 until days) {
            sum += sp.getLong(dayKey(ymdInt(cal)), 0L)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        // include current day running time if applicable
        return if (days >= 1) {
            // avoid double counting today: replace today's stored value with live value
            val todayStored = sp.getLong(dayKey(todayYmdInt()), 0L)
            sum - todayStored + getRuntimeMsToday(ctx)
        } else sum
    }

    fun getRuntimeMsForMonth(ctx: Context, year: Int, month1Based: Int): Long {
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
            sum += sp.getLong(dayKey(ymd), 0L)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        // include live today if in target month/year
        val now = Calendar.getInstance()
        if (now.get(Calendar.YEAR) == year && now.get(Calendar.MONTH) == targetMonth) {
            val today = todayYmdInt()
            val todayStored = sp.getLong(dayKey(today), 0L)
            sum = sum - todayStored + getRuntimeMsToday(ctx)
        }
        return sum
    }

    fun getRuntimeMsForYear(ctx: Context, year: Int): Long {
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
            sum += sp.getLong(dayKey(ymdInt(cal)), 0L)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        // include live today if in target year
        val now = Calendar.getInstance()
        if (now.get(Calendar.YEAR) == year) {
            val today = todayYmdInt()
            val todayStored = sp.getLong(dayKey(today), 0L)
            sum = sum - todayStored + getRuntimeMsToday(ctx)
        }
        return sum
    }

    /**
     * Sums all persisted per-day runtime values across all days.
     * Includes live "running since" time (like getRuntimeMsToday) by replacing today's stored value.
     */
    fun getRuntimeMsOverall(ctx: Context): Long {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0L
        for ((k, vAny) in sp.all) {
            if (!k.startsWith(PREFIX_DAY)) continue
            val v = when (vAny) {
                is Long -> vAny
                is Int -> vAny.toLong()
                is Number -> vAny.toLong()
                else -> 0L
            }
            if (v > 0L) sum += v
        }

        // Replace today's stored runtime with the live runtime (includes running_since).
        val todayKey = dayKey(todayYmdInt())
        val todayStored = sp.getLong(todayKey, 0L)
        return (sum - todayStored).coerceAtLeast(0L) + getRuntimeMsToday(ctx)
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
