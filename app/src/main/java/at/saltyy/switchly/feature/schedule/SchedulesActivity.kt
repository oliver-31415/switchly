package at.saltyy.switchly.feature.schedule

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.ScheduleStore
import at.saltyy.switchly.data.prefs.ScheduleStore.Days
import at.saltyy.switchly.feature.stats.StatisticsHubActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

class SchedulesActivity : AppCompatActivity() {

    private enum class NewScheduleMode { TIME, WIFI, BT }
    private enum class Kind { TIME, WIFI, BT }
    private enum class TimeMode { SINGLE, TIME_RANGE, DATE_RANGE }

    private lateinit var adapter: ScheduleAdapter

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.perm_location_denied_wifi_schedule),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    private val requestBluetoothPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    getString(R.string.perm_bt_denied_schedule),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedules)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        // Classic system bars (status/nav handled by the framework)
        // + apply the same bottom-nav gesture inset handling used by Main/Stats
        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = toolbar,
            bottomNav = bottomNav
        )
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)

        // Keep status bar neutral (no accent bleed into system bar)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        val recycler = findViewById<RecyclerView>(R.id.recyclerSchedules)
        recycler.layoutManager = LinearLayoutManager(this)

        adapter = ScheduleAdapter(
            onToggleEnabled = { schedule, enabled ->
                val list = ScheduleStore.getAll(this).map {
                    if (it.id == schedule.id) it.copy(enabled = enabled) else it
                }
                ScheduleStore.saveAll(this, list)
                SchedulePlanner.updateNextAlarm(this)
                SchedulePlanner.notifyNextChanged(this)
            },
            onDelete = { schedule -> confirmDelete(schedule) },
            onEdit = { schedule -> showScheduleDialog(existing = schedule, preselectedMode = null) }
        )
        recycler.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener { showNewScheduleTypeDialog() }
        refreshList()

        setupBottomNav(bottomNav)
    }

    private fun rootContentView(): View? {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return content?.getChildAt(0)
    }

    override fun onResume() {
        super.onResume()
        findViewById<BottomNavigationView>(R.id.bottomNav)?.selectedItemId = R.id.nav_schedules

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

    private fun setupBottomNav(bottomNav: BottomNavigationView) {
        bottomNav.selectedItemId = R.id.nav_schedules

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                    finish()
                    true
                }

                R.id.nav_schedules -> true

                R.id.nav_stats -> {
                    startActivity(Intent(this, StatisticsHubActivity::class.java))
                    finish()
                    true
                }

                else -> false
            }
        }
    }

    private fun showNewScheduleTypeDialog() {
        val isPremium = PremiumManager.isPremium(this)

        data class TypeItem(val label: String, val iconRes: Int, val mode: NewScheduleMode)

        val items = buildList {
            add(TypeItem(getString(R.string.schedules_type_time), R.drawable.alarm_24, NewScheduleMode.TIME))
            if (isPremium) {
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

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.schedules_choose_type)
            .setAdapter(adapter) { _, which ->
                when (items[which].mode) {
                    NewScheduleMode.TIME -> showScheduleDialog(null, NewScheduleMode.TIME)
                    NewScheduleMode.WIFI -> showScheduleDialog(null, NewScheduleMode.WIFI)
                    NewScheduleMode.BT -> showScheduleDialog(null, NewScheduleMode.BT)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener { dialog.applyAccentToButtons() }
        dialog.show()
    }

    private fun AlertDialog.applyAccentToButtons() {
        val accent = AccentColor.getAccentColorInt(context)
        getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
        getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
        getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(accent)
    }

    private fun refreshList() {
        adapter.submitList(ScheduleStore.getAll(this))
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
            .show()
    }

    private fun wifiSsidPermissionName(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    private fun hasWifiSsidPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            wifiSsidPermissionName()
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

    private fun showWhyLocationDialogForWifi() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_location_title)
            .setMessage(R.string.perm_location_why_wifi_ssid)
            .setPositiveButton(R.string.ok) { _, _ ->
                requestLocationPermission.launch(wifiSsidPermissionName())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
            .setNeutralButton(R.string.permissions_btn_open_settings) { _, _ ->
                openAppSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_schedule_add, null, false)

        val editTitle = view.findViewById<EditText>(R.id.editTitle)
        val editNote = view.findViewById<EditText>(R.id.editNote)
        val spinnerProfile = view.findViewById<Spinner>(R.id.spinnerProfile)
        val spinnerAction = view.findViewById<Spinner>(R.id.spinnerAction)
        val spinnerTimeMode = view.findViewById<Spinner>(R.id.spinnerTimeMode)

        val groupTimeMode = view.findViewById<View>(R.id.groupTimeMode)

        val groupTime = view.findViewById<View>(R.id.groupTime)
        val groupTimeRow = view.findViewById<View>(R.id.groupTimeRow)
        val textStartTime = view.findViewById<TextView>(R.id.textStartTime)
        val textEndTime = view.findViewById<TextView>(R.id.textEndTime)

        // Connection schedules: make the time window optional
        val switchConnTimeWindow = view.findViewById<SwitchCompat>(R.id.switchConnTimeWindow)
        val textConnAllDayHint = view.findViewById<TextView>(R.id.textConnAllDayHint)

        val inputWifiSsid = view.findViewById<EditText>(R.id.inputWifiSsid)
        val inputBtName = view.findViewById<EditText>(R.id.inputBtName)

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
            inputWifiSsid.visibility = View.GONE
            inputBtName.visibility = View.GONE
        }

        fun applyKindVisibility() {
            val isTime = kind == Kind.TIME
            groupTimeMode.isVisible = isTime

            val showWifi = isPremium && kind == Kind.WIFI
            val showBt = isPremium && kind == Kind.BT

            inputWifiSsid.isVisible = showWifi
            inputBtName.isVisible = showBt

            // Time window switch only makes sense for connection schedules
            val isConn = kind == Kind.WIFI || kind == Kind.BT
            switchConnTimeWindow.isVisible = isConn
            textConnAllDayHint.isVisible = false
        }

        // Profiles
        val profiles = ProfileStore.getProfiles(this)
        val profileList: List<String> = if (existing != null && existing.profile !in profiles) {
            listOf(existing.profile) + profiles
        } else profiles.toList()

        spinnerProfile.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            profileList
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        fun selectProfile(name: String?) {
            if (name == null) return
            val idx = profileList.indexOf(name)
            if (idx >= 0) spinnerProfile.setSelection(idx)
        }

        // Defaults
        var startMinutes = if (kind == Kind.TIME) 8 * 60 else 0
        var endMinutes = if (kind == Kind.TIME) 16 * 60 else 24 * 60 - 1
        var startDateYmd = 0
        var endDateYmd = 0

        fun currentTimeMode(): TimeMode {
            if (kind != Kind.TIME) return TimeMode.SINGLE
            return when (spinnerTimeMode.selectedItemPosition) {
                1 -> TimeMode.TIME_RANGE
                2 -> TimeMode.DATE_RANGE
                else -> TimeMode.SINGLE
            }
        }

        // Setup TimeMode spinner (only meaningful for TIME)
        fun setupTimeModeSpinner() {
            if (kind != Kind.TIME) return

            val labels = listOf(
                getString(R.string.schedules_time_mode_single),
                getString(R.string.schedules_time_mode_range),
                getString(R.string.schedules_time_mode_date_range)
            )

            spinnerTimeMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val sel = when {
                existing?.type == ScheduleStore.Type.ONE_TIME -> 2
                existing?.action == ScheduleStore.Action.ENABLE_AND_DISABLE ||
                    existing?.action == ScheduleStore.Action.DISABLE_AND_ENABLE -> 1
                else -> 0
            }
            spinnerTimeMode.setSelection(sel)
        }

        // Action spinner (filled in code from strings)
        var actionOptions: List<ScheduleStore.Action> = emptyList()

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
            spinnerAction.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val pref = prefer ?: options.firstOrNull()
            val idx = if (pref != null) options.indexOf(pref) else 0
            spinnerAction.setSelection(if (idx >= 0) idx else 0)
        }

        fun selectedAction(): ScheduleStore.Action {
            val idx = spinnerAction.selectedItemPosition
            return actionOptions.getOrNull(idx) ?: ScheduleStore.Action.ENABLE
        }

        fun formatMinutes(m: Int): String {
            val h = m / 60
            val mm = m % 60
            return String.format(Locale.getDefault(), "%02d:%02d", h, mm)
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
                                listOf(
                                    ScheduleStore.Action.ENABLE,
                                    ScheduleStore.Action.DISABLE,
                                    ScheduleStore.Action.TOGGLE
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
                                listOf(
                                    ScheduleStore.Action.ENABLE_AND_DISABLE,
                                    ScheduleStore.Action.DISABLE_AND_ENABLE
                                ),
                                existing?.action?.takeIf {
                                    it == ScheduleStore.Action.ENABLE_AND_DISABLE ||
                                        it == ScheduleStore.Action.DISABLE_AND_ENABLE
                                } ?: ScheduleStore.Action.ENABLE_AND_DISABLE
                            )
                        }

                        TimeMode.DATE_RANGE -> {
                            setActionOptions(
                                listOf(ScheduleStore.Action.ENABLE_AND_DISABLE),
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

                    setActionOptions(
                        listOf(
                            ScheduleStore.Action.ENABLE_AND_DISABLE,
                            ScheduleStore.Action.DISABLE_AND_ENABLE
                        ),
                        preferred
                    )
                }
            }
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
                textStartDate.text = getString(
                    R.string.schedules_label_value_fmt,
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

        // Fill from existing / defaults
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

        // TimeMode spinner listener (TIME only)
        spinnerTimeMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (kind != Kind.TIME) return
                updateActionUi()
                updateVisibilityForMode()
                updateLabels()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Pickers
        fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
            val h = initial / 60
            val m = initial % 60
            TimePickerDialog(this, { _, hourOfDay, minute ->
                onPicked(hourOfDay * 60 + minute)
                updateLabels()
            }, h, m, true).show()
        }

        fun pickDate(initialYmd: Int, onPicked: (Int) -> Unit) {
            val cal = Calendar.getInstance()
            if (initialYmd > 0) {
                val y = initialYmd / 10000
                val mo = (initialYmd / 100) % 100
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

        // --- Quick chips sync (weekly only) ---
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

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.schedules_add else R.string.schedules_edit)
            .setView(view)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.applyAccentToButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {

                inputWifiSsid.error = null
                inputBtName.error = null

                val profile = spinnerProfile.selectedItem?.toString() ?: ""
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
                    inputWifiSsid.error = getString(R.string.schedules_error_wifi_required)
                    inputWifiSsid.requestFocus()
                    showSnack(R.string.schedules_error_wifi_required)
                    return@setOnClickListener
                }

                if (kind == Kind.BT && isPremium && btName.isNullOrBlank()) {
                    inputBtName.error = getString(R.string.schedules_error_bt_required)
                    inputBtName.requestFocus()
                    showSnack(R.string.schedules_error_bt_required)
                    return@setOnClickListener
                }

                // Permissions only if actually saving those schedule types / conditions
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

/* --- Adapter & ViewHolder --- */

private class ScheduleAdapter(
    private val onToggleEnabled: (ScheduleStore.Schedule, Boolean) -> Unit,
    private val onDelete: (ScheduleStore.Schedule) -> Unit,
    private val onEdit: (ScheduleStore.Schedule) -> Unit
) : androidx.recyclerview.widget.ListAdapter<ScheduleStore.Schedule, ScheduleViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScheduleViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_schedule, parent, false)
        return ScheduleViewHolder(v, onToggleEnabled, onDelete, onEdit)
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
    private val onDelete: (ScheduleStore.Schedule) -> Unit,
    private val onEdit: (ScheduleStore.Schedule) -> Unit
) : RecyclerView.ViewHolder(itemView) {

    private val kindIcon = itemView.findViewById<android.widget.ImageView>(R.id.imgKind)
    private val title = itemView.findViewById<TextView>(R.id.textTitle)
    private val subtitle = itemView.findViewById<TextView>(R.id.textSubtitle)
    private val note = itemView.findViewById<TextView>(R.id.textNote)
    private val switchEnabled = itemView.findViewById<SwitchCompat>(R.id.switchEnabled)
    private val buttonDelete = itemView.findViewById<ImageButton>(R.id.buttonDelete)
    private val cardRoot = itemView.findViewById<View>(R.id.cardRoot)

    private var current: ScheduleStore.Schedule? = null
    private var binding = false

    init {
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (!binding) current?.let { onToggleEnabled(it, isChecked) }
        }
        buttonDelete.setOnClickListener { current?.let { onDelete(it) } }
        cardRoot.setOnClickListener { current?.let { onEdit(it) } }
    }

    private fun fmtMinutes(m: Int): String {
        val h = m / 60
        val mm = m % 60
        return String.format(Locale.getDefault(), "%02d:%02d", h, mm)
    }

    fun bind(s: ScheduleStore.Schedule) {
        current = s

        binding = true
        switchEnabled.isChecked = s.enabled
        binding = false

        title.text = s.title.ifBlank { s.profile }
        val ctx = itemView.context

        val hasWifi = !s.wifiSsid.isNullOrBlank()
        val hasBt = !s.btDeviceName.isNullOrBlank()

        // Nice icon in the list: time / wifi / bluetooth (or combined)
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
