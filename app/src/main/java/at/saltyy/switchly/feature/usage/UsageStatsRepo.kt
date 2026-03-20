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
        val now = System.currentTimeMillis()
        val start = startOfTodayLocal()
        return getSummary(ctx, start, now, topN)
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
            if (e.packageName == packageName && e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                sessions++
            }
        }
        return sessions
    }

    fun getTotalMsForWindow(ctx: Context, from: Long, to: Long, packageName: String): Long {
        return getSummary(ctx, from, to, topN = 1, onlyPackage = packageName).totalTimeMs
    }

    fun getTodayMsForPackage(ctx: Context, packageName: String, now: Long = System.currentTimeMillis()): Long {
        return getSummary(ctx, startOfDayLocal(now), now, topN = 1, onlyPackage = packageName).totalTimeMs
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
            getSummary(ctx, start, end, topN = 1, onlyPackage = packageName).totalTimeMs
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
            getSummary(ctx, start, end, topN = 1, onlyPackage = packageName).totalTimeMs
        }
    }

    /**
     * Returns the earliest timestamp (ms) that the system actually reports usage for within the window.
     * Useful for showing a UI banner like: "Data available since ..." on devices that retain only ~1 week.
     */
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

    private fun shouldExcludePackage(pkg: String, homePkgs: Set<String>): Boolean {
        val p = pkg.lowercase()
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
                if (shouldExcludePackage(pkg, homePkgs)) {
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
                UsageEvents.Event.MOVE_TO_FOREGROUND,
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val startAt = e.timeStamp.coerceAtLeast(from)
                    val prev = starts[pkg]
                    if (prev == null || startAt < prev) starts[pkg] = startAt
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND,
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
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
