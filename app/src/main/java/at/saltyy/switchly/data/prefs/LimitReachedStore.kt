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
import java.util.Calendar

/**
 * Tracks whether a usage-limited app has reached its limit for today.
 * Once reached, we consider the app "blocked" until the next day reset (or until the user uses emergency bypass/temporary allow).
 */
object LimitReachedStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "limit_reached_" // limit_reached_yyyymmdd_pkg

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(ymd: Int, pkg: String): String = PREFIX + ymd + "_" + pkg
    private fun key(ymd: Int, profile: String, pkg: String): String =
        PREFIX + ymd + "_" + profile.safeKeyPart() + "_" + pkg.safeKeyPart()

    fun isReachedToday(context: Context, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return prefs(context).getBoolean(key(todayYmdInt(), pkg), false)
    }

    fun isReachedToday(context: Context, profile: String, pkg: String): Boolean {
        if (profile.isBlank() || pkg.isBlank()) return false
        val sp = prefs(context)
        val ymd = todayYmdInt()
        return sp.getBoolean(key(ymd, profile, pkg), false)
    }

    fun markReachedToday(context: Context, pkg: String) {
        if (pkg.isBlank()) return

        val sharedPreferences = prefs(context)
        val key = key(todayYmdInt(), pkg)
        val alreadyReached = sharedPreferences.getBoolean(key, false)

        sharedPreferences.edit { putBoolean(key, true) }

        if (!alreadyReached) {
            LimitHitCountStore.incrementToday(context)
        }
    }

    fun markReachedToday(context: Context, profile: String, pkg: String) {
        if (profile.isBlank() || pkg.isBlank()) return

        val sharedPreferences = prefs(context)
        val key = key(todayYmdInt(), profile, pkg)
        val alreadyReached = sharedPreferences.getBoolean(key, false)

        sharedPreferences.edit { putBoolean(key, true) }

        if (!alreadyReached) {
            LimitHitCountStore.incrementToday(context)
        }
    }

    fun clearToday(context: Context, pkg: String) {
        if (pkg.isBlank()) return
        val ymd = todayYmdInt()
        val prefix = PREFIX + ymd + "_"
        val suffix = "_" + pkg.safeKeyPart()
        val sp = prefs(context)
        val profileScopedKeys = sp.all.keys.filter { it.startsWith(prefix) && it.endsWith(suffix) }
        sp.edit {
            remove(key(ymd, pkg))
            profileScopedKeys.forEach { remove(it) }
        }
    }

    fun clearToday(context: Context, profile: String, pkg: String) {
        if (profile.isBlank() || pkg.isBlank()) return
        prefs(context).edit { remove(key(todayYmdInt(), profile, pkg)) }
    }

    private fun String.safeKeyPart(): String = replace("_", "__")

    private fun todayYmdInt(): Int = ymdInt(Calendar.getInstance())

    private fun ymdInt(calendar: Calendar): Int {
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        return (year * 10000) + (month * 100) + day
    }
}
