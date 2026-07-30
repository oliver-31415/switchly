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

// Persists and retrieves temp allow state.
object TempAllowStore {
    private const val PREFS = "switchly_prefs"
    private const val KEY_PREFIX = "temp_allow_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(pkg: String): String = KEY_PREFIX + pkg

    fun allow(context: Context, pkg: String, durationMillis: Long) {
        val until = System.currentTimeMillis() + durationMillis
        prefs(context).edit { putLong(key(pkg), until) }
    }

    fun isAllowed(context: Context, pkg: String): Boolean {
        val sharedPreferences = prefs(context)
        val key = key(pkg)
        val until = sharedPreferences.getLong(key, 0L)
        if (until == 0L) {
            return false
        }

        if (System.currentTimeMillis() > until) {
            sharedPreferences.edit { remove(key) }
            return false
        }

        return true
    }
}
