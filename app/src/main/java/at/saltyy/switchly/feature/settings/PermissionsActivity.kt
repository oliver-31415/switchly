package at.saltyy.switchly.feature.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppPreferences
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Permissions overview screen.
 *
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
        MISSING
    }

    private lateinit var bannerWarn: MaterialCardView
    private lateinit var bannerWarnText: TextView

    private lateinit var tvNotificationsStatus: TextView
    private lateinit var tvNotificationAccessStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvLocationStatus: TextView
    private lateinit var tvBluetoothStatus: TextView
    private lateinit var tvBatteryStatus: TextView

    private lateinit var btnWhyNotifications: MaterialButton
    private lateinit var btnWhyNotificationAccess: MaterialButton
    private lateinit var btnWhyAccessibility: MaterialButton
    private lateinit var btnWhyLocation: MaterialButton
    private lateinit var btnWhyBluetooth: MaterialButton
    private lateinit var btnWhyBattery: MaterialButton
    private lateinit var btnWhyAutostart: MaterialButton

    private lateinit var btnOpenNotifications: MaterialButton
    private lateinit var btnOpenNotificationAccess: MaterialButton
    private lateinit var btnOpenAccessibility: MaterialButton
    private lateinit var btnOpenLocation: MaterialButton
    private lateinit var btnReqLocation: MaterialButton
    private lateinit var btnReqBluetooth: MaterialButton
    private lateinit var btnOpenBluetooth: MaterialButton
    private lateinit var btnReqBattery: MaterialButton
    private lateinit var btnOpenBattery: MaterialButton
    private lateinit var btnOpenAutostart: MaterialButton

    private lateinit var groupAutostart: View
    private lateinit var tvAutostartHint: TextView

    private companion object {
        const val REQ_LOC_FINE = 1001
        const val REQ_LOC_BACKGROUND = 1003
        const val REQ_BT = 1002
        const val REQ_NEARBY_WIFI = 1004
        const val REQ_POST_NOTIF = 1005
    }

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

        bannerWarn = findViewById(R.id.bannerWarn)
        bannerWarnText = findViewById(R.id.bannerWarnText)

        tvNotificationsStatus = findViewById(R.id.tvNotificationsStatus)
        tvNotificationAccessStatus = findViewById(R.id.tvNotificationAccessStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvLocationStatus = findViewById(R.id.tvLocationStatus)
        tvBluetoothStatus = findViewById(R.id.tvBluetoothStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)

        btnWhyNotifications = findViewById(R.id.btnWhyNotifications)
        btnWhyNotificationAccess = findViewById(R.id.btnWhyNotificationAccess)
        btnWhyAccessibility = findViewById(R.id.btnWhyAccessibility)
        btnWhyLocation = findViewById(R.id.btnWhyLocation)
        btnWhyBluetooth = findViewById(R.id.btnWhyBluetooth)
        btnWhyBattery = findViewById(R.id.btnWhyBattery)
        btnWhyAutostart = findViewById(R.id.btnWhyAutostart)

        btnOpenNotifications = findViewById(R.id.btnOpenNotifications)
        btnOpenNotificationAccess = findViewById(R.id.btnOpenNotificationAccess)
        btnOpenAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnOpenLocation = findViewById(R.id.btnOpenLocation)
        btnReqLocation = findViewById(R.id.btnReqLocation)
        btnReqBluetooth = findViewById(R.id.btnReqBluetooth)
        btnOpenBluetooth = findViewById(R.id.btnOpenBluetooth)
        btnReqBattery = findViewById(R.id.btnReqBattery)
        btnOpenBattery = findViewById(R.id.btnOpenBattery)
        btnOpenAutostart = findViewById(R.id.btnOpenAutostart)

        groupAutostart = findViewById(R.id.groupAutostart)
        tvAutostartHint = findViewById(R.id.tvAutostartHint)

        // OPEN buttons
        btnOpenNotifications.setOnClickListener {
            openOrRequestNotifications()
        }

        btnOpenNotificationAccess.setOnClickListener {
            if (!safeStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) openAppDetails()
        }

        btnOpenAccessibility.setOnClickListener {
            // This opens the general Accessibility settings screen.
            // OEMs often don't support deep-linking to a specific service entry reliably.
            safeStart(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOpenLocation.setOnClickListener {
            openLocationSettingsForApp()
        }

        btnOpenBluetooth.setOnClickListener {
            openAppDetails()
        }

        btnOpenBattery.setOnClickListener {
            openBatteryOptimizationSettingsPages()
        }

        btnOpenAutostart.setOnClickListener {
            openAppDetails()
        }

        // REQUEST buttons
        btnReqLocation.setOnClickListener { requestLocationFlow() }
        btnReqBluetooth.setOnClickListener { requestBluetoothPermissionIfMissing() }
        btnReqBattery.setOnClickListener { requestIgnoreBatteryOptimizationsSystemPopup() }

        // WHY dialogs
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
                getString(R.string.permissions_battery_desc)
            )
        }

        btnWhyAutostart.setOnClickListener {
            showWhyDialog(
                getString(R.string.permissions_autostart_title),
                getString(R.string.permissions_autostart_desc)
            )
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    // UI STATE
    private fun updateUi() {
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val postNotifGranted = hasPostNotificationsPermission()
        val notificationsOk = notificationsEnabled && postNotifGranted
        val notificationAccessGranted = NotificationBlockStore.hasListenerAccess(this)
        val notificationBlockingEnabled = NotificationBlockStore.isEnabled(this)

        val accessibilityEnabled = BlockingRuntime.isAccessibilityActive(this)
        val locationNeeded = isLocationNeededForFeatures()
        val locationState = if (locationNeeded) getLocationStateForWifi() else LocationState.OK
        val locationOk = locationState == LocationState.OK

        val bluetoothNeeded = isBluetoothNeededForFeatures()
        val btGranted = if (bluetoothNeeded) hasBluetoothPermission() else true

        val batteryOk = isIgnoringBatteryOptimizations()

        applyStatus(tvNotificationsStatus, notificationsOk)
        applyStatus(tvNotificationAccessStatus, notificationAccessGranted)
        applyStatus(tvAccessibilityStatus, accessibilityEnabled)

        // Notifications button:
        // - Android 13+ needs the runtime permission
        // - Older versions only need the system notifications toggle
        btnOpenNotifications.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotifGranted) {
            getString(R.string.permissions_btn_allow)
        } else {
            getString(R.string.permissions_btn_open)
        }
        if (locationNeeded) {
            applyLocationStatus(locationState)
        } else {
            val green = ContextCompat.getColor(this, R.color.status_ok)
            tvLocationStatus.text = getString(R.string.permissions_for_wifi_not_needed)
            tvLocationStatus.setTextColor(green)
        }

        if (bluetoothNeeded) {
            applyStatus(tvBluetoothStatus, btGranted)
        } else {
            val green = ContextCompat.getColor(this, R.color.status_ok)
            tvBluetoothStatus.text = getString(R.string.permissions_for_bluetooth_not_needed)
            tvBluetoothStatus.setTextColor(green)
        }

        applyStatus(tvBatteryStatus, batteryOk)

        // Location request button
        btnReqLocation.visibility = if (!locationNeeded || locationOk) View.GONE else View.VISIBLE
        if (locationNeeded) {
            btnReqLocation.text = when (locationState) {
                LocationState.MISSING -> getString(R.string.permissions_btn_request)
                LocationState.APPROX_ONLY -> getString(R.string.permissions_btn_enable_precise)
                LocationState.BACKGROUND_MISSING -> getString(R.string.permissions_btn_enable_all_the_time)
                LocationState.OK -> getString(R.string.permissions_status_enabled)
            }
        }

        btnOpenLocation.visibility = if (locationNeeded) View.VISIBLE else View.GONE

        // Bluetooth request button
        btnReqBluetooth.visibility = if (!bluetoothNeeded || btGranted) View.GONE else View.VISIBLE
        btnOpenBluetooth.visibility = if (bluetoothNeeded) View.VISIBLE else View.GONE

        // Battery request button
        btnReqBattery.visibility = if (batteryOk) View.GONE else View.VISIBLE
        btnOpenBattery.visibility = View.VISIBLE

        // OEM autostart
        val showOem = isLikelyAggressiveOem()
        groupAutostart.visibility = if (showOem) View.VISIBLE else View.GONE
        tvAutostartHint.visibility = if (showOem) View.VISIBLE else View.GONE
        btnOpenAutostart.visibility = if (showOem) View.VISIBLE else View.GONE

        // Update the notifications action label depending on what the user actually needs to do.
        btnOpenNotifications.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !postNotifGranted) {
            getString(R.string.permissions_btn_allow)
        } else {
            getString(R.string.permissions_btn_open)
        }

        updateBanner(
            missingCount = listOf(
                (!accessibilityEnabled),
                !notificationsOk,
                (notificationBlockingEnabled && !notificationAccessGranted),
                (locationNeeded && !locationOk),
                (bluetoothNeeded && !btGranted),
                !batteryOk
            ).count { it }
        )
    }

    private fun updateBanner(missingCount: Int) {
        if (missingCount == 0) {
            bannerWarn.visibility = View.GONE
            return
        }
        bannerWarn.visibility = View.VISIBLE
        bannerWarnText.text = resources.getQuantityString(
            R.plurals.permissions_banner_missing_count,
            missingCount,
            missingCount
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
        }
    }

    // FEATURE NEEDS
    private fun isLocationNeededForFeatures(): Boolean {
        val hasWifiSchedules = at.saltyy.switchly.data.prefs.ScheduleStore
            .hasEnabledWifiSchedules(applicationContext)

        val hasWifiRules = at.saltyy.switchly.data.prefs.WifiRuleStore
            .getAll(applicationContext)
            .any { it.enabled && it.ssid.isNotBlank() }

        return hasWifiSchedules || hasWifiRules
    }

    private fun isBluetoothNeededForFeatures(): Boolean {
        return at.saltyy.switchly.data.prefs.ScheduleStore
            .hasEnabledBluetoothSchedules(applicationContext)
    }

    // LOCATION
    private fun hasCoarseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        // On Android 9 and below there is no separate background location permission.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true

        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Wi‑Fi schedules need location permissions so the connected SSID/BSSID can be read reliably.
     *
     * For best reliability on many devices, especially when triggers run while the app is not
     * in the foreground, we also guide users to enable "Allow all the time"
     * (ACCESS_BACKGROUND_LOCATION) via a 2‑step flow.
     */
    private fun getLocationStateForWifi(): LocationState {
        val fine = hasFineLocationPermission()
        val coarse = hasCoarseLocationPermission()

        if (!fine) {
            return if (coarse) LocationState.APPROX_ONLY else LocationState.MISSING
        }

        // For best reliability, especially when Wi‑Fi triggers run in the background,
        // guide users to "Allow all the time" (ACCESS_BACKGROUND_LOCATION).
        if (!hasBackgroundLocationPermission()) {
            return LocationState.BACKGROUND_MISSING
        }
        return LocationState.OK
    }

    /**
     * Runtime request flow for Wi‑Fi schedules.
     *
     * 1) Request ACCESS_FINE_LOCATION (Android popup: "While using the app")
     * 2) Request / guide to ACCESS_BACKGROUND_LOCATION ("Allow all the time")
     * 3) (Optional, Android 13+) Request NEARBY_WIFI_DEVICES
     */
    private fun requestLocationFlow() {
        // 1) Precise location
        if (!hasFineLocationPermission()) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
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
            val nearbyGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED

            if (!nearbyGranted) {
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
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQ_LOC_BACKGROUND
            )
            return
        }

        // Android 11+: requesting ACCESS_BACKGROUND_LOCATION will usually open
        // the system permission controller where the user can switch to
        // "Allow all the time" for Location.
        //
        // On some devices/ROMs this may still not show the exact location page,
        // so we keep a settings fallback in onRequestPermissionsResult.
        requestPermissions(
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun requestBluetoothPermissionIfMissing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (hasBluetoothPermission()) return
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
    }

    // BATTERY OPTIMIZATION (popup)
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimizationsSystemPopup() {
        if (isIgnoringBatteryOptimizations()) {
            // Already unrestricted/whitelisted.
            openBatteryOptimizationSettingsPages()
            return
        }

        // Optional: direct system popup (nicer UX).
        // NOTE: This can trigger Play policy warnings depending on your use-case.
        // Keep it behind an explicit user action (button tap), as implemented here.
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
        }

        if (!safeStart(intent)) {
            // Fallback to settings pages.
            openBatteryOptimizationSettingsPages()
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
            if (safeStart(i)) return
        }
    }

    // HELPERS
    private fun openLocationSettingsForApp() {
        val pkg = packageName
        val packageUri = "package:$pkg".toUri()

        val extraPermissionName = "android.intent.extra.PERMISSION_NAME"
        val locationGroup = Manifest.permission_group.LOCATION

        // Best effort: jump directly into the permission controller screen where
        // "Allow all the time" can be selected for Location.
        //
        // These intents are @hide in the SDK and may not exist on every ROM/device,
        // so we try them first and gracefully fall back to the normal app settings page.
        val intents = listOf(
            // Best-effort deep link directly into the Location permission entry.
            Intent("android.intent.action.MANAGE_APP_PERMISSION").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, pkg)
                putExtra(extraPermissionName, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
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
            if (safeStart(i)) return
        }
    }

    private fun openAppDetails() {
        safeStart(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        )
    }

    private fun isLikelyAggressiveOem(): Boolean {
        val m = (Build.MANUFACTURER ?: "").lowercase()
        val b = (Build.BRAND ?: "").lowercase()
        val all = "$m $b"
        return listOf(
            "xiaomi", "redmi", "poco", "huawei", "honor",
            "oppo", "realme", "oneplus", "vivo", "samsung"
        ).any { all.contains(it) }
    }

    private fun showWhyDialog(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun safeStart(intent: Intent): Boolean {
        return try {
            startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
