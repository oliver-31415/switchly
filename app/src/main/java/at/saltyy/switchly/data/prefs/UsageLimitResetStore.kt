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

/**
 * Reset behavior for app time limits.
 *
 * DAY keeps the existing behavior: an app limit is consumed once per day/profile.
 * SESSION resets the allowance whenever a new active Switchly/profile session starts.
 */
object UsageLimitResetStore {
    const val MODE_DAY = "day"
    const val MODE_SESSION = "session"

    private const val PREFS = "switchly_prefs"
    private const val PREFIX_MODE = "usage_limit_reset__" // + profile + "__" + pkg

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(profile: String, pkg: String): String = PREFIX_MODE + profile + "__" + pkg

    fun getMode(context: Context, profile: String, pkg: String): String {
        if (profile.isBlank() || pkg.isBlank()) {
            return MODE_DAY
        }
        return when (prefs(context).getString(key(profile, pkg), MODE_DAY)) {
            MODE_SESSION -> MODE_SESSION
            else -> MODE_DAY
        }
    }

    fun isSessionMode(context: Context, profile: String, pkg: String): Boolean =
        getMode(context, profile, pkg) == MODE_SESSION

    fun setMode(context: Context, profile: String, pkg: String, mode: String) {
        if (profile.isBlank() || pkg.isBlank()) {
            return
        }
        val k = key(profile, pkg)
        prefs(context).edit {
            when (mode) {
                MODE_SESSION -> putString(k, MODE_SESSION)
                else -> remove(k) // missing = per day, keeps old/default behavior clean
            }
        }
    }

    fun clearMode(context: Context, profile: String, pkg: String) {
        if (profile.isBlank() || pkg.isBlank()) {
            return
        }
        prefs(context).edit { remove(key(profile, pkg)) }
    }
}
