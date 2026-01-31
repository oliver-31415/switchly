package at.saltyy.switchly.feature.stats

/** Small formatting helpers shared across stats screens/adapters. */
object StatsFormat {
    fun prettyMs(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalSec = (ms / 1000L).toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
    }
}
