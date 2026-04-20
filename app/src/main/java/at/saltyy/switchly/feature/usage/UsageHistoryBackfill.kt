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
import androidx.core.content.edit
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.UsageStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

object UsageHistoryBackfill {
    private const val PREFS = "switchly_prefs"
    private const val KEY_IMPORT_VERSION = "usage_history_backfill_version"
    private const val CURRENT_VERSION = 2
    private const val MAX_LOOKBACK_DAYS = 120

    fun maybeRun(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getInt(KEY_IMPORT_VERSION, 0) >= CURRENT_VERSION) return false
        if (!UsageStatsRepo.hasUsageAccess(ctx)) return false
        // Accessibility-backed local history is the source of truth. Only do a one-time import
        // on fresh installs / fresh data stores to seed older history conservatively.
        if (UsageStore.hasAnyUsageData(ctx)) {
            sp.edit { putInt(KEY_IMPORT_VERSION, CURRENT_VERSION) }
            return false
        }

        val changed = runCatching { backfillFromSystem(ctx) }.getOrDefault(false)
        sp.edit { putInt(KEY_IMPORT_VERSION, CURRENT_VERSION) }
        return changed
    }

    private fun backfillFromSystem(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now)
        val startOfYear = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val lookbackFloor = startOfDay(now - TimeUnit.DAYS.toMillis((MAX_LOOKBACK_DAYS - 1).toLong()))
        var cursor = maxOf(startOfYear, lookbackFloor)
        var changed = false

        while (cursor <= todayStart) {
            val next = Calendar.getInstance().apply {
                timeInMillis = cursor
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val dayEnd = minOf(next, now)
            val ymd = ymdInt(cursor)

            val usage = UsageStatsRepo.getSingleDayUsageMapForImport(ctx, cursor, dayEnd)
            for ((pkg, ms) in usage) {
                val before = UsageStore.getUsageMsForDay(ctx, ymd, pkg)
                UsageStore.mergeUsageMsForDay(ctx, ymd, pkg, ms)
                if (ms > before) changed = true
            }

            val sessions = UsageStatsRepo.getSessionCountMapForWindow(ctx, cursor, dayEnd)
            for ((pkg, count) in sessions) {
                OpenCountStore.mergeLegacyForDay(ctx, ymd, pkg, count)
                if (count > 0) changed = true
            }

            cursor = next
        }

        return changed
    }

    private fun startOfDay(timeMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timeMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun ymdInt(timeMs: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
