package at.saltyy.switchly.data.statsdb

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.util.Calendar

/**
 * Simple Firestore mapping for daily aggregates.
 *
 * We intentionally sync *daily aggregates* instead of raw event logs:
 * - much smaller payload
 * - stable over time
 * - enough for charts/insights
 */
object StatsCloudMapper {

    private const val TAG = "StatsCloudMapper"

    /**
     * Export daily stats for the last [maxDays] days.
     * Format: [ {"ymd": 20251231, "pkg": "com.x", "b": 1234, "c": 2, "a": 5}, ... ]
     */
    fun exportDaily(ctx: Context, maxDays: Int = 365): List<Map<String, Any>> {
        return runBlocking {
            try {
                val repo = StatsRepository.get(ctx)
                val to = todayYmdInt()
                val from = ymdDaysAgo(maxDays - 1)
                repo.listDailyBetween(from, to).map { s ->
                    mapOf(
                        "ymd" to s.ymd,
                        "pkg" to s.packageName,
                        "b" to s.blockedMs,
                        "c" to s.blockCount,
                        "a" to s.attemptCount
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "exportDaily failed", t)
                emptyList()
            }
        }
    }

    fun importDaily(ctx: Context, raw: Any?) {
        val list = raw as? List<*> ?: return
        val parsed = ArrayList<DailyAppStat>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: continue
            val ymd = (m["ymd"] as? Number)?.toInt() ?: continue
            val pkg = m["pkg"] as? String ?: continue
            val b = (m["b"] as? Number)?.toLong() ?: 0L
            val c = (m["c"] as? Number)?.toInt() ?: 0
            val a = (m["a"] as? Number)?.toInt() ?: 0
            parsed.add(
                DailyAppStat(
                    ymd = ymd,
                    packageName = pkg,
                    blockedMs = b,
                    blockCount = c,
                    attemptCount = a
                )
            )
        }

        runBlocking {
            try {
                StatsRepository.get(ctx).replaceAllDaily(parsed)
            } catch (t: Throwable) {
                Log.e(TAG, "importDaily failed", t)
            }
        }
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdDaysAgo(daysAgo: Int): Int {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
        return ymdInt(cal)
    }

    private fun ymdInt(cal: Calendar): Int {
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return (y * 10000) + (m * 100) + d
    }
}
