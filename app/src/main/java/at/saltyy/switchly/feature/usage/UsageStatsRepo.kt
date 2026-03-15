package at.saltyy.switchly.feature.usage

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.TimeUnit

object UsageStatsRepo {

    private fun startOfDayLocal(timeMs: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = timeMs
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfTodayLocal(): Long = startOfDayLocal(System.currentTimeMillis())

    private fun startOfTomorrowLocal(): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = startOfTodayLocal()
        c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
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
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        val from = c.timeInMillis
        val to = System.currentTimeMillis()
        return getSummary(ctx, from, to, topN)
    }

    fun getThisYearSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
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

    fun getLast7DaysPerDay(ctx: Context, packageName: String): List<Long> {
        // Build 7 true calendar-day buckets (local timezone): oldest -> newest (today)
        val todayStart = startOfTodayLocal()
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = todayStart

        val dayStarts = mutableListOf<Long>()
        for (i in 0 until 7) {
            dayStarts.add(c.timeInMillis)
            c.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        dayStarts.reverse()

        val res = mutableListOf<Long>()
        for (i in 0 until dayStarts.size) {
            val start = dayStarts[i]
            val end = if (i == dayStarts.lastIndex) startOfTomorrowLocal() else dayStarts[i + 1]
            val sum = getSummary(ctx, start, end, topN = 1, onlyPackage = packageName).totalTimeMs
            res.add(sum)
        }
        return res
    }

    fun getLastNDaysPerDay(ctx: Context, packageName: String, days: Int): List<Long> {
        // Build N true calendar-day buckets (local timezone): oldest -> newest (today)
        val n = days.coerceAtLeast(1).coerceAtMost(60)
        val todayStart = startOfTodayLocal()
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = todayStart

        val dayStarts = mutableListOf<Long>()
        for (i in 0 until n) {
            dayStarts.add(c.timeInMillis)
            c.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        dayStarts.reverse()

        val res = mutableListOf<Long>()
        for (i in 0 until dayStarts.size) {
            val start = dayStarts[i]
            val end = if (i == dayStarts.lastIndex) startOfTomorrowLocal() else dayStarts[i + 1]
            val sum = getSummary(ctx, start, end, topN = 1, onlyPackage = packageName).totalTimeMs
            res.add(sum)
        }
        return res
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

        // Common launcher packages across OEMs
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

        // Heuristic: anything that clearly looks like a launcher
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
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to)

        val byPkg = HashMap<String, Long>()
        for (st in stats) {
            val pkg = st.packageName ?: continue
            if (onlyPackage != null && pkg != onlyPackage) continue
            val t = st.totalTimeInForeground
            if (t > 0) byPkg[pkg] = (byPkg[pkg] ?: 0L) + t
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

                // Drop packages that are no longer installed (stale usage records).
        run {
            val it2 = byPkg.keys.iterator()
            while (it2.hasNext()) {
                val pkg = it2.next()
                if (!isInstalled(ctx, pkg)) it2.remove()
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
                val icon = try { pm.getApplicationIcon(pkg) } catch (_: Throwable) { null }
                val percent = if (total > 0) (ms.toFloat()/total.toFloat()) else 0f
                AppUsage(pkg, label, icon, ms, percent)
            }

        return UsageSummary(totalTimeMs = total, topApps = top)
    }
}

    private fun isInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }


