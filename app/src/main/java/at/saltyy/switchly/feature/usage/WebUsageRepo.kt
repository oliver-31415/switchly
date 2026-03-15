package at.saltyy.switchly.feature.usage

import android.content.Context
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.WebUsageStore

object WebUsageRepo {

    fun getLast7DaysSummary(ctx: Context, topN: Int = 20): UsageSummary {
        return getLastNDaysSummary(ctx, days = 7, topN = topN)
    }

    fun getLastNDaysSummary(ctx: Context, days: Int, topN: Int = 20): UsageSummary {
        return getSummary(ctx, days = days, topN = topN)
    }

    fun getThisMonthSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val c = java.util.Calendar.getInstance()
        val today = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        // days since start of month including today
        val dayMs = 24L * 60L * 60L * 1000L
        val startDay = (c.timeInMillis/dayMs)
        val endDay = (today.timeInMillis/dayMs)
        val days = ((endDay - startDay) + 1).toInt().coerceAtLeast(1).coerceAtMost(366)
        return getSummary(ctx, days = days, topN = topN)
    }

    fun getThisYearSummary(ctx: Context, topN: Int = 20): UsageSummary {
        val c = java.util.Calendar.getInstance()
        val today = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.MONTH, java.util.Calendar.JANUARY)
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val dayMs = 24L * 60L * 60L * 1000L
        val startDay = (c.timeInMillis/dayMs)
        val endDay = (today.timeInMillis/dayMs)
        val days = ((endDay - startDay) + 1).toInt().coerceAtLeast(1).coerceAtMost(366)
        return getSummary(ctx, days = days, topN = topN)
    }

    fun getOverallSummary(ctx: Context, topN: Int = 20): UsageSummary {
        WebUsageStore.flush(ctx)
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
        val totalsByDomain = HashMap<String, Long>()

        for ((k, v) in prefs.all) {
            if (!k.startsWith("web_usage_day_")) continue
            // web_usage_day_YYYYMMDD_<domain>
            val rest = k.removePrefix("web_usage_day_")
            if (rest.length <= 9) continue
            val domain = rest.substring(9)
            val ms = (v as? Long) ?: 0L
            if (domain.isNotBlank() && ms > 0L) {
                totalsByDomain[domain] = (totalsByDomain[domain] ?: 0L) + ms
            }
        }

        if (totalsByDomain.isEmpty()) return UsageSummary(0L, emptyList())

        val totalAll = totalsByDomain.values.sum().coerceAtLeast(1L)
        val icon = ContextCompat.getDrawable(ctx, R.drawable.language_24)

        val top = totalsByDomain.entries
            .sortedByDescending { it.value }
            .take(topN)
            .map { (domain, ms) ->
                AppUsage(
                    packageName = domain,
                    label = domain,
                    icon = icon,
                    timeMs = ms,
                    percent = (ms.toFloat()/totalAll.toFloat()).coerceIn(0f, 1f)
                )
            }

        return UsageSummary(totalTimeMs = totalsByDomain.values.sum(), topApps = top)
    }

    fun getTodaySummary(ctx: Context, topN: Int = 20): UsageSummary {
        return getSummary(ctx, days = 1, topN = topN)
    }

    private fun getSummary(ctx: Context, days: Int, topN: Int): UsageSummary {
        // Include buffered increments from the Accessibility service.
        WebUsageStore.flush(ctx)
        val domains = WebUsageStore.getDomains(ctx)
        if (domains.isEmpty()) return UsageSummary(0L, emptyList())

        val totals = ArrayList<Pair<String, Long>>(domains.size)
        for (d in domains) {
            val sum = WebUsageStore.getUsageMsForLastNDays(ctx, d, days).sum()
            if (sum > 0L) totals.add(d to sum)
        }
        val totalAll = totals.sumOf { it.second }.coerceAtLeast(1L)
        val icon = ContextCompat.getDrawable(ctx, R.drawable.language_24)

        val top = totals
            .sortedByDescending { it.second }
            .take(topN)
            .map { (domain, ms) ->
                AppUsage(
                    packageName = domain,
                    label = domain,
                    icon = icon,
                    timeMs = ms,
                    percent = (ms.toFloat()/totalAll.toFloat()).coerceIn(0f, 1f)
                )
            }

        return UsageSummary(totalTimeMs = totals.sumOf { it.second }, topApps = top)
    }
}
