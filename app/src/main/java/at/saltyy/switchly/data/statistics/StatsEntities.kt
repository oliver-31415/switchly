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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stat_values",
    indices = [Index(value = ["prefsName", "prefKey"], unique = true)]
)
data class StatValueEntity(
    @PrimaryKey val id: String,
    val prefsName: String,
    val prefKey: String,
    val valueType: String,
    val longValue: Long? = null,
    val realValue: Double? = null,
    val textValue: String? = null,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "stat_sessions",
    indices = [
        Index(value = ["category", "subject", "startMs"], unique = true),
        Index(value = ["category", "startMs"]),
        Index(value = ["day"]),
    ]
)
data class StatSessionEntity(
    @PrimaryKey val id: String,
    val category: String,
    val day: Int,
    val subject: String,
    val startMs: Long,
    val endMs: Long,
    val metadata: String? = null,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "stat_events",
    indices = [
        Index(value = ["day"]),
        Index(value = ["timestampMs"]),
        Index(value = ["category"]),
    ]
)
data class StatEventEntity(
    @PrimaryKey val id: String,
    val day: Int,
    val timestampMs: Long,
    val category: String,
    val tag: String,
    val message: String,
    val rawLine: String,
)

@Entity(tableName = "stat_metadata")
data class StatMetadataEntity(
    @PrimaryKey val key: String,
    val value: String,
)
