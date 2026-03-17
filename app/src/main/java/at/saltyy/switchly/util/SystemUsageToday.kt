package at.saltyy.switchly.util

import android.content.Context
import at.saltyy.switchly.feature.usage.UsageStatsRepo

/**
 * Small helper to read system-reported foreground time for "today".
 * Note: Some devices only update totals when the app is backgrounded.
 * Use this as a best-effort signal (e.g., for UI and as a fallback for enforcement), not as the only real-time source.
 */
object SystemUsageToday {
    fun getUsageMsToday(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): Long {
        return try {
            UsageStatsRepo.getTodayMsForPackage(ctx, pkg, now)
        } catch (_: Throwable) {
            0L
        }
    }
}
