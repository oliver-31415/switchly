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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tracks how long Switchly is effectively active.
 * - Current session start is persisted so the Home indicator survives app restarts.
 * - Finished active ranges are split into per-day buckets so statistics can show today/week/month/year/overall progress.
 */
object ActiveDurationStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_ACTIVE_SINCE_MS = "switch_mode_active_since_ms"
    private const val KEY_OVERALL_MS = "switchly_active_overall_ms"
    private const val PREFIX_DAY_MS = "switchly_active_day_ms_"
    private const val PREFIX_DAY_SESSIONS = "switchly_active_day_sessions_"

    private val dayKeyFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd", Locale.US)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun dayFormatter(): SimpleDateFormat =
        dayKeyFormat.get() ?: SimpleDateFormat("yyyyMMdd", Locale.US).also(dayKeyFormat::set)

    private fun dayKey(timeMillis: Long): String =
        dayFormatter().format(Date(timeMillis))

    private fun startOfDay(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfTomorrow(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfDay(timeMillis)
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun startOfThisMonth(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun startOfThisYear(now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun addFinishedRange(ctx: Context, startMs: Long, endMs: Long) {
        if (startMs <= 0L || endMs <= startMs) return

        val sp = prefs(ctx)
        var cursor = startMs
        val increments = linkedMapOf<String, Long>()
        val sessionSegments = linkedMapOf<String, MutableSet<String>>()
        var total = 0L

        while (cursor < endMs) {
            val nextBoundary = startOfTomorrow(cursor)
            val segmentEnd = minOf(endMs, nextBoundary)
            val delta = (segmentEnd - cursor).coerceAtLeast(0L)
            if (delta > 0L) {
                val day = dayKey(cursor)
                val totalKey = PREFIX_DAY_MS + day
                val sessionsKey = PREFIX_DAY_SESSIONS + day
                increments[totalKey] = (increments[totalKey] ?: 0L) + delta
                sessionSegments.getOrPut(sessionsKey) {
                    (sp.getStringSet(sessionsKey, emptySet()) ?: emptySet()).toMutableSet()
                }.add("$cursor:$segmentEnd")
                total += delta
            }
            cursor = segmentEnd
        }

        if (total <= 0L) return

        sp.edit {
            increments.forEach { (key, delta) ->
                putLong(key, sp.getLong(key, 0L) + delta)
            }
            sessionSegments.forEach { (key, segments) ->
                putStringSet(key, segments)
            }
            putLong(KEY_OVERALL_MS, sp.getLong(KEY_OVERALL_MS, 0L) + total)
        }
    }

    fun syncEffectiveState(ctx: Context, enabledNow: Boolean) {
        val sp = prefs(ctx)
        val currentSince = sp.getLong(KEY_ACTIVE_SINCE_MS, 0L)
        val now = System.currentTimeMillis()

        when {
            enabledNow && currentSince <= 0L -> {
                sp.edit { putLong(KEY_ACTIVE_SINCE_MS, now) }
            }
            !enabledNow && currentSince > 0L -> {
                addFinishedRange(ctx, currentSince, now)
                sp.edit { putLong(KEY_ACTIVE_SINCE_MS, 0L) }
            }
        }
    }

    fun getActiveSinceMillis(ctx: Context): Long =
        prefs(ctx).getLong(KEY_ACTIVE_SINCE_MS, 0L)

    fun getActiveDurationMillis(ctx: Context): Long {
        val since = getActiveSinceMillis(ctx)
        if (since <= 0L) return 0L
        return (System.currentTimeMillis() - since).coerceAtLeast(0L)
    }

    private fun ongoingOverlapMs(ctx: Context, rangeStart: Long, rangeEnd: Long = System.currentTimeMillis()): Long {
        val since = getActiveSinceMillis(ctx)
        if (since <= 0L) return 0L
        val start = maxOf(since, rangeStart)
        val end = minOf(System.currentTimeMillis(), rangeEnd)
        return (end - start).coerceAtLeast(0L)
    }

    private fun storedDayMs(ctx: Context, timeMillis: Long): Long =
        prefs(ctx).getLong(PREFIX_DAY_MS + dayKey(timeMillis), 0L)

    fun dayTotalMs(ctx: Context, timeMillis: Long): Long {
        val start = startOfDay(timeMillis)
        val end = startOfTomorrow(timeMillis)
        return storedDayMs(ctx, timeMillis) + ongoingOverlapMs(ctx, start, end)
    }

    fun todayMs(ctx: Context): Long =
        dayTotalMs(ctx, System.currentTimeMillis())

    fun lastNDaysMs(ctx: Context, days: Int): Long {
        val count = days.coerceAtLeast(1)
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfDay(System.currentTimeMillis())
        cal.add(Calendar.DAY_OF_YEAR, -(count - 1))
        var total = 0L
        repeat(count) {
            total += dayTotalMs(ctx, cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }

    fun thisMonthMs(ctx: Context): Long {
        val start = startOfThisMonth()
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.timeInMillis = start
        var total = 0L
        while (cal.timeInMillis <= now) {
            total += dayTotalMs(ctx, cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }

    fun thisYearMs(ctx: Context): Long {
        val start = startOfThisYear()
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.timeInMillis = start
        var total = 0L
        while (cal.timeInMillis <= now) {
            total += dayTotalMs(ctx, cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }

    fun rangeMs(ctx: Context, startMs: Long, endMs: Long): Long {
        if (endMs <= startMs) return 0L
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfDay(startMs)
        var total = 0L
        while (cal.timeInMillis <= endMs) {
            total += dayTotalMs(ctx, cal.timeInMillis)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }

    fun overallMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_OVERALL_MS, 0L) + getActiveDurationMillis(ctx)

    data class SessionSegment(
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    fun daySessions(ctx: Context, timeMillis: Long): List<SessionSegment> {
        val key = PREFIX_DAY_SESSIONS + dayKey(timeMillis)
        val stored = prefs(ctx).getStringSet(key, emptySet()) ?: emptySet()
        val parsed = stored.mapNotNull { raw ->
            val parts = raw.split(":")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            if (start != null && end != null && end > start) SessionSegment(start, end) else null
        }.toMutableList()

        val since = getActiveSinceMillis(ctx)
        val now = System.currentTimeMillis()
        val start = startOfDay(timeMillis)
        val end = startOfTomorrow(timeMillis)
        if (since > 0L && now > since) {
            val overlapStart = maxOf(since, start)
            val overlapEnd = minOf(now, end)
            if (overlapEnd > overlapStart) {
                parsed += SessionSegment(overlapStart, overlapEnd)
            }
        }

        return parsed.sortedBy { it.startMs }
    }

    data class Bucket(
        val label: String,
        val valueMs: Long,
        val timeMillis: Long = 0L
    )

    fun dailyBuckets(ctx: Context, days: Int): List<Bucket> {
        val count = days.coerceAtLeast(1)
        val fmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfDay(System.currentTimeMillis())
        cal.add(Calendar.DAY_OF_YEAR, -(count - 1))
        return buildList {
            repeat(count) {
                add(Bucket(fmt.format(Date(cal.timeInMillis)), dayTotalMs(ctx, cal.timeInMillis), cal.timeInMillis))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    fun dailyBucketsForRange(ctx: Context, startMs: Long, endMs: Long): List<Bucket> {
        if (endMs <= startMs) return emptyList()
        val fmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfDay(startMs)
        return buildList {
            while (cal.timeInMillis <= endMs) {
                add(Bucket(fmt.format(Date(cal.timeInMillis)), dayTotalMs(ctx, cal.timeInMillis), cal.timeInMillis))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    fun dailyBucketsForMonth(ctx: Context, monthStartMs: Long): List<Bucket> {
        val fmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = monthStartMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val month = cal.get(Calendar.MONTH)
        val now = System.currentTimeMillis()

        return buildList {
            while (cal.get(Calendar.MONTH) == month && cal.timeInMillis <= now) {
                add(Bucket(fmt.format(Date(cal.timeInMillis)), dayTotalMs(ctx, cal.timeInMillis), cal.timeInMillis))
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    fun monthlyBucketsOverall(ctx: Context): List<Bucket> {
        val sp = prefs(ctx)
        val fmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val monthTotals = linkedMapOf<Long, Long>()

        sp.all.keys
            .filter { it.startsWith(PREFIX_DAY_MS) }
            .mapNotNull { key ->
                val day = key.removePrefix(PREFIX_DAY_MS)
                val parsed = runCatching {
                    dayFormatter().parse(day)?.time
                }.getOrNull()
                parsed?.let { it to sp.getLong(key, 0L) }
            }
            .sortedBy { it.first }
            .forEach { (dayMs, valueMs) ->
                val cal = Calendar.getInstance()
                cal.timeInMillis = dayMs
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val monthStart = cal.timeInMillis
                monthTotals[monthStart] = (monthTotals[monthStart] ?: 0L) + valueMs
            }

        val activeSince = getActiveSinceMillis(ctx)
        if (activeSince > 0L) {
            val now = System.currentTimeMillis()
            val cal = Calendar.getInstance()
            cal.timeInMillis = startOfDay(activeSince)
            while (cal.timeInMillis <= now) {
                val dayMs = cal.timeInMillis
                val dayStart = startOfDay(dayMs)
                val dayEnd = startOfTomorrow(dayMs)
                val overlap = ongoingOverlapMs(ctx, dayStart, dayEnd)
                if (overlap > 0L) {
                    val monthCal = Calendar.getInstance()
                    monthCal.timeInMillis = dayMs
                    monthCal.set(Calendar.DAY_OF_MONTH, 1)
                    monthCal.set(Calendar.HOUR_OF_DAY, 0)
                    monthCal.set(Calendar.MINUTE, 0)
                    monthCal.set(Calendar.SECOND, 0)
                    monthCal.set(Calendar.MILLISECOND, 0)
                    val monthStart = monthCal.timeInMillis
                    monthTotals[monthStart] = (monthTotals[monthStart] ?: 0L) + overlap
                }
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        return monthTotals.map { (monthStart, valueMs) ->
            Bucket(fmt.format(Date(monthStart)), valueMs, monthStart)
        }
    }

    fun monthlyBucketsThisYear(ctx: Context): List<Bucket> {
        val fmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
        val cal = Calendar.getInstance()
        cal.timeInMillis = startOfThisYear()
        val now = System.currentTimeMillis()
        return buildList {
            while (cal.timeInMillis <= now) {
                val monthStart = cal.timeInMillis
                val month = cal.get(Calendar.MONTH)
                var total = 0L
                while (cal.get(Calendar.MONTH) == month && cal.timeInMillis <= now) {
                    total += dayTotalMs(ctx, cal.timeInMillis)
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                add(Bucket(fmt.format(Date(monthStart)), total, monthStart))
            }
        }
    }
}
