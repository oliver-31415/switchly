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
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * Helper for profile-scoped in-app rule toggles.
 * The actual settings screen reads/writes the same scoped keys directly for speed, while this store keeps profile rename/remove/duplicate behavior consistent.
 */
object InAppRuleStore {
    const val MODE_BLOCK_SELECTED = "block_selected"
    const val MODE_ALLOW_SELECTED = "allow_selected"
    private const val PREFIX_IN_APP_RULE_MODE = "in_app_rule_mode__"
    private const val KEY_LEGACY_RULE_MIGRATION_DONE = "in_app_rules_profile_migration_done"

    // In-app toggles are stored in the default preferences because the settings UI and
    // Accessibility runtime read/write the profile-scoped switch keys there.
    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    private fun sanitizeProfile(profile: String): String {
        return profile
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_.-]"), "_")
            .ifBlank { "default" }
    }

    private fun key(profile: String, baseKey: String): String =
        "p_${sanitizeProfile(profile)}_$baseKey"

    private fun modeKey(profile: String): String =
        PREFIX_IN_APP_RULE_MODE + sanitizeProfile(profile)

    fun getMode(context: Context, profile: String): String {
        val value = prefs(context).getString(modeKey(profile), MODE_BLOCK_SELECTED)
        return when (value) {
            MODE_ALLOW_SELECTED -> MODE_ALLOW_SELECTED
            else -> MODE_BLOCK_SELECTED
        }
    }

    fun isAllowMode(context: Context, profile: String): Boolean =
        getMode(context, profile) == MODE_ALLOW_SELECTED

    fun setMode(context: Context, profile: String, mode: String) {
        val safe = when (mode) {
            MODE_ALLOW_SELECTED -> MODE_ALLOW_SELECTED
            else -> MODE_BLOCK_SELECTED
        }
        prefs(context).edit { putString(modeKey(profile), safe) }
    }

    private val PACKAGE_TO_RULE_KEYS: Map<String, Set<String>> = mapOf(
        "com.google.android.youtube" to setOf(
            BlockingToggleKeys.KEY_BLOCK_YT_SHORTS,
            BlockingToggleKeys.KEY_BLOCK_YT_SUBSCRIPTIONS,
            BlockingToggleKeys.KEY_BLOCK_YT_YOU,
            BlockingToggleKeys.KEY_BLOCK_YT_MINI_PLAYER,
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

    fun migrateLegacyRulesIntoCurrentProfileIfNeeded(context: Context) {
        migrateLegacyRulesIntoProfileIfNeeded(context, ProfileStore.getCurrent(context) ?: "default")
    }

    private fun migrateLegacyRulesIntoProfileIfNeeded(context: Context, profile: String) {
        val sp = prefs(context)
        val hasLegacyRules = BlockingToggleKeys.IN_APP_RULE_KEYS.any { sp.contains(it) }
        if (sp.getBoolean(KEY_LEGACY_RULE_MIGRATION_DONE, false) && !hasLegacyRules) {
            return
        }

        sp.edit {
            BlockingToggleKeys.IN_APP_RULE_KEYS.forEach { baseKey ->
                if (baseKey == BlockingToggleKeys.KEY_BLOCK_YT_HOME) {
                    remove(baseKey)
                } else if (sp.contains(baseKey)) {
                    putBoolean(key(profile, baseKey), sp.getBoolean(baseKey, false))
                    remove(baseKey)
                }
            }
            putBoolean(KEY_LEGACY_RULE_MIGRATION_DONE, true)
        }
    }

    private fun readScopedBool(context: Context, profile: String, baseKey: String, def: Boolean = false): Boolean {
        if (baseKey == BlockingToggleKeys.KEY_BLOCK_YT_HOME) {
            return false
        }
        migrateLegacyRulesIntoProfileIfNeeded(context, profile)
        val sp = prefs(context)
        val scoped = key(profile, baseKey)
        return when {
            sp.contains(scoped) -> sp.getBoolean(scoped, def)
            else -> def
        }
    }

    fun isRuleSelected(context: Context, profile: String, baseKey: String): Boolean =
        readScopedBool(context, profile, baseKey, def = false)

    fun setRuleSelected(context: Context, profile: String, baseKey: String, selected: Boolean) {
        if (baseKey == BlockingToggleKeys.KEY_BLOCK_YT_HOME) {
            return
        }
        migrateLegacyRulesIntoProfileIfNeeded(context, profile)
        prefs(context).edit { putBoolean(key(profile, baseKey), selected) }
    }

    fun supportedPackages(): Set<String> = PACKAGE_TO_RULE_KEYS.keys

    fun packageForRuleKey(baseKey: String): String? =
        PACKAGE_TO_RULE_KEYS.entries.firstOrNull { (_, keys) -> baseKey in keys }?.key

    fun hasSelectedRulesForPackage(context: Context, profile: String, packageName: String): Boolean {
        if (profile.isBlank() || packageName.isBlank()) {
            return false
        }
        return PACKAGE_TO_RULE_KEYS[packageName]
            ?.any { readScopedBool(context, profile, it, def = false) } == true
    }

    fun hasEnabledRulesForPackage(context: Context, profile: String, packageName: String): Boolean {
        // In allow-mode the selected surfaces are the allowed exceptions, but the package should only become active when the user selected at least one exception for it.
        // This avoids enabling allow-mode and accidentally blocking every supported app.
        return hasSelectedRulesForPackage(context, profile, packageName)
    }

    fun shouldBlockSurface(context: Context, profile: String, baseKey: String): Boolean {
        if (profile.isBlank() || baseKey.isBlank()) {
            return false
        }
        if (baseKey == BlockingToggleKeys.KEY_BLOCK_YT_HOME) {
            return false
        }
        val pkg = packageForRuleKey(baseKey) ?: return false
        val selected = readScopedBool(context, profile, baseKey, def = false)
        if (!isAllowMode(context, profile)) {
            return selected
        }
        if (!hasSelectedRulesForPackage(context, profile, pkg)) {
            return false
        }
        return !selected
    }

    fun getPackagesWithEnabledRules(context: Context, profile: String): Set<String> {
        if (profile.isBlank()) {
            return emptySet()
        }
        return supportedPackages()
            .filterTo(linkedSetOf()) { hasEnabledRulesForPackage(context, profile, it) }
    }

    fun onProfileRenamed(context: Context, oldProfile: String, newProfile: String) {
        val sp = prefs(context)
        sp.edit {
            val oldModeKey = modeKey(oldProfile)
            val newModeKey = modeKey(newProfile)
            if (sp.contains(oldModeKey)) {
                putString(newModeKey, sp.getString(oldModeKey, MODE_BLOCK_SELECTED))
                remove(oldModeKey)
            }
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
            remove(modeKey(profile))
            BlockingToggleKeys.IN_APP_RULE_KEYS.forEach { baseKey -> remove(key(profile, baseKey)) }
        }
    }

    fun copyProfile(context: Context, fromProfile: String, toProfile: String) {
        val sp = prefs(context)
        sp.edit {
            putString(modeKey(toProfile), getMode(context, fromProfile))
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
