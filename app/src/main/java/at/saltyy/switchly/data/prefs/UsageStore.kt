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

// Persists and retrieves usage state.
object UsageStore {
    data class MonthBucket(val year: Int, val month1Based: Int, val totalMs: Long)

    private const val PREFS = "switchly_prefs"

    // usage_day_yyyymmdd_pkg
    private const val PREFIX_DAY = "usage_day_" // + yyyymmdd + "_" + pkg
    private const val KEY_SANITIZE_VERSION = "usage_sanitize_version"
    private const val CURRENT_SANITIZE_VERSION = 1

    // Buffer frequent increments to avoid high-frequency SharedPreferences writes.
    // We flush at most every FLUSH_INTERVAL_MS or if too many keys are pending.
    private const val FLUSH_INTERVAL_MS = 10_000L
    private const val MAX_PENDING_KEYS = 32

    private val lock = Any()
    private val pending = HashMap<String, Long>()
    @Volatile private var lastFlushAtMs: Long = 0L

    fun addUsageMsToday(ctx: Context, pkg: String, deltaMs: Long) {
        if (pkg.isBlank()) {
            return
        }
        if (deltaMs <= 0L) {
            return
        }

        val ymd = todayYmdInt()
        val key = dayKey(ymd, pkg)

        val now = System.currentTimeMillis()
        var shouldFlush = false

        synchronized(lock) {
            pending[key] = (pending[key] ?: 0L) + deltaMs
            shouldFlush = (now - lastFlushAtMs) >= FLUSH_INTERVAL_MS || pending.size >= MAX_PENDING_KEYS
        }

        if (shouldFlush) flush(ctx)
    }

