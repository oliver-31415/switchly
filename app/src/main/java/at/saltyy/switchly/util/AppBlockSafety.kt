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

package at.saltyy.switchly.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.EmergencyPinStore

object AppBlockSafety {

    enum class Level {
        NONE,
        SOFT_WARNING,
        HARD_EXCLUDED
    }

    enum class RiskCategory {
        SETTINGS,
        INSTALL,
        PERMISSIONS,
        PROVISIONING,
        ASSISTANT,
        STORE,
        FILES,
        BROWSER,
        SYSTEM_UI,
        LAUNCHER,
        DIALER,
        INPUT_METHOD,
        OTHER
    }

    enum class PolicyAction {
        ALLOW,
        WARN_ONLY,
        STRICT_MODE_ONLY,
        NEVER_BLOCK
    }

    data class RiskRule(
        val category: RiskCategory,
        val action: PolicyAction,
        val reason: String
    )

    data class Info(
        val level: Level = Level.NONE,
        val hint: String? = null,
        val warningTitle: String? = null,
        val warningMessage: String? = null
    )

    private data class ResolvedDefaults(
        val defaultIme: String?,
        val defaultHome: String?,
        val defaultDialer: String?,
        val defaultAssistant: String?,
        val settingsPackage: String?
    )

    @Volatile
    private var cachedResolvedDefaults: ResolvedDefaults? = null

    @Volatile
    private var cachedResolvedDefaultsAtMs: Long = 0L

    private const val RESOLVED_DEFAULTS_CACHE_TTL_MS = 5 * 60 * 1000L

    private val walletPackagePrefixes = listOf(
        "com.samsung.android.spay",
        "com.google.android.apps.walletnfcrel"
    )

