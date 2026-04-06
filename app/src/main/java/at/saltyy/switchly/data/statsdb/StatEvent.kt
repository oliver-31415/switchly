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
