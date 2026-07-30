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

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object TimeFormatPrefs {
    const val KEY = "pref_time_format"
    private const val VALUE_SYSTEM = "system"
    private const val VALUE_24H = "24h"
    private const val VALUE_12H = "12h"

    fun getMode(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(KEY, VALUE_SYSTEM) ?: VALUE_SYSTEM
    }

    fun setMode(context: Context, mode: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(KEY, mode)
        }
    }

    fun is24Hour(context: Context): Boolean {
        return when (getMode(context)) {
            VALUE_24H -> true
            VALUE_12H -> false
            else -> android.text.format.DateFormat.is24HourFormat(context)
        }
    }

    fun formatMinutesOfDay(context: Context, minutes: Int): String {
        val safe = minutes.coerceIn(0, 24 * 60 - 1)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, safe / 60)
            set(Calendar.MINUTE, safe % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val pattern = if (is24Hour(context)) "HH:mm" else "h:mm a"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(cal.time)
    }
}
