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

package at.saltyy.switchly.feature.schedule

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleRuntimeStore
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.ScheduleStore.Days
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.feature.settings.PermissionsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.platform.receiver.location.LocationTriggerMonitor
import at.saltyy.switchly.platform.receiver.schedule.ScheduleReceiver
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.theme.CustomAccentApplier
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.util.BatteryOptimizationCompat
import at.saltyy.switchly.util.SystemBarColorCompat
import at.saltyy.switchly.util.TimeFormatPrefs
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.net.InetAddress
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SchedulesActivity : AppCompatActivity() {

    private enum class NewScheduleMode { TIME, WIFI, BT, LOCATION }
    private enum class Kind { TIME, WIFI, BT, LOCATION }
    private enum class TimeMode { SINGLE, TIME_RANGE, DATE_RANGE }

    private companion object {
        const val PREFS_SCHEDULE_HEALTH = "switchly_schedule_health"
        const val KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE =
            "battery_optimization_confirmed_max_available"
        const val GOOGLE_MAPS_DNS_HOST = "clients4.google.com"
        const val GOOGLE_MAPS_REACHABILITY_TIMEOUT_MS = 2_500L
    }

    private data class ResolvedLocation(
        val latitude: Double,
        val longitude: Double,
        val label: String?
    )

    private lateinit var adapter: ScheduleAdapter
    private lateinit var cardScheduleHealth: View
    private lateinit var tvStatusTitle: TextView
    private lateinit var tvStatusBody: TextView
    private lateinit var tvStatusFooter: TextView
    private lateinit var btnStatusAction: MaterialButton
    private lateinit var rowStatusAction: View
    private lateinit var btnStatusInfo: View
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvStatusActionTitle: TextView
    private lateinit var dividerStatus: View
    private lateinit var switchShowNextSchedule: SwitchCompat

    private var isScheduleUiReadOnly = false

    private var isSelectionMode = false
    private val selectedScheduleIds = linkedSetOf<Int>()

    private var pendingAfterLocationGrant: (() -> Unit)? = null
    private var pendingAfterFineLocationGrant: (() -> Unit)? = null
    private var pendingAfterBluetoothGrant: (() -> Unit)? = null

    private var pendingMapPickerCallback: ((ResolvedLocation) -> Unit)? = null

    private val locationMapPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingMapPickerCallback
            pendingMapPickerCallback = null

            if (result.resultCode != Activity.RESULT_OK || callback == null) return@registerForActivityResult

            val data = result.data ?: return@registerForActivityResult
            val latitude = data.getDoubleExtra(LocationMapPickerActivity.EXTRA_LATITUDE, Double.NaN)
            val longitude = data.getDoubleExtra(LocationMapPickerActivity.EXTRA_LONGITUDE, Double.NaN)
            if (latitude.isNaN() || longitude.isNaN()) return@registerForActivityResult

            callback(
                ResolvedLocation(
                    latitude = latitude,
                    longitude = longitude,
                    label = data.getStringExtra(LocationMapPickerActivity.EXTRA_LABEL)
                )
            )
        }

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
            refreshList()
        }

    private val requestFineLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingAfterFineLocationGrant
            pendingAfterFineLocationGrant = null
            if (granted) {
                action?.invoke()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.perm_location_denied_geofence_schedule),
                    Toast.LENGTH_SHORT
                ).show()
            }
            refreshList()
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
            refreshList()
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

    private fun isScheduleEditingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this) && !EmergencyBypassStore.isActive(this)
    }

    private fun canEditSchedules(): Boolean {
        return isScheduleAutomationAllowed() && !isScheduleEditingLocked()
    }

    private fun currentAutomationModeLabel(): String {
        return when (AutomationModeStore.getMode(this)) {
            AutomationModeStore.Mode.SCHEDULE -> getString(R.string.pref_mode_schedule_title)
            AutomationModeStore.Mode.NFC -> getString(R.string.pref_mode_nfc_title)
            AutomationModeStore.Mode.QR -> getString(R.string.pref_mode_qr_title)
            AutomationModeStore.Mode.BARCODE -> getString(R.string.pref_mode_barcode_title)
            AutomationModeStore.Mode.MIXED -> getString(R.string.pref_mode_mixed_title)
        }
    }

    private fun openProtectionControls() {
        startActivity(
            Intent(this, ToggleOptionsActivity::class.java).apply {
                putExtra(
                    ToggleOptionsActivity.EXTRA_SCROLL_TO_SECTION,
                    ToggleOptionsActivity.SECTION_BLOCKING
                )
            }
        )
    }

    private fun showSchedulesReadOnlyToast() {
        val msgRes = if (!isScheduleAutomationAllowed()) {
            R.string.schedules_disabled_read_only_toast
        } else {
            R.string.schedules_locked_read_only_toast
        }
        Toast.makeText(this, getString(msgRes), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedules)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = toolbar
        )

        SystemBarColorCompat.setStatusBarColor(window, ContextCompat.getColor(this, android.R.color.black))
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val recycler = findViewById<RecyclerView>(R.id.recyclerSchedules)
        recycler.layoutManager = LinearLayoutManager(this)

        cardScheduleHealth = findViewById(R.id.cardScheduleHealth)
        tvStatusTitle = cardScheduleHealth.findViewById(R.id.tvStatusTitle)
        tvStatusBody = cardScheduleHealth.findViewById(R.id.tvStatusBody)
        tvStatusFooter = cardScheduleHealth.findViewById(R.id.tvStatusFooter)
        btnStatusAction = cardScheduleHealth.findViewById(R.id.btnStatusAction)
        rowStatusAction = cardScheduleHealth.findViewById(R.id.rowStatusAction)
        btnStatusInfo = cardScheduleHealth.findViewById(R.id.btnStatusInfo)
        ivStatusIcon = cardScheduleHealth.findViewById(R.id.ivStatusIcon)
        tvStatusActionTitle = cardScheduleHealth.findViewById(R.id.tvStatusActionTitle)
        dividerStatus = cardScheduleHealth.findViewById(R.id.dividerStatus)

        ivStatusIcon.setImageResource(R.drawable.schedule_24)
        tvStatusActionTitle.setText(R.string.schedules_health_action_title)
        btnStatusInfo.visibility = View.VISIBLE
        btnStatusInfo.setOnClickListener { showScheduleHealthInfoDialog() }

        val rowShowNextSchedule = findViewById<View>(R.id.rowShowNextSchedule)
        switchShowNextSchedule = findViewById(R.id.switchShowNextSchedule)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread { refreshList() }
                }
            }
        }
        val uiPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        switchShowNextSchedule.isChecked =
            uiPrefs.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        tintSwitchCompat(switchShowNextSchedule)

        rowShowNextSchedule.setOnClickListener { switchShowNextSchedule.toggle() }
        switchShowNextSchedule.setOnCheckedChangeListener { _, isChecked ->
            uiPrefs.edit { putBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, isChecked) }
            SchedulePlanner.notifyNextChanged(this)
        }

        adapter = ScheduleAdapter(
            onToggleEnabled = { schedule, enabled ->
                if (!canEditSchedules()) {
                    showSchedulesReadOnlyToast()
                } else {
                    val list = ScheduleStore.getAll(this).map {
                        if (it.id == schedule.id) it.copy(enabled = enabled) else it
                    }
                    ScheduleStore.saveAll(this, list)
                    LocationTriggerMonitor.syncNow(this)
                    reapplySchedulesNow()
                    SchedulePlanner.updateNextAlarm(this)
                    SchedulePlanner.notifyNextChanged(this)
                }
            },
            canInteract = { canEditSchedules() },
            isSelectionMode = { isSelectionMode },
            isSelected = { id -> selectedScheduleIds.contains(id) },
            onToggleSelection = { id -> toggleSelection(id) },
            onEnterSelection = { preselectId -> enterSelectionMode(preselectId) },
            onEdit = { schedule ->
                if (!canEditSchedules()) {
                    showSchedulesReadOnlyToast()
                } else {
                    showScheduleDialog(existing = schedule, preselectedMode = null)
                }
            }
        )
        recycler.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            if (!canEditSchedules()) {
                showSchedulesReadOnlyToast()
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
        val canInteract = canEditSchedules()
        val hasItems = adapter.itemCount > 0
        menu.findItem(R.id.action_select)?.isVisible = canInteract && !isSelectionMode && hasItems
        menu.findItem(R.id.action_cancel_selection)?.isVisible = canInteract && isSelectionMode
        menu.findItem(R.id.action_delete_selected)?.isVisible = canInteract && isSelectionMode

        val del = menu.findItem(R.id.action_delete_selected)
        val canDelete = canInteract && selectedScheduleIds.isNotEmpty()
        del?.isEnabled = canDelete
        del?.alphaCompat(if (canDelete) 1f else 0.4f)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select -> {
                if (!canEditSchedules()) {
                    showSchedulesReadOnlyToast()
                } else {
                    enterSelectionMode(null)
                }
                true
            }
            R.id.action_cancel_selection -> {
                exitSelectionMode()
                true
            }
            R.id.action_delete_selected -> {
                if (!canEditSchedules()) {
                    showSchedulesReadOnlyToast()
                } else {
                    confirmDeleteSelectedSchedules()
                }
                true
            }
            R.id.action_info -> {
                showScheduleActionInfoDialog()
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
        val canInteract = canEditSchedules()
        findViewById<View>(R.id.fabAdd)?.visibility =
            if (isSelectionMode || !canInteract) View.GONE else View.VISIBLE
    }

    private fun enterSelectionMode(preselectId: Int?) {
        if (!canEditSchedules()) {
            showSchedulesReadOnlyToast()
            return
        }
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
        if (selectedScheduleIds.contains(id)) {
            selectedScheduleIds.remove(id)
        } else {
            selectedScheduleIds.add(id)
        }
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
                val remaining = ScheduleStore.getAll(this)
                    .filterNot { selectedScheduleIds.contains(it.id) }
                ScheduleStore.saveAll(this, remaining)
                LocationTriggerMonitor.syncNow(this)
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

        ExactAlarmPermissionSync.syncAndReschedule(this, reason = "schedules_resume")

        val uiPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        switchShowNextSchedule.isChecked =
            uiPrefs.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        tintSwitchCompat(switchShowNextSchedule)

        refreshList()

        if (!isBatteryOptimizationLikelyActive() &&
            isBatteryOptimizationUserConfirmedMaxAvailable()
        ) {
            setBatteryOptimizationUserConfirmedMaxAvailable(false)
        }

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
        if (!canEditSchedules()) {
            showSchedulesReadOnlyToast()
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
                add(TypeItem(getString(R.string.schedules_type_location), R.drawable.location_on_24, NewScheduleMode.LOCATION))
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
                    NewScheduleMode.LOCATION -> showScheduleDialog(null, NewScheduleMode.LOCATION)
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

    private fun showScheduleActionInfoDialog() {
        val bodyView = TextView(this).apply {
            text = buildScheduleActionInfoBody()
            textSize = 14f
            setLineSpacing(0f, 1.18f)
        }

        val scroll = ScrollView(this).apply {
            val padH = (20 * resources.displayMetrics.density).toInt()
            val padV = (8 * resources.displayMetrics.density).toInt()
            setPadding(padH, padV, padH, padV)
            addView(
                bodyView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.schedules_action_info_title)
            .setView(scroll)
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    private fun buildScheduleActionInfoBody(): CharSequence {
        val sb = SpannableStringBuilder()

        fun addItem(title: String, desc: String) {
            val titleStart = sb.length
            sb.append("• ").append(title)
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                titleStart + 2,
                titleStart + 2 + title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.append("  ").append(desc.trim()).append('\n')
        }

        addItem(
            getString(R.string.schedules_action_enable),
            getString(R.string.schedules_action_info_enable_body)
        )
        addItem(
            getString(R.string.schedules_action_disable),
            getString(R.string.schedules_action_info_disable_body)
        )
        addItem(
            getString(R.string.schedules_action_toggle),
            getString(R.string.schedules_action_info_toggle_body)
        )
        addItem(
            getString(R.string.schedules_action_enable_disable),
            getString(R.string.schedules_action_info_enable_disable_body)
        )
        addItem(
            getString(R.string.schedules_action_disable_enable),
            getString(R.string.schedules_action_info_disable_enable_body)
        )

        sb.append(getString(R.string.schedules_action_info_tip))
        return sb
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
        val sorted = list.sortedWith(scheduleDisplayComparator())
        val readOnlyNow = !canEditSchedules()
        val readOnlyChanged = isScheduleUiReadOnly != readOnlyNow
        isScheduleUiReadOnly = readOnlyNow

        adapter.submitList(sorted) {
            if (readOnlyChanged) {
                adapter.notifyItemRangeChanged(0, adapter.itemCount)
            }
        }

        val ids = list.map { it.id }.toSet()
        selectedScheduleIds.retainAll(ids)
        if (isSelectionMode && (selectedScheduleIds.isEmpty() || isScheduleUiReadOnly)) {
            exitSelectionMode()
        } else {
            updateMenuState()
        }
        updateScheduleHealthBanner()
    }

    private fun scheduleDisplayComparator(): Comparator<ScheduleStore.Schedule> {
        return compareBy<ScheduleStore.Schedule>(
            { it.startMinutes.coerceAtLeast(0) },
            { if (it.type == ScheduleStore.Type.ONE_TIME) 0 else 1 },
            { if (it.type == ScheduleStore.Type.ONE_TIME) it.startDate else Int.MAX_VALUE },
            { if (it.type == ScheduleStore.Type.WEEKLY) weeklySortKey(it.daysMask) else Int.MAX_VALUE },
            { it.title.lowercase() },
            { it.id }
        )
    }

    private fun weeklySortKey(daysMask: Int): Int {
        val order = listOf(
            Days.MON,
            Days.TUE,
            Days.WED,
            Days.THU,
            Days.FRI,
            Days.SAT,
            Days.SUN,
        )
        return order.indexOfFirst { daysMask and it != 0 }
            .takeIf { it >= 0 } ?: Int.MAX_VALUE
    }

    private fun canScheduleExactAlarms(): Boolean {
        return ExactAlarmPermissionSync.canScheduleExactAlarms(this)
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
        val before = isBatteryOptimizationLikelyActive()

        // Play-policy safe: do not trigger the direct ignore-battery-optimization exemption popup. 
        // Open normal settings pages and let the user manually choose Unrestricted/Not optimized.
        val intents = listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = "package:$packageName".toUri()
            }
        )

        intents.firstOrNull { intent ->
            runCatching {
                startActivity(intent)
                true
            }.getOrDefault(false)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val stillActive = isBatteryOptimizationLikelyActive()
            if (before && stillActive && !isBatteryOptimizationUserConfirmedMaxAvailable()) {
                showBatteryOptimizationMaxAvailableDialog()
            } else {
                refreshList()
            }
        }, 1400L)
    }

    private fun isBatteryOptimizationLikelyActive(): Boolean {
        return BatteryOptimizationCompat.isLikelyStillRestricted(this)
    }

    private fun isBatteryOptimizationUserConfirmedMaxAvailable(): Boolean {
        return BatteryOptimizationCompat.isUserConfirmedMaxAvailable(this)
    }

    private fun setBatteryOptimizationUserConfirmedMaxAvailable(value: Boolean) {
        getSharedPreferences(PREFS_SCHEDULE_HEALTH, MODE_PRIVATE).edit {
            putBoolean(KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE, value)
        }
    }

    private fun isBatteryOptimizationEffectivelyOk(): Boolean {
        return BatteryOptimizationCompat.isEffectivelyOk(this)
    }

    private fun showBatteryOptimizationMaxAvailableDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedules_health_battery_title)
            .setMessage(R.string.schedules_health_battery_max_available_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.schedules_health_action_mark_battery_done) { _, _ ->
                setBatteryOptimizationUserConfirmedMaxAvailable(true)
                refreshList()
            }
            .showAccented()
    }

    private fun updateScheduleHealthBanner() {
        val schedules = ScheduleStore.getAll(this)
        val enabledSchedules = schedules.filter { it.enabled }

        if (!isScheduleAutomationAllowed()) {
            val modeLabel = currentAutomationModeLabel()
            cardScheduleHealth.isVisible = true
            btnStatusInfo.isVisible = false
            tvStatusTitle.text = getString(R.string.schedules_disabled_title)
            tvStatusBody.isVisible = true
            tvStatusBody.text = getString(R.string.schedules_disabled_body)
            tvStatusFooter.isVisible = true
            tvStatusFooter.text = if (AutomationModeStore.getMode(this) == AutomationModeStore.Mode.MIXED) {
                getString(R.string.schedules_disabled_footer_mixed_off, modeLabel)
            } else {
                getString(R.string.schedules_disabled_footer_mode, modeLabel)
            }
            btnStatusAction.isVisible = true
            btnStatusAction.setText(R.string.schedules_disabled_action_open_controls)
            btnStatusAction.setOnClickListener { openProtectionControls() }
            rowStatusAction.isVisible = true
            dividerStatus.isVisible = true
            return
        }

        if (isScheduleEditingLocked()) {
            val modeLabel = currentAutomationModeLabel()
            cardScheduleHealth.isVisible = true
            btnStatusInfo.isVisible = false
            tvStatusTitle.text = getString(R.string.schedules_locked_title)
            tvStatusBody.isVisible = true
            tvStatusBody.text = getString(R.string.schedules_locked_body)
            tvStatusFooter.isVisible = true
            tvStatusFooter.text = getString(R.string.schedules_locked_footer, modeLabel)
            btnStatusAction.isVisible = true
            btnStatusAction.setText(R.string.schedules_locked_action_open_controls)
            btnStatusAction.setOnClickListener { openProtectionControls() }
            rowStatusAction.isVisible = true
            dividerStatus.isVisible = true
            return
        }

        btnStatusInfo.isVisible = true

        if (enabledSchedules.isEmpty()) {
            cardScheduleHealth.isVisible = false
            return
        }

        val batteryOptimizationActive = !isBatteryOptimizationEffectivelyOk()
        val exactAlarmsAllowed = canScheduleExactAlarms()
        val accessibilityActive = BlockingRuntime.isAccessibilityActive(this)

        val wifiSchedulesNeedPerm = enabledSchedules.any { !it.wifiSsid.isNullOrBlank() }
        val btSchedulesNeedPerm = enabledSchedules.any { !it.btDeviceName.isNullOrBlank() || !it.btDeviceAddress.isNullOrBlank() }
        val locationSchedulesNeedPerm = enabledSchedules.any { it.isLocationSchedule() }
        val wifiPermMissing = wifiSchedulesNeedPerm && !hasWifiSsidPermission()
        val btPermMissing = btSchedulesNeedPerm && !hasBluetoothConnectPermission()
        val locationPermMissing = locationSchedulesNeedPerm && !hasLocationSchedulePermission()

        val nfcLockActiveForSchedules = isNfcLockActiveForSchedules()
        val nfcConflict = nfcLockActiveForSchedules &&
            enabledSchedules.any { it.action in nfcLockedActions }

        val blockedAt = ScheduleRuntimeStore.getLastDisableBlockedByNfcMs(this)
        val nfcBlockedRecently = nfcLockActiveForSchedules &&
            blockedAt > 0L &&
            (System.currentTimeMillis() - blockedAt) < 24L * 60L * 60L * 1000L

        val hasPermissionIssue = !accessibilityActive || wifiPermMissing || btPermMissing ||
            locationPermMissing || batteryOptimizationActive || !exactAlarmsAllowed
        val hasAnyIssue = hasPermissionIssue || nfcConflict || nfcBlockedRecently

        if (!hasAnyIssue) {
            cardScheduleHealth.isVisible = false
            return
        }

        val lastExecMs = ScheduleRuntimeStore.getLastExecutionMs(this)
        val lastExecText = if (lastExecMs > 0L) {
            runCatching {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(lastExecMs))
            }.getOrDefault(DateFormat.getDateTimeInstance().format(Date(lastExecMs)))
        } else {
            getString(R.string.schedules_health_last_exec_never)
        }

        cardScheduleHealth.isVisible = true
        tvStatusTitle.text = getString(R.string.schedules_health_generic_issue)
        tvStatusBody.isVisible = false
        tvStatusBody.text = ""
        tvStatusFooter.isVisible = true
        tvStatusFooter.text = getString(R.string.schedules_health_last_exec_compact, lastExecText)

        when {
            nfcConflict || nfcBlockedRecently -> {
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_toggle_options)
                btnStatusAction.setOnClickListener {
                    startActivity(Intent(this, ToggleOptionsActivity::class.java))
                }
            }
            else -> {
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_permissions)
                btnStatusAction.setOnClickListener { openPermissionsOverview() }
            }
        }

        rowStatusAction.isVisible = btnStatusAction.isVisible
        dividerStatus.isVisible = rowStatusAction.isVisible
    }

    private fun showScheduleHealthInfoDialog() {
        if (!isScheduleAutomationAllowed()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.schedules_disabled_title)
                .setMessage(R.string.schedules_disabled_body)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.schedules_disabled_action_open_controls) { _, _ ->
                    openProtectionControls()
                }
                .showAccented()
            return
        }

        if (isScheduleEditingLocked()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.schedules_locked_title)
                .setMessage(R.string.schedules_locked_body)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.schedules_locked_action_open_controls) { _, _ ->
                    openProtectionControls()
                }
                .showAccented()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.schedules_health_info_title)
            .setMessage(R.string.schedules_health_info_body)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.schedules_health_action_permissions) { _, _ ->
                openPermissionsOverview()
            }
            .showAccented()
    }

    private fun wifiSsidPermissionName(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    private fun hasNearbyWifiPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationSchedulePermission(): Boolean {
        return hasFineLocationPermission() && hasBackgroundLocationPermission()
    }

    private fun hasWifiSsidPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            hasNearbyWifiPermission() || hasFineLocationPermission()
        } else {
            hasFineLocationPermission()
        }
    }

    private fun reapplySchedulesNow() {
        ScheduleRuntimeStore.resetActiveScheduleState(this)
        sendBroadcast(
            Intent(this, ScheduleReceiver::class.java).apply {
                action = ScheduleReceiver.ACTION_TICK
                putExtra("alarm_reason", "schedule_edit")
            }
        )
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

    private fun showWhyLocationDialogForGeofence(onGranted: (() -> Unit)? = null) {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_location_title)
            .setMessage(R.string.perm_location_why_geofence)
            .setPositiveButton(R.string.ok) { _, _ ->
                pendingAfterFineLocationGrant = onGranted
                requestFineLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNeutralButton(R.string.schedules_health_action_permissions) { _, _ ->
                openPermissionsOverview()
            }
            .setNegativeButton(R.string.cancel, null)
            .showAccented()
    }

    private fun showWhyBackgroundLocationDialogForGeofence() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_location_background_title)
            .setMessage(R.string.perm_location_background_why_geofence)
            .setPositiveButton(R.string.schedules_health_action_permissions) { _, _ ->
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
        val themedCtx = androidx.appcompat.view.ContextThemeWrapper(
            this,
            R.style.ThemeOverlay_Switchly_Dialog
        )
        val view = LayoutInflater.from(themedCtx)
            .inflate(R.layout.dialog_schedule_add, null, false)

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

        val inputLocationLabel = view.findViewById<EditText>(R.id.inputLocationLabel)
        val layoutLocationLabel = view.findViewById<TextInputLayout>(R.id.layoutLocationLabel)
        val groupLocationActions = view.findViewById<View>(R.id.groupLocationActions)
        val spinnerLocationTrigger = view.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerLocationTrigger)
        val textLocationSummary = view.findViewById<TextView>(R.id.textLocationSummary)
        val btnUseCurrentLocation = view.findViewById<MaterialButton>(R.id.btnUseCurrentLocation)
        val spinnerLocationCooldown = view.findViewById<android.widget.AutoCompleteTextView>(R.id.spinnerLocationCooldown)
        val chipRadius100 = view.findViewById<Chip>(R.id.chipRadius100)
        val chipRadius250 = view.findViewById<Chip>(R.id.chipRadius250)
        val chipRadius500 = view.findViewById<Chip>(R.id.chipRadius500)

        if (isCustomAccent) {
            tintPickButton(btnScanWifi)
            tintPickButton(btnUseConnectedBt)
            tintPickButton(btnPickPairedBt)
            tintPickButton(btnUseCurrentLocation)
            tintSwitchCompat(switchConnTimeWindow)
            val accent = AccentColor.getAccentColorInt(this)
            layoutWifiSsid.boxStrokeColor = accent
            layoutBtName.boxStrokeColor = accent
            layoutLocationLabel.boxStrokeColor = accent
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

        fun applyDayChipColors(chip: Chip) {
            val primary = if (isCustomAccent) {
                AccentColor.getAccentColorInt(this@SchedulesActivity)
            } else {
                MaterialColors.getColor(chip, androidx.appcompat.R.attr.colorPrimary)
            }

            val onPrimary = if (isCustomAccent) {
                val black = ColorUtils.calculateContrast(Color.BLACK, primary)
                val white = ColorUtils.calculateContrast(Color.WHITE, primary)
                if (black >= white) Color.BLACK else Color.WHITE
            } else {
                MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnPrimary)
            }

            val onSurface =
                MaterialColors.getColor(chip, com.google.android.material.R.attr.colorOnSurface)

            val bgUnchecked = MaterialColors.compositeARGBWithAlpha(onSurface, 0x14)
            val strokeUnchecked = MaterialColors.compositeARGBWithAlpha(onSurface, 0x3D)

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
            preselectedMode == NewScheduleMode.LOCATION -> Kind.LOCATION
            preselectedMode == NewScheduleMode.TIME -> Kind.TIME
            existing?.wifiSsid?.isNotBlank() == true -> Kind.WIFI
            (existing?.btDeviceName?.isNotBlank() == true || existing?.btDeviceAddress?.isNotBlank() == true) -> Kind.BT
            existing?.isLocationSchedule() == true -> Kind.LOCATION
            else -> Kind.TIME
        }

        if (!isPremium) {
            layoutWifiSsid.visibility = View.GONE
            layoutBtName.visibility = View.GONE
            layoutLocationLabel.visibility = View.GONE
        }

        fun applyKindVisibility() {
            val isTime = kind == Kind.TIME
            groupTimeMode.isVisible = isTime

            val showWifi = isPremium && kind == Kind.WIFI
            val showBt = isPremium && kind == Kind.BT
            val showLocation = isPremium && kind == Kind.LOCATION

            layoutWifiSsid.isVisible = showWifi
            groupWifiActions.isVisible = showWifi
            layoutBtName.isVisible = showBt
            groupBtActions.isVisible = showBt
            layoutLocationLabel.isVisible = showLocation
            groupLocationActions.isVisible = showLocation

            val isConn = kind == Kind.WIFI || kind == Kind.BT || kind == Kind.LOCATION
            switchConnTimeWindow.isVisible = isConn
            textConnAllDayHint.isVisible = false
        }

        fun requestWifiPermissionThenRetry(action: () -> Unit) {
            pendingAfterLocationGrant = action
            requestLocationPermission.launch(wifiSsidPermissionName())
        }

        fun requestFineLocationPermissionThenRetry(action: () -> Unit) {
            pendingAfterFineLocationGrant = action
            requestFineLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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

        fun scanResultSsid(scan: ScanResult): String? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                scan.wifiSsid?.toString()
            } else {
                runCatching { scan.javaClass.getField("SSID").get(scan) as? String }.getOrNull()
            }
        }

        fun markNeedsLocationHintOnce() {
            getSharedPreferences("switchly_wifi_cache", MODE_PRIVATE).edit {
                putBoolean("wifi_needs_location_hint", true)
            }
        }

        fun openLocationSettings() {
            runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }

        fun openWifiPickerPanel() {
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
                .mapNotNull { cleanSsid(scanResultSsid(it)) }
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
                Toast.makeText(
                    this,
                    getString(R.string.schedules_wifi_location_required),
                    Toast.LENGTH_LONG
                ).show()
                openLocationSettings()
                return
            }

            val wifi = getSystemService(WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) {
                Toast.makeText(
                    this,
                    getString(R.string.schedules_wifi_enable_wifi),
                    Toast.LENGTH_LONG
                ).show()
                openWifiPickerPanel()
                return
            }

            val hasFineLocation =
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            val hasNearbyWifi = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            val canReadWifi = if (Build.VERSION.SDK_INT >= 33) hasNearbyWifi else hasFineLocation

            if (!canReadWifi) {
                showWhyLocationDialogForWifi()
                return
            }

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
                false
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
            val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
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

        var locationLat: Double? = existing?.locationLat
        var locationLng: Double? = existing?.locationLng
        var locationRadiusMeters = existing?.locationRadiusMeters ?: 250
        var locationTrigger = existing?.locationTrigger ?: ScheduleStore.LocationTrigger.ENTER
        val cooldownOptions = listOf(0, 5, 15, 30)
        var selectedCooldownMinutes = existing?.locationCooldownMinutes ?: 15
        var refreshActionUi: () -> Unit = {}

        fun selectedRadiusMeters(): Int = when {
            chipRadius100.isChecked -> 100
            chipRadius500.isChecked -> 500
            else -> 250
        }

        fun syncRadiusChips() {
            when (locationRadiusMeters) {
                100 -> chipRadius100.isChecked = true
                500 -> chipRadius500.isChecked = true
                else -> chipRadius250.isChecked = true
            }
        }

        fun currentLocationLabelText(): String {
            val manual = inputLocationLabel.text?.toString()?.trim().orEmpty()
            if (manual.isNotBlank()) return manual
            val lat = locationLat
            val lng = locationLng
            if (lat == null || lng == null) return getString(R.string.schedules_location_not_set)
            return getString(R.string.schedules_location_coords_fmt, lat, lng)
        }

        fun updateLocationSummary() {
            val lat = locationLat
            val lng = locationLng
            if (lat == null || lng == null) {
                textLocationSummary.text = getString(R.string.schedules_location_not_set)
            } else {
                val label = currentLocationLabelText()
                textLocationSummary.text = getString(
                    R.string.schedules_location_selected_fmt,
                    label,
                    selectedRadiusMeters()
                )
            }
        }

        fun applyPickedLocation(latitude: Double, longitude: Double, suggestedLabel: String?) {
            val previousLat = locationLat
            val previousLng = locationLng
            val previousCoordsLabel = if (previousLat != null && previousLng != null) {
                getString(R.string.schedules_location_coords_fmt, previousLat, previousLng)
            } else {
                ""
            }
            val manualLabel = inputLocationLabel.text?.toString()?.trim().orEmpty()
            locationLat = latitude
            locationLng = longitude
            val replacementLabel = suggestedLabel?.trim().orEmpty()
            if (replacementLabel.isNotBlank() && (manualLabel.isBlank() || manualLabel == previousCoordsLabel)) {
                inputLocationLabel.setText(replacementLabel)
            }
            updateLocationSummary()
        }

        fun setupLocationTriggerSpinner() {
            val triggerOptions = listOf(
                ScheduleStore.LocationTrigger.ENTER,
                ScheduleStore.LocationTrigger.EXIT,
                ScheduleStore.LocationTrigger.ENTER_EXIT
            )
            val labels = triggerOptions.map {
                when (it) {
                    ScheduleStore.LocationTrigger.ENTER -> getString(R.string.schedules_location_trigger_enter)
                    ScheduleStore.LocationTrigger.EXIT -> getString(R.string.schedules_location_trigger_exit)
                    ScheduleStore.LocationTrigger.ENTER_EXIT -> getString(R.string.schedules_location_trigger_both)
                }
            }
            spinnerLocationTrigger.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
            val idx = triggerOptions.indexOf(locationTrigger).takeIf { it >= 0 } ?: 0
            spinnerLocationTrigger.setText(labels[idx], false)
            spinnerLocationTrigger.setOnItemClickListener { _, _, position, _ ->
                locationTrigger = triggerOptions.getOrElse(position) { ScheduleStore.LocationTrigger.ENTER }
                refreshActionUi()
            }
        }

        fun setupLocationCooldownSpinner() {
            val labels = cooldownOptions.map {
                when (it) {
                    0 -> getString(R.string.schedules_location_cooldown_none)
                    5 -> getString(R.string.schedules_location_cooldown_5)
                    15 -> getString(R.string.schedules_location_cooldown_15)
                    else -> getString(R.string.schedules_location_cooldown_30)
                }
            }
            spinnerLocationCooldown.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
            val idx = cooldownOptions.indexOf(selectedCooldownMinutes).takeIf { it >= 0 } ?: 2
            selectedCooldownMinutes = cooldownOptions[idx]
            spinnerLocationCooldown.setText(labels[idx], false)
            spinnerLocationCooldown.setOnItemClickListener { _, _, position, _ ->
                selectedCooldownMinutes = cooldownOptions.getOrElse(position) { 15 }
            }
        }

        fun useCurrentLocation() {
            if (!hasFineLocationPermission()) {
                showWhyLocationDialogForGeofence { useCurrentLocation() }
                return
            }
            if (!isLocationEnabled()) {
                Toast.makeText(this, getString(R.string.schedules_wifi_location_required), Toast.LENGTH_LONG).show()
                openLocationSettings()
                return
            }

            Toast.makeText(this, getString(R.string.schedules_location_fetching), Toast.LENGTH_SHORT).show()
            val client = LocationServices.getFusedLocationProviderClient(this)
            val cts = CancellationTokenSource()
            runCatching {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                    .addOnSuccessListener { loc ->
                        if (loc != null) {
                            val fallbackLabel = getString(
                                R.string.schedules_location_coords_fmt,
                                loc.latitude,
                                loc.longitude
                            )
                            reverseGeocodeLabel(loc.latitude, loc.longitude, fallbackLabel) { label ->
                                applyPickedLocation(loc.latitude, loc.longitude, label ?: fallbackLabel)
                            }
                        } else {
                            Toast.makeText(this, getString(R.string.schedules_location_not_set), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, getString(R.string.schedules_location_not_set), Toast.LENGTH_SHORT).show()
                    }
            }.onFailure {
                Toast.makeText(this, getString(R.string.schedules_location_not_set), Toast.LENGTH_SHORT).show()
            }
        }

        fun openLocationSearchDialog() {
            val seedQuery = inputLocationLabel.text?.toString()?.trim().orEmpty()
            showLocationPickerDialog(
                initialQuery = seedQuery,
                onUseCurrentLocation = { useCurrentLocation() }
            ) { picked ->
                applyPickedLocation(picked.latitude, picked.longitude, picked.label)
            }
        }

        fun openVisualLocationPickerDialog() {
            if (!BuildConfig.SWITCHLY_HAS_MAPS_API_KEY) {
                Toast.makeText(
                    this,
                    getString(R.string.schedules_location_map_picker_unavailable),
                    Toast.LENGTH_LONG
                ).show()
                openLocationSearchDialog()
                return
            }

            lifecycleScope.launch {
                val mapsReachable = canResolveGoogleMapsHost()
                if (!mapsReachable) {
                    showGoogleMapsUnavailableDialog(
                        onUseSearch = { openLocationSearchDialog() },
                        onUseCurrentLocation = { useCurrentLocation() }
                    )
                    return@launch
                }

                showLocationMapPickerDialog(
                    initialLatitude = locationLat,
                    initialLongitude = locationLng,
                    initialLabel = inputLocationLabel.text?.toString()?.trim().orEmpty().takeIf { it.isNotBlank() }
                ) { picked ->
                    applyPickedLocation(picked.latitude, picked.longitude, picked.label)
                }
            }
        }

        fun showChooseLocationMethodDialog() {
            if (!PremiumManager.isPremium(this)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.schedules_location_picker_premium_title)
                    .setMessage(R.string.schedules_location_picker_premium_message)
                    .setPositiveButton(R.string.usage_details_premium_action) { _, _ ->
                        startActivity(Intent(this, PremiumInfoActivity::class.java))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .styleSwitchlyDialogButtons()
                return
            }

            val mapPickerAvailable = BuildConfig.SWITCHLY_HAS_MAPS_API_KEY
            val options = if (mapPickerAvailable) {
                arrayOf(
                    getString(R.string.schedules_location_method_picker),
                    getString(R.string.schedules_location_method_search),
                    getString(R.string.schedules_location_method_current)
                )
            } else {
                arrayOf(
                    getString(R.string.schedules_location_method_search),
                    getString(R.string.schedules_location_method_current)
                )
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.schedules_location_method_title)
                .setItems(options) { dialog, which ->
                    if (mapPickerAvailable) {
                        when (which) {
                            0 -> openVisualLocationPickerDialog()
                            1 -> openLocationSearchDialog()
                            else -> useCurrentLocation()
                        }
                    } else {
                        when (which) {
                            0 -> openLocationSearchDialog()
                            else -> useCurrentLocation()
                        }
                    }
                    dialog.dismiss()
                }
                .show()
                .styleSwitchlyDialogButtons()
        }

        inputLocationLabel.addTextChangedListener {
            updateLocationSummary()
        }
        btnUseCurrentLocation.setOnClickListener { showChooseLocationMethodDialog() }
        chipRadius100.setOnCheckedChangeListener { _, checked -> if (checked) { locationRadiusMeters = 100; updateLocationSummary() } }
        chipRadius250.setOnCheckedChangeListener { _, checked -> if (checked) { locationRadiusMeters = 250; updateLocationSummary() } }
        chipRadius500.setOnCheckedChangeListener { _, checked -> if (checked) { locationRadiusMeters = 500; updateLocationSummary() } }
        syncRadiusChips()
        setupLocationTriggerSpinner()
        setupLocationCooldownSpinner()
        updateLocationSummary()

        val profiles = ProfileStore.getProfiles(this)
        val profileList: List<String> =
            if (existing != null && existing.profile !in profiles) {
                listOf(existing.profile) + profiles
            } else {
                profiles.toList()
            }

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
            spinnerTimeMode.setText(
                labels.getOrNull(sel) ?: labels.firstOrNull().orEmpty(),
                false
            )
        }

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
            spinnerAction.setText(
                labels.getOrNull(selectedActionIndex) ?: labels.firstOrNull().orEmpty(),
                false
            )
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
            return TimeFormatPrefs.formatMinutesOfDay(this, m)
        }

        fun formatYmd(ymd: Int): String {
            if (ymd <= 0) return getString(R.string.schedules_date_not_set)
            val y = ymd / 10000
            val mo = (ymd / 100) % 100
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
                    val preferred = when (existing?.action) {
                        ScheduleStore.Action.DISABLE_AND_ENABLE -> ScheduleStore.Action.DISABLE_AND_ENABLE
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

                Kind.LOCATION -> {
                    val options = when (locationTrigger) {
                        ScheduleStore.LocationTrigger.ENTER,
                        ScheduleStore.LocationTrigger.EXIT -> listOf(
                            ScheduleStore.Action.ENABLE,
                            ScheduleStore.Action.DISABLE,
                            ScheduleStore.Action.TOGGLE
                        )
                        ScheduleStore.LocationTrigger.ENTER_EXIT -> listOf(
                            ScheduleStore.Action.ENABLE_AND_DISABLE,
                            ScheduleStore.Action.DISABLE_AND_ENABLE
                        )
                    }

                    val preferred = existing?.action?.takeIf { it in options } ?: options.first()
                    setActionOptions(filterActionsForNfc(options), preferred)
                }
            }
            updateActionNfcHint()
        }

        refreshActionUi = { updateActionUi() }

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

                Kind.WIFI, Kind.BT, Kind.LOCATION -> {
                    groupTime.isVisible = true
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
            if (kind == Kind.BT && isPremium) inputBtName.setText(existing.btDeviceName ?: existing.btDeviceAddress.orEmpty())
            if (kind == Kind.LOCATION && isPremium) {
                inputLocationLabel.setText(existing.locationLabel.orEmpty())
                locationLat = existing.locationLat
                locationLng = existing.locationLng
                locationRadiusMeters = existing.locationRadiusMeters
                locationTrigger = existing.locationTrigger ?: ScheduleStore.LocationTrigger.ENTER
                selectedCooldownMinutes = existing.locationCooldownMinutes
                syncRadiusChips()
                setupLocationTriggerSpinner()
                setupLocationCooldownSpinner()
                updateLocationSummary()
            }
        } else {
            selectProfile(ProfileStore.getCurrent(this))
            chipMon.isChecked = true
            chipTue.isChecked = true
            chipWed.isChecked = true
            chipThu.isChecked = true
            chipFri.isChecked = true
        }

        val isConnKind = kind == Kind.WIFI || kind == Kind.BT || kind == Kind.LOCATION
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

        switchConnTimeWindow.setOnCheckedChangeListener { _, checked ->
            if (kind != Kind.WIFI && kind != Kind.BT && kind != Kind.LOCATION) return@setOnCheckedChangeListener

            if (!checked) {
                startMinutes = 0
                endMinutes = 24 * 60 - 1
            } else {
                if (startMinutes == 0 && endMinutes >= 24 * 60 - 1) {
                    val now = Calendar.getInstance()
                    startMinutes =
                        now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
                    endMinutes = (startMinutes + 60) % (24 * 60)
                    if (endMinutes == startMinutes) {
                        endMinutes = (startMinutes + 30) % (24 * 60)
                    }
                }
            }

            updateVisibilityForMode()
            updateLabels()
        }

        spinnerAction.setOnItemClickListener { _, _, pos, _ ->
            selectedActionIndex = pos
            updateActionNfcHint()
        }

        spinnerTimeMode.setOnItemClickListener { _, _, pos, _ ->
            if (kind != Kind.TIME) return@setOnItemClickListener
            selectedTimeModeIndex = pos
            updateActionUi()
            updateVisibilityForMode()
            updateLabels()
        }

        fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
            val h = initial / 60
            val m = initial % 60

            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(if (TimeFormatPrefs.is24Hour(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
                .setHour(h)
                .setMinute(m)
                .build()

            picker.addOnPositiveButtonClickListener {
                onPicked(picker.hour * 60 + picker.minute)
                updateLabels()
            }

            val tag = "switchly_timepicker_${SystemClock.uptimeMillis()}"
            picker.show(supportFragmentManager, tag)

            if (CustomAccentApplier.isCustomAccentEnabled(this)) {
                window.decorView.post {
                    val d = picker.dialog
                    val decor = d?.window?.decorView
                    if (decor != null) {
                        CustomAccentApplier.applyToView(decor, this)
                        longArrayOf(60L, 180L, 360L).forEach { delay ->
                            decor.postDelayed(
                                { runCatching { CustomAccentApplier.applyToView(decor, this) } },
                                delay
                            )
                        }
                    }
                }
            }
        }

        fun pickDate(initialYmd: Int, onPicked: (Int) -> Unit) {
            val cal = Calendar.getInstance()
            if (initialYmd > 0) {
                val y = initialYmd / 10000
                val mo = (initialYmd / 100) % 100
                val d = initialYmd % 100
                cal.set(y, mo - 1, d)
            }
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    val ymd = year * 10000 + (month + 1) * 100 + dayOfMonth
                    onPicked(ymd)
                    updateLabels()
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        textStartTime.setOnClickListener {
            if (groupTime.isVisible && groupTimeRow.isVisible) {
                pickTime(startMinutes) { startMinutes = it }
            }
        }
        textEndTime.setOnClickListener {
            if (groupTime.isVisible && groupTimeRow.isVisible && textEndTime.isVisible) {
                pickTime(endMinutes) { endMinutes = it }
            }
        }

        textStartDate.setOnClickListener {
            if (groupOnce.isVisible) {
                pickDate(
                    if (startDateYmd > 0) startDateYmd else ScheduleStore.todayYmd()
                ) { startDateYmd = it }
            }
        }
        textEndDate.setOnClickListener {
            if (groupOnce.isVisible) {
                pickDate(
                    if (endDateYmd > 0) endDateYmd else ScheduleStore.todayYmd()
                ) { endDateYmd = it }
            }
        }

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
                chipMon.isChecked = true
                chipTue.isChecked = true
                chipWed.isChecked = true
                chipThu.isChecked = true
                chipFri.isChecked = true
                chipSat.isChecked = false
                chipSun.isChecked = false
            } else {
                all.forEach { it.isChecked = false }
            }
            updateQuickChipsFromDays()
        }

        chipWeekend.setOnCheckedChangeListener { _, checked ->
            if (isUpdatingQuick) return@setOnCheckedChangeListener
            val all = listOf(chipMon, chipTue, chipWed, chipThu, chipFri, chipSat, chipSun)
            if (checked) {
                chipMon.isChecked = false
                chipTue.isChecked = false
                chipWed.isChecked = false
                chipThu.isChecked = false
                chipFri.isChecked = false
                chipSat.isChecked = true
                chipSun.isChecked = true
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
                layoutLocationLabel.error = null
                inputWifiSsid.error = null
                inputBtName.error = null
                inputLocationLabel.error = null

                val profile = profileList.getOrNull(selectedProfileIndex)
                    ?: spinnerProfile.text?.toString().orEmpty()
                if (profile.isBlank()) {
                    showSnack(R.string.schedules_error_no_profile)
                    return@setOnClickListener
                }

                val wifiSsid: String? = if (kind == Kind.WIFI && isPremium) {
                    inputWifiSsid.text.toString().trim().ifEmpty { null }
                } else {
                    null
                }

                val btName: String? = if (kind == Kind.BT && isPremium) {
                    inputBtName.text.toString().trim().ifEmpty { null }
                } else {
                    null
                }

                val locationLabel: String? = if (kind == Kind.LOCATION && isPremium) {
                    inputLocationLabel.text.toString().trim().ifEmpty { null }
                } else {
                    null
                }

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

                if (kind == Kind.LOCATION && isPremium && (locationLat == null || locationLng == null)) {
                    layoutLocationLabel.error = getString(R.string.schedules_error_location_required)
                    inputLocationLabel.error = getString(R.string.schedules_error_location_required)
                    inputLocationLabel.requestFocus()
                    showSnack(R.string.schedules_error_location_required)
                    return@setOnClickListener
                }

                if (kind == Kind.WIFI && isPremium && !wifiSsid.isNullOrBlank() && !hasWifiSsidPermission()) {
                    showWhyLocationDialogForWifi()
                    return@setOnClickListener
                }

                if (kind == Kind.BT && isPremium && !btName.isNullOrBlank() && !hasBluetoothConnectPermission()) {
                    showWhyBluetoothDialogForSchedules()
                    return@setOnClickListener
                }

                if (kind == Kind.LOCATION && isPremium && !hasFineLocationPermission()) {
                    showWhyLocationDialogForGeofence()
                    return@setOnClickListener
                }
                if (kind == Kind.LOCATION && isPremium && !hasBackgroundLocationPermission()) {
                    showWhyBackgroundLocationDialogForGeofence()
                    return@setOnClickListener
                }

                var daysMask = 0
                val isConn = kind == Kind.WIFI || kind == Kind.BT || kind == Kind.LOCATION
                val isDateRange = kind == Kind.TIME && currentTimeMode() == TimeMode.DATE_RANGE

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
                    currentTimeMode() == TimeMode.TIME_RANGE -> selectedAction()
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

                if ((isConn ||
                        action == ScheduleStore.Action.ENABLE_AND_DISABLE ||
                        action == ScheduleStore.Action.DISABLE_AND_ENABLE) &&
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
                    locationLabel = locationLabel,
                    locationLat = if (kind == Kind.LOCATION && isPremium) locationLat else null,
                    locationLng = if (kind == Kind.LOCATION && isPremium) locationLng else null,
                    locationRadiusMeters = if (kind == Kind.LOCATION && isPremium) selectedRadiusMeters() else 250,
                    locationTrigger = if (kind == Kind.LOCATION && isPremium) locationTrigger else null,
                    locationCooldownMinutes = if (kind == Kind.LOCATION && isPremium) selectedCooldownMinutes else 15,
                    action = action
                )

                val oldList = ScheduleStore.getAll(this)
                val newList = if (existing == null) {
                    oldList + newSchedule
                } else {
                    oldList.map { if (it.id == existing.id) newSchedule else it }
                }

                ScheduleStore.saveAll(this, newList)
                LocationTriggerMonitor.syncNow(this)
                reapplySchedulesNow()
                SchedulePlanner.updateNextAlarm(this)
                SchedulePlanner.notifyNextChanged(this)
                refreshList()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun parseLocationCoordinateQuery(raw: String): Pair<Double, Double>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val patterns = listOf(
            Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)\s*[,;]\s*([+-]?\d+(?:[.,]\d+)?)\s*$"""),
            Regex("""^\s*([+-]?\d+(?:[.,]\d+)?)\s+([+-]?\d+(?:[.,]\d+)?)\s*$""")
        )

        for (pattern in patterns) {
            val match = pattern.matchEntire(trimmed) ?: continue
            val lat = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val lng = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                return lat to lng
            }
        }

        return null
    }

    private fun formatGeocoderLabel(address: android.location.Address?): String? {
        if (address == null) return null

        val candidates = listOf(
            listOfNotNull(address.featureName, address.subLocality, address.locality)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() },
            listOfNotNull(address.locality, address.adminArea)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() },
            address.getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() }
        )

        return candidates.firstOrNull { !it.isNullOrBlank() }
    }

    private fun reverseGeocodeLabel(
        latitude: Double,
        longitude: Double,
        fallback: String? = null,
        onResult: (String?) -> Unit
    ) {
        if (!Geocoder.isPresent()) {
            onResult(fallback)
            return
        }

        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(latitude, longitude, 1) { addresses ->
                val label = formatGeocoderLabel(addresses.firstOrNull()) ?: fallback
                runOnUiThread { onResult(label) }
            }
        } else {
            lifecycleScope.launch {
                val label = withContext(Dispatchers.IO) {
                    runCatching {
                        getFromLocationBlockingCompat(geocoder, latitude, longitude)
                            .firstOrNull()
                    }.getOrNull()?.let(::formatGeocoderLabel) ?: fallback
                }
                onResult(label)
            }
        }
    }

    private fun getFromLocationBlockingCompat(
        geocoder: Geocoder,
        latitude: Double,
        longitude: Double
    ): List<android.location.Address> {
        return runCatching {
            val method = Geocoder::class.java.getMethod(
                "getFromLocation",
                java.lang.Double.TYPE,
                java.lang.Double.TYPE,
                java.lang.Integer.TYPE
            )
            val result = method.invoke(geocoder, latitude, longitude, 1)
            (result as? List<*>)?.filterIsInstance<android.location.Address>().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun getFromLocationNameBlockingCompat(
        geocoder: Geocoder,
        query: String
    ): List<android.location.Address> {
        return runCatching {
            val method = Geocoder::class.java.getMethod(
                "getFromLocationName",
                String::class.java,
                java.lang.Integer.TYPE
            )
            val result = method.invoke(geocoder, query, 1)
            (result as? List<*>)?.filterIsInstance<android.location.Address>().orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun resolveLocationQuery(query: String, onResult: (ResolvedLocation?) -> Unit) {
        val coordinateMatch = parseLocationCoordinateQuery(query)
        if (coordinateMatch != null) {
            val (latitude, longitude) = coordinateMatch
            val fallbackLabel = getString(R.string.schedules_location_coords_fmt, latitude, longitude)
            reverseGeocodeLabel(latitude, longitude, fallbackLabel) { label ->
                onResult(ResolvedLocation(latitude, longitude, label ?: fallbackLabel))
            }
            return
        }

        if (!Geocoder.isPresent()) {
            onResult(null)
            return
        }

        val geocoder = Geocoder(this, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocationName(query, 1) { addresses ->
                val first = addresses.firstOrNull()
                val resolved = if (first != null) {
                    ResolvedLocation(
                        latitude = first.latitude,
                        longitude = first.longitude,
                        label = formatGeocoderLabel(first) ?: query.trim()
                    )
                } else {
                    null
                }
                runOnUiThread { onResult(resolved) }
            }
        } else {
            lifecycleScope.launch {
                val resolved = withContext(Dispatchers.IO) {
                    runCatching {
                        getFromLocationNameBlockingCompat(geocoder, query).firstOrNull()
                    }.getOrNull()?.let { address ->
                        ResolvedLocation(
                            latitude = address.latitude,
                            longitude = address.longitude,
                            label = formatGeocoderLabel(address) ?: query.trim()
                        )
                    }
                }
                onResult(resolved)
            }
        }
    }

    private suspend fun canResolveGoogleMapsHost(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(GOOGLE_MAPS_REACHABILITY_TIMEOUT_MS) {
                InetAddress.getByName(GOOGLE_MAPS_DNS_HOST)
            }
            true
        }.getOrDefault(false)
    }

    private fun showGoogleMapsUnavailableDialog(
        onUseSearch: () -> Unit,
        onUseCurrentLocation: () -> Unit
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.schedules_location_map_unavailable_title)
            .setMessage(R.string.schedules_location_map_unavailable_message)
            .setPositiveButton(R.string.schedules_location_use_search) { _, _ -> onUseSearch() }
            .setNegativeButton(R.string.schedules_location_use_current) { _, _ -> onUseCurrentLocation() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
            .styleSwitchlyDialogButtons()
    }

    private fun showLocationMapPickerDialog(
        initialLatitude: Double?,
        initialLongitude: Double?,
        initialLabel: String?,
        onPicked: (ResolvedLocation) -> Unit
    ) {
        pendingMapPickerCallback = onPicked
        locationMapPickerLauncher.launch(
            LocationMapPickerActivity.createIntent(
                context = this,
                initialLatitude = initialLatitude,
                initialLongitude = initialLongitude,
                initialLabel = initialLabel
            )
        )
    }

    private fun showLocationPickerDialog(
        initialQuery: String,
        onUseCurrentLocation: () -> Unit,
        onPicked: (ResolvedLocation) -> Unit
    ) {
        val dialogContext = androidx.appcompat.view.ContextThemeWrapper(
            this,
            R.style.ThemeOverlay_Switchly_Dialog
        )
        val dialogView = LayoutInflater.from(dialogContext)
            .inflate(R.layout.dialog_location_picker_search, null, false)
        val layoutQuery = dialogView.findViewById<TextInputLayout>(R.id.layoutLocationQuery)
        val inputQuery = dialogView.findViewById<EditText>(R.id.inputLocationQuery)
        val btnCurrent = dialogView.findViewById<MaterialButton>(R.id.btnPickerUseCurrentLocation)
        inputQuery.setText(initialQuery)

        val dialog = MaterialAlertDialogBuilder(dialogContext)
            .setTitle(R.string.schedules_location_search_title)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.styleSwitchlyDialogButtons()
            val btnApply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            btnApply.setOnClickListener {
                val query = inputQuery.text?.toString()?.trim().orEmpty()
                if (query.isBlank()) {
                    layoutQuery.error = getString(R.string.schedules_location_picker_invalid)
                    return@setOnClickListener
                }

                layoutQuery.error = null
                btnApply.isEnabled = false
                btnCurrent.isEnabled = false

                resolveLocationQuery(query) { resolved ->
                    btnApply.isEnabled = true
                    btnCurrent.isEnabled = true
                    if (resolved == null) {
                        layoutQuery.error = getString(R.string.schedules_location_picker_not_found)
                    } else {
                        onPicked(resolved)
                        dialog.dismiss()
                    }
                }
            }

            btnCurrent.setOnClickListener {
                dialog.dismiss()
                onUseCurrentLocation()
            }
        }

        dialog.show()
    }
}

private class ScheduleAdapter(
    private val onToggleEnabled: (ScheduleStore.Schedule, Boolean) -> Unit,
    private val canInteract: () -> Boolean,
    private val isSelectionMode: () -> Boolean,
    private val isSelected: (Int) -> Boolean,
    private val onToggleSelection: (Int) -> Unit,
    private val onEnterSelection: (Int) -> Unit,
    private val onEdit: (ScheduleStore.Schedule) -> Unit
) : androidx.recyclerview.widget.ListAdapter<ScheduleStore.Schedule, ScheduleViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScheduleViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_schedule, parent, false)
        return ScheduleViewHolder(
            v,
            onToggleEnabled,
            canInteract,
            isSelectionMode,
            isSelected,
            onToggleSelection,
            onEnterSelection,
            onEdit
        )
    }

    override fun onBindViewHolder(holder: ScheduleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    companion object {
        private val DIFF =
            object : androidx.recyclerview.widget.DiffUtil.ItemCallback<ScheduleStore.Schedule>() {
                override fun areItemsTheSame(
                    oldItem: ScheduleStore.Schedule,
                    newItem: ScheduleStore.Schedule
                ) = oldItem.id == newItem.id

                override fun areContentsTheSame(
                    oldItem: ScheduleStore.Schedule,
                    newItem: ScheduleStore.Schedule
                ) = oldItem == newItem
            }
    }
}

private class ScheduleViewHolder(
    itemView: View,
    private val onToggleEnabled: (ScheduleStore.Schedule, Boolean) -> Unit,
    private val canInteract: () -> Boolean,
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
    private val checkSelect =
        itemView.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.checkSelect)
    private val cardRoot = itemView.findViewById<View>(R.id.cardRoot)

    private var current: ScheduleStore.Schedule? = null
    private var binding = false

    private fun tintEnabledSwitch() {
        val ctx = itemView.context
        val accent = AccentColor.getAccentColorInt(ctx)
        val thumbOff = ColorUtils.blendARGB(accent, Color.WHITE, 0.72f)
        val thumbDisabled = ColorUtils.blendARGB(accent, Color.LTGRAY, 0.80f)
        val trackOn = ColorUtils.setAlphaComponent(accent, 0x88)
        val trackOff = ColorUtils.setAlphaComponent(Color.DKGRAY, 0x44)
        val trackOffDisabled = ColorUtils.setAlphaComponent(Color.GRAY, 0x33)

        switchEnabled.thumbTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(thumbDisabled, accent, thumbOff)
        )

        switchEnabled.trackTintList = ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(trackOffDisabled, trackOn, trackOff)
        )
    }

    init {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!binding && !isSelectionMode() && canInteract()) current?.let { onToggleEnabled(it, isChecked) }
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
            if (!isSelectionMode() && canInteract()) {
                onEnterSelection(s.id)
            } else if (!canInteract()) {
                onEdit(s)
            }
            true
        }
    }

    private fun fmtMinutes(m: Int): String {
        val h = m / 60
        val mm = m % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, mm)
    }

    fun bind(s: ScheduleStore.Schedule) {
        current = s

        val canInteractNow = canInteract()

        binding = true
        switchEnabled.isChecked = s.enabled
        switchEnabled.isEnabled = canInteractNow && !isSelectionMode()
        tintEnabledSwitch()
        binding = false

        val selecting = isSelectionMode()
        checkSelect.visibility = if (selecting) View.VISIBLE else View.GONE
        checkSelect.isChecked = selecting && isSelected(s.id)
        cardRoot.isClickable = true
        cardRoot.isLongClickable = true

        title.text = s.title.ifBlank { s.profile }
        val ctx = itemView.context

        val hasWifi = !s.wifiSsid.isNullOrBlank()
        val hasBt = (!s.btDeviceName.isNullOrBlank() || !s.btDeviceAddress.isNullOrBlank())
        val hasLocation = s.isLocationSchedule()

        val iconRes = when {
            hasLocation -> R.drawable.location_on_24
            hasWifi && hasBt -> R.drawable.layers_24
            hasWifi -> R.drawable.wifi_24
            hasBt -> R.drawable.bluetooth_24
            else -> R.drawable.alarm_24
        }
        kindIcon.setImageResource(iconRes)

        val a = when {
            !canInteractNow && s.enabled -> 0.72f
            !canInteractNow -> 0.45f
            s.enabled -> 1f
            else -> 0.5f
        }
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

        val timeOrConn: String = if (hasWifi || hasBt || hasLocation) {
            val conn = when {
                hasLocation -> {
                    val label = s.locationLabel?.takeIf { it.isNotBlank() } ?: run {
                        val lat = s.locationLat
                        val lng = s.locationLng
                        if (lat != null && lng != null) {
                            String.format(Locale.getDefault(), "%.5f, %.5f", lat, lng)
                        } else {
                            "-"
                        }
                    }
                    ctx.getString(
                        R.string.schedules_conn_location_fmt,
                        "$label · ${s.locationRadiusMeters}m"
                    )
                }
                hasWifi && hasBt -> ctx.getString(
                    R.string.schedules_conn_wifi_bt_fmt,
                    s.wifiSsid,
                    (s.btDeviceName ?: s.btDeviceAddress)
                )
                hasWifi -> ctx.getString(R.string.schedules_conn_wifi_fmt, s.wifiSsid)
                else -> ctx.getString(R.string.schedules_conn_bt_fmt, s.btDeviceName ?: s.btDeviceAddress)
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
