package at.saltyy.switchly.feature.stats

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.TimeUnit

class UsageStatsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        usm.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - TimeUnit.DAYS.toMillis(7),
            now
        )
    }
}
