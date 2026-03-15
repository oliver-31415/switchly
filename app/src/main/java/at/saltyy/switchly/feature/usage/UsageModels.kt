package at.saltyy.switchly.feature.usage

import android.graphics.drawable.Drawable

data class AppUsage(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val timeMs: Long,
    val percent: Float
)

data class UsageSummary(
    val totalTimeMs: Long,
    val topApps: List<AppUsage>
)
