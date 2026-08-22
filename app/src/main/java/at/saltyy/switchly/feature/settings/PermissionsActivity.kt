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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppPreferences
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.NfcUidPairingStore
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.faq.FaqActivity
import at.saltyy.switchly.feature.usage.UsageStatsRepo
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.SwitchlyInfoRow
import at.saltyy.switchly.ui.dialog.showSwitchlyInfoDialog
import at.saltyy.switchly.util.BatteryOptimizationRequest
import at.saltyy.switchly.util.BatteryOptimizationCompat
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.NfcLaunchAccessCompat
import at.saltyy.switchly.util.PermissionSetupChecks
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Permissions overview screen.
 * Shows the current status of all required permissions/settings and provides:
 * - "Open" buttons to jump into the relevant system settings
 * - "Request" buttons for runtime permissions (only when missing)
 * - "Why" dialogs explaining why each permission is needed
 */
class PermissionsActivity : AppCompatActivity() {

    private enum class LocationState {
        OK,
        APPROX_ONLY,
        BACKGROUND_MISSING,
        NEARBY_WIFI_MISSING,
        MISSING
    }

    private lateinit var permissionStatusButton: AppCompatTextView

    private var lastMissingPermissionCount: Int = 0
    private var lastHasAccessibilityMismatch: Boolean = false

    private lateinit var tvNotificationsStatus: TextView
    private lateinit var tvNotificationAccessStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvUsageAccessStatus: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvExactAlarmsStatus: TextView
    private lateinit var tvNfcStatus: TextView

    private lateinit var btnWhyNotifications: View
    private lateinit var btnWhyNotificationAccess: View
    private lateinit var btnWhyAccessibility: View
    private lateinit var btnWhyUsageAccess: View
    private lateinit var btnWhyLocation: View
    private lateinit var btnWhyBluetooth: View
    private lateinit var btnWhyBattery: View
    private lateinit var btnWhyAutostart: View
    private lateinit var btnWhyExactAlarms: View
    private lateinit var btnWhyNfc: View

    private lateinit var btnOpenNotifications: MaterialButton
    private lateinit var btnOpenNotificationAccess: MaterialButton
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var btnOpenUsageAccess: MaterialButton
    private lateinit var btnOpenLocation: MaterialButton
    private lateinit var btnReqLocation: MaterialButton
    private lateinit var btnReqBluetooth: MaterialButton
    private lateinit var btnOpenBluetooth: MaterialButton
    private lateinit var btnReqBattery: MaterialButton
    private lateinit var btnOpenBattery: MaterialButton
    private lateinit var btnOpenAutostart: MaterialButton
    private lateinit var btnOpenExactAlarms: MaterialButton
    private lateinit var btnOpenNfc: MaterialButton

    private lateinit var groupAutostart: View
    private lateinit var tvAutostartHint: TextView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        val toolbarIconColor = toolbarForegroundColor()
        toolbar.navigationIcon?.mutate()?.setTint(toolbarIconColor)
        toolbar.setTitleTextColor(toolbarIconColor)

        setupPermissionStatusAction(toolbar)

