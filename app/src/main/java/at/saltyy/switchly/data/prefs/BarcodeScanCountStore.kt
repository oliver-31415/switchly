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
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Calendar

object BarcodeScanCountStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "barcode_scan_count_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(ymd: Int): String = PREFIX + ymd

    fun incrementToday(context: Context, delta: Int = 1) {
        if (delta <= 0) return

        val sharedPreferences = prefs(context)
        val key = key(todayYmdInt())
        val current = readIntCompat(sharedPreferences, key)
        sharedPreferences.edit { putLong(key, (current + delta).toLong()) }
    }

    private fun readIntCompat(sharedPreferences: SharedPreferences, key: String): Int {
        return when (val value = sharedPreferences.all[key]) {
            is Int -> value
            is Long -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toLongOrNull()?.toInt() ?: value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(calendar: Calendar): Int {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return (year * 10000) + (month * 100) + day
    }
}
