package at.saltyy.switchly.data.prefs

import android.content.Context
import androidx.core.content.edit

object ProfileStore {
    private const val PREFS = "switchly_prefs"

    private const val KEY_PROFILES = "profiles"        // Set<String>
    private const val KEY_CURRENT = "current_profile"  // String

    private fun keyBlocked(profile: String) = "blocked_apps_$profile" // Set<String>

    /** Returns all profiles (Set). */
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

    /** Adds a new profile if it doesn't exist yet. */
    fun addProfile(context: Context, name: String): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = getProfiles(context).toMutableSet()
        if (name in current) return false
        current += name
        sp.edit { putStringSet(KEY_PROFILES, current) }
        return true
    }

    /** Removes a profile and its blocked-app data. */
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
    }

    /** Renames a profile. */
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
        return true
    }

    /** Returns the currently active profile (creates one if missing). */
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

    /** Sets the currently active profile (only if it exists). */
    fun setCurrent(context: Context, name: String) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = getProfiles(context)
        if (name in all) {
            sp.edit { putString(KEY_CURRENT, name) }
        }
    }

    /** Returns all blocked package names for a specific profile. */
    fun getBlockedForProfile(context: Context, profile: String): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            sp.getStringSet(keyBlocked(profile), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            sp.edit { remove(keyBlocked(profile)) }
            emptySet()
        }
    }

    /** Updates the blocked-app list for the given profile. */
    fun setBlockedForProfile(context: Context, profile: String, pkgs: Set<String>) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putStringSet(keyBlocked(profile), pkgs) }
    }
}
