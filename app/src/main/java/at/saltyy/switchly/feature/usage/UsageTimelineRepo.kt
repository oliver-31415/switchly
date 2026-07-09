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

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import at.saltyy.switchly.data.prefs.BlockAttemptStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.WebUsageStore
import at.saltyy.switchly.util.AppBlockSafety
import at.saltyy.switchly.util.PackageLaunchIntentCompat
import java.util.Calendar
import java.util.concurrent.TimeUnit

object UsageTimelineRepo {
    private const val EVENT_ACTIVITY_RESUMED = 1
    private const val EVENT_ACTIVITY_PAUSED = 2
    private const val EVENT_SCREEN_NON_INTERACTIVE = 16
    private const val EVENT_KEYGUARD_SHOWN = 17
    private const val EVENT_KEYGUARD_HIDDEN = 18
    private const val SESSION_TIMEOUT_MS = 30_000L

    private val hiddenUsagePackages = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher"
    )

    private val hiddenUsagePrefixes = listOf(
        "com.android.launcher",
        "com.google.android.apps.tvlauncher",
        "com.miui.home",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.oneplus.launcher"
    )

    data class AppSession(
        val packageName: String,
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    enum class TimelineSource {
        APP,
        WEBSITE
    }

    data class UsageTimelineSession(
        val source: TimelineSource,
        val id: String,
        val label: String,
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    data class LaunchSummary(
        val totalLaunches: Int,
        val protectedOpens: Int,
        val blockedAttempts: Int
    )

    data class UnlockSession(
        val startMs: Long,
        val endMs: Long
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    }

    data class UnlockSummary(
        val today: Int,
        val thisWeek: Int,
        val previousWeek: Int
    ) {
        val weekDelta: Int get() = thisWeek - previousWeek
    }

    fun windowForRange(rangeName: String): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (rangeName) {
            "today" -> startOfToday() to now
            "week" -> now - TimeUnit.DAYS.toMillis(7) to now
            "month" -> now - TimeUnit.DAYS.toMillis(30) to now
            "year" -> now - TimeUnit.DAYS.toMillis(365) to now
            else -> 0L to now
        }
    }

    fun appSessions(ctx: Context, packageName: String, fromMs: Long, toMs: Long, limit: Int = 80): List<AppSession> {
        if (!UsageStatsRepo.hasUsageAccess(ctx) || packageName.isBlank() || toMs <= fromMs) return emptyList()
        val events = queryEvents(ctx, fromMs, toMs) ?: return emptyList()
        val out = mutableListOf<AppSession>()
        var activeStart: Long? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue

            when (event.eventType) {
                EVENT_ACTIVITY_RESUMED -> {
                    if (activeStart == null) activeStart = event.timeStamp
                }
                EVENT_ACTIVITY_PAUSED -> {
                    val start = activeStart ?: continue
                    val end = event.timeStamp.coerceAtLeast(start)
                    out += AppSession(packageName, start, end)
                    activeStart = null
                }
            }
        }

        activeStart?.let { start ->
            out += AppSession(packageName, start, toMs.coerceAtLeast(start))
        }

        return out
            .mergeTinyGaps()
            .limitLatest(limit)
    }

    fun launchCount(ctx: Context, packageName: String, fromMs: Long, toMs: Long): Int {
        return appSessions(ctx, packageName, fromMs, toMs, limit = 0).size
    }

    fun launchCounts(ctx: Context, fromMs: Long, toMs: Long): Map<String, Int> {
        return allAppSessions(ctx, fromMs, toMs, limit = 0)
            .groupingBy { it.packageName }
            .eachCount()
    }

    fun allAppSessions(ctx: Context, fromMs: Long, toMs: Long, limit: Int = 500): List<AppSession> {
        if (!UsageStatsRepo.hasUsageAccess(ctx) || toMs <= fromMs) return emptyList()
        val events = queryEvents(ctx, fromMs, toMs) ?: return emptyList()
        val activeStarts = linkedMapOf<String, Long>()
        val out = mutableListOf<AppSession>()
        val visiblePackage = mutableMapOf<String, Boolean>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (!visiblePackage.getOrPut(pkg) { isUsageInsightPackage(ctx, pkg) }) continue

            when (event.eventType) {
                EVENT_ACTIVITY_RESUMED -> {
                    if (!activeStarts.containsKey(pkg)) activeStarts[pkg] = event.timeStamp
                }
                EVENT_ACTIVITY_PAUSED -> {
                    val start = activeStarts.remove(pkg) ?: continue
                    val end = event.timeStamp.coerceAtLeast(start)
                    out += AppSession(pkg, start, end)
                }
            }
        }

        activeStarts.forEach { (pkg, start) ->
            out += AppSession(pkg, start, toMs.coerceAtLeast(start))
        }

        return out
            .mergeTinyGaps()
            .limitLatest(limit)
    }

    fun combinedUsageSessions(ctx: Context, fromMs: Long, toMs: Long, limit: Int = 500): List<UsageTimelineSession> {
        if (toMs <= fromMs) return emptyList()
        val apps = allAppSessions(ctx, fromMs, toMs, limit = 0).map {
            UsageTimelineSession(
                source = TimelineSource.APP,
                id = it.packageName,
                label = it.packageName,
                startMs = it.startMs,
                endMs = it.endMs
            )
        }
        val websites = WebUsageStore.getSessionsForDateRange(ctx, fromMs, toMs).map {
            UsageTimelineSession(
                source = TimelineSource.WEBSITE,
                id = it.domain,
                label = it.domain,
                startMs = it.startMs,
                endMs = it.endMs
            )
        }
        return (apps + websites)
            .sortedBy { it.startMs }
            .limitLatest(limit)
    }

    fun launchSummary(ctx: Context, packageName: String, rangeName: String): LaunchSummary {
        val (from, to) = windowForRange(rangeName)
        val launches = launchCount(ctx, packageName, from, to)
        val protectedOpens = when (rangeName) {
            "today" -> OpenCountStore.getTodayAllProfiles(ctx, packageName)
            "week" -> OpenCountStore.getForCurrentWeekAllProfiles(ctx, packageName)
            "month" -> OpenCountStore.getForCurrentMonthAllProfiles(ctx, packageName)
            "year" -> OpenCountStore.getForCurrentYearAllProfiles(ctx, packageName)
            else -> OpenCountStore.getOverallAllProfiles(ctx, packageName)
        }
        val blockedAttempts = when (rangeName) {
            "today" -> BlockAttemptStore.getToday(ctx, packageName)
            "week" -> BlockAttemptStore.getForCurrentWeek(ctx, packageName)
            "month" -> BlockAttemptStore.getForCurrentMonth(ctx, packageName)
            "year" -> BlockAttemptStore.getForCurrentYear(ctx, packageName)
            else -> BlockAttemptStore.getOverall(ctx, packageName)
        }
        return LaunchSummary(launches, protectedOpens, blockedAttempts)
    }

    fun screenUnlockSummary(ctx: Context): UnlockSummary {
        if (!UsageStatsRepo.hasUsageAccess(ctx)) return UnlockSummary(0, 0, 0)
        val now = System.currentTimeMillis()
        val todayStart = startOfToday()
        val weekStart = startOfWeek()
        val previousWeekStart = weekStart - TimeUnit.DAYS.toMillis(7)
        return UnlockSummary(
            today = screenUnlockCount(ctx, todayStart, now),
            thisWeek = screenUnlockCount(ctx, weekStart, now),
            previousWeek = screenUnlockCount(ctx, previousWeekStart, weekStart)
        )
    }

    fun screenUnlockCount(ctx: Context, fromMs: Long, toMs: Long): Int {
        if (!UsageStatsRepo.hasUsageAccess(ctx) || toMs <= fromMs) return 0
        val events = queryEvents(ctx, fromMs, toMs) ?: return 0
        val event = UsageEvents.Event()
        var count = 0
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == EVENT_KEYGUARD_HIDDEN) count++
        }
        return count
    }

    fun screenUnlockSessions(ctx: Context, fromMs: Long, toMs: Long, limit: Int = 120): List<UnlockSession> {
        if (!UsageStatsRepo.hasUsageAccess(ctx) || toMs <= fromMs) return emptyList()
        val events = queryEvents(ctx, fromMs, toMs) ?: return emptyList()
        val out = mutableListOf<UnlockSession>()
        val event = UsageEvents.Event()
        var activeStart: Long? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                EVENT_KEYGUARD_HIDDEN -> activeStart = event.timeStamp
                EVENT_KEYGUARD_SHOWN, EVENT_SCREEN_NON_INTERACTIVE -> {
                    val start = activeStart ?: continue
                    val end = event.timeStamp.coerceAtLeast(start)
                    if (end > start) out += UnlockSession(start, end)
                    activeStart = null
                }
            }
        }

        activeStart?.let { start ->
            out += UnlockSession(start, toMs.coerceAtLeast(start))
        }

        return out.limitLatest(limit)
    }

    fun isUsageInsightPackage(ctx: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (packageName == ctx.packageName) return false
        if (packageName in hiddenUsagePackages) return false
        if (hiddenUsagePrefixes.any { packageName.startsWith(it) }) return false
        if (AppBlockSafety.isHardExcluded(ctx, packageName)) return false
        return PackageLaunchIntentCompat.isLaunchable(ctx, packageName)
    }

    private fun queryEvents(ctx: Context, fromMs: Long, toMs: Long): UsageEvents? {
        return runCatching {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            usm.queryEvents(fromMs, toMs)
        }.getOrNull()
    }

    private fun startOfToday(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun startOfWeek(): Long {
        return Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.getInstance().firstDayOfWeek
            set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun List<AppSession>.mergeTinyGaps(): List<AppSession> {
        if (isEmpty()) return emptyList()
        val merged = mutableListOf<AppSession>()
        for (session in sortedBy { it.startMs }) {
            val last = merged.lastOrNull()
            if (last != null && last.packageName == session.packageName && session.startMs - last.endMs in 0..SESSION_TIMEOUT_MS) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, session.endMs))
            } else {
                merged += session
            }
        }
        return merged
    }

    private fun <T> List<T>.limitLatest(limit: Int): List<T> {
        if (limit <= 0 || size <= limit) return this
        return asReversed().take(limit).asReversed()
    }
}
