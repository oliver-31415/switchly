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

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.net.toUri
import at.saltyy.switchly.R
import at.saltyy.switchly.receiver.DPMReceiver
import at.saltyy.switchly.util.AndroidSystemPackages

class AdvancedModeActivity : TilesInfoActivity() {

    private lateinit var adminComponentName: ComponentName
    private lateinit var adminFlatComponent: String
    private var lastManagedStateKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        adminComponentName = ComponentName(this, DPMReceiver::class.java)
        adminFlatComponent = adminComponentName.flattenToShortString()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        val currentStateKey = currentManagedStateKey()
        refreshTiles()
        val previousStateKey = lastManagedStateKey
        if (previousStateKey != null && previousStateKey != currentStateKey) {
            Toast.makeText(this, getString(R.string.advanced_mode_status_updated_toast), Toast.LENGTH_SHORT).show()
        }
        lastManagedStateKey = currentStateKey
    }

    override fun screenTitle(): String = getString(R.string.advanced_mode_title)

    private fun currentManagedStateKey(): String {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return when {
            dpm?.isDeviceOwnerApp(packageName) == true -> "device_owner"
            dpm?.isProfileOwnerApp(packageName) == true -> "profile_owner"
            dpm?.isAdminActive(adminComponentName) == true -> "device_admin"
            else -> "standard"
        }
    }

    override fun tiles(): List<Tile> {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val isDeviceOwner = dpm?.isDeviceOwnerApp(packageName) == true
        val isProfileOwner = dpm?.isProfileOwnerApp(packageName) == true
        val isDeviceAdmin = dpm?.isAdminActive(adminComponentName) == true

        val detailStatus = when {
            isDeviceOwner -> getString(R.string.advanced_mode_status_device_owner)
            isProfileOwner -> getString(R.string.advanced_mode_status_profile_owner)
            isDeviceAdmin -> getString(R.string.advanced_mode_status_device_admin)
            else -> getString(R.string.advanced_mode_status_standard)
        }
        val managedActive = isDeviceOwner || isProfileOwner || isDeviceAdmin

        val deviceOwnerCmd = "adb shell dpm set-device-owner '$adminFlatComponent'"
        val profileOwnerCmd = "adb shell dpm set-profile-owner '$adminFlatComponent'"
        val removeAdminCmd = "adb shell dpm remove-active-admin '$adminFlatComponent'"
        val dumpsysCmd = "adb shell dumpsys device_policy"

        val summary = getString(R.string.advanced_mode_summary_body)
        val recommendation = getString(R.string.advanced_mode_recommendation_body)
        val statusSubtitle = detailStatus

        return listOf(
            Tile(
                title = getString(R.string.advanced_mode_tile_status),
                subtitle = statusSubtitle,
                sectionTitle = getString(R.string.about_section_admin_status),
                iconRes = R.drawable.security_24,
                subtitleColorRes = if (managedActive) R.color.accent_default_green else android.R.color.holo_red_dark,
                subtitleAlpha = 1f,
                onClick = { openAdminScreen() },
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_constraints),
                subtitle = getString(R.string.advanced_mode_constraints_body),
                sectionTitle = getString(R.string.about_section_admin_status),
                iconRes = R.drawable.lock_24,
                copyValue = getString(R.string.advanced_mode_constraints_body)
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_summary),
                subtitle = summary,
                sectionTitle = getString(R.string.about_section_documentation),
                iconRes = R.drawable.info_24,
                copyValue = summary
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_recommendation),
                subtitle = recommendation,
                sectionTitle = getString(R.string.about_section_documentation),
                iconRes = R.drawable.help_24,
                copyValue = recommendation
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_receiver),
                subtitle = adminFlatComponent,
                sectionTitle = getString(R.string.about_section_adb_tools),
                iconRes = R.drawable.account_box_24,
                copyValue = adminFlatComponent,
                showCopyButton = true,
                copiedToast = getString(R.string.advanced_mode_component_copied)
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_device_owner),
                subtitle = deviceOwnerCmd,
                sectionTitle = getString(R.string.about_section_adb_tools),
                iconRes = R.drawable.security_24,
                copyValue = deviceOwnerCmd,
                showCopyButton = true,
                copiedToast = getString(R.string.advanced_mode_command_copied)
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_profile_owner),
                subtitle = profileOwnerCmd,
                sectionTitle = getString(R.string.about_section_adb_tools),
                iconRes = R.drawable.switch_account_24,
                copyValue = profileOwnerCmd,
                showCopyButton = true,
                copiedToast = getString(R.string.advanced_mode_command_copied)
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_remove_admin),
                subtitle = removeAdminCmd,
                sectionTitle = getString(R.string.about_section_adb_tools),
                iconRes = R.drawable.delete_24,
                copyValue = removeAdminCmd,
                showCopyButton = true,
                copiedToast = getString(R.string.advanced_mode_command_copied)
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_check_state),
                subtitle = dumpsysCmd,
                sectionTitle = getString(R.string.about_section_adb_tools),
                iconRes = R.drawable.tune_24,
                copyValue = dumpsysCmd,
                showCopyButton = true,
                copiedToast = getString(R.string.advanced_mode_command_copied)
            ),
            Tile(
                title = getString(R.string.advanced_mode_tile_docs),
                subtitle = getString(R.string.advanced_mode_docs_body),
                sectionTitle = getString(R.string.about_section_documentation),
                iconRes = R.drawable.language_24,
                onClick = { openUrl(getString(R.string.advanced_mode_docs_url)) },
                copyValue = getString(R.string.advanced_mode_docs_url),
                showCopyButton = true,
                copiedToast = getString(R.string.advanced_mode_command_copied)
            )
        )
    }

    private fun openAdminScreen() {
        val primary = Intent().setComponent(
            ComponentName(
                AndroidSystemPackages.SETTINGS,
                AndroidSystemPackages.SETTINGS_DEVICE_ADMIN_CLASS
            )
        )

        val fallback = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.advanced_mode_add_admin_explanation)
            )
        }

        val opened = runCatching { startActivity(primary) }.isSuccess
        if (!opened) {
            runCatching { startActivity(fallback) }.onFailure {
                Toast.makeText(
                    this,
                    getString(R.string.advanced_mode_open_admin_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }.onFailure {
            Toast.makeText(this, getString(R.string.about_no_browser), Toast.LENGTH_SHORT).show()
        }
    }
}
