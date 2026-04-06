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
import at.saltyy.switchly.util.AppBlockSafety

object ProfileStore {
    private const val PREFS = "switchly_prefs"

    private const val KEY_PROFILES = "profiles"        // Set<String>
    private const val KEY_CURRENT = "current_profile"  // String

    private fun keyBlocked(profile: String) = "blocked_apps_$profile" // Set<String>

    // Returns all profiles (Set).
    fun getProfiles(context: Context): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Default: create a profile set on first run.
        if (!sp.contains(KEY_PROFILES)) {
            val initial = setOf("Default")
            sp.edit { putStringSet(KEY_PROFILES, initial) }
            return initial
        }

        // Strict: we only support StringSet storage. If the stored type is wrong, reset to default.
        val read: Set<String> = try {
            sp.getStringSet(KEY_PROFILES, emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }

        if (read.isNotEmpty()) return read

        // Stored set is empty or invalid -> reset to default.
        val initial = setOf("Default")
        sp.edit {
            remove(KEY_PROFILES)
            putStringSet(KEY_PROFILES, initial)
        }
        return initial
    }

    // Adds a new profile if it doesn't exist yet.
    fun addProfile(context: Context, name: String): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getProfiles(context).toMutableSet()
        if (name in current) return false
        current += name
        sp.edit { putStringSet(KEY_PROFILES, current) }
        return true
    }

    // Removes a profile and its blocked-app data.
    fun removeProfile(context: Context, name: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getProfiles(context).toMutableSet()
        if (!current.remove(name)) return

        sp.edit {
            putStringSet(KEY_PROFILES, current)

            val active = getCurrent(context)
            if (active == name) {
                putString(KEY_CURRENT, current.firstOrNull() ?: "")
            }

            remove(keyBlocked(name))
        }

        DomainLimitStore.onProfileRemoved(context, name)
    }

    // Renames a profile.
    fun renameProfile(context: Context, old: String, new: String): Boolean {
        if (old == new) return true
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = getProfiles(context).toMutableSet()
        if (!set.contains(old)) return false
        if (set.contains(new)) return false

        val oldBlocked = try {
            sp.getStringSet(keyBlocked(old), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }

        set.remove(old)
        set.add(new)

        sp.edit {
            putStringSet(KEY_PROFILES, set)
            putStringSet(keyBlocked(new), oldBlocked)
            remove(keyBlocked(old))
            if (getCurrent(context) == old) {
                putString(KEY_CURRENT, new)
            }
        }

        DomainLimitStore.onProfileRenamed(context, old, new)
        return true
    }

    // Returns the currently active profile (creates one if missing).
    fun getCurrent(context: Context): String? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getString(KEY_CURRENT, null)
        if (!v.isNullOrEmpty()) return v

        val first = getProfiles(context).firstOrNull()
        if (first != null) {
            sp.edit { putString(KEY_CURRENT, first) }
        }
        return first
    }

    // Sets the currently active profile (only if it exists).
    fun setCurrent(context: Context, name: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = getProfiles(context)
        if (name in all) {
            sp.edit { putString(KEY_CURRENT, name) }
        }
    }

    // Returns all blocked package names for a specific profile.
    fun getBlockedForProfile(context: Context, profile: String): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = try {
            sp.getStringSet(keyBlocked(profile), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            sp.edit { remove(keyBlocked(profile)) }
            emptySet()
        }

        if (raw.isEmpty()) return emptySet()

        // Keep the stored set mostly intact (minus blanks), but always exclude packages that Switchly currently protects for device safety (for example the active launcher or keyboard).
        return raw
            .filterTo(linkedSetOf()) { it.isNotBlank() }
            .let { AppBlockSafety.sanitizeManagedPackages(context, it) }
    }

    // Updates the blocked-app list for the given profile.
    fun setBlockedForProfile(context: Context, profile: String, pkgs: Set<String>) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sanitized = AppBlockSafety.sanitizeManagedPackages(context, pkgs)
        sp.edit { putStringSet(keyBlocked(profile), sanitized) }
    }
}
