package at.saltyy.switchly.util

import android.content.Context
import at.saltyy.switchly.data.prefs.UsageStore

/**
 * Single source of truth for app usage "today" when user-facing timers and limits are involved.
 *
 * We intentionally use Switchly's internal per-day counter here. On some devices/OEMs,
 * UsageStats for the current day can be delayed or include stale data from the previous day,
 * which causes apps to be blocked before the real daily limit is actually reached.
 */
object AppUsageToday {
    fun getUsageMsToday(
        ctx: Context,
        pkg: String,
        now: Long = System.currentTimeMillis()
    ): Long {
        return runCatching { UsageStore.getUsageMsToday(ctx, pkg) }.getOrDefault(0L)
    }
}
