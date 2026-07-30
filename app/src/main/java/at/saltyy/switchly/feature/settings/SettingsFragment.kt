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

package at.saltyy.switchly.feature.settings

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Patterns
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.auth.AccountDeletion
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.ActivityHistoryLogStore
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.sync.BackupCategory
import at.saltyy.switchly.data.sync.BackupCategoryFilter
import at.saltyy.switchly.data.sync.BackupSelection
import at.saltyy.switchly.data.sync.BackupSelectionStore
import at.saltyy.switchly.data.statistics.StatsPersistence
import at.saltyy.switchly.data.sync.CloudSyncRuntime
import at.saltyy.switchly.data.sync.FileBackupRuntime
import at.saltyy.switchly.feature.about.AppInfoActivity
import at.saltyy.switchly.feature.about.DeveloperInfoActivity
import at.saltyy.switchly.feature.about.DeviceInfoActivity
import at.saltyy.switchly.feature.about.OtherSwitchlyProductsActivity
import at.saltyy.switchly.feature.about.PrivacyReportActivity
import at.saltyy.switchly.feature.about.WhatsNewActivity
import at.saltyy.switchly.feature.faq.FaqActivity
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.ManagePairedTagsActivity
import at.saltyy.switchly.feature.support.SupportActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.showSwitchlyMultiChoiceDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.TimeFormatPrefs
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.firebase.auth.FirebaseAuth
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : PreferenceFragmentCompat() {

    private fun openToggleOptions(section: String? = null) {
        val intent = Intent(requireContext(), ToggleOptionsActivity::class.java)
        if (!section.isNullOrBlank()) {
            intent.putExtra(ToggleOptionsActivity.EXTRA_SCROLL_TO_SECTION, section)
        }
        startActivity(intent)
    }

    fun currentScreenTitle(): String {
        val t = preferenceScreen?.title?.toString()
        return if (!t.isNullOrBlank()) {
            t
        } else {
            getString(R.string.settings)
        }
    }

    fun scrollToTop() {
        listView?.post {
            runCatching {
                listView?.smoothScrollToPosition(0)
            }
        }
    }

    private val categoryTitles = mutableSetOf<String>()
    private var devVisible: Boolean = false
    private fun refreshBlockedInboxPreferenceState() {
        findPreference<Preference>("pref_blocked_inbox")?.isVisible = true
    }

    private fun refreshHomeModeAppearancePrefs() {
        val ctx = context ?: return
        val currentMode = HomeModeDialogHelper.currentHomeLayoutMode(ctx)
        val modeLabel = HomeModeDialogHelper.homeLayoutModeLabel(ctx, currentMode)
        findPreference<Preference>("pref_home_mode_appearance")?.summary = modeLabel
        findPreference<Preference>("pref_customize_home_appearance")?.isVisible = currentMode == ToggleOptionsActivity.HOME_MODE_CUSTOM
    }

    private var authListener: FirebaseAuth.AuthStateListener? = null
    private var nextChangedReceiver: BroadcastReceiver? = null
    private var lastNestedNavKey: String? = null
    private var lastNestedNavAtMs: Long = 0L
    private var pendingFileBackupSelection: BackupSelection? = null

    private fun isRestrictedSettingsAccess(): Boolean {
        return (activity as? SettingsActivity)?.isRestrictedAccessActive() == true
    }

    private fun applyRestrictedAccountDataState() {
        val restricted = isRestrictedSettingsAccess()
        val restrictedKeys = listOf(
            "screen_backup_restore",
            "pref_cloud_restore",
            "pref_file_restore",
            "pref_reset_app_data"
        )
        restrictedKeys.forEach { key ->
            findPreference<Preference>(key)?.isEnabled = !restricted
        }
    }

    private val createBackupFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) {
            pendingFileBackupSelection = null
            return@registerForActivityResult
        }
        writeBackupFile(uri)
    }

    private val restoreBackupFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        restoreBackupFile(uri)
    }

    private data class IconActionItem(val title: String, val iconRes: Int, val tintIcon: Boolean = true)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // If we are navigating into a nested PreferenceScreen (Help/Account/...), we pass the target root via fragment arguments.
        val effectiveRoot = rootKey ?: arguments?.getString(ARG_PREFERENCE_ROOT)
        setPreferencesFromResource(R.xml.preferences_settings, effectiveRoot)

        val ctx = requireContext()
        // App-scoped prefs used by SettingsFragment
        val appPrefs = ctx.getSharedPreferences(PREFS, 0)
        devVisible = appPrefs.getBoolean(KEY_DEV_UNLOCKED, false)

        tintCategories()
        ensureDeveloperInfoIconAccent()
        // Extra pass to prevent occasional fallback to default accent in Customize.
        requireActivity().window?.decorView?.post {
            runCatching {
                CustomAccentApplier.applyIfNeeded(requireActivity())
                tintCategories()
                ensureDeveloperInfoIconAccent()
                tintCategoryViewsInList()
            }
        }

        // Hidden master toggle (dev-only)
        findPreference<SwitchPreferenceCompat>("pref_switch_mode")?.apply {
            isVisible = devVisible
            isChecked = SwitchModeStore.isEnabled(ctx)

            setOnPreferenceClickListener {
                val enabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
                val locked = enabled && requireNfc
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                }
                false
            }

            setOnPreferenceChangeListener { _, new ->
                val target = new as Boolean
                val currentlyEnabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)

                val locked = currentlyEnabled && requireNfc && !target
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                    false
                } else {
                    SwitchModeStore.setEnabled(ctx, target)
                    AppLogStore.append(
                        ctx,
                        "Profiles",
                        "Manual toggle action=${if (target) "enable" else "disable"} profile=${ProfileStore.getCurrent(ctx)}"
                    )
                    refreshLockUi()
                    true
                }
            }
        }

        // Master toggle (visible)
        findPreference<SwitchPreferenceCompat>("pref_switchly_enabled")?.apply {
            isChecked = SwitchModeStore.isEnabled(ctx)

            setOnPreferenceClickListener {
                val enabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
                val locked = enabled && requireNfc
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                }
                false
            }

            setOnPreferenceChangeListener { _, new ->
                val target = new as Boolean
                val currentlyEnabled = SwitchModeStore.isEnabled(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)

                val locked = currentlyEnabled && requireNfc && !target
                if (locked) {
                    Toast.makeText(ctx, getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                    false
                } else {
                    SwitchModeStore.setEnabled(ctx, target)
                    AppLogStore.append(
                        ctx,
                        "Profiles",
                        "Manual toggle action=${if (target) "enable" else "disable"} profile=${ProfileStore.getCurrent(ctx)}"
                    )
                    refreshLockUi()
                    true
                }
            }
        }

        // Language
        findPreference<Preference>("pref_language")?.apply {
            updateLanguageSummary(this)
            setOnPreferenceClickListener {
                showLanguageDialog()
                true
            }
        }

        // Appearance (Display mode + Theme color)
        findPreference<Preference>("pref_theme_mode")?.apply {
            updateThemeModeSummary(this)
            setOnPreferenceClickListener {
                showThemeModeDialog()
                true
            }
        }

        findPreference<Preference>("pref_time_format")?.apply {
            updateTimeFormatSummary(this)
            setOnPreferenceClickListener {
                showTimeFormatDialog()
                true
            }
        }

        findPreference<Preference>("pref_theme_color")?.apply {
            updateThemeColorSummary(this)
            setOnPreferenceClickListener {
                showThemeColorDialog()
                true
            }
        }

        // Manage profiles
        findPreference<Preference>("pref_manage_profiles")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                val ctx = requireContext()
                val enabled = SwitchModeStore.isEnabled(ctx)
                val emergencyActive = EmergencyBypassStore.isActive(ctx)
                val emergencyPaused = EmergencyBypassStore.isPaused(ctx)
                val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
                val profileLocked = when {
                    emergencyActive -> false
                    !enabled -> false
                    requireNfc || emergencyPaused -> true
                    else -> !AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(ctx)
                }
                if (profileLocked) {
                    val msgRes = if (enabled && !requireNfc && !emergencyPaused) {
                        R.string.edit_locked_manage_profiles
                    } else {
                        R.string.toast_cannot_change_profile_while_locked
                    }
                    EditingLockGuard.showLockedDialog(ctx, msgRes)
                    return@setOnPreferenceClickListener true
                }
                startActivity(Intent(requireContext(), ManageProfilesActivity::class.java))
                true
            }
        }

        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val pairedUiEnabled = defaultPrefs.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)

        // NFC tag writer (should always be available; pairing is just one optional action)
        findPreference<Preference>("pref_write_tag")?.apply {
            isVisible = true
            setOnPreferenceClickListener {
                if (SwitchModeStore.isEnabled(requireContext()) &&
                    !AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(requireContext())
                ) {
                    EditingLockGuard.showLockedDialog(requireContext(), R.string.edit_locked_write_nfc_tags)
                } else {
                    startActivity(Intent(requireContext(), NfcWriterActivity::class.java))
                }
                true
            }
        }

        // Manage paired NFC tags (UID list) (only shown when feature is enabled)
        findPreference<Preference>("pref_manage_paired_tags")?.apply {
            isVisible = pairedUiEnabled
            setOnPreferenceClickListener {
                val ctx = requireContext()
                val locked = EditingLockGuard.isLocked(ctx)
                AppLogStore.append(ctx, "NFC", "Manage Paired Tags clicked from Settings locked=$locked")
                if (locked) {
                    EditingLockGuard.showLockedDialog(ctx, R.string.edit_locked_manage_paired_tags)
                } else {
                    runCatching {
                        startActivity(Intent(ctx, ManagePairedTagsActivity::class.java))
                    }.onFailure { error ->
                        AppLogStore.append(ctx, "NFC", "Failed to open Manage Paired Tags from Settings", error)
                        Toast.makeText(ctx, R.string.error_open_manage_paired_tags, Toast.LENGTH_LONG).show()
                    }
                }
                true
            }
        }

        // Emergency unlock
        findPreference<Preference>("pref_emergency_unlock")?.setOnPreferenceClickListener {
            showEmergencyUnlockWithPin()
            true
        }

        // Permissions overview
        findPreference<Preference>("pref_permissions")?.setOnPreferenceClickListener {
            val enabled = SwitchModeStore.isEnabled(requireContext())
            val requireNfc = SwitchModeStore.isNfcRequiredForDisable(requireContext())
            val locked = enabled && requireNfc
            if (locked) {
                Toast.makeText(requireContext(), getString(R.string.toast_disable_requires_nfc), Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }

            startActivity(Intent(requireContext(), PermissionsActivity::class.java))
            true
        }

        // Toggle controls: flattened entry point (single page)
        findPreference<Preference>("pref_toggle_options")?.setOnPreferenceClickListener {
            openToggleOptions()
            true
        }
        findPreference<Preference>("pref_toggle_options_blocking")?.setOnPreferenceClickListener {
            openToggleOptions()
            true
        }

        findPreference<Preference>("pref_home_mode_appearance")?.setOnPreferenceClickListener {
            HomeModeDialogHelper.showHomeLayoutModeDialog(requireContext()) {
                refreshHomeModeAppearancePrefs()
            }
            true
        }

        findPreference<Preference>("pref_customize_home_appearance")?.setOnPreferenceClickListener {
            HomeModeDialogHelper.showCustomizeHomeDialog(requireContext()) {
                refreshHomeModeAppearancePrefs()
            }
            true
        }
        refreshHomeModeAppearancePrefs()

        // Troubleshooting
        findPreference<Preference>("pref_troubleshooting")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), TroubleshootingActivity::class.java))
            true
        }

        // Blocked notifications inbox
        findPreference<Preference>("pref_blocked_inbox")?.setOnPreferenceClickListener {
            val ctx = requireContext()
            if (SwitchModeStore.isEnabled(ctx) && !EmergencyBypassStore.isActive(ctx)) {
                EditingLockGuard.showLockedDialog(ctx, R.string.edit_locked_manage_blocked_notifications)
            } else {
                startActivity(Intent(ctx, BlockedInboxActivity::class.java))
            }
            true
        }
        refreshBlockedInboxPreferenceState()

        // Open schedules (Customize -> Schedules)
        findPreference<Preference>("pref_open_schedules")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), SchedulesActivity::class.java))
            true
        }

        // Help -> Other help
        findPreference<Preference>("pref_other_help_battery")?.setOnPreferenceClickListener {
            // Open system screen where the user can allow Switchly to ignore battery optimizations.
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                // Fallback: App details
                val uri = Uri.fromParts("package", requireContext().packageName, null)
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
            true
        }

        findPreference<Preference>("pref_other_help_contact")?.setOnPreferenceClickListener {
            // Dedicated support screen with a single email action
            startActivity(Intent(requireContext(), SupportActivity::class.java))
            true
        }

        // About sub pages
        findPreference<Preference>("pref_about_app_info")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), AppInfoActivity::class.java))
            true
        }
        findPreference<Preference>("pref_about_device_info")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DeviceInfoActivity::class.java))
            true
        }
        findPreference<Preference>("pref_about_developer_info")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DeveloperInfoActivity::class.java))
            true
        }
        findPreference<Preference>("pref_about_other_switchly_products")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), OtherSwitchlyProductsActivity::class.java))
            true
        }

        // What's new
        findPreference<Preference>("pref_changelog")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), WhatsNewActivity::class.java))
            true
        }

        // FAQ
        findPreference<Preference>("pref_faq")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), FaqActivity::class.java))
            true
        }

        // Google account (popup only for sign-in/out/delete)
        findPreference<Preference>("pref_google_account")?.apply {
            isVisible = BuildConfig.SWITCHLY_FIREBASE_ENABLED
            setOnPreferenceClickListener {
                showGoogleAccountDialog()
                true
            }
        }

        // Emergency unlock PIN (Account)
        findPreference<Preference>("pref_change_emergency_pin")?.setOnPreferenceClickListener {
            showChangeEmergencyPinFlow()
            true
        }

        // Backup as standalone prefs
        findPreference<Preference>("pref_cloud_backup")?.apply {
            setOnPreferenceClickListener {
                showBackupSelectionFlow { selection ->
                    confirmAction(
                        title = getString(R.string.settings_confirm_backup_title),
                        message = backupConfirmMessage(
                            selection = selection,
                            fullMessageRes = R.string.settings_confirm_backup_message_with_categories,
                            includedOnlyMessageRes = R.string.settings_confirm_backup_message_with_included_categories,
                        ),
                        positiveText = getString(R.string.settings_confirm_backup_title),
                    ) {
                        val backupCtx = context ?: return@confirmAction
                        val loadingDialog = showProgressDialog(
                            backupCtx,
                            R.string.pref_cloud_backup_title,
                            R.string.cloud_backup_loading
                        )
                        val startBackup = {
                            CloudSyncRuntime.pushLocalState(backupCtx, selection) { ok, err ->
                                val c = context ?: return@pushLocalState
                                if (!isAdded) return@pushLocalState
                                if (loadingDialog.isShowing) loadingDialog.dismiss()
                                val msg = if (ok) {
                                    PreferenceManager.getDefaultSharedPreferences(c).edit {
                                        putLong("pref_last_backup_epoch_ms", System.currentTimeMillis())
                                    }
                                    updateGooglePrefSummary()
                                    updateCloudPrefVisibility()
                                    getString(R.string.cloud_backup_ok)
                                } else {
                                    getString(R.string.cloud_error_fmt, err ?: getString(R.string.error_unknown))
                                }
                                Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                        if (view?.post { startBackup() } != true) startBackup()
                    }
                }
                true
            }
        }

        // Restore as standalone prefs
        findPreference<Preference>("pref_cloud_restore")?.apply {
            setOnPreferenceClickListener {
                if (isRestrictedSettingsAccess()) {
                    Toast.makeText(requireContext(), R.string.settings_restricted_action_unavailable, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                confirmAction(
                    title = getString(R.string.settings_confirm_restore_title),
                    message = getString(R.string.settings_confirm_restore_message),
                    positiveText = getString(R.string.settings_confirm_restore_title),
                ) {
                    startRestoreFlowWithChoice()
                }
                true
            }
        }

        findPreference<Preference>("pref_file_backup")?.apply {
            setOnPreferenceClickListener {
                showBackupSelectionFlow { selection ->
                    confirmAction(
                        title = getString(R.string.settings_confirm_file_backup_title),
                        message = backupConfirmMessage(
                            selection = selection,
                            fullMessageRes = R.string.settings_confirm_file_backup_message_with_categories,
                            includedOnlyMessageRes = R.string.settings_confirm_file_backup_message_with_included_categories,
                        ),
                        positiveText = getString(R.string.settings_confirm_file_backup_title),
                    ) {
                        pendingFileBackupSelection = selection
                        createBackupFileLauncher.launch(defaultBackupFileName())
                    }
                }
                true
            }
        }

        findPreference<Preference>("pref_file_restore")?.apply {
            setOnPreferenceClickListener {
                if (isRestrictedSettingsAccess()) {
                    Toast.makeText(requireContext(), R.string.settings_restricted_action_unavailable, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                confirmAction(
                    title = getString(R.string.settings_confirm_file_restore_title),
                    message = getString(R.string.settings_confirm_file_restore_message),
                    positiveText = getString(R.string.settings_confirm_file_restore_title),
                ) {
                    restoreBackupFileLauncher.launch(
                        arrayOf("application/json", "text/json", "text/plain", "*/*")
                    )
                }
                true
            }
        }

        // Delete backups
        findPreference<Preference>("pref_cloud_delete_backups")?.apply {
            setOnPreferenceClickListener {
                showDeleteBackupsDialog()
                true
            }
        }

        findPreference<Preference>("pref_privacy_report")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), PrivacyReportActivity::class.java))
            true
        }

        // Local in-app reset (clear ALL app data)
        findPreference<Preference>("pref_reset_app_data")?.setOnPreferenceClickListener {
            if (isRestrictedSettingsAccess()) {
                Toast.makeText(requireContext(), R.string.settings_restricted_action_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnPreferenceClickListener true
            }
            showResetAllDataDialog()
            true
        }

        // Tutorial
        findPreference<Preference>("pref_tutorial")
            ?.setOnPreferenceClickListener {
                startActivity(
                    Intent(requireContext(), at.saltyy.switchly.feature.onboarding.OnboardingActivity::class.java)
                        .putExtra(at.saltyy.switchly.feature.onboarding.OnboardingActivity.EXTRA_FORCE, true)
                )
                true
            }

        // Initial UI state
        updateGooglePrefSummary()
        updateCloudPrefVisibility()
        refreshEmergencyPref()
        refreshLockUi()
        applyRestrictedAccountDataState()

        // Live updates from SwitchModeStore
        SwitchModeStore.ensureInit(ctx)
        lifecycleScope.launch {
            SwitchModeStore.enabledFlow.collect {
                val enabledNow = SwitchModeStore.isEnabled(ctx)
                findPreference<SwitchPreferenceCompat>("pref_switch_mode")?.isChecked = enabledNow
                findPreference<SwitchPreferenceCompat>("pref_switchly_enabled")?.isChecked = enabledNow
                refreshLockUi()
            }
        }

        // Auth listener for Firebase account builds only.
        if (BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            authListener = FirebaseAuth.AuthStateListener {
                if (isAdded) {
                    updateGooglePrefSummary()
                    updateCloudPrefVisibility()
                }
            }
            // Firebase can be missing during dev builds (e.g. no google-services.json).
            // Don't crash Settings screen if Firebase isn't initialized.
            authListener?.let { listener ->
                runCatching { FirebaseAuth.getInstance().addAuthStateListener(listener) }
            }
        }
    }

    // Navigate nested PreferenceScreens (Help/Account/...). Some setups do not automatically open nested screens, so we handle it explicitly.
    private fun openNestedPreferenceScreen(screenKey: String): Boolean {
        if (screenKey.isBlank()) {
            return false
        }

        // Some AndroidX/device combinations can dispatch both callbacks for one tap.
        // Debounce identical navigation to avoid double back-stack entries ("back needs 2 taps").
        val now = SystemClock.uptimeMillis()
        if (lastNestedNavKey == screenKey && (now - lastNestedNavAtMs) < 650L) {
            return true
        }
        lastNestedNavKey = screenKey
        lastNestedNavAtMs = now

        val currentRoot = arguments?.getString(ARG_PREFERENCE_ROOT)
        if (currentRoot == screenKey) {
            return true
        }

        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_PREFERENCE_ROOT, screenKey)
            }
        }

        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(screenKey)
            .commit()

        return true
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is PreferenceScreen && preference.key?.startsWith("screen_") == true) {
            return openNestedPreferenceScreen(preference.key.orEmpty())
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onNavigateToScreen(preferenceScreen: PreferenceScreen) {
        // Some devices/androidx versions won't trigger onPreferenceTreeClick for PreferenceScreen, but will call onNavigateToScreen. Handle both to be safe.
        if (preferenceScreen.key?.startsWith("screen_") == true) {
            openNestedPreferenceScreen(preferenceScreen.key.orEmpty())
        } else {
            super.onNavigateToScreen(preferenceScreen)
        }
    }

    // Hide/show cloud backup actions depending on login state. File backup stays available offline.
    private fun updateCloudPrefVisibility() {
        val loggedIn = BuildConfig.SWITCHLY_FIREBASE_ENABLED && at.saltyy.switchly.auth.Auth.uid() != null
        findPreference<PreferenceScreen>("screen_backup")?.isVisible = true
        findPreference<Preference>("pref_cloud_backup")?.isVisible = loggedIn
        findPreference<Preference>("pref_cloud_restore")?.isVisible = loggedIn
        findPreference<Preference>("pref_cloud_delete_backups")?.isVisible = loggedIn
        findPreference<Preference>("pref_file_backup")?.isVisible = true
        findPreference<Preference>("pref_file_restore")?.isVisible = true
    }

    private fun tintCategoryViewsInList() {
        val list = listView ?: return
        if (categoryTitles.isEmpty()) {
            return
        }
        val accent = getCurrentAccentColor(requireContext())

        fun tintInView(v: View) {
            if (v is TextView) {
                val text = v.text?.toString() ?: return
                if (categoryTitles.contains(text)) v.setTextColor(accent)
            } else if (v is ImageView) {
                val d = v.drawable ?: return
                val wrapped = DrawableCompat.wrap(d).mutate()
                DrawableCompat.setTint(wrapped, accent)
                v.setImageDrawable(wrapped)
            } else if (v is ViewGroup) {
                for (i in 0 until v.childCount) tintInView(v.getChildAt(i))
            }
        }

        for (i in 0 until list.childCount) tintInView(list.getChildAt(i))

        list.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View?, child: View?) {
                if (child != null) tintInView(child)
            }

            override fun onChildViewRemoved(parent: View?, child: View?) {}
        })
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val list = listView ?: return
        // The nested SettingsActivity container already provides the same horizontal padding as the root cards.
        // Do not add a second 16dp inset here, otherwise nested pages such as Appearance and Help/About look zoomed out/narrower than the main Settings screen.
        val padBottom = list.paddingBottom
        list.setPadding(0, 0, 0, padBottom)
        list.clipToPadding = false
        tintCategoryViewsInList()
        CustomAccentApplier.applyIfNeeded(requireActivity())
    }

    // Next schedule indicator
    private fun updateNextScheduleIndicator() {
        val ctx = requireContext()
        val pref = findPreference<Preference>("pref_schedules_next") ?: return

        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val show = sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        pref.isVisible = show
        if (!show) {
            return
        }

        if (!AutomationModeStore.isScheduleAllowed(ctx)) {
            pref.summary = getString(R.string.schedules_next_inactive_control_mode)
            return
        }

        val nextMillis = SchedulePlanner.getNextBoundaryMillis(ctx)
        if (nextMillis <= 0L) {
            pref.summary = getString(R.string.schedules_next_none)
        } else {
            val text = formatLocalScheduleBoundaryTime(ctx, nextMillis)
            pref.summary = getString(R.string.schedules_next_at, text)
        }
    }

    private fun formatLocalScheduleBoundaryTime(context: Context, timeMillis: Long): String {
        val cal = Calendar.getInstance().apply { this.timeInMillis = timeMillis }
        val minutesOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return TimeFormatPrefs.formatMinutesOfDay(context, minutesOfDay)
    }

    override fun onResume() {
        super.onResume()
        (activity as? SettingsActivity)?.setToolbarTitle(currentScreenTitle())
        refreshLockUi()
        refreshEmergencyPref()
        updateTimeFormatSummary(findPreference("pref_time_format"))
        updateNextScheduleIndicator()
        updateGooglePrefSummary()
        updateCloudPrefVisibility()
        applyRestrictedAccountDataState()
        refreshBlockedInboxPreferenceState()
        refreshHomeModeAppearancePrefs()
        CustomAccentApplier.applyIfNeeded(requireActivity())
        tintCategories()
        ensureDeveloperInfoIconAccent()

        val hasNextSchedulePref = findPreference<Preference>("pref_schedules_next") != null
        if (hasNextSchedulePref && nextChangedReceiver == null) {
            nextChangedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == SchedulePlanner.ACTION_NEXT_CHANGED) {
                        updateNextScheduleIndicator()
                    }
                }
            }

            val filter = IntentFilter(SchedulePlanner.ACTION_NEXT_CHANGED)
            ContextCompat.registerReceiver(
                requireContext(),
                nextChangedReceiver!!,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onPause() {
        super.onPause()
        nextChangedReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        nextChangedReceiver = null
    }

    override fun onDestroyView() {
        if (BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            authListener?.let { listener ->
                runCatching { FirebaseAuth.getInstance().removeAuthStateListener(listener) }
            }
        }
        authListener = null

        nextChangedReceiver?.let { runCatching { requireContext().unregisterReceiver(it) } }
        nextChangedReceiver = null

        super.onDestroyView()
    }

    // Language
    private fun updateLanguageSummary(pref: Preference?) {
        pref ?: return
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_language", "system") ?: "system"

        val entries = resources.getStringArray(R.array.pref_language_entries)
        val values = resources.getStringArray(R.array.pref_language_values)

        val label = values.indexOf(current).let { idx ->
            if (idx in entries.indices) entries[idx] else entries.firstOrNull()
        } ?: ""

        pref.summary = label
    }

    // Appearance summaries
    private fun updateThemeModeSummary(pref: Preference?) {
        pref ?: return
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_theme_mode", "system") ?: "system"

        val label = when (current) {
            "light" -> getString(R.string.pref_theme_mode_light)
            "dark" -> getString(R.string.pref_theme_mode_dark)
            else -> getString(R.string.pref_theme_mode_system)
        }
        pref.summary = label
    }

    private fun updateTimeFormatSummary(pref: Preference?) {
        pref ?: return
        pref.summary = when (TimeFormatPrefs.getMode(requireContext())) {
            "12h" -> getString(R.string.pref_time_format_12h)
            "24h" -> getString(R.string.pref_time_format_24h)
            else -> getString(R.string.pref_time_format_system)
        }
    }

    private fun updateThemeColorSummary(pref: Preference?) {
        pref ?: return
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_accent", "default") ?: "default"

        if (current == "custom") {
            val hex = prefs.getString("pref_accent_custom", "").orEmpty()
            pref.summary = if (hex.isNotBlank()) {
                getString(R.string.pref_theme_color_custom_fmt, hex)
            } else {
                getString(R.string.pref_accent_custom_title)
            }
            return
        }

        val entries = resources.getStringArray(R.array.pref_accent_entries)
        val values = resources.getStringArray(R.array.pref_accent_values)
        val label = values.indexOf(current).let { i -> if (i in entries.indices) entries[i] else entries.firstOrNull() }
            ?: ""
        pref.summary = label
    }

    private fun showLanguageDialog() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val pref = findPreference<Preference>("pref_language")
        val current = prefs.getString("pref_language", "system") ?: "system"

        val entries = resources.getStringArray(R.array.pref_language_entries)
        val values = resources.getStringArray(R.array.pref_language_values)

        val checked = values.indexOf(current).let { idx -> if (idx >= 0) idx else 0 }

        val summaries = arrayOf(
            getString(R.string.pref_language_system_summary),
            getString(R.string.pref_language_en_summary),
            getString(R.string.pref_language_de_summary)
        )

        showSingleSelectCheckboxDialog(
            title = getString(R.string.pref_language_title),
            entries = entries,
            checkedIndex = checked,
            summaries = summaries,
            iconDrawables = arrayOf<Drawable?>(
                badgeDrawable("AUTO"),
                badgeDrawable("EN"),
                badgeDrawable("DE")
            ),
        ) { which, dialog ->
            val selected = values[which]
            prefs.edit { putString("pref_language", selected) }
            LocaleHelper.setLanguage(requireActivity().application, selected)
            updateLanguageSummary(pref)
            restartAppTask()
            dialog.dismiss()
        }
    }

    // Theme Dialog (Mode + Color)
    private fun showThemeModeDialog() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val current = prefs.getString("pref_theme_mode", "system") ?: "system"

        val entries = arrayOf(
            getString(R.string.pref_theme_mode_system),
            getString(R.string.pref_theme_mode_light),
            getString(R.string.pref_theme_mode_dark)
        )
        val values = arrayOf("system", "light", "dark")
        val checked = values.indexOf(current).coerceAtLeast(0)

        val summaries = arrayOf(
            getString(R.string.pref_theme_mode_system_summary),
            getString(R.string.pref_theme_mode_light_summary),
            getString(R.string.pref_theme_mode_dark_summary)
        )

        showSingleSelectCheckboxDialog(
            title = getString(R.string.pref_theme_mode_title),
            entries = entries,
            checkedIndex = checked,
            summaries = summaries,
            iconRes = arrayOf<Int?>(
                R.drawable.tune_24,
                R.drawable.light_mode_24,
                R.drawable.dark_mode_24
            ),
        ) { which, dialog ->
            val selected = values[which]
            prefs.edit { putString("pref_theme_mode", selected) }
            updateThemeModeSummary(findPreference("pref_theme_mode"))

            when (selected) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }

            dialog.dismiss()
        }
    }

    private fun showTimeFormatDialog() {
        val ctx = requireContext()
        val current = TimeFormatPrefs.getMode(ctx)
        val entries = arrayOf(
            getString(R.string.pref_time_format_system),
            getString(R.string.pref_time_format_24h),
            getString(R.string.pref_time_format_12h)
        )
        val values = arrayOf("system", "24h", "12h")
        val checked = values.indexOf(current).coerceAtLeast(0)

        val summaries = arrayOf(
            getString(R.string.pref_time_format_system_summary),
            getString(R.string.pref_time_format_24h_summary),
            getString(R.string.pref_time_format_12h_summary)
        )

        showSingleSelectCheckboxDialog(
            title = getString(R.string.pref_time_format_title),
            entries = entries,
            checkedIndex = checked,
            summaries = summaries,
            iconDrawables = arrayOf<Drawable?>(
                badgeDrawable("AUTO"),
                badgeDrawable("24"),
                badgeDrawable("12")
            ),
        ) { which, dialog ->
            TimeFormatPrefs.setMode(ctx, values[which])
            updateTimeFormatSummary(findPreference("pref_time_format"))
            dialog.dismiss()
        }
    }

    private fun showThemeColorDialog() {
        val ctx = requireContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val isPremium = PremiumManager.isPremium(ctx)
        val current = prefs.getString("pref_accent", "default") ?: "default"

        val allEntries = resources.getStringArray(R.array.pref_accent_entries)
        val allValues = resources.getStringArray(R.array.pref_accent_values)

        val freeCount = minOf(5, allEntries.size, allValues.size)

        val entries: Array<String>
        val values: Array<String>

        if (isPremium) {
            entries = allEntries + getString(R.string.pref_accent_custom)
            values = allValues + "custom"
        } else {
            entries = allEntries.copyOfRange(0, freeCount)
            values = allValues.copyOfRange(0, freeCount)
        }

        val checked = values.indexOf(current).let { idx -> if (idx >= 0) idx else 0 }

        val summaries = values.map { value ->
            when (value) {
                "default" -> getString(R.string.pref_accent_default_summary)
                "blue" -> getString(R.string.pref_accent_blue_summary)
                "orange" -> getString(R.string.pref_accent_orange_summary)
                "purple" -> getString(R.string.pref_accent_purple_summary)
                "pink" -> getString(R.string.pref_accent_pink_summary)
                "teal" -> getString(R.string.pref_accent_teal_summary)
                "red" -> getString(R.string.pref_accent_red_summary)
                "amber" -> getString(R.string.pref_accent_amber_summary)
                "gray" -> getString(R.string.pref_accent_gray_summary)
                "custom" -> getString(R.string.pref_accent_custom_summary)
                else -> getString(R.string.pref_theme_color_summary)
            }
        }.toTypedArray()

        showSingleSelectCheckboxDialog(
            title = getString(R.string.pref_theme_color_title),
            dialogSubtitle = getString(R.string.pref_theme_color_summary),
            entries = entries,
            checkedIndex = checked,
            summaries = summaries,
            iconDrawables = values.map { colorPreviewDrawable(accentColorForValue(ctx, it)) as Drawable? }.toTypedArray(),
        ) { which, dialog ->
            val selected = values[which]
            if (selected == "custom") {
                dialog.dismiss()
                showCustomColorPicker()
            } else {
                prefs.edit { putString("pref_accent", selected) }
                updateThemeColorSummary(findPreference("pref_theme_color"))
                dialog.dismiss()
                restartAppTask()
            }
        }
    }

    /**
     * Shared single-select card dialog.
     * Selection is shown with accent border/background only — no radio/checkmark bubbles.
     */
    private fun showSingleSelectCheckboxDialog(
        title: String,
        dialogSubtitle: String? = null,
        entries: Array<String>,
        checkedIndex: Int,
        summaries: Array<String>? = null,
        iconRes: Array<Int?>? = null,
        iconDrawables: Array<Drawable?>? = null,
        onSelected: (index: Int, dialog: AlertDialog) -> Unit,
    ) {
        lateinit var dialog: AlertDialog
        dialog = requireContext().showSwitchlyOptionDialog(
            title = title,
            subtitle = dialogSubtitle,
            options = entries.mapIndexed { index, label ->
                SwitchlyDialogOption(
                    title = label,
                    summary = summaries?.getOrNull(index),
                    iconRes = iconRes?.getOrNull(index),
                    iconDrawable = iconDrawables?.getOrNull(index),
                    selected = index == checkedIndex
                )
            },
            confirmSelection = true
        ) { which ->
            onSelected(which, dialog)
        }
    }

    private fun colorPreviewDrawable(color: Int): Drawable {
        val outline = MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOutline, 0x33000000)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(dp(2), outline)
            setSize(dp(28), dp(28))
        }
    }

    private fun badgeDrawable(text: String): Drawable {
        val accent = getCurrentAccentColor(requireContext())
        val onAccent = if (androidx.core.graphics.ColorUtils.calculateContrast(Color.BLACK, accent) >=
            androidx.core.graphics.ColorUtils.calculateContrast(Color.WHITE, accent)
        ) Color.BLACK else Color.WHITE
        return TextBadgeDrawable(text, accent, onAccent)
    }

    private fun accentColorForValue(ctx: Context, value: String): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        return when (value) {
            "blue" -> ContextCompat.getColor(ctx, R.color.accent_blue)
            "orange" -> ContextCompat.getColor(ctx, R.color.accent_orange)
            "purple" -> ContextCompat.getColor(ctx, R.color.accent_purple)
            "pink" -> ContextCompat.getColor(ctx, R.color.accent_pink)
            "teal" -> ContextCompat.getColor(ctx, R.color.accent_teal)
            "red" -> ContextCompat.getColor(ctx, R.color.accent_red)
            "amber" -> ContextCompat.getColor(ctx, R.color.accent_amber)
            "gray" -> ContextCompat.getColor(ctx, R.color.accent_gray)
            "custom" -> runCatching {
                (prefs.getString("pref_accent_custom", "#2E8B57") ?: "#2E8B57").toColorInt()
            }.getOrDefault(ContextCompat.getColor(ctx, R.color.accent_green))
            else -> ContextCompat.getColor(ctx, R.color.accent_green)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private class TextBadgeDrawable(
        private val text: String,
        private val backgroundColor: Int,
        private val foregroundColor: Int
    ) : Drawable() {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = backgroundColor
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = foregroundColor
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val radius = b.width().coerceAtMost(b.height()) / 2f
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), radius, bgPaint)
            textPaint.textSize = b.height() * if (text.length > 2) 0.28f else 0.42f
            val y = b.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, b.exactCenterX(), y, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            textPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }

        @Deprecated("Required by the Android Drawable API")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class SingleSelectCheckboxAdapter(
        private val entries: List<String>,
        initialSelected: Int,
        private val onSelected: (Int) -> Unit,
    ) : RecyclerView.Adapter<SingleSelectCheckboxAdapter.VH>() {

        private var selectedIndex: Int = initialSelected.coerceIn(0, (entries.size - 1).coerceAtLeast(0))

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_single_select_checkbox, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.title.text = entries[position]

            // Ensure checkbox tint is always the current accent (especially important in custom-accent mode where OEM/framework defaults can show up again when views are rebound after scrolling).
            val accent = AccentColor.getAccentColorInt(holder.itemView.context)
            val unchecked = (accent and 0x00FFFFFF) or (0x8C shl 24) // ~55% alpha
            holder.cb.buttonTintList = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(
                    accent,
                    unchecked
                )
            )

            // Avoid recursive click loops: row and checkbox both call select(position), but do NOT call performClick().
            holder.cb.setOnCheckedChangeListener(null)
            holder.cb.isChecked = position == selectedIndex
            // Checkbox is visual-only; the entire row handles clicks for stable behaviour across OEMs.
            holder.cb.isClickable = false
            holder.cb.isFocusable = false

            fun select() {
                // Selecting an entry immediately applies + closes the dialog.
                // Avoid any adapter update churn here (can race with dialog dismissal on some OEMs).
                // Do not treat "position" as stable; view holders can be rebound.
                val p = holder.bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) {
                    return
                }
                selectedIndex = p
                onSelected(p)
            }

            holder.itemView.setOnClickListener { select() }
        }

        override fun getItemCount(): Int = entries.size

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val title: TextView = itemView.findViewById(R.id.title)
            val cb: MaterialRadioButton = itemView.findViewById(R.id.radio)
        }
    }

    private fun showCustomColorPicker() {
        val ctx = requireContext()
        if (!PremiumManager.isPremium(ctx)) {
            Toast.makeText(ctx, R.string.premium_required_for_theme, Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val initialHex = prefs.getString("pref_accent_custom", "#2E8B57") ?: "#2E8B57"
        var color = try { initialHex.toColorInt() } catch (_: IllegalArgumentException) { "#2E8B57".toColorInt() }

        val view = layoutInflater.inflate(R.layout.dialog_color_picker, FrameLayout(requireContext()), false)
        val preview = view.findViewById<View>(R.id.colorPreview)
        val sliderR = view.findViewById<SeekBar>(R.id.sliderR)
        val sliderG = view.findViewById<SeekBar>(R.id.sliderG)
        val sliderB = view.findViewById<SeekBar>(R.id.sliderB)

        fun updatePreviewFromColor() { preview.setBackgroundColor(color) }
        fun updateColorFromSliders() {
            color = Color.rgb(sliderR.progress, sliderG.progress, sliderB.progress)
            updatePreviewFromColor()
        }

        sliderR.max = 255; sliderG.max = 255; sliderB.max = 255
        sliderR.progress = Color.red(color)
        sliderG.progress = Color.green(color)
        sliderB.progress = Color.blue(color)

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { updateColorFromSliders() }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sliderR.setOnSeekBarChangeListener(listener)
        sliderG.setOnSeekBarChangeListener(listener)
        sliderB.setOnSeekBarChangeListener(listener)
        updatePreviewFromColor()

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_accent_custom_title))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val hex = String.format("#%08X", color)
                prefs.edit {
                    putString("pref_accent", "custom")
                    putString("pref_accent_custom", hex)
                }
                updateThemeColorSummary(findPreference("pref_theme_color"))
                restartAppTask()
            }
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    // Account (Google + email/password)
    private fun updateGooglePrefSummary() {
        val pref = findPreference<Preference>("pref_google_account") ?: return
        val ctx = requireContext()
        pref.isVisible = BuildConfig.SWITCHLY_FIREBASE_ENABLED
        if (!BuildConfig.SWITCHLY_FIREBASE_ENABLED) {
            return
        }
        val user = runCatching { FirebaseAuth.getInstance().currentUser }.getOrNull()

        val base = if (user != null) {
            val email = user.email?.trim().orEmpty()
            if (email.isNotEmpty()) getString(R.string.settings_account_logged_in_as, email)
            else getString(R.string.settings_account_logged_in)
        } else {
            getString(R.string.settings_account_logged_out)
        }

        if (user == null) {
            pref.summary = base
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val lastBackup = prefs.getLong("pref_last_backup_epoch_ms", -1L)
        if (lastBackup <= 0L) {
            pref.summary = base
            return
        }

        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val formatted = df.format(Date(lastBackup))
        pref.summary = "$base\n" + getString(R.string.settings_last_backup, formatted)
    }

    private fun showGoogleAccountDialog() {
        val ctx = requireContext()
        val loggedIn = at.saltyy.switchly.auth.Auth.uid() != null

        if (!loggedIn) {
            val items = listOf(
                IconActionItem(getString(R.string.settings_account_action_sign_in), R.drawable.login_24),
                IconActionItem(getString(R.string.settings_account_action_create), R.drawable.account_box_24)
            )

            ctx.showSwitchlyOptionDialog(
                title = getString(R.string.settings_account_dialog_title),
                options = listOf(
                    SwitchlyDialogOption(
                        title = getString(R.string.settings_account_action_sign_in),
                        summary = getString(R.string.settings_account_sign_in_summary),
                        iconRes = R.drawable.login_24
                    ),
                    SwitchlyDialogOption(
                        title = getString(R.string.settings_account_action_create),
                        summary = getString(R.string.settings_account_create_summary),
                        iconRes = R.drawable.account_box_24
                    )
                ),
                compact = false,
                showCancelButton = false,
                widthFraction = 0.94f
            ) { which ->
                when (which) {
                    0 -> showAccountSignInDialog()
                    1 -> showAccountCreateDialog()
                }
            }
            return
        }

        val restricted = isRestrictedSettingsAccess()
        val items = buildList {
            add(IconActionItem(getString(R.string.sign_out), R.drawable.logout_24))
            if (!restricted) {
                add(IconActionItem(getString(R.string.settings_account_action_delete), R.drawable.delete_24))
            }
        }

        ctx.showSwitchlyOptionDialog(
            title = getString(R.string.settings_account_dialog_title),
            options = items.map {
                SwitchlyDialogOption(
                    title = it.title,
                    iconRes = it.iconRes,
                    destructive = it.iconRes == R.drawable.delete_24
                )
            }
        ) { which ->
            when (which) {
                0 -> confirmAction(
                    title = getString(R.string.sign_out),
                    message = getString(R.string.settings_confirm_sign_out_message),
                    positiveText = getString(R.string.sign_out),
                ) {
                    at.saltyy.switchly.auth.Auth.signOut(ctx) {
                        PreferenceManager.getDefaultSharedPreferences(ctx).edit {
                            remove("pref_last_backup_epoch_ms")
                        }
                        updateGooglePrefSummary()
                        updateCloudPrefVisibility()
                        Toast.makeText(ctx, getString(R.string.settings_signed_out), Toast.LENGTH_SHORT).show()
                    }
                }

                1 -> {
                    if (isRestrictedSettingsAccess()) {
                        Toast.makeText(ctx, R.string.settings_restricted_action_unavailable, Toast.LENGTH_SHORT).show()
                    } else {
                        confirmAction(
                            title = getString(R.string.settings_account_delete_confirm_title),
                            message = getString(R.string.settings_account_delete_confirm_message),
                            positiveText = getString(R.string.delete),
                        ) {
                            AccountDeletion.deleteAccount(requireActivity())
                        }
                    }
                }
            }
        }
    }

    private fun showAccountSignInDialog() {
        val ctx = requireContext()
        val googleAvailable = at.saltyy.switchly.auth.AuthRuntime.isGoogleSignInAvailable(ctx)
        val items = buildList {
            if (googleAvailable) add(IconActionItem(getString(R.string.settings_account_continue_google), R.drawable.google_24, tintIcon = false))
            add(IconActionItem(getString(R.string.settings_account_sign_in_email), R.drawable.mail_24))
        }

        ctx.showSwitchlyOptionDialog(
            title = getString(R.string.settings_account_action_sign_in),
            options = items.map { item ->
                val isGoogle = item.iconRes == R.drawable.google_24
                SwitchlyDialogOption(
                    title = item.title,
                    summary = getString(if (isGoogle) R.string.settings_account_google_summary else R.string.settings_account_email_sign_in_summary),
                    iconRes = if (item.tintIcon) item.iconRes else null,
                    iconDrawable = if (item.tintIcon) null else ContextCompat.getDrawable(ctx, item.iconRes)
                )
            },
            compact = false,
            showCancelButton = false,
            widthFraction = 0.94f
        ) { which ->
            when {
                googleAvailable && which == 0 -> startGoogleAccountSignIn()
                else -> showEmailPasswordDialog(createAccount = false)
            }
        }
    }

    private fun showAccountCreateDialog() {
        val ctx = requireContext()
        val googleAvailable = at.saltyy.switchly.auth.AuthRuntime.isGoogleSignInAvailable(ctx)
        val items = buildList {
            if (googleAvailable) add(IconActionItem(getString(R.string.settings_account_continue_google), R.drawable.google_24, tintIcon = false))
            add(IconActionItem(getString(R.string.settings_account_create_email), R.drawable.mail_24))
        }

        ctx.showSwitchlyOptionDialog(
            title = getString(R.string.settings_account_action_create),
            options = items.map { item ->
                val isGoogle = item.iconRes == R.drawable.google_24
                SwitchlyDialogOption(
                    title = item.title,
                    summary = getString(if (isGoogle) R.string.settings_account_google_summary else R.string.settings_account_email_create_summary),
                    iconRes = if (item.tintIcon) item.iconRes else null,
                    iconDrawable = if (item.tintIcon) null else ContextCompat.getDrawable(ctx, item.iconRes)
                )
            },
            compact = false,
            showCancelButton = false,
            widthFraction = 0.94f
        ) { which ->
            when {
                googleAvailable && which == 0 -> startGoogleAccountSignIn()
                else -> showEmailPasswordDialog(createAccount = true)
            }
        }
    }

    private fun startGoogleAccountSignIn() {
        findPreference<Preference>("pref_google_account")?.summary =
            getString(R.string.settings_account_signing_in)
        at.saltyy.switchly.auth.Auth.startSignIn(requireActivity()) { _, _ ->
            if (!isAdded) return@startSignIn
            updateGooglePrefSummary()
            updateCloudPrefVisibility()
        }
    }

    private fun showEmailPasswordDialog(createAccount: Boolean) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        val margin = (24 * density).toInt()
        val spacing = (12 * density).toInt()

        val emailInput = EditText(ctx).apply {
            hint = getString(R.string.settings_account_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            backgroundTintList = AccentColor.getActiveColor(ctx)
        }

        val passwordInput = EditText(ctx).apply {
            hint = getString(R.string.settings_account_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            backgroundTintList = AccentColor.getActiveColor(ctx)
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, 0, margin, 0)
            addView(
                emailInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                passwordInput,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = spacing
                }
            )
        }

        val titleRes = if (createAccount) {
            R.string.settings_account_action_create
        } else {
            R.string.settings_account_action_sign_in
        }
        val positiveRes = if (createAccount) {
            R.string.settings_account_create_email
        } else {
            R.string.settings_account_sign_in_email
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle(getString(titleRes))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(positiveRes), null)

        if (!createAccount) {
            builder.setNeutralButton(getString(R.string.settings_account_action_reset_password), null)
        }

        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            if (!createAccount) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                    dialog.dismiss()
                    showPasswordResetDialog()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val email = emailInput.text?.toString()?.trim().orEmpty()
                val password = passwordInput.text?.toString().orEmpty()

                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(ctx, getString(R.string.settings_account_invalid_email), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (password.isBlank()) {
                    Toast.makeText(ctx, getString(R.string.settings_account_password_required), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (createAccount && password.length < 8) {
                    Toast.makeText(ctx, resources.getQuantityString(R.plurals.settings_account_password_min_length, 8, 8), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positive?.isEnabled = false

                val onFinished: (Boolean, String?) -> Unit = { success, _ ->
                    positive?.isEnabled = true
                    if (success && isAdded) {
                        updateGooglePrefSummary()
                        updateCloudPrefVisibility()
                        dialog.dismiss()
                    }
                }

                if (createAccount) {
                    at.saltyy.switchly.auth.Auth.createAccountWithEmail(ctx, email, password, onFinished)
                } else {
                    at.saltyy.switchly.auth.Auth.signInWithEmail(ctx, email, password, onFinished)
                }
            }
        }
        dialog.show()
    }

    private fun showPasswordResetDialog() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = getString(R.string.settings_account_email_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            backgroundTintList = AccentColor.getActiveColor(ctx)
        }

        val container = FrameLayout(ctx).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.settings_account_action_reset_password))
            .setMessage(getString(R.string.settings_account_reset_password_message))
            .setView(container)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.settings_account_send_reset_email), null)
            .create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val email = input.text?.toString()?.trim().orEmpty()
                if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    Toast.makeText(ctx, getString(R.string.settings_account_invalid_email), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positive?.isEnabled = false
                at.saltyy.switchly.auth.Auth.sendPasswordResetEmail(ctx, email) { success, _ ->
                    if (!isAdded) return@sendPasswordResetEmail
                    positive?.isEnabled = true
                    if (success) {
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun backupConfirmMessage(
        selection: BackupSelection,
        fullMessageRes: Int,
        includedOnlyMessageRes: Int,
    ): String {
        return if (selection.hasExcludedCategories()) {
            getString(fullMessageRes, selection.includedNames(), selection.excludedNames())
        } else {
            getString(includedOnlyMessageRes, selection.includedNames())
        }
    }

    private fun showBackupSelectionFlow(onSelected: (BackupSelection) -> Unit) {
        val ctx = context ?: return
        val presets = listOf(
            Triple(getString(R.string.backup_preset_full), getString(R.string.backup_preset_full_summary), BackupSelection.full()),
            Triple(getString(R.string.backup_preset_privacy), getString(R.string.backup_preset_privacy_summary), BackupSelection.privacyFocused()),
            Triple(getString(R.string.backup_preset_profiles_only), getString(R.string.backup_preset_profiles_only_summary), BackupSelection.profilesOnly()),
            Triple(getString(R.string.backup_preset_manual_custom), getString(R.string.backup_preset_manual_custom_summary), BackupSelectionStore.load(ctx)),
        )

        ctx.showSwitchlyOptionDialog(
            title = getString(R.string.backup_select_preset_title),
            options = presets.mapIndexed { index, preset ->
                SwitchlyDialogOption(
                    title = preset.first,
                    summary = preset.second,
                    iconRes = when (index) {
                        0 -> R.drawable.cloud_upload_24
                        1 -> R.drawable.security_24
                        2 -> R.drawable.switch_account_24
                        else -> R.drawable.tune_24
                    },
                )
            },
            compact = false,
            showCancelButton = true,
            widthFraction = 0.94f,
        ) { index ->
            showBackupCategoryDialog(presets[index].third, onSelected)
        }
    }

    private fun showBackupCategoryDialog(initial: BackupSelection, onSelected: (BackupSelection) -> Unit) {
        val ctx = context ?: return
        val categories = BackupCategory.values()
        val checked = categories.map { it.id in initial.categoryIds }.toBooleanArray()
        val options = categories.map { category ->
            val suffix = if (category.sensitive) getString(R.string.backup_category_sensitive_suffix) else ""
            SwitchlyDialogOption(
                title = "${category.displayName}$suffix",
                summary = category.description,
                iconRes = backupCategoryIconRes(category)
            )
        }

        ctx.showSwitchlyMultiChoiceDialog(
            title = getString(R.string.backup_select_categories_title),
            options = options,
            checked = checked,
            positiveTextRes = R.string.backup_create_with_selection,
            compact = false,
            widthFraction = 0.94f,
        ) { states ->
            val selected = categories
                .filterIndexed { index, _ -> states.getOrNull(index) == true }
                .map { it.id }
                .toSet()
            val selection = BackupSelection.fromIds(selected)
            if (selection.categoryIds.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.backup_select_at_least_one), Toast.LENGTH_SHORT).show()
                return@showSwitchlyMultiChoiceDialog
            }
            BackupSelectionStore.save(ctx, selection)
            onSelected(selection)
        }
    }

    private fun backupCategoryIconRes(category: BackupCategory): Int = when (category) {
        BackupCategory.PROFILES -> R.drawable.switch_account_24
        BackupCategory.BLOCKED_APPS -> R.drawable.apps_24
        BackupCategory.WEBSITE_RULES,
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

    private fun defaultBackupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "switchly-backup-$stamp.json"
    }

    private fun writeBackupFile(uri: Uri) {
        val activeCtx = context ?: return
        val selection = pendingFileBackupSelection ?: BackupSelectionStore.load(activeCtx)
        pendingFileBackupSelection = null
        val loadingDialog = showProgressDialog(
            activeCtx,
            R.string.settings_confirm_file_backup_title,
            R.string.file_backup_loading
        )
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileBackupRuntime.writeLocalBackupToUri(activeCtx, uri, selection)
            }
            if (!isAdded) return@launch
            if (loadingDialog.isShowing) loadingDialog.dismiss()
            val msg = result.fold(
                onSuccess = {
                    PreferenceManager.getDefaultSharedPreferences(activeCtx).edit {
                        putLong("pref_last_backup_epoch_ms", System.currentTimeMillis())
                    }
                    updateGooglePrefSummary()
                    getString(R.string.file_backup_ok)
                },
                onFailure = { e ->
                    getString(R.string.file_backup_error_fmt, e.localizedMessage ?: getString(R.string.error_unknown))
                }
            )
            Toast.makeText(activeCtx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreBackupFile(uri: Uri) {
        val activeCtx = context ?: return
        val loadingDialog = showProgressDialog(
            activeCtx,
            R.string.settings_confirm_file_restore_title,
            R.string.file_restore_loading,
        )
        lifecycleScope.launch {
            val payloadResult = withContext(Dispatchers.IO) {
                FileBackupRuntime.readBackupPayloadFromUri(activeCtx, uri)
            }
            if (loadingDialog.isShowing) {
                loadingDialog.dismiss()
            }
            if (!isAdded) {
                return@launch
            }
            payloadResult
                .onFailure { error ->
                    Toast.makeText(
                        activeCtx,
                        getString(
                            R.string.file_restore_error_fmt,
                            error.localizedMessage ?: getString(R.string.error_unknown),
                        ),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onSuccess { payload ->
                    showRestoreSelectionDialog(activeCtx, payload) { selectedPayload ->
                        val restoreDialog = showProgressDialog(
                            activeCtx,
                            R.string.settings_confirm_file_restore_title,
                            R.string.restore_applying,
                        )
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                FileBackupRuntime.restoreBackupPayload(activeCtx, selectedPayload)
                            }
                            if (restoreDialog.isShowing) {
                                restoreDialog.dismiss()
                            }
                            if (!isAdded) {
                                return@launch
                            }
                            val message = result.fold(
                                onSuccess = { getString(R.string.file_restore_ok_restart) },
                                onFailure = { error ->
                                    getString(
                                        R.string.file_restore_error_fmt,
                                        error.localizedMessage ?: getString(R.string.error_unknown),
                                    )
                                },
                            )
                            Toast.makeText(activeCtx, message, Toast.LENGTH_SHORT).show()
                            if (result.isSuccess) {
                                restartAppTask()
                            }
                        }
                    }
                }
        }
    }

    private fun startRestoreFlowWithChoice() {
        val initialCtx = context ?: return
        val loadingDialog = AlertDialog.Builder(initialCtx)
            .setTitle(getString(R.string.pref_cloud_restore_title))
            .setMessage(getString(R.string.cloud_restore_loading))
            .setCancelable(false)
            .create()

        loadingDialog.setOnShowListener { loadingDialog.styleSwitchlyDialogButtons() }
        loadingDialog.show()

        CloudSyncRuntime.listBackups(initialCtx) { ok, err, backups ->
            val activeCtx = context ?: return@listBackups
            if (!isAdded) return@listBackups
            if (loadingDialog.isShowing) loadingDialog.dismiss()
            if (!ok) {
                Toast.makeText(
                    activeCtx,
                    getString(R.string.cloud_error_fmt, err ?: getString(R.string.error_unknown)),
                    Toast.LENGTH_SHORT
                ).show()
                return@listBackups
            }

            val list = backups ?: emptyList()
            if (list.isEmpty()) {
                CloudSyncRuntime.pullRemoteState(activeCtx) { ok2, err2 ->
                    val restoreCtx = context ?: return@pullRemoteState
                    if (!isAdded) return@pullRemoteState
                    if (ok2) {
                        Toast.makeText(restoreCtx, getString(R.string.cloud_restore_ok_restart), Toast.LENGTH_SHORT).show()
                        restartAppTask()
                    } else {
                        Toast.makeText(
                            restoreCtx,
                            getString(R.string.cloud_error_fmt, err2 ?: getString(R.string.error_unknown)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return@listBackups
            }

            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val labels = list.map { meta -> df.format(Date(meta.createdAt)) }.toTypedArray()

            activeCtx.showSwitchlyOptionDialog(
                title = getString(R.string.settings_restore_choose_title),
                options = labels.map { SwitchlyDialogOption(title = it) }
            ) { which ->
                val meta = list[which]
                CloudSyncRuntime.loadBackupPayload(activeCtx, meta.id) { ok3, err3, payload ->
                    val restoreCtx = context ?: return@loadBackupPayload
                    if (!isAdded) return@loadBackupPayload
                    if (!ok3 || payload == null) {
                        Toast.makeText(
                            restoreCtx,
                            getString(R.string.cloud_error_fmt, err3 ?: getString(R.string.error_unknown)),
                            Toast.LENGTH_SHORT
                        ).show()
                        return@loadBackupPayload
                    }

                    showRestoreSelectionDialog(restoreCtx, payload) { selectedPayload ->
                        val restoreDialog = showProgressDialog(
                            restoreCtx,
                            R.string.settings_confirm_restore_title,
                            R.string.restore_applying,
                        )
                        CloudSyncRuntime.applyBackupPayloadAsync(restoreCtx, selectedPayload) { result ->
                            if (restoreDialog.isShowing) {
                                restoreDialog.dismiss()
                            }
                            if (!isAdded) {
                                return@applyBackupPayloadAsync
                            }
                            result.fold(
                                onSuccess = {
                                    Toast.makeText(
                                        restoreCtx,
                                        getString(R.string.cloud_restore_ok_restart),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    restartAppTask()
                                },
                                onFailure = { error ->
                                    Toast.makeText(
                                        restoreCtx,
                                        getString(
                                            R.string.cloud_error_fmt,
                                            error.localizedMessage ?: getString(R.string.error_unknown),
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun showRestoreSelectionDialog(
        ctx: Context,
        payload: Map<*, *>,
        onConfirm: (Map<*, *>) -> Unit
    ) {
        val includedIds = BackupCategoryFilter.includedCategoryIdsFromPayload(payload)
        val categories = BackupCategory.values()
            .filter { category -> includedIds == null || category.id in includedIds }
            .ifEmpty { BackupCategory.values().toList() }
        val checked = categories.map { true }.toBooleanArray()
        val options = categories.map { category ->
            SwitchlyDialogOption(
                title = category.displayName,
                summary = category.description,
                iconRes = backupCategoryIconRes(category)
            )
        }

        ctx.showSwitchlyMultiChoiceDialog(
            title = getString(R.string.restore_select_categories_title),
            options = options,
            checked = checked,
            positiveTextRes = R.string.settings_confirm_restore_apply,
            compact = false,
            widthFraction = 0.94f,
        ) { states ->
            val selected = categories
                .filterIndexed { index, _ -> states.getOrNull(index) == true }
                .map { it.id }
                .toSet()
            val selection = BackupSelection.fromIds(selected)
            if (selection.categoryIds.isEmpty()) {
                Toast.makeText(ctx, getString(R.string.restore_select_at_least_one), Toast.LENGTH_SHORT).show()
                return@showSwitchlyMultiChoiceDialog
            }
            onConfirm(BackupCategoryFilter.filterPayloadForRestore(payload, selection))
        }
    }

    private fun showProgressDialog(ctx: Context, titleRes: Int, messageRes: Int): AlertDialog {
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(titleRes)
            .setMessage(getString(messageRes))
            .setCancelable(false)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
        return dialog
    }

    private fun confirmAction(title: String, message: String, positiveText: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    // Emergency
    private fun refreshEmergencyPref() {
        val pref = findPreference<Preference>("pref_emergency_unlock") ?: return
        val ctx = requireContext()

        val featureEnabled = EmergencyBypassStore.isFeatureEnabled(ctx)
        pref.isVisible = true

        if (!featureEnabled) {
            pref.isEnabled = true
            pref.summary = getString(R.string.pref_emergency_summary_disabled)
            return
        }

        val active = EmergencyBypassStore.isActive(ctx)
        val paused = EmergencyBypassStore.isPaused(ctx)
        val usedToday = EmergencyBypassStore.hasUsedToday(ctx)
        val remaining = EmergencyBypassStore.minutesRemaining(ctx)

        // Keep this clickable while active/paused so users can pause/resume.
        pref.isEnabled = active || paused || !usedToday
        pref.summary = when {
            paused -> getString(R.string.pref_emergency_summary_paused, remaining)
            active -> getString(R.string.pref_emergency_summary_active_with_time, remaining)
            usedToday -> getString(R.string.pref_emergency_summary_used)
            else -> getString(R.string.pref_emergency_summary)
        }
    }

    private fun refreshLockUi() {
        val ctx = requireContext()
        val enabled = SwitchModeStore.isEnabled(ctx)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(ctx)
        val locked = enabled && requireNfc
        stylePreferenceLocked("pref_switch_mode", locked)
        stylePreferenceLocked("pref_permissions", locked)
    }

    private fun stylePreferenceLocked(key: String, locked: Boolean) {
        val pref = findPreference<Preference>(key) ?: return
        val ctx = requireContext()

        val baseTitle = pref.extras.getString("base_title") ?: pref.title?.toString().orEmpty()
        if (!pref.extras.containsKey("base_title")) pref.extras.putString("base_title", baseTitle)

        val baseSummaryStored = pref.extras.getString("base_summary")
        val baseSummary = baseSummaryStored ?: pref.summary?.toString()
        if (!pref.extras.containsKey("base_summary") && pref.summary != null) {
            pref.extras.putString("base_summary", pref.summary.toString())
        }

        if (!locked) {
            pref.title = baseTitle
            if (baseSummary != null) pref.summary = baseSummary
            return
        }

        val disabledColor = ContextCompat.getColor(ctx, R.color.status_neutral)
        val titleText = "🔒 $baseTitle"
        pref.title = SpannableString(titleText).apply {
            setSpan(ForegroundColorSpan(disabledColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val summaryText = baseSummary ?: ""
        if (summaryText.isNotEmpty()) {
            pref.summary = SpannableString(summaryText).apply {
                setSpan(ForegroundColorSpan(disabledColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // app restart
    private fun restartAppTask() {
        val i = Intent(requireContext(), MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(i)
        requireActivity().finish()
    }

    // accent helper
    private fun getCurrentAccentColor(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = prefs.getString("pref_accent", "default") ?: "default"
        return if (key == "custom") {
            val hex = prefs.getString("pref_accent_custom", "#2E8B57") ?: "#2E8B57"
            try { hex.toColorInt() } catch (_: IllegalArgumentException) { AccentColor.getAccentColorInt(context) }
        } else {
            AccentColor.getAccentColorInt(context)
        }
    }

    private fun tintCategories() {
        val screen = preferenceScreen ?: return
        val accent = getCurrentAccentColor(requireContext())
        tintGroup(screen, accent)
    }

    private fun ensureDeveloperInfoIconAccent() {
        val ctx = context ?: return
        val pref = findPreference<Preference>("pref_about_developer_info") ?: return
        val accent = getCurrentAccentColor(ctx)
        val base = ContextCompat.getDrawable(ctx, R.drawable.info_24)?.mutate() ?: return
        val wrapped = DrawableCompat.wrap(base)
        DrawableCompat.setTint(wrapped, accent)
        pref.icon = wrapped
    }

    private fun tintGroup(group: PreferenceGroup, accent: Int) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceCategory) {
                val title = pref.title?.toString() ?: ""
                if (title.isNotEmpty()) {
                    categoryTitles.add(title)
                    pref.title = SpannableString(title).apply {
                        setSpan(ForegroundColorSpan(accent), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
            } else {
                val title = pref.title?.toString() ?: ""
                if (title.isNotEmpty()) {
                    pref.title = SpannableString(title).apply {
                        setSpan(ForegroundColorSpan(accent), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }

                pref.icon?.let { icon ->
                    val wrapped = DrawableCompat.wrap(icon.mutate())
                    DrawableCompat.setTint(wrapped, accent)
                    pref.icon = wrapped
                }
            }
            if (pref is PreferenceGroup) tintGroup(pref, accent)
        }
    }

    /**
     * Account setting: change the PIN used for Emergency Unlock.
     * Flow:
     * - If no PIN exists yet: directly ask to set one.
     * - If PIN exists: verify current PIN first, then ask to set a new one.
     */
    private fun showChangeEmergencyPinFlow() {
        val ctx = requireContext()
        val storedPin = getStoredEmergencyPin(ctx)

        if (storedPin.isNullOrEmpty()) {
            showSetEmergencyPinDialog {
                Toast.makeText(ctx, R.string.emergency_pin_changed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Verify current PIN first.
        showEnterEmergencyPinDialog(storedPin) {
            showSetEmergencyPinDialog {
                Toast.makeText(ctx, R.string.emergency_pin_changed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openEmergencyUnlockDirect() {
        showEmergencyUnlockWithPin()
    }

    private fun showEmergencyUnlockWithPin() {
        val ctx = requireContext()
        val storedPin = getStoredEmergencyPin(ctx)

        if (!EmergencyBypassStore.isFeatureEnabled(ctx)) {
            val dialog = AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.pref_emergency_title))
                .setMessage(getString(R.string.emergency_disabled_message_controls))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.emergency_open_controls_action)) { _, _ ->
                    startActivity(Intent(ctx, ToggleOptionsActivity::class.java))
                }
                .create()

            dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
            dialog.show()
            return
        }

        val active = EmergencyBypassStore.isActive(ctx)
        val paused = EmergencyBypassStore.isPaused(ctx)
        if (active || paused) {
            showEmergencyManageDialog()
            return
        }

        if (EmergencyBypassStore.hasUsedToday(ctx)) {
            Toast.makeText(ctx, R.string.emergency_used_today, Toast.LENGTH_SHORT).show()
            return
        }

        if (storedPin.isNullOrEmpty()) {
            showSetEmergencyPinDialog { showEmergencyUnlockStartDialog() }
        } else {
            showEnterEmergencyPinDialog(storedPin) { showEmergencyUnlockStartDialog() }
        }
    }

    private fun showEmergencyManageDialog() {
        val ctx = requireContext()
        val active = EmergencyBypassStore.isActive(ctx)
        val paused = EmergencyBypassStore.isPaused(ctx)
        val remaining = EmergencyBypassStore.minutesRemaining(ctx)

        if (!active && !paused) {
            refreshEmergencyPref()
            return
        }

        val title = if (paused) {
            getString(R.string.emergency_manage_title_paused, remaining)
        } else {
            getString(R.string.emergency_manage_title_active, remaining)
        }

        val actions = if (active) {
            listOf(
                getString(R.string.emergency_action_pause),
                getString(R.string.emergency_action_end)
            )
        } else {
            listOf(
                getString(R.string.emergency_action_resume),
                getString(R.string.emergency_action_end)
            )
        }

        ctx.showSwitchlyOptionDialog(
            title = title,
            options = actions.mapIndexed { index, label ->
                SwitchlyDialogOption(
                    title = label,
                    destructive = index == 1
                )
            }
        ) { which ->
            if (active) {
                when (which) {
                    0 -> {
                        val ok = EmergencyBypassStore.pause(ctx)
                        if (ok) {
                            SwitchModeStore.clearTemporary(ctx)
                            AppLogStore.append(ctx, "Emergency", "Emergency mode paused from Settings")
                            Toast.makeText(ctx, getString(R.string.emergency_paused_toast), Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        AppLogStore.append(ctx, "Emergency", "Emergency mode ended from Settings")
                        EmergencyBypassStore.cancel(ctx)
                        SwitchModeStore.clearTemporary(ctx)
                        Toast.makeText(ctx, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                when (which) {
                    0 -> {
                        val ok = EmergencyBypassStore.resume(ctx)
                        if (ok) {
                            val remaining = EmergencyBypassStore.minutesRemaining(ctx).coerceAtLeast(1)
                            SwitchModeStore.setTemporarilyDisabled(ctx, remaining * 60_000L)
                            AppLogStore.append(ctx, "Emergency", "Emergency mode resumed from Settings with ${remaining}m remaining")
                            Toast.makeText(ctx, getString(R.string.emergency_resumed_toast), Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        AppLogStore.append(ctx, "Emergency", "Emergency mode ended from Settings")
                        EmergencyBypassStore.cancel(ctx)
                        SwitchModeStore.clearTemporary(ctx)
                        Toast.makeText(ctx, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                    }
                }
            }

            BlockingRuntime.ensureRunning(ctx)
            refreshEmergencyPref()
        }
    }

    private fun showEmergencyUnlockStartDialog() {
        val ctx = requireContext()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.pref_emergency_title))
            .setMessage(getString(R.string.emergency_action_start_15))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                triggerEmergencyUnlock()
            }
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun triggerEmergencyUnlock() {

        val ctx = requireContext()
        val minutes = 15
        val ok = EmergencyBypassStore.enableIfAllowed(ctx, minutes)
        if (ok) {
            AppLogStore.append(ctx, "Emergency", "Emergency mode started from Settings for ${minutes}m")
            SwitchModeStore.setTemporarilyDisabled(ctx, minutes * 60_000L)
            Toast.makeText(ctx, getString(R.string.emergency_enabled_toast, minutes), Toast.LENGTH_SHORT).show()
            BlockingRuntime.ensureRunning(ctx)
        } else {
            Toast.makeText(ctx, getString(R.string.emergency_used_today), Toast.LENGTH_SHORT).show()
        }
        refreshEmergencyPref()
    }

    private fun getStoredEmergencyPin(ctx: Context): String? {
        val appPrefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val defaultPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)

        val candidates = listOf(
            appPrefs.getString(KEY_EMERGENCY_PIN, null),
            defaultPrefs.getString(KEY_EMERGENCY_PIN, null),
            appPrefs.getString("emergency_pin", null),
            defaultPrefs.getString("emergency_pin", null),
            appPrefs.getString("pref_emergency_unlock_pin", null),
            defaultPrefs.getString("pref_emergency_unlock_pin", null),
            appPrefs.getString("emergency_unlock_pin", null),
            defaultPrefs.getString("emergency_unlock_pin", null),
        )

        val resolved = candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
        if (!resolved.isNullOrEmpty() && appPrefs.getString(KEY_EMERGENCY_PIN, null) != resolved) {
            appPrefs.edit { putString(KEY_EMERGENCY_PIN, resolved) }
        }
        return resolved
    }

    private fun showSetEmergencyPinDialog(onSuccess: () -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.emergency_pin_choose_hint)
            backgroundTintList = AccentColor.getActiveColor(ctx)
        }

        val container = FrameLayout(ctx).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.emergency_pin_title))
            .setMessage(getString(R.string.emergency_pin_message))
            .setView(container)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin.length < 4) {
                    Toast.makeText(ctx, getString(R.string.emergency_pin_too_short), Toast.LENGTH_SHORT).show()
                } else {
                    ctx.getSharedPreferences(PREFS, 0).edit { putString(KEY_EMERGENCY_PIN, pin) }
                    onSuccess()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showEnterEmergencyPinDialog(expectedPin: String, onSuccess: () -> Unit) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.emergency_pin_enter_current_hint)
            backgroundTintList = AccentColor.getActiveColor(ctx)
        }

        val container = FrameLayout(ctx).apply {
            val margin = (24 * resources.displayMetrics.density).toInt()
            setPadding(margin, 0, margin, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.emergency_pin_enter_current_title))
            .setMessage(getString(R.string.emergency_pin_enter_current_message))
            .setView(container)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                val pin = input.text.toString().trim()
                if (pin == expectedPin) onSuccess()
                else Toast.makeText(ctx, getString(R.string.emergency_pin_incorrect), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun showDeleteBackupsDialog(vararg _ignored: Any?) {
        showDeleteBackupsDialog()
    }

    private fun showDeleteBackupsDialog() {
        val initialCtx = context ?: return
        val loadingDialog = showProgressDialog(
            initialCtx,
            R.string.settings_delete_backups_title,
            R.string.cloud_restore_loading
        )

        CloudSyncRuntime.listBackups(initialCtx) { ok, err, backups ->
            val activeCtx = context ?: return@listBackups
            if (!isAdded) return@listBackups
            if (loadingDialog.isShowing) loadingDialog.dismiss()
            if (!ok) {
                Toast.makeText(activeCtx, getString(R.string.cloud_error_fmt, err ?: getString(R.string.error_unknown)), Toast.LENGTH_SHORT).show()
                return@listBackups
            }

            val list = backups.orEmpty()
            if (list.isEmpty()) {
                Toast.makeText(activeCtx, getString(R.string.cloud_no_backups), Toast.LENGTH_SHORT).show()
                return@listBackups
            }

            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            val labels = list.map { df.format(Date(it.createdAt)) }.toTypedArray()
            val checked = BooleanArray(labels.size)

            activeCtx.showSwitchlyMultiChoiceDialog(
                title = getString(R.string.settings_delete_backups_title),
                options = labels.map { SwitchlyDialogOption(title = it, destructive = true) },
                checked = checked,
                positiveTextRes = R.string.delete
            ) { result ->
                val ids = list.indices.filter { result[it] }.map { list[it].id }
                if (ids.isEmpty()) return@showSwitchlyMultiChoiceDialog

                val deleteDialog = showProgressDialog(
                    activeCtx,
                    R.string.settings_delete_backups_title,
                    R.string.cloud_delete_backups_loading
                )
                var remaining = ids.size
                var failed = 0
                var lastError: String? = null

                fun finishOne(ok: Boolean, err: String?) {
                    if (!ok) {
                        failed += 1
                        if (!err.isNullOrBlank()) lastError = err
                    }
                    remaining -= 1
                    if (remaining > 0) {
                        return
                    }

                    val ctx = context ?: return
                    if (!isAdded) {
                        return
                    }
                    if (deleteDialog.isShowing) deleteDialog.dismiss()
                    val message = if (failed == 0) {
                        getString(R.string.deleted)
                    } else {
                        getString(R.string.cloud_error_fmt, lastError ?: getString(R.string.error_unknown))
                    }
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
                }

                ids.forEach { id ->
                    runCatching {
                        CloudSyncRuntime.deleteBackup(activeCtx, id) { ok, err ->
                            finishOne(ok, err)
                        }
                    }.onFailure { e ->
                        finishOne(false, e.localizedMessage)
                    }
                }
            }
        }
    }

    private fun showResetAllDataDialog() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.pref_reset_app_data_confirm_title))
            .setMessage(getString(R.string.pref_reset_app_data_confirm_message) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                resetAllAppDataNow()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showDestructiveAccented()
    }

    private fun resetAllAppDataNow() {
        val ctx = requireContext()
        val ok = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.clearApplicationUserData()
        }.getOrDefault(false)

        if (!ok) {
            // Fallback for OEMs where clearApplicationUserData may fail silently.
            runCatching {
                StatsPersistence.prepareForFullDataDeletion(ctx)
                try {
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit(commit = true) { clear() }
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) { clear() }
                    ctx.getSharedPreferences("switchly_prefs_schedules", Context.MODE_PRIVATE).edit(commit = true) { clear() }
                    ctx.getSharedPreferences("switchly_ui_hints", Context.MODE_PRIVATE).edit(commit = true) { clear() }
                    ctx.getSharedPreferences(ActivityHistoryLogStore.PREFS_NAME, Context.MODE_PRIVATE).edit(commit = true) { clear() }
                    ctx.databaseList().forEach { databaseName ->
                        ctx.deleteDatabase(databaseName)
                    }
                    ctx.cacheDir?.deleteRecursively()
                    ctx.filesDir?.listFiles()?.forEach { it.deleteRecursively() }
                } finally {
                    StatsPersistence.resumeAfterFullDataDeletion(ctx)
                }
            }
            Toast.makeText(ctx, getString(R.string.pref_reset_app_data_done), Toast.LENGTH_LONG).show()
            restartAppTask()
        }
    }

    companion object {
        private const val ARG_PREFERENCE_ROOT = "androidx.preference.PreferenceFragmentCompat.PREFERENCE_ROOT"
        private const val PREFS = "switchly_prefs"
        private const val KEY_DEV_UNLOCKED = "pref_dev_unlocked"
        private const val KEY_EMERGENCY_PIN = "pref_emergency_pin"
    }
}
