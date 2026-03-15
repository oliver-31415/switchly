package at.saltyy.switchly.feature.stats

// Row model for the Runtime screen: per-app blocked time.
data class RuntimeBlockedRow(
    val packageName: String,
    val appName: String,
    val blockedMs: Long,
    val blockedCount: Int,
    val attemptCount: Int,
    /**
     * Value used for sorting and for the bar/progress width.
     * We prefer real blockedMs, but fall back to a small proxy based on blocks/attempts so the list isn't empty when blockedMs is 0 due to quick redirects.
     */
    val scoreMs: Long
)
