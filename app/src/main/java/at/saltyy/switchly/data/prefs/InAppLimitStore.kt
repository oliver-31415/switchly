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
import java.util.Locale

/**
 * Profile-based global in-app daily limit (minutes).
 * This is the "overall budget" for timed in-app sections.
 * If set and reached, all timed in-app sections will be blocked (even if they have their own per-surface limit).
 */
object InAppLimitStore {
    private const val PREFS = "switchly_prefs"
    private const val PREFIX = "inapp_limit_min__" // + profile

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(profile: String): String = PREFIX + sanitizeProfile(profile)

    fun getLimitMinutes(context: Context, profile: String): Int {
        val sharedPreferences = prefs(context)
        val key = key(profile)
        if (!sharedPreferences.contains(key)) return 0
        return readMinutesCompat(sharedPreferences, key)
    }

    fun setLimitMinutes(context: Context, profile: String, minutes: Int) {
        val sharedPreferences = prefs(context)
        val key = key(profile)
        val safeMinutes = minutes.coerceAtLeast(0)

        if (safeMinutes <= 0) {
            sharedPreferences.edit { remove(key) }
            return
        }

        sharedPreferences.edit { putInt(key, safeMinutes) }
    }

    private fun sanitizeProfile(profile: String): String {
        return profile.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }

    private fun readMinutesCompat(
        sharedPreferences: SharedPreferences,
        key: String,
    ): Int {
        val value = sharedPreferences.all[key]
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }
}
