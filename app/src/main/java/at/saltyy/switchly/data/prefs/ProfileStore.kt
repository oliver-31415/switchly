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
import android.content.Intent
import androidx.core.content.edit
import at.saltyy.switchly.util.AppBlockSafety

object ProfileStore {
    private const val PREFS = "switchly_prefs"

    private const val KEY_PROFILES = "profiles"        // Set<String>
    private const val KEY_CURRENT = "current_profile"  // String

    private fun keyBlocked(profile: String) = "blocked_apps_$profile" // Set<String>
    private fun keyAllowed(profile: String) = "allowed_apps_$profile" // Set<String>
    private fun keyDescription(profile: String) = "profile_description_$profile" // String
    private fun keyAutoBlockNewApps(profile: String) = "auto_block_new_apps_$profile" // Boolean
    private fun keyAutoBlockKnownApps(profile: String) = "auto_block_known_apps_$profile" // Set<String>

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
            remove(keyAllowed(name))
            remove(keyDescription(name))
        }

        DomainBlockStore.onProfileRemoved(context, name)
        DomainLimitStore.onProfileRemoved(context, name)
        InAppLimitStore.onProfileRemoved(context, name)
        SurfaceLimitStore.onProfileRemoved(context, name)
        InAppRuleStore.onProfileRemoved(context, name)
        ProfileRuleModeStore.onProfileRemoved(context, name)
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
        val oldAllowed = try {
            sp.getStringSet(keyAllowed(old), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }
        val oldDescription = sp.getString(keyDescription(old), "").orEmpty()

        set.remove(old)
        set.add(new)

        sp.edit {
            putStringSet(KEY_PROFILES, set)
            putStringSet(keyBlocked(new), oldBlocked)
            putStringSet(keyAllowed(new), oldAllowed)
            if (oldDescription.isBlank()) {
                remove(keyDescription(new))
            } else {
                putString(keyDescription(new), oldDescription)
            }
            remove(keyBlocked(old))
            remove(keyAllowed(old))
            remove(keyDescription(old))
            if (getCurrent(context) == old) {
                putString(KEY_CURRENT, new)
            }
        }

        DomainBlockStore.onProfileRenamed(context, old, new)
        DomainLimitStore.onProfileRenamed(context, old, new)
        InAppLimitStore.onProfileRenamed(context, old, new)
        SurfaceLimitStore.onProfileRenamed(context, old, new)
        InAppRuleStore.onProfileRenamed(context, old, new)
        ProfileRuleModeStore.onProfileRenamed(context, old, new)
        return true
    }

    fun getDescription(context: Context, profile: String): String {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getString(keyDescription(profile), "").orEmpty()
    }

    fun setDescription(context: Context, profile: String, description: String) {
        val cleaned = description.trim()
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            if (cleaned.isBlank()) {
                remove(keyDescription(profile))
            } else {
                putString(keyDescription(profile), cleaned)
            }
        }
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

    private fun getRawBlockedForProfile(context: Context, profile: String): Set<String> {
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

    // Returns all whole-app blocked package names for a specific profile.
    fun getBlockedForProfile(context: Context, profile: String): Set<String> {
        val blocked = getRawBlockedForProfile(context, profile)
        if (blocked.isEmpty()) return emptySet()

        // In-app rules pin supported apps into the picker/list so the user can see why they are involved, but they must not turn the entire app into a whole-app block.
        return blocked.filterTo(linkedSetOf()) { pkg ->
            !isInAppOnlySelection(context, profile, pkg)
        }
    }

    private fun isInAppOnlySelection(context: Context, profile: String, pkg: String): Boolean {
        if (!InAppRuleStore.hasEnabledRulesForPackage(context, profile, pkg)) return false
        return UsageLimitStore.getLimitMinutes(context, profile, pkg) <= 0 &&
            SessionLimitStore.getLimitMinutes(context, profile, pkg) <= 0 &&
            AttemptLimitStore.getLimitAttempts(context, profile, pkg) <= 0
    }

    // Updates the blocked-app list for the given profile.
    fun setBlockedForProfile(context: Context, profile: String, pkgs: Set<String>) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sanitized = AppBlockSafety.sanitizeManagedPackages(context, pkgs)
        sp.edit { putStringSet(keyBlocked(profile), sanitized) }
    }

    // Returns all allowed package names for a specific profile.
    fun getAllowedForProfile(context: Context, profile: String): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = try {
            sp.getStringSet(keyAllowed(profile), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            sp.edit { remove(keyAllowed(profile)) }
            emptySet()
        }

        val cleaned = raw
            .filterTo(linkedSetOf()) { it.isNotBlank() }
            .let { AppBlockSafety.sanitizeManagedPackages(context, it) }

        // In allow-list mode, apps with active in-app rules must stay allowed.
        // Otherwise a configured rule like “block YouTube Shorts” would be hidden by the whole-app allow list and the entire app would be blocked instead.
        if (!ProfileRuleModeStore.isAllowMode(context, profile)) return cleaned

        val requiredForInAppRules = InAppRuleStore.getPackagesWithEnabledRules(context, profile)
        if (requiredForInAppRules.isEmpty()) return cleaned

        return AppBlockSafety.sanitizeManagedPackages(context, cleaned + requiredForInAppRules)
    }

    // Updates the allow-app list for the given profile.
    fun setAllowedForProfile(context: Context, profile: String, pkgs: Set<String>) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sanitized = AppBlockSafety.sanitizeManagedPackages(context, pkgs)
        sp.edit { putStringSet(keyAllowed(profile), sanitized) }
    }

    fun getSelectedForProfileMode(context: Context, profile: String): Set<String> {
        return if (ProfileRuleModeStore.isAllowMode(context, profile)) {
            getAllowedForProfile(context, profile)
        } else {
            getBlockedForProfile(context, profile)
        }
    }

    fun setSelectedForProfileMode(context: Context, profile: String, pkgs: Set<String>) {
        if (ProfileRuleModeStore.isAllowMode(context, profile)) {
            setAllowedForProfile(context, profile, pkgs)
        } else {
            setBlockedForProfile(context, profile, pkgs)
        }
    }

    fun isAutoBlockNewAppsEnabled(context: Context, profile: String): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.getBoolean(keyAutoBlockNewApps(profile), false)
    }

    fun setAutoBlockNewAppsEnabled(context: Context, profile: String, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit {
            putBoolean(keyAutoBlockNewApps(profile), enabled)
            if (enabled && !sp.contains(keyAutoBlockKnownApps(profile))) {
                putStringSet(keyAutoBlockKnownApps(profile), getLaunchablePackages(context))
            }
        }
    }

    fun setAutoBlockKnownPackages(context: Context, profile: String, packages: Set<String>) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit { putStringSet(keyAutoBlockKnownApps(profile), packages.filterTo(linkedSetOf()) { it.isNotBlank() }) }
    }

    private fun getAutoBlockKnownPackages(context: Context, profile: String): Set<String> {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            sp.getStringSet(keyAutoBlockKnownApps(profile), emptySet()) ?: emptySet()
        } catch (_: ClassCastException) {
            emptySet()
        }
    }

    private fun markAutoBlockKnownPackage(context: Context, profile: String, pkg: String) {
        if (pkg.isBlank()) return
        val current = getAutoBlockKnownPackages(context, profile).toMutableSet()
        if (current.add(pkg)) {
            setAutoBlockKnownPackages(context, profile, current)
        }
    }

    fun getLaunchablePackages(context: Context): Set<String> {
        val pm = context.packageManager
        val packages = linkedSetOf<String>()

        listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        ).forEach { launcherIntent ->
            runCatching { pm.queryIntentActivities(launcherIntent, 0) }
                .getOrDefault(emptyList())
                .mapNotNullTo(packages) { it.activityInfo?.applicationInfo?.packageName }
        }

        // Do not call getInstalledApplications() here. 
        // Android package visibility can make it incomplete and lint warns about it.
        // Saved package names are resolved individually where needed instead of treating "not in launcher query" as unavailable.
        return packages.filterTo(linkedSetOf()) { it.isNotBlank() && it != context.packageName }
    }

    fun addBlockedAppToAutoBlockProfiles(context: Context, pkg: String): Int {
        if (pkg.isBlank()) return 0
        var changed = 0
        getProfiles(context).forEach { profile ->
            if (!isAutoBlockNewAppsEnabled(context, profile)) return@forEach
            if (ProfileRuleModeStore.isAllowMode(context, profile)) return@forEach
            val current = getBlockedForProfile(context, profile)
            if (pkg in current) return@forEach

            val sanitized = AppBlockSafety.sanitizeManagedPackages(context, current + pkg)
            if (pkg !in sanitized) return@forEach

            setBlockedForProfile(context, profile, sanitized)
            changed++
            markAutoBlockKnownPackage(context, profile, pkg)
        }
        return changed
    }

    /**
     * Safety net for missed PACKAGE_ADDED broadcasts. 
     * If auto-block is enabled for a profile, any launchable packages that appeared after the stored baseline are added automatically.
     * If no baseline exists yet, create one without adding existing apps to avoid surprise bulk changes.
     */
    fun reconcileAutoBlockNewApps(context: Context): Int {
        val installed = getLaunchablePackages(context)
        if (installed.isEmpty()) return 0

        var changed = 0
        getProfiles(context).forEach { profile ->
            if (!isAutoBlockNewAppsEnabled(context, profile)) return@forEach
            if (ProfileRuleModeStore.isAllowMode(context, profile)) return@forEach

            val known = getAutoBlockKnownPackages(context, profile)
            if (known.isEmpty()) {
                setAutoBlockKnownPackages(context, profile, installed)
                return@forEach
            }

            val newlyInstalled = installed - known
            if (newlyInstalled.isEmpty()) return@forEach

            val current = getBlockedForProfile(context, profile)
            val sanitized = AppBlockSafety.sanitizeManagedPackages(context, current + newlyInstalled)
            val actuallyAdded = sanitized - current
            if (actuallyAdded.isNotEmpty()) {
                setBlockedForProfile(context, profile, sanitized)
                changed += actuallyAdded.size
            }
            setAutoBlockKnownPackages(context, profile, known + installed)
        }
        return changed
    }
}
