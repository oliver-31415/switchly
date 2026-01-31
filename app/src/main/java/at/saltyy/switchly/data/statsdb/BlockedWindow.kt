package at.saltyy.switchly.data.statsdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocked_windows",
    indices = [
        Index("packageName"),
        Index("profileId"),
        Index("startTs"),
        Index("endTs")
    ]
)
data class BlockedWindow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val profileId: String?,
    val reason: String?,
    val startTs: Long,
    val endTs: Long? = null
)
