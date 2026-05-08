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

package at.saltyy.switchly.feature.about

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.net.toUri
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AdvancedModeStore
import at.saltyy.switchly.util.PlayStoreUpdatePrompt
import java.text.DateFormat
import java.util.Date

class AppInfoActivity : TilesInfoActivity() {

    override fun screenTitle(): String = getString(R.string.about_app_info_title)

    override fun tiles(): List<Tile> {
        val appName = getString(R.string.app_name)
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE.toString()
        val pkg = BuildConfig.APPLICATION_ID

        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        val firstInstall = pi?.firstInstallTime?.takeIf { it > 0L }?.let { df.format(Date(it)) } ?: "-"
        val lastUpdate = pi?.lastUpdateTime?.takeIf { it > 0L }?.let { df.format(Date(it)) } ?: "-"

        val installerPackage = resolveInstallerPackageName()
        val installer = formatInstallerLabel(installerPackage)

        val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"

        val website = getString(R.string.about_website_url)
        val downloads = getString(R.string.about_downloads_url)
        val repo = getString(R.string.about_gitlab_url)
        val discord = getString(R.string.about_discord_url)

        return listOf(
            Tile(
                getString(R.string.about_app_name_label),
                appName
            ),
            Tile(
                getString(R.string.about_version_label),
                "$versionName ($versionCode)",
                onClick = { PlayStoreUpdatePrompt.promptNow(this) },
                onLongClick = {
                    unlockAndOpenAdvancedMode()
                    true
                },
                enableLongPressCopy = false
            ),
            Tile(
                getString(R.string.about_build_type_label),
                buildType
            ),
            Tile(
                getString(R.string.about_package_label),
                pkg,
                onClick = {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = "package:$pkg".toUri()
                            }
                        )
                    }
                }
            ),
            Tile(
                getString(R.string.about_install_source_label),
                installer,
                showCopyButton = true,
                copiedToast = getString(R.string.copied)
            ),
            Tile(
                getString(R.string.about_first_install_label),
                firstInstall
            ),
            Tile(
                getString(R.string.about_last_update_label),
                lastUpdate
            ),
            Tile(
                getString(R.string.about_website_label),
                website,
                onClick = { openUrl(website) }
            ),
            Tile(
                getString(R.string.about_older_versions_label),
                downloads,
                onClick = { openUrl(downloads) },
                showCopyButton = true,
                copiedToast = getString(R.string.copied)
            ),
            Tile(
                getString(R.string.about_gitlab_label),
                repo,
                onClick = { openUrl(repo) },
                showCopyButton = true,
                copiedToast = getString(R.string.copied)
            ),
            Tile(
                getString(R.string.about_discord_label),
                discord,
                onClick = { openUrl(discord) },
                showCopyButton = true,
                copiedToast = getString(R.string.about_discord_copied)
            ),
        )
    }

    private fun unlockAndOpenAdvancedMode() {
        val wasEnabled = AdvancedModeStore.isEnabled(this)
        if (!wasEnabled) {
            AdvancedModeStore.setEnabled(this, true)
            Toast.makeText(this, getString(R.string.advanced_mode_unlocked_toast), Toast.LENGTH_SHORT).show()
        }
        openAdvancedMode()
    }

    private fun openAdvancedMode() {
        startActivity(Intent(this, AdvancedModeActivity::class.java))
    }

    private fun resolveInstallerPackageName(): String? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(packageName).installingPackageName
            } else {
                packageManager.javaClass
                    .getMethod("getInstallerPackageName", String::class.java)
                    .invoke(packageManager, packageName) as? String
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun formatInstallerLabel(installerPackage: String?): String {
        if (installerPackage.isNullOrBlank()) {
            return getString(R.string.about_install_source_unknown)
        }

        val known = mapOf(
            "com.android.vending" to getString(R.string.about_install_source_google_play),
            "com.google.android.feedback" to getString(R.string.about_install_source_google_play),
            "com.amazon.venezia" to getString(R.string.about_install_source_amazon),
            "org.fdroid.fdroid" to "F-Droid",
            "com.sec.android.app.samsungapps" to getString(R.string.about_install_source_galaxy_store),
            "com.huawei.appmarket" to "Huawei AppGallery",
            "com.xiaomi.mipicks" to "Xiaomi GetApps",
            "com.android.packageinstaller" to getString(R.string.about_install_source_package_installer),
            "com.google.android.packageinstaller" to getString(R.string.about_install_source_package_installer),
            "com.miui.packageinstaller" to getString(R.string.about_install_source_package_installer)
        )
        known[installerPackage]?.let { return it }

        val label = runCatching {
            val appInfo = packageManager.getApplicationInfo(installerPackage, 0)
            packageManager.getApplicationLabel(appInfo).toString().trim()
        }.getOrNull()

        return label?.takeIf { it.isNotBlank() } ?: installerPackage
    }

    private fun openUrl(url: String) {
        val i = Intent(Intent.ACTION_VIEW, url.toUri())
        runCatching { startActivity(i) }
    }
}
