package at.saltyy.switchly.feature.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleRuntimeStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ScheduleStore.Days
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.feature.settings.PermissionsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons

class SchedulesActivity : AppCompatActivity() {

    private enum class NewScheduleMode { TIME, WIFI, BT }
    private enum class Kind { TIME, WIFI, BT }
    private enum class TimeMode { SINGLE, TIME_RANGE, DATE_RANGE }

    private lateinit var adapter: ScheduleAdapter
    private lateinit var cardScheduleHealth: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var btnStatusAction: MaterialButton
    private lateinit var rowStatusAction: View
    private lateinit var btnStatusInfo: View
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusActionTitle: TextView
    private lateinit var dividerStatus: View
    private lateinit var switchShowNextSchedule: SwitchCompat

    // Unified list actions: selection mode
    private var isSelectionMode = false
    private val selectedScheduleIds = linkedSetOf<Int>()

    private var pendingAfterLocationGrant: (() -> Unit)? = null
    private var pendingAfterBluetoothGrant: (() -> Unit)? = null

    private val nfcLockedActions = setOf(
        ScheduleStore.Action.DISABLE,
        ScheduleStore.Action.TOGGLE,
        ScheduleStore.Action.ENABLE_AND_DISABLE,
        ScheduleStore.Action.DISABLE_AND_ENABLE
    )

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAfterLocationGrant
            pendingAfterLocationGrant = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.perm_location_denied_wifi_schedule),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAfterBluetoothGrant
            pendingAfterBluetoothGrant = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.perm_bt_denied_schedule),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    private var externalActivityReturnCallback: (() -> Unit)? = null

    private val externalActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val cb = externalActivityReturnCallback
            externalActivityReturnCallback = null
            cb?.invoke()
        }

    private fun launchExternalActivity(intent: Intent, onReturn: (() -> Unit)? = null) {
        externalActivityReturnCallback = onReturn
        runCatching {
            externalActivityLauncher.launch(intent)
        }.onFailure {
            // Fallback if the launcher cannot be used in this state.
            runCatching { startActivity(intent) }
            if (onReturn != null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (externalActivityReturnCallback === onReturn) {
                        val cb = externalActivityReturnCallback
                        externalActivityReturnCallback = null
                        cb?.invoke()
                    }
                }, 1400L)
            } else {
                externalActivityReturnCallback = null
            }
        }
    }

    private fun isNfcLockActiveForSchedules(): Boolean {
        return AutomationModeStore.isNfcAllowed(this) &&
            SwitchModeStore.isEnabled(this) &&
            SwitchModeStore.isNfcRequiredForDisable(this)
    }

    private fun isScheduleAutomationAllowed(): Boolean {
        return AutomationModeStore.isScheduleAllowed(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedules)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        // Classic system bars (status/nav handled by the framework)
        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = toolbar
        )

        // Keep status bar neutral (no accent bleed into system bar)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val recycler = findViewById<RecyclerView>(R.id.recyclerSchedules)
        recycler.layoutManager = LinearLayoutManager(this)

        cardScheduleHealth = findViewById(R.id.cardScheduleHealth)
        tvStatusTitle = cardScheduleHealth.findViewById(R.id.tvStatusTitle)
        btnStatusAction = cardScheduleHealth.findViewById(R.id.btnStatusAction)
        rowStatusAction = cardScheduleHealth.findViewById(R.id.rowStatusAction)
        btnStatusInfo = cardScheduleHealth.findViewById(R.id.btnStatusInfo)
        ivStatusIcon = cardScheduleHealth.findViewById(R.id.ivStatusIcon)
        tvStatusActionTitle = cardScheduleHealth.findViewById(R.id.tvStatusActionTitle)
        dividerStatus = cardScheduleHealth.findViewById(R.id.dividerStatus)

        // Static visuals for this screen
        ivStatusIcon.setImageResource(R.drawable.schedule_24)
        tvStatusActionTitle.setText(R.string.schedules_health_action_title)
        btnStatusInfo.visibility = View.VISIBLE
        btnStatusInfo.setOnClickListener { showScheduleHealthInfoDialog() }

        val rowShowNextSchedule = findViewById<View>(R.id.rowShowNextSchedule)
        switchShowNextSchedule = findViewById(R.id.switchShowNextSchedule)
        val uiPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        switchShowNextSchedule.isChecked = uiPrefs.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        tintSwitchCompat(switchShowNextSchedule)

        rowShowNextSchedule.setOnClickListener { switchShowNextSchedule.toggle() }
        switchShowNextSchedule.setOnCheckedChangeListener { _, isChecked ->
            uiPrefs.edit { putBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, isChecked) }
            SchedulePlanner.notifyNextChanged(this)
        }

        adapter = ScheduleAdapter(
            onToggleEnabled = { schedule, enabled ->
                val list = ScheduleStore.getAll(this).map {
                    if (it.id == schedule.id) it.copy(enabled = enabled) else it
                }
                ScheduleStore.saveAll(this, list)
                SchedulePlanner.updateNextAlarm(this)
                SchedulePlanner.notifyNextChanged(this)
            },
            isSelectionMode = { isSelectionMode },
            isSelected = { id -> selectedScheduleIds.contains(id) },
            onToggleSelection = { id -> toggleSelection(id) },
            onEnterSelection = { preselectId -> enterSelectionMode(preselectId) },
            onEdit = { schedule -> showScheduleDialog(existing = schedule, preselectedMode = null) }
        )
        recycler.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            if (!isScheduleAutomationAllowed()) {
                Toast.makeText(this, getString(R.string.mode_blocked_schedule_action), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showNewScheduleTypeDialog()
        }
        refreshList()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_schedules, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasItems = adapter.itemCount > 0
        menu.findItem(R.id.action_select)?.isVisible = !isSelectionMode && hasItems
        menu.findItem(R.id.action_cancel_selection)?.isVisible = isSelectionMode
        menu.findItem(R.id.action_delete_selected)?.isVisible = isSelectionMode

        val del = menu.findItem(R.id.action_delete_selected)
        del?.isEnabled = selectedScheduleIds.isNotEmpty()
        del?.alphaCompat(if (selectedScheduleIds.isNotEmpty()) 1f else 0.4f)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select -> {
                enterSelectionMode(null)
                true
            }
            R.id.action_cancel_selection -> {
                exitSelectionMode()
                true
            }
            R.id.action_delete_selected -> {
                confirmDeleteSelectedSchedules()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun MenuItem.alphaCompat(alpha: Float) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        icon?.mutate()?.alpha = a
    }

    private fun updateMenuState() {
        invalidateOptionsMenu()
        findViewById<View>(R.id.fabAdd)?.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
    }

    private fun enterSelectionMode(preselectId: Int?) {
        isSelectionMode = true
        selectedScheduleIds.clear()
        preselectId?.let { selectedScheduleIds.add(it) }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun exitSelectionMode() {
        isSelectionMode = false
        selectedScheduleIds.clear()
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun toggleSelection(id: Int) {
        if (!isSelectionMode) return
        if (selectedScheduleIds.contains(id)) selectedScheduleIds.remove(id) else selectedScheduleIds.add(id)
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun confirmDeleteSelectedSchedules() {
        if (selectedScheduleIds.isEmpty()) return
        val count = selectedScheduleIds.size
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(resources.getQuantityString(R.plurals.delete_schedules_confirm, count, count))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()

        dlg.setOnShowListener {
            dlg.styleSwitchlyDialogButtons()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val remaining = ScheduleStore.getAll(this).filterNot { selectedScheduleIds.contains(it.id) }
                ScheduleStore.saveAll(this, remaining)
                SchedulePlanner.updateNextAlarm(this)
                SchedulePlanner.notifyNextChanged(this)
                exitSelectionMode()
                refreshList()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun rootContentView(): View? {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return content?.getChildAt(0)
    }

    override fun onResume() {
        super.onResume()

        val uiPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        switchShowNextSchedule.isChecked = uiPrefs.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        tintSwitchCompat(switchShowNextSchedule)

        refreshList()

        // If Wi-Fi triggers can't read the current SSID, we show a one-time hint.
        // This commonly happens on modern Android when Location is turned off.
        val wifiPrefs = getSharedPreferences("switchly_wifi_cache", MODE_PRIVATE)
        val needsLocationHint = wifiPrefs.getBoolean("wifi_needs_location_hint", false)
        if (needsLocationHint) {
            Snackbar.make(
                rootContentView() ?: findViewById(android.R.id.content),
                getString(R.string.schedules_wifi_location_hint),
                Snackbar.LENGTH_LONG
            ).show()

            wifiPrefs.edit {
                putBoolean("wifi_needs_location_hint", false)
            }
        }
    }

    private fun showNewScheduleTypeDialog() {
        if (!isScheduleAutomationAllowed()) {
            Toast.makeText(this, getString(R.string.mode_blocked_schedule_action), Toast.LENGTH_SHORT).show()
            return
        }

        val isPremium = PremiumManager.isPremium(this)
        val nfcLockOn = isNfcLockActiveForSchedules()

        data class TypeItem(val label: String, val iconRes: Int, val mode: NewScheduleMode)

        val items = buildList {
            add(TypeItem(getString(R.string.schedules_type_time), R.drawable.alarm_24, NewScheduleMode.TIME))
            if (isPremium && !nfcLockOn) {
                add(TypeItem(getString(R.string.schedules_type_wifi), R.drawable.wifi_24, NewScheduleMode.WIFI))
                add(TypeItem(getString(R.string.schedules_type_bt), R.drawable.bluetooth_24, NewScheduleMode.BT))
            }
        }.toTypedArray()

        val adapter = object : ArrayAdapter<TypeItem>(this, android.R.layout.select_dialog_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: LayoutInflater.from(context)
                    .inflate(android.R.layout.select_dialog_item, parent, false)
                val tv = v.findViewById<TextView>(android.R.id.text1)
                val item = getItem(position)!!

                tv.text = item.label
                tv.setCompoundDrawablesWithIntrinsicBounds(item.iconRes, 0, 0, 0)
                tv.compoundDrawablePadding = (12 * resources.displayMetrics.density).toInt()

                val onSurface = MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurface)
                tv.setTextColor(onSurface)
                tv.alpha = 1f
                return v
            }
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.schedules_choose_type)
            .setAdapter(adapter) { _, which ->
                when (items[which].mode) {
                    NewScheduleMode.TIME -> showScheduleDialog(null, NewScheduleMode.TIME)
                    NewScheduleMode.WIFI -> showScheduleDialog(null, NewScheduleMode.WIFI)
                    NewScheduleMode.BT -> showScheduleDialog(null, NewScheduleMode.BT)
                }
            }
            .setNegativeButton(R.string.cancel, null)

        if (nfcLockOn) {
            builder.setMessage(R.string.schedules_nfc_lock_add_dialog_hint)
        }

        val dialog = builder.create()
        dialog.setOnShowListener { dialog.styleSwitchlyDialogButtons() }
        dialog.show()
    }

    private fun tintPickButton(button: MaterialButton) {
        val accent = AccentColor.getAccentColorInt(this)
        button.strokeColor = ColorStateList.valueOf(accent)
        button.setTextColor(accent)
        button.iconTint = ColorStateList.valueOf(accent)
    }

    private fun tintSwitchCompat(switch: SwitchCompat) {
        val accent = AccentColor.getAccentColorInt(this)
        val thumbOff = ColorUtils.blendARGB(accent, Color.WHITE, 0.72f)
        val thumbDisabled = ColorUtils.blendARGB(accent, Color.LTGRAY, 0.80f)
        val trackOn = ColorUtils.setAlphaComponent(accent, 0x88)
        val trackOff = ColorUtils.setAlphaComponent(Color.DKGRAY, 0x44)
        val trackOffDisabled = ColorUtils.setAlphaComponent(Color.GRAY, 0x33)

        switch.thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(thumbDisabled, accent, thumbOff)
        )

        switch.trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(trackOffDisabled, trackOn, trackOff)
        )
    }

    private fun refreshList() {
        val list = ScheduleStore.getAll(this)
        adapter.submitList(list)

        // keep selection consistent
        val ids = list.map { it.id }.toSet()
        selectedScheduleIds.retainAll(ids)
        if (isSelectionMode && selectedScheduleIds.isEmpty()) {
            exitSelectionMode()
        } else {
            updateMenuState()
        }
        updateScheduleHealthBanner()
    }

    private fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = getSystemService(AlarmManager::class.java) ?: return true
        return am.canScheduleExactAlarms()
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:$packageName".toUri()
            })
        }
    }

    private fun openBatteryOptimizationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            })
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun isBatteryOptimizationLikelyActive(): Boolean {
        val pm = getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateScheduleHealthBanner() {
        val schedules = ScheduleStore.getAll(this)
        val enabledSchedules = schedules.filter { it.enabled }

        if (enabledSchedules.isEmpty()) {
            cardScheduleHealth.isVisible = false
            return
        }

        val batteryOptimizationActive = isBatteryOptimizationLikelyActive()
        val exactAlarmsAllowed = canScheduleExactAlarms()
        val accessibilityActive = BlockingRuntime.isAccessibilityActive(this)

        // Some schedule modes need extra runtime permissions to work reliably
        val wifiSchedulesNeedPerm = enabledSchedules.any { !it.wifiSsid.isNullOrBlank() }
        val btSchedulesNeedPerm = enabledSchedules.any { !it.btDeviceName.isNullOrBlank() }
        val wifiPermMissing = wifiSchedulesNeedPerm && !hasWifiSsidPermission()
        val btPermMissing = btSchedulesNeedPerm && !hasBluetoothConnectPermission()

        val nfcLockActiveForSchedules = isNfcLockActiveForSchedules()
        val nfcConflict = nfcLockActiveForSchedules &&
            enabledSchedules.any { it.action in nfcLockedActions }

        val blockedAt = ScheduleRuntimeStore.getLastDisableBlockedByNfcMs(this)
        val nfcBlockedRecently = nfcLockActiveForSchedules &&
            blockedAt > 0L && (System.currentTimeMillis() - blockedAt) < 24L * 60L * 60L * 1000L

        val lastExecMs = ScheduleRuntimeStore.getLastExecutionMs(this)
        val lastExecText = if (lastExecMs > 0L) {
            runCatching {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(lastExecMs))
            }.getOrDefault(DateFormat.getDateTimeInstance().format(Date(lastExecMs)))
        } else {
            getString(R.string.schedules_health_last_exec_never)
        }

        val statusLines = mutableListOf<String>()
        statusLines += if (batteryOptimizationActive) {
            getString(R.string.schedules_health_status_battery_bad)
        } else {
            getString(R.string.schedules_health_status_battery_ok)
        }
        statusLines += if (exactAlarmsAllowed) {
            getString(R.string.schedules_health_status_exact_ok)
        } else {
            getString(R.string.schedules_health_status_exact_bad)
        }
        statusLines += getString(R.string.schedules_health_status_last_exec, lastExecText)

        val healthStatusBase = statusLines.joinToString("\n")

        cardScheduleHealth.isVisible = true

        when {
            !accessibilityActive -> {
                tvStatusTitle.text = getString(
                    R.string.schedules_health_status_with_detail,
                    healthStatusBase,
                    getString(R.string.schedules_health_accessibility)
                )
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_permissions)
                btnStatusAction.setOnClickListener {
                    startActivity(Intent(this, PermissionsActivity::class.java))
                }
            }

            wifiPermMissing || btPermMissing -> {
                val detail = when {
                    wifiPermMissing && btPermMissing -> getString(R.string.schedules_health_permissions_wifi_bt)
                    wifiPermMissing -> getString(R.string.schedules_health_permissions_wifi)
                    else -> getString(R.string.schedules_health_permissions_bt)
                }
                tvStatusTitle.text = getString(
                    R.string.schedules_health_status_with_detail,
                    healthStatusBase,
                    detail
                )
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_permissions)
                btnStatusAction.setOnClickListener { openPermissionsOverview() }
            }

            nfcConflict || nfcBlockedRecently -> {
                tvStatusTitle.text = getString(
                    R.string.schedules_health_status_with_detail,
                    healthStatusBase,
                    getString(R.string.schedules_health_nfc_conflict)
                )
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_toggle_options)
                btnStatusAction.setOnClickListener {
                    startActivity(Intent(this, ToggleOptionsActivity::class.java))
                }
            }

            batteryOptimizationActive -> {
                tvStatusTitle.text = getString(
                    R.string.schedules_health_status_with_detail,
                    healthStatusBase,
                    getString(R.string.schedules_health_battery)
                )
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_battery)
                btnStatusAction.setOnClickListener { openBatteryOptimizationSettings() }
            }

            !exactAlarmsAllowed -> {
                tvStatusTitle.text = getString(
                    R.string.schedules_health_status_with_detail,
                    healthStatusBase,
                    getString(R.string.schedules_health_exact_alarm)
                )
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_exact_alarm)
                btnStatusAction.setOnClickListener { openExactAlarmSettings() }
            }

            else -> {
                tvStatusTitle.text = healthStatusBase
                btnStatusAction.isVisible = false
                btnStatusAction.setOnClickListener(null)
            }
        }

        // Hide the whole "Recommended action" row when nothing is required.
        rowStatusAction.isVisible = btnStatusAction.isVisible
        dividerStatus.isVisible = rowStatusAction.isVisible
    }

    private fun showScheduleHealthInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedules_health_info_title)
            .setMessage(R.string.schedules_health_info_body)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.schedules_health_action_permissions) { _, _ ->
                openPermissionsOverview()
            }
            .showAccented()
    }

    private fun confirmDelete(schedule: ScheduleStore.Schedule) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.are_you_sure))
            .setMessage(getString(R.string.schedules_delete_confirm))
            .setPositiveButton(R.string.delete) { _, _ ->
                val newList = ScheduleStore.getAll(this).filterNot { it.id == schedule.id }
                ScheduleStore.saveAll(this, newList)
                SchedulePlanner.updateNextAlarm(this)
                SchedulePlanner.notifyNextChanged(this)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .showAccented()
    }

    private fun wifiSsidPermissionName(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    private fun hasWifiSsidPermission(): Boolean {
        val primaryGranted = ContextCompat.checkSelfPermission(
            this,
            wifiSsidPermissionName()
        ) == PackageManager.PERMISSION_GRANTED
        if (primaryGranted) return true

        // Be permissive on Android 13+: some devices expose SSID access via fine location even when Nearby Wi‑Fi wasn't explicitly granted yet.
        return Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }

    private fun openPermissionsOverview() {
        runCatching {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }.onFailure {
            openAppSettings()
        }
    }

    private fun showWhyLocationDialogForWifi() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_location_title)
            .setMessage(R.string.perm_location_why_wifi_ssid)
            .setPositiveButton(R.string.ok) { _, _ ->
                requestLocationPermission.launch(wifiSsidPermissionName())
            }
            .setNeutralButton(R.string.schedules_health_action_permissions) { _, _ ->
                openPermissionsOverview()
            }
            .setNegativeButton(R.string.cancel, null)
            .showAccented()
    }

    private fun showWhyBluetoothDialogForSchedules() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_bt_title)
            .setMessage(R.string.perm_bt_why_needed)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            .setNeutralButton(R.string.schedules_health_action_permissions) { _, _ ->
                openPermissionsOverview()
            }
            .setNegativeButton(R.string.cancel, null)
            .showAccented()
    }

    private fun showSnack(msgRes: Int) {
        val root = findViewById<View?>(android.R.id.content)
        if (root != null) Snackbar.make(root, msgRes, Snackbar.LENGTH_LONG).show()
        else Toast.makeText(this, getString(msgRes), Toast.LENGTH_LONG).show()
    }

    private fun showScheduleDialog(
        existing: ScheduleStore.Schedule?,
        preselectedMode: NewScheduleMode? = null
    ) {
        // Inflate with the dialog theme overlay to avoid OEM/Android 16 theme resolution issues when TextInputLayout (Material) reads theme attributes.
        val themedCtx = androidx.appcompat.view.ContextThemeWrapper(this, R.style.ThemeOverlay_Switchly_Dialog)
        val view = LayoutInflater.from(themedCtx).inflate(R.layout.dialog_schedule_add, null, false)

        // Dialog content is inflated with a theme overlay, which can still resolve some Material widget colors (chips/text fields) to the default (green) accent. 
        // In CUSTOM mode, do an explicit runtime recolor pass so cursor/selection + checkables are correct.
        val isCustomAccent = CustomAccentApplier.isCustomAccentEnabled(this)
        if (isCustomAccent) {
            CustomAccentApplier.applyToView(view, this)
        }

        val editTitle = view.findViewById<EditText>(R.id.editTitle)
        val editNote = view.findViewById<EditText>(R.id.editNote)
        val spinnerProfile = view.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerProfile)
        val spinnerAction = view.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerAction)
        val textActionNfcHint = view.findViewById<TextView>(R.id.textActionNfcHint)
        val spinnerTimeMode = view.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerTimeMode)

        val groupTimeMode = view.findViewById<View>(R.id.groupTimeMode)

        val groupTime = view.findViewById<View>(R.id.groupTime)
        val groupTimeRow = view.findViewById<View>(R.id.groupTimeRow)
        val textStartTime = view.findViewById<TextView>(R.id.textStartTime)
        val textEndTime = view.findViewById<TextView>(R.id.textEndTime)

        // Connection schedules: make the time window optional
        val switchConnTimeWindow = view.findViewById<SwitchCompat>(R.id.switchConnTimeWindow)
        val textConnAllDayHint = view.findViewById<TextView>(R.id.textConnAllDayHint)

        val inputWifiSsid = view.findViewById<EditText>(R.id.inputWifiSsid)
        val layoutWifiSsid = view.findViewById<TextInputLayout>(R.id.layoutWifiSsid)
        val groupWifiActions = view.findViewById<View>(R.id.groupWifiActions)
        val btnScanWifi = view.findViewById<MaterialButton>(R.id.btnScanWifi)
        val inputBtName = view.findViewById<EditText>(R.id.inputBtName)
        val layoutBtName = view.findViewById<TextInputLayout>(R.id.layoutBtName)
        val groupBtActions = view.findViewById<View>(R.id.groupBtActions)
        val btnUseConnectedBt = view.findViewById<MaterialButton>(R.id.btnUseConnectedBt)
        val btnPickPairedBt = view.findViewById<MaterialButton>(R.id.btnPickPairedBt)

        if (isCustomAccent) {
            tintPickButton(btnScanWifi)
            tintPickButton(btnUseConnectedBt)
            tintPickButton(btnPickPairedBt)
            tintSwitchCompat(switchConnTimeWindow)
            // Outlined TextInputLayouts look best when the stroke is accented.
            val accent = AccentColor.getAccentColorInt(this)
            layoutWifiSsid.boxStrokeColor = accent
            layoutBtName.boxStrokeColor = accent
        }

        val groupWeekly = view.findViewById<View>(R.id.groupWeekly)
        val groupOnce = view.findViewById<View>(R.id.groupOnce)
        val textStartDate = view.findViewById<TextView>(R.id.textStartDate)
        val textEndDate = view.findViewById<TextView>(R.id.textEndDate)

        val chipMon = view.findViewById<Chip>(R.id.chipMon)
        val chipTue = view.findViewById<Chip>(R.id.chipTue)
        val chipWed = view.findViewById<Chip>(R.id.chipWed)
        val chipThu = view.findViewById<Chip>(R.id.chipThu)
        val chipFri = view.findViewById<Chip>(R.id.chipFri)
        val chipSat = view.findViewById<Chip>(R.id.chipSat)
        val chipSun = view.findViewById<Chip>(R.id.chipSun)

        val chipWeekdays = view.findViewById<Chip>(R.id.chipWeekdays)
        val chipWeekend = view.findViewById<Chip>(R.id.chipWeekend)
        val chipToday = view.findViewById<Chip>(R.id.chipToday)

        // Make chip selection states obvious without extra color selector XML files.
        // (We build the ColorStateList in code so you can keep resources lean.)
        fun applyDayChipColors(chip: Chip) {
            // IMPORTANT: This dialog is inflated with a theme overlay.
            // In CUSTOM accent modethe overlay can still resolve colorPrimary to the default green, so we must pull the user-selected accent explicitly.
            val primary = if (isCustomAccent) {
                AccentColor.getAccentColorInt(this@SchedulesActivity)
            } else {
                // NOTE: Depending on the Material library version, colorPrimary may be defined by
                // AppCompat rather than com.google.android.material.R.attr.
                MaterialColors.getColor(chip, androidx.appcompat.R.attr.colorPrimary)
            }

            val onPrimary = if (isCustomAccent) {
                val black = ColorUtils.calculateContrast(Color.BLACK, primary)
                val white = ColorUtils.calculateContrast(Color.WHITE, primary)
                if (black >= white) Color.BLACK else Color.WHITE
            } else {
                MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimary)
            }
            val onSurface = MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurface)

            // Subtle unselected look (tinted onSurface); clear selected look (primary).
            val bgUnchecked = MaterialColors.compositeARGBWithAlpha(onSurface, 0x14) // ~8%
            val strokeUnchecked = MaterialColors.compositeARGBWithAlpha(onSurface, 0x3D) // ~24%

            chip.chipBackgroundColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(primary, bgUnchecked)
            )
            chip.chipStrokeColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(primary, strokeUnchecked)
            )
            chip.setTextColor(
                ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(onPrimary, onSurface)
                )
            )
        }

        listOf(
            chipMon, chipTue, chipWed, chipThu, chipFri, chipSat, chipSun,
            chipWeekdays, chipWeekend, chipToday
        ).forEach(::applyDayChipColors)

        val isPremium = PremiumManager.isPremium(this)

        val kind: Kind = when {
            preselectedMode == NewScheduleMode.WIFI -> Kind.WIFI
            preselectedMode == NewScheduleMode.BT -> Kind.BT
            preselectedMode == NewScheduleMode.TIME -> Kind.TIME
            existing?.wifiSsid?.isNotBlank() == true -> Kind.WIFI
            existing?.btDeviceName?.isNotBlank() == true -> Kind.BT
            else -> Kind.TIME
        }

        // For non-premium: just hide the premium-only inputs
        if (!isPremium) {
            layoutWifiSsid.visibility = View.GONE
            layoutBtName.visibility = View.GONE
        }

        fun applyKindVisibility() {
            val isTime = kind == Kind.TIME
            groupTimeMode.isVisible = isTime

            val showWifi = isPremium && kind == Kind.WIFI
            val showBt = isPremium && kind == Kind.BT

            layoutWifiSsid.isVisible = showWifi
            groupWifiActions.isVisible = showWifi
            layoutBtName.isVisible = showBt
            groupBtActions.isVisible = showBt

            // Time window switch only makes sense for connection schedules
            val isConn = kind == Kind.WIFI || kind == Kind.BT
            switchConnTimeWindow.isVisible = isConn
            textConnAllDayHint.isVisible = false
        }

        // Wi‑Fi helper actions
        fun requestWifiPermissionThenRetry(action: () -> Unit) {
            pendingAfterLocationGrant = action
            requestLocationPermission.launch(wifiSsidPermissionName())
        }

        fun requestBluetoothPermissionThenRetry(action: () -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingAfterBluetoothGrant = action
                requestBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                action()
            }
        }

        fun isLocationEnabled(): Boolean {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            return runCatching {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }

        fun cleanSsid(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val s = raw.trim().removePrefix("\"").removeSuffix("\"")
            if (s.equals("<unknown ssid>", ignoreCase = true)) return null
            return s
        }

        fun markNeedsLocationHintOnce() {
            getSharedPreferences("switchly_wifi_cache", MODE_PRIVATE).edit {
                putBoolean("wifi_needs_location_hint", true)
            }
        }

        fun openLocationSettings() {
            runCatching {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
        }
        fun openWifiPickerPanel() {
            // Open plain Wi‑Fi settings only. We do not rely on callback-selected SSID.
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }

        fun openWifiPickerOrPermissions() {
            if (!hasWifiSsidPermission()) {
                openPermissionsOverview()
            } else {
                openWifiPickerPanel()
            }
        }



        fun showWifiPickerFromResults(results: List<ScanResult>) {
            val ssids = results
                .mapNotNull { cleanSsid(it.SSID) }
                .distinct()
                .sorted()

            if (ssids.isEmpty()) {
                val positiveActionLabel =
                    if (!hasWifiSsidPermission()) R.string.schedules_health_action_permissions
                    else R.string.schedules_wifi_open_picker

                AlertDialog.Builder(this)
                    .setTitle(R.string.schedules_wifi_scan_empty)
                    .setMessage(R.string.schedules_wifi_scan_empty_hint)
                    .setPositiveButton(positiveActionLabel) { _, _ ->
                        openWifiPickerOrPermissions()
                    }
                    .setNeutralButton(R.string.schedules_wifi_enter_manually) { _, _ ->
                        inputWifiSsid.requestFocus()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .showAccented()
                return
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.schedules_wifi_pick_title)
                .setItems(ssids.toTypedArray()) { _, which ->
                    inputWifiSsid.setText(ssids[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .showAccented()
        }

        fun scanWifi() {
            if (!hasWifiSsidPermission()) {
                requestWifiPermissionThenRetry { scanWifi() }
                return
            }
            if (!isLocationEnabled()) {
                markNeedsLocationHintOnce()
                Toast.makeText(this, getString(R.string.schedules_wifi_location_required), Toast.LENGTH_LONG).show()
                openLocationSettings()
                return
            }

            val wifi = getSystemService(WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) {
                Toast.makeText(this, getString(R.string.schedules_wifi_enable_wifi), Toast.LENGTH_LONG).show()
                openWifiPickerPanel()
                return
            }

            val hasFineLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasNearbyWifi = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            val canReadWifi = hasFineLocation || (Build.VERSION.SDK_INT >= 33 && hasNearbyWifi)

            if (!canReadWifi) {
                showWhyLocationDialogForWifi()
                return
            }

            // Best-effort: show cached results if available; otherwise trigger a scan and re-check.
            val cached = try {
                if (canReadWifi) wifi.scanResults else emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }
            if (cached.isNotEmpty()) {
                showWifiPickerFromResults(cached)
                return
            }

            Toast.makeText(this, getString(R.string.schedules_wifi_scanning), Toast.LENGTH_SHORT).show()
            val started = try {
                if (canReadWifi) wifi.startScan() else false
            } catch (_: SecurityException) {
                false
            }

            Handler(Looper.getMainLooper()).postDelayed({
                val fresh = try {
                    if (canReadWifi) wifi.scanResults else emptyList()
                } catch (_: SecurityException) {
                    emptyList()
                }
                if (fresh.isNotEmpty()) {
                    showWifiPickerFromResults(fresh)
                } else {
                    // Many devices/ROMs restrict scanning. Fallback to the system Wi‑Fi picker.
                    val positiveActionLabel =
                        if (!hasWifiSsidPermission()) R.string.schedules_health_action_permissions
                        else R.string.schedules_wifi_open_picker

                    AlertDialog.Builder(this)
                        .setTitle(R.string.schedules_wifi_scan_empty)
                        .setMessage(R.string.schedules_wifi_fix_message)
                        .setPositiveButton(positiveActionLabel) { _, _ ->
                            openWifiPickerOrPermissions()
                        }
                        .setNeutralButton(R.string.schedules_wifi_retry_scan) { _, _ ->
                            scanWifi()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .showAccented()
                }
            }, if (started) 1400L else 200L)
        }

        fun useConnectedBt() {
            if (!hasBluetoothConnectPermission()) {
                requestBluetoothPermissionThenRetry { useConnectedBt() }
                return
            }

            val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, getString(R.string.schedules_bt_enable_bt), Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                return
            }

            val hasBtConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

            if (!hasBtConnect) {
                requestBluetoothPermissionThenRetry { useConnectedBt() }
                return
            }

            val names = linkedSetOf<String>()

            // 1) Classic Bluetooth (A2DP/Headset/etc.): infer connected bonded devices.
            val bonded = runCatching { adapter.bondedDevices }.getOrDefault(emptySet())
            val isConnectedMethod = runCatching {
                Class.forName("android.bluetooth.BluetoothDevice").getMethod("isConnected")
            }.getOrNull()

            bonded.forEach { device ->
                val connected = runCatching {
                    (isConnectedMethod?.invoke(device) as? Boolean) ?: false
                }.getOrDefault(false)

                if (connected) {
                    val n = runCatching { device.name }
                        .getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    if (n != null) names += n
                }
            }

            // 2) BLE fallback via BluetoothManager.
            // Android 16 betas have shown IllegalArgumentException here on some devices/ROMs.
            // Keep this best-effort and never allow it to crash the picker flow.
            val bleProfiles = intArrayOf(BluetoothProfile.GATT)
            for (profile in bleProfiles) {
                val devices = try {
                    manager.getConnectedDevices(profile)
                } catch (_: IllegalArgumentException) {
                    emptyList()
                } catch (_: SecurityException) {
                    emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }

                for (d in devices) {
                    val n = try {
                        d.name?.trim()?.takeIf { it.isNotEmpty() }
                    } catch (_: Throwable) {
                        null
                    }
                    if (n != null) names += n
                }
            }

            val selected = names.firstOrNull()
            if (selected.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.schedules_bt_no_connected), Toast.LENGTH_LONG).show()
                // Helpful fallback: open paired picker so user can still finish quickly.
                btnPickPairedBt.performClick()
                return
            }

            inputBtName.setText(selected)
        }

        fun pickPairedBt() {
            if (!hasBluetoothConnectPermission()) {
                requestBluetoothPermissionThenRetry { pickPairedBt() }
                return
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, getString(R.string.schedules_bt_enable_bt), Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                return
            }

            val hasBtConnect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED

            if (!hasBtConnect) {
                requestBluetoothPermissionThenRetry { pickPairedBt() }
                return
            }

            val bonded = try {
                if (hasBtConnect) adapter.bondedDevices else emptySet()
            } catch (_: SecurityException) {
                emptySet()
            }

            val names = bonded
                .mapNotNull { d ->
                    runCatching { d.name }
                        .getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                .distinct()
                .sorted()

            if (names.isEmpty()) {
                Toast.makeText(this, getString(R.string.schedules_bt_no_paired), Toast.LENGTH_LONG).show()
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                return
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.schedules_bt_pick_paired)
                .setItems(names.toTypedArray()) { _, which ->
                    inputBtName.setText(names[which])
                }
                .setNegativeButton(R.string.cancel, null)
                .showAccented()
        }

        btnScanWifi.setOnClickListener { scanWifi() }
        btnUseConnectedBt.setOnClickListener {
            try {
                useConnectedBt()
            } catch (_: Throwable) {
                Toast.makeText(this, getString(R.string.schedules_bt_no_connected), Toast.LENGTH_LONG).show()
                btnPickPairedBt.performClick()
            }
        }
        btnPickPairedBt.setOnClickListener { pickPairedBt() }

        // Profiles
        val profiles = ProfileStore.getProfiles(this)
        val profileList: List<String> = if (existing != null && existing.profile !in profiles) {
            listOf(existing.profile) + profiles
        } else profiles.toList()

        val profileAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            profileList
        )
        spinnerProfile.setAdapter(profileAdapter)

        var selectedProfileIndex = 0
        spinnerProfile.setOnItemClickListener { _, _, position, _ ->
            selectedProfileIndex = position
        }

        fun selectProfile(name: String?) {
            if (name == null) return
            val idx = profileList.indexOf(name)
            if (idx >= 0) {
                selectedProfileIndex = idx
                spinnerProfile.setText(profileList[idx], false)
            }
        }

        // Defaults
        var startMinutes = if (kind == Kind.TIME) 8 * 60 else 0
        var endMinutes = if (kind == Kind.TIME) 16 * 60 else 24 * 60 - 1
        var startDateYmd = 0
        var endDateYmd = 0

        var selectedTimeModeIndex = 0

        fun currentTimeMode(): TimeMode {
            if (kind != Kind.TIME) return TimeMode.SINGLE
            return when (selectedTimeModeIndex) {
                1 -> TimeMode.TIME_RANGE
                2 -> TimeMode.DATE_RANGE
                else -> TimeMode.SINGLE
            }
        }

        // Setup TimeMode spinner (only meaningful for TIME)
        fun setupTimeModeSpinner() {
            if (kind != Kind.TIME) return

            val nfcLockOn = isNfcLockActiveForSchedules()
            val labels = if (nfcLockOn) {
                listOf(getString(R.string.schedules_time_mode_single))
            } else {
                listOf(
                    getString(R.string.schedules_time_mode_single),
                    getString(R.string.schedules_time_mode_range),
                    getString(R.string.schedules_time_mode_date_range)
                )
            }

            val timeModeAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            spinnerTimeMode.setAdapter(timeModeAdapter)

            val sel = if (nfcLockOn) {
                0
            } else {
                when {
                    existing?.type == ScheduleStore.Type.ONE_TIME -> 2
                    existing?.action == ScheduleStore.Action.ENABLE_AND_DISABLE ||
                        existing?.action == ScheduleStore.Action.DISABLE_AND_ENABLE -> 1
                    else -> 0
                }
            }
            selectedTimeModeIndex = sel
            spinnerTimeMode.setText(labels.getOrNull(sel) ?: labels.firstOrNull().orEmpty(), false)
        }

        // Action spinner (filled in code from strings)
        var actionOptions: List<ScheduleStore.Action> = emptyList()
        var selectedActionIndex = 0

        fun actionLabel(a: ScheduleStore.Action): String = when (a) {
            ScheduleStore.Action.ENABLE -> getString(R.string.schedules_action_enable)
            ScheduleStore.Action.DISABLE -> getString(R.string.schedules_action_disable)
            ScheduleStore.Action.TOGGLE -> getString(R.string.schedules_action_toggle)
            ScheduleStore.Action.ENABLE_AND_DISABLE -> getString(R.string.schedules_action_enable_disable)
            ScheduleStore.Action.DISABLE_AND_ENABLE -> getString(R.string.schedules_action_disable_enable)
        }

        fun setActionOptions(options: List<ScheduleStore.Action>, prefer: ScheduleStore.Action?) {
            actionOptions = options
            val labels = options.map { actionLabel(it) }
            val actionAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
            spinnerAction.setAdapter(actionAdapter)

            val pref = prefer ?: options.firstOrNull()
            val idx = if (pref != null) options.indexOf(pref) else 0
            selectedActionIndex = if (idx >= 0) idx else 0
            spinnerAction.setText(labels.getOrNull(selectedActionIndex) ?: labels.firstOrNull().orEmpty(), false)
        }

        fun selectedAction(): ScheduleStore.Action {
            return actionOptions.getOrNull(selectedActionIndex) ?: ScheduleStore.Action.ENABLE
        }

        fun filterActionsForNfc(options: List<ScheduleStore.Action>): List<ScheduleStore.Action> {
            if (!isNfcLockActiveForSchedules()) return options
            val filtered = options.filterNot { it in nfcLockedActions }
            return if (filtered.isNotEmpty()) filtered else listOf(ScheduleStore.Action.ENABLE)
        }

        fun updateActionNfcHint() {
            val nfcLocked = isNfcLockActiveForSchedules()
            if (!nfcLocked) {
                textActionNfcHint.isVisible = false
                return
            }

            val actionBlocked = selectedAction() in nfcLockedActions
            textActionNfcHint.text = if (actionBlocked) {
                getString(R.string.schedules_dialog_nfc_lock_hint)
            } else {
                getString(R.string.schedules_dialog_nfc_lock_filtered_hint)
            }
            textActionNfcHint.isVisible = true
        }

        fun formatMinutes(m: Int): String {
            val h = m/60
            val mm = m % 60
            return String.format(Locale.getDefault(), "%02d:%02d", h, mm)
        }

        fun formatYmd(ymd: Int): String {
            if (ymd <= 0) return getString(R.string.schedules_date_not_set)
            val y = ymd/10000
            val mo = (ymd/100) % 100
            val d = ymd % 100
            val c = Calendar.getInstance().apply {
                set(Calendar.YEAR, y)
                set(Calendar.MONTH, mo - 1)
                set(Calendar.DAY_OF_MONTH, d)
            }
            return DateFormat.getDateInstance(DateFormat.MEDIUM).format(c.time)
        }

        fun updateActionUi() {
            when (kind) {
                Kind.TIME -> {
                    when (currentTimeMode()) {
                        TimeMode.SINGLE -> {
                            setActionOptions(
                                filterActionsForNfc(
                                    listOf(
                                        ScheduleStore.Action.ENABLE,
                                        ScheduleStore.Action.DISABLE,
                                        ScheduleStore.Action.TOGGLE
                                    )
                                ),
                                existing?.action?.takeIf {
                                    it == ScheduleStore.Action.ENABLE ||
                                        it == ScheduleStore.Action.DISABLE ||
                                        it == ScheduleStore.Action.TOGGLE
                                } ?: ScheduleStore.Action.ENABLE
                            )
                        }

                        TimeMode.TIME_RANGE -> {
                            setActionOptions(
                                filterActionsForNfc(
                                    listOf(
                                        ScheduleStore.Action.ENABLE_AND_DISABLE,
                                        ScheduleStore.Action.DISABLE_AND_ENABLE
                                    )
                                ),
                                existing?.action?.takeIf {
                                    it == ScheduleStore.Action.ENABLE_AND_DISABLE ||
                                        it == ScheduleStore.Action.DISABLE_AND_ENABLE
                                } ?: ScheduleStore.Action.ENABLE_AND_DISABLE
                            )
                        }

                        TimeMode.DATE_RANGE -> {
                            setActionOptions(
                                filterActionsForNfc(listOf(ScheduleStore.Action.ENABLE_AND_DISABLE)),
                                ScheduleStore.Action.ENABLE_AND_DISABLE
                            )
                        }
                    }
                }

                Kind.WIFI, Kind.BT -> {
                    // Connection triggers behave like a range automation layer:
                    // - ENABLE_AND_DISABLE: enable on connect, disable on disconnect
                    // - DISABLE_AND_ENABLE: disable on connect, enable on disconnect
                    // One-shot actions (enable/disable/toggle) were confusing for Wi-Fi/BT schedules
                    // and led to broken UX. Keep the UI limited to the two range modes.

                    val preferred = when (existing?.action) {
                        ScheduleStore.Action.DISABLE_AND_ENABLE -> ScheduleStore.Action.DISABLE_AND_ENABLE
                        // If an old schedule was saved as a one-shot action, map it to a sensible default.
                        ScheduleStore.Action.DISABLE -> ScheduleStore.Action.DISABLE_AND_ENABLE
                        else -> ScheduleStore.Action.ENABLE_AND_DISABLE
                    }
                    val connOptions = if (isNfcLockActiveForSchedules()) {
                        listOf(ScheduleStore.Action.ENABLE)
                    } else {
                        listOf(
                            ScheduleStore.Action.ENABLE_AND_DISABLE,
                            ScheduleStore.Action.DISABLE_AND_ENABLE
                        )
                    }

                    setActionOptions(
                        filterActionsForNfc(connOptions),
                        preferred
                    )
                }
            }
            updateActionNfcHint()
        }

        fun updateVisibilityForMode() {
            when (kind) {
                Kind.TIME -> {
                    when (currentTimeMode()) {
                        TimeMode.SINGLE -> {
                            groupTime.isVisible = true
                            groupTimeRow.isVisible = true
                            textEndTime.isVisible = false
                            groupWeekly.isVisible = true
                            groupOnce.isVisible = false
                        }

                        TimeMode.TIME_RANGE -> {
                            groupTime.isVisible = true
                            groupTimeRow.isVisible = true
                            textEndTime.isVisible = true
                            groupWeekly.isVisible = true
                            groupOnce.isVisible = false
                        }

                        TimeMode.DATE_RANGE -> {
                            groupTime.isVisible = false
                            groupTimeRow.isVisible = false
                            groupWeekly.isVisible = false
                            groupOnce.isVisible = true
                        }
                    }
                }

                Kind.WIFI, Kind.BT -> {
                    // Connection triggers can optionally be limited by a time window.
                    groupTime.isVisible = true
                    // End time is only meaningful when the time window is enabled
                    textEndTime.isVisible = true
                    groupWeekly.isVisible = true
                    groupOnce.isVisible = false

                    val useWindow = switchConnTimeWindow.isChecked
                    groupTimeRow.isVisible = useWindow
                    textConnAllDayHint.isVisible = !useWindow
                }
            }
        }

        fun updateLabels() {
            if (groupTime.isVisible && groupTimeRow.isVisible) {
                textStartTime.text = getString(
                    R.string.schedules_label_value_fmt,
                    getString(R.string.schedules_start_time),
                    formatMinutes(startMinutes)
                )
                if (textEndTime.isVisible) {
                    textEndTime.text = getString(
                        R.string.schedules_label_value_fmt,
                        getString(R.string.schedules_end_time),
                        formatMinutes(endMinutes)
                    )
                }
            }

            if (groupOnce.isVisible) {
                // Two-line label looks cleaner in the dialog (label on top, value below)
                textStartDate.text = getString(
                    R.string.schedules_label_value_multiline_fmt,
                    getString(R.string.schedules_start_date),
                    formatYmd(startDateYmd)
                )
                textEndDate.text = getString(
                    R.string.schedules_label_value_fmt,
                    getString(R.string.schedules_end_date),
                    formatYmd(endDateYmd)
                )
            }
        }

        // Fill from existing/defaults
        if (existing != null) {
            editTitle.setText(existing.title)
            editNote.setText(existing.note)
            selectProfile(existing.profile)

            startMinutes = existing.startMinutes
            endMinutes = existing.endMinutes

            if (existing.type == ScheduleStore.Type.ONE_TIME) {
                startDateYmd = existing.startDate
                endDateYmd = existing.endDate
            }

            val dm = existing.daysMask
            chipMon.isChecked = dm and Days.MON != 0
            chipTue.isChecked = dm and Days.TUE != 0
            chipWed.isChecked = dm and Days.WED != 0
            chipThu.isChecked = dm and Days.THU != 0
            chipFri.isChecked = dm and Days.FRI != 0
            chipSat.isChecked = dm and Days.SAT != 0
            chipSun.isChecked = dm and Days.SUN != 0

            if (kind == Kind.WIFI && isPremium) inputWifiSsid.setText(existing.wifiSsid.orEmpty())
            if (kind == Kind.BT && isPremium) inputBtName.setText(existing.btDeviceName.orEmpty())

        } else {
            selectProfile(ProfileStore.getCurrent(this))

            // Default weekdays Mon–Fri
            chipMon.isChecked = true
            chipTue.isChecked = true
            chipWed.isChecked = true
            chipThu.isChecked = true
            chipFri.isChecked = true
        }

        // Connection schedules: time window is optional.
        // Default: all-day (no time limit) unless an existing schedule actually has a window set.
        val isConnKind = (kind == Kind.WIFI || kind == Kind.BT)
        if (isConnKind) {
            val hasWindow = !(startMinutes == 0 && endMinutes >= 24 * 60 - 1)
            switchConnTimeWindow.isChecked = hasWindow
        }

        applyKindVisibility()
        setupTimeModeSpinner()
        updateActionUi()
        updateVisibilityForMode()
        updateLabels()
        updateActionNfcHint()

        // Connection time window toggle behavior
        switchConnTimeWindow.setOnCheckedChangeListener { _, checked ->
            if (kind != Kind.WIFI && kind != Kind.BT) return@setOnCheckedChangeListener

            if (!checked) {
                // Full day
                startMinutes = 0
                endMinutes = 24 * 60 - 1
            } else {
                // If it was "all day" before, pick a sane default window around "now"
                if (startMinutes == 0 && endMinutes >= 24 * 60 - 1) {
                    val now = Calendar.getInstance()
                    startMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    endMinutes = (startMinutes + 60) % (24 * 60)
                    if (endMinutes == startMinutes) endMinutes = (startMinutes + 30) % (24 * 60)
                }
            }

            updateVisibilityForMode()
            updateLabels()
        }

        spinnerAction.setOnItemClickListener { _, _, pos, _ ->
            selectedActionIndex = pos
            updateActionNfcHint()
        }

        // TimeMode dropdown listener (TIME only)
        spinnerTimeMode.setOnItemClickListener { _, _, pos, _ ->
            if (kind != Kind.TIME) return@setOnItemClickListener
            selectedTimeModeIndex = pos
            updateActionUi()
            updateVisibilityForMode()
            updateLabels()
        }

        // Pickers
        fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
            val h = initial/60
            val m = initial % 60

            // The platform TimePickerDialog often keeps the theme default (green) accent in CUSTOM mode.
            // Use MaterialTimePicker and then run our custom accent pass over the dialog content.
            if (CustomAccentApplier.isCustomAccentEnabled(this)) {
                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(h)
                    .setMinute(m)
                    .build()

                picker.addOnPositiveButtonClickListener {
                    onPicked(picker.hour * 60 + picker.minute)
                    updateLabels()
                }

                val tag = "switchly_timepicker_${SystemClock.uptimeMillis()}"
                picker.show(supportFragmentManager, tag)

                // Apply custom accent after the dialog is attached and views are inflated.
                window.decorView.post {
                    val d = picker.dialog
                    val decor = d?.window?.decorView
                    if (decor != null) {
                        CustomAccentApplier.applyToView(decor, this)
                        longArrayOf(60L, 180L, 360L).forEach { delay ->
                            decor.postDelayed({ runCatching { CustomAccentApplier.applyToView(decor, this) } }, delay)
                        }
                    }
                }
                return
            }

            TimePickerDialog(this, { _, hourOfDay, minute ->
                onPicked(hourOfDay * 60 + minute)
                updateLabels()
            }, h, m, true).show()
        }

        fun pickDate(initialYmd: Int, onPicked: (Int) -> Unit) {
            val cal = Calendar.getInstance()
            if (initialYmd > 0) {
                val y = initialYmd/10000
                val mo = (initialYmd/100) % 100
                val d = initialYmd % 100
                cal.set(y, mo - 1, d)
            }
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                val ymd = year * 10000 + (month + 1) * 100 + dayOfMonth
                onPicked(ymd)
                updateLabels()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        textStartTime.setOnClickListener {
            if (groupTime.isVisible && groupTimeRow.isVisible) pickTime(startMinutes) { startMinutes = it }
        }
        textEndTime.setOnClickListener {
            if (groupTime.isVisible && groupTimeRow.isVisible && textEndTime.isVisible) {
                pickTime(endMinutes) { endMinutes = it }
            }
        }

        textStartDate.setOnClickListener {
            if (groupOnce.isVisible) {
                pickDate(if (startDateYmd > 0) startDateYmd else ScheduleStore.todayYmd()) { startDateYmd = it }
            }
        }
        textEndDate.setOnClickListener {
            if (groupOnce.isVisible) {
                pickDate(if (endDateYmd > 0) endDateYmd else ScheduleStore.todayYmd()) { endDateYmd = it }
            }
        }

        // Quick chips sync (weekly only)
        var isUpdatingQuick = false

        fun updateQuickChipsFromDays() {
            if (isUpdatingQuick) return
            isUpdatingQuick = true

            val mon = chipMon.isChecked
            val tue = chipTue.isChecked
            val wed = chipWed.isChecked
            val thu = chipThu.isChecked
            val fri = chipFri.isChecked
            val sat = chipSat.isChecked
            val sun = chipSun.isChecked

            chipWeekdays.isChecked = mon && tue && wed && thu && fri && !sat && !sun
            chipWeekend.isChecked = !mon && !tue && !wed && !thu && !fri && sat && sun

            val todayDow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            chipToday.isChecked = when (todayDow) {
                Calendar.MONDAY -> mon && !tue && !wed && !thu && !fri && !sat && !sun
                Calendar.TUESDAY -> !mon && tue && !wed && !thu && !fri && !sat && !sun
                Calendar.WEDNESDAY -> !mon && !tue && wed && !thu && !fri && !sat && !sun
                Calendar.THURSDAY -> !mon && !tue && !wed && thu && !fri && !sat && !sun
                Calendar.FRIDAY -> !mon && !tue && !wed && !thu && fri && !sat && !sun
                Calendar.SATURDAY -> !mon && !tue && !wed && !thu && !fri && sat && !sun
                Calendar.SUNDAY -> !mon && !tue && !wed && !thu && !fri && !sat && sun
                else -> false
            }

            isUpdatingQuick = false
        }

        chipWeekdays.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingQuick) return@setOnCheckedChangeListener
            val all = listOf(chipMon, chipTue, chipWed, chipThu, chipFri, chipSat, chipSun)
            if (checked) {
                chipMon.isChecked = true; chipTue.isChecked = true; chipWed.isChecked = true
                chipThu.isChecked = true; chipFri.isChecked = true
                chipSat.isChecked = false; chipSun.isChecked = false
            } else {
                all.forEach { it.isChecked = false }
            }
            updateQuickChipsFromDays()
        }

        chipWeekend.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingQuick) return@setOnCheckedChangeListener
            val all = listOf(chipMon, chipTue, chipWed, chipThu, chipFri, chipSat, chipSun)
            if (checked) {
                chipMon.isChecked = false; chipTue.isChecked = false; chipWed.isChecked = false
                chipThu.isChecked = false; chipFri.isChecked = false
                chipSat.isChecked = true; chipSun.isChecked = true
            } else {
                all.forEach { it.isChecked = false }
            }
            updateQuickChipsFromDays()
        }

        chipToday.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingQuick) return@setOnCheckedChangeListener
            val all = listOf(chipMon, chipTue, chipWed, chipThu, chipFri, chipSat, chipSun)
            all.forEach { it.isChecked = false }
            if (checked) {
                when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> chipMon.isChecked = true
                    Calendar.TUESDAY -> chipTue.isChecked = true
                    Calendar.WEDNESDAY -> chipWed.isChecked = true
                    Calendar.THURSDAY -> chipThu.isChecked = true
                    Calendar.FRIDAY -> chipFri.isChecked = true
                    Calendar.SATURDAY -> chipSat.isChecked = true
                    Calendar.SUNDAY -> chipSun.isChecked = true
                }
            }
            updateQuickChipsFromDays()
        }

        listOf(chipMon, chipTue, chipWed, chipThu, chipFri, chipSat, chipSun).forEach { chip ->
            chip.setOnCheckedChangeListener { _, _ -> updateQuickChipsFromDays() }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.schedules_add else R.string.schedules_edit)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            if (CustomAccentApplier.isCustomAccentEnabled(this)) {
                tintSwitchCompat(switchConnTimeWindow)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {

                layoutWifiSsid.error = null
                layoutBtName.error = null
                inputWifiSsid.error = null
                inputBtName.error = null

                val profile = profileList.getOrNull(selectedProfileIndex)
                    ?: spinnerProfile.text?.toString().orEmpty()
                if (profile.isBlank()) {
                    showSnack(R.string.schedules_error_no_profile)
                    return@setOnClickListener
                }

                // Connection fields
                val wifiSsid: String? = if (kind == Kind.WIFI && isPremium) {
                    inputWifiSsid.text.toString().trim().ifEmpty { null }
                } else null

                val btName: String? = if (kind == Kind.BT && isPremium) {
                    inputBtName.text.toString().trim().ifEmpty { null }
                } else null

                // Validate connection fields if needed
                if (kind == Kind.WIFI && isPremium && wifiSsid.isNullOrBlank()) {
                    layoutWifiSsid.error = getString(R.string.schedules_error_wifi_required)
                    inputWifiSsid.error = getString(R.string.schedules_error_wifi_required)
                    inputWifiSsid.requestFocus()
                    showSnack(R.string.schedules_error_wifi_required)
                    return@setOnClickListener
                }

                if (kind == Kind.BT && isPremium && btName.isNullOrBlank()) {
                    layoutBtName.error = getString(R.string.schedules_error_bt_required)
                    inputBtName.error = getString(R.string.schedules_error_bt_required)
                    inputBtName.requestFocus()
                    showSnack(R.string.schedules_error_bt_required)
                    return@setOnClickListener
                }

                // Permissions only if actually saving those schedule types/conditions
                if (kind == Kind.WIFI && isPremium && !wifiSsid.isNullOrBlank() && !hasWifiSsidPermission()) {
                    showWhyLocationDialogForWifi()
                    return@setOnClickListener
                }

                if (kind == Kind.BT && isPremium && !btName.isNullOrBlank() && !hasBluetoothConnectPermission()) {
                    showWhyBluetoothDialogForSchedules()
                    return@setOnClickListener
                }

                // Days mask (weekly only)
                var daysMask = 0
                val isConn = (kind == Kind.WIFI || kind == Kind.BT)
                val isDateRange = (kind == Kind.TIME && currentTimeMode() == TimeMode.DATE_RANGE)

                if (!isDateRange) {
                    if (chipMon.isChecked) daysMask = daysMask or Days.MON
                    if (chipTue.isChecked) daysMask = daysMask or Days.TUE
                    if (chipWed.isChecked) daysMask = daysMask or Days.WED
                    if (chipThu.isChecked) daysMask = daysMask or Days.THU
                    if (chipFri.isChecked) daysMask = daysMask or Days.FRI
                    if (chipSat.isChecked) daysMask = daysMask or Days.SAT
                    if (chipSun.isChecked) daysMask = daysMask or Days.SUN

                    if (daysMask == 0) {
                        showSnack(R.string.schedules_error_no_days)
                        return@setOnClickListener
                    }
                }

                // Date range validation (time schedules only)
                if (isDateRange) {
                    if (startDateYmd <= 0 || endDateYmd <= 0) {
                        showSnack(R.string.schedules_error_no_dates)
                        return@setOnClickListener
                    }
                    if (endDateYmd < startDateYmd) {
                        showSnack(R.string.schedules_error_date_order)
                        return@setOnClickListener
                    }
                }

                val action: ScheduleStore.Action = when {
                    isConn -> selectedAction()
                    currentTimeMode() == TimeMode.TIME_RANGE -> selectedAction() // includes both range actions
                    currentTimeMode() == TimeMode.DATE_RANGE -> ScheduleStore.Action.ENABLE_AND_DISABLE
                    else -> selectedAction()
                }

                val normalizedStart: Int
                val normalizedEnd: Int

                if (isConn) {
                    normalizedStart = startMinutes
                    normalizedEnd = endMinutes
                } else if (isDateRange) {
                    normalizedStart = 0
                    normalizedEnd = 24 * 60 - 1
                } else {
                    normalizedStart = startMinutes
                    normalizedEnd = when (action) {
                        ScheduleStore.Action.ENABLE_AND_DISABLE,
                        ScheduleStore.Action.DISABLE_AND_ENABLE -> endMinutes
                        else -> startMinutes
                    }
                }

                // Range schedules must have a non-empty time window
                if ((isConn || action == ScheduleStore.Action.ENABLE_AND_DISABLE || action == ScheduleStore.Action.DISABLE_AND_ENABLE) &&
                    normalizedEnd == normalizedStart
                ) {
                    showSnack(R.string.schedules_error_time_range_empty)
                    return@setOnClickListener
                }

                val type: ScheduleStore.Type = when {
                    isConn -> ScheduleStore.Type.WEEKLY
                    isDateRange -> ScheduleStore.Type.ONE_TIME
                    else -> ScheduleStore.Type.WEEKLY
                }

                val newSchedule = ScheduleStore.Schedule(
                    id = existing?.id ?: ScheduleStore.nextId(ScheduleStore.getAll(this)),
                    enabled = existing?.enabled ?: true,
                    profile = profile,
                    title = editTitle.text.toString().trim(),
                    note = editNote.text.toString().trim(),
                    type = type,
                    daysMask = if (type == ScheduleStore.Type.WEEKLY) daysMask else 0,
                    startMinutes = normalizedStart,
                    endMinutes = normalizedEnd,
                    startDate = if (type == ScheduleStore.Type.ONE_TIME) startDateYmd else 0,
                    endDate = if (type == ScheduleStore.Type.ONE_TIME) endDateYmd else 0,
                    wifiSsid = wifiSsid,
                    btDeviceName = btName,
                    action = action
                )

                val oldList = ScheduleStore.getAll(this)
                val newList =
                    if (existing == null) oldList + newSchedule
                    else oldList.map { if (it.id == existing.id) newSchedule else it }

                ScheduleStore.saveAll(this, newList)
                SchedulePlanner.updateNextAlarm(this)
                SchedulePlanner.notifyNextChanged(this)
                refreshList()
                dialog.dismiss()
            }
        }

        dialog.show()
    }
}

