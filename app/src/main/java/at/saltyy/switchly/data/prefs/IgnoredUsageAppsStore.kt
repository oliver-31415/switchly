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
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.edit
import at.saltyy.switchly.data.statistics.UsageInsightsAppCatalog
import at.saltyy.switchly.util.PackageManagerApiCompat

/**
 * Stores user-selected app visibility filters for Usage & Insights and app pickers.
 * Both sets live in switchly_prefs so they follow the existing local/cloud backup path.
 */
object IgnoredUsageAppsStore {
    private const val PREFS = "switchly_prefs"

    private const val KEY_USAGE_PACKAGES = "usage_insights_ignored_packages"
    private const val KEY_USAGE_INITIALIZED = "usage_insights_ignored_packages_initialized"
    private const val KEY_USAGE_SUGGESTIONS_VERSION = "usage_insights_ignored_suggestions_version"
    private const val USAGE_SUGGESTIONS_VERSION = 3

    private const val KEY_APP_PICKER_PACKAGES = "app_picker_hidden_packages"

    fun getIgnoredPackages(context: Context): Set<String> {
        ensureUsageInitialized(context)
        return getInstalledPackages(context, KEY_USAGE_PACKAGES)
    }

    fun isIgnored(context: Context, packageName: String): Boolean {
        val requested = packageName.trim().takeIf { it.isNotBlank() } ?: return false
        if (UsageInsightsAppCatalog.shouldAlwaysHide(requested)) return false
        ensureUsageInitialized(context)
        return storedPackages(context, KEY_USAGE_PACKAGES)
            .any { it.equals(requested, ignoreCase = true) }
    }

    fun setIgnoredPackages(context: Context, packages: Collection<String>) {
        setPackages(context, KEY_USAGE_PACKAGES, packages)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_USAGE_INITIALIZED, true)
        }
    }

    fun getAppPickerHiddenPackages(context: Context): Set<String> {
        return getInstalledPackages(context, KEY_APP_PICKER_PACKAGES)
    }

    fun isHiddenFromAppPickers(context: Context, packageName: String): Boolean {
        val requested = packageName.trim().takeIf { it.isNotBlank() } ?: return false
        if (UsageInsightsAppCatalog.shouldAlwaysHide(requested)) return false
        return storedPackages(context, KEY_APP_PICKER_PACKAGES)
            .any { it.equals(requested, ignoreCase = true) }
    }

    fun setAppPickerHiddenPackages(context: Context, packages: Collection<String>) {
        setPackages(context, KEY_APP_PICKER_PACKAGES, packages)
    }

    fun suggestedPackages(context: Context): Set<String> {
        val packages = linkedSetOf<String>()
        packages += context.packageName
        packages += homePackages(context)
        defaultInputMethodPackage(context)?.let(packages::add)
        defaultDialerPackage(context)?.let(packages::add)
        packages += UsageInsightsAppCatalog.STATIC_SUGGESTION_PACKAGES

        return packages
            .mapNotNull { canonicalPackage(context, it) }
            .filterTo(linkedSetOf()) { isInstalled(context, it) }
    }

    private fun getInstalledPackages(context: Context, key: String): Set<String> {
        val stored = storedPackages(context, key)
        val installed = stored
            .mapNotNull { canonicalPackage(context, it) }
            .filterTo(linkedSetOf()) { packageName ->
                !UsageInsightsAppCatalog.shouldAlwaysHide(packageName) && isInstalled(context, packageName)
            }
        if (installed != stored) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putStringSet(key, installed)
            }
        }
        return installed
    }

    private fun storedPackages(context: Context, key: String): Set<String> {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = preferences.getStringSet(key, emptySet()).orEmpty()
        val cleaned = stored
            .mapNotNull { value -> value.trim().takeIf { it.isNotBlank() } }
            .filterNot(UsageInsightsAppCatalog::shouldAlwaysHide)
            .toSet()
        if (cleaned != stored) {
            preferences.edit { putStringSet(key, cleaned) }
        }
        return cleaned
    }

    private fun setPackages(context: Context, key: String, packages: Collection<String>) {
        val canonical = packages
            .mapNotNull { canonicalPackage(context, it) }
            .filterNot(UsageInsightsAppCatalog::shouldAlwaysHide)
            .toSet()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putStringSet(key, canonical)
        }
    }

    private fun ensureUsageInitialized(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_USAGE_INITIALIZED, false)) {
            prefs.edit {
                putStringSet(KEY_USAGE_PACKAGES, suggestedPackages(context))
                putBoolean(KEY_USAGE_INITIALIZED, true)
                putInt(KEY_USAGE_SUGGESTIONS_VERSION, USAGE_SUGGESTIONS_VERSION)
            }
            return
        }

        val currentVersion = prefs.getInt(KEY_USAGE_SUGGESTIONS_VERSION, 1)
        if (currentVersion < USAGE_SUGGESTIONS_VERSION) {
            val stored = prefs.getStringSet(KEY_USAGE_PACKAGES, emptySet()).orEmpty()
            val migrated = buildSet {
                addAll(stored)
                if (currentVersion < 2) addAll(newSuggestionPackages(context))
            }.mapNotNull { canonicalPackage(context, it) }
                .filterNot(UsageInsightsAppCatalog::shouldAlwaysHide)
                .toSet()
            prefs.edit {
                putStringSet(KEY_USAGE_PACKAGES, migrated)
                putInt(KEY_USAGE_SUGGESTIONS_VERSION, USAGE_SUGGESTIONS_VERSION)
            }
        }
    }

    private fun newSuggestionPackages(context: Context): Set<String> {
        return UsageInsightsAppCatalog.STATIC_SUGGESTION_PACKAGES
            .mapNotNull { canonicalPackage(context, it) }
            .filterTo(linkedSetOf()) { isInstalled(context, it) }
    }

    private fun homePackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            val results = PackageManagerApiCompat.queryIntentActivities(
                packageManager = context.packageManager,
                intent = intent,
            )
            results.mapNotNullTo(linkedSetOf()) { it.activityInfo?.packageName }
        }.getOrDefault(emptySet())
    }

    private fun defaultInputMethodPackage(context: Context): String? {
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()
    }

    private fun defaultDialerPackage(context: Context): String? {
        val telecomPackage = runCatching {
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        if (!telecomPackage.isNullOrBlank()) return telecomPackage

        val intent = Intent(Intent.ACTION_DIAL)
        return runCatching {
            val result = PackageManagerApiCompat.resolveActivity(
                packageManager = context.packageManager,
                intent = intent,
            )
            result?.activityInfo?.packageName
        }.getOrNull()
    }

    private fun isInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            PackageManagerApiCompat.getApplicationInfo(
                packageManager = context.packageManager,
                packageName = packageName,
            )
        }.isSuccess
    }

    private fun canonicalPackage(context: Context, packageName: String): String? {
        val raw = packageName.trim().takeIf { it.isNotBlank() } ?: return null

        val exact = runCatching {
            PackageManagerApiCompat.getApplicationInfo(
                packageManager = context.packageManager,
                packageName = raw,
            ).packageName
        }.getOrNull()
        if (!exact.isNullOrBlank()) return exact

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val visibleMatch = runCatching {
            PackageManagerApiCompat.queryIntentActivities(
                packageManager = context.packageManager,
                intent = launcherIntent,
            )
        }.getOrDefault(emptyList())
            .asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it.equals(raw, ignoreCase = true) }

        return visibleMatch ?: raw
    }
}