        tvNotificationsStatus = findViewById(R.id.tvNotificationsStatus)
        tvNotificationAccessStatus = findViewById(R.id.tvNotificationAccessStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvUsageAccessStatus = findViewById(R.id.tvUsageAccessStatus)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvExactAlarmsStatus = findViewById(R.id.tvExactAlarmsStatus)
        tvNfcStatus = findViewById(R.id.tvNfcStatus)

        btnWhyNotifications = findViewById(R.id.btnWhyNotifications)
        btnWhyNotificationAccess = findViewById(R.id.btnWhyNotificationAccess)
        btnWhyAccessibility = findViewById(R.id.btnWhyAccessibility)
        btnWhyUsageAccess = findViewById(R.id.btnWhyUsageAccess)
        btnWhyLocation = findViewById(R.id.btnWhyLocation)
        btnWhyBluetooth = findViewById(R.id.btnWhyBluetooth)
        btnWhyBattery = findViewById(R.id.btnWhyBattery)
        btnWhyAutostart = findViewById(R.id.btnWhyAutostart)
        btnWhyExactAlarms = findViewById(R.id.btnWhyExactAlarms)
        btnWhyNfc = findViewById(R.id.btnWhyNfc)

        btnOpenNotifications = findViewById(R.id.btnOpenNotifications)
        btnOpenNotificationAccess = findViewById(R.id.btnOpenNotificationAccess)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnOpenUsageAccess = findViewById(R.id.btnOpenUsageAccess)
        btnOpenLocation = findViewById(R.id.btnOpenLocation)
        btnReqLocation = findViewById(R.id.btnReqLocation)
        btnReqBluetooth = findViewById(R.id.btnReqBluetooth)
        btnOpenBluetooth = findViewById(R.id.btnOpenBluetooth)
        btnReqBattery = findViewById(R.id.btnReqBattery)
        btnOpenBattery = findViewById(R.id.btnOpenBattery)
        btnOpenAutostart = findViewById(R.id.btnOpenAutostart)
        btnOpenExactAlarms = findViewById(R.id.btnOpenExactAlarms)
        btnOpenNfc = findViewById(R.id.btnOpenNfc)

        groupAutostart = findViewById(R.id.groupAutostart)
        tvAutostartHint = findViewById(R.id.tvAutostartHint)

        // OPEN buttons
        btnOpenNotifications.setOnClickListener {
            openOrRequestNotifications()
        }

        btnOpenNotificationAccess.setOnClickListener {
            if (!safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) {
                openAppDetails()
            }
        }

        btnOpenAccessibility.setOnClickListener {
            AccessibilityDisclosure.openSettingsWithDisclosure(this)
        }

        btnOpenUsageAccess.setOnClickListener {
            safeStart(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        btnOpenLocation.setOnClickListener {
            openLocationSettingsForApp()
        }

        btnOpenBluetooth.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasBluetoothPermission()) {
                requestBluetoothPermissionIfMissing()
            } else {
                openBluetoothSettingsOrAppDetails()
            }
        }

        btnOpenBattery.setOnClickListener {
            openBatteryOptimizationSettingsPages()
        }

        btnOpenAutostart.setOnClickListener {
            startActivity(
                FaqActivity.intent(
                    context = this,
                    category = FaqActivity.CATEGORY_BACKGROUND_ACCESS,
                    questionResId = R.string.faq_q_device_background_steps
                )
            )
        }

        btnOpenExactAlarms.setOnClickListener {
            openExactAlarmSettings()
        }

        btnOpenNfc.setOnClickListener {
            openNfcSettings()
        }

        btnReqLocation.setOnClickListener {
            requestLocationFlow()
        }

        btnReqBluetooth.setOnClickListener {
            requestBluetoothPermissionIfMissing()
        }

        btnReqBattery.setOnClickListener {
            requestIgnoreBatteryOptimizationsSystemPopup()
        }

        btnWhyNotifications.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_notifications_title),
                getString(R.string.permissions_notifications_desc)
            )
        }

        btnWhyNotificationAccess.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_notification_access_title),
                getString(R.string.permissions_notification_access_desc)
            )
        }

        btnWhyAccessibility.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_accessibility_title),
                getString(R.string.permissions_accessibility_desc)
            )
        }

        btnWhyUsageAccess.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_usage_access_title),
                getString(R.string.permissions_usage_access_desc)
            )
        }

        btnWhyLocation.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_location_title),
                getString(R.string.permissions_location_desc)
            )
        }

        btnWhyBluetooth.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_bluetooth_title),
                getString(R.string.permissions_bluetooth_desc)
            )
        }

        btnWhyBattery.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_battery_title),
                getString(R.string.permissions_battery_desc) + "\n\n" + getString(R.string.permissions_battery_oem_note)
            )
        }

        btnWhyAutostart.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_autostart_title),
                getString(R.string.permissions_autostart_desc)
            )
        }

        btnWhyExactAlarms.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_exact_alarms_title),
                getString(R.string.permissions_exact_alarms_summary) + "\n\n" + getString(R.string.permissions_exact_alarms_note)
            )
        }

        btnWhyNfc.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_nfc_title),
                getString(R.string.permissions_nfc_desc)
            )
        }

        updateUi()
        focusRequestedSection()

        if (intent.getBooleanExtra(EXTRA_SHOW_ACCESSIBILITY_DISCLOSURE, false)) {
            findViewById<View>(R.id.root).post {
                AccessibilityDisclosure.openSettingsWithDisclosure(this, forceShow = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CustomAccentApplier.applyIfNeeded(this)
        ExactAlarmPermissionSync.syncAndReschedule(this, reason = "permissions_resume")
        updateUi()
        lifecycleScope.launch {
            delay(350)
            updateUi()
            delay(900)
            updateUi()
        }
    }

    private fun updateUi(forceHeartbeat: Boolean = false) {
        val postNotifGranted = hasPostNotificationsPermission()
        val notificationsOk = PermissionSetupChecks.notificationsReady(
            this,
            requireListenerAccess = false
        )
        val notificationAccessGranted = NotificationBlockStore.hasListenerAccess(this)
        val notificationBlockingEnabled = NotificationBlockStore.isEnabled(this)

        val accessibilityRuntime = BlockingRuntime.isAccessibilityActive(this)
        val accessibilityDirect = BlockingRuntime.isAccessibilityEnabledInSettings(this)
        val accessibilityMismatchNow = accessibilityRuntime != accessibilityDirect
        val stickyAccessibilityMismatch = refreshStickyAccessibilityMismatch(accessibilityMismatchNow)
        val accessibilityEnabled = accessibilityRuntime

        val usageAccessOk = UsageStatsRepo.hasUsageAccess(this)
        val locationState = getLocationStateForWifi()
        val locationOk = locationState == LocationState.OK

        val btGranted = hasBluetoothPermission()

        val locationNeeded = ScheduleStore.hasEnabledWifiSchedules(this) || ScheduleStore.hasEnabledLocationSchedules(this)
        val bluetoothNeeded = ScheduleStore.hasEnabledBluetoothSchedules(this)

        val batteryOk = isBatteryOptimizationEffectivelyOk()
        val hasEnabledSchedules = ScheduleStore.getAll(this).any { it.enabled }
        val batteryRelevant = hasEnabledSchedules

        val exactAlarmsOk = canScheduleExactAlarms()
        val exactAlarmsRelevant = hasEnabledSchedules && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        val permissionsLocked = SwitchModeStore.isEnabled(this) && SwitchModeStore.isNfcRequiredForDisable(this)
        val nfcState = NfcLaunchAccessCompat.state(this)
        val nfcRelevant = AutomationModeStore.isNfcAllowed(this) ||
            runCatching { NfcUidPairingStore.getPairedUidsHex(this).isNotEmpty() }.getOrDefault(false)
        val nfcMissing = nfcRelevant && nfcState in setOf(
            NfcLaunchAccessCompat.State.NOT_ALLOWED,
            NfcLaunchAccessCompat.State.NFC_DISABLED
        )

        applyStatus(tvNotificationsStatus, notificationsOk)
        applyStatus(tvNotificationAccessStatus, notificationAccessGranted)
        applyStatus(tvAccessibilityStatus, accessibilityEnabled)
        applyStatus(tvUsageAccessStatus, usageAccessOk)

        btnOpenNotifications.text =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotifGranted) {
                getString(R.string.permissions_btn_allow)
            } else {
                getString(R.string.permissions_btn_open)
            }

        applyLocationStatus(locationState)
        applyStatus(tvBluetoothStatus, btGranted)

        applyBatteryStatus(tvBatteryStatus, batteryOk)
        applyExactAlarmsStatus(tvExactAlarmsStatus, exactAlarmsOk)
        applyNfcStatus(tvNfcStatus)

        // Location request button
        btnReqLocation.visibility = if (locationOk) View.GONE else View.VISIBLE
        btnReqLocation.text = when (locationState) {
            LocationState.MISSING -> getString(R.string.permissions_btn_set_permission)
            LocationState.APPROX_ONLY -> getString(R.string.permissions_btn_enable_precise)
            LocationState.BACKGROUND_MISSING -> getString(R.string.permissions_btn_enable_all_the_time)
            LocationState.NEARBY_WIFI_MISSING -> getString(R.string.permissions_btn_set_permission)
            LocationState.OK -> getString(R.string.permissions_status_enabled)
        }

        btnOpenLocation.visibility = View.VISIBLE

        // Bluetooth request button
        btnReqBluetooth.visibility = if (btGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) View.GONE else View.VISIBLE
        btnReqBluetooth.text = getString(R.string.permissions_btn_set_permission)
        btnOpenBluetooth.visibility = View.VISIBLE

        // Battery request button
        btnReqBattery.visibility = if (!batteryOk) View.VISIBLE else View.GONE
        btnOpenBattery.visibility = View.VISIBLE

        // Exact alarms button
        btnOpenExactAlarms.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) View.VISIBLE else View.GONE
        btnOpenExactAlarms.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        btnOpenExactAlarms.alpha = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 1f else 0.55f
        val nfcSupported = AutomationModeStore.isNfcSupported(this)
        btnOpenNfc.visibility = View.VISIBLE
        btnOpenNfc.isEnabled = nfcSupported
        btnOpenNfc.alpha = if (nfcSupported) 1f else 0.55f

        // OEM autostart
        val showOem = isLikelyAggressiveOem()
        groupAutostart.visibility = if (showOem) View.VISIBLE else View.GONE
        tvAutostartHint.visibility = if (showOem) View.VISIBLE else View.GONE
        btnOpenAutostart.visibility = if (showOem) View.VISIBLE else View.GONE

        applyProtectedButtonState(btnOpenAccessibility, permissionsLocked && accessibilityEnabled)
        applyProtectedButtonState(btnOpenUsageAccess, permissionsLocked && usageAccessOk)
        applyProtectedButtonState(
            btnOpenNotificationAccess,
            permissionsLocked && notificationAccessGranted
        )
        applyProtectedButtonState(btnOpenNotifications, permissionsLocked && notificationsOk)
        applyProtectedButtonState(btnOpenLocation, permissionsLocked && locationOk)
        applyProtectedButtonState(btnOpenBluetooth, permissionsLocked && btGranted)
        applyProtectedButtonState(btnOpenBattery, permissionsLocked && batteryOk)
        applyProtectedButtonState(btnOpenAutostart, permissionsLocked)
        applyProtectedButtonState(btnOpenExactAlarms, permissionsLocked && exactAlarmsOk)

        btnOpenNotifications.text =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotifGranted) {
                getString(R.string.permissions_btn_allow)
            } else {
                getString(R.string.permissions_btn_open)
            }

        val missingCount = listOf(
            (!accessibilityEnabled),
            (!usageAccessOk),
            !notificationsOk,
            (notificationBlockingEnabled && !notificationAccessGranted),
            (locationNeeded && !locationOk),
            (bluetoothNeeded && !btGranted),
            (batteryRelevant && !batteryOk),
            (exactAlarmsRelevant && !exactAlarmsOk),
            nfcMissing
        ).count { it }

        lastMissingPermissionCount = missingCount
        lastHasAccessibilityMismatch = stickyAccessibilityMismatch || accessibilityMismatchNow

        updateBanner(
            missingCount = missingCount,
            stickyAccessibilityMismatch = stickyAccessibilityMismatch || accessibilityMismatchNow
        )
        if (forceHeartbeat) {
            permissionStatusButton.animate().alpha(0.55f).setDuration(80L).withEndAction {
                permissionStatusButton.animate().alpha(1f).setDuration(140L).start()
            }.start()
        }
    }

    private fun refreshStickyAccessibilityMismatch(mismatchNow: Boolean): Boolean {
        val prefs = getSharedPreferences(PREFS_PERMISSION_HEALTH, MODE_PRIVATE)
        val wasSticky = prefs.getBoolean(KEY_STICKY_ACCESSIBILITY_MISMATCH, false)
        return if (mismatchNow) {
            if (!wasSticky) prefs.edit { putBoolean(KEY_STICKY_ACCESSIBILITY_MISMATCH, true) }
            true
        } else {
            if (wasSticky) prefs.edit { putBoolean(KEY_STICKY_ACCESSIBILITY_MISMATCH, false) }
            false
        }
    }

    private fun applyProtectedButtonState(button: MaterialButton, locked: Boolean) {
        button.isEnabled = !locked
        button.isClickable = !locked
        button.alpha = if (locked) 0.55f else 1f
    }

    private fun updateBanner(missingCount: Int, stickyAccessibilityMismatch: Boolean) {
        val hasIssues = missingCount > 0 || stickyAccessibilityMismatch
        permissionStatusButton.setText(
            if (hasIssues) R.string.permissions_toolbar_issues
            else R.string.ok
        )

        // Keep the toolbar action flat and consistently visible on every accent color.
        val contentColor = ContextCompat.getColor(this, android.R.color.white)
        val shield = ContextCompat.getDrawable(this, R.drawable.security_24)?.mutate()?.apply {
            setTint(contentColor)
        }
        permissionStatusButton.setCompoundDrawablesRelativeWithIntrinsicBounds(
            shield,
            null,
            null,
            null
        )
        permissionStatusButton.setTextColor(contentColor)
        permissionStatusButton.background = null
        permissionStatusButton.alpha = 1f
    }

    private fun setupPermissionStatusAction(toolbar: MaterialToolbar) {
        val horizontalPadding = (8 * resources.displayMetrics.density + 0.5f).toInt()
        val actionHeight = (48 * resources.displayMetrics.density + 0.5f).toInt()
        permissionStatusButton = AppCompatTextView(this).apply {
            minWidth = 0
            minHeight = actionHeight
            gravity = Gravity.CENTER
            isSingleLine = true
            includeFontPadding = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@PermissionsActivity, android.R.color.white))
            background = null
            compoundDrawablePadding =
                resources.getDimensionPixelSize(R.dimen.permission_toolbar_status_icon_padding)
            setPadding(horizontalPadding, 0, horizontalPadding, 0)
            contentDescription = getString(R.string.permissions_health_recheck)
            setOnClickListener {
                updateUi(forceHeartbeat = true)
                val message = when {
                    lastHasAccessibilityMismatch -> getString(R.string.permissions_health_rechecked_with_mismatch)
                    lastMissingPermissionCount > 0 -> resources.getQuantityString(
                        R.plurals.permissions_health_rechecked_missing,
                        lastMissingPermissionCount,
                        lastMissingPermissionCount
                    )
                    else -> getString(R.string.permissions_health_rechecked_all_good)
                }
                Toast.makeText(this@PermissionsActivity, message, Toast.LENGTH_SHORT).show()
            }
        }

        val toolbarSpacing = (2 * resources.displayMetrics.density + 0.5f).toInt()
        toolbar.addView(
            permissionStatusButton,
            Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                actionHeight
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = toolbarSpacing
            }
        )
    }

    private fun applyStatus(view: TextView, enabled: Boolean) {
        val green = ContextCompat.getColor(this, R.color.status_ok)
        val red = ContextCompat.getColor(this, R.color.status_error)

        view.text = getString(
            if (enabled) R.string.permissions_status_enabled
            else R.string.permissions_status_disabled
        )
        view.setTextColor(if (enabled) green else red)
    }

    private fun applyBatteryStatus(view: TextView, enabled: Boolean) {
        val green = ContextCompat.getColor(this, R.color.status_ok)
        val red = ContextCompat.getColor(this, R.color.status_error)

        val manuallyConfirmed = isBatteryOptimizationUserConfirmedMaxAvailable()
        view.text = when {
            enabled && manuallyConfirmed -> getString(R.string.permissions_battery_highest_available)
            enabled -> getString(R.string.permissions_battery_allowed)
            else -> getString(R.string.permissions_battery_not_allowed)
        }
        view.setTextColor(if (enabled) green else red)
    }

    private fun applyExactAlarmsStatus(view: TextView, enabled: Boolean) {
        val green = ContextCompat.getColor(this, R.color.status_ok)
        val red = ContextCompat.getColor(this, R.color.status_error)

        view.text = getString(
            if (enabled) R.string.permissions_exact_alarms_allowed
            else R.string.permissions_exact_alarms_not_allowed
        )
        view.setTextColor(if (enabled) green else red)
    }

    private fun applyNfcStatus(view: TextView) {
        if (!AutomationModeStore.isNfcSupported(this)) {
            view.text = getString(R.string.mode_not_supported_on_device)
            view.setTextColor(ContextCompat.getColor(this, R.color.status_neutral))
            return
        }
        when (NfcLaunchAccessCompat.state(this)) {
            NfcLaunchAccessCompat.State.ALLOWED -> applyStatus(view, true)
            NfcLaunchAccessCompat.State.NOT_ALLOWED -> {
                view.text = getString(R.string.permissions_nfc_status_not_allowed)
                view.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            }
            NfcLaunchAccessCompat.State.NFC_DISABLED -> {
                view.text = getString(R.string.permissions_nfc_status_system_disabled)
                view.setTextColor(ContextCompat.getColor(this, R.color.status_error))
            }
            NfcLaunchAccessCompat.State.UNKNOWN -> {
                view.text = getString(R.string.permissions_nfc_status_manual)
                view.setTextColor(AccentColor.getAccentColorInt(this))
            }
        }
    }

    private fun applyLocationStatus(state: LocationState) {
        when (state) {
            LocationState.OK -> applyStatus(tvLocationStatus, true)
            LocationState.MISSING -> applyStatus(tvLocationStatus, false)
            LocationState.APPROX_ONLY -> {
                val red = ContextCompat.getColor(this, R.color.status_error)
                tvLocationStatus.text = getString(R.string.permissions_status_location_approx)
                tvLocationStatus.setTextColor(red)
            }

            LocationState.BACKGROUND_MISSING -> {
                val red = ContextCompat.getColor(this, R.color.status_error)
                tvLocationStatus.text = getString(R.string.permissions_status_location_background_missing)
                tvLocationStatus.setTextColor(red)
            }

            LocationState.NEARBY_WIFI_MISSING -> {
                val red = ContextCompat.getColor(this, R.color.status_error)
                tvLocationStatus.text = getString(R.string.permissions_status_nearby_wifi_missing)
                tvLocationStatus.setTextColor(red)
            }
        }
    }

    // LOCATION
    private fun hasCoarseLocationPermission(): Boolean {
        return PermissionSetupChecks.hasCoarseLocation(this)
    }

    private fun hasFineLocationPermission(): Boolean {
        return PermissionSetupChecks.hasFineLocation(this)
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        return PermissionSetupChecks.hasBackgroundLocation(this)
    }

    /**
     * Wi‑Fi schedules need location permissions so the connected SSID/BSSID can be read reliably.
     * For best reliability on many devices, especially when triggers run while the app is not
     * in the foreground, we also guide users to enable "Allow all the time" (ACCESS_BACKGROUND_LOCATION) via a 2‑step flow.
     */
    private fun getLocationStateForWifi(): LocationState {
        val fine = hasFineLocationPermission()
        val coarse = hasCoarseLocationPermission()

        if (!fine) {
            return if (coarse) {
                LocationState.APPROX_ONLY
            } else {
                LocationState.MISSING
            }
        }

        // For best reliability, especially when Wi‑Fi triggers run in the background, guide users to "Allow all the time" (ACCESS_BACKGROUND_LOCATION).
        if (!hasBackgroundLocationPermission()) {
            return LocationState.BACKGROUND_MISSING
        }
        if (!PermissionSetupChecks.hasNearbyWifiDevices(this)) {
            return LocationState.NEARBY_WIFI_MISSING
        }
        return LocationState.OK
    }

    /**
     * Runtime request flow for Wi‑Fi schedules.
     * 1) Request ACCESS_FINE_LOCATION (Android popup: "While using the app")
     * 2) Request/guide to ACCESS_BACKGROUND_LOCATION ("Allow all the time")
     * 3) (Optional, Android 13+) Request NEARBY_WIFI_DEVICES
     */
    private fun requestLocationFlow() {
        // 1) Precise location
        if (!hasFineLocationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOC_FINE
            )
            return
        }

        // 2) Background location (Android 10+)
        if (!hasBackgroundLocationPermission()) {
            requestBackgroundLocationFlow()
            return
        }

        // Optional Android 13+ permission for some Wi‑Fi access paths
        if (Build.VERSION.SDK_INT >= 33) {
            if (!PermissionSetupChecks.hasNearbyWifiDevices(this)) {
                requestPermissions(
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                    REQ_NEARBY_WIFI
                )
                return
            }
        }

        updateUi()
    }

    private fun requestBackgroundLocationFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateUi()
            return
        }

        // Android 10: we can still request it directly.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            requestPermissions(
                arrayOf(ACCESS_BACKGROUND_LOCATION_PERMISSION),
                REQ_LOC_BACKGROUND
            )
            return
        }

        // Android 11+: requesting ACCESS_BACKGROUND_LOCATION will usually open the system permission controller where the user can switch to
        // "Allow all the time" for Location.
        // On some devices/ROMs this may still not show the exact location page, so we keep a settings fallback in onRequestPermissionsResult.
        requestPermissions(
            arrayOf(ACCESS_BACKGROUND_LOCATION_PERMISSION),
            REQ_LOC_BACKGROUND
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            // After "Request" -> optionally ask for NEARBY_WIFI_DEVICES
            REQ_LOC_FINE -> {
                if (hasFineLocationPermission()) {
                    // Continue the 2‑step flow: "While using" -> "All the time"
                    requestLocationFlow()
                } else {
                    updateUi()
                }
            }

            REQ_LOC_BACKGROUND -> updateUi()

            REQ_NEARBY_WIFI,
            REQ_BT,
            REQ_POST_NOTIF -> updateUi()
        }
    }

    // BLUETOOTH
    private fun hasBluetoothPermission(): Boolean {
        return PermissionSetupChecks.bluetoothTriggersReady(this)
    }

    private fun requestBluetoothPermissionIfMissing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        if (hasBluetoothPermission()) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
    }

    private fun isBatteryOptimizationUserConfirmedMaxAvailable(): Boolean {
        return BatteryOptimizationCompat.isUserConfirmedMaxAvailable(this)
    }

    private fun isBatteryOptimizationEffectivelyOk(): Boolean {
        return PermissionSetupChecks.batteryOptimizationReady(this)
    }

    private fun requestIgnoreBatteryOptimizationsSystemPopup() {
        if (!BatteryOptimizationRequest.isAlreadyAllowed(this) &&
            safeStart(BatteryOptimizationRequest.intent(this))
        ) {
            return
        }
        openBatteryOptimizationSettingsPages()
    }

    // EXACT ALARMS
    private fun canScheduleExactAlarms(): Boolean {
        return ExactAlarmPermissionSync.canScheduleExactAlarms(this)
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:$packageName".toUri()
            })
        }
    }

    private fun openNfcSettings() {
        for (intent in NfcLaunchAccessCompat.settingsIntents(this)) {
            if (safeStart(intent)) {
                return
            }
        }
    }

    // NOTIFICATIONS (Android 13+)
    private fun hasPostNotificationsPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun openOrRequestNotifications() {
        // If runtime permission is missing, request it once.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPostNotificationsPermission()) {
            lifecycleScope.launch {
                val prefs = AppPreferences(applicationContext)
                val askedBefore = prefs.notificationsPermissionAsked.first()
                if (!askedBefore) {
                    prefs.setNotificationsPermissionAsked(true)
                    requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQ_POST_NOTIF
                    )
                } else {
                    // If user denied previously, open settings instead of spamming prompts.
                    openNotificationSettings()
                }
            }
            return
        }

        openNotificationSettings()
    }

    private fun openNotificationSettings() {
        safeStart(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
        )
    }

    private fun openBatteryOptimizationSettingsPages() {
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        )
        for (i in intents) {
            if (safeStart(i)) {
                return
            }
        }
    }

    private fun openLocationSettingsForApp() {
        val pkg = packageName
        val packageUri = "package:$pkg".toUri()

        val extraPermissionName = "android.intent.extra.PERMISSION_NAME"
        val locationGroup = Manifest.permission_group.LOCATION

        val intents = listOf(
            // Best-effort deep link directly into the Location permission entry.
            Intent("android.intent.action.MANAGE_APP_PERMISSION").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                putExtra(extraPermissionName, "android.permission.ACCESS_BACKGROUND_LOCATION")
            },
            Intent("android.intent.action.MANAGE_APP_PERMISSION").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                putExtra(extraPermissionName, Manifest.permission.ACCESS_FINE_LOCATION)
            },
            // Some ROMs behave better with the permission *group*
            Intent("android.intent.action.MANAGE_APP_PERMISSION").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                putExtra(extraPermissionName, locationGroup)
            },

            // App permissions list (often available, sometimes hidden)
            Intent("android.settings.APP_PERMISSIONS_SETTINGS").apply {
                data = packageUri
                putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            },
            Intent("android.intent.action.MANAGE_APP_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
            },

            // Fallbacks
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = packageUri },
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        )

        for (i in intents) {
            if (safeStart(i)) {
                return
            }
        }
    }

    private fun openAppDetails() {
        safeStart(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        )
    }

    private fun openBluetoothSettingsOrAppDetails() {
        if (!safeStart(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) {
            openAppDetails()
        }
    }

    private fun isLikelyAggressiveOem(): Boolean {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        val all = "$m $b"
        return listOf(
            "xiaomi",
            "redmi",
            "poco",
            "huawei",
            "honor",
            "oppo",
            "realme",
            "oneplus",
            "vivo",
            "samsung",
            "motorola",
            "lenovo",
            "asus",
            "sony",
            "nokia",
            "zte",
            "tecno",
            "infinix"
        ).any { all.contains(it) }
    }

    private fun showWhyDialog(title: String, message: String) {
        showSwitchlyInfoDialog(
            title = title,
            rows = listOf(
                SwitchlyInfoRow(
                    label = getString(R.string.permissions_why_dialog_header),
                    value = message,
                ),
            ),
        )
    }

    private fun safeStart(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun focusRequestedSection() {
        val sectionId = when (intent.getStringExtra(EXTRA_FOCUS_SECTION)) {
            SECTION_CORE -> R.id.sectionCore
            SECTION_NOTIFICATIONS -> R.id.sectionNotifications
            SECTION_TRIGGERS -> R.id.sectionTriggers
            SECTION_BATTERY -> R.id.sectionBattery
            else -> return
        }
        val requestedTargetId = when (intent.getStringExtra(EXTRA_FOCUS_TARGET)) {
            TARGET_AUTOSTART -> R.id.groupAutostart
            else -> sectionId
        }
        val scroll = findViewById<ScrollView>(R.id.permissionsScroll)
        scroll.post {
            val requested = findViewById<View>(requestedTargetId)
            val fallback = findViewById<View>(sectionId)
            val target = requested.takeIf { it.isVisible } ?: fallback
            scroll.smoothScrollTo(
                0,
                (target.top - resources.displayMetrics.density * 8f).toInt().coerceAtLeast(0),
            )
            if (requestedTargetId != sectionId && requested.isVisible) {
                requested.animate().alpha(0.72f).setDuration(120L).withEndAction {
                    requested.animate().alpha(1f).setDuration(220L).start()
                }.start()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
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

    companion object {
        private const val ACCESS_BACKGROUND_LOCATION_PERMISSION = "android.permission.ACCESS_BACKGROUND_LOCATION"

        const val REQ_LOC_FINE = 1001
        const val REQ_LOC_BACKGROUND = 1003
        const val REQ_BT = 1002
        const val REQ_NEARBY_WIFI = 1004
        const val REQ_POST_NOTIF = 1005

        private const val PREFS_PERMISSION_HEALTH = "permissions_health"
        private const val KEY_STICKY_ACCESSIBILITY_MISMATCH = "sticky_accessibility_mismatch"

        private const val PREFS_SCHEDULE_HEALTH = "switchly_schedule_health"
        private const val KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE = "battery_optimization_confirmed_max_available"

        const val EXTRA_FROM_ONBOARDING = "extra_from_onboarding"
        const val EXTRA_SHOW_ACCESSIBILITY_DISCLOSURE = "extra_show_accessibility_disclosure"
        const val EXTRA_FOCUS_SECTION = "extra_focus_section"
        const val EXTRA_FOCUS_TARGET = "extra_focus_target"

        const val SECTION_CORE = "core"
        const val SECTION_NOTIFICATIONS = "notifications"
        const val SECTION_TRIGGERS = "triggers"
        const val SECTION_BATTERY = "battery"
        const val TARGET_AUTOSTART = "autostart"
    }
}
