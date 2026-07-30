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

package at.saltyy.switchly.data.statistics

import at.saltyy.switchly.util.AndroidSystemPackages
import java.util.Locale

/**
 * Central package catalog for Usage & Insights.
 * System-only components are always excluded, while user-facing candidates are suggested to users.
 */
object UsageInsightsAppCatalog {
    private val CORE_SYSTEM_PACKAGES = setOf(
        AndroidSystemPackages.ANDROID,
        AndroidSystemPackages.SYSTEM_UI,
        "com.android.shell",
        AndroidSystemPackages.INTENT_RESOLVER,
        "com.android.settings.intelligence",
    )

    private val NFC_SERVICE_PACKAGES = setOf(
        "com.android.nfc",
        "com.google.android.nfc",
        "com.samsung.android.nfc",
    )

    private val TELECOM_SERVICE_PACKAGES = setOf(
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.incallui",
    )

    private val VPN_DIALOG_PACKAGES = setOf(
        "com.android.vpndialogs",
        "com.google.android.vpndialogs",
    )

    private val PACKAGE_INSTALLER_PACKAGES = AndroidSystemPackages.PACKAGE_INSTALLER_PACKAGES

    private val PERMISSION_CONTROLLER_PACKAGES = AndroidSystemPackages.PERMISSION_CONTROLLER_PACKAGES

    private val BACKGROUND_SYSTEM_SERVICE_PACKAGES = setOf(
        "com.android.certinstaller",
        "com.android.keychain",
        "com.android.managedprovisioning",
        "com.android.networkstack",
        "com.android.proxyhandler",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.modulemetadata",
        "com.google.android.networkstack",
    )

    internal val SETTINGS_CANDIDATES = AndroidSystemPackages.SETTINGS_PACKAGES

    internal val CONTACTS_CANDIDATES = setOf(
        "com.android.contacts",
        "com.google.android.contacts",
        "com.samsung.android.contacts",
    )

    internal val FILES_CANDIDATES = setOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.google.android.apps.nbu.files",
        "com.sec.android.app.myfiles",
    )

    internal val GOOGLE_CANDIDATES = setOf(
        "com.google.android.googlequicksearchbox",
    )

    internal val STATIC_SUGGESTION_PACKAGES: Set<String> =
        SETTINGS_CANDIDATES + CONTACTS_CANDIDATES + FILES_CANDIDATES + GOOGLE_CANDIDATES

    private val ALWAYS_EXCLUDED_PACKAGES: Set<String> =
        CORE_SYSTEM_PACKAGES +
            NFC_SERVICE_PACKAGES +
            TELECOM_SERVICE_PACKAGES +
            VPN_DIALOG_PACKAGES +
            PACKAGE_INSTALLER_PACKAGES +
            PERMISSION_CONTROLLER_PACKAGES +
            BACKGROUND_SYSTEM_SERVICE_PACKAGES

    fun shouldAlwaysHide(packageName: String): Boolean {
        val normalized = packageName.trim().lowercase(Locale.US)
        return normalized.isBlank() || normalized in ALWAYS_EXCLUDED_PACKAGES
    }
}