    private val exactRiskRules = mapOf(
        "com.android.settings" to RiskRule(
            RiskCategory.SETTINGS,
            PolicyAction.STRICT_MODE_ONLY,
            "System settings can disable protections or grant privileged access."
        ),
        "com.google.android.packageinstaller" to RiskRule(
            RiskCategory.INSTALL,
            PolicyAction.NEVER_BLOCK,
            "Package installer can install or update apps."
        ),
        "com.android.packageinstaller" to RiskRule(
            RiskCategory.INSTALL,
            PolicyAction.NEVER_BLOCK,
            "Package installer can install or update apps."
        ),
        "com.android.vending" to RiskRule(
            RiskCategory.STORE,
            PolicyAction.WARN_ONLY,
            "Play Store can install helper or escape apps."
        ),
        "com.google.android.packageinstaller" to RiskRule(
            RiskCategory.INSTALL,
            PolicyAction.NEVER_BLOCK,
            "Package installer can install or update apps."
        ),
        "com.google.android.permissioncontroller" to RiskRule(
            RiskCategory.PERMISSIONS,
            PolicyAction.NEVER_BLOCK,
            "Permission controller manages sensitive grants."
        ),
        "com.android.documentsui" to RiskRule(
            RiskCategory.FILES,
            PolicyAction.WARN_ONLY,
            "Files/Documents surfaces can matter for backup, restore, and recovery."
        ),
        "com.google.android.documentsui" to RiskRule(
            RiskCategory.FILES,
            PolicyAction.WARN_ONLY,
            "Files/Documents surfaces can matter for backup, restore, and recovery."
        ),
        "com.google.android.apps.nbu.files" to RiskRule(
            RiskCategory.FILES,
            PolicyAction.WARN_ONLY,
            "Files/Documents surfaces can matter for backup, restore, and recovery."
        ),
        "com.google.android.permission" to RiskRule(
            RiskCategory.PERMISSIONS,
            PolicyAction.NEVER_BLOCK,
            "Permission controller manages sensitive grants."
        ),
        "com.google.android.setupwizard" to RiskRule(
            RiskCategory.PROVISIONING,
            PolicyAction.NEVER_BLOCK,
            "Setup flow is a privileged onboarding surface."
        ),
        "com.android.managedprovisioning" to RiskRule(
            RiskCategory.PROVISIONING,
            PolicyAction.NEVER_BLOCK,
            "Managed provisioning is a privileged enterprise/device-owner flow."
        ),
        "com.google.android.googlequicksearchbox" to RiskRule(
            RiskCategory.ASSISTANT,
            PolicyAction.NEVER_BLOCK,
            "Google app can be deeply integrated into launcher/search/assistant flows."
        ),
        "com.android.systemui" to RiskRule(
            RiskCategory.SYSTEM_UI,
            PolicyAction.NEVER_BLOCK,
            "Blocking System UI can destabilize the device."
        ),
        "com.google.android.gms" to RiskRule(
            RiskCategory.OTHER,
            PolicyAction.WARN_ONLY,
            "Google Play services is deeply integrated into Android."
        ),
        "com.google.android.as" to RiskRule(
            RiskCategory.ASSISTANT,
            PolicyAction.WARN_ONLY,
            "Android System Intelligence may surface assistant or search flows."
        ),
        "com.samsung.android.oneconnect" to RiskRule(
            RiskCategory.OTHER,
            PolicyAction.WARN_ONLY,
            "Samsung device-control surfaces can affect device usability."
        ),
        "com.samsung.android.app.settings.bixby" to RiskRule(
            RiskCategory.ASSISTANT,
            PolicyAction.WARN_ONLY,
            "Bixby settings can affect assistant behavior and recovery paths."
        ),
        "com.android.chrome" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "org.mozilla.firefox" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "org.mozilla.firefox_beta" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "net.waterfox.android.release" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.brave.browser" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.brave.browser_beta" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.brave.browser_nightly" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.microsoft.emmx" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.sec.android.app.sbrowser" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.sec.android.app.sbrowser.beta" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.vivaldi.browser" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.opera.browser" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.opera.browser.beta" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.opera.mini.native" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.kiwibrowser.browser" to RiskRule(
            RiskCategory.BROWSER,
            PolicyAction.WARN_ONLY,
            "Browsers are often used for downloads, support, and recovery steps."
        ),
        "com.google.android.apps.nexuslauncher" to RiskRule(
            RiskCategory.LAUNCHER,
            PolicyAction.WARN_ONLY,
            "Default or OEM launchers should be treated carefully."
        ),
        "com.sec.android.app.launcher" to RiskRule(
            RiskCategory.LAUNCHER,
            PolicyAction.WARN_ONLY,
            "Default or OEM launchers should be treated carefully."
        ),
        "com.oplus.games" to RiskRule(
            RiskCategory.OTHER,
            PolicyAction.NEVER_BLOCK,
            "OEM game hub wrappers can appear as foreground packages and cause false app blocks."
        ),
        "com.oneplus.gamespace" to RiskRule(
            RiskCategory.OTHER,
            PolicyAction.NEVER_BLOCK,
            "OEM game hub wrappers can appear as foreground packages and cause false app blocks."
        )
    )

    private val prefixRiskRules = listOf(
        "com.google.android.permissioncontroller" to RiskRule(
            RiskCategory.PERMISSIONS,
            PolicyAction.NEVER_BLOCK,
            "Permission controller manages sensitive grants."
        ),
        "com.android.permissioncontroller" to RiskRule(
            RiskCategory.PERMISSIONS,
            PolicyAction.NEVER_BLOCK,
            "Permission controller manages sensitive grants."
        )
    )

    fun resolve(context: Context, pkg: String): Info {
        return resolve(context, pkg, collectResolvedDefaults(context))
    }

    private fun resolve(context: Context, pkg: String, defaults: ResolvedDefaults): Info {
        val normalized = pkg.trim()
        if (normalized.isBlank()) return Info()

        if (normalized == defaults.defaultIme) {
            return Info(
                level = Level.HARD_EXCLUDED,
                hint = context.getString(R.string.app_picker_protected_keyboard_hint)
            )
        }

        if (normalized == defaults.defaultHome) {
            return Info(
                level = Level.HARD_EXCLUDED,
                hint = context.getString(R.string.app_picker_protected_launcher_hint)
            )
        }

        val riskRule = matchRiskRule(normalized, defaults.defaultAssistant)
        if (riskRule?.action == PolicyAction.NEVER_BLOCK) {
            val (hintRes, titleRes, messageRes) = resourcesForCategory(riskRule.category)
            return Info(
                level = Level.HARD_EXCLUDED,
                hint = context.getString(hintRes),
                warningTitle = titleRes?.let(context::getString),
                warningMessage = messageRes?.let(context::getString)
            )
        }

        if (isWalletPackage(normalized)) {
            val messageRes = if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                R.string.app_picker_wallet_warning_message_samsung
            } else {
                R.string.app_picker_wallet_warning_message
            }
            return Info(
                level = Level.SOFT_WARNING,
                hint = context.getString(R.string.app_picker_wallet_hint),
                warningTitle = context.getString(R.string.app_picker_wallet_warning_title),
                warningMessage = context.getString(messageRes)
            )
        }

        if (normalized == defaults.defaultDialer) {
            return Info(
                level = Level.HARD_EXCLUDED,
                hint = context.getString(R.string.app_picker_dialer_hint),
                warningTitle = context.getString(R.string.app_picker_dialer_warning_title),
                warningMessage = context.getString(R.string.app_picker_dialer_warning_message)
            )
        }

        if (riskRule?.action == PolicyAction.STRICT_MODE_ONLY || isSettingsPackage(normalized, defaults.settingsPackage)) {
            return Info(
                level = Level.SOFT_WARNING,
                hint = context.getString(R.string.app_picker_settings_hint),
                warningTitle = context.getString(R.string.app_picker_settings_warning_title),
                warningMessage = context.getString(R.string.app_picker_settings_warning_message)
            )
        }

        if (riskRule?.action == PolicyAction.WARN_ONLY) {
            val (hintRes, titleRes, messageRes) = resourcesForCategory(riskRule.category)
            return Info(
                level = Level.SOFT_WARNING,
                hint = context.getString(hintRes),
                warningTitle = titleRes?.let(context::getString),
                warningMessage = messageRes?.let(context::getString)
            )
        }

        return Info()
    }

    fun isHardExcluded(context: Context, pkg: String): Boolean =
        resolve(context, pkg, collectResolvedDefaults(context, includeSlowPackageManagerLookups = false)).level == Level.HARD_EXCLUDED

    fun isAllowModeEssential(context: Context, pkg: String): Boolean {
        val normalized = pkg.trim()
        if (normalized.isBlank()) return false

        // Switchly itself must never be blocked in Allow selected mode, otherwise the user can lock themselves out of the recovery UI.
        if (normalized == context.packageName) return true

        // Allow mode is a safety-critical path. 
        // Use the full cached default-app resolver here so the active launcher, keyboard, dialer, permission controller, installer, and similar hard exclusions remain reachable even if the fast cache has not been warmed yet.
        return resolve(context, normalized, collectResolvedDefaults(context, includeSlowPackageManagerLookups = true)).level == Level.HARD_EXCLUDED
    }

    fun sanitizeManagedPackages(context: Context, pkgs: Set<String>): Set<String> {
        if (pkgs.isEmpty()) return emptySet()

        // Build the default-app snapshot once.
        // This method is used from hot UI paths (status refresh, profile reads), so avoid PackageManager/Telecom binder calls here.
        val defaults = collectResolvedDefaults(context, includeSlowPackageManagerLookups = false)
        return pkgs
            .asSequence()
            .filter { it.isNotBlank() }
            .filterNot { resolve(context, it, defaults).level == Level.HARD_EXCLUDED }
            .toCollection(linkedSetOf())
    }

    fun requiresStrictModeForBlocking(context: Context, pkg: String): Boolean {
        val normalized = pkg.trim()
        if (normalized.isBlank()) return false
        val defaultAssistant = getDefaultAssistantPackage(context)
        return matchRiskRule(normalized, defaultAssistant)?.action == PolicyAction.STRICT_MODE_ONLY ||
            isSettingsPackage(normalized, defaultSettingsPackage = null)
    }

    fun isStrictModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEV_UNLOCKED, false)
    }

    fun hasEmergencyRecoveryConfigured(context: Context): Boolean {
        return EmergencyPinStore.hasPin(context) && EmergencyBypassStore.isFeatureEnabled(context)
    }

    fun canAllowStrictModeBlocking(context: Context, pkg: String): Boolean {
        if (!requiresStrictModeForBlocking(context, pkg)) return true
        return isStrictModeEnabled(context) && hasEmergencyRecoveryConfigured(context)
    }

    fun getDefaultInputMethodPackage(context: Context): String? {
        val currentId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty().trim()

        // DEFAULT_INPUT_METHOD is already stored as a flattened component id (package/class).
        // Avoid InputMethodManager.enabledInputMethodList here:  that binder call can block long enough to trigger ANRs on Android 16/OEM builds.
        return currentId.substringBefore('/').trim().takeIf { it.isNotBlank() && it.contains('.') }
    }

    fun getDefaultHomePackage(context: Context): String? {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrNull()
        normalizeHomePackage(resolved?.activityInfo?.packageName)?.let { return it }

        val candidates = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(
                    homeIntent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            }
        }.getOrElse { emptyList() }

        return candidates
            .asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .mapNotNull(::normalizeHomePackage)
            .firstOrNull()
    }

    fun getDefaultAssistantPackage(context: Context): String? {
        val voiceInteraction = runCatching {
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        }.getOrNull().orEmpty().trim()
        if (voiceInteraction.isNotBlank()) {
            return voiceInteraction.substringBefore('/').trim().takeIf { it.isNotBlank() && it.contains('.') }
        }

        val assistant = runCatching {
            Settings.Secure.getString(context.contentResolver, "assistant")
        }.getOrNull().orEmpty().trim()
        if (assistant.isBlank()) return null
        return assistant.substringBefore('/').trim().takeIf { it.isNotBlank() && it.contains('.') }
    }

    fun getDefaultDialerPackage(context: Context): String? {
        val telecomManager = runCatching {
            context.getSystemService(TelecomManager::class.java)
        }.getOrNull()
        return runCatching { telecomManager?.defaultDialerPackage }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun matchRiskRule(context: Context, pkg: String): RiskRule? {
        return matchRiskRule(pkg, getDefaultAssistantPackage(context))
    }

    private fun matchRiskRule(pkg: String, defaultAssistant: String?): RiskRule? {
        val normalized = pkg.trim()
        if (normalized.isBlank()) return null

        exactRiskRules[normalized]?.let { return it }
        prefixRiskRules.firstOrNull { (prefix, _) -> normalized == prefix || normalized.startsWith("$prefix.") }
            ?.let { return it.second }

        if (!defaultAssistant.isNullOrBlank() && normalized == defaultAssistant) {
            return RiskRule(
                RiskCategory.ASSISTANT,
                PolicyAction.WARN_ONLY,
                "Default assistant can be used as an escape surface."
            )
        }

        return null
    }

    private fun resourcesForCategory(category: RiskCategory): Triple<Int, Int?, Int?> {
        return when (category) {
            RiskCategory.SETTINGS -> Triple(
                R.string.app_picker_settings_hint,
                R.string.app_picker_settings_warning_title,
                R.string.app_picker_settings_warning_message
            )
            RiskCategory.STORE -> Triple(
                R.string.app_picker_play_store_hint,
                R.string.app_picker_play_store_warning_title,
                R.string.app_picker_play_store_warning_message
            )
            RiskCategory.INSTALL -> Triple(
                R.string.app_picker_installer_hint,
                R.string.app_picker_installer_warning_title,
                R.string.app_picker_installer_warning_message
            )
            RiskCategory.PERMISSIONS -> Triple(
                R.string.app_picker_permissions_hint,
                R.string.app_picker_permissions_warning_title,
                R.string.app_picker_permissions_warning_message
            )
            RiskCategory.FILES -> Triple(
                R.string.app_picker_files_hint,
                R.string.app_picker_files_warning_title,
                R.string.app_picker_files_warning_message
            )
            RiskCategory.BROWSER -> Triple(
                R.string.app_picker_browser_hint,
                R.string.app_picker_browser_warning_title,
                R.string.app_picker_browser_warning_message
            )
            else -> Triple(
                R.string.app_picker_protected_generic_hint,
                null,
                null
            )
        }
    }

    private fun isWalletPackage(pkg: String): Boolean {
        return walletPackagePrefixes.any { prefix ->
            pkg == prefix || pkg.startsWith("$prefix.")
        }
    }

    private fun isSettingsPackage(context: Context, pkg: String): Boolean {
        val normalized = pkg.trim()
        if (normalized.isBlank()) return false
        return isSettingsPackage(normalized, getDefaultSettingsPackage(context))
    }

    private fun isSettingsPackage(pkg: String, defaultSettingsPackage: String?): Boolean {
        val normalized = pkg.trim()
        if (normalized.isBlank()) return false
        if (normalized in knownSettingsPackages) return true
        return !defaultSettingsPackage.isNullOrBlank() && normalized == defaultSettingsPackage
    }

    private fun collectResolvedDefaults(
        context: Context,
        includeSlowPackageManagerLookups: Boolean = true
    ): ResolvedDefaults {
        val now = System.currentTimeMillis()
        val cached = cachedResolvedDefaults
        val cachedIsFresh = cached != null && now - cachedResolvedDefaultsAtMs <= RESOLVED_DEFAULTS_CACHE_TTL_MS

        if (includeSlowPackageManagerLookups && cachedIsFresh && cached != null) {
            return cached
        }

        if (!includeSlowPackageManagerLookups) {
            // Hot paths such as AccessibilityService/package sanitizing must not run launcher, dialer, settings, or PackageManager resolver calls. 
            // Reuse the last slow snapshot  when available, while still refreshing cheap Settings.Secure values.
            return ResolvedDefaults(
                defaultIme = getDefaultInputMethodPackage(context) ?: cached?.defaultIme,
                defaultHome = cached?.defaultHome.takeIf { cachedIsFresh },
                defaultDialer = cached?.defaultDialer.takeIf { cachedIsFresh },
                defaultAssistant = getDefaultAssistantPackage(context) ?: cached?.defaultAssistant,
                settingsPackage = cached?.settingsPackage.takeIf { cachedIsFresh }
            )
        }

        val resolved = ResolvedDefaults(
            defaultIme = getDefaultInputMethodPackage(context),
            defaultHome = getDefaultHomePackage(context),
            defaultDialer = getDefaultDialerPackage(context),
            defaultAssistant = getDefaultAssistantPackage(context),
            settingsPackage = getDefaultSettingsPackage(context)
        )
        cachedResolvedDefaults = resolved
        cachedResolvedDefaultsAtMs = now
        return resolved
    }

    private fun getDefaultSettingsPackage(context: Context): String? {
        return runCatching {
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
        }.getOrNull()
            ?.activityInfo
            ?.packageName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalizeHomePackage(pkg: String?): String? {
        val normalized = pkg?.trim().orEmpty()
        if (normalized.isBlank()) return null
        if (normalized in blockedHomeResolverPackages) return null
        return normalized
    }

    private const val APP_PREFS = "switchly_prefs"
    private const val KEY_DEV_UNLOCKED = "pref_dev_unlocked"

    private val knownSettingsPackages = setOf(
        "com.android.settings"
    )

    private val blockedHomeResolverPackages = setOf(
        "android",
        "com.android.settings",
        "com.android.intentresolver",
        "com.google.android.permission",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )
}
