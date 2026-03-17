package at.saltyy.switchly.util

import android.content.Context
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.feature.usage.UsageStatsRepo

/**
 * Single source of truth for app usage "today" when user-facing app timers are involved.
 *
 * If Usage Access is granted, prefer the system-reported value so Switchly's shown usage and
 * enforcement match Android's Screen Time / Digital Wellbeing more closely.
 *
 * If Usage Access is unavailable (or the system call fails), fall back to Switchly's internal
 * tick-based counter so limits can still work.
 */
object AppUsageToday {
    fun getUsageMsToday(
        ctx: Context,
        pkg: String,
        now: Long = System.currentTimeMillis()
    ): Long {
        val systemMs = if (UsageStatsRepo.hasUsageAccess(ctx)) {
            runCatching { UsageStatsRepo.getTodayMsForPackage(ctx, pkg, now) }.getOrDefault(0L)
        } else {
            0L
        }
        if (systemMs > 0L || UsageStatsRepo.hasUsageAccess(ctx)) return systemMs
        return runCatching { UsageStore.getUsageMsToday(ctx, pkg) }.getOrDefault(0L)
    }
}
