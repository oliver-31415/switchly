package at.saltyy.switchly.feature.stats

data class ScreenTimeRow(
    val packageName: String,
    val appName: String,
    val usedMs: Long
)
