package at.saltyy.switchly.data.statsdb

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stat_events",
    indices = [
        Index("ts"),
        Index("type"),
        Index("packageName"),
        Index("profileId")
    ]
)
data class StatEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val type: Int,
    val packageName: String?,
    val profileId: String?,
    val reason: String?,
    val metaJson: String?
)
