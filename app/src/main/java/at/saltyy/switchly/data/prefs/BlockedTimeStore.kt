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

// Persists and retrieves blocked time state.
object BlockedTimeStore {
    private const val PREFS = "switchly_prefs"
    // blocked_ms_yyyymmdd_pkg  (ymd = Int like 20251223)
    private const val PREFIX_DAY = "blocked_ms_" // + yyyymmdd + "_" + pkg

    // Buffer frequent increments to avoid high-frequency SharedPreferences writes.
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_PENDING_KEYS = 32

    private val lock = Any()
    private val pending = HashMap<String, Long>()
    @Volatile private var lastFlushAtMs: Long = 0L

    private fun dayKey(ymd: Int, pkg: String): String = PREFIX_DAY + ymd.toString() + "_" + pkg

    fun addBlockedMsToday(ctx: Context, pkg: String, deltaMs: Long) {
        if (deltaMs <= 0L || pkg.isBlank()) {
            return
        }
        val ymd = todayYmdInt()
        val k = dayKey(ymd, pkg)

        val now = System.currentTimeMillis()
        var shouldFlush = false

        synchronized(lock) {
            pending[k] = (pending[k] ?: 0L) + deltaMs
            shouldFlush = (now - lastFlushAtMs) >= FLUSH_INTERVAL_MS || pending.size >= MAX_PENDING_KEYS
        }

        if (shouldFlush) flush(ctx)
    }

    fun getBlockedMsToday(ctx: Context, pkg: String): Long {
        if (pkg.isBlank()) {
            return 0L
        }
        val ymd = todayYmdInt()
        val key = dayKey(ymd, pkg)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val persisted = sp.getLong(key, 0L)
        val buffered = synchronized(lock) { pending[key] ?: 0L }
        return persisted + buffered
    }

    fun getBlockedMsForLastNDays(ctx: Context, pkg: String, days: Int): Long {
        if (pkg.isBlank() || days <= 0) {
            return 0L
        }

        flush(ctx)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()

        var sum = 0L
        for (i in 0 until days) {
            val ymd = ymdInt(cal)
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getBlockedMsForMonth(ctx: Context, pkg: String, year: Int, month1Based: Int): Long {
        if (pkg.isBlank()) {
            return 0L
        }

        flush(ctx)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1Based - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12) // avoid DST weirdness
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val targetMonth = cal.get(Calendar.MONTH)
        var sum = 0L
        while (cal.get(Calendar.MONTH) == targetMonth) {
            val ymd = ymdInt(cal)
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return sum
    }

    fun getBlockedMsForYear(ctx: Context, pkg: String, year: Int): Long {
        if (pkg.isBlank()) {
            return 0L
        }

        flush(ctx)

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
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return sum
    }

    /**
     * Per-day blocked-time totals for the last N days (inclusive of today),
     * ordered oldest -> today. Entry index [days - 1] is always today.
     * Used by the Foqos-style activity heatmap on Home.
     */
    fun getDayTotalsMs(ctx: Context, days: Int): LongArray {
        val result = LongArray(days.coerceAtLeast(1))
        if (days <= 0) {
            return result
        }

        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Map ymd -> index in result.
        val indexByYmd = HashMap<Int, Int>(days)
        val calWalk = cal.clone() as Calendar
        calWalk.add(Calendar.DAY_OF_YEAR, -(days - 1))
        for (i in 0 until days) {
            indexByYmd[ymdInt(calWalk)] = i
            calWalk.add(Calendar.DAY_OF_YEAR, 1)
        }

        for ((k, vAny) in sp.all) {
            if (!k.startsWith(PREFIX_DAY)) continue
            // Key: blocked_ms_yyyymmdd_pkg
            val ymdPart = k.removePrefix(PREFIX_DAY).substringBefore('_')
            val idx = indexByYmd[ymdPart.toIntOrNull() ?: continue] ?: continue
            val v = when (vAny) {
                is Long -> vAny
                is Int -> vAny.toLong()
                is Number -> vAny.toLong()
                else -> 0L
            }
            if (v > 0L) result[idx] += v
        }
        return result
    }

    /**
     * Sums all persisted blocked_ms entries for the given package across *all* days.
     * This is used for the "Overall" stats range.
     */
    fun getBlockedMsOverall(ctx: Context, pkg: String): Long {
        if (pkg.isBlank()) {
            return 0L
        }
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val suffix = "_" + pkg

        var sum = 0L
        for ((k, vAny) in sp.all) {
            if (!k.startsWith(PREFIX_DAY) || !k.endsWith(suffix)) continue
            val v = when (vAny) {
                is Long -> vAny
                is Int -> vAny.toLong()
                is Number -> vAny.toLong()
                else -> 0L
            }
            if (v > 0L) sum += v
        }
        return sum
    }

    /**
     * Forces a flush of buffered deltas to SharedPreferences.
     * Safe to call frequently; does nothing if no pending values exist.
     */
    fun flush(ctx: Context) {
        val toWrite: Map<String, Long>
        synchronized(lock) {
            if (pending.isEmpty()) {
                return
            }
            toWrite = HashMap(pending)
            pending.clear()
            lastFlushAtMs = System.currentTimeMillis()
        }

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val merged = HashMap<String, Long>(toWrite.size)
        for ((k, delta) in toWrite) {
            val cur = sp.getLong(k, 0L)
            merged[k] = cur + delta
        }

        sp.edit {
            for ((k, v) in merged) {
                putLong(k, v)
            }
        }
    }

    // helpers
    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
