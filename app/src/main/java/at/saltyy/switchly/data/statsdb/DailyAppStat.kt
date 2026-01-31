package at.saltyy.switchly.data.statsdb

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "daily_app_stats",
    primaryKeys = ["ymd", "packageName"],
    indices = [
        Index("ymd"),
        Index("packageName")
    ]
)
data class DailyAppStat(
    val ymd: Int,
    val packageName: String,
    val blockedMs: Long,
    val blockCount: Int,
    val attemptCount: Int
)
