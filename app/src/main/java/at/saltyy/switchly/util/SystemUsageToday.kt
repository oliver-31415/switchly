package at.saltyy.switchly.util

import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * Small helper to read system-reported foreground time for "today".
 * Note: Some devices only update totals when the app is backgrounded.
 * Use this as a best-effort signal (e.g., for UI and as a fallback for enforcement), not as the only real-time source.
 */
object SystemUsageToday {
    fun getUsageMsToday(ctx: Context, pkg: String, now: Long = System.currentTimeMillis()): Long {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return 0L

        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis

        return try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now) ?: return 0L
            val s = stats.firstOrNull { it.packageName == pkg } ?: return 0L
            s.totalTimeInForeground.coerceAtLeast(0L)
        } catch (_: SecurityException) {
            0L
        } catch (_: Throwable) {
            0L
        }
    }
}
