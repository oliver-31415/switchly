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
 * Per-profile website rule mode.
 * Older builds used [ProfileRuleModeStore] for both apps and websites.
 * The first read migrates that shared value so existing profiles keep their current behavior, while later changes remain independent from app rules.
 */
object WebsiteRuleModeStore {
    const val MODE_BLOCK_SELECTED = ProfileRuleModeStore.MODE_BLOCK_SELECTED
    const val MODE_ALLOW_SELECTED = ProfileRuleModeStore.MODE_ALLOW_SELECTED

    private const val PREFS = "switchly_prefs"
    private const val PREFIX_MODE = "profile_website_rule_mode__"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyMode(profile: String): String =
        PREFIX_MODE + ProfileRuleModeStore.sanitizeProfile(profile)

    fun getMode(context: Context, profile: String): String {
        val preferences = prefs(context)
        val key = keyMode(profile)
        if (!preferences.contains(key)) {
            val migrated = ProfileRuleModeStore.getMode(context, profile)
            preferences.edit { putString(key, migrated) }
            return migrated
        }

        return when (preferences.getString(key, MODE_BLOCK_SELECTED)) {
            MODE_ALLOW_SELECTED -> MODE_ALLOW_SELECTED
            else -> MODE_BLOCK_SELECTED
        }
    }

    fun isAllowMode(context: Context, profile: String): Boolean =
        getMode(context, profile) == MODE_ALLOW_SELECTED

    fun setMode(context: Context, profile: String, mode: String) {
        val safeMode = if (mode == MODE_ALLOW_SELECTED) {
            MODE_ALLOW_SELECTED
        } else {
            MODE_BLOCK_SELECTED
        }
        prefs(context).edit { putString(keyMode(profile), safeMode) }
    }

    fun onProfileRenamed(context: Context, oldProfile: String, newProfile: String) {
        val preferences = prefs(context)
        val oldKey = keyMode(oldProfile)
        val newKey = keyMode(newProfile)
        val oldMode = getMode(context, oldProfile)
        preferences.edit {
            putString(newKey, oldMode)
            remove(oldKey)
        }
    }

    fun onProfileRemoved(context: Context, profile: String) {
        prefs(context).edit { remove(keyMode(profile)) }
    }

    fun copyProfile(context: Context, fromProfile: String, toProfile: String) {
        prefs(context).edit {
            putString(keyMode(toProfile), getMode(context, fromProfile))
        }
    }
}
