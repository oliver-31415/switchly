package at.saltyy.switchly.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.iterator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.onboarding.OnboardingActivity
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.qr.QrGenerateActivity
import at.saltyy.switchly.feature.qr.QrScanActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.stats.StatisticsHubActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.PlayStoreUpdatePrompt
import at.saltyy.switchly.util.getIntCompat
import at.saltyy.switchly.util.ProtectionStatusNotifier
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var tvSwitchMode: TextView
    private lateinit var profileDropdown: MaterialAutoCompleteTextView

    private lateinit var blockedHeader: TextView
    private lateinit var rvBlocked: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnPickAppsEmpty: MaterialButton

    private val blockedAdapter = BlockedAppsAdapter()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Ensure the flow in SwitchModeStore reflects the current prefs
        SwitchModeStore.ensureInit(applicationContext)

        // Refresh premium status from Google Play Billing
        PremiumManager.refreshFromPlay(this)

        // Onboarding gate (versioned)
        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)

        val onboardingVersion = sp.getIntCompat("onboarding_version", 0)
        if (onboardingVersion < OnboardingActivity.ONBOARDING_VERSION) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)

        // Classic system-bars for the main screen:
        // - Bottom nav sits flush (no extra inset padding)
        // - Status bar keeps system look (no accent color bleed)
        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = toolbar,
            bottomNav = bottomNav
        )

        // Match Schedules look: keep BottomNav slightly above the gesture area on all devices
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)

        // Keep status bar neutral (no accent bleed into system bar)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        // Match schedules: keep navigation bar dark so the system gesture/nav area reads as spacing.
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setSupportActionBar(toolbar)
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // Force white toolbar action/overflow icons (some devices/theme combos render them black in light mode)
        runCatching {
            val white = ContextCompat.getColor(this, R.color.font_white)
            toolbar.overflowIcon?.mutate()?.let { it.setTint(white); toolbar.overflowIcon = it }
            toolbar.navigationIcon?.mutate()?.let { it.setTint(white); toolbar.navigationIcon = it }
        }

        // UI refs
        tvSwitchMode = findViewById(R.id.tvSwitchMode)
        profileDropdown = findViewById(R.id.profileDropdown)
        blockedHeader = findViewById(R.id.blockedHeader)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnPickAppsEmpty = findViewById(R.id.btnPickAppsEmpty)
        rvBlocked = findViewById(R.id.rvBlocked)

        // Update button colors to the selected accent color
        applyAccentToButtons()

        // Empty-state "pick apps" button (NFC-lock aware)
        btnPickAppsEmpty.setOnClickListener {
            openAppPickerIfUnlocked()
        }

        // Profile dropdown: selection only, no free text allowed
        profileDropdown.inputType = InputType.TYPE_NULL
        profileDropdown.keyListener = null
        profileDropdown.setOnClickListener { v ->
            v.post {
                if (v.windowToken != null && !isFinishing && !isDestroyed) {
                    profileDropdown.showDropDown()
                }
            }
        }

        // Button to add a new profile (NFC-lock aware)
        findViewById<View>(R.id.btnAddProfile).setOnClickListener {
            openAddProfileIfUnlocked()
        }

        // Open app picker (NFC-lock aware)
        findViewById<View>(R.id.btnPickApps).setOnClickListener {
            openAppPickerIfUnlocked()
        }

        // Blocked apps list (non-scrollable – parent scrolls)
        rvBlocked.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean = false
        }
        rvBlocked.isNestedScrollingEnabled = false
        rvBlocked.setHasFixedSize(false)
        rvBlocked.adapter = blockedAdapter

        // React to global enable/disable state
        lifecycleScope.launch {
            SwitchModeStore.enabledFlow.collect { enabled ->
                updateSwitchState()
                if (enabled) {
                    BlockingRuntime.ensureRunning(this@MainActivity)
                } else {
                    val keepForTimer =
                        SwitchModeStore.getTemporaryRemainingMillis(this@MainActivity) > 0L ||
                            SwitchModeStore.getTemporaryEnableRemainingMillis(this@MainActivity) > 0L ||
                            EmergencyBypassStore.isActive(this@MainActivity)

                    if (keepForTimer) {
                        BlockingRuntime.ensureRunning(this@MainActivity)
                    } else {
                        BlockingRuntime.stop(this@MainActivity)
                    }
                }
            }
        }

        // only runs when activity is active
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (currentCoroutineContext().isActive) {
                    updateSwitchState()
                    delay(1000)
                }
            }
        }

        refreshProfilesUi()
        refreshBlockedList()

        setupBottomNav(bottomNav)
    }

    private fun rootContentView(): View? {
        val content = findViewById<ViewGroup>(android.R.id.content)
        return content?.getChildAt(0)
    }

    private fun setupBottomNav(bottomNav: BottomNavigationView) {
        // Always set "home" active in main
        bottomNav.selectedItemId = R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true

                R.id.nav_schedules -> {
                    startActivity(Intent(this, SchedulesActivity::class.java))
                    true
                }

                R.id.nav_stats -> {
                    startActivity(Intent(this, StatisticsHubActivity::class.java))
                    true
                }

                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProfilesUi()
        refreshBlockedList()
        updateSwitchState()

        // Refresh toolbar and button accent colors when theme changes
        findViewById<MaterialToolbar>(R.id.toolbar)
            .setBackgroundColor(AccentColor.getToolbarColor(this))
        applyAccentToButtons()

        // Bottom navigation state
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        setupBottomNav(bottomNav)

        // refresh menu visibility (QR toggle)
        invalidateOptionsMenu()

        // Keep the user informed when protection is inactive (e.g. Accessibility disabled)
        ProtectionStatusNotifier.refresh(this)

        // Optional: show "update available" prompt when Google Play has a newer version
        PlayStoreUpdatePrompt.check(this)
    }

    private fun applyAccentToButtons() {
        val tint = AccentColor.getActiveColor(this)
        btnPickAppsEmpty.backgroundTintList = tint
        (findViewById<View>(R.id.btnAddProfile) as? MaterialButton)?.backgroundTintList = tint
        (findViewById<View>(R.id.btnPickApps) as? MaterialButton)?.backgroundTintList = tint
    }

    private fun isNfcLocked(): Boolean {
        val enabled = SwitchModeStore.isEnabled(this)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        return enabled && requireNfc
    }

    private fun openAppPickerIfUnlocked() {
        if (isNfcLocked()) {
            Toast.makeText(
                this,
                getString(R.string.toast_cannot_change_profile_while_locked),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }
    }

    private fun openAddProfileIfUnlocked() {
        if (isNfcLocked()) {
            Toast.makeText(
                this,
                getString(R.string.toast_cannot_change_profile_while_locked),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        showAddProfileDialog()
    }

    /**
     * Updates the colored "Switchly: Enabled/Disabled" header, and optionally appends temporary disable OR temporary enable OR emergency info.
     */
    private fun updateSwitchState() {
        SwitchModeStore.finishTemporaryDisableIfExpired(this)
        SwitchModeStore.finishTemporaryEnableIfExpired(this)

        val enabled = SwitchModeStore.isEnabled(this)
        val baseEnabled = SwitchModeStore.isBaseEnabled(this)

        val tempDisableRemaining = SwitchModeStore.getTemporaryRemainingMillis(this)
        val tempEnableRemaining = SwitchModeStore.getTemporaryEnableRemainingMillis(this)

        val emergencyActive = EmergencyBypassStore.isActive(this)
        val emergencyRemMinutes = EmergencyBypassStore.minutesRemaining(this)

        val stateWord =
            if (enabled) getString(R.string.state_enabled) else getString(R.string.state_disabled)
        val sb = StringBuilder()
        sb.append(getString(R.string.switch_mode_label, stateWord))

        if (tempDisableRemaining > 0L) {
            val secs = tempDisableRemaining / 1000L
            val mins = secs / 60L
            val remSecs = secs % 60L
            sb.append(" (")
            sb.append(getString(R.string.temp_disabled_for, mins, remSecs))
            sb.append(")")
        } else if (tempEnableRemaining > 0L) {
            val secs = tempEnableRemaining / 1000L
            val mins = secs / 60L
            val remSecs = secs % 60L
            sb.append(" (")
            sb.append(getString(R.string.temp_enabled_for, mins, remSecs))
            sb.append(")")
        }

        if (emergencyActive) {
            sb.append("  •  ")
            sb.append(getString(R.string.emergency_enabled_inline, emergencyRemMinutes))
        }

        val full = sb.toString()
        val span = SpannableString(full)
        val start = full.indexOf(stateWord)
        if (start >= 0) {
            val color =
                if (enabled) ContextCompat.getColor(this, R.color.switchly_green)
                else ContextCompat.getColor(this, R.color.status_error)

            span.setSpan(
                ForegroundColorSpan(color),
                start,
                start + stateWord.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvSwitchMode.text = span

        // Cleanup stale timers
        if (tempDisableRemaining == 0L && enabled && baseEnabled) {
            SwitchModeStore.clearTemporary(this)
        }

        if (tempEnableRemaining == 0L && !baseEnabled) {
            SwitchModeStore.clearTemporaryEnable(this)
        }
    }

    private fun refreshProfilesUi() {
        val profiles: List<String> = ProfileStore.getProfiles(this).toList().sorted()
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, profiles)
        profileDropdown.setAdapter(adapter)

        val current = ProfileStore.getCurrent(this)
        if (current.isNullOrEmpty() && profiles.isNotEmpty()) {
            ProfileStore.setCurrent(this, profiles.first())
            profileDropdown.setText(profiles.first(), false)
        } else {
            profileDropdown.setText(current ?: "", false)
        }

        profileDropdown.setOnItemClickListener { _, _, pos, _ ->
            val selected = adapter.getItem(pos) ?: return@setOnItemClickListener

            if (isNfcLocked()) {
                Toast.makeText(
                    this,
                    getString(R.string.toast_cannot_change_profile_while_locked),
                    Toast.LENGTH_SHORT
                ).show()

                val currentProfile = ProfileStore.getCurrent(this)
                profileDropdown.setText(currentProfile ?: "", false)
                return@setOnItemClickListener
            }

            ProfileStore.setCurrent(this, selected)
            refreshBlockedList()
        }
    }

    private fun snackRoot(): View {
        return findViewById(android.R.id.content) ?: window.decorView
    }

    private fun showAddProfileDialog() {
        // Safety guard: even if called directly elsewhere, don't allow while NFC locked
        if (isNfcLocked()) {
            Toast.makeText(
                this,
                getString(R.string.toast_cannot_change_profile_while_locked),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val sheet = BottomSheetDialog(this)
        val parent = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_profile, parent, false)
        sheet.setContentView(view)

        val til = view.findViewById<TextInputLayout>(R.id.tilProfile)
        val et = view.findViewById<TextInputEditText>(R.id.etProfile)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnCreate = view.findViewById<MaterialButton>(R.id.btnCreate)

        btnCancel.backgroundTintList = AccentColor.getActiveColor(this)
        btnCreate.backgroundTintList = AccentColor.getActiveColor(this)

        fun validate(): Boolean {
            val name = et.text?.toString()?.trim().orEmpty()
            return when {
                name.length < 2 -> {
                    til.error = getString(R.string.profile_name_too_short); false
                }
                ProfileStore.getProfiles(this).contains(name) -> {
                    til.error = getString(R.string.profile_name_exists, name); false
                }
                else -> {
                    til.error = null; true
                }
            }
        }

        btnCreate.isEnabled = false
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnCreate.isEnabled = validate()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnCancel.setOnClickListener { sheet.dismiss() }

        btnCreate.setOnClickListener {
            if (!validate()) return@setOnClickListener
            val name = et.text?.toString()?.trim().orEmpty()

            val added = ProfileStore.addProfile(this, name)
            if (added) {
                ProfileStore.setCurrent(this, name)
                refreshProfilesUi()
                profileDropdown.setText(name, false)
                refreshBlockedList()
                sheet.dismiss()

                Snackbar.make(
                    snackRoot(),
                    getString(R.string.profile_created, name),
                    Snackbar.LENGTH_SHORT
                ).show()
            } else {
                til.error = getString(R.string.profile_name_exists, name)
            }
        }

        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
    }

    private fun refreshBlockedList() {
        val current = ProfileStore.getCurrent(this)
        val pkgs: List<String> = loadBlockedPkgsFor(current)
        val items: List<AppDisplay> = pkgs
            .map { pkg -> resolveAppDisplay(pkg) }
            .sortedBy { app -> app.label.lowercase() }

        val isEmpty = items.isEmpty()
        blockedHeader.visibility = if (isEmpty) View.GONE else View.VISIBLE
        tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        btnPickAppsEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE

        blockedAdapter.submitList(items)
    }

    private fun loadBlockedPkgsFor(profile: String?): List<String> {
        if (profile.isNullOrEmpty()) return emptyList()

        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)
        val key = "blocked_apps_$profile"

        // No legacy formats: we only accept a real StringSet.
        // If the stored type is wrong, don't crash — drop the value.
        val set = try {
            sp.getStringSet(key, emptySet())?.toSet() ?: emptySet()
        } catch (_: ClassCastException) {
            sp.edit { remove(key) }
            emptySet()
        }

        return set.toList()
    }

    private fun resolveAppDisplay(pkg: String): AppDisplay {
        val pm = packageManager
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = runCatching {
                pm.getApplicationLabel(ai)?.toString()
            }.getOrNull() ?: pkg
            val icon = pm.getApplicationIcon(pkg)
            AppDisplay(label, pkg, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            AppDisplay(pkg, pkg, ContextCompat.getDrawable(this, android.R.drawable.sym_def_app_icon)!!)
        }
    }

    data class AppDisplay(val label: String, val pkg: String, val icon: Drawable)

    private class BlockedAppsAdapter :
        ListAdapter<AppDisplay, BlockedAppsAdapter.VH>(DIFF) {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val name: TextView = v.findViewById(R.id.appName)
            val pkg: TextView = v.findViewById(R.id.appPkg)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.row_blocked_app, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.icon.setImageDrawable(item.icon)
            holder.name.text = item.label
            holder.pkg.text = item.pkg
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppDisplay>() {
                override fun areItemsTheSame(oldItem: AppDisplay, newItem: AppDisplay): Boolean {
                    return oldItem.pkg == newItem.pkg
                }

                override fun areContentsTheSame(oldItem: AppDisplay, newItem: AppDisplay): Boolean {
                    return oldItem.pkg == newItem.pkg && oldItem.label == newItem.label
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_top_main, menu)

        val white = ContextCompat.getColor(this, R.color.font_white)

        for (item in menu) {
            item.icon?.mutate()?.setTint(white)
        }

        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val showQr = sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_QR_CODE, false)
        menu.findItem(R.id.action_qr)?.isVisible = showQr
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_qr -> {
                showQrChoiceDialog()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showQrChoiceDialog() {
        val items = arrayOf(getString(R.string.qr_scan_title), getString(R.string.qr_generate_title))
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.qr_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, QrScanActivity::class.java))
                    1 -> startActivity(Intent(this, QrGenerateActivity::class.java))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
