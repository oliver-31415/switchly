package at.saltyy.switchly.feature.stats

// Small formatting helpers shared across stats screens/adapters.
object StatsFormat {
    fun prettyMsWithSeconds(ms: Long): String {
        if (ms <= 0L) return "0m 0s"
        val totalSec = (ms/1000L).toInt()
        val h = totalSec/3600
        val m = (totalSec % 3600)/60
        val s = totalSec % 60
        return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
    }

    fun prettyPercent(p: Float): String {
        val v = (p * 100f)
        return if (v.isNaN()) "0.0%" else String.format(java.util.Locale.getDefault(), "%.1f%%", v)
    }

    fun prettyMs(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalSec = (ms/1000L).toInt()
        val h = totalSec/3600
        val m = (totalSec % 3600)/60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm".format(m)
    }
}
