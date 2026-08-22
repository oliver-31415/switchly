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

package at.saltyy.switchly.feature.support

import android.Manifest
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.ImageViewCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AdvancedModeStore
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockingToggleKeys
import at.saltyy.switchly.data.prefs.BlockedInboxStore
import at.saltyy.switchly.data.prefs.BarcodeScanCountStore
import at.saltyy.switchly.data.prefs.DomainBlockStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.InAppRuleStore
import at.saltyy.switchly.data.prefs.LastBlockReasonStore
import at.saltyy.switchly.data.prefs.NfcDiagnosticsStore
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.ProfileRuleModeStore
import at.saltyy.switchly.data.prefs.WebsiteRuleModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.QrScanCountStore
import at.saltyy.switchly.data.prefs.ScheduleExecutionCountStore
import at.saltyy.switchly.data.prefs.ScheduleInsights
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleRuntimeStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.SwitchlyActionCountStore
import at.saltyy.switchly.data.prefs.SwitchlyRuntimeStore
import at.saltyy.switchly.data.sync.BackupSelectionStore
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.feature.usage.ActivityHistoryRepository
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.receiver.DPMReceiver
import at.saltyy.switchly.security.AppLockStore
import at.saltyy.switchly.security.PlayIntegrityRuntime
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.showSwitchlyMultiChoiceDialog
import at.saltyy.switchly.util.AppSigningInfo
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.BatteryOptimizationCompat
import at.saltyy.switchly.util.NfcLaunchAccessCompat
import at.saltyy.switchly.util.PersistentStatusNotifier
import at.saltyy.switchly.util.ReleaseDiagnostics
import at.saltyy.switchly.util.ManagedDevicePolicyHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SupportActivity : AppCompatActivity() {

    private companion object {
        private const val SUPPORT_EMAIL = "support@saltyy.at"
        private const val KEY_INCLUDE_DEBUG = "support_include_debug"
        private const val KEY_INCLUDE_ADVANCED_DEBUG = "support_include_advanced_debug"
        private const val KEY_INCLUDE_SETUP_DETAILS = "support_include_setup_details"
        private const val KEY_INCLUDE_ACTIVE_PROFILE_APPS = "support_include_active_profile_apps"
        private const val KEY_INCLUDE_LOGS = "support_include_logs"
    }

    private data class ReportSelection(
        val includeDebug: Boolean,
        val includeActiveProfileApps: Boolean,
        val includeSetupDetails: Boolean,
        val includeAdvancedDebug: Boolean,
        val includeLogs: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        val toolbarIconColor = toolbarForegroundColor()
        toolbar.navigationIcon?.mutate()?.setTint(toolbarIconColor)
        toolbar.setTitleTextColor(toolbarIconColor)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val email = SUPPORT_EMAIL
        findViewById<TextView>(R.id.tvSupportEmail).apply {
            text = email
            setTextColor(ContextCompat.getColor(this@SupportActivity, R.color.contact_text))
            visibility = View.VISIBLE
            alpha = 1f
        }

        findViewById<ImageButton>(R.id.btnCopyEmailInline).apply {
            ImageViewCompat.setImageTintList(this, AccentColor.getActiveColor(this@SupportActivity))
            setOnClickListener {
                copyToClipboard(label = getString(R.string.support_copy_email), text = email)
                Toast.makeText(this@SupportActivity, getString(R.string.support_copied), Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.rowViewLogs).setOnClickListener {
            startActivity(Intent(this, SupportLogActivity::class.java))
        }

        findViewById<View>(R.id.rowCopyLogs).setOnClickListener {
            val payload = AppLogStore.export(this@SupportActivity)
            copyToClipboard(label = getString(R.string.support_copy_latest_logs), text = payload)
            Toast.makeText(this@SupportActivity, getString(R.string.support_logs_copied), Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnOpenEmail).setOnClickListener {
            showReportSelectionDialog()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_support, menu)
        menu.findItem(R.id.action_info)?.icon?.mutate()?.setTint(toolbarForegroundColor())
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_info -> {
                showSupportResponseInfoDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSupportResponseInfoDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.support_response_time_title)
            .setMessage(R.string.support_response_time_details)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun showReportSelectionDialog() {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val options = listOf(
            SwitchlyDialogOption(
                title = getString(R.string.support_include_debug),
                summary = getString(R.string.support_include_debug_summary),
                iconRes = R.drawable.info_24
            ),
            SwitchlyDialogOption(
                title = getString(R.string.support_include_active_profile_apps),
                summary = getString(R.string.support_include_active_profile_apps_summary),
                iconRes = R.drawable.apps_24
            ),
            SwitchlyDialogOption(
                title = getString(R.string.support_include_setup_details),
                summary = getString(R.string.support_include_setup_details_summary),
                iconRes = R.drawable.tune_24
            ),
            SwitchlyDialogOption(
                title = getString(R.string.support_include_advanced_debug),
                summary = getString(R.string.support_include_advanced_debug_summary),
                iconRes = R.drawable.security_24
            ),
            SwitchlyDialogOption(
                title = getString(R.string.support_include_logs),
                summary = getString(R.string.support_include_logs_summary),
                iconRes = R.drawable.layers_24
            )
        )
        val checked = booleanArrayOf(
            sp.getBoolean(KEY_INCLUDE_DEBUG, true),
            sp.getBoolean(KEY_INCLUDE_ACTIVE_PROFILE_APPS, true),
            sp.getBoolean(KEY_INCLUDE_SETUP_DETAILS, false),
            sp.getBoolean(KEY_INCLUDE_ADVANCED_DEBUG, false),
            sp.getBoolean(KEY_INCLUDE_LOGS, false)
        )

        showSwitchlyMultiChoiceDialog(
            title = getString(R.string.support_choose_report_data),
            options = options,
            checked = checked,
            positiveTextRes = R.string.support_open_email,
            compact = false,
            forceHorizontalButtons = true
        ) { states ->
            val selection = ReportSelection(
                includeDebug = states.getOrNull(0) == true,
                includeActiveProfileApps = states.getOrNull(1) == true,
                includeSetupDetails = states.getOrNull(2) == true,
                includeAdvancedDebug = states.getOrNull(3) == true,
                includeLogs = states.getOrNull(4) == true
            )
            sp.edit {
                putBoolean(KEY_INCLUDE_DEBUG, selection.includeDebug)
                putBoolean(KEY_INCLUDE_ACTIVE_PROFILE_APPS, selection.includeActiveProfileApps)
                putBoolean(KEY_INCLUDE_SETUP_DETAILS, selection.includeSetupDetails)
                putBoolean(KEY_INCLUDE_ADVANCED_DEBUG, selection.includeAdvancedDebug)
                putBoolean(KEY_INCLUDE_LOGS, selection.includeLogs)
            }
            openSupportEmail(selection)
        }
    }

    private fun openSupportEmail(selection: ReportSelection) {
        val includeDebugReport = selection.includeDebug ||
            selection.includeSetupDetails ||
            selection.includeAdvancedDebug
        val sections = mutableListOf<String>()

        if (includeDebugReport) {
            sections += buildDebugInfo(
                includeAdvanced = selection.includeAdvancedDebug,
                includeSetupDetails = selection.includeSetupDetails
            ).trim()
        }
        if (selection.includeActiveProfileApps) {
            sections += buildActiveProfileAppsInfo().trim()
        }
        if (selection.includeLogs) {
            val logs = AppLogStore.export(this@SupportActivity).trim()
            if (logs.isNotBlank()) {
                sections += buildString {
                    append("-----\n")
                    append(getString(R.string.support_latest_logs_heading))
                    append("\n-----\n")
                    append(logs)
                }
            }
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.support_email_subject))
            sections.filter { it.isNotBlank() }
                .joinToString("\n\n")
                .takeIf { it.isNotBlank() }
                ?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }

        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.support_open_email)))
        }
    }

    private fun buildActiveProfileAppsInfo(): String = buildString {
        val profile = runCatching { ProfileStore.getCurrent(this@SupportActivity) }
            .getOrNull()
            .orEmpty()
        append("-----\n")
        append("Active profile app rules\n")
        append("-----\n")
        append("Profile: ").append(profile.ifBlank { "-" }).append("\n")
        if (profile.isBlank()) {
            append("Selected apps: 0\n")
            return@buildString
        }

        val ruleMode = runCatching {
            ProfileRuleModeStore.getMode(this@SupportActivity, profile)
        }.getOrDefault("-")
        val packages = runCatching {
            ProfileStore.getSelectedForProfileMode(this@SupportActivity, profile)
        }.getOrDefault(emptySet())
        val apps = packages.map { packageName ->
            applicationLabel(packageName) to packageName
        }.sortedWith(
            compareBy<Pair<String, String>> { it.first.lowercase(Locale.getDefault()) }
                .thenBy { it.second }
        )

        append("Rule mode: ").append(ruleMode).append("\n")
        append("Selected apps: ").append(apps.size).append("\n")
        apps.forEach { (label, packageName) ->
            append("- ").append(label)
            append(" | ").append(packageName)
            append("\n")
        }
    }

    private fun applicationLabel(packageName: String): String {
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0L)
                )
            } else {
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
    }

    private fun buildDebugInfo(includeAdvanced: Boolean, includeSetupDetails: Boolean): String = buildString {
        fun line(key: String, value: Any?) {
            val v = value?.toString()?.takeIf { it.isNotBlank() } ?: "-"
            append(key).append(": ").append(v).append("\n")
        }

        fun section(title: String) {
            append("\n")
            append("[").append(title).append("]\n")
        }

        append(getString(R.string.support_debug_preface))
        append("\n\n")
        append("-----\n")
        append("Debug info\n")
        append("-----\n")

        val nowMs = System.currentTimeMillis()

        line(
            "Timestamp",
            formatDateTime(nowMs)
        )
        line(
            "Locale",
            Locale.getDefault().toLanguageTag()
        )
        line(
            "Timezone",
            TimeZone.getDefault().id
        )

        section("App")
        line(
            "App",
            "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        )
        line(
            "APK variant",
            BuildConfig.SWITCHLY_APK_VARIANT
        )
        line(
            "Build type",
            BuildConfig.BUILD_TYPE
        )
        line(
            "Build variant",
            "${BuildConfig.SWITCHLY_APK_VARIANT}-${BuildConfig.BUILD_TYPE}"
        )
        line(
            "Package",
            packageName
        )
        line(
            "Signing SHA-1",
            AppSigningInfo.sha1(this@SupportActivity)
        )

        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            line(
                "First install",
                formatDateTime(info.firstInstallTime)
            )
            line(
                "Last update",
                formatDateTime(info.lastUpdateTime)
            )
        }.onFailure {
            line(
                "First install",
                "-"
            )
            line(
                "Last update",
                "-"
            )
        }

        section("Build configuration")
        line(
            "Maps API key bundled",
            BuildConfig.SWITCHLY_HAS_MAPS_API_KEY
        )
        line(
            "Official release signing configured at build",
            BuildConfig.SWITCHLY_RELEASE_SIGNING_CONFIGURED
        )

        section("Release diagnostics")
        val releaseDiagnostics = ReleaseDiagnostics.snapshot(this@SupportActivity)
        line("Status", releaseDiagnostics.status.name)
        line("Signing check", releaseDiagnostics.signingMessage)
        line("Installed signing SHA-1", releaseDiagnostics.currentSha1 ?: "-")
        line("Signing history count", releaseDiagnostics.signingHistory.size)
        line(
            "Signing history SHA-1",
            releaseDiagnostics.signingHistory.ifEmpty { listOf("-") }.joinToString(" -> ")
        )
        line("Installer", releaseDiagnostics.installerPackage ?: "-")
        line("Upgrade observed", releaseDiagnostics.upgradeObserved)
        line(
            "Upgrade check",
            if (releaseDiagnostics.upgradeObserved) {
                "OK: Android reports an in-place update for this installation."
            } else {
                "N/A: fresh install or no previous install timestamp; run a release-signed upgrade smoke test before publishing."
            }
        )

        section("Device")
        line(
            "Device",
            "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        line(
            "Android",
            "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        )
        val securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: "-"
        line(
            "Security patch",
            securityPatch
        )

        section("Switchly state")
        val currentProfile = runCatching { ProfileStore.getCurrent(this@SupportActivity) }.getOrNull().orEmpty()
        val blockedCurrentProfile = if (currentProfile.isNotBlank()) {
            runCatching { ProfileStore.getSelectedForProfileMode(this@SupportActivity, currentProfile).size }.getOrDefault(0)
        } else {
            0
        }
        val profiles = runCatching { ProfileStore.getProfiles(this@SupportActivity) }.getOrDefault(emptySet())
        val currentRuleMode = if (currentProfile.isBlank()) {
            "-"
        } else {
            runCatching { ProfileRuleModeStore.getMode(this@SupportActivity, currentProfile) }.getOrDefault("-")
        }
        val currentWebsiteRuleMode = if (currentProfile.isBlank()) {
            "-"
        } else {
            runCatching { WebsiteRuleModeStore.getMode(this@SupportActivity, currentProfile) }.getOrDefault("-")
        }

        val mode = runCatching { AutomationModeStore.getMode(this@SupportActivity) }
            .getOrDefault(AutomationModeStore.Mode.MIXED)
        val defaultSp = PreferenceManager.getDefaultSharedPreferences(this@SupportActivity)
        val switchlyPrefs = this@SupportActivity.getSharedPreferences("switchly_prefs", MODE_PRIVATE)
        val homeMode = defaultSp.getString(ToggleOptionsActivity.KEY_HOME_LAYOUT_MODE, ToggleOptionsActivity.HOME_MODE_DEFAULT)
            ?: ToggleOptionsActivity.HOME_MODE_DEFAULT
        val customHomeKeys = listOf(
            ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_TIMER to true,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_MAIN_BUTTON to true,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_ACTIVE_PROFILE to true,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_CONTROL_MODE to true,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_TEMPORARY to false,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_EMERGENCY to false,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_QUICK_ACTIONS to false,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_NEXT_SCHEDULE to false,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_BLOCKED_APPS to false,
            ToggleOptionsActivity.KEY_HOME_CUSTOM_PROFILE_DROPDOWN to false
        )

        line(
            "Premium",
            PremiumManager.isPremium(this@SupportActivity)
        )
        line(
            "Premium source",
            PremiumManager.premiumSource(this@SupportActivity)
        )
        line(
            "Premium redeem codes enabled",
            BuildConfig.SWITCHLY_REDEEM_CODES_ENABLED
        )
        line(
            "Premium online redeem enabled",
            BuildConfig.SWITCHLY_ONLINE_REDEEM_CODES_ENABLED
        )
        line(
            "Premium offline redeem enabled",
            BuildConfig.SWITCHLY_OFFLINE_REDEEM_CODES_ENABLED
        )
        line(
            "Switchly enabled",
            SwitchModeStore.isEnabled(this@SupportActivity)
        )
        line(
            "Switchly base enabled",
            SwitchModeStore.isBaseEnabled(this@SupportActivity)
        )
        line(
            "Mode",
            mode.raw
        )

        line(
            "Profiles count",
            profiles.size
        )
        line(
            "Active profile",
            if (currentProfile.isBlank()) "-" else currentProfile
        )
        line(
            "App rule mode (active profile)",
            currentRuleMode
        )
        line(
            "Website rule mode (active profile)",
            currentWebsiteRuleMode
        )
        line(
            "Profiles with app allow mode",
            profiles.count { ProfileRuleModeStore.isAllowMode(this@SupportActivity, it) }
        )
        line(
            "Profiles with website allow mode",
            profiles.count { WebsiteRuleModeStore.isAllowMode(this@SupportActivity, it) }
        )
        line(
            "Selected apps (active profile)",
            blockedCurrentProfile
        )
        line(
            "Website rules enabled",
            DomainBlockStore.isEnabled(this@SupportActivity)
        )
        line(
            "Website rules (active profile)",
            if (currentProfile.isBlank()) 0 else DomainBlockStore.getDomainsForProfile(this@SupportActivity, currentProfile).size
        )
        line(
            "Allowed website rules (active profile)",
            if (currentProfile.isBlank()) 0 else DomainBlockStore.getAllowedDomainsForProfile(this@SupportActivity, currentProfile).size
        )
        line(
            "In-app rule packages (active profile)",
            if (currentProfile.isBlank()) 0 else InAppRuleStore.getPackagesWithEnabledRules(this@SupportActivity, currentProfile).size
        )
        line(
            "Time limits with session reset",
            switchlyPrefs.all.keys.count { it.startsWith("usage_limit_reset__") }
        )

        val nfcPairedTags = runCatching { NfcUidPairingStore.getPairedTags(this@SupportActivity) }.getOrDefault(emptyList())
        line(
            "NFC required to disable",
            SwitchModeStore.isNfcRequiredForDisable(this@SupportActivity)
        )
        line(
            "NFC paired tags",
            nfcPairedTags.size
        )
        line(
            "NFC paired read-only tags",
            nfcPairedTags.count { it.tagKind == NfcUidPairingStore.TagKind.READ_ONLY }
        )
        line(
            "NFC paired writable tags",
            nfcPairedTags.count { it.tagKind == NfcUidPairingStore.TagKind.WRITABLE }
        )
        line(
            "NFC paired UID-action tags",
            nfcPairedTags.count { it.supportsUidOnlyAction }
        )
        line(
            "NFC paired writable action overrides",
            nfcPairedTags.count {
                it.tagKind == NfcUidPairingStore.TagKind.WRITABLE &&
                    it.action != NfcUidPairingStore.PairedTagAction.USE_WRITTEN
            }
        )
        line(
            "NFC paired profile-specific actions",
            nfcPairedTags.count { it.actionProfile != null }
        )
        line(
            "NFC paired ask-duration actions",
            nfcPairedTags.count { it.askDurationWhenScanned }
        )
        line(
            "NFC paired tags enabled",
            switchlyPrefs.getBoolean(BlockingToggleKeys.KEY_ENABLE_PAIRED_UIDS, false)
        )
        line(
            "NFC auto pair on write",
            switchlyPrefs.getBoolean(BlockingToggleKeys.KEY_AUTO_PAIR_ON_WRITE, false)
        )
        line(
            "NFC scans today",
            runCatching { NfcScanCountStore.getToday(this@SupportActivity) }.getOrDefault(0)
        )
        line(
            "NFC scans last 7d",
            runCatching { NfcScanCountStore.getForLastNDays(this@SupportActivity, 7) }.getOrDefault(0)
        )
        line(
            "QR scans today",
            runCatching { QrScanCountStore.getToday(this@SupportActivity) }.getOrDefault(0)
        )
        line(
            "QR scans last 7d",
            runCatching { QrScanCountStore.getForLastNDays(this@SupportActivity, 7) }.getOrDefault(0)
        )
        line(
            "Barcode scans today",
            runCatching { BarcodeScanCountStore.getToday(this@SupportActivity) }.getOrDefault(0)
        )
        line(
            "Barcode scans last 7d",
            runCatching { BarcodeScanCountStore.getForLastNDays(this@SupportActivity, 7) }.getOrDefault(0)
        )
        line(
            "Switchly enables today",
            runCatching { SwitchlyActionCountStore.getToday(this@SupportActivity, SwitchlyActionCountStore.Action.ENABLE) }.getOrDefault(0)
        )
        line(
            "Switchly disables today",
            runCatching { SwitchlyActionCountStore.getToday(this@SupportActivity, SwitchlyActionCountStore.Action.DISABLE) }.getOrDefault(0)
        )
        val nfcAdapter = runCatching { NfcAdapter.getDefaultAdapter(this@SupportActivity) }.getOrNull()
        val nfcDiag = NfcDiagnosticsStore.snapshot(this@SupportActivity)
        line(
            "NFC hardware available",
            nfcAdapter != null
        )
        line(
            "NFC system enabled",
            nfcAdapter?.isEnabled == true
        )
        line(
            "NFC launch access",
            NfcLaunchAccessCompat.state(this@SupportActivity).name.lowercase()
        )
        line(
            "Last NFC intent",
            formatDateTime(nfcDiag.lastIntentAtMillis)
        )
        line(
            "Last NFC intent action",
            nfcDiag.lastIntentAction.ifBlank { "-" }
        )
        line(
            "Last NFC UID",
            nfcDiag.lastUidHex.ifBlank { "-" }
        )
        line(
            "Last NFC techs",
            nfcDiag.lastTechList.ifBlank { "-" }
        )
        line(
            "Last NFC URI",
            nfcDiag.lastUri.ifBlank { "-" }
        )
        line(
            "Last NFC resolved action",
            nfcDiag.lastResolvedAction.ifBlank { "-" }
        )
        line(
            "Last NFC resolved profile",
            nfcDiag.lastResolvedProfile.ifBlank { "-" }
        )
        line(
            "Last NFC failure",
            nfcDiag.lastFailureReason.ifBlank { "-" }
        )
        line(
            "Last NFC write",
            formatDateTime(nfcDiag.lastWriteAtMillis)
        )
        line(
            "Last NFC write result",
            nfcDiag.lastWriteResult.ifBlank { "-" }
        )

        line(
            "Temp disable active",
            SwitchModeStore.getTemporaryRemainingMillis(this@SupportActivity) > 0L
        )
        line(
            "Temp disable remaining ms",
            SwitchModeStore.getTemporaryRemainingMillis(this@SupportActivity)
        )
        line(
            "Temp enable active",
            SwitchModeStore.getTemporaryEnableRemainingMillis(this@SupportActivity) > 0L
        )
        line(
            "Temp enable remaining ms",
            SwitchModeStore.getTemporaryEnableRemainingMillis(this@SupportActivity)
        )

        line(
            "Emergency feature enabled",
            EmergencyBypassStore.isFeatureEnabled(this@SupportActivity)
        )
        val emergencyActive = EmergencyBypassStore.isActive(this@SupportActivity)
        val emergencyPaused = EmergencyBypassStore.isPaused(this@SupportActivity)
        line(
            "Emergency active",
            emergencyActive
        )
        line(
            "Emergency paused",
            emergencyPaused
        )
        line(
            "Emergency minutes remaining",
            EmergencyBypassStore.minutesRemaining(this@SupportActivity)
        )
        line(
            "Emergency used today",
            EmergencyBypassStore.hasUsedToday(this@SupportActivity)
        )
        line(
            "Emergency editing bypass effective",
            emergencyActive
        )
        line(
            "Schedules skipped by emergency",
            emergencyActive
        )

        line(
            "Channel allowed: schedule",
            AutomationModeStore.isScheduleAllowed(this@SupportActivity)
        )
        line(
            "Channel allowed: nfc",
            AutomationModeStore.isNfcAllowed(this@SupportActivity)
        )
        line(
            "Channel allowed: qr(channel)",
            AutomationModeStore.isQrChannelAllowed(this@SupportActivity)
        )
        line(
            "Channel allowed: qr(effective)",
            AutomationModeStore.isQrAllowed(this@SupportActivity)
        )
        line(
            "Channel allowed: barcode(channel)",
            AutomationModeStore.isBarcodeChannelAllowed(this@SupportActivity)
        )
        line(
            "Channel allowed: barcode(effective)",
            AutomationModeStore.isBarcodeAllowed(this@SupportActivity)
        )
        line(
            "Channel allowed: tile",
            AutomationModeStore.isTileAllowed(this@SupportActivity)
        )
        line(
            "Channel allowed: button",
            AutomationModeStore.isButtonAllowed(this@SupportActivity)
        )
        line(
            "Button enable allowed",
            AutomationModeStore.isButtonEnableAllowed(this@SupportActivity)
        )
        line(
            "Button can enable (effective)",
            AutomationModeStore.canButtonEnable(this@SupportActivity)
        )

        line(
            "Allowed while enabled: app picker",
            AutomationModeStore.isAppPickerAllowedWhileEnabled(this@SupportActivity)
        )
        line(
            "Allowed while enabled: profile switching",
            AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(this@SupportActivity)
        )
        line(
            "Allowed while enabled: schedule editing",
            AutomationModeStore.isScheduleEditingAllowedWhileEnabled(this@SupportActivity)
        )
        line(
            "Allowed while enabled: NFC tag writing",
            AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(this@SupportActivity)
        )
        line(
            "Restricted editing active",
            EditingLockGuard.isLocked(this@SupportActivity)
        )
        val legacyUninstallFrictionEnabled = AutomationModeStore.isUninstallFrictionEnabled(this@SupportActivity)
        val strictProtectionConfigured = AppLockStore.isStrictProtectionEnabled(this@SupportActivity)
        line(
            "Uninstall protection configured",
            strictProtectionConfigured
        )
        line(
            "Legacy uninstall friction",
            legacyUninstallFrictionEnabled
        )
        line(
            "Developer mode",
            AdvancedModeStore.isEnabled(this@SupportActivity)
        )
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(this@SupportActivity, DPMReceiver::class.java)
        val deviceAdminActive = dpm?.isAdminActive(adminComponent) == true
        val profileOwnerActive = dpm?.isProfileOwnerApp(packageName) == true
        val deviceOwnerActive = dpm?.isDeviceOwnerApp(packageName) == true
        val managedSelfUninstallBlocked = ManagedDevicePolicyHelper.isSelfUninstallBlocked(this@SupportActivity)
        val uninstallProtectionEffective = when {
            (deviceOwnerActive || profileOwnerActive) && strictProtectionConfigured -> managedSelfUninstallBlocked == true
            strictProtectionConfigured && deviceAdminActive -> true
            else -> legacyUninstallFrictionEnabled
        }
        val uninstallProtectionReason = when {
            deviceOwnerActive && strictProtectionConfigured && managedSelfUninstallBlocked == true -> "managed uninstall block active + device owner"
            profileOwnerActive && strictProtectionConfigured && managedSelfUninstallBlocked == true -> "managed uninstall block active + profile owner"
            (deviceOwnerActive || profileOwnerActive) && strictProtectionConfigured -> "managed ownership active but uninstall policy not reported active"
            deviceAdminActive && strictProtectionConfigured -> "device admin uninstall friction active"
            legacyUninstallFrictionEnabled -> "legacy uninstall friction enabled"
            strictProtectionConfigured -> "uninstall protection configured but admin inactive"
            else -> "uninstall protection disabled"
        }
        line(
            "Device admin active",
            deviceAdminActive
        )
        line(
            "Profile owner active",
            profileOwnerActive
        )
        line(
            "Device owner active",
            deviceOwnerActive
        )
        line(
            "Managed self-uninstall blocked",
            managedSelfUninstallBlocked?.toString() ?: "n/a"
        )
        line(
            "Uninstall protection effective",
            "$uninstallProtectionEffective ($uninstallProtectionReason)"
        )

        if (mode == AutomationModeStore.Mode.MIXED) {
            line(
                "Mixed toggle: schedule",
                AutomationModeStore.isMixedAllowSchedule(this@SupportActivity)
            )
            line(
                "Mixed toggle: nfc",
                AutomationModeStore.isMixedAllowNfc(this@SupportActivity)
            )
            line(
                "Mixed toggle: qr",
                AutomationModeStore.isMixedAllowQr(this@SupportActivity)
            )
            line(
                "Mixed toggle: barcode",
                AutomationModeStore.isMixedAllowBarcode(this@SupportActivity)
            )
            line(
                "Mixed toggle: tile",
                AutomationModeStore.isMixedAllowTile(this@SupportActivity)
            )
            line(
                "Mixed toggle: button",
                AutomationModeStore.isMixedAllowButton(this@SupportActivity)
            )
            line(
                "Mixed toggle: app picking",
                AutomationModeStore.isMixedAllowAppPicking(this@SupportActivity)
            )
            line(
                "Mixed toggle: profile switching",
                AutomationModeStore.isMixedAllowProfileSwitching(this@SupportActivity)
            )
            line(
                "Mixed toggle: schedule editing",
                AutomationModeStore.isMixedAllowScheduleEditing(this@SupportActivity)
            )
            line(
                "Mixed toggle: NFC tag writing",
                AutomationModeStore.isMixedAllowNfcTagWriting(this@SupportActivity)
            )
        }

        line(
            "Quick actions visible",
            defaultSp.getBoolean("pref_show_quick_actions", true)
        )
        line(
            "Temporary mode visible",
            defaultSp.getBoolean("pref_show_temporary_mode", true)
        )
        line(
            "Emergency unlock visible",
            defaultSp.getBoolean("pref_show_emergency_unlock", true)
        )
        line(
            "Persistent status notification",
            PersistentStatusNotifier.isEnabled(this@SupportActivity)
        )
        line(
            "Home display mode",
            homeMode
        )
        line(
            "Custom Home sections",
            "${customHomeKeys.count { (key, defaultValue) -> defaultSp.getBoolean(key, defaultValue) }}/${customHomeKeys.size}"
        )
        line(
            "Quick actions expanded",
            defaultSp.getBoolean("home_quick_actions_expanded", false)
        )
        line(
            "QR tools visible",
            AutomationModeStore.shouldShowQrTools(this@SupportActivity)
        )
        line(
            "Barcode tools visible",
            AutomationModeStore.shouldShowBarcodeTools(this@SupportActivity)
        )
        line(
            "Backup selection",
            BackupSelectionStore.load(this@SupportActivity).displaySummary()
        )

        section("Permissions")
        val notificationsEnabled = NotificationManagerCompat.from(this@SupportActivity).areNotificationsEnabled()
        val postNotifsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        line(
            "Notifications enabled",
            notificationsEnabled
        )
        line(
            "POST_NOTIFICATIONS granted",
            postNotifsGranted
        )
        line(
            "Notification listener access",
            NotificationBlockStore.hasListenerAccess(this@SupportActivity)
        )
        line(
            "Notification blocking enabled",
            NotificationBlockStore.isEnabled(this@SupportActivity)
        )

        line(
            "Accessibility enabled",
            BlockingRuntime.isAccessibilityActive(this@SupportActivity)
        )
        line(
            "Usage access",
            UsageStatsRepo.hasUsageAccess(this@SupportActivity)
        )
        line(
            "Location services enabled",
            isLocationEnabled()
        )

        line(
            "Location fine",
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        )
        line(
            "Location coarse",
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            line(
                "Location background",
                hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            line(
                "Bluetooth connect",
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
            )
            line(
                "Bluetooth scan",
                hasPermission(Manifest.permission.BLUETOOTH_SCAN)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            line(
                "Nearby Wi‑Fi devices",
                hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES)
            )
        }

        val ignoringBattery = BatteryOptimizationCompat.isIgnoringBatteryOptimizations(this@SupportActivity)
        val backgroundRestricted = BatteryOptimizationCompat.isBackgroundRestricted(this@SupportActivity)
        val batteryEffectivelyOk = BatteryOptimizationCompat.isEffectivelyOk(this@SupportActivity)
        val batteryMaxConfirmed = BatteryOptimizationCompat.isUserConfirmedMaxAvailable(this@SupportActivity)
        line(
            "Ignore battery optimizations",
            ignoringBattery
        )
        line(
            "Background restricted",
            backgroundRestricted
        )
        line(
            "Battery effectively OK",
            batteryEffectivelyOk
        )
        line(
            "Battery max confirmed",
            batteryMaxConfirmed
        )
        line(
            "Exact alarms allowed",
            canScheduleExactAlarmsCompat()
        )

        section("Play Integrity")
        val integrity = PlayIntegrityRuntime.snapshot(this@SupportActivity)
        line(
            "Soft checks enabled",
            integrity.enabled
        )
        line(
            "SDK available",
            integrity.sdkAvailable
        )
        line(
            "Last status",
            integrity.lastStatus
        )
        line(
            "Last reason",
            integrity.lastReason
        )
        line(
            "Last request",
            formatDateTime(integrity.lastRequestMs)
        )
        line(
            "Last success",
            formatDateTime(integrity.lastSuccessMs)
        )
        line(
            "Last token length",
            integrity.lastTokenLength
        )
        line(
            "Last error",
            integrity.lastError.ifBlank { "-" }
        )

        section("Schedules")
        val schedules = runCatching { ScheduleStore.getAll(this@SupportActivity) }.getOrDefault(emptyList())
        line(
            "Schedules stored",
            schedules.size
        )
        line(
            "Schedules enabled",
            schedules.count { it.enabled }
        )

        val nextBoundary = SchedulePlanner.getNextBoundaryMillis(this@SupportActivity)
        line(
            "Next boundary",
            if (nextBoundary > 0L) formatDateTime(nextBoundary) else "-"
        )
        line(
            "Next boundary in ms",
            if (nextBoundary > 0L) (nextBoundary - nowMs).coerceAtLeast(0L) else 0
        )

        line(
            "Last schedule tick",
            formatDateTime(ScheduleRuntimeStore.getLastTickMs(this@SupportActivity))
        )
        line(
            "Last schedule execution",
            formatDateTime(ScheduleRuntimeStore.getLastExecutionMs(this@SupportActivity))
        )
        line(
            "Last schedule blocked by NFC",
            formatDateTime(ScheduleRuntimeStore.getLastDisableBlockedByNfcMs(this@SupportActivity))
        )

        line(
            "Range E→D active",
            ScheduleRuntimeStore.hadEnableAndDisable(this@SupportActivity)
        )
        line(
            "Range D→E active",
            ScheduleRuntimeStore.hadDisableAndEnable(this@SupportActivity)
        )
        line(
            "Schedule manual override",
            ScheduleRuntimeStore.isManualOverrideActive(this@SupportActivity)
        )

        line(
            "Schedule executions today",
            runCatching { ScheduleExecutionCountStore.getToday(this@SupportActivity) }.getOrDefault(0)
        )
        line(
            "Schedule executions last 7d",
            runCatching { ScheduleExecutionCountStore.getForLastNDays(this@SupportActivity, 7) }.getOrDefault(0)
        )
        line(
            "Schedule enables today",
            runCatching { SwitchlyActionCountStore.getToday(this@SupportActivity, SwitchlyActionCountStore.Action.SCHEDULE_ENABLE) }.getOrDefault(0)
        )
        line(
            "Schedule disables today",
            runCatching { SwitchlyActionCountStore.getToday(this@SupportActivity, SwitchlyActionCountStore.Action.SCHEDULE_DISABLE) }.getOrDefault(0)
        )

        if (includeSetupDetails) {
            section("Setup details")
            line(
                "Setup details included",
                true
            )
            line(
                "Setup privacy note",
                "Contains profile names, schedule times, trigger labels, and rule counts"
            )

            line(
                "Current day",
                dayName(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))
            )
            line(
                "Current minutes",
                minutesOfDay(Calendar.getInstance())
            )

            append("\nProfiles:\n")
            if (profiles.isEmpty()) {
                append("- none\n")
            } else {
                profiles.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { profile ->
                    val modeForProfile = ProfileRuleModeStore.getMode(this@SupportActivity, profile)
                    val websiteModeForProfile = WebsiteRuleModeStore.getMode(this@SupportActivity, profile)
                    val blocked = runCatching { ProfileStore.getBlockedForProfile(this@SupportActivity, profile).size }.getOrDefault(0)
                    val allowed = runCatching { ProfileStore.getAllowedForProfile(this@SupportActivity, profile).size }.getOrDefault(0)
                    val selected = runCatching { ProfileStore.getSelectedForProfileMode(this@SupportActivity, profile).size }.getOrDefault(0)
                    val websites = runCatching { DomainBlockStore.getDomainsForProfile(this@SupportActivity, profile).size }.getOrDefault(0)
                    val allowedWebsites = runCatching { DomainBlockStore.getAllowedDomainsForProfile(this@SupportActivity, profile).size }.getOrDefault(0)
                    val inAppPackages = runCatching { InAppRuleStore.getPackagesWithEnabledRules(this@SupportActivity, profile).size }.getOrDefault(0)

                    append("- ")
                    append(if (profile == currentProfile) "* " else "")
                    append(profile)
                    append(" | appMode=").append(modeForProfile)
                    append(" | websiteMode=").append(websiteModeForProfile)
                    append(" | selectedApps=").append(selected)
                    append(" | blockedApps=").append(blocked)
                    append(" | allowedApps=").append(allowed)
                    append(" | websiteRules=").append(websites)
                    append(" | allowedWebsiteRules=").append(allowedWebsites)
                    append(" | inAppRulePackages=").append(inAppPackages)
                    append(" | autoBlockNewApps=").append(ProfileStore.isAutoBlockNewAppsEnabled(this@SupportActivity, profile))
                    append("\n")
                }
            }

            append("\nSchedules:\n")
            if (schedules.isEmpty()) {
                append("- none\n")
            } else {
                schedules.sortedWith(compareBy<ScheduleStore.Schedule> { !it.enabled }.thenBy { it.startMinutes }.thenBy { it.id })
                    .forEach { schedule ->
                        append("- #").append(schedule.id)
                        append(" | enabled=").append(schedule.enabled)
                        append(" | activeNow=").append(isScheduleActiveNow(schedule))
                        append(" | profile=").append(schedule.profile.ifBlank { "-" })
                        append(" | action=").append(schedule.action.name)
                        append(" | type=").append(schedule.type.name)
                        append(" | title=").append(schedule.title.ifBlank { "-" })
                        append(" | days=").append(daysMaskSummary(schedule.daysMask))
                        append(" | time=").append(formatMinutes(schedule.startMinutes)).append("-").append(formatMinutes(schedule.endMinutes))
                        append(" | date=").append(formatScheduleDate(schedule.startDate)).append("-").append(formatScheduleDate(schedule.endDate))
                        append(" | trigger=").append(scheduleTriggerSummary(schedule))
                        append("\n")
                    }
            }

            val overlaps = ScheduleInsights.detectOverlaps(schedules)
            append("\nSchedule overlaps:\n")
            if (overlaps.isEmpty()) {
                append("- none\n")
            } else {
                overlaps.take(10).forEach { overlap ->
                    append("- #").append(overlap.first.id)
                    append(" ").append(ScheduleInsights.scheduleDisplayName(overlap.first))
                    append(" overlaps #").append(overlap.second.id)
                    append(" ").append(ScheduleInsights.scheduleDisplayName(overlap.second))
                    append(" | profiles=")
                    append(overlap.first.profile.ifBlank { "-" })
                    append(" -> ")
                    append(overlap.second.profile.ifBlank { "-" })
                    append("\n")
                }
            }
        }

        section("Runtime")
        line(
            "Watcher runtime today ms",
            runCatching { SwitchlyRuntimeStore.getRuntimeMsToday(this@SupportActivity) }.getOrDefault(0L)
        )
        line(
            "Watcher runtime last 7d ms",
            runCatching { SwitchlyRuntimeStore.getRuntimeMsForLastNDays(this@SupportActivity, 7) }.getOrDefault(0L)
        )

        val runtimeDiagnostics = BlockingRuntime.getRuntimeDiagnostics(this@SupportActivity)
        line(
            "Accessibility enabled in settings",
            runtimeDiagnostics.accessibilityEnabledInSettings
        )
        line(
            "Accessibility active heartbeat",
            runtimeDiagnostics.accessibilityActive
        )
        line(
            "Accessibility heartbeat age ms",
            runtimeDiagnostics.heartbeatAgeMs
        )
        line(
            "Last accessibility event",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastEventWallMs,
                runtimeDiagnostics.lastEventPackage,
                "type=${runtimeDiagnostics.lastEventType}"
            )
        )
        line(
            "Last foreground package",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastForegroundWallMs,
                runtimeDiagnostics.lastForegroundPackage,
                runtimeDiagnostics.lastForegroundSource
            )
        )
        line(
            "Last usage-events foreground",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastUsageResolveWallMs,
                runtimeDiagnostics.lastUsageResolvePackage,
                runtimeDiagnostics.lastUsageResolveSource
            )
        )
        line(
            "Last blocking check",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastBlockCheckWallMs,
                runtimeDiagnostics.lastBlockCheckPackage,
                listOf(
                    runtimeDiagnostics.lastBlockCheckReason,
                    runtimeDiagnostics.lastBlockCheckDetails
                )
                    .filter { it.isNotBlank() }
                    .joinToString(" | ")
            )
        )
        line(
            "Last block shown",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastBlockShownWallMs,
                runtimeDiagnostics.lastBlockShownPackage,
                runtimeDiagnostics.lastBlockShownDetails
            )
        )
        line(
            "Last blocker launch",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastBlockerLaunchWallMs,
                runtimeDiagnostics.lastBlockerLaunchPackage,
                runtimeDiagnostics.lastBlockerLaunchDetails
            )
        )
        line(
            "Last blocker verify",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastBlockerVerifyWallMs,
                runtimeDiagnostics.lastBlockerVerifyPackage,
                runtimeDiagnostics.lastBlockerVerifyDetails
            )
        )
        line(
            "Last blocker activity",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastBlockerActivityWallMs,
                runtimeDiagnostics.lastBlockerActivityPackage,
                runtimeDiagnostics.lastBlockerActivityDetails
            )
        )
        line(
            "Last multi-window fallback",
            formatRuntimeDiagnostic(
                runtimeDiagnostics.lastMultiWindowBlockWallMs,
                runtimeDiagnostics.lastMultiWindowBlockPackage,
                runtimeDiagnostics.lastMultiWindowBlockDetails
            )
        )

        val inboxEvents = runCatching { BlockedInboxStore.getAll(this@SupportActivity) }.getOrDefault(emptyList())
        line(
            "Blocked inbox events stored",
            inboxEvents.size
        )

        if (includeAdvanced) {
            section("Advanced diagnostics")

            val switchlyAll = switchlyPrefs.all
            val defaultAll = defaultSp.all

            fun countKeys(prefix: String): Int = switchlyAll.keys.count { it.startsWith(prefix) }
            fun countDefaultKeys(prefix: String): Int = defaultAll.keys.count { it.startsWith(prefix) }

            line(
                "switchly_prefs key count",
                switchlyAll.size
            )
            line(
                "default prefs key count",
                defaultAll.size
            )
            line(
                "blocked_count_* keys",
                countKeys("blocked_count_")
            )
            line(
                "blocked_attempt_* keys",
                countKeys("blocked_attempt_")
            )
            line(
                "profile_rule_mode__ keys",
                countKeys("profile_rule_mode__")
            )
            line(
                "profile_website_rule_mode__ keys",
                countKeys("profile_website_rule_mode__")
            )
            line(
                "allowed_apps_* keys",
                countKeys("allowed_apps_")
            )
            line(
                "session_limit_min__ keys",
                countKeys("session_limit_min__")
            )
            line(
                "attempt_limit__ keys",
                countKeys("attempt_limit__")
            )
            line(
                "domain_block_domains__p__ keys",
                countDefaultKeys("domain_block_domains__p__")
            )
            line(
                "domain_allowed_domains__p__ keys",
                countDefaultKeys("domain_allowed_domains__p__")
            )
            line(
                "inapp_limit_min__ keys",
                countKeys("inapp_limit_min__")
            )
            line(
                "surf_rule__ keys",
                countKeys("surf_rule__")
            )
            line(
                "home_custom_* keys",
                countDefaultKeys("home_custom_")
            )
            line(
                "schedule_exec_count_* keys",
                countKeys("schedule_exec_count_")
            )
            line(
                "nfc_scan_count_* keys",
                countKeys("nfc_scan_count_")
            )
            line(
                "runtime day keys",
                countKeys("switchly_runtime_ms_")
            )
            line(
                "surface usage keys",
                countKeys("surface_usage_")
            )

            val sampleProfiles = runCatching { ProfileStore.getProfiles(this@SupportActivity).take(6).joinToString(", ") }
                .getOrDefault("-")
            line(
                "Profile names sample",
                if (sampleProfiles.isBlank()) "-" else sampleProfiles
            )
        }

        section("Recent context")
        val lastBlock = LastBlockReasonStore.snapshot(this@SupportActivity)
        line(
            "Last block package",
            lastBlock?.pkg ?: "-"
        )
        line(
            "Last block profile",
            lastBlock?.profile ?: "-"
        )
        line(
            "Last block rule",
            lastBlock?.rule ?: "-"
        )
        line(
            "Last block source",
            lastBlock?.source ?: "-"
        )
        line(
            "Last block matched",
            lastBlock?.matched ?: "-"
        )
        line(
            "Last block result",
            lastBlock?.result ?: "-"
        )
        line(
            "Last block time",
            formatDateTime(lastBlock?.timeMillis ?: 0L)
        )
        val latestLines = AppLogStore.latestLines(this@SupportActivity, 250)
        line(
            "Last NFC/QR/barcode action",
            latestLines.lastOrNull { it.contains("[NFC]") || it.contains("[QR]") || it.contains("[Barcode]") } ?: "-"
        )
        line(
            "Last schedule event",
            latestLines.lastOrNull { it.contains("[Schedule]") } ?: "-"
        )
        append("Recent activity:\n")
        val recentActivity = runCatching { ActivityHistoryRepository.recentEntries(this@SupportActivity, days = 7, limit = 8) }.getOrDefault(emptyList())
        if (recentActivity.isEmpty()) {
            append("- none\n")
        } else {
            recentActivity.forEach { entry ->
                append("- ")
                    .append(formatDateTime(entry.timeMillis))
                    .append(" | ")
                    .append(entry.title)
                    .append(" | ")
                    .append(entry.summary.replace('\n', ' '))
                    .append("\n")
            }
        }
    }

    private fun minutesOfDay(calendar: Calendar): Int {
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    private fun isScheduleActiveNow(schedule: ScheduleStore.Schedule): Boolean {
        if (!schedule.enabled) {
            return false
        }
        val now = Calendar.getInstance()
        val today = ScheduleStore.todayYmd()

        if (schedule.type == ScheduleStore.Type.ONE_TIME) {
            if (today < schedule.startDate || today > schedule.endDate) {
                return false
            }
        } else {
            val mask = ScheduleStore.Days.fromCalendarDay(now.get(Calendar.DAY_OF_WEEK))
            if ((schedule.daysMask and mask) == 0) {
                return false
            }
        }

        val current = minutesOfDay(now)
        return if (schedule.startMinutes <= schedule.endMinutes) {
            current in schedule.startMinutes until schedule.endMinutes
        } else {
            current >= schedule.startMinutes || current < schedule.endMinutes
        }
    }

    private fun daysMaskSummary(mask: Int): String {
        if (mask == 0) {
            return "-"
        }
        val days = listOf(
            ScheduleStore.Days.MON to "Mon",
            ScheduleStore.Days.TUE to "Tue",
            ScheduleStore.Days.WED to "Wed",
            ScheduleStore.Days.THU to "Thu",
            ScheduleStore.Days.FRI to "Fri",
            ScheduleStore.Days.SAT to "Sat",
            ScheduleStore.Days.SUN to "Sun"
        )
        return days.filter { (bit, _) -> (mask and bit) != 0 }
            .joinToString(",") { (_, label) -> label }
            .ifBlank { "-" }
    }

    private fun dayName(dayOfWeek: Int): String {
        return when (dayOfWeek) {
            Calendar.MONDAY -> "Mon"
            Calendar.TUESDAY -> "Tue"
            Calendar.WEDNESDAY -> "Wed"
            Calendar.THURSDAY -> "Thu"
            Calendar.FRIDAY -> "Fri"
            Calendar.SATURDAY -> "Sat"
            Calendar.SUNDAY -> "Sun"
            else -> "-"
        }
    }

    private fun formatMinutes(minutes: Int): String {
        val safe = minutes.coerceIn(0, 24 * 60)
        return "%02d:%02d".format(Locale.US, safe / 60, safe % 60)
    }

    private fun formatScheduleDate(ymd: Int): String {
        if (ymd <= 0) {
            return "-"
        }
        val y = ymd / 10000
        val m = (ymd / 100) % 100
        val d = ymd % 100
        return "%04d-%02d-%02d".format(Locale.US, y, m, d)
    }

    private fun scheduleTriggerSummary(schedule: ScheduleStore.Schedule): String {
        val parts = mutableListOf<String>()
        schedule.wifiSsid?.takeIf { it.isNotBlank() }?.let { parts += "wifi=$it" }
        schedule.btDeviceName?.takeIf { it.isNotBlank() }?.let { parts += "btName=$it" }
        schedule.btDeviceAddress?.takeIf { it.isNotBlank() }?.let { parts += "btAddress=$it" }
        schedule.locationLabel?.takeIf { it.isNotBlank() }?.let { parts += "location=$it" }
        schedule.locationTrigger?.let { parts += "locationTrigger=${it.name}" }
        if (schedule.locationLat != null && schedule.locationLng != null) {
            parts += "locationRadius=${schedule.locationRadiusMeters}m"
        }
        return parts.joinToString("; ").ifBlank { "time" }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as? android.location.LocationManager ?: return false
        return runCatching {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
    }

    private fun formatRuntimeDiagnostic(timeMs: Long, value: String, details: String = ""): String {
        if (timeMs <= 0L && value.isBlank() && details.isBlank()) {
            return "-"
        }
        return buildString {
            append(formatDateTime(timeMs))
            if (value.isNotBlank()) {
                append(" | ").append(value)
            }
            if (details.isNotBlank()) {
                append(" | ").append(details)
            }
        }
    }

    private fun canScheduleExactAlarmsCompat(): Boolean {
        val am = getSystemService(AlarmManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }
    }

    // German date format: DD-MM-YYYY (use calendar year 'yyyy' in pattern)
    private fun formatDateTime(ms: Long): String {
        if (ms <= 0L) {
            return "-"
        }
        return runCatching {
            SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.GERMANY).format(Date(ms))
        }.getOrDefault("-")
    }

    private fun toolbarForegroundColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            Color.WHITE
        } else {
            Color.BLACK
        }
    }
}
