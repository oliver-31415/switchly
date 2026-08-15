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
import android.content.SharedPreferences
import androidx.core.content.edit
import at.saltyy.switchly.data.prefs.AppLaunchCountStore
import at.saltyy.switchly.data.prefs.ScreenUnlockHistoryStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.statistics.StatsPersistence
import java.util.Calendar
import java.util.concurrent.TimeUnit

object UsageHistoryBackfill {
    private const val PREFS = "switchly_prefs"
    private const val KEY_IMPORT_VERSION = "usage_history_backfill_version"
    private const val CURRENT_VERSION = 4
    private const val MAX_LOOKBACK_DAYS = 120

    fun maybeRun(ctx: Context): Boolean {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (readImportVersion(sp) >= CURRENT_VERSION) {
            return false
        }
        if (!UsageStatsRepo.hasUsageAccess(ctx)) {
            return false
        }
        // Accessibility-backed local history remains the source of truth.
        // Existing usage totals do not need to be overwritten, while launch counts and unlock sessions are imported because older builds depended on Android's short-lived event timeline for those views.
        val importUsage = !UsageStore.hasAnyUsageData(ctx)
        val result = runCatching { backfillFromSystem(ctx, importUsage) }
        if (result.isFailure) {
            return false
        }
        sp.edit { putInt(KEY_IMPORT_VERSION, CURRENT_VERSION) }
        return result.getOrDefault(false)
    }

    private fun backfillFromSystem(ctx: Context, importUsage: Boolean): Boolean {
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

            if (importUsage) {
                val usage = UsageStatsRepo.getSingleDayUsageMapForImport(ctx, cursor, dayEnd)
                for ((pkg, ms) in usage) {
                    val before = UsageStore.getUsageMsForDay(ctx, ymd, pkg)
                    UsageStore.mergeUsageMsForDay(ctx, ymd, pkg, ms)
                    if (ms > before) changed = true
                }
            }

            val appSessions = UsageTimelineRepo.systemAllAppSessions(ctx, cursor, dayEnd, limit = 0)
            if (appSessions.isNotEmpty()) {
                StatsPersistence.archiveAppSessions(ctx, appSessions.map { session ->
                    StatsPersistence.AppSession(session.packageName, session.startMs, session.endMs)
                })
                changed = true
            }

            val sessions = UsageStatsRepo.getSessionCountMapForWindow(ctx, cursor, dayEnd)
            for ((pkg, count) in sessions) {
                AppLaunchCountStore.mergeForDay(ctx, ymd, pkg, count)
                if (count > 0) changed = true
            }

            val unlockSessions = UsageTimelineRepo.screenUnlockSessions(ctx, cursor, dayEnd, limit = 0)
                .map { ScreenUnlockHistoryStore.Session(it.startMs, it.endMs) }
            if (unlockSessions.isNotEmpty()) {
                ScreenUnlockHistoryStore.mergeSessions(ctx, unlockSessions)
                changed = true
            }

            cursor = next
        }

        // Mark the migration complete only after every queued Room write has finished.
        StatsPersistence.flushBlocking(ctx)
        return changed
    }

    private fun readImportVersion(sp: SharedPreferences): Int {
        val raw = sp.all[KEY_IMPORT_VERSION] ?: return 0
        val value = when (raw) {
            is Int -> raw
            is Long -> raw.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            is Number -> raw.toLong().coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            is String -> raw.toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
            else -> 0
        }

        if (raw !is Int) {
            sp.edit { putInt(KEY_IMPORT_VERSION, value) }
        }

        return value
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
