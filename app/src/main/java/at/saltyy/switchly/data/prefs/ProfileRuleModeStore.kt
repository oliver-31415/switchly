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
 * Per-profile app rule mode.
 * BLOCK_SELECTED: selected apps are blocked.
 * ALLOW_SELECTED: within the picker/listed app set, selected apps are allowed and unselected listed apps are blocked. Apps that are not listed in the picker are treated as allowed.
 */
object ProfileRuleModeStore {
    const val MODE_BLOCK_SELECTED = "block_selected"
    const val MODE_ALLOW_SELECTED = "allow_selected"

    private const val PREFS = "switchly_prefs"
    private const val PREFIX_MODE = "profile_rule_mode__"
    private const val PREFIX_ALLOW_ESSENTIALS = "profile_rule_allow_essentials__"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sanitizeProfile(profile: String): String =
        profile.trim()
            .replace("_", "__")
            .replace(":", "_")
            .ifBlank { "default" }

    private fun keyMode(profile: String): String = PREFIX_MODE + sanitizeProfile(profile)
    private fun keyAllowEssentials(profile: String): String = PREFIX_ALLOW_ESSENTIALS + sanitizeProfile(profile)

    fun getMode(context: Context, profile: String): String {
        val value = prefs(context).getString(keyMode(profile), MODE_BLOCK_SELECTED)
        return if (value == MODE_ALLOW_SELECTED) {
            MODE_ALLOW_SELECTED
        } else {
            MODE_BLOCK_SELECTED
        }
    }

    fun isAllowMode(context: Context, profile: String): Boolean =
        getMode(context, profile) == MODE_ALLOW_SELECTED

    fun setMode(context: Context, profile: String, mode: String) {
        val safe = if (mode == MODE_ALLOW_SELECTED) MODE_ALLOW_SELECTED else MODE_BLOCK_SELECTED
        prefs(context).edit { putString(keyMode(profile), safe) }
    }

    fun shouldAllowEssentialSystemApps(context: Context, profile: String): Boolean =
        prefs(context).getBoolean(keyAllowEssentials(profile), true)

    fun setAllowEssentialSystemApps(context: Context, profile: String, allow: Boolean) {
        prefs(context).edit { putBoolean(keyAllowEssentials(profile), allow) }
    }

    fun onProfileRenamed(context: Context, oldProfile: String, newProfile: String) {
        val p = prefs(context)
        val oldMode = keyMode(oldProfile)
        val newMode = keyMode(newProfile)
        val oldEssentials = keyAllowEssentials(oldProfile)
        val newEssentials = keyAllowEssentials(newProfile)

        p.edit {
            if (p.contains(oldMode)) {
                putString(newMode, p.getString(oldMode, MODE_BLOCK_SELECTED))
                remove(oldMode)
            }
            if (p.contains(oldEssentials)) {
                putBoolean(newEssentials, p.getBoolean(oldEssentials, true))
                remove(oldEssentials)
            }
        }
    }

    fun onProfileRemoved(context: Context, profile: String) {
        prefs(context).edit {
            remove(keyMode(profile))
            remove(keyAllowEssentials(profile))
        }
    }

    fun copyProfile(context: Context, fromProfile: String, toProfile: String) {
        val p = prefs(context)
        p.edit {
            putString(keyMode(toProfile), getMode(context, fromProfile))
            putBoolean(keyAllowEssentials(toProfile), shouldAllowEssentialSystemApps(context, fromProfile))
        }
    }
}
