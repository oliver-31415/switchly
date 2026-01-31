package at.saltyy.switchly.data.statsdb

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        StatEvent::class,
        BlockedWindow::class,
        DailyAppStat::class
    ],
    version = 1,
    exportSchema = false
)
abstract class StatsDatabase : RoomDatabase() {
    abstract fun dao(): StatsDao

    companion object {
        @Volatile private var INSTANCE: StatsDatabase? = null

        fun get(ctx: Context): StatsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    StatsDatabase::class.java,
                    "switchly_stats.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
