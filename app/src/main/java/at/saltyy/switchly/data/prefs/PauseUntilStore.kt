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

package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object PauseUntilStore {
    private const val PREFS = "switchly_pause_until"
    private const val KEY_LOCATION_SCHEDULE_ID = "location_schedule_id"
    private const val KEY_LOCATION_ACTIVE = "location_active"

    fun markUntilLocationExit(context: Context, scheduleId: Int) {
        if (scheduleId <= 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_LOCATION_ACTIVE, true)
            putInt(KEY_LOCATION_SCHEDULE_ID, scheduleId)
        }
    }

    fun shouldEndOnLocationExit(context: Context, scheduleId: Int): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_LOCATION_ACTIVE, false) && sp.getInt(KEY_LOCATION_SCHEDULE_ID, -1) == scheduleId
    }

    fun clearLocationPause(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            remove(KEY_LOCATION_ACTIVE)
            remove(KEY_LOCATION_SCHEDULE_ID)
        }
    }
}
