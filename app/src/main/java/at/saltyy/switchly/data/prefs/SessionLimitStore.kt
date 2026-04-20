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

/**
 * Per-app session limits (in minutes).
 * Session = continuous time inside the app. When the user leaves and comes back, the session counter resets.
 */
object SessionLimitStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX_LIMIT_MIN = "session_limit_min__" // + profile + "__" + pkg
    private const val PREFIX_EVER_LIMIT = "session_limit_ever__" // + pkg

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(profile: String, pkg: String): String = PREFIX_LIMIT_MIN + profile + "__" + pkg

    fun getLimitMinutes(context: Context, profile: String, pkg: String): Int {
        val sharedPreferences = prefs(context)
        val key = key(profile, pkg)
        if (!sharedPreferences.contains(key)) return 0
        return readIntOrLongAndMigrate(sharedPreferences, key).coerceAtLeast(0)
    }

    fun setLimitMinutes(context: Context, profile: String, pkg: String, minutes: Int) {
        val sharedPreferences = prefs(context)
        val key = key(profile, pkg)

        if (minutes > 0) {
            sharedPreferences.edit { putBoolean(PREFIX_EVER_LIMIT + pkg, true) }
        }

        if (minutes <= 0) {
            sharedPreferences.edit { remove(key) }
            return
        }

        sharedPreferences.edit { putInt(key, minutes.coerceAtLeast(0)) }
    }

    fun getAllLimitedPackages(context: Context, profile: String): List<String> {
        val sharedPreferences = prefs(context)
        val prefix = PREFIX_LIMIT_MIN + profile + "__"

        return sharedPreferences.all.keys.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { pkg -> getLimitMinutes(context, profile, pkg) > 0 }
            .distinct()
            .sorted()
            .toList()
    }

    fun getAllEverLimitedPackages(context: Context): List<String> {
        val sharedPreferences = prefs(context)
        return sharedPreferences.all.keys.asSequence()
            .filter { it.startsWith(PREFIX_EVER_LIMIT) }
            .map { it.removePrefix(PREFIX_EVER_LIMIT) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .filter { pkg -> isInstalled(context, pkg) }
            .toList()
    }

    private fun isInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun readIntOrLongAndMigrate(
        sharedPreferences: SharedPreferences,
        key: String,
    ): Int {
        if (!sharedPreferences.contains(key)) return 0

        return try {
            sharedPreferences.getInt(key, 0)
        } catch (_: ClassCastException) {
            val value = try {
                sharedPreferences.getLong(key, 0L).toInt()
            } catch (_: Exception) {
                0
            }
            sharedPreferences.edit { putInt(key, value) }
            value
        }
    }
}
