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

package at.saltyy.switchly.data.statistics

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface StatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertValues(values: List<StatValueEntity>)

    @Query("DELETE FROM stat_values WHERE id = :id")
    fun deleteValue(id: String)

    @Query("DELETE FROM stat_values WHERE id IN (:ids)")
    fun deleteValues(ids: List<String>)

    @Query("SELECT * FROM stat_values ORDER BY prefsName, prefKey")
    fun getAllValues(): List<StatValueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSessions(sessions: List<StatSessionEntity>)

    @Query(
        """
        SELECT * FROM stat_sessions
        WHERE category = :category
          AND (:subject IS NULL OR subject = :subject)
          AND endMs > :startMs
          AND startMs < :endMs
        ORDER BY startMs ASC
        """
    )
    fun getSessions(
        category: String,
        subject: String?,
        startMs: Long,
        endMs: Long,
    ): List<StatSessionEntity>

    @Query("DELETE FROM stat_sessions WHERE category = :category AND subject = :subject")
    fun deleteSessionsForSubject(category: String, subject: String)

    @Query("DELETE FROM stat_sessions WHERE category = :category AND day = :day")
    fun deleteSessionsForDay(category: String, day: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertEvents(events: List<StatEventEntity>)

    @Query("SELECT * FROM stat_events ORDER BY timestampMs ASC")
    fun getAllEvents(): List<StatEventEntity>

    @Query("DELETE FROM stat_events WHERE day = :day")
    fun deleteEventsForDay(day: Int)

    @Query("SELECT * FROM stat_sessions ORDER BY startMs ASC")
    fun getAllSessions(): List<StatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putMetadata(metadata: StatMetadataEntity)

    @Query("SELECT value FROM stat_metadata WHERE `key` = :key LIMIT 1")
    fun getMetadata(key: String): String?

    @Query("DELETE FROM stat_values")
    fun clearValues()

    @Query("DELETE FROM stat_sessions")
    fun clearSessions()

    @Query("DELETE FROM stat_events")
    fun clearEvents()

    @Query("DELETE FROM stat_metadata")
    fun clearMetadata()

    @Transaction
    fun replaceAll(
        values: List<StatValueEntity>,
        sessions: List<StatSessionEntity>,
        events: List<StatEventEntity>,
        metadata: List<StatMetadataEntity>,
    ) {
        clearValues()
        clearSessions()
        clearEvents()
        clearMetadata()
        if (values.isNotEmpty()) {
            upsertValues(values)
        }
        if (sessions.isNotEmpty()) {
            upsertSessions(sessions)
        }
        if (events.isNotEmpty()) {
            upsertEvents(events)
        }
        metadata.forEach(::putMetadata)
    }
}
