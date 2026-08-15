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
 * Defensive numeric SharedPreferences accessors.
 * Restores and older backup formats can rehydrate integral values with a different storage type. 
 * Android's typed getters throw ClassCastException in that case, so these helpers preserve the value and heal it back to the expected type.
 */
fun SharedPreferences.getIntCompat(key: String, defaultValue: Int = 0): Int {
    return try {
        getInt(key, defaultValue)
    } catch (_: ClassCastException) {
        val value = coerceIntCompatValue(all[key], defaultValue)
        edit { putInt(key, value) }
        value
    }
}

fun SharedPreferences.getLongCompat(key: String, defaultValue: Long = 0L): Long {
    return try {
        getLong(key, defaultValue)
    } catch (_: ClassCastException) {
        val value = coerceLongCompatValue(all[key], defaultValue)
        edit { putLong(key, value) }
        value
    }
}

internal fun coerceIntCompatValue(raw: Any?, defaultValue: Int): Int {
    val value = when (raw) {
        is Int -> raw.toLong()
        is Number -> raw.toLong()
        is String -> raw.toLongOrNull()
        else -> null
    } ?: return defaultValue

    return value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
}

internal fun coerceLongCompatValue(raw: Any?, defaultValue: Long): Long = when (raw) {
    is Long -> raw
    is Number -> raw.toLong()
    is String -> raw.toLongOrNull() ?: defaultValue
    else -> defaultValue
}