    fun getUsageMsToday(ctx: Context, pkg: String): Long {
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

    fun getUsageMsMapToday(ctx: Context): Map<String, Long> {
        flush(ctx)
        val ymd = todayYmdInt()
        val prefix = PREFIX_DAY + ymd.toString() + "_"
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = linkedMapOf<String, Long>()
        for ((k, v) in sp.all) {
            if (!k.startsWith(prefix)) continue
            val pkg = k.removePrefix(prefix).trim()
            val ms = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            if (pkg.isNotBlank() && ms > 0L) {
                out[pkg] = (out[pkg] ?: 0L) + ms
            }
        }
        return out
    }

    // Explicit setter used for "clear today usage" when removing limit.
    fun setUsageMsToday(ctx: Context, pkg: String, ms: Long) {
        if (pkg.isBlank()) {
            return
        }
        // Ensure buffered deltas are persisted before overwriting.
        flush(ctx)
        val ymd = todayYmdInt()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        sp.edit {
            putLong(dayKey(ymd, pkg), ms.coerceAtLeast(0L))
        }
    }

    fun getUsageMsForDay(ctx: Context, ymd: Int, pkg: String): Long {
        if (pkg.isBlank()) {
            return 0L
        }
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getLong(dayKey(ymd, pkg), 0L).coerceAtLeast(0L)
    }

    fun mergeUsageMsForDay(ctx: Context, ymd: Int, pkg: String, ms: Long) {
        if (pkg.isBlank() || ms <= 0L) {
            return
        }
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = dayKey(ymd, pkg)
        val cur = sp.getLong(k, 0L).coerceAtLeast(0L)
        val merged = maxOf(cur, ms.coerceAtLeast(0L))
        if (merged == cur) {
            return
        }
        sp.edit { putLong(k, merged) }
    }

    fun hasAnyUsageData(ctx: Context): Boolean {
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.all.keys.any { it.startsWith(PREFIX_DAY) }
    }

    fun getUsageMsForLastNDays(ctx: Context, pkg: String, days: Int): Long {
        if (pkg.isBlank()) {
            return 0L
        }
        if (days <= 0) {
            return 0L
        }

        // Stats screen: flush once so the most recent buffered data is included.
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

    /**
     * Sum usage for a range of days in the past.
     * @param startOffsetDays 0 = include today, 1 = start yesterday, 7 = start 7 days ago...
     * @param days number of days to sum (must be > 0)
     */
    fun getUsageMsForPastRange(ctx: Context, pkg: String, startOffsetDays: Int, days: Int): Long {
        if (pkg.isBlank()) {
            return 0L
        }
        if (days <= 0) {
            return 0L
        }

        flush(ctx)

        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -startOffsetDays)

        var sum = 0L
        for (i in 0 until days) {
            val ymd = ymdInt(cal)
            sum += sp.getLong(dayKey(ymd, pkg), 0L)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return sum
    }

    fun getUsageMsForMonth(ctx: Context, pkg: String, year: Int, month1Based: Int): Long {
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

    fun getUsageMsForYear(ctx: Context, pkg: String, year: Int): Long {
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

    fun getTrackedPackages(ctx: Context): Set<String> {
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = linkedSetOf<String>()
        for ((k, v) in sp.all) {
            if (!k.startsWith(PREFIX_DAY)) continue
            val rest = k.removePrefix(PREFIX_DAY)
            val idx = rest.indexOf('_')
            if (idx <= 0 || idx >= rest.length - 1) continue
            val pkg = rest.substring(idx + 1).trim()
            val ms = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            if (pkg.isNotBlank() && ms > 0L) out += pkg
        }
        return out
    }

    fun getUsageMsMapForDay(ctx: Context, ymd: Int): Map<String, Long> {
        flush(ctx)
        return getUsageMsMapMatching(ctx) { day, _ -> day == ymd }
    }

    fun getUsageMsMapForLastNDays(ctx: Context, days: Int): Map<String, Long> {
        if (days <= 0) {
            return emptyMap()
        }
        flush(ctx)
        val wanted = HashSet<Int>(days)
        val cal = Calendar.getInstance()
        repeat(days) {
            wanted += ymdInt(cal)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return getUsageMsMapMatching(ctx) { ymd, _ -> ymd in wanted }
    }

    fun getUsageMsMapForMonth(ctx: Context, year: Int, month1Based: Int): Map<String, Long> {
        flush(ctx)
        return getUsageMsMapMatching(ctx) { ymd, _ ->
            (ymd / 10000) == year && ((ymd / 100) % 100) == month1Based
        }
    }

    fun getUsageMsMapForYear(ctx: Context, year: Int): Map<String, Long> {
        flush(ctx)
        return getUsageMsMapMatching(ctx) { ymd, _ -> (ymd / 10000) == year }
    }

    fun getUsageMsMapForDateRange(ctx: Context, startMs: Long, endMs: Long): Map<String, Long> {
        if (endMs <= startMs) {
            return emptyMap()
        }
        flush(ctx)
        val wanted = HashSet<Int>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= endMs) {
            wanted += ymdInt(cal)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return getUsageMsMapMatching(ctx) { ymd, _ -> ymd in wanted }
    }

    fun getUsageMsSeriesForDateRange(ctx: Context, pkg: String, startMs: Long, endMs: Long): List<Long> {
        if (pkg.isBlank() || endMs <= startMs) {
            return emptyList()
        }
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = ArrayList<Long>()
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMs
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis <= endMs) {
            out += sp.getLong(dayKey(ymdInt(cal), pkg), 0L).coerceAtLeast(0L)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return out
    }

    fun getUsageMsMapOverall(ctx: Context): Map<String, Long> {
        flush(ctx)
        return getUsageMsMapMatching(ctx) { _, _ -> true }
    }

    fun getUsageMsSeriesForLastNDays(ctx: Context, pkg: String, days: Int): List<Long> {
        if (pkg.isBlank() || days <= 0) {
            return emptyList()
        }
        flush(ctx)
        val cal = Calendar.getInstance()
        val ymds = ArrayList<Int>(days)
        repeat(days) {
            ymds += ymdInt(cal)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        ymds.reverse()
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ymds.map { ymd -> sp.getLong(dayKey(ymd, pkg), 0L).coerceAtLeast(0L) }
    }

    fun getUsageMsSeriesForCurrentMonth(ctx: Context, pkg: String): List<Long> {
        if (pkg.isBlank()) {
            return emptyList()
        }
        flush(ctx)
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetMonth = cal.get(Calendar.MONTH)
        val targetYear = cal.get(Calendar.YEAR)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = ArrayList<Long>()
        while (cal.get(Calendar.YEAR) == targetYear && cal.get(Calendar.MONTH) == targetMonth) {
            out += sp.getLong(dayKey(ymdInt(cal), pkg), 0L).coerceAtLeast(0L)
            val sameDay = cal.get(Calendar.DAY_OF_MONTH) == Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            if (sameDay) break
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return out
    }

    fun getUsageMsMonthBucketsForCurrentYear(ctx: Context, pkg: String): List<MonthBucket> {
        if (pkg.isBlank()) {
            return emptyList()
        }
        flush(ctx)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val currentMonth1 = Calendar.getInstance().get(Calendar.MONTH) + 1
        return (1..currentMonth1).map { month1 ->
            MonthBucket(year, month1, getUsageMsForMonth(ctx, pkg, year, month1))
        }
    }

    fun getUsageMsMonthBucketsAllTime(ctx: Context, pkg: String, maxMonths: Int = Int.MAX_VALUE): List<MonthBucket> {
        if (pkg.isBlank()) {
            return emptyList()
        }
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val byYearMonth = linkedMapOf<Int, Long>()
        val suffix = '_' + pkg
        for ((k, v) in sp.all) {
            if (!k.startsWith(PREFIX_DAY) || !k.endsWith(suffix)) continue
            val rest = k.removePrefix(PREFIX_DAY)
            if (rest.length < 10) continue
            val ymd = rest.substring(0, 8).toIntOrNull() ?: continue
            val year = ymd / 10000
            val month1 = (ymd / 100) % 100
            val yearMonth = year * 100 + month1
            val ms = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            if (ms > 0L) byYearMonth[yearMonth] = (byYearMonth[yearMonth] ?: 0L) + ms
        }
        val sorted = byYearMonth.entries.sortedBy { it.key }.map { (ym, total) ->
            MonthBucket(ym / 100, ym % 100, total)
        }
        return if (sorted.size > maxMonths) {
            sorted.takeLast(maxMonths)
        } else {
            sorted
        }
    }

    private fun getUsageMsMapMatching(ctx: Context, matches: (ymd: Int, pkg: String) -> Boolean): Map<String, Long> {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val out = linkedMapOf<String, Long>()
        for ((k, v) in sp.all) {
            if (!k.startsWith(PREFIX_DAY)) continue
            val rest = k.removePrefix(PREFIX_DAY)
            val idx = rest.indexOf('_')
            if (idx <= 0 || idx >= rest.length - 1) continue
            val ymd = rest.substring(0, idx).toIntOrNull() ?: continue
            val pkg = rest.substring(idx + 1).trim()
            val ms = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            if (pkg.isBlank() || ms <= 0L) continue
            if (!matches(ymd, pkg)) continue
            out[pkg] = (out[pkg] ?: 0L) + ms
        }
        return out
    }

    fun getUsageMsOverall(ctx: Context, pkg: String): Long {
        if (pkg.isBlank()) {
            return 0L
        }
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var sum = 0L
        val suffix = "_" + pkg
        for ((k, v) in sp.all) {
            if (k.startsWith(PREFIX_DAY) && k.endsWith(suffix)) {
                sum += (v as? Number)?.toLong() ?: 0L
            }
        }
        return sum
    }

    fun getEarliestTrackedStartMs(ctx: Context): Long? {
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var earliestYmd: Int? = null
        for ((k, v) in sp.all) {
            if (!k.startsWith(PREFIX_DAY)) continue
            val rest = k.removePrefix(PREFIX_DAY)
            val idx = rest.indexOf('_')
            if (idx <= 0 || idx >= rest.length - 1) continue
            val ymd = rest.substring(0, idx).toIntOrNull() ?: continue
            val ms = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            if (ms <= 0L) continue
            if (earliestYmd == null || ymd < earliestYmd!!) earliestYmd = ymd
        }
        val value = earliestYmd ?: return null
        val year = value / 10000
        val month1 = (value / 100) % 100
        val day = value % 100
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, (month1 - 1).coerceIn(0, 11))
            set(Calendar.DAY_OF_MONTH, day.coerceAtLeast(1))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun sanitizeImpossibleDailyTotals(ctx: Context): Boolean {
        flush(ctx)
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getInt(KEY_SANITIZE_VERSION, 0) >= CURRENT_SANITIZE_VERSION) {
            return false
        }

        val all = sp.all
        val byDay = linkedMapOf<Int, MutableList<Pair<String, Long>>>()
        for ((k, v) in all) {
            if (!k.startsWith(PREFIX_DAY)) continue
            val rest = k.removePrefix(PREFIX_DAY)
            val idx = rest.indexOf('_')
            if (idx <= 0 || idx >= rest.length - 1) continue
            val ymd = rest.substring(0, idx).toIntOrNull() ?: continue
            val ms = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
            if (ms <= 0L) continue
            byDay.getOrPut(ymd) { mutableListOf() }.add(k to ms)
        }

        if (byDay.isEmpty()) {
            sp.edit { putInt(KEY_SANITIZE_VERSION, CURRENT_SANITIZE_VERSION) }
            return false
        }

        val now = Calendar.getInstance()
        val todayYmd = ymdInt(now)
        val startOfToday = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val elapsedTodayMs = (System.currentTimeMillis() - startOfToday).coerceAtLeast(0L)
        val fullDayMs = 24L * 60L * 60L * 1000L

        var changed = false
        sp.edit {
            for ((ymd, entries) in byDay) {
                val total = entries.sumOf { it.second }
                val cap = if (ymd == todayYmd) elapsedTodayMs else fullDayMs
                if (cap <= 0L || total <= cap) continue

                val ratio = cap.toDouble() / total.toDouble()
                var assigned = 0L
                entries.forEachIndexed { index, (key, ms) ->
                    val scaled = if (index == entries.lastIndex) {
                        (cap - assigned).coerceAtLeast(0L)
                    } else {
                        kotlin.math.floor(ms.toDouble() * ratio).toLong().coerceAtLeast(0L)
                    }
                    assigned += scaled
                    putLong(key, scaled)
                }
                changed = true
            }
            putInt(KEY_SANITIZE_VERSION, CURRENT_SANITIZE_VERSION)
        }

        return changed
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

    private fun dayKey(ymd: Int, pkg: String): String {
        return PREFIX_DAY + ymd.toString() + "_" + pkg
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
