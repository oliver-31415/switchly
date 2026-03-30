package at.saltyy.switchly.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import at.saltyy.switchly.R

object AppBlockSafety {

    enum class Level {
        NONE,
        SOFT_WARNING,
        HARD_EXCLUDED
    }

    data class Info(
        val level: Level = Level.NONE,
        val hint: String? = null,
        val warningTitle: String? = null,
        val warningMessage: String? = null
    )

    private val walletPackagePrefixes = listOf(
        "com.samsung.android.spay",
        "com.google.android.apps.walletnfcrel"
    )

    fun resolve(context: Context, pkg: String): Info {
        if (pkg.isBlank()) return Info()

        val defaultIme = getDefaultInputMethodPackage(context)
        if (pkg == defaultIme) {
            return Info(
                level = Level.HARD_EXCLUDED,
                hint = context.getString(R.string.app_picker_protected_keyboard_hint)
            )
        }

        val defaultHome = getDefaultHomePackage(context)
        if (pkg == defaultHome) {
            return Info(
                level = Level.HARD_EXCLUDED,
                hint = context.getString(R.string.app_picker_protected_launcher_hint)
            )
        }

        if (isWalletPackage(pkg)) {
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

        val defaultDialer = getDefaultDialerPackage(context)
        if (pkg == defaultDialer) {
            return Info(
                level = Level.SOFT_WARNING,
                hint = context.getString(R.string.app_picker_dialer_hint),
                warningTitle = context.getString(R.string.app_picker_dialer_warning_title),
                warningMessage = context.getString(R.string.app_picker_dialer_warning_message)
            )
        }

        if (isSettingsPackage(context, pkg)) {
            return Info(
                level = Level.SOFT_WARNING,
                hint = context.getString(R.string.app_picker_settings_hint),
                warningTitle = context.getString(R.string.app_picker_settings_warning_title),
                warningMessage = context.getString(R.string.app_picker_settings_warning_message)
            )
        }

        return Info()
    }

    fun isHardExcluded(context: Context, pkg: String): Boolean =
        resolve(context, pkg).level == Level.HARD_EXCLUDED

    fun sanitizeManagedPackages(context: Context, pkgs: Set<String>): Set<String> {
        if (pkgs.isEmpty()) return emptySet()
        return pkgs
            .asSequence()
            .filter { it.isNotBlank() }
            .filterNot { isHardExcluded(context, it) }
            .toCollection(linkedSetOf())
    }

    fun getDefaultInputMethodPackage(context: Context): String? {
        val currentId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        }.getOrNull().orEmpty().trim()
        if (currentId.isBlank()) return null

        val imm = context.getSystemService(InputMethodManager::class.java)
        val fromEnabledList = runCatching {
            imm?.enabledInputMethodList
                ?.firstOrNull { it.id == currentId }
                ?.packageName
        }.getOrNull()

        if (!fromEnabledList.isNullOrBlank()) return fromEnabledList

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

    fun getDefaultDialerPackage(context: Context): String? {
        val telecomManager = runCatching {
            context.getSystemService(TelecomManager::class.java)
        }.getOrNull()
        return runCatching { telecomManager?.defaultDialerPackage }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isWalletPackage(pkg: String): Boolean {
        return walletPackagePrefixes.any { prefix ->
            pkg == prefix || pkg.startsWith("$prefix.")
        }
    }

    private fun isSettingsPackage(context: Context, pkg: String): Boolean {
        val normalized = pkg.trim()
        if (normalized.isBlank()) return false
        if (normalized in knownSettingsPackages) return true

        val resolved = runCatching {
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
        }.getOrNull()?.activityInfo?.packageName?.trim().orEmpty()

        return resolved.isNotBlank() && normalized == resolved
    }

    private fun normalizeHomePackage(pkg: String?): String? {
        val normalized = pkg?.trim().orEmpty()
        if (normalized.isBlank()) return null
        if (normalized in blockedHomeResolverPackages) return null
        return normalized
    }

    private val knownSettingsPackages = setOf(
        "com.android.settings"
    )

    private val blockedHomeResolverPackages = setOf(
        "android",
        "com.android.settings",
        "com.android.intentresolver",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller"
    )
}
