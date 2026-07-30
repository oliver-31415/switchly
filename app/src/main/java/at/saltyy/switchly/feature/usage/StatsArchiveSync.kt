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
import at.saltyy.switchly.data.prefs.AppLaunchCountStore
import at.saltyy.switchly.data.prefs.ScreenUnlockHistoryStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.data.statistics.StatsPersistence
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Periodically snapshots Android's short-lived UsageEvents into Switchly-owned daily stores.
 * The archive is then available for long-range statistics and backup/restore.
 */
object StatsArchiveSync {
    private const val PREFS = "switchly_prefs"
    private const val KEY_LAST_SYNC_MS = "stats_archive_last_sync_ms"
    private const val MIN_SYNC_INTERVAL_MS = 60L * 60L * 1000L
    private const val INITIAL_LOOKBACK_DAYS = 7
    private const val MAX_CATCH_UP_DAYS = 30
    private val lock = Any()

    fun sync(context: Context, force: Boolean = false): Boolean {
        val ctx = context.applicationContext
        if (!UsageStatsRepo.hasUsageAccess(ctx)) {
            return false
        }

        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val last = prefs.getLong(KEY_LAST_SYNC_MS, 0L)
            if (!force && last > 0L && now - last < MIN_SYNC_INTERVAL_MS) {
                return false
            }

            var changed = false
            val todayStart = startOfDay(now)
            val catchUpStart = if (last > 0L) {
                maxOf(
                    startOfDay(last - TimeUnit.DAYS.toMillis(1)),
                    startOfDay(now - TimeUnit.DAYS.toMillis(MAX_CATCH_UP_DAYS.toLong())),
                )
            } else {
                startOfDay(now - TimeUnit.DAYS.toMillis((INITIAL_LOOKBACK_DAYS - 1).toLong()))
            }
            var cursor = catchUpStart

            while (cursor <= todayStart) {
                val next = Calendar.getInstance().apply {
                    timeInMillis = cursor
                    add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                val dayEnd = minOf(next, now)
                val ymd = ymdInt(cursor)

                UsageStatsRepo.getSingleDayUsageMapForImport(ctx, cursor, dayEnd).forEach { (pkg, ms) ->
                    val before = UsageStore.getUsageMsForDay(ctx, ymd, pkg)
                    UsageStore.mergeUsageMsForDay(ctx, ymd, pkg, ms)
                    if (ms > before) {
                        changed = true
                    }
                }
                val appSessions = UsageTimelineRepo.systemAllAppSessions(ctx, cursor, dayEnd, limit = 0)
                if (appSessions.isNotEmpty()) {
                    StatsPersistence.archiveAppSessions(ctx, appSessions.map { session ->
                        StatsPersistence.AppSession(session.packageName, session.startMs, session.endMs)
                    })
                    changed = true
                }

                UsageStatsRepo.getSessionCountMapForWindow(ctx, cursor, dayEnd).forEach { (pkg, count) ->
                    AppLaunchCountStore.mergeForDay(ctx, ymd, pkg, count)
                    if (count > 0) {
                        changed = true
                    }
                }
                cursor = next
            }

            val unlocks = UsageTimelineRepo.screenUnlockSessions(ctx, catchUpStart, now, limit = 0)
                .map { ScreenUnlockHistoryStore.Session(it.startMs, it.endMs) }
            if (unlocks.isNotEmpty()) {
                ScreenUnlockHistoryStore.mergeSessions(ctx, unlocks)
                changed = true
            }

            // Do not advance the archive cursor until queued Room and preference mirrors are durable.
            StatsPersistence.flushBlocking(ctx)
            prefs.edit { putLong(KEY_LAST_SYNC_MS, now) }
            return changed
        }
    }

    private fun startOfDay(timeMs: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timeMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun ymdInt(timeMs: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timeMs }
        return cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
    }
}
