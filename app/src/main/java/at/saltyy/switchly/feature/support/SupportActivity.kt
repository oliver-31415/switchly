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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AdvancedModeStore
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.BlockedInboxStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.NfcScanCountStore
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleExecutionCountStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleRuntimeStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.SwitchlyRuntimeStore
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.receiver.DPMReceiver
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.BatteryOptimizationCompat
import at.saltyy.switchly.util.SystemBarColorCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SupportActivity : AppCompatActivity() {

    private companion object {
        private const val KEY_INCLUDE_DEBUG = "support_include_debug"
        private const val KEY_INCLUDE_ADVANCED_DEBUG = "support_include_advanced_debug"
        private const val KEY_INCLUDE_LOGS = "support_include_logs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_support)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        SystemBarColorCompat.setStatusBarColor(window, getColor(android.R.color.black))
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        val email = getString(R.string.about_mail_address)
        findViewById<TextView>(R.id.tvSupportEmail).text = email

        findViewById<ImageButton>(R.id.btnCopyEmailInline).setOnClickListener {
            copyToClipboard(label = getString(R.string.support_copy_email), text = email)
            Toast.makeText(this, getString(R.string.support_copied), Toast.LENGTH_SHORT).show()
        }

        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val includeDebug = findViewById<SwitchMaterial>(R.id.switchIncludeDebug)
        val includeAdvancedDebug = findViewById<SwitchMaterial>(R.id.switchIncludeAdvancedDebug)
        val includeLogs = findViewById<SwitchMaterial>(R.id.switchIncludeLogs)

        includeDebug.isChecked = sp.getBoolean(KEY_INCLUDE_DEBUG, true)
        includeAdvancedDebug.isChecked = sp.getBoolean(KEY_INCLUDE_ADVANCED_DEBUG, false)
        includeLogs.isChecked = sp.getBoolean(KEY_INCLUDE_LOGS, false)

        fun syncAdvancedState() {
            val enabled = includeDebug.isChecked
            includeAdvancedDebug.isEnabled = enabled
            includeAdvancedDebug.alpha = if (enabled) 1f else 0.55f
        }

        includeDebug.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_INCLUDE_DEBUG, isChecked) }
            syncAdvancedState()
        }

        includeAdvancedDebug.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_INCLUDE_ADVANCED_DEBUG, isChecked) }
        }

        includeLogs.setOnCheckedChangeListener { _, isChecked ->
            sp.edit { putBoolean(KEY_INCLUDE_LOGS, isChecked) }
        }

        syncAdvancedState()

        findViewById<MaterialButton>(R.id.btnCopyEmail).setOnClickListener {
            val payload = AppLogStore.export(this@SupportActivity)
            copyToClipboard(label = getString(R.string.support_copy_latest_logs), text = payload)
            Toast.makeText(this, getString(R.string.support_logs_copied), Toast.LENGTH_SHORT).show()
        }

        findViewById<MaterialButton>(R.id.btnOpenEmail).setOnClickListener {
            val subject = getString(R.string.support_email_subject)
            val debugBody = if (includeDebug.isChecked) {
                buildDebugInfo(includeAdvanced = includeAdvancedDebug.isChecked)
            } else {
                ""
            }
            val logsBody = if (includeLogs.isChecked) {
                AppLogStore.export(this@SupportActivity).trim()
            } else {
                ""
            }
            val body = buildString {
                if (debugBody.isNotBlank()) append(debugBody.trim())

                if (logsBody.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("-----\n")
                    append(getString(R.string.support_latest_logs_heading))
                    append("\n-----\n")
                    append(logsBody)
                }
            }

            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = "mailto:".toUri()
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                if (body.isNotBlank()) putExtra(Intent.EXTRA_TEXT, body)
            }

            // Prefer an email client.
            runCatching { startActivity(Intent.createChooser(intent, getString(R.string.support_open_email))) }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun buildDebugInfo(includeAdvanced: Boolean): String = buildString {
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

        line("Timestamp", formatDateTime(nowMs))
        line("Locale", Locale.getDefault().toLanguageTag())
        line("Timezone", TimeZone.getDefault().id)

        section("App")
        line("App", "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        line("Build type", BuildConfig.BUILD_TYPE)
        line("Package", packageName)

        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            line("First install", formatDateTime(info.firstInstallTime))
            line("Last update", formatDateTime(info.lastUpdateTime))
        }.onFailure {
            line("First install", "-")
            line("Last update", "-")
        }

        section("Device")
        line("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
        line("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } ?: "-"
        line("Security patch", securityPatch)

        section("Switchly state")
        val currentProfile = runCatching { ProfileStore.getCurrent(this@SupportActivity) }.getOrNull().orEmpty()
        val blockedCurrentProfile = if (currentProfile.isNotBlank()) {
            runCatching { ProfileStore.getBlockedForProfile(this@SupportActivity, currentProfile).size }.getOrDefault(0)
        } else {
            0
        }

        val mode = runCatching { AutomationModeStore.getMode(this@SupportActivity) }
            .getOrDefault(AutomationModeStore.Mode.MIXED)
        val defaultSp = PreferenceManager.getDefaultSharedPreferences(this@SupportActivity)

        line("Premium", PremiumManager.isPremium(this@SupportActivity))
        line("Switchly enabled", SwitchModeStore.isEnabled(this@SupportActivity))
        line("Switchly base enabled", SwitchModeStore.isBaseEnabled(this@SupportActivity))
        line("Mode", mode.raw)

        line("Profiles count", runCatching { ProfileStore.getProfiles(this@SupportActivity).size }.getOrDefault(0))
        line("Active profile", if (currentProfile.isBlank()) "-" else currentProfile)
        line("Blocked apps (active profile)", blockedCurrentProfile)

        line("NFC required to disable", SwitchModeStore.isNfcRequiredForDisable(this@SupportActivity))
        line("NFC paired tags", runCatching { NfcUidPairingStore.getPairedUidsHex(this@SupportActivity).size }.getOrDefault(0))
        line("NFC scans today", runCatching { NfcScanCountStore.getToday(this@SupportActivity) }.getOrDefault(0))
        line("NFC scans last 7d", runCatching { NfcScanCountStore.getForLastNDays(this@SupportActivity, 7) }.getOrDefault(0))

        line("Temp disable active", SwitchModeStore.getTemporaryRemainingMillis(this@SupportActivity) > 0L)
        line("Temp disable remaining ms", SwitchModeStore.getTemporaryRemainingMillis(this@SupportActivity))
        line("Temp enable active", SwitchModeStore.getTemporaryEnableRemainingMillis(this@SupportActivity) > 0L)
        line("Temp enable remaining ms", SwitchModeStore.getTemporaryEnableRemainingMillis(this@SupportActivity))

        line("Emergency feature enabled", EmergencyBypassStore.isFeatureEnabled(this@SupportActivity))
        line("Emergency active", EmergencyBypassStore.isActive(this@SupportActivity))
        line("Emergency paused", EmergencyBypassStore.isPaused(this@SupportActivity))
        line("Emergency minutes remaining", EmergencyBypassStore.minutesRemaining(this@SupportActivity))
        line("Emergency used today", EmergencyBypassStore.hasUsedToday(this@SupportActivity))

        line("Channel allowed: schedule", AutomationModeStore.isScheduleAllowed(this@SupportActivity))
        line("Channel allowed: nfc", AutomationModeStore.isNfcAllowed(this@SupportActivity))
        line("Channel allowed: qr(channel)", AutomationModeStore.isQrChannelAllowed(this@SupportActivity))
        line("Channel allowed: qr(effective)", AutomationModeStore.isQrAllowed(this@SupportActivity))
        line("Channel allowed: barcode(channel)", AutomationModeStore.isBarcodeChannelAllowed(this@SupportActivity))
        line("Channel allowed: barcode(effective)", AutomationModeStore.isBarcodeAllowed(this@SupportActivity))
        line("Channel allowed: tile", AutomationModeStore.isTileAllowed(this@SupportActivity))
        line("Channel allowed: button", AutomationModeStore.isButtonAllowed(this@SupportActivity))
        line("Button enable allowed", AutomationModeStore.isButtonEnableAllowed(this@SupportActivity))
        line("Button can enable (effective)", AutomationModeStore.canButtonEnable(this@SupportActivity))

        line("Allowed while enabled: app picker", AutomationModeStore.isAppPickerAllowedWhileEnabled(this@SupportActivity))
        line("Allowed while enabled: profile switching", AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(this@SupportActivity))
        line("Allowed while enabled: schedule editing", AutomationModeStore.isScheduleEditingAllowedWhileEnabled(this@SupportActivity))
        line("Allowed while enabled: NFC tag writing", AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(this@SupportActivity))
        line("Lock Switchly app access", AutomationModeStore.isSwitchlyAppAccessLockEnabled(this@SupportActivity))
        line("Uninstall friction", AutomationModeStore.isUninstallFrictionEnabled(this@SupportActivity))
        line("Advanced mode", AdvancedModeStore.isEnabled(this@SupportActivity))
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val adminComponent = ComponentName(this@SupportActivity, DPMReceiver::class.java)
        line("Device admin active", dpm?.isAdminActive(adminComponent) == true)
        line("Profile owner active", dpm?.isProfileOwnerApp(packageName) == true)
        line("Device owner active", dpm?.isDeviceOwnerApp(packageName) == true)

        if (mode == AutomationModeStore.Mode.MIXED) {
            line("Mixed toggle: schedule", AutomationModeStore.isMixedAllowSchedule(this@SupportActivity))
            line("Mixed toggle: nfc", AutomationModeStore.isMixedAllowNfc(this@SupportActivity))
            line("Mixed toggle: qr", AutomationModeStore.isMixedAllowQr(this@SupportActivity))
            line("Mixed toggle: barcode", AutomationModeStore.isMixedAllowBarcode(this@SupportActivity))
            line("Mixed toggle: tile", AutomationModeStore.isMixedAllowTile(this@SupportActivity))
            line("Mixed toggle: button", AutomationModeStore.isMixedAllowButton(this@SupportActivity))
            line("Mixed toggle: app picking", AutomationModeStore.isMixedAllowAppPicking(this@SupportActivity))
            line("Mixed toggle: profile switching", AutomationModeStore.isMixedAllowProfileSwitching(this@SupportActivity))
            line("Mixed toggle: schedule editing", AutomationModeStore.isMixedAllowScheduleEditing(this@SupportActivity))
            line("Mixed toggle: NFC tag writing", AutomationModeStore.isMixedAllowNfcTagWriting(this@SupportActivity))
        }

        line("Quick actions visible", defaultSp.getBoolean("pref_show_quick_actions", true))
        line("Quick actions expanded", defaultSp.getBoolean("home_quick_actions_expanded", true))
        line("QR tools visible", AutomationModeStore.shouldShowQrTools(this@SupportActivity))
        line("Barcode tools visible", AutomationModeStore.shouldShowBarcodeTools(this@SupportActivity))

        section("Permissions")
        val notificationsEnabled = NotificationManagerCompat.from(this@SupportActivity).areNotificationsEnabled()
        val postNotifsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }

        line("Notifications enabled", notificationsEnabled)
        line("POST_NOTIFICATIONS granted", postNotifsGranted)
        line("Notification listener access", NotificationBlockStore.hasListenerAccess(this@SupportActivity))
        line("Notification blocking enabled", NotificationBlockStore.isEnabled(this@SupportActivity))

        line("Accessibility enabled", BlockingRuntime.isAccessibilityActive(this@SupportActivity))
        line("Usage access", UsageStatsRepo.hasUsageAccess(this@SupportActivity))
        line("Location services enabled", isLocationEnabled())

        line("Location fine", hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        line("Location coarse", hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            line("Location background", hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            line("Bluetooth connect", hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
            line("Bluetooth scan", hasPermission(Manifest.permission.BLUETOOTH_SCAN))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            line("Nearby Wi‑Fi devices", hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES))
        }

        val ignoringBattery = BatteryOptimizationCompat.isIgnoringBatteryOptimizations(this@SupportActivity)
        val backgroundRestricted = BatteryOptimizationCompat.isBackgroundRestricted(this@SupportActivity)
        val batteryEffectivelyOk = BatteryOptimizationCompat.isEffectivelyOk(this@SupportActivity)
        val batteryMaxConfirmed = BatteryOptimizationCompat.isUserConfirmedMaxAvailable(this@SupportActivity)
        line("Ignore battery optimizations", ignoringBattery)
        line("Background restricted", backgroundRestricted)
        line("Battery effectively OK", batteryEffectivelyOk)
        line("Battery max confirmed", batteryMaxConfirmed)
        line("Exact alarms allowed", canScheduleExactAlarmsCompat())

        section("Schedules")
        val schedules = runCatching { ScheduleStore.getAll(this@SupportActivity) }.getOrDefault(emptyList())
        line("Schedules stored", schedules.size)
        line("Schedules enabled", schedules.count { it.enabled })

        val nextBoundary = SchedulePlanner.getNextBoundaryMillis(this@SupportActivity)
        line("Next boundary", if (nextBoundary > 0L) formatDateTime(nextBoundary) else "-")
        line("Next boundary in ms", if (nextBoundary > 0L) (nextBoundary - nowMs).coerceAtLeast(0L) else 0)

        line("Last schedule tick", formatDateTime(ScheduleRuntimeStore.getLastTickMs(this@SupportActivity)))
        line("Last schedule execution", formatDateTime(ScheduleRuntimeStore.getLastExecutionMs(this@SupportActivity)))
        line("Last schedule blocked by NFC", formatDateTime(ScheduleRuntimeStore.getLastDisableBlockedByNfcMs(this@SupportActivity)))

        line("Range E→D active", ScheduleRuntimeStore.hadEnableAndDisable(this@SupportActivity))
        line("Range D→E active", ScheduleRuntimeStore.hadDisableAndEnable(this@SupportActivity))
        line("Schedule manual override", ScheduleRuntimeStore.isManualOverrideActive(this@SupportActivity))

        line("Schedule executions today", runCatching { ScheduleExecutionCountStore.getToday(this@SupportActivity) }.getOrDefault(0))
        line("Schedule executions last 7d", runCatching { ScheduleExecutionCountStore.getForLastNDays(this@SupportActivity, 7) }.getOrDefault(0))

        section("Runtime")
        line("Watcher runtime today ms", runCatching { SwitchlyRuntimeStore.getRuntimeMsToday(this@SupportActivity) }.getOrDefault(0L))
        line("Watcher runtime last 7d ms", runCatching { SwitchlyRuntimeStore.getRuntimeMsForLastNDays(this@SupportActivity, 7) }.getOrDefault(0L))

        val inboxEvents = runCatching { BlockedInboxStore.getAll(this@SupportActivity) }.getOrDefault(emptyList())
        line("Blocked inbox events stored", inboxEvents.size)

        if (includeAdvanced) {
            section("Advanced diagnostics")

            val switchlyPrefs = getSharedPreferences("switchly_prefs", MODE_PRIVATE)
            val switchlyAll = switchlyPrefs.all
            val defaultAll = defaultSp.all

            fun countKeys(prefix: String): Int = switchlyAll.keys.count { it.startsWith(prefix) }

            line("switchly_prefs key count", switchlyAll.size)
            line("default prefs key count", defaultAll.size)
            line("blocked_count_* keys", countKeys("blocked_count_"))
            line("blocked_attempt_* keys", countKeys("blocked_attempt_"))
            line("schedule_exec_count_* keys", countKeys("schedule_exec_count_"))
            line("nfc_scan_count_* keys", countKeys("nfc_scan_count_"))
            line("runtime day keys", countKeys("switchly_runtime_ms_"))
            line("surface usage keys", countKeys("surface_usage_"))

            val sampleProfiles = runCatching { ProfileStore.getProfiles(this@SupportActivity).take(6).joinToString(", ") }
                .getOrDefault("-")
            line("Profile names sample", if (sampleProfiles.isBlank()) "-" else sampleProfiles)
        }
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
        if (ms <= 0L) return "-"
        return runCatching {
            SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.GERMANY).format(Date(ms))
        }.getOrDefault("-")
    }
}
