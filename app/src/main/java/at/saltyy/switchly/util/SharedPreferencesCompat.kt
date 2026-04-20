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

package at.saltyy.switchly.util

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Defensive SharedPreferences accessors.
 * Firestore/JSON/etc. can occasionally rehydrate integral values as Long (or String).
 * SharedPreferences is type-strict and will throw ClassCastException if you call getInt() when the stored type is not Int.
 * These helpers avoid crashes and "heal" the stored value back to the expected type.
 */
fun SharedPreferences.getIntCompat(key: String, def: Int = 0): Int {
    return try {
        getInt(key, def)
    } catch (_: ClassCastException) {
        val v = when (val any = all[key]) {
            is Long -> any.toInt()
            is Int -> any
            is String -> any.toIntOrNull() ?: def
            else -> def
        }
        edit { putInt(key, v) }
        v
    }
}
