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
 * Per-profile rules for in-app "surfaces" (Shorts/Reels/Explore/Stories/etc).
 * Stored as an Int per (profile, surfaceKey).
 * Values:
 *  -1  => always block this surface (immediate)
 *   0  => no specific rule (falls back to global in-app limit if set)
 *  >0  => daily limit in minutes
 */
object SurfaceLimitStore {
    private const val PREFS = "switchly_prefs"

    // surf_rule__<profile>__<surfaceKey>
    private const val PREFIX_RULE = "surf_rule__"

    private fun sanitizeProfile(profile: String): String {
        return profile.trim()
            .lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "default" }
    }

    private fun key(profile: String, surfaceKey: String): String {
        return PREFIX_RULE + sanitizeProfile(profile) + "__" + surfaceKey
    }

    fun hasRule(ctx: Context, profile: String, surfaceKey: String): Boolean {
        if (surfaceKey.isBlank()) return false
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.contains(key(profile, surfaceKey))
    }

    /**
     * Returns the raw rule value for (profile, surfaceKey).
     * See [SurfaceLimitStore] doc for meaning.
     */
    fun getRule(ctx: Context, profile: String, surfaceKey: String): Int {
        if (surfaceKey.isBlank()) return 0
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, surfaceKey)
        return readIntOrMigrate(prefs, k)
    }

    /**
     * Sets the raw rule value for (profile, surfaceKey).
     * Use -1 for always block, 0 to clear, >0 for minutes/day.
     */
    fun setRule(ctx: Context, profile: String, surfaceKey: String, rule: Int) {
        if (surfaceKey.isBlank()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val k = key(profile, surfaceKey)

        // Store 0 explicitly so we can distinguish "no saved choice" vs "user chose to use global".
        prefs.edit { putInt(k, rule) }
    }

    fun clear(ctx: Context, profile: String, surfaceKey: String) {
        if (surfaceKey.isBlank()) return
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { remove(key(profile, surfaceKey)) }
    }

    fun onProfileRenamed(ctx: Context, oldProfile: String, newProfile: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val oldPrefix = PREFIX_RULE + sanitizeProfile(oldProfile) + "__"
        val newPrefix = PREFIX_RULE + sanitizeProfile(newProfile) + "__"
        val keys = prefs.all.keys.filter { it.startsWith(oldPrefix) }
        if (keys.isEmpty()) return

        prefs.edit {
            keys.forEach { oldKey ->
                val surface = oldKey.removePrefix(oldPrefix)
                val newKey = newPrefix + surface
                val value = readIntOrMigrate(prefs, oldKey)
                putInt(newKey, value)
                remove(oldKey)
            }
        }
    }

    fun onProfileRemoved(ctx: Context, profile: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = PREFIX_RULE + sanitizeProfile(profile) + "__"
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        prefs.edit { keys.forEach { remove(it) } }
    }

    fun copyProfile(ctx: Context, fromProfile: String, toProfile: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fromPrefix = PREFIX_RULE + sanitizeProfile(fromProfile) + "__"
        val toPrefix = PREFIX_RULE + sanitizeProfile(toProfile) + "__"
        val fromKeys = prefs.all.keys.filter { it.startsWith(fromPrefix) }
        prefs.edit {
            prefs.all.keys
                .filter { it.startsWith(toPrefix) }
                .forEach { remove(it) }
            fromKeys.forEach { fromKey ->
                val surface = fromKey.removePrefix(fromPrefix)
                putInt(toPrefix + surface, readIntOrMigrate(prefs, fromKey))
            }
        }
    }

    /**
     * Convenience: returns only the positive daily limit minutes (0 if none).
     */
    fun getLimitMinutes(ctx: Context, profile: String, surfaceKey: String): Int {
        return getRule(ctx, profile, surfaceKey).coerceAtLeast(0)
    }

    fun setLimitMinutes(ctx: Context, profile: String, surfaceKey: String, minutes: Int) {
        val m = minutes.coerceAtLeast(0)
        setRule(ctx, profile, surfaceKey, m)
    }

    private fun readIntOrMigrate(prefs: SharedPreferences, key: String): Int {
        // Fast path
        try {
            return prefs.getInt(key, 0)
        } catch (_: ClassCastException) {
            // Fall through to migration path.
        }

        val any = prefs.all[key]
        val value = when (any) {
            is Int -> any
            is Long -> any.toInt()
            is Number -> any.toInt()
            is String -> any.toIntOrNull() ?: any.toLongOrNull()?.toInt() ?: 0
            else -> 0
        }

        // Migrate to stable Int storage to prevent repeated crashes.
        prefs.edit { putInt(key, value) }
        return value
    }
}
