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

object AndroidSystemPackages {
    const val ANDROID = "android"
    const val SYSTEM_UI = "com.android.systemui"
    const val INTENT_RESOLVER = "com.android.intentresolver"

    const val SETTINGS = "com.android.settings"
    const val GOOGLE_SETTINGS = "com.google.android.settings"
    const val SAMSUNG_SETTINGS = "com.samsung.android.settings"
    const val SETTINGS_DEVICE_ADMIN_CLASS = "com.android.settings.DeviceAdminSettings"

    const val PLAY_STORE = "com.android.vending"

    const val ANDROID_PACKAGE_INSTALLER = "com.android.packageinstaller"
    const val GOOGLE_PACKAGE_INSTALLER = "com.google.android.packageinstaller"
    const val SAMSUNG_PACKAGE_INSTALLER = "com.samsung.android.packageinstaller"
    const val MIUI_PACKAGE_INSTALLER = "com.miui.packageinstaller"
    const val OPPO_PACKAGE_INSTALLER = "com.oplus.packageinstaller"
    const val COLOR_OS_PACKAGE_INSTALLER = "com.coloros.packageinstaller"
    const val REALME_PACKAGE_INSTALLER = "com.realme.packageinstaller"
    const val VIVO_PACKAGE_INSTALLER = "com.vivo.packageinstaller"
    const val HUAWEI_PACKAGE_INSTALLER = "com.huawei.android.packageinstaller"

    const val ANDROID_PERMISSION_CONTROLLER = "com.android.permissioncontroller"
    const val GOOGLE_PERMISSION_CONTROLLER = "com.google.android.permissioncontroller"
    const val SAMSUNG_PERMISSION_CONTROLLER = "com.samsung.android.permissioncontroller"
    const val LEGACY_GOOGLE_PERMISSION_CONTROLLER = "com.google.android.permission"

    const val ANDROID_SYSTEM_INTELLIGENCE = "com.google.android.as"

    val SETTINGS_PACKAGES: Set<String> = setOf(
        SETTINGS,
        GOOGLE_SETTINGS,
        SAMSUNG_SETTINGS,
    )

    val PACKAGE_INSTALLER_PACKAGES: Set<String> = setOf(
        ANDROID_PACKAGE_INSTALLER,
        GOOGLE_PACKAGE_INSTALLER,
        SAMSUNG_PACKAGE_INSTALLER,
        MIUI_PACKAGE_INSTALLER,
        OPPO_PACKAGE_INSTALLER,
        COLOR_OS_PACKAGE_INSTALLER,
        REALME_PACKAGE_INSTALLER,
        VIVO_PACKAGE_INSTALLER,
        HUAWEI_PACKAGE_INSTALLER,
    )

    val PERMISSION_CONTROLLER_PACKAGES: Set<String> = setOf(
        ANDROID_PERMISSION_CONTROLLER,
        GOOGLE_PERMISSION_CONTROLLER,
        SAMSUNG_PERMISSION_CONTROLLER,
        LEGACY_GOOGLE_PERMISSION_CONTROLLER,
    )

    val SETTINGS_BYPASS_PACKAGES: Set<String> = SETTINGS_PACKAGES +
        PERMISSION_CONTROLLER_PACKAGES +
        setOf(
            "com.miui.securitycenter",
            "com.miui.securitycenter.remote",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.vivo.permissionmanager",
            "com.huawei.systemmanager",
        )

    val UNINSTALL_SURFACE_PACKAGES: Set<String> = SETTINGS_PACKAGES +
        PACKAGE_INSTALLER_PACKAGES +
        PERMISSION_CONTROLLER_PACKAGES +
        PLAY_STORE

    val BLOCKED_HOME_RESOLVER_PACKAGES: Set<String> = setOf(
        ANDROID,
        SETTINGS,
        INTENT_RESOLVER,
        LEGACY_GOOGLE_PERMISSION_CONTROLLER,
        GOOGLE_PERMISSION_CONTROLLER,
        ANDROID_PERMISSION_CONTROLLER,
    )
}
