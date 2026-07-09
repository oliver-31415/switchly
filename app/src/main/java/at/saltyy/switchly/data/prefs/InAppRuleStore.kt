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
import java.util.Locale

/**
 * Helper for profile-scoped in-app rule toggles.
 * The actual settings screen reads/writes the same scoped keys directly for speed, while this store keeps profile rename/remove/duplicate behavior consistent.
 */
object InAppRuleStore {
    private const val PREFS = "switchly_prefs"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sanitizeProfile(profile: String): String {
        return profile
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_.-]"), "_")
            .ifBlank { "default" }
    }

    private fun key(profile: String, baseKey: String): String =
        "p_${sanitizeProfile(profile)}_$baseKey"

    private val PACKAGE_TO_RULE_KEYS: Map<String, Set<String>> = mapOf(
        "com.google.android.youtube" to setOf(
            BlockingToggleKeys.KEY_BLOCK_YT_SHORTS,
            BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS,
            BlockingToggleKeys.KEY_BLOCK_YT_YOU,
            BlockingToggleKeys.KEY_BLOCK_YT_PIP
        ),
        "com.instagram.android" to setOf(
            BlockingToggleKeys.KEY_BLOCK_IG_REELS,
            BlockingToggleKeys.KEY_BLOCK_IG_EXPLORE,
            BlockingToggleKeys.KEY_BLOCK_IG_SEARCH,
            BlockingToggleKeys.KEY_BLOCK_IG_STORIES,
            BlockingToggleKeys.KEY_BLOCK_IG_COMMENTS
        ),
        "com.twitter.android" to setOf(
            BlockingToggleKeys.KEY_BLOCK_X_HOME,
            BlockingToggleKeys.KEY_BLOCK_X_SEARCH,
            BlockingToggleKeys.KEY_BLOCK_X_GROK,
            BlockingToggleKeys.KEY_BLOCK_X_NOTIFICATIONS
        ),
        "com.snapchat.android" to setOf(
            BlockingToggleKeys.KEY_BLOCK_SNAP_MAP,
            BlockingToggleKeys.KEY_BLOCK_SNAP_STORIES,
            BlockingToggleKeys.KEY_BLOCK_SNAP_SPOTLIGHT,
            BlockingToggleKeys.KEY_BLOCK_SNAP_FOLLOWING
        )
    )

    private val PACKAGE_TO_SURFACE_KEYS: Map<String, Set<String>> = mapOf(
        "com.google.android.youtube" to setOf("yt:shorts", "yt:subscriptions", "yt:you", "yt:pip"),
        "com.instagram.android" to setOf("ig:reels", "ig:explore", "ig:search", "ig:stories", "ig:comments"),
        "com.twitter.android" to setOf("x:foryou", "x:search", "x:grok", "x:notifications"),
        "com.snapchat.android" to setOf("snap:map", "snap:stories", "snap:spotlight", "snap:following")
    )

    private fun readScopedBool(context: Context, profile: String, baseKey: String, def: Boolean = false): Boolean {
        val sp = prefs(context)
        val scoped = key(profile, baseKey)
        if (sp.contains(scoped)) return sp.getBoolean(scoped, def)
        return if (sp.contains(baseKey)) sp.getBoolean(baseKey, def) else def
    }

    fun supportedPackages(): Set<String> = PACKAGE_TO_RULE_KEYS.keys

    fun packageForRuleKey(baseKey: String): String? =
        PACKAGE_TO_RULE_KEYS.entries.firstOrNull { (_, keys) -> baseKey in keys }?.key

    fun hasEnabledRulesForPackage(context: Context, profile: String, packageName: String): Boolean {
        if (profile.isBlank() || packageName.isBlank()) return false
        if (!readScopedBool(context, profile, BlockingToggleKeys.KEY_BLOCK_INAPP, def = true)) return false

        val toggled = PACKAGE_TO_RULE_KEYS[packageName]
            ?.any { readScopedBool(context, profile, it, def = false) } == true
        if (toggled) return true

        return PACKAGE_TO_SURFACE_KEYS[packageName]
            ?.any { SurfaceLimitStore.hasRule(context, profile, it) } == true
    }

    fun getPackagesWithEnabledRules(context: Context, profile: String): Set<String> {
        if (profile.isBlank()) return emptySet()
        return supportedPackages()
            .filterTo(linkedSetOf()) { hasEnabledRulesForPackage(context, profile, it) }
    }

    fun onProfileRenamed(context: Context, oldProfile: String, newProfile: String) {
        val sp = prefs(context)
        sp.edit {
            BlockingToggleKeys.IN_APP_RULE_KEYS.forEach { baseKey ->
                val oldKey = key(oldProfile, baseKey)
                val newKey = key(newProfile, baseKey)
                if (sp.contains(oldKey)) {
                    putBoolean(newKey, sp.getBoolean(oldKey, baseKey == BlockingToggleKeys.KEY_BLOCK_INAPP))
                    remove(oldKey)
                }
            }
        }
    }

    fun onProfileRemoved(context: Context, profile: String) {
        prefs(context).edit {
            BlockingToggleKeys.IN_APP_RULE_KEYS.forEach { baseKey -> remove(key(profile, baseKey)) }
        }
    }

    fun copyProfile(context: Context, fromProfile: String, toProfile: String) {
        val sp = prefs(context)
        sp.edit {
            BlockingToggleKeys.IN_APP_RULE_KEYS.forEach { baseKey ->
                val fromKey = key(fromProfile, baseKey)
                val toKey = key(toProfile, baseKey)
                if (sp.contains(fromKey)) {
                    putBoolean(toKey, sp.getBoolean(fromKey, baseKey == BlockingToggleKeys.KEY_BLOCK_INAPP))
                } else {
                    remove(toKey)
                }
            }
        }
    }
}
