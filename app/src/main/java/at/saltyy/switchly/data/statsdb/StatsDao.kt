/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package at.saltyy.switchly.data.statsdb

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface StatsDao {

    // --- Events ---
    @Insert
    suspend fun insertEvent(ev: StatEvent): Long

    @Query("SELECT * FROM stat_events WHERE ts BETWEEN :fromTs AND :toTs ORDER BY ts ASC")
    suspend fun listEventsBetween(fromTs: Long, toTs: Long): List<StatEvent>

    // --- Blocked windows ---
    @Insert
    suspend fun insertWindow(win: BlockedWindow): Long

    @Update
    suspend fun updateWindow(win: BlockedWindow)

    @Query("SELECT * FROM blocked_windows WHERE packageName = :pkg AND endTs IS NULL ORDER BY startTs DESC LIMIT 1")
    suspend fun getOpenWindowForPkg(pkg: String): BlockedWindow?

    @Query("SELECT * FROM blocked_windows WHERE profileId = :profileId AND endTs IS NULL")
    suspend fun getOpenWindowsForProfile(profileId: String): List<BlockedWindow>

    @Query("UPDATE blocked_windows SET endTs = :endTs WHERE packageName = :pkg AND endTs IS NULL")
    suspend fun closeAllOpenWindowsForPkg(pkg: String, endTs: Long)

    @Query("UPDATE blocked_windows SET endTs = :endTs WHERE profileId = :profileId AND endTs IS NULL")
    suspend fun closeAllOpenWindowsForProfile(profileId: String, endTs: Long)

    // --- Daily aggregates ---
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDaily(stat: DailyAppStat)

    @Query("SELECT * FROM daily_app_stats WHERE ymd BETWEEN :fromYmd AND :toYmd")
    suspend fun listDailyBetween(fromYmd: Int, toYmd: Int): List<DailyAppStat>

    @Query("SELECT * FROM daily_app_stats WHERE ymd = :ymd")
    suspend fun listDailyForDay(ymd: Int): List<DailyAppStat>

    @Query("SELECT * FROM daily_app_stats WHERE ymd BETWEEN :fromYmd AND :toYmd AND packageName = :pkg")
    suspend fun listDailyForPkgBetween(pkg: String, fromYmd: Int, toYmd: Int): List<DailyAppStat>

    @Query("DELETE FROM stat_events")
    suspend fun deleteAllEvents()

    @Query("DELETE FROM blocked_windows")
    suspend fun deleteAllWindows()

    @Query("DELETE FROM daily_app_stats")
    suspend fun deleteAllDaily()
}