// Adapter & ViewHolder
private class ScheduleAdapter(
    private val onToggleEnabled: (ScheduleStore.Schedule, Boolean) -> Unit,
    private val isSelectionMode: () -> Boolean,
    private val isSelected: (Int) -> Boolean,
    private val onToggleSelection: (Int) -> Unit,
    private val onEnterSelection: (Int) -> Unit,
    private val onEdit: (ScheduleStore.Schedule) -> Unit
) : androidx.recyclerview.widget.ListAdapter<ScheduleStore.Schedule, ScheduleViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScheduleViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false)
        return ScheduleViewHolder(v, onToggleEnabled, isSelectionMode, isSelected, onToggleSelection, onEnterSelection, onEdit)
    }

    override fun onBindViewHolder(holder: ScheduleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF =
            object : androidx.recyclerview.widget.DiffUtil.ItemCallback<ScheduleStore.Schedule>() {
                override fun areItemsTheSame(oldItem: ScheduleStore.Schedule, newItem: ScheduleStore.Schedule) =
                    oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: ScheduleStore.Schedule, newItem: ScheduleStore.Schedule) =
                    oldItem == newItem
            }
    }
}

private class ScheduleViewHolder(
    itemView: View,
    private val onToggleEnabled: (ScheduleStore.Schedule, Boolean) -> Unit,
    private val isSelectionMode: () -> Boolean,
    private val isSelected: (Int) -> Boolean,
    private val onToggleSelection: (Int) -> Unit,
    private val onEnterSelection: (Int) -> Unit,
    private val onEdit: (ScheduleStore.Schedule) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val kindIcon = itemView.findViewById<ImageView>(R.id.imgKind)
    private val title = itemView.findViewById<TextView>(R.id.textTitle)
    private val subtitle = itemView.findViewById<TextView>(R.id.textSubtitle)
    private val note = itemView.findViewById<TextView>(R.id.textNote)
    private val switchEnabled = itemView.findViewById<SwitchCompat>(R.id.switchEnabled)
    private val checkSelect = itemView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkSelect)
    private val cardRoot = itemView.findViewById<View>(R.id.cardRoot)

    private var current: ScheduleStore.Schedule? = null
    private var binding = false

    init {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!binding && !isSelectionMode()) current?.let { onToggleEnabled(it, isChecked) }
        }

        cardRoot.setOnClickListener {
            val s = current ?: return@setOnClickListener
            if (isSelectionMode()) {
                onToggleSelection(s.id)
            } else {
                onEdit(s)
            }
        }

        cardRoot.setOnLongClickListener {
            val s = current ?: return@setOnLongClickListener true
            if (!isSelectionMode()) {
                onEnterSelection(s.id)
            }
            true
        }
    }

    private fun fmtMinutes(m: Int): String {
        val h = m/60
        val mm = m % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, mm)
    }

    fun bind(s: ScheduleStore.Schedule) {
        current = s

        binding = true
        switchEnabled.isChecked = s.enabled
        switchEnabled.isEnabled = !isSelectionMode()
        binding = false

        val selecting = isSelectionMode()
        checkSelect.visibility = if (selecting) View.VISIBLE else View.GONE
        checkSelect.isChecked = selecting && isSelected(s.id)

        title.text = s.title.ifBlank { s.profile }
        val ctx = itemView.context

        val hasWifi = !s.wifiSsid.isNullOrBlank()
        val hasBt = !s.btDeviceName.isNullOrBlank()

        // Nice icon in the list: time/wifi/bluetooth (or combined)
        val iconRes = when {
            hasWifi && hasBt -> R.drawable.layers_24
            hasWifi -> R.drawable.wifi_24
            hasBt -> R.drawable.bluetooth_24
            else -> R.drawable.alarm_24
        }
        kindIcon.setImageResource(iconRes)

        // Dim disabled schedules for better overview
        val a = if (s.enabled) 1f else 0.5f
        kindIcon.alpha = a
        title.alpha = a
        subtitle.alpha = a
        note.alpha = a

        val actionLabel = when (s.action) {
            ScheduleStore.Action.ENABLE -> ctx.getString(R.string.schedules_action_enable)
            ScheduleStore.Action.DISABLE -> ctx.getString(R.string.schedules_action_disable)
            ScheduleStore.Action.TOGGLE -> ctx.getString(R.string.schedules_action_toggle)
            ScheduleStore.Action.ENABLE_AND_DISABLE -> ctx.getString(R.string.schedules_action_enable_disable)
            ScheduleStore.Action.DISABLE_AND_ENABLE -> ctx.getString(R.string.schedules_action_disable_enable)
        }

        var timeOrConn: String = if (hasWifi || hasBt) {
            val conn = when {
                hasWifi && hasBt -> ctx.getString(
                    R.string.schedules_conn_wifi_bt_fmt,
                    s.wifiSsid,
                    s.btDeviceName
                )
                hasWifi -> ctx.getString(R.string.schedules_conn_wifi_fmt, s.wifiSsid)
                else -> ctx.getString(R.string.schedules_conn_bt_fmt, s.btDeviceName)
            }
            val base = ctx.getString(R.string.schedules_label_value_fmt, actionLabel, conn)
            val hasWindow = !(s.startMinutes == 0 && s.endMinutes >= 24 * 60 - 1)
            if (hasWindow) {
                val window = ctx.getString(
                    R.string.schedules_time_range_fmt,
                    fmtMinutes(s.startMinutes),
                    fmtMinutes(s.endMinutes)
                )
                "$base · $window"
            } else {
                base
            }
        } else {
            when (s.action) {
                ScheduleStore.Action.ENABLE_AND_DISABLE,
                ScheduleStore.Action.DISABLE_AND_ENABLE -> {
                    ctx.getString(
                        R.string.schedules_time_range_fmt,
                        fmtMinutes(s.startMinutes),
                        fmtMinutes(s.endMinutes)
                    )
                }
                else -> {
                    ctx.getString(
                        R.string.schedules_label_value_fmt,
                        actionLabel,
                        fmtMinutes(s.startMinutes)
                    )
                }
            }
        }

        val daysLabel = when (s.type) {
            ScheduleStore.Type.WEEKLY -> {
                val parts = mutableListOf<String>()
                if (s.daysMask and Days.MON != 0) parts += ctx.getString(R.string.day_short_mon)
                if (s.daysMask and Days.TUE != 0) parts += ctx.getString(R.string.day_short_tue)
                if (s.daysMask and Days.WED != 0) parts += ctx.getString(R.string.day_short_wed)
                if (s.daysMask and Days.THU != 0) parts += ctx.getString(R.string.day_short_thu)
                if (s.daysMask and Days.FRI != 0) parts += ctx.getString(R.string.day_short_fri)
                if (s.daysMask and Days.SAT != 0) parts += ctx.getString(R.string.day_short_sat)
                if (s.daysMask and Days.SUN != 0) parts += ctx.getString(R.string.day_short_sun)
                parts.joinToString(" ")
            }

            ScheduleStore.Type.ONE_TIME -> {
                ctx.getString(
                    R.string.schedules_once_range_fmt,
                    s.startDate.toString(),
                    s.endDate.toString()
                )
            }
        }

        subtitle.text = ctx.getString(R.string.schedules_subtitle_fmt, daysLabel, timeOrConn)

        if (s.note.isNotBlank()) {
            note.visibility = View.VISIBLE
            note.text = s.note
        } else {
            note.visibility = View.GONE
        }
    }
}