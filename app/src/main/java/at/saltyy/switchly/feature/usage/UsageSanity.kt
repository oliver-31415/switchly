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

package at.saltyy.switchly.feature.usage

import android.content.Context
import at.saltyy.switchly.data.prefs.UsageStore
import java.util.Calendar
import kotlin.math.floor

object UsageSanity {
    enum class RangeCap { TODAY, WEEK, MONTH, YEAR, OVERALL }

    fun capMapToRange(ctx: Context, raw: Map<String, Long>, range: RangeCap): Map<String, Long> {
        if (raw.isEmpty()) return raw
        val total = raw.values.sum().coerceAtLeast(0L)
        val cap = maxPossibleMs(ctx, range)
        if (cap <= 0L || total <= cap) return raw.mapValues { (_, v) -> v.coerceAtLeast(0L) }

        val out = linkedMapOf<String, Long>()
        var assigned = 0L
        val entries = raw.entries.sortedByDescending { it.value }
        entries.forEachIndexed { index, (key, value) ->
            val safe = value.coerceAtLeast(0L)
            val scaled = if (index == entries.lastIndex) {
                (cap - assigned).coerceAtLeast(0L)
            } else {
                floor(safe.toDouble() * cap.toDouble() / total.toDouble()).toLong().coerceAtLeast(0L)
            }
            assigned += scaled
            out[key] = scaled
        }
        return out
    }

    fun capSeriesToRange(ctx: Context, series: List<Long>, range: RangeCap): List<Long> {
        if (series.isEmpty()) return series
        return capSeriesToCap(series, maxPossibleMs(ctx, range))
    }

    fun capTotalToRange(ctx: Context, totalMs: Long, range: RangeCap): Long {
        val cap = maxPossibleMs(ctx, range)
        return if (cap <= 0L) totalMs.coerceAtLeast(0L) else totalMs.coerceIn(0L, cap)
    }

    private fun capSeriesToCap(series: List<Long>, cap: Long): List<Long> {
        if (series.isEmpty()) return series
        if (cap <= 0L) return series.map { 0L }
        val clean = series.map { it.coerceAtLeast(0L) }
        val total = clean.sum()
        if (total <= cap) return clean
        val out = ArrayList<Long>(clean.size)
        var assigned = 0L
        clean.forEachIndexed { index, value ->
            val scaled = if (index == clean.lastIndex) {
                (cap - assigned).coerceAtLeast(0L)
            } else {
                floor(value.toDouble() * cap.toDouble() / total.toDouble()).toLong().coerceAtLeast(0L)
            }
            assigned += scaled
            out += scaled
        }
        return out
    }

    private fun maxPossibleMs(ctx: Context, range: RangeCap): Long {
        val nowMs = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val startMs = when (range) {
            RangeCap.TODAY -> (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            RangeCap.WEEK -> (cal.clone() as Calendar).apply {
                firstDayOfWeek = Calendar.MONDAY
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                val currentDow = get(Calendar.DAY_OF_WEEK)
                val shift = (currentDow - Calendar.MONDAY + 7) % 7
                add(Calendar.DAY_OF_YEAR, -shift)
            }.timeInMillis
            RangeCap.MONTH -> (cal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            RangeCap.YEAR -> (cal.clone() as Calendar).apply {
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            RangeCap.OVERALL -> UsageStore.getEarliestTrackedStartMs(ctx) ?: 0L
        }
        return (nowMs - startMs).coerceAtLeast(0L)
    }
}
