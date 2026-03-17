package at.saltyy.switchly.feature.settings

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.widget.ImageViewCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.NotificationBlockStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.BatteryOptimizationCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class TroubleshootingActivity : AppCompatActivity() {

    private lateinit var cardTroubleshootingStatus: View
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusBody: TextView
    private lateinit var tvStatusFooter: TextView
    private lateinit var btnStatusInfo: View
    private lateinit var dividerStatus: View
    private lateinit var rowStatusAction: View
    private lateinit var tvStatusActionTitle: TextView
    private lateinit var btnStatusAction: MaterialButton

    private lateinit var tvAccessibility: TextView
    private lateinit var tvNotifAccess: TextView
    private lateinit var tvBattery: TextView
    private lateinit var tvBluetooth: TextView
    private lateinit var tvExactAlarms: TextView
    private lateinit var tvLocation: TextView

    private lateinit var ivInfoAccessibility: ImageView
    private lateinit var ivInfoNotif: ImageView
    private lateinit var ivInfoBattery: ImageView
    private lateinit var ivInfoBluetooth: ImageView
    private lateinit var ivInfoExact: ImageView
    private lateinit var ivInfoLocation: ImageView

    private lateinit var btnAccessibility: MaterialButton
    private lateinit var btnNotifAccess: MaterialButton
    private lateinit var btnBattery: MaterialButton
    private lateinit var btnBluetooth: MaterialButton
    private lateinit var btnExactAlarms: MaterialButton
    private lateinit var btnLocation: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_troubleshooting)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        tvAccessibility = findViewById(R.id.tvAccessibilityStatus)
        tvNotifAccess = findViewById(R.id.tvNotificationAccessStatus)
        tvBattery = findViewById(R.id.tvBatteryStatus)
        tvBluetooth = findViewById(R.id.tvBluetoothStatus)
        tvExactAlarms = findViewById(R.id.tvExactAlarmsStatus)
        tvLocation = findViewById(R.id.tvLocationStatus)

        ivInfoAccessibility = findViewById(R.id.infoAccessibility)
        ivInfoNotif = findViewById(R.id.infoNotif)
        ivInfoBattery = findViewById(R.id.infoBattery)
        ivInfoBluetooth = findViewById(R.id.infoBluetooth)
        ivInfoExact = findViewById(R.id.infoExact)
        ivInfoLocation = findViewById(R.id.infoLocation)

        btnAccessibility = findViewById(R.id.btnOpenAccessibility)
        btnNotifAccess = findViewById(R.id.btnOpenNotificationAccess)
        btnBattery = findViewById(R.id.btnOpenBattery)
        btnBluetooth = findViewById(R.id.btnOpenBluetooth)
        btnExactAlarms = findViewById(R.id.btnOpenExactAlarms)
        btnLocation = findViewById(R.id.btnOpenLocation)

        cardTroubleshootingStatus = findViewById(R.id.cardTroubleshootingStatus)
        ivStatusIcon = cardTroubleshootingStatus.findViewById(R.id.ivStatusIcon)
        tvStatusTitle = cardTroubleshootingStatus.findViewById(R.id.tvStatusTitle)
        tvStatusBody = cardTroubleshootingStatus.findViewById(R.id.tvStatusBody)
        tvStatusFooter = cardTroubleshootingStatus.findViewById(R.id.tvStatusFooter)
        btnStatusInfo = cardTroubleshootingStatus.findViewById(R.id.btnStatusInfo)
        dividerStatus = cardTroubleshootingStatus.findViewById(R.id.dividerStatus)
        rowStatusAction = cardTroubleshootingStatus.findViewById(R.id.rowStatusAction)
        tvStatusActionTitle = cardTroubleshootingStatus.findViewById(R.id.tvStatusActionTitle)
        btnStatusAction = cardTroubleshootingStatus.findViewById(R.id.btnStatusAction)

        ivStatusIcon.visibility = View.GONE
        tvStatusBody.visibility = View.VISIBLE
        tvStatusFooter.visibility = View.GONE
        btnStatusInfo.visibility = View.GONE

        tvStatusActionTitle.setText(R.string.pref_permissions_title)
        btnStatusAction.setText(R.string.open)
        btnStatusAction.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        btnAccessibility.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        btnNotifAccess.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        btnBattery.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }

        btnExactAlarms.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = "package:$packageName".toUri()
                    })
                }
            }
        }

        btnLocation.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        btnBluetooth.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        fun info(titleRes: Int, msgRes: Int, extraText: String? = null) {
            val message = buildString {
                append(getString(msgRes))
                if (!extraText.isNullOrBlank()) {
                    append("\n\n")
                    append(extraText)
                }
            }

            val dlg = Dialogs.builder(this)
                .setTitle(getString(titleRes))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .showAccented()

            CustomAccentApplier.applyToDialog(dlg)
        }

        findViewById<View>(R.id.infoAccessibility).setOnClickListener {
            info(R.string.troubleshooting_accessibility, R.string.troubleshooting_info_accessibility)
        }

        findViewById<View>(R.id.infoNotif).setOnClickListener {
            info(R.string.troubleshooting_notification_access, R.string.troubleshooting_info_notification_access)
        }

        findViewById<View>(R.id.infoBattery).setOnClickListener {
            info(R.string.troubleshooting_battery, R.string.troubleshooting_info_battery, getString(R.string.troubleshooting_battery_oem_note))
        }

        findViewById<View>(R.id.infoBluetooth).setOnClickListener {
            info(R.string.troubleshooting_bluetooth, R.string.troubleshooting_info_bluetooth)
        }

        findViewById<View>(R.id.infoExact).setOnClickListener {
            info(R.string.troubleshooting_exact_alarms, R.string.troubleshooting_info_exact_alarms, getString(R.string.troubleshooting_exact_alarms_note))
        }

        findViewById<View>(R.id.infoLocation).setOnClickListener {
            info(R.string.troubleshooting_location, R.string.troubleshooting_info_location)
        }

        findViewById<View>(R.id.btnOpenPermissionsOverview).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        CustomAccentApplier.applyIfNeeded(this)
        updateUi()
    }

    private fun setOk(tv: TextView, ok: Boolean) {
        tv.text = if (ok) getString(R.string.troubleshooting_ok) else getString(R.string.troubleshooting_missing)
        tv.setTextColor(
            if (ok) getColor(R.color.switchly_ok) else getColor(R.color.switchly_error)
        )
    }

    private fun setBatteryStatus(tv: TextView, ok: Boolean) {
        tv.text = when {
            ok && isBatteryOptimizationUserConfirmedMaxAvailable() ->
                getString(R.string.permissions_battery_highest_available)
            ok -> getString(R.string.troubleshooting_ok)
            else -> getString(R.string.troubleshooting_missing)
        }
        tv.setTextColor(
            if (ok) getColor(R.color.switchly_ok) else getColor(R.color.switchly_error)
        )
    }

    private fun tintInfoIcon(iv: ImageView) {
        val color = AccentColor.getAccentColorInt(this)
        ImageViewCompat.setImageTintList(iv, ColorStateList.valueOf(color))
        iv.alpha = 0.95f
    }

    private enum class LocationPermissionState {
        OK, APPROX_ONLY, BACKGROUND_MISSING, MISSING
    }

    private fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun getLocationPermissionState(): LocationPermissionState {
        val fine = hasFineLocationPermission()
        val coarse = hasCoarseLocationPermission()
        if (!fine) return if (coarse) LocationPermissionState.APPROX_ONLY else LocationPermissionState.MISSING
        if (!hasBackgroundLocationPermission()) return LocationPermissionState.BACKGROUND_MISSING
        return LocationPermissionState.OK
    }

    private fun isBatteryOptimizationUserConfirmedMaxAvailable(): Boolean {
        return BatteryOptimizationCompat.isUserConfirmedMaxAvailable(this)
    }

    private fun isBatteryOptimizationEffectivelyOk(): Boolean {
        return BatteryOptimizationCompat.isEffectivelyOk(this)
    }

    private fun updateUi() {
        // Accessibility = core blocking
        val accessibilityOk = BlockingRuntime.isAccessibilityActive(this)
        setOk(tvAccessibility, accessibilityOk)
        tintInfoIcon(ivInfoAccessibility)

        // Notification listener access for notification blocking feature
        val notifOk = NotificationBlockStore.hasListenerAccess(this)
        setOk(tvNotifAccess, notifOk)
        tintInfoIcon(ivInfoNotif)
        
        // Battery optimization
        val batteryOk = isBatteryOptimizationEffectivelyOk()
        setBatteryStatus(tvBattery, batteryOk)
        tintInfoIcon(ivInfoBattery)

        val exactOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.canScheduleExactAlarms()
        } else true
        setOk(tvExactAlarms, exactOk)
        tintInfoIcon(ivInfoExact)

        val bluetoothOk = hasBluetoothPermission()
        setOk(tvBluetooth, bluetoothOk)
        tintInfoIcon(ivInfoBluetooth)

        val locationOk = getLocationPermissionState() == LocationPermissionState.OK
        setOk(tvLocation, locationOk)
        tintInfoIcon(ivInfoLocation)

        val checks = listOf(
            getString(R.string.troubleshooting_accessibility) to accessibilityOk,
            getString(R.string.troubleshooting_notification_access) to notifOk,
            getString(R.string.troubleshooting_battery) to batteryOk,
            getString(R.string.troubleshooting_bluetooth) to bluetoothOk,
            getString(R.string.troubleshooting_exact_alarms) to exactOk,
            getString(R.string.troubleshooting_location) to locationOk,
        )

        val missing = checks.filter { !it.second }.map { it.first }
        val accent = AccentColor.getAccentColorInt(this)
        ImageViewCompat.setImageTintList(ivStatusIcon, ColorStateList.valueOf(accent))

        if (missing.isEmpty()) {
            tvStatusTitle.text = getString(R.string.troubleshooting_ok)
            tvStatusBody.text = getString(R.string.permissions_health_rechecked_all_good)
            rowStatusAction.visibility = View.GONE
            dividerStatus.visibility = View.GONE
        } else {
            tvStatusTitle.text = resources.getQuantityString(
                R.plurals.permissions_banner_missing_count_short,
                missing.size,
                missing.size
            )
            val bullet = getString(R.string.common_bullet)
            tvStatusBody.text = missing.joinToString(prefix = "$bullet ", separator = "\n$bullet ")
            rowStatusAction.visibility = View.VISIBLE
            dividerStatus.visibility = View.VISIBLE
        }
    }

    companion object {
        private const val PREFS_SCHEDULE_HEALTH = "switchly_schedule_health"
        private const val KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE = "battery_optimization_confirmed_max_available"
    }
}