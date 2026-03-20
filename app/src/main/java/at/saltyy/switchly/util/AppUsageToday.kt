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
 * If the system currently reports 0 (common on some OEMs for the ongoing day), fall back to
 * Switchly's internal tick-based counter so limits and daily statistics still show real usage.
 */
object AppUsageToday {
    fun getUsageMsToday(
        ctx: Context,
        pkg: String,
        now: Long = System.currentTimeMillis()
    ): Long {
        val hasUsage = UsageStatsRepo.hasUsageAccess(ctx)
        val systemMs = if (hasUsage) {
            runCatching { UsageStatsRepo.getTodayMsForPackage(ctx, pkg, now) }.getOrDefault(0L)
        } else {
            0L
        }
        val internalMs = runCatching { UsageStore.getUsageMsToday(ctx, pkg) }.getOrDefault(0L)
        return when {
            systemMs > 0L && internalMs > 0L -> maxOf(systemMs, internalMs)
            systemMs > 0L -> systemMs
            internalMs > 0L -> internalMs
            else -> 0L
        }
    }
}
