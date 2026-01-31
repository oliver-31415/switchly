package at.saltyy.switchly.feature.stats

data class StatsRow(
    val packageName: String,
    val appName: String,
    val limitMinutes: Int,
    val usedMsToday: Long,
    val blockedMsToday: Long = 0L
)
