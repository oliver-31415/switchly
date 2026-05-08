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

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import at.saltyy.switchly.data.prefs.UsageStore
import java.util.Calendar
import java.util.concurrent.TimeUnit

object UsageStatsRepo {

    // UsageEvents.Event ACTIVITY_* numeric values.
    // Referencing these as local constants keeps minSdk 27 builds lint-clean without using newer SDK fields directly.
    private const val EVENT_ACTIVITY_RESUMED = 1
    private const val EVENT_ACTIVITY_PAUSED = 2
    private const val EVENT_ACTIVITY_STOPPED = 23

    private fun startOfDayLocal(timeMs: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = timeMs
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfTodayLocal(): Long = startOfDayLocal(System.currentTimeMillis())

    private fun startOfTomorrowLocal(): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = startOfTodayLocal()
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun isDayAligned(timeMs: Long): Boolean = startOfDayLocal(timeMs) == timeMs

    private fun isSingleLocalDayWindow(from: Long, to: Long): Boolean {
        if (!isDayAligned(from) || to <= from) return false
        val nextDay = Calendar.getInstance().apply {
            timeInMillis = from
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return to <= nextDay
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            ctx.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getLast7DaysSummary(ctx: Context, topN: Int = 20): UsageSummary {
        return getLastNDaysSummary(ctx, days = 7, topN = topN)
    }

    fun getLastNDaysSummary(ctx: Context, days: Int, topN: Int = 20): UsageSummary {
        val now = System.currentTimeMillis()
        val from = now - TimeUnit.DAYS.toMillis(days.toLong().coerceAtLeast(1L))
        return getSummary(ctx, from, now, topN)
    }

    fun getThisMonthSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        val to = System.currentTimeMillis()
        return getSummary(ctx, from, to, topN)
    }

    fun getThisYearSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val c = Calendar.getInstance()
        c.set(Calendar.MONTH, Calendar.JANUARY)
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        val to = System.currentTimeMillis()
        return getSummary(ctx, from, to, topN)
    }

    fun getOverallSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val now = System.currentTimeMillis()
        return getSummary(ctx, 0L, now, topN)
    }

