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

import android.app.ActivityManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.BlockedInboxStore
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.InAppRuleStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.sync.BackupCategory
import at.saltyy.switchly.data.sync.BackupSelection
import at.saltyy.switchly.data.sync.BackupSelectionStore
import at.saltyy.switchly.data.sync.FileBackupRuntime
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.showSwitchlyMultiChoiceDialog
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrivacyReportActivity : TilesInfoActivity() {
    private data class LocalDataCounts(
        val profiles: Int,
        val schedules: Int,
        val websiteRules: Int,
        val inAppPackages: Int,
        val blockedNotifications: Int,
        val diagnosticLogs: Int
    )

    private val exportDataLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        exportSelectedData(uri)
    }
    private var pendingExportSelection: BackupSelection? = null

    override fun screenTitle(): String = getString(R.string.privacy_report_title)

    override fun onResume() {
        super.onResume()
        refreshTiles()
    }

    override fun tiles(): List<Tile> {
        val selection = BackupSelectionStore.load(this)
        val localData = localDataCounts()
        return listOf(
            Tile(
                title = getString(R.string.privacy_report_profiles_schedules_title),
                subtitle = getString(
                    R.string.privacy_report_profiles_schedules_summary,
                    localData.profiles,
                    localData.schedules
                ),
                sectionTitle = getString(R.string.privacy_report_local_title),
                iconRes = R.drawable.schedule_24,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_rules_title),
                subtitle = getString(
                    R.string.privacy_report_rules_summary,
                    localData.websiteRules,
                    localData.inAppPackages
                ),
                sectionTitle = getString(R.string.privacy_report_local_title),
                iconRes = R.drawable.app_blocking_black_24,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_diagnostics_title),
                subtitle = getString(
                    R.string.privacy_report_diagnostics_summary,
                    localData.blockedNotifications,
                    localData.diagnosticLogs
                ),
                sectionTitle = getString(R.string.privacy_report_local_title),
                iconRes = R.drawable.notifications_24,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_not_stored_title),
                subtitle = getString(R.string.privacy_report_not_stored_compact),
                sectionTitle = getString(R.string.privacy_report_section_privacy),
                iconRes = R.drawable.lock_24,
                onClick = {
                    showInfo(
                        R.string.privacy_report_not_stored_title,
                        getString(R.string.privacy_report_not_stored_summary)
                    )
                },
                showOpenButton = true,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_sharing_title),
                subtitle = getString(R.string.privacy_report_sharing_compact),
                sectionTitle = getString(R.string.privacy_report_section_privacy),
                iconRes = R.drawable.security_24,
                onClick = {
                    showInfo(
                        R.string.privacy_report_sharing_title,
                        getString(R.string.privacy_report_sharing_summary)
                    )
                },
                showOpenButton = true,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_backup_title),
                subtitle = getString(
                    R.string.privacy_report_backup_compact,
                    selection.displaySummary()
                ),
                sectionTitle = getString(R.string.privacy_report_section_privacy),
                iconRes = R.drawable.cloud_24,
                onClick = {
                    showInfo(
                        R.string.privacy_report_backup_title,
                        getString(
                            R.string.privacy_report_backup_summary,
                            selection.displaySummary(),
                            selection.includedNames()
                        )
                    )
                },
                showOpenButton = true,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_export_report_title),
                subtitle = getString(R.string.privacy_report_export_report_summary),
                sectionTitle = getString(R.string.privacy_report_section_actions),
                iconRes = R.drawable.content_copy_24,
                onClick = { sharePrivacyReport() },
                showOpenButton = true,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_export_data_title),
                subtitle = getString(
                    R.string.privacy_report_export_data_summary,
                    selection.displaySummary()
                ),
                sectionTitle = getString(R.string.privacy_report_section_actions),
                iconRes = R.drawable.cloud_download_24,
                onClick = { showExportSelectionDialog() },
                showOpenButton = true,
                enableLongPressCopy = false
            ),
            Tile(
                title = getString(R.string.privacy_report_delete_title),
                subtitle = getString(R.string.privacy_report_delete_summary),
                sectionTitle = getString(R.string.privacy_report_section_actions),
                iconRes = R.drawable.delete_24,
                onClick = { confirmDeleteAllData() },
                subtitleColorRes = R.color.status_error,
                subtitleAlpha = 1f,
                enableLongPressCopy = false
            )
        )
    }

    private fun localDataCounts(): LocalDataCounts {
        val profiles = ProfileStore.getProfiles(this)
        val websiteRules = profiles.sumOf { profile ->
            DomainBlockStore.getDomainsForProfile(this, profile).size +
                DomainBlockStore.getAllowedDomainsForProfile(this, profile).size
        }
        val inAppPackages = profiles.sumOf { profile ->
            InAppRuleStore.getPackagesWithEnabledRules(this, profile).size
        }
        return LocalDataCounts(
            profiles = profiles.size,
            schedules = ScheduleStore.getAll(this).size,
            websiteRules = websiteRules,
            inAppPackages = inAppPackages,
            blockedNotifications = BlockedInboxStore.getAll(this).size,
            diagnosticLogs = AppLogStore.latestLines(this).size
        )
    }

    private fun localDataSummary(): String {
        val localData = localDataCounts()
        return getString(
            R.string.privacy_report_local_compact,
            localData.profiles,
            localData.schedules,
            localData.websiteRules,
            localData.inAppPackages,
            localData.blockedNotifications,
            localData.diagnosticLogs
        )
    }

    private fun showInfo(titleRes: Int, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    private fun createPrivacyReport(): String {
        val selection = BackupSelectionStore.load(this)
        return getString(
            R.string.privacy_report_export_body,
            localDataSummary(),
            getString(R.string.privacy_report_not_stored_summary),
            getString(R.string.privacy_report_sharing_summary),
            selection.displaySummary(),
            selection.includedNames()
        )
    }

    private fun sharePrivacyReport() {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.privacy_report_title))
                    putExtra(Intent.EXTRA_TEXT, createPrivacyReport())
                },
                getString(R.string.privacy_report_export_report_title)
            )
        )
    }

    private fun showExportSelectionDialog() {
        val categories = BackupCategory.values()
        val current = BackupSelectionStore.load(this)
        val checked = categories.map { current.includes(it) }.toBooleanArray()
        val options = categories.map { category ->
            val suffix = if (category.sensitive) {
                getString(R.string.backup_category_sensitive_suffix)
            } else {
                ""
            }
            SwitchlyDialogOption(
                title = "${category.displayName}$suffix",
                summary = category.description,
                iconRes = backupCategoryIconRes(category)
            )
        }

        showSwitchlyMultiChoiceDialog(
            title = getString(R.string.privacy_report_export_select_title),
            options = options,
            checked = checked,
            positiveTextRes = R.string.privacy_report_export_select_action,
            compact = false,
            widthFraction = 0.94f,
        ) { states ->
            val selectedIds = categories
                .filterIndexed { index, _ -> states.getOrNull(index) == true }
                .map { it.id }
                .toSet()
            if (selectedIds.isEmpty()) {
                Toast.makeText(this, R.string.privacy_report_export_select_empty, Toast.LENGTH_SHORT).show()
                return@showSwitchlyMultiChoiceDialog
            }
            pendingExportSelection = BackupSelection.fromIds(selectedIds)
            exportDataLauncher.launch(exportFileName())
        }
    }

    private fun backupCategoryIconRes(category: BackupCategory): Int = when (category) {
        BackupCategory.PROFILES -> R.drawable.switch_account_24
        BackupCategory.BLOCKED_APPS -> R.drawable.apps_24
        BackupCategory.WEBSITE_RULES -> R.drawable.language_24
        BackupCategory.WEBSITE_BROWSER_SETTINGS -> R.drawable.language_24
        BackupCategory.NOTIFICATION_BLOCKING -> R.drawable.notifications_24
        BackupCategory.IN_APP_BLOCKING -> R.drawable.app_blocking_black_24
        BackupCategory.SCHEDULES -> R.drawable.schedule_24
        BackupCategory.LOCATION_SCHEDULES -> R.drawable.location_on_24
        BackupCategory.WIFI_SCHEDULES -> R.drawable.wifi_24
        BackupCategory.BLUETOOTH_SCHEDULES -> R.drawable.bluetooth_24
        BackupCategory.KEYS -> R.drawable.nfc_24
        BackupCategory.CONTROL_SETTINGS -> R.drawable.tune_24
        BackupCategory.STRICT_PROTECTION -> R.drawable.lock_24
        BackupCategory.STATISTICS -> R.drawable.bar_chart_24
        BackupCategory.APP_PREFERENCES -> R.drawable.account_box_24
    }

    private fun exportSelectedData(uri: Uri) {
        val selection = pendingExportSelection ?: BackupSelectionStore.load(this)
        pendingExportSelection = null
        val progress = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_report_export_data_title)
            .setMessage(R.string.privacy_report_export_progress)
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileBackupRuntime.writeLocalBackupToUri(this@PrivacyReportActivity, uri, selection)
            }
            progress.dismiss()
            Toast.makeText(
                this@PrivacyReportActivity,
                if (result.isSuccess) {
                    R.string.privacy_report_export_done
                } else {
                    R.string.privacy_report_export_failed
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun exportFileName(): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return "Switchly-backup-$date.json"
    }

    private fun confirmDeleteAllData() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_reset_app_data_confirm_title)
            .setMessage(
                getString(R.string.pref_reset_app_data_confirm_message) + "\n\n" +
                    getString(R.string.destructive_cannot_be_undone)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                if (!activityManager.clearApplicationUserData()) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = "package:$packageName".toUri()
                        }
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showDestructiveAccented()
    }
}
