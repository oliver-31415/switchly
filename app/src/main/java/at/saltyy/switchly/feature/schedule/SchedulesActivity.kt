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
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.BuildConfig
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.ScheduleInsights
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
import at.saltyy.switchly.ui.SwitchlyDropdownAdapter
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.attachEditDeleteSwipe
import at.saltyy.switchly.ui.updateSelectionSubtitle
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.ui.dialog.showDestructiveAccented
import at.saltyy.switchly.ui.dialog.styleSwitchlyDestructivePositiveButton
import at.saltyy.switchly.ui.dialog.SwitchlyDialogOption
import at.saltyy.switchly.ui.dialog.showSwitchlyOptionDialog
import at.saltyy.switchly.ui.dialog.styleSwitchlyDialogButtons
import at.saltyy.switchly.ui.dialog.applySwitchlyDialogWidth
import at.saltyy.switchly.util.BatteryOptimizationCompat
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.TimeFormatPrefs
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.net.InetAddress
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SchedulesActivity : AppCompatActivity() {

    private enum class NewScheduleMode { TIME, WIFI, BT, LOCATION }
    private enum class Kind { TIME, WIFI, BT, LOCATION }
    private enum class TimeMode { SINGLE, TIME_RANGE, DATE_RANGE }

    companion object {
        const val EXTRA_OPEN_ADD_TIME = "extra_open_add_time"
        private const val PREFS_SCHEDULE_HEALTH = "switchly_schedule_health"
        const val KEY_BATTERY_OPTIMIZATION_CONFIRMED_MAX_AVAILABLE = "battery_optimization_confirmed_max_available"
        const val GOOGLE_MAPS_DNS_HOST = "clients4.google.com"
        const val GOOGLE_MAPS_REACHABILITY_TIMEOUT_MS = 2_500L
    }

    private data class ResolvedLocation(
        val latitude: Double,
        val longitude: Double,
        val label: String?
    )

    private lateinit var adapter: ScheduleAdapter
    private lateinit var toolbar: MaterialToolbar
    private lateinit var emptyState: View
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

            if (result.resultCode != RESULT_OK || callback == null) return@registerForActivityResult

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
                showScheduleMessage(R.string.perm_location_denied_wifi_schedule)
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
                showScheduleMessage(R.string.perm_location_denied_geofence_schedule)
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
                showScheduleMessage(R.string.perm_bt_denied_schedule)
            }
            refreshList()
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
        return EditingLockGuard.isLocked(this)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedules)

        toolbar = findViewById(R.id.toolbar)

        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = toolbar
        )
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        setSupportActionBar(toolbar)
        toolbar.subtitle = getString(R.string.schedules_profile_subtitle)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val recycler = findViewById<RecyclerView>(R.id.recyclerSchedules)
        recycler.layoutManager = LinearLayoutManager(this)
        emptyState = findViewById(R.id.tvEmpty)

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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                SwitchModeStore.enabledFlow.collect {
                    runOnUiThread { refreshList() }
                }
            }
        }

        adapter = ScheduleAdapter(
            onToggleEnabled = { schedule, enabled ->
                if (canEditSchedules()) {
                    val list = ScheduleStore.getAll(this).map {
                        if (it.id == schedule.id) it.copy(enabled = enabled) else it
                    }
                    ScheduleStore.saveAll(this, list)
                    LocationTriggerMonitor.syncAsync(this)
                    reapplySchedulesNow()
                    SchedulePlanner.updateNextAlarm(this)
                    SchedulePlanner.notifyNextChanged(this)
                    if (enabled) {
                        showEnabledScheduleOverlapWarning(schedule.id, list)
                    }
                }
            },
            canInteract = { canEditSchedules() },
            isSelectionMode = { isSelectionMode },
            isSelected = { id -> selectedScheduleIds.contains(id) },
            onToggleSelection = { id -> toggleSelection(id) },
            onEnterSelection = { preselectId -> enterSelectionMode(preselectId) },
            onEdit = { schedule ->
                if (canEditSchedules()) {
                    showScheduleDialog(existing = schedule, preselectedMode = null)
                }
            }
        )
        recycler.adapter = adapter
        recycler.attachEditDeleteSwipe(
            canSwipe = { !isSelectionMode && canEditSchedules() },
            onEdit = { position ->
                adapter.itemAt(position)?.let { schedule ->
                    showScheduleDialog(existing = schedule, preselectedMode = null)
                }
            },
            onDelete = { position ->
                adapter.itemAt(position)?.let(::confirmDeleteSchedule)
            }
        )

        val addScheduleClick = View.OnClickListener {
            if (!canEditSchedules()) {
                return@OnClickListener
            }
            showNewScheduleTypeDialog()
        }
        findViewById<View>(R.id.fabAdd).setOnClickListener(addScheduleClick)
        findViewById<View>(R.id.btnEmptyAddSchedule).setOnClickListener(addScheduleClick)

        refreshList()

        if (intent.getBooleanExtra(EXTRA_OPEN_ADD_TIME, false)) {
            intent.removeExtra(EXTRA_OPEN_ADD_TIME)
            window.decorView.post {
                if (canEditSchedules()) {
                    showScheduleDialog(null, NewScheduleMode.TIME)
                }
            }
        }
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
                if (canEditSchedules()) {
                    enterSelectionMode(null)
                }
                true
            }
            R.id.action_cancel_selection -> {
                exitSelectionMode()
                true
            }
            R.id.action_delete_selected -> {
                if (canEditSchedules()) {
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
        findViewById<View>(R.id.fabAdd)?.apply {
            visibility = if (isSelectionMode) View.GONE else View.VISIBLE
            isEnabled = canInteract
            isClickable = canInteract
            alpha = if (canInteract) 1f else 0.45f
        }
        findViewById<View>(R.id.btnEmptyAddSchedule)?.apply {
            isEnabled = canInteract
            isClickable = canInteract
            alpha = if (canInteract) 1f else 0.45f
        }
        if (::toolbar.isInitialized) {
            toolbar.updateSelectionSubtitle(
                isSelectionMode,
                selectedScheduleIds.size,
                getString(R.string.schedules_profile_subtitle)
            )
        }
    }

    private fun enterSelectionMode(preselectId: Int?) {
        if (!canEditSchedules()) {
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
        if (!isSelectionMode) {
            return
        }
        if (selectedScheduleIds.contains(id)) {
            selectedScheduleIds.remove(id)
        } else {
            selectedScheduleIds.add(id)
        }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        updateMenuState()
    }

    private fun confirmDeleteSelectedSchedules() {
        if (!canEditSchedules()) {
            return
        }
        if (selectedScheduleIds.isEmpty()) {
            return
        }
        val count = selectedScheduleIds.size
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(resources.getQuantityString(R.plurals.delete_schedules_confirm, count, count) + "\n\n" + getString(R.string.destructive_cannot_be_undone))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()

        dlg.setOnShowListener {
            dlg.styleSwitchlyDestructivePositiveButton()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                deleteScheduleIds(selectedScheduleIds)
                exitSelectionMode()
                refreshList()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun confirmDeleteSchedule(schedule: ScheduleStore.Schedule) {
        if (!canEditSchedules()) {
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(
                resources.getQuantityString(R.plurals.delete_schedules_confirm, 1, 1) +
                    "\n\n" +
                    getString(R.string.destructive_cannot_be_undone)
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteScheduleIds(setOf(schedule.id))
                refreshList()
            }
            .showDestructiveAccented()
    }

    private fun deleteScheduleIds(ids: Set<Int>) {
        if (!canEditSchedules()) {
            return
        }
        if (ids.isEmpty()) {
            return
        }
        val remaining = ScheduleStore.getAll(this).filterNot { it.id in ids }
        ScheduleStore.saveAll(this, remaining)
        LocationTriggerMonitor.syncAsync(this)
        SchedulePlanner.updateNextAlarm(this)
        SchedulePlanner.notifyNextChanged(this)
    }

    private fun rootContentView(): View? {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return content?.getChildAt(0)
    }

    override fun onResume() {
        super.onResume()

        ExactAlarmPermissionSync.syncAndReschedule(this, reason = "schedules_resume")

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

        val optionList = items.map { item ->
            SwitchlyDialogOption(
                title = item.label,
                iconRes = item.iconRes
            )
        }
        val hint = if (nfcLockOn) getString(R.string.schedules_nfc_lock_add_dialog_hint) else null
        showSwitchlyOptionDialog(
            title = getString(R.string.schedules_choose_type),
            options = if (hint == null) optionList else listOf(SwitchlyDialogOption(title = hint, enabled = false)) + optionList
        ) { which ->
            val itemIndex = if (hint == null) which else which - 1
            val selected = items.getOrNull(itemIndex) ?: return@showSwitchlyOptionDialog
            when (selected.mode) {
                NewScheduleMode.TIME -> showScheduleDialog(null, NewScheduleMode.TIME)
                NewScheduleMode.WIFI -> showScheduleDialog(null, NewScheduleMode.WIFI)
                NewScheduleMode.BT -> showScheduleDialog(null, NewScheduleMode.BT)
                NewScheduleMode.LOCATION -> showScheduleDialog(null, NewScheduleMode.LOCATION)
            }
        }
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

    private fun showEnabledScheduleOverlapWarning(scheduleId: Int, schedules: List<ScheduleStore.Schedule>) {
        val overlap = ScheduleInsights.detectOverlaps(schedules).firstOrNull {
            it.first.id == scheduleId || it.second.id == scheduleId
        } ?: return
        val other = if (overlap.first.id == scheduleId) overlap.second else overlap.first
        AlertDialog.Builder(this)
            .setTitle(R.string.schedules_overlap_warning_title)
            .setMessage(
                getString(
                    R.string.schedules_overlap_enabled_message_fmt,
                    ScheduleInsights.scheduleDisplayName(other)
                )
            )
            .setPositiveButton(R.string.ok, null)
            .showAccented()
    }

    private fun buildScheduleActionInfoBody(): CharSequence {
        val sb = SpannableStringBuilder()

        fun addItem(title: String, desc: String) {
            val titleStart = sb.length
            sb.append(title)
            sb.setSpan(
                StyleSpan(Typeface.BOLD),
                titleStart,
                titleStart + title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.append('\n')
            sb.append(desc.trim()).append("\n\n")
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
        sb.append("\n\n")
        sb.append(getString(R.string.schedules_priority_info_body))
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
        val thumbOff = Color.WHITE
        val thumbDisabled = Color.LTGRAY
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

        emptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = "package:$packageName".toUri()
            })
        }
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

    private data class SchedulePermissionState(
        val accessibilityMissing: Boolean,
        val wifiMissing: Boolean,
        val bluetoothMissing: Boolean,
        val locationMissing: Boolean,
        val batteryMissing: Boolean,
        val exactAlarmMissing: Boolean,
    ) {
        val hasIssue: Boolean
            get() = accessibilityMissing || wifiMissing || bluetoothMissing || locationMissing || batteryMissing || exactAlarmMissing
    }

    private fun currentSchedulePermissionState(
        enabledSchedules: List<ScheduleStore.Schedule>,
    ): SchedulePermissionState {
        val accessibilityReady = BlockingRuntime.isAccessibilityActive(this) ||         BlockingRuntime.isAccessibilityEnabledInSettings(this)
        val wifiSchedulesNeedPermission = enabledSchedules.any { !it.wifiSsid.isNullOrBlank() }
        val bluetoothSchedulesNeedPermission = enabledSchedules.any {
            !it.btDeviceName.isNullOrBlank() || !it.btDeviceAddress.isNullOrBlank()
        }
        val locationSchedulesNeedPermission = enabledSchedules.any { it.isLocationSchedule() }

        return SchedulePermissionState(
            accessibilityMissing = !accessibilityReady,
            wifiMissing = wifiSchedulesNeedPermission && !hasWifiSsidPermission(),
            bluetoothMissing = bluetoothSchedulesNeedPermission && !hasBluetoothConnectPermission(),
            locationMissing = locationSchedulesNeedPermission && !hasLocationSchedulePermission(),
            batteryMissing = enabledSchedules.isNotEmpty() && !isBatteryOptimizationEffectivelyOk(),
            exactAlarmMissing = enabledSchedules.isNotEmpty() && !canScheduleExactAlarms(),
        )
    }

    private fun openSchedulePermissionsIfStillNeeded() {
        val enabledSchedules = ScheduleStore.getAll(this).filter { it.enabled }
        if (!currentSchedulePermissionState(enabledSchedules).hasIssue) {
            Toast.makeText(
                this,
                R.string.permissions_health_rechecked_all_good,
                Toast.LENGTH_SHORT,
            ).show()
            refreshList()
            return
        }
        openPermissionsOverview()
    }

    private fun updateScheduleHealthBanner() {
        val schedules = ScheduleStore.getAll(this)
        val enabledSchedules = schedules.filter { it.enabled }

        if (!isScheduleAutomationAllowed() && !isScheduleEditingLocked()) {
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
            btnStatusAction.isVisible = false
            btnStatusAction.setOnClickListener(null)
            rowStatusAction.isVisible = false
            dividerStatus.isVisible = false
            return
        }

        btnStatusInfo.isVisible = true

        if (enabledSchedules.isEmpty()) {
            cardScheduleHealth.isVisible = false
            return
        }

        val permissionState = currentSchedulePermissionState(enabledSchedules)

        val nfcLockActiveForSchedules = isNfcLockActiveForSchedules()
        val nfcConflict = nfcLockActiveForSchedules && enabledSchedules.any { it.action in nfcLockedActions }

        val blockedAt = ScheduleRuntimeStore.getLastDisableBlockedByNfcMs(this)
        val nfcBlockedRecently = nfcLockActiveForSchedules && blockedAt > 0L && (System.currentTimeMillis() - blockedAt) < 24L * 60L * 60L * 1000L

        val hasPermissionIssue = permissionState.hasIssue
        val hasAnyIssue = hasPermissionIssue || nfcConflict || nfcBlockedRecently
        val overlaps = ScheduleInsights.detectOverlaps(enabledSchedules)
        val activeRangeSummary = ScheduleInsights.activeRangeSummary(this, enabledSchedules)
        val insightBody = buildScheduleInsightBody(overlaps, activeRangeSummary)

        if (!hasAnyIssue && insightBody.isBlank()) {
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
        tvStatusTitle.text = when {
            overlaps.isNotEmpty() -> getString(R.string.schedules_insights_overlap_title)
            hasAnyIssue -> getString(R.string.schedules_health_generic_issue)
            else -> getString(R.string.schedules_insights_title)
        }
        tvStatusBody.isVisible = insightBody.isNotBlank()
        tvStatusBody.text = insightBody
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
            hasPermissionIssue -> {
                btnStatusAction.isVisible = true
                btnStatusAction.setText(R.string.schedules_health_action_permissions)
                btnStatusAction.setOnClickListener { openSchedulePermissionsIfStillNeeded() }
            }
            else -> {
                btnStatusAction.isVisible = false
                btnStatusAction.setOnClickListener(null)
            }
        }

        rowStatusAction.isVisible = btnStatusAction.isVisible
        dividerStatus.isVisible = rowStatusAction.isVisible
    }

    private fun buildScheduleInsightBody(
        overlaps: List<ScheduleInsights.Overlap>,
        activeRangeSummary: String?
    ): String {
        val parts = mutableListOf<String>()
        val firstOverlap = overlaps.firstOrNull()
        if (firstOverlap != null) {
            parts += getString(
                R.string.schedules_insights_overlap_body_fmt,
                ScheduleInsights.scheduleDisplayName(firstOverlap.second)
            )
        }
        if (!activeRangeSummary.isNullOrBlank()) {
            parts += activeRangeSummary
        }
        return parts.joinToString(separator = "\n")
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
        if (Build.VERSION.SDK_INT < 33) {
            return true
        }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
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

    private fun showScheduleMessage(msgRes: Int) {
        showSnack(msgRes)
    }

    private fun showScheduleDialog(
        existing: ScheduleStore.Schedule?,
        preselectedMode: NewScheduleMode? = null
    ) {
        if (!canEditSchedules()) {
            return
        }
        val themedCtx = androidx.appcompat.view.ContextThemeWrapper(
            this,
            R.style.ThemeOverlay_Switchly_Dialog
        )
        val view = LayoutInflater.from(themedCtx)
            .inflate(R.layout.dialog_schedule_add, FrameLayout(this), false)

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
            if (raw.isNullOrBlank()) {
                return null
            }
            val s = raw.trim().removePrefix("\"").removeSuffix("\"")
            if (s.equals("<unknown ssid>", ignoreCase = true)) {
                return null
            }
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

            showSwitchlyOptionDialog(
                title = getString(R.string.schedules_wifi_pick_title),
                options = ssids.map { SwitchlyDialogOption(title = it, iconRes = R.drawable.wifi_24) }
            ) { which ->
                inputWifiSsid.setText(ssids[which])
            }
        }

        fun scanWifi() {
            if (!hasWifiSsidPermission()) {
                requestWifiPermissionThenRetry { scanWifi() }
                return
            }
            if (!isLocationEnabled()) {
                markNeedsLocationHintOnce()
                showScheduleMessage(R.string.schedules_wifi_location_required)
                openLocationSettings()
                return
            }

            val wifi = getSystemService(WIFI_SERVICE) as WifiManager
            if (!wifi.isWifiEnabled) {
                showScheduleMessage(R.string.schedules_wifi_enable_wifi)
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

            showScheduleMessage(R.string.schedules_wifi_scanning)
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
                showScheduleMessage(R.string.schedules_bt_enable_bt)
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
                showScheduleMessage(R.string.schedules_bt_no_connected)
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
                showScheduleMessage(R.string.schedules_bt_enable_bt)
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
                showScheduleMessage(R.string.schedules_bt_no_paired)
                runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                return
            }

            showSwitchlyOptionDialog(
                title = getString(R.string.schedules_bt_pick_paired),
                options = names.map { SwitchlyDialogOption(title = it, iconRes = R.drawable.bluetooth_24) }
            ) { which ->
                inputBtName.setText(names[which])
            }
        }

        btnScanWifi.setOnClickListener { scanWifi() }
        btnUseConnectedBt.setOnClickListener {
            try {
                useConnectedBt()
            } catch (_: Throwable) {
                showScheduleMessage(R.string.schedules_bt_no_connected)
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
            if (manual.isNotBlank()) {
                return manual
            }
            val lat = locationLat
            val lng = locationLng
            if (lat == null || lng == null) {
                return getString(R.string.schedules_location_not_set)
            }
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
            spinnerLocationTrigger.setAdapter(SwitchlyDropdownAdapter(this, labels))
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
            spinnerLocationCooldown.setAdapter(SwitchlyDropdownAdapter(this, labels))
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
                showScheduleMessage(R.string.schedules_wifi_location_required)
                openLocationSettings()
                return
            }

            showScheduleMessage(R.string.schedules_location_fetching)
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
                            showScheduleMessage(R.string.schedules_location_not_set)
                        }
                    }
                    .addOnFailureListener {
                        showScheduleMessage(R.string.schedules_location_not_set)
                    }
            }.onFailure {
                showScheduleMessage(R.string.schedules_location_not_set)
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
                showScheduleMessage(R.string.schedules_location_map_picker_unavailable)
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
            showSwitchlyOptionDialog(
                title = getString(R.string.schedules_location_method_title),
                options = options.mapIndexed { index, label ->
                    SwitchlyDialogOption(
                        title = label,
                        iconRes = when {
                            label == getString(R.string.schedules_location_method_current) -> R.drawable.location_on_24
                            label == getString(R.string.schedules_location_method_search) -> R.drawable.tune_24
                            else -> R.drawable.location_on_24
                        }
                    )
                }
            ) { which ->
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
            }
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

        val profileAdapter = SwitchlyDropdownAdapter(this, profileList)
        spinnerProfile.setAdapter(profileAdapter)

        var selectedProfileIndex = 0
        spinnerProfile.setOnItemClickListener { _, _, position, _ ->
            selectedProfileIndex = position
        }

        fun selectProfile(name: String?) {
            if (name == null) {
                return
            }
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
            if (kind != Kind.TIME) {
                return TimeMode.SINGLE
            }
            return when (selectedTimeModeIndex) {
                1 -> TimeMode.TIME_RANGE
                2 -> TimeMode.DATE_RANGE
                else -> TimeMode.SINGLE
            }
        }

        fun setupTimeModeSpinner() {
            if (kind != Kind.TIME) {
                return
            }

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

            val timeModeAdapter = SwitchlyDropdownAdapter(this, labels)
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
            val actionAdapter = SwitchlyDropdownAdapter(this, labels)
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
            if (!isNfcLockActiveForSchedules()) {
                return options
            }
            val filtered = options.filterNot { it in nfcLockedActions }
            return if (filtered.isNotEmpty()) {
                filtered
            } else {
                listOf(ScheduleStore.Action.ENABLE)
            }
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
            if (ymd <= 0) {
                return getString(R.string.schedules_date_not_set)
            }
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
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
            }
            if (initialYmd > 0) {
                val y = initialYmd / 10000
                val mo = (initialYmd / 100) % 100
                val d = initialYmd % 100
                cal.set(y, mo - 1, d)
            } else {
                val today = Calendar.getInstance()
                cal.set(
                    today.get(Calendar.YEAR),
                    today.get(Calendar.MONTH),
                    today.get(Calendar.DAY_OF_MONTH)
                )
            }

            val picker = MaterialDatePicker.Builder.datePicker()
                .setTheme(com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
                .setSelection(cal.timeInMillis)
                .build()
            picker.addOnPositiveButtonClickListener { millis ->
                val selected = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                    timeInMillis = millis
                }
                val ymd = selected.get(Calendar.YEAR) * 10000 +
                    (selected.get(Calendar.MONTH) + 1) * 100 +
                    selected.get(Calendar.DAY_OF_MONTH)
                onPicked(ymd)
                updateLabels()
            }
            val shown = runCatching {
                picker.show(
                    supportFragmentManager,
                    "switchly_datepicker_${SystemClock.uptimeMillis()}"
                )
            }.isSuccess
            if (!shown) {
                return
            }
            if (CustomAccentApplier.isCustomAccentEnabled(this)) {
                window.decorView.post {
                    picker.dialog?.window?.decorView?.let { decor ->
                        CustomAccentApplier.applyToView(decor, this)
                        longArrayOf(120L, 360L).forEach { delay ->
                            decor.postDelayed(
                                { runCatching { CustomAccentApplier.applyToView(decor, this) } },
                                delay
                            )
                        }
                    }
                }
            }
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
            if (isUpdatingQuick) {
                return
            }
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
            dialog.applySwitchlyDialogWidth(0.96f)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(if (existing == null) R.string.create else R.string.save)
            if (CustomAccentApplier.isCustomAccentEnabled(this)) {
                tintSwitchCompat(switchConnTimeWindow)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!canEditSchedules()) {
                    dialog.dismiss()
                    refreshList()
                    return@setOnClickListener
                }
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

                fun persistSchedule() {
                    ScheduleStore.saveAll(this, newList)
                    LocationTriggerMonitor.syncAsync(this)
                    reapplySchedulesNow()
                    SchedulePlanner.updateNextAlarm(this)
                    SchedulePlanner.notifyNextChanged(this)
                    refreshList()
                    dialog.dismiss()
                }

                val overlap = ScheduleInsights.detectOverlaps(newList).firstOrNull {
                    it.first.id == newSchedule.id || it.second.id == newSchedule.id
                }
                if (overlap != null) {
                    val other = if (overlap.first.id == newSchedule.id) overlap.second else overlap.first
                    AlertDialog.Builder(this)
                        .setTitle(R.string.schedules_overlap_warning_title)
                        .setMessage(
                            getString(
                                R.string.schedules_overlap_warning_message_fmt,
                                ScheduleInsights.scheduleDisplayName(other)
                            )
                        )
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.schedules_overlap_warning_save) { _, _ ->
                            persistSchedule()
                        }
                        .showAccented()
                    return@setOnClickListener
                }

                persistSchedule()
            }
        }

        dialog.show()
    }

    private fun parseLocationCoordinateQuery(raw: String): Pair<Double, Double>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }

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
        if (address == null) {
            return null
        }

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
            .inflate(R.layout.dialog_location_picker_search, FrameLayout(this), false)
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

    fun itemAt(position: Int): ScheduleStore.Schedule? = currentList.getOrNull(position)

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
    private val cardRoot = itemView.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardRoot)

    private var current: ScheduleStore.Schedule? = null
    private var binding = false

    private fun tintEnabledSwitch() {
        val ctx = itemView.context
        val accent = AccentColor.getAccentColorInt(ctx)
        val thumbOff = Color.WHITE
        val thumbDisabled = Color.LTGRAY
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

    private fun dp(value: Int): Int =
        (value * itemView.resources.displayMetrics.density + 0.5f).toInt()

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
        val selected = selecting && isSelected(s.id)
        checkSelect.visibility = if (selecting) View.VISIBLE else View.GONE
        checkSelect.isChecked = selected
        cardRoot.isClickable = canInteractNow || selecting
        cardRoot.isLongClickable = canInteractNow
        val ctx = itemView.context
        if (selected) {
            val accent = AccentColor.getAccentColorInt(ctx)
            cardRoot.strokeWidth = dp(2)
            cardRoot.strokeColor = accent
            cardRoot.setCardBackgroundColor(ColorUtils.setAlphaComponent(accent, 0x22))
        } else {
            cardRoot.strokeWidth = dp(1)
            cardRoot.strokeColor = ContextCompat.getColor(ctx, R.color.switchly_card_stroke)
            cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.switchly_card_bg))
        }

        title.text = s.title.ifBlank { s.profile }

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
        val accent = AccentColor.getAccentColorInt(ctx)
        val tintedIcon = ContextCompat.getDrawable(ctx, iconRes)?.mutate()?.apply {
            setTint(accent)
        }
        if (tintedIcon != null) {
            kindIcon.setImageDrawable(tintedIcon)
        } else {
            kindIcon.setImageResource(iconRes)
        }
        kindIcon.imageTintList = ColorStateList.valueOf(accent)
        kindIcon.setColorFilter(accent)
        kindIcon.isEnabled = true

        val a = when {
            !canInteractNow && s.enabled -> 0.72f
            !canInteractNow -> 0.45f
            s.enabled -> 1f
            else -> 0.5f
        }
        kindIcon.alpha = 1f
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