    fun getTodaySummary(ctx: Context, topN: Int = 20): UsageSummary {
        // Prefer Switchly's own per-day store for "today".
        // Some devices/OEMs over-report UsageStats for the current day and can leak yesterday's total into today's app values, which then causes early blocking.
        // If the internal store is still empty, fall back to a live system query so the Today tab doesn't look blank.
        val byPkg = HashMap(UsageStore.getUsageMsMapToday(ctx))
        if (byPkg.isEmpty()) {
            return getSummary(ctx, startOfTodayLocal(), System.currentTimeMillis(), topN)
        }

        val homePkgs = getHomePackages(ctx)
        val it = byPkg.keys.iterator()
        while (it.hasNext()) {
            val pkg = it.next()
            if (shouldExcludePackage(ctx, pkg, homePkgs) || !isInstalled(ctx, pkg)) {
                it.remove()
            }
        }

        val total = byPkg.values.sum()
        val pm = ctx.packageManager
        val top = byPkg.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (pkg, ms) ->
                val label = try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Throwable) {
                    pkg
                }
                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (_: Throwable) {
                    null
                }
                val percent = if (total > 0L) ms.toFloat() / total.toFloat() else 0f
                AppUsage(pkg, label, icon, ms, percent)
            }

        return UsageSummary(totalTimeMs = total, topApps = top)
    }

    fun getTodayPerHour(
        ctx: Context,
        packageName: String,
        now: Long = System.currentTimeMillis()
    ): List<Long> {
        if (packageName.isBlank()) return List(24) { 0L }

        val safeNow = now.coerceAtMost(System.currentTimeMillis())
        val dayStart = startOfTodayLocal()
        val buckets = LongArray(24)
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try {
            usm.queryEvents(dayStart, safeNow)
        } catch (_: SecurityException) {
            null
        } catch (_: Throwable) {
            null
        }

        if (events != null) {
            var activeStart: Long? = null
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.packageName != packageName) continue
                when (e.eventType) {
                    EVENT_ACTIVITY_RESUMED -> {
                        val startAt = e.timeStamp.coerceIn(dayStart, safeNow)
                        val prev = activeStart
                        if (prev == null || startAt < prev) activeStart = startAt
                    }

                    EVENT_ACTIVITY_PAUSED,
                    EVENT_ACTIVITY_STOPPED -> {
                        val startAt = activeStart ?: continue
                        val endAt = e.timeStamp.coerceIn(dayStart, safeNow)
                        addRangeToHourlyBuckets(buckets, dayStart, startAt, endAt)
                        activeStart = null
                    }
                }
            }

            val startAt = activeStart
            if (startAt != null) {
                addRangeToHourlyBuckets(buckets, dayStart, startAt, safeNow)
            }
        }

        val internalToday = UsageStore.getUsageMsToday(ctx, packageName).coerceAtLeast(0L)
        val bucketTotal = buckets.sum().coerceAtLeast(0L)
        if (internalToday > bucketTotal) {
            val hourIndex = (((safeNow - dayStart) / TimeUnit.HOURS.toMillis(1)).toInt()).coerceIn(0, 23)
            buckets[hourIndex] += (internalToday - bucketTotal)
        }

        return buckets.map { it.coerceAtLeast(0L) }
    }

    fun getSessionsToday(ctx: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        val start = startOfTodayLocal()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(start, now)
        var sessions = 0
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName == packageName && e.eventType == EVENT_ACTIVITY_RESUMED) {
                sessions++
            }
        }
        return sessions
    }

    private fun getPackageUsageForWindowClamped(
        ctx: Context,
        from: Long,
        to: Long,
        packageName: String
    ): Long {
        val safeTo = to.coerceAtMost(System.currentTimeMillis())
        val windowMs = (safeTo - from).coerceAtLeast(0L)
        if (packageName.isBlank() || windowMs <= 0L) return 0L
        val reported = getSummary(ctx, from, safeTo, topN = 1, onlyPackage = packageName).totalTimeMs
        // A single package cannot physically be in the foreground longer than the
        // queried window itself. Some OEMs over-report package usage in bucketed
        // queries, especially for historical windows, so clamp to the window size.
        return reported.coerceIn(0L, windowMs)
    }

    fun getTotalMsForWindow(ctx: Context, from: Long, to: Long, packageName: String): Long {
        return getPackageUsageForWindowClamped(ctx, from, to, packageName)
    }

    fun getTodayMsForPackage(ctx: Context, packageName: String, now: Long = System.currentTimeMillis()): Long {
        return getPackageUsageForWindowClamped(ctx, startOfDayLocal(now), now, packageName)
    }

    fun getSessionsForWindow(ctx: Context, from: Long, to: Long, packageName: String): Int {
        val safeTo = to.coerceAtMost(System.currentTimeMillis())
        if (packageName.isBlank() || safeTo <= from) return 0
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try {
            usm.queryEvents(from, safeTo)
        } catch (_: SecurityException) {
            return 0
        } catch (_: Throwable) {
            return 0
        }
        var sessions = 0
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.packageName == packageName && e.eventType == EVENT_ACTIVITY_RESUMED) {
                sessions++
            }
        }
        return sessions
    }

    fun getSessionsForCurrentWeek(ctx: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val diff = (7 + (get(Calendar.DAY_OF_WEEK) - firstDayOfWeek)) % 7
            add(Calendar.DAY_OF_YEAR, -diff)
        }
        return getSessionsForWindow(ctx, c.timeInMillis, now, packageName)
    }

    fun getSessionsForCurrentMonth(ctx: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return getSessionsForWindow(ctx, c.timeInMillis, now, packageName)
    }

    fun getSessionsForCurrentYear(ctx: Context, packageName: String): Int {
        val now = System.currentTimeMillis()
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MONTH, Calendar.JANUARY)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return getSessionsForWindow(ctx, c.timeInMillis, now, packageName)
    }

    fun getSessionsOverall(ctx: Context, packageName: String): Int {
        return getSessionsForWindow(ctx, 0L, System.currentTimeMillis(), packageName)
    }

    fun getLast7DaysPerDay(ctx: Context, packageName: String): List<Long> {
        val todayStart = startOfTodayLocal()
        val c = Calendar.getInstance()
        c.timeInMillis = todayStart

        val dayStarts = mutableListOf<Long>()
        for (i in 0 until 7) {
            dayStarts.add(c.timeInMillis)
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        dayStarts.reverse()

        return dayStarts.mapIndexed { i, start ->
            val end = if (i == dayStarts.lastIndex) startOfTomorrowLocal() else dayStarts[i + 1]
            getPackageUsageForWindowClamped(ctx, start, end, packageName)
        }
    }

    fun getLastNDaysPerDay(ctx: Context, packageName: String, days: Int): List<Long> {
        val n = days.coerceAtLeast(1).coerceAtMost(60)
        val todayStart = startOfTodayLocal()
        val c = Calendar.getInstance()
        c.timeInMillis = todayStart

        val dayStarts = mutableListOf<Long>()
        for (i in 0 until n) {
            dayStarts.add(c.timeInMillis)
            c.add(Calendar.DAY_OF_YEAR, -1)
        }
        dayStarts.reverse()

        return dayStarts.mapIndexed { i, start ->
            val end = if (i == dayStarts.lastIndex) startOfTomorrowLocal() else dayStarts[i + 1]
            getPackageUsageForWindowClamped(ctx, start, end, packageName)
        }
    }

    /**
     * Returns the earliest timestamp (ms) that the system actually reports usage for within the window.
     * Useful for showing a UI banner like: "Data available since ..." on devices that retain only ~1 week.
     */

    fun getSingleDayUsageMapForImport(ctx: Context, dayStart: Long, dayEnd: Long): Map<String, Long> {
        val safeEnd = dayEnd.coerceAtMost(System.currentTimeMillis())
        if (safeEnd <= dayStart) return emptyMap()
        val windowMs = (safeEnd - dayStart).coerceAtLeast(0L)

        // Import existing app-open data conservatively.
        // For the one-time migration we intentionally do NOT merge multiple system sources, because that can inflate historical values on some devices.
        // We only import the raw daily UsageStats values that Android already exposes for the requested window.
        val byPkg = queryDailyUsage(
            ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager,
            dayStart,
            safeEnd,
            onlyPackage = null
        )

        val homePkgs = getHomePackages(ctx)
        val out = linkedMapOf<String, Long>()
        for ((pkg, rawMs) in byPkg) {
            if (shouldExcludePackage(ctx, pkg, homePkgs)) continue
            if (!isInstalled(ctx, pkg)) continue
            val ms = rawMs.coerceIn(0L, windowMs)
            if (ms > 0L) out[pkg] = ms
        }
        return out
    }

    fun getSessionCountMapForWindow(ctx: Context, from: Long, to: Long): Map<String, Int> {
        val safeTo = to.coerceAtMost(System.currentTimeMillis())
        if (safeTo <= from) return emptyMap()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = try {
            usm.queryEvents(from, safeTo)
        } catch (_: SecurityException) {
            return emptyMap()
        } catch (_: Throwable) {
            return emptyMap()
        }
        val homePkgs = getHomePackages(ctx)
        val out = linkedMapOf<String, Int>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (shouldExcludePackage(ctx, pkg, homePkgs)) continue
            if (!isInstalled(ctx, pkg)) continue
            if (e.eventType == EVENT_ACTIVITY_RESUMED) {
                out[pkg] = (out[pkg] ?: 0) + 1
            }
        }
        return out
    }

    fun getEarliestAvailableUsageMs(ctx: Context, from: Long, to: Long): Long? {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
        if (stats.isNullOrEmpty()) return null
        return stats.minOfOrNull { it.firstTimeStamp }
    }

    private fun getHomePackages(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return try {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                pm.queryIntentActivities(intent, 0)
            }
            list.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    private fun shouldExcludePackage(ctx: Context, pkg: String, homePkgs: Set<String>): Boolean {
        val p = pkg.lowercase()
        if (pkg == ctx.packageName) return true
        if (pkg in homePkgs) return true
        if (p == "android") return true
        if (p == "com.android.systemui") return true

        val known = setOf(
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.google.android.launcher",
            "com.miui.home",
            "com.oneplus.launcher",
            "com.sec.android.app.launcher",
            "com.huawei.android.launcher",
            "com.oppo.launcher",
            "com.vivo.launcher",
            "com.android.quickstep"
        )
        if (p in known) return true
        if (p.contains("launcher")) return true
        if (p.contains("quickstep")) return true

        return false
    }

    private fun getSummary(
        ctx: Context,
        from: Long,
        to: Long,
        topN: Int,
        onlyPackage: String? = null
    ): UsageSummary {
        val safeTo = to.coerceAtMost(System.currentTimeMillis())
        if (safeTo <= from) return UsageSummary(totalTimeMs = 0L, topApps = emptyList())

        val byPkg = if (isSingleLocalDayWindow(from, safeTo)) {
            getSingleDayUsageByPackage(ctx, from, safeTo, onlyPackage)
        } else {
            getBucketedUsageByPackage(ctx, from, safeTo, onlyPackage)
        }

        if (onlyPackage == null) {
            val homePkgs = getHomePackages(ctx)
            val it = byPkg.keys.iterator()
            while (it.hasNext()) {
                val pkg = it.next()
                if (shouldExcludePackage(ctx, pkg, homePkgs)) {
                    it.remove()
                }
            }
        }

        run {
            val it = byPkg.keys.iterator()
            while (it.hasNext()) {
                val pkg = it.next()
                if (!isInstalled(ctx, pkg)) it.remove()
            }
        }

        val total = byPkg.values.sum()
        val pm = ctx.packageManager
        val top = byPkg.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (pkg, ms) ->
                val label = try {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(appInfo).toString()
                } catch (_: Throwable) {
                    pkg
                }
                val icon = try {
                    pm.getApplicationIcon(pkg)
                } catch (_: Throwable) {
                    null
                }
                val percent = if (total > 0L) ms.toFloat() / total.toFloat() else 0f
                AppUsage(pkg, label, icon, ms, percent)
            }

        return UsageSummary(totalTimeMs = total, topApps = top)
    }

    private fun getSingleDayUsageByPackage(
        ctx: Context,
        dayStart: Long,
        dayEnd: Long,
        onlyPackage: String?
    ): HashMap<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val byPkg = HashMap<String, Long>()

        mergeUsage(byPkg, queryAggregateUsage(usm, dayStart, dayEnd, onlyPackage))
        mergeUsage(byPkg, queryDailyUsage(usm, dayStart, dayEnd, onlyPackage))
        mergeUsage(byPkg, queryEventDerivedUsage(usm, dayStart, dayEnd, onlyPackage))

        // Only merge the live in-memory "today" usage for the actual current-day window.
        // Historical single-day queries (used by month/year/overall detail screens) must not pull in today's buffered value, otherwise today's usage gets duplicated into every historical day and long-range totals explode.
        if (dayStart == startOfTodayLocal()) {
            val internalToday = UsageStore.getUsageMsMapToday(ctx)
            if (onlyPackage != null) {
                val internal = internalToday[onlyPackage]?.coerceAtLeast(0L) ?: 0L
                if (internal > 0L) {
                    byPkg[onlyPackage] = maxOf(byPkg[onlyPackage] ?: 0L, internal)
                }
            } else {
                for ((pkg, ms) in internalToday) {
                    if (ms <= 0L) continue
                    byPkg[pkg] = maxOf(byPkg[pkg] ?: 0L, ms)
                }
            }
        }

        return byPkg
    }

    private fun mergeUsage(target: MutableMap<String, Long>, incoming: Map<String, Long>) {
        for ((pkg, ms) in incoming) {
            if (pkg.isBlank() || ms <= 0L) continue
            target[pkg] = maxOf(target[pkg] ?: 0L, ms)
        }
    }

    private fun queryAggregateUsage(
        usm: UsageStatsManager,
        from: Long,
        to: Long,
        onlyPackage: String?
    ): Map<String, Long> {
        val out = HashMap<String, Long>()
        val aggregated = try {
            usm.queryAndAggregateUsageStats(from, to)
        } catch (_: SecurityException) {
            emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
        for ((pkg, st) in aggregated.orEmpty()) {
            if (onlyPackage != null && pkg != onlyPackage) continue
            val t = st.totalTimeInForeground.coerceAtLeast(0L)
            if (t > 0L) out[pkg] = maxOf(out[pkg] ?: 0L, t)
        }
        return out
    }

    private fun queryDailyUsage(
        usm: UsageStatsManager,
        from: Long,
        to: Long,
        onlyPackage: String?
    ): Map<String, Long> {
        val out = HashMap<String, Long>()
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
        for (st in stats.orEmpty()) {
            val pkg = st.packageName ?: continue
            if (onlyPackage != null && pkg != onlyPackage) continue
            val overlapsWindow = st.lastTimeStamp <= 0L || st.lastTimeStamp > from
            if (!overlapsWindow) continue
            val t = st.totalTimeInForeground.coerceAtLeast(0L)
            if (t > 0L) out[pkg] = maxOf(out[pkg] ?: 0L, t)
        }
        return out
    }

    private fun queryEventDerivedUsage(
        usm: UsageStatsManager,
        from: Long,
        to: Long,
        onlyPackage: String?
    ): Map<String, Long> {
        val events = try {
            usm.queryEvents(from, to)
        } catch (_: SecurityException) {
            return emptyMap()
        } catch (_: Throwable) {
            return emptyMap()
        }

        val starts = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            if (onlyPackage != null && pkg != onlyPackage) continue
            when (e.eventType) {
                EVENT_ACTIVITY_RESUMED -> {
                    val startAt = e.timeStamp.coerceAtLeast(from)
                    val prev = starts[pkg]
                    if (prev == null || startAt < prev) starts[pkg] = startAt
                }

                EVENT_ACTIVITY_PAUSED,
                EVENT_ACTIVITY_STOPPED -> {
                    val startAt = starts.remove(pkg) ?: continue
                    val endAt = e.timeStamp.coerceAtMost(to)
                    if (endAt > startAt) {
                        totals[pkg] = (totals[pkg] ?: 0L) + (endAt - startAt)
                    }
                }
            }
        }

        for ((pkg, startAt) in starts) {
            val safeStart = startAt.coerceIn(from, to)
            if (to > safeStart) {
                totals[pkg] = (totals[pkg] ?: 0L) + (to - safeStart)
            }
        }

        return totals
    }

    private fun addRangeToHourlyBuckets(
        buckets: LongArray,
        dayStart: Long,
        startMs: Long,
        endMs: Long
    ) {
        val safeStart = startMs.coerceAtLeast(dayStart)
        val safeEnd = endMs.coerceAtLeast(safeStart)
        if (safeEnd <= safeStart) return

        val hourMs = TimeUnit.HOURS.toMillis(1)
        var cursor = safeStart
        while (cursor < safeEnd) {
            val hourIndex = (((cursor - dayStart) / hourMs).toInt()).coerceIn(0, 23)
            val hourEnd = minOf(dayStart + ((hourIndex + 1) * hourMs), safeEnd)
            if (hourEnd > cursor) buckets[hourIndex] += (hourEnd - cursor)
            cursor = hourEnd
        }
    }

    private fun getBucketedUsageByPackage(
        ctx: Context,
        from: Long,
        to: Long,
        onlyPackage: String?
    ): HashMap<String, Long> {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Throwable) {
            emptyList()
        }

        val byPkg = HashMap<String, Long>()
        for (st in stats.orEmpty()) {
            val pkg = st.packageName ?: continue
            if (onlyPackage != null && pkg != onlyPackage) continue
            val t = st.totalTimeInForeground.coerceAtLeast(0L)
            if (t > 0L) byPkg[pkg] = (byPkg[pkg] ?: 0L) + t
        }
        return byPkg
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
