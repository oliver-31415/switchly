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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import at.saltyy.switchly.data.prefs.UsageStore

object AppUsageRepo {

    fun getTodaySummary(ctx: Context, topN: Int = 20): UsageSummary {
        return UsageStatsRepo.getTodaySummary(ctx, topN)
    }

    fun getDaySummary(ctx: Context, timeMillis: Long, topN: Int = 8): UsageSummary {
        val ymd = java.util.Calendar.getInstance().apply {
            timeInMillis = timeMillis
        }.let { cal ->
            cal.get(java.util.Calendar.YEAR) * 10000 +
                (cal.get(java.util.Calendar.MONTH) + 1) * 100 +
                cal.get(java.util.Calendar.DAY_OF_MONTH)
        }
        val local = UsageStore.getUsageMsMapForDay(ctx, ymd)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.OVERALL) else UsageSummary(0L, emptyList())
    }

    fun getLastNDaysSummary(ctx: Context, days: Int, topN: Int = 20): UsageSummary {
        val local = UsageStore.getUsageMsMapForLastNDays(ctx, days)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.WEEK) else UsageSummary(0L, emptyList())
    }

    fun getThisMonthSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val now = java.util.Calendar.getInstance()
        val local = UsageStore.getUsageMsMapForMonth(ctx, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH) + 1)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.MONTH) else UsageSummary(0L, emptyList())
    }

    fun getThisYearSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val local = UsageStore.getUsageMsMapForYear(ctx, year)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.YEAR) else UsageSummary(0L, emptyList())
    }

    fun getDateRangeSummary(ctx: Context, startMs: Long, endMs: Long, topN: Int = 20): UsageSummary {
        val local = UsageStore.getUsageMsMapForDateRange(ctx, startMs, endMs)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.OVERALL) else UsageSummary(0L, emptyList())
    }

    fun getOverallSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val local = UsageStore.getUsageMsMapOverall(ctx)
        return if (local.isNotEmpty()) buildSummary(ctx, local, topN, UsageSanity.RangeCap.OVERALL) else UsageSummary(0L, emptyList())
    }

    fun getDeviceSummary(ctx: Context, daysOrRange: Int, topN: Int = 20): UsageSummary {
        return when (daysOrRange) {
            1 -> UsageStatsRepo.getTodaySummary(ctx, topN)
            7 -> UsageStatsRepo.getLastNDaysSummary(ctx, 7, topN)
            30 -> UsageStatsRepo.getThisMonthSummary(ctx, topN)
            365 -> UsageStatsRepo.getThisYearSummary(ctx, topN)
            else -> UsageStatsRepo.getOverallSummary(ctx, topN)
        }
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
