package at.saltyy.switchly.feature.stats

data class BlockStatsRow(
    val packageName: String,
    val appName: String,
    val blockedMs: Long,
    val blockedCount: Int,
    val attemptCount: Int
)
