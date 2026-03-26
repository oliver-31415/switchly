package at.saltyy.switchly.feature.usage

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import at.saltyy.switchly.data.prefs.UsageStore

object AppUsageRepo {

    fun getTodaySummary(ctx: Context, topN: Int = 20): UsageSummary {
        val local = UsageStore.getUsageMsMapToday(ctx)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.TODAY) else UsageStatsRepo.getTodaySummary(ctx, topN)
    }

    fun getLastNDaysSummary(ctx: Context, days: Int, topN: Int = 20): UsageSummary {
        val local = UsageStore.getUsageMsMapForLastNDays(ctx, days)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.WEEK) else UsageStatsRepo.getLastNDaysSummary(ctx, days, topN)
    }

    fun getThisMonthSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val now = java.util.Calendar.getInstance()
        val local = UsageStore.getUsageMsMapForMonth(ctx, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.MONTH) else UsageStatsRepo.getThisMonthSummary(ctx, topN)
    }

    fun getThisYearSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val local = UsageStore.getUsageMsMapForYear(ctx, year)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.YEAR) else UsageStatsRepo.getThisYearSummary(ctx, topN)
    }

    fun getOverallSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val local = UsageStore.getUsageMsMapOverall(ctx)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.OVERALL) else UsageStatsRepo.getOverallSummary(ctx, topN)
    }

    private fun buildSummary(ctx: Context, raw: Map<String, Long>, topN: Int, rangeCap: UsageSanity.RangeCap): UsageSummary {
        if (raw.isEmpty()) return UsageSummary(0L, emptyList())
        val byPkg = HashMap(raw)
        val homePkgs = getHomePackages(ctx)
        val it = byPkg.keys.iterator()
        while (it.hasNext()) {
            val pkg = it.next()
            if (shouldExcludePackage(ctx, pkg, homePkgs) || !isInstalled(ctx, pkg)) {
                it.remove()
            }
        }
        if (byPkg.isEmpty()) return UsageSummary(0L, emptyList())

        val capped = UsageSanity.capMapToRange(ctx, byPkg, rangeCap)
        val total = capped.values.sum().coerceAtLeast(0L)
        val pm = ctx.packageManager
        val top = capped.entries
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

    private fun isInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
