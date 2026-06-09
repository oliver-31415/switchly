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

package at.saltyy.switchly.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.core.widget.TextViewCompat
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
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AttemptLimitStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.ExactAlarmPermissionSync
import at.saltyy.switchly.data.prefs.LimitReachedStore
import at.saltyy.switchly.data.prefs.OpenCountStore
import at.saltyy.switchly.data.prefs.ProfileStore
import at.saltyy.switchly.data.prefs.SchedulePlanner
import at.saltyy.switchly.data.prefs.SessionLimitStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.UsageLimitStore
import at.saltyy.switchly.feature.barcode.BarcodeScanActivity
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.feature.onboarding.OnboardingActivity
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.qr.QrGenerateActivity
import at.saltyy.switchly.feature.qr.QrScanActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.InAppBlockingActivity
import at.saltyy.switchly.feature.settings.ManageBarcodesActivity
import at.saltyy.switchly.feature.settings.ManageBlockedWebsitesActivity
import at.saltyy.switchly.feature.settings.ManagePairedTagsActivity
import at.saltyy.switchly.feature.settings.PermissionsActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.support.SupportActivity
import at.saltyy.switchly.feature.tools.BlockingHubActivity
import at.saltyy.switchly.feature.tools.ToolsHubActivity
import at.saltyy.switchly.feature.usage.QuickLimitDialogs
import at.saltyy.switchly.feature.usage.ScreenTimeDashboardActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.premium.PremiumManager
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.dialog.Dialogs
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.AppUsageToday
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.PlayStoreUpdatePrompt
import at.saltyy.switchly.util.ProtectionStatusNotifier
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import at.saltyy.switchly.util.BatteryOptimizationCompat
import at.saltyy.switchly.util.SystemBarColorCompat
import at.saltyy.switchly.util.TimeFormatPrefs
import at.saltyy.switchly.util.getIntCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_UI_HINTS = "switchly_ui_hints"
        private const val KEY_TEMP_MODE_DISCOVERED = "temp_mode_discovered"
        private const val KEY_PRIMARY_TOGGLE_TAP_COUNT = "primary_toggle_tap_count"
        private const val KEY_QUICK_ACTIONS_EXPANDED = "home_quick_actions_expanded"
        private const val KEY_EXPERIMENTAL_NOTICE_211 = "experimental_notice_2_1_1_shown"
        private const val KEY_QA_APPS = "home_quick_tile_apps"
        private const val KEY_QA_PROFILES = "home_quick_tile_profiles"
        private const val KEY_QA_WEBSITES = "home_quick_tile_websites"
        private const val KEY_QA_INAPP = "home_quick_tile_inapp"
        private const val KEY_QA_USAGE = "home_quick_tile_usage"
        private const val KEY_QA_NFC_WRITE = "home_quick_tile_nfc_write"
        private const val KEY_QA_BLOCKED_NOTIFICATIONS = "home_quick_tile_blocked_notifications"
        private const val KEY_QA_QR = "home_quick_tile_qr"
        private const val KEY_QA_BARCODE = "home_quick_tile_barcode"
        private val PAYLOAD_BLOCKED_CHIPS = Any()
        private val PAYLOAD_BLOCKED_EDIT_STATE = Any()

    }

    // Core status
    private lateinit var ivStatusIcon: ImageView
    private lateinit var tvSwitchMode: TextView
    private lateinit var tvActiveProfile: TextView
    private lateinit var rowActiveProfile: View
    private lateinit var tvNfcLockedHint: TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var chipTemp: Chip
    private lateinit var tvTempHint: TextView
    private lateinit var tvEmergencyHint: TextView

    // Profile controls
    private lateinit var profileDropdown: MaterialAutoCompleteTextView

    // Setup banner
    private lateinit var cardSetup: MaterialCardView
    private lateinit var tvSetupTitle: TextView
    private lateinit var tvSetupSubtitle: TextView
    private lateinit var tvSetupBadge: TextView
    private lateinit var tvSetupDesc: TextView
    private lateinit var btnFinishSetup: MaterialButton

    // Quick actions tiles
    private lateinit var tileManageApps: MaterialCardView
    private lateinit var tileProfiles: MaterialCardView
    private lateinit var tileWriteNfc: MaterialCardView
    private lateinit var tileToggleOptions: MaterialCardView
    private lateinit var tilePermissions: MaterialCardView
    private lateinit var tileNfcWrite: MaterialCardView
    private lateinit var tileBlockedNotifications: MaterialCardView
    private lateinit var tileQr: MaterialCardView
    private lateinit var tileBarcode: MaterialCardView
    private lateinit var rowToolsShortcuts: LinearLayout
    private lateinit var rowScanShortcuts: LinearLayout

    private lateinit var rowQuickActionsHeader: View
    private lateinit var tvQuickActionsTitle: TextView
    private lateinit var ivQuickActionsEdit: ImageView
    private lateinit var ivQuickActionsChevron: ImageView
    private lateinit var gridQuickActions: View
    private lateinit var rowManageShortcuts: LinearLayout
    private lateinit var rowUtilityShortcuts: LinearLayout
    private lateinit var badgePermissions: TextView

    // Next schedule
    private lateinit var cardNextSchedule: MaterialCardView
    private lateinit var tvNextScheduleValue: TextView
    private var nextChangedReceiver: BroadcastReceiver? = null

    // Blocked right now (live)
    private lateinit var cardBlockedNow: MaterialCardView
    private lateinit var layoutBlockedNowEmpty: View
    private lateinit var ivBlockedNowEmptyIcon: ImageView
    private lateinit var tvBlockedNowEmpty: TextView
    private lateinit var tvBlockedNowMore: TextView
    private lateinit var rvBlockedNow: RecyclerView

    // Blocked list
    private lateinit var rvBlocked: RecyclerView
    private lateinit var layoutBlockedAppsEmpty: View
    private lateinit var tvEmpty: TextView
    private lateinit var btnPickApps: MaterialButton

    private val blockedAdapter = BlockedAppsAdapter(
        onAppClick = { app -> showBlockedAppQuickActions(app) },
        onEditClick = { app -> showBlockedAppQuickActions(app) },
        canEdit = { ensureCanRemoveBlockedApp(showToast = false) }
    )
    private val blockedNowAdapter = BlockedNowAdapter()

    private fun notifyBlockedChipsChanged() {
        notifyBlockedAdapterRangeChanged(PAYLOAD_BLOCKED_CHIPS)
    }

    private fun notifyBlockedEditStateChanged() {
        notifyBlockedAdapterRangeChanged(PAYLOAD_BLOCKED_EDIT_STATE)
    }

    private fun notifyBlockedAdapterRangeChanged(payload: Any) {
        val count = blockedAdapter.itemCount
        if (count <= 0) return

        rvBlocked.post {
            val postedCount = blockedAdapter.itemCount
            if (postedCount > 0) {
                blockedAdapter.notifyItemRangeChanged(0, postedCount, payload)
            }
        }
    }

    // Cache the resolved blocked apps for the current profile so we can build the
    // "Blocked right now" section without hitting the PackageManager every second.
    private var cachedBlockedApps: List<AppDisplay> = emptyList()
    private var blockedListRefreshSeq: Int = 0
    private var blockedListRefreshJob: Job? = null

    // For micro-animations: avoid animating every 1s refresh
    private var lastEnabledUi: Boolean? = null

    // The blocked list rows show a "Blocked now" chip. That chip depends on runtime state
    // (enabled/emergency). Keep a small key so we can refresh row bindings when these
    // states change without re-binding every 1s tick.
    private var lastBlockedChipKey: String? = null
    private var lastBlockedEditEnabled: Boolean? = null

    // Live updates: refresh "Blocked now" chips as soon as limits are reached while the app is open.
    private var livePrefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private fun showExperimentalFeaturesNoticeIfNeeded() {
        val prefs = getSharedPreferences(PREFS_UI_HINTS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXPERIMENTAL_NOTICE_211, false)) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.experimental_notice_title)
            .setMessage(R.string.experimental_notice_body)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.edit { putBoolean(KEY_EXPERIMENTAL_NOTICE_211, true) }
            }
            .setOnCancelListener {
                prefs.edit { putBoolean(KEY_EXPERIMENTAL_NOTICE_211, true) }
            }
            .showAccented()
    }

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
        val onboardingDone = sp.getBoolean("onboarding_done", false)
        val hasExistingState = sp.all.isNotEmpty()

        val shouldRunOnboarding = onboardingVersion <= 0 && !onboardingDone && !hasExistingState
        if (shouldRunOnboarding) {
            startActivity(Intent(this, OnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            finish()
            return
        }

        // Existing installs should not be forced through onboarding again on app updates.
        if (onboardingVersion in 1 until OnboardingActivity.ONBOARDING_VERSION) {
            sp.edit {
                putInt("onboarding_version", OnboardingActivity.ONBOARDING_VERSION)
                putBoolean("onboarding_done", true)
            }
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

        // Keep status/navigation bars neutral (no accent bleed into system bar)
        SystemBarColorCompat.setStatusBarColor(window, ContextCompat.getColor(this, android.R.color.black))
        SystemBarColorCompat.setNavigationBarColor(window, ContextCompat.getColor(this, android.R.color.black))
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setSupportActionBar(toolbar)
        showExperimentalFeaturesNoticeIfNeeded()
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // Force white toolbar action/overflow icons (some devices/theme combos render them black in light mode)
        runCatching {
            val white = ContextCompat.getColor(this, R.color.font_white)
            toolbar.overflowIcon?.mutate()?.let { it.setTint(white); toolbar.overflowIcon = it }
            toolbar.navigationIcon?.mutate()?.let { it.setTint(white); toolbar.navigationIcon = it }
        }

        // UI refs
        ivStatusIcon = findViewById(R.id.ivStatusIcon)
        tvSwitchMode = findViewById(R.id.tvSwitchMode)
        tvActiveProfile = findViewById(R.id.tvActiveProfile)
        rowActiveProfile = findViewById(R.id.rowActiveProfile)
        tvNfcLockedHint = findViewById(R.id.tvNfcLockedHint)
        btnToggle = findViewById(R.id.btnToggle)
        chipTemp = findViewById(R.id.chipTemp)
        tvTempHint = findViewById(R.id.tvTempHint)
        tvEmergencyHint = findViewById(R.id.tvEmergencyHint)

        profileDropdown = findViewById(R.id.profileDropdown)

        cardSetup = findViewById(R.id.cardSetup)
        tvSetupTitle = findViewById(R.id.tvSetupTitle)
        tvSetupSubtitle = findViewById(R.id.tvSetupSubtitle)
        tvSetupBadge = findViewById(R.id.tvSetupBadge)
        tvSetupDesc = findViewById(R.id.tvSetupDesc)
        btnFinishSetup = findViewById(R.id.btnFinishSetup)

        tileManageApps = findViewById(R.id.tileManageApps)
        tileProfiles = findViewById(R.id.tileProfiles)
        tileWriteNfc = findViewById(R.id.tileWriteNfc)
        tileToggleOptions = findViewById(R.id.tileToggleOptions)
        tilePermissions = findViewById(R.id.tilePermissions)
        tileNfcWrite = findViewById(R.id.tileNfcWrite)
        tileBlockedNotifications = findViewById(R.id.tileBlockedNotifications)
        tileQr = findViewById(R.id.tileQr)
        tileBarcode = findViewById(R.id.tileBarcode)
        rowToolsShortcuts = findViewById(R.id.rowToolsShortcuts)
        rowScanShortcuts = findViewById(R.id.rowScanShortcuts)

        rowQuickActionsHeader = findViewById(R.id.rowQuickActionsHeader)
        tvQuickActionsTitle = findViewById(R.id.tvQuickActionsTitle)
        ivQuickActionsEdit = findViewById(R.id.ivQuickActionsEdit)
        ivQuickActionsChevron = findViewById(R.id.ivQuickActionsChevron)
        gridQuickActions = findViewById(R.id.gridQuickActions)
        rowManageShortcuts = findViewById(R.id.rowManageShortcuts)
        rowUtilityShortcuts = findViewById(R.id.rowUtilityShortcuts)

        badgePermissions = findViewById(R.id.badgePermissions)

        cardNextSchedule = findViewById(R.id.cardNextSchedule)
        tvNextScheduleValue = findViewById(R.id.tvNextScheduleValue)

        cardBlockedNow = findViewById(R.id.cardBlockedNow)
        layoutBlockedNowEmpty = findViewById(R.id.layoutBlockedNowEmpty)
        ivBlockedNowEmptyIcon = findViewById(R.id.ivBlockedNowEmptyIcon)
        tvBlockedNowEmpty = findViewById(R.id.tvBlockedNowEmpty)
        tvBlockedNowMore = findViewById(R.id.tvBlockedNowMore)
        rvBlockedNow = findViewById(R.id.rvBlockedNow)

        rvBlocked = findViewById(R.id.rvBlocked)
        layoutBlockedAppsEmpty = findViewById(R.id.layoutBlockedAppsEmpty)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnPickApps = findViewById(R.id.btnPickApps)

        // Update button colors to the selected accent color
        applyAccentToButtons()

        // Micro animations: subtle press-scale on interactive cards/buttons
        listOf(
            tileManageApps, tileProfiles, tileWriteNfc, tileToggleOptions, tilePermissions, tileNfcWrite, tileBlockedNotifications, tileQr, tileBarcode,
            cardNextSchedule, cardBlockedNow, rowActiveProfile, rowQuickActionsHeader
        ).forEach { applyPressScale(it) }
        applyPressScale(btnToggle)

        // Primary toggle
        btnToggle.setOnClickListener {
            trackPrimaryToggleTapForTempNudge()
            toggleSwitchIfAllowed()
        }

        // Temp badge (visible when a temp timer is active)
        chipTemp.setOnClickListener {
            val opened = showTempToggleSheet()
            if (opened) {
                markTempModeDiscovered(showSnack = false)
            }
        }

        // Optional text hint also opens temp mode
        tvTempHint.setOnClickListener {
            val opened = showTempToggleSheet()
            if (opened) {
                markTempModeDiscovered(showSnack = false)
            }
        }

        // Emergency quick entry near temporary mode
        tvEmergencyHint.setOnClickListener {
            showEmergencyQuickSheet()
        }

        // Active profile quick-jump
        rowActiveProfile.setOnClickListener { openProfilesIfUnlocked() }

        // Setup CTA
        btnFinishSetup.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }

        // Quick actions
        rowQuickActionsHeader.setOnClickListener { toggleQuickActionsExpanded() }
        ivQuickActionsEdit.setOnClickListener { showQuickActionsCustomizeDialog() }
        tileManageApps.setOnClickListener { openAppPickerIfUnlocked() }
        tileProfiles.setOnClickListener { openProfilesIfUnlocked() }
        tileWriteNfc.setOnClickListener {
            if (EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_websites)
            } else {
                startActivity(Intent(this, ManageBlockedWebsitesActivity::class.java))
            }
        }
        tileToggleOptions.setOnClickListener {
            if (SwitchModeStore.isBaseEnabled(this) || EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_inapp)
            } else {
                startActivity(Intent(this, InAppBlockingActivity::class.java))
            }
        }
        tileQr.setOnClickListener { openQrScannerDirectly() }
        tileQr.setOnLongClickListener {
            showQrChoiceDialog()
            true
        }
        tileBarcode.setOnClickListener { openBarcodeScannerDirectly() }
        tileBarcode.setOnLongClickListener {
            showBarcodeChoiceDialog()
            true
        }
        tilePermissions.setOnClickListener {
            startActivity(ScreenTimeDashboardActivity.intent(this))
        }
        tileNfcWrite.setOnClickListener {
            if (isNfcTagWritingLocked()) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_write_nfc_tags)
            } else {
                startActivity(Intent(this, NfcWriterActivity::class.java))
            }
        }
        tileBlockedNotifications.setOnClickListener {
            openBlockedNotificationsIfAllowed()
        }

        syncScanQuickActions()

        // Next schedule card
        cardNextSchedule.setOnClickListener {
            startActivity(Intent(this, SchedulesActivity::class.java))
        }

        // App picker
        btnPickApps.setOnClickListener { openAppPickerIfUnlocked() }

        // Profile dropdown: selection only, no free text allowed
        profileDropdown.inputType = InputType.TYPE_NULL
        profileDropdown.keyListener = null
        profileDropdown.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !ensureCanSwitchProfiles(showToast = false)) {
                profileDropdown.dismissDropDown()
                profileDropdown.clearFocus()
            }
        }
        profileDropdown.setOnClickListener { v ->
            if (!ensureCanSwitchProfiles(showToast = true)) {
                profileDropdown.dismissDropDown()
                profileDropdown.clearFocus()
                return@setOnClickListener
            }
            v.post {
                if (v.windowToken != null && !isFinishing && !isDestroyed) {
                    profileDropdown.showDropDown()
                }
            }
        }

        // Blocked right now list (non-scrollable – parent scrolls)
        rvBlockedNow.layoutManager = object : LinearLayoutManager(this) {
            override fun canScrollVertically(): Boolean = false
        }
        rvBlockedNow.isNestedScrollingEnabled = false
        rvBlockedNow.setHasFixedSize(false)
        rvBlockedNow.adapter = blockedNowAdapter

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
        updateSetupCard()
        updateNextScheduleCard()
        updateQuickActionsVisibility()
        updateTempHintVisibility()
        updateEmergencyHintVisibility()

        setupBottomNav(bottomNav)
    }

    override fun onResume() {
        super.onResume()
        syncScanQuickActions()

        ExactAlarmPermissionSync.syncAndReschedule(this, reason = "main_resume")
        BlockingRuntime.ensureRunning(this)

        refreshProfilesUi()
        refreshBlockedList()
        updateSwitchState()
        updateSetupCard()
        updateNextScheduleCard()
        updateQuickActionsVisibility()
        updateTempHintVisibility()
        updateEmergencyHintVisibility()

        // Refresh toolbar + accents when theme changes
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

        // Live next schedule updates (optional)
        if (nextChangedReceiver == null) {
            nextChangedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == SchedulePlanner.ACTION_NEXT_CHANGED) {
                        updateNextScheduleCard()
                    }
                }
            }
            val filter = IntentFilter(SchedulePlanner.ACTION_NEXT_CHANGED)
            ContextCompat.registerReceiver(
                this,
                nextChangedReceiver!!,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // Listen for limit reached/open-count changes so "Blocked now" chips update immediately
        // while the app is in the foreground.
        if (livePrefsListener == null) {
            val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)
            livePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key.isNullOrBlank()) return@OnSharedPreferenceChangeListener

                val isLimitReachedKey = key.startsWith("limit_reached_")
                val isOpenCountKey = key.startsWith("open_count_")
                val isManagedRuleKey =
                    key.startsWith("blocked_apps_") ||
                        key.startsWith("usage_limit_min__") ||
                        key.startsWith("attempt_limit__") ||
                        key.startsWith("session_limit_min__")

                when {
                    isManagedRuleKey -> {
                        // Limits can add/remove apps from the main managed list, so fully refresh it.
                        refreshBlockedList()
                    }
                    isLimitReachedKey || isOpenCountKey -> {
                        // Re-bind rows so chips update; keep it lightweight.
                        notifyBlockedChipsChanged()
                        updateBlockedNowCard()
                    }
                }
            }
            sp.registerOnSharedPreferenceChangeListener(livePrefsListener)
        }
    }

    override fun onPause() {
        super.onPause()
        nextChangedReceiver?.let { runCatching { unregisterReceiver(it) } }
        nextChangedReceiver = null

        livePrefsListener?.let {
            runCatching {
                getSharedPreferences("switchly_prefs", MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(it)
            }
        }
        livePrefsListener = null
    }

    private fun setupBottomNav(bottomNav: BottomNavigationView) {
        // Always set "home" active in main
        bottomNav.selectedItemId = R.id.nav_home

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true

                R.id.nav_blocking -> {
                    startActivity(Intent(this, BlockingHubActivity::class.java))
                    true
                }

                R.id.nav_tools -> {
                    startActivity(Intent(this, ToolsHubActivity::class.java))
                    true
                }

                R.id.nav_settings -> {
                    if (SwitchlyAppAccessGuard.isLocked(this)) {
                        SwitchlyAppAccessGuard.showLockedToast(this)
                        false
                    } else {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                }

                else -> false
            }
        }
    }

    private fun applyAccentToButtons() {
        val accent = AccentColor.getAccentColorInt(this)
        val tint = ColorStateList.valueOf(accent)
        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.52) Color.BLACK else Color.WHITE

        btnToggle.backgroundTintList = tint
        btnToggle.setTextColor(onAccent)
        btnFinishSetup.backgroundTintList = tint
        btnFinishSetup.setTextColor(onAccent)
        // Make the icon match the button text (otherwise it may stay default/black).
        btnFinishSetup.iconTint = ColorStateList.valueOf(onAccent)

        // Active temporary-mode chip should follow accent (was still green in custom mode).
        chipTemp.setTextColor(accent)
        chipTemp.chipBackgroundColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x20))
        chipTemp.chipStrokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x66))
        chipTemp.chipStrokeWidth = resources.displayMetrics.density

        // Inline info/hint rows should look like normal text (not accent-colored).
        // Tint their icons to the text color for consistency.
        val hintTint = ColorStateList.valueOf(tvTempHint.currentTextColor)
        TextViewCompat.setCompoundDrawableTintList(tvTempHint, hintTint)
        TextViewCompat.setCompoundDrawableTintList(tvEmergencyHint, hintTint)

    }

    private fun applyPressScale(v: View) {
        // Small scale feedback on press (feels more "premium" without being flashy).
        // IMPORTANT: never consume touch events here, otherwise long-press handlers
        // (e.g. btnToggle temporary enable/disable sheet) stop firing.
        var downX = 0f
        var downY = 0f
        val slop = android.view.ViewConfiguration.get(v.context).scaledTouchSlop

        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    view.animate().scaleX(0.985f).scaleY(0.985f).setDuration(80).start()
                }

                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val movedTooFar = kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop
                    if (!movedTooFar && !view.hasOnClickListeners()) {
                        view.performClick()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false // let the view handle click + long-click normally
        }
    }

    // Subtle fade helpers (used for cards and empty states)
    private fun View.showFade(duration: Long = 160) {
        if (isVisible) return
        alpha = 0f
        isVisible = true
        animate().alpha(1f).setDuration(duration).start()
    }

    private fun View.hideFade(duration: Long = 140) {
        if (!isVisible) return
        animate().alpha(0f).setDuration(duration).withEndAction {
            isVisible = false
            alpha = 1f
        }.start()
    }

    private fun isNfcLocked(): Boolean {
        val enabled = SwitchModeStore.isEnabled(this)
        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        return enabled && requireNfc
    }

    private fun isAppPickingLockedWhileEnabled(): Boolean {
        return SwitchModeStore.isEnabled(this) &&
            !AutomationModeStore.isAppPickerAllowedWhileEnabled(this)
    }

    private fun isProfileSwitchLockedWhileEnabled(): Boolean {
        val enabled = SwitchModeStore.isEnabled(this)
        val emergencyActive = EmergencyBypassStore.isActive(this)
        val emergencyPaused = EmergencyBypassStore.isPaused(this)
        if (!enabled && !emergencyActive) return false

        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        if (requireNfc || emergencyActive || (enabled && emergencyPaused)) return true

        return !AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(this)
    }

    private fun ensureCanOpenAppPicker(showToast: Boolean = true): Boolean {
        if (isNfcLocked()) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.toast_cannot_change_profile_while_locked), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (isAppPickingLockedWhileEnabled()) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.toast_disable_switchly_to_edit_blocked_apps), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun ensureCanRemoveBlockedApp(showToast: Boolean = true): Boolean {
        if (isNfcLocked()) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.toast_cannot_change_profile_while_locked), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (SwitchModeStore.isEnabled(this)) {
            if (showToast) {
                Toast.makeText(this, R.string.toast_disable_switchly_to_edit_app_limits, Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun ensureCanSwitchProfiles(showToast: Boolean = true): Boolean {
        if (isNfcLocked()) {
            if (showToast) {
                EditingLockGuard.showLockedDialog(this, R.string.toast_cannot_change_profile_while_locked)
            }
            return false
        }
        if (isProfileSwitchLockedWhileEnabled()) {
            if (showToast) {
                EditingLockGuard.showLockedDialog(this, R.string.toast_disable_switchly_to_switch_profiles)
            }
            return false
        }
        return true
    }

    private fun toggleSwitchIfAllowed() {
        val enabled = SwitchModeStore.isEnabled(this)
        val canChange = if (enabled) {
            AutomationModeStore.isButtonAllowed(this) || AutomationModeStore.isBarcodeSetupMissing(this)
        } else {
            AutomationModeStore.canButtonEnable(this)
        }
        if (!canChange) {
            val msg = if (enabled && AutomationModeStore.isButtonEnableAllowed(this)) {
                R.string.mode_blocked_button_disable_enable_only
            } else {
                R.string.mode_blocked_button_action
            }
            Toast.makeText(this, getString(msg), Toast.LENGTH_SHORT).show()
            return
        }

        if (enabled && AutomationModeStore.isBarcodeSetupMissing(this)) {
            AppLogStore.append(this, "Safety", "Allowing manual disable because barcode control is enabled but no managed barcodes exist")
        }

        if (enabled && isNfcLocked()) {
            Toast.makeText(
                this,
                getString(R.string.toast_cannot_disable_while_locked),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        SwitchModeStore.setEnabled(this, !enabled)
        updateSwitchState()
    }

    private enum class TempSheetMode { DISABLE, ENABLE }

    private fun showTempToggleSheet(): Boolean {
        if (!AutomationModeStore.isButtonAllowed(this)) {
            Toast.makeText(
                this,
                getString(R.string.mode_blocked_button_action),
                Toast.LENGTH_SHORT
            ).show()
            return false
        }

        val enabledNow = SwitchModeStore.isEnabled(this)
        val tempDisableRemaining = SwitchModeStore.getTemporaryRemainingMillis(this)
        val tempEnableRemaining = SwitchModeStore.getTemporaryEnableRemainingMillis(this)

        val mode = when {
            tempDisableRemaining > 0L -> TempSheetMode.DISABLE
            tempEnableRemaining > 0L -> TempSheetMode.ENABLE
            enabledNow -> TempSheetMode.DISABLE
            else -> TempSheetMode.ENABLE
        }

        // If NFC lock is active while enabled, temporary disable actions are locked.
        // We still open the sheet so users can access emergency actions from here.
        val lockedByNfc = (mode == TempSheetMode.DISABLE && isNfcLocked())

        val accent = AccentColor.getAccentColorInt(this)
        val tint = ColorStateList.valueOf(accent)

        val sheet = BottomSheetDialog(this)
        val parent = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_temp_toggle, parent, false)
        sheet.setContentView(view)

        val ivIcon = view.findViewById<ImageView>(R.id.ivIcon)
        val tvTitle = view.findViewById<TextView>(R.id.tvTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvSubtitle)
        val tvRemaining = view.findViewById<TextView>(R.id.tvRemaining)
        val tvNote = view.findViewById<TextView>(R.id.tvNote)
        val llOptions = view.findViewById<ViewGroup>(R.id.llOptions)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnClose)

        ivIcon.imageTintList = tint
        tvRemaining.setTextColor(accent)
        btnClose.setTextColor(accent)

        if (mode == TempSheetMode.DISABLE) {
            tvTitle.text = getString(R.string.nfc_action_temp_disable)
            tvSubtitle.text = if (lockedByNfc) {
                getString(R.string.dashboard_temp_sheet_sub_disable_locked)
            } else {
                getString(R.string.dashboard_temp_sheet_sub_disable)
            }
        } else {
            tvTitle.text = getString(R.string.nfc_action_temp_enable)
            tvSubtitle.text = getString(R.string.dashboard_temp_sheet_sub_enable)
        }

        tvNote.text = if (lockedByNfc) {
            getString(R.string.dashboard_temp_hint_locked_nfc)
        } else {
            getString(R.string.nfc_temp_hint_timer_behavior)
        }

        // Show remaining time if a timer is already running
        val hasActive = (tempDisableRemaining > 0L) || (tempEnableRemaining > 0L)
        if (hasActive) {
            val handler = Handler(Looper.getMainLooper())
            val tick = object : Runnable {
                override fun run() {
                    val rem = when {
                        tempDisableRemaining > 0L -> SwitchModeStore.getTemporaryRemainingMillis(this@MainActivity)
                        tempEnableRemaining > 0L -> SwitchModeStore.getTemporaryEnableRemainingMillis(this@MainActivity)
                        else -> 0L
                    }

                    if (rem > 0L) {
                        tvRemaining.visibility = View.VISIBLE
                        tvRemaining.text = getString(
                            R.string.dashboard_temp_remaining_fmt,
                            formatRemainingShort(rem)
                        )
                        handler.postDelayed(this, 1000L)
                    } else {
                        tvRemaining.visibility = View.GONE
                    }
                }
            }

            sheet.setOnDismissListener {
                handler.removeCallbacksAndMessages(null)
            }
            tick.run()
        } else {
            tvRemaining.visibility = View.GONE
        }

        fun addOption(label: String, onClick: () -> Unit) {
            val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                isAllCaps = false
                setTextColor(accent)
                strokeColor = tint
                rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x22))
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    val m = (8 * resources.displayMetrics.density).toInt()
                    topMargin = m
                }
            }
            b.setOnClickListener { onClick() }
            llOptions.addView(b)
        }

        if (!lockedByNfc) {
            // Presets
            val presets = listOf(5, 15, 30, 60)
            for (m in presets) {
                val label = resources.getQuantityString(R.plurals.minutes_format, m, m)
                addOption(label) {
                    val ms = m * 60_000L
                    if (mode == TempSheetMode.DISABLE) {
                        SwitchModeStore.setTemporarilyDisabled(this, ms)
                    } else {
                        SwitchModeStore.setTemporarilyEnabled(this, ms)
                    }
                    sheet.dismiss()
                    updateSwitchState()
                }
            }

            // Custom
            addOption(getString(R.string.custom_minutes)) {
                sheet.dismiss()
                showCustomTempMinutesInput { minutes ->
                    val ms = minutes * 60_000L
                    if (mode == TempSheetMode.DISABLE) {
                        SwitchModeStore.setTemporarilyDisabled(this, ms)
                    } else {
                        SwitchModeStore.setTemporarilyEnabled(this, ms)
                    }
                    updateSwitchState()
                }
            }
        }

        // Cancel active timer
        if (hasActive && !(lockedByNfc && tempDisableRemaining > 0L)) {
            addOption(getString(R.string.dashboard_temp_sheet_cancel_timer)) {
                if (tempDisableRemaining > 0L) {
                    SwitchModeStore.cancelTemporaryDisable(this)
                } else if (tempEnableRemaining > 0L) {
                    SwitchModeStore.cancelTemporaryEnable(this)
                }
                sheet.dismiss()
                updateSwitchState()
            }
        }

        btnClose.setOnClickListener { sheet.dismiss() }
        sheet.show()
        return true
    }

    private fun showCustomTempMinutesInput(onPicked: (Int) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.minutes_hint)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad/2, pad, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.custom_minutes_title)
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val m = input.text?.toString()?.trim()?.toIntOrNull()
                if (m == null || m < 1 || m > 120) {
                    Toast.makeText(this, R.string.nfc_time_custom_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onPicked(m)
            }
            .showAccented()
    }

    private fun openAppPickerIfUnlocked() {
        if (!ensureCanOpenAppPicker(showToast = true)) return
        startActivity(Intent(this, AppPickerActivity::class.java))
    }

    private fun openProfilesIfUnlocked() {
        if (!ensureCanSwitchProfiles(showToast = true)) return
        startActivity(Intent(this, ManageProfilesActivity::class.java))
    }

    private fun openAddProfileIfUnlocked() {
        if (!ensureCanSwitchProfiles(showToast = true)) return
        showAddProfileDialog()
    }

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

    /**
     * Formats remaining milliseconds as a compact time (m:ss or h:mm:ss).
     */
    private fun formatRemainingShort(ms: Long): String {
        val totalSec = (ms/1000L).coerceAtLeast(0L)
        val h = totalSec/3600L
        val m = (totalSec % 3600L)/60L
        val s = totalSec % 60L
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", m, s)
        }
    }

    private fun updateSetupCard() {
        val missing = mutableListOf<String>()

        // required for blocking
        val accessibilityOk = BlockingRuntime.isAccessibilityActive(this)
        if (!accessibilityOk) {
            missing.add(getString(R.string.permissions_accessibility_title))
        }

        // allow notifications (optional, but recommended for tips + status)
        val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val postNotifOk = hasPostNotificationsPermission()
        if (!notifEnabled || !postNotifOk) {
            missing.add(getString(R.string.permissions_notifications_title))
        }

        // battery optimization (highly recommended – otherwise OEMs may kill the app/service)
        val batteryOk = BatteryOptimizationCompat.isEffectivelyOk(this)
        if (!batteryOk) {
            missing.add(getString(R.string.permissions_battery_title))
        }

        val show = missing.isNotEmpty()

        // Smooth reveal/hide (feels less "jumpy")
        if (show && !cardSetup.isVisible) {
            cardSetup.alpha = 0f
            cardSetup.isVisible = true
            cardSetup.animate().alpha(1f).setDuration(180).start()
        } else if (!show && cardSetup.isVisible) {
            cardSetup.animate().alpha(0f).setDuration(150).withEndAction {
                cardSetup.isVisible = false
                cardSetup.alpha = 1f
            }.start()
        }

        if (show) {
            val missingText = resources.getQuantityString(
                R.plurals.dashboard_badge_missing,
                missing.size,
                missing.size
            )
            tvSetupTitle.text = missingText
            tvSetupTitle.setTextColor(
                MaterialColors.getColor(tvSetupTitle, androidx.appcompat.R.attr.colorPrimary)
            )
            tvSetupBadge.text = missingText
            tvSetupBadge.isVisible = false
            tvSetupSubtitle.text = resources.getQuantityString(
                R.plurals.dashboard_setup_subtitle_missing,
                missing.size
            )
            val bulletList = missing.joinToString(separator = "\n") { "• $it" }
            tvSetupDesc.text = bulletList
        }

        updateQuickActionBadges(missing.size)
    }

    private fun updateQuickActionBadges(missingSetupCount: Int) {
        // Quick-action permission badge was removed when the tile became a Usage shortcut.
        if (badgePermissions.isVisible) {
            badgePermissions.isVisible = false
            badgePermissions.alpha = 1f
        }
    }

    private fun updateNextScheduleCard() {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val show = sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_NEXT_SCHEDULE, false)
        if (show) {
            if (!cardNextSchedule.isVisible) {
                cardNextSchedule.showFade()
            }
        } else {
            if (cardNextSchedule.isVisible) {
                cardNextSchedule.hideFade()
            }
            return
        }

        if (!AutomationModeStore.isScheduleAllowed(this)) {
            tvNextScheduleValue.text = getString(R.string.schedules_next_inactive_control_mode)
            return
        }

        val nextMillis = SchedulePlanner.getNextBoundaryMillis(this)
        if (nextMillis <= 0L) {
            tvNextScheduleValue.text = getString(R.string.schedules_next_none)
        } else {
            val text = TimeFormatPrefs.formatMinutesOfDay(this, ((nextMillis / 60000L) % (24 * 60)).toInt())
            tvNextScheduleValue.text = getString(R.string.schedules_next_at, text)
        }
    }

    private fun updateQuickActionsVisibility() {
        val show = areQuickActionsEnabled()
        val expanded = areQuickActionsExpanded()
        val enabledCount = getVisibleQuickActionsCount()

        tvQuickActionsTitle.text = if (expanded || enabledCount <= 0) {
            getString(R.string.dashboard_quick_actions)
        } else {
            resources.getQuantityString(R.plurals.dashboard_quick_actions_count, enabledCount, enabledCount)
        }

        if (show) {
            rowQuickActionsHeader.showFade()
            tvQuickActionsTitle.showFade()
            ivQuickActionsEdit.showFade()
            ivQuickActionsChevron.showFade()
            if (expanded) {
                gridQuickActions.showFade()
            } else {
                gridQuickActions.hideFade()
            }
        } else {
            gridQuickActions.hideFade()
            ivQuickActionsChevron.hideFade()
            ivQuickActionsEdit.hideFade()
            tvQuickActionsTitle.hideFade()
            rowQuickActionsHeader.hideFade()
        }

        ivQuickActionsChevron.animate().cancel()
        ivQuickActionsChevron.rotation = if (expanded) 180f else 0f
        ivQuickActionsChevron.contentDescription = getString(
            if (expanded) R.string.dashboard_quick_actions_collapse else R.string.dashboard_quick_actions_expand
        )

        syncScanQuickActions()
        invalidateOptionsMenu()
    }

    private fun areQuickActionsEnabled(): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        return sp.getBoolean(ToggleOptionsActivity.KEY_SHOW_QUICK_ACTIONS, true)
    }

    private fun isProtectionActivelyEnforced(): Boolean {
        return SwitchModeStore.isEnabled(this) && !EmergencyBypassStore.isActive(this)
    }

    private fun openBlockedNotificationsIfAllowed() {
        if (isProtectionActivelyEnforced()) {
            EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_blocked_notifications)
            return
        }
        startActivity(Intent(this, BlockedInboxActivity::class.java))
    }

    private fun areQuickActionsExpanded(): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        return sp.getBoolean(KEY_QUICK_ACTIONS_EXPANDED, true)
    }

    private fun isQuickActionTileEnabled(key: String, defaultValue: Boolean = true): Boolean {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        return sp.getBoolean(key, defaultValue)
    }

    private fun getVisibleQuickActionsCount(): Int {
        var count = 0
        if (isQuickActionTileEnabled(KEY_QA_APPS)) count++
        if (isQuickActionTileEnabled(KEY_QA_PROFILES)) count++
        if (isQuickActionTileEnabled(KEY_QA_WEBSITES)) count++
        if (isQuickActionTileEnabled(KEY_QA_INAPP)) count++
        if (isQuickActionTileEnabled(KEY_QA_USAGE)) count++
        if (isQuickActionTileEnabled(KEY_QA_NFC_WRITE, defaultValue = false)) count++
        if (isQuickActionTileEnabled(KEY_QA_BLOCKED_NOTIFICATIONS, defaultValue = false)) count++
        if (isQuickActionTileEnabled(KEY_QA_QR) && AutomationModeStore.isQrAllowed(this)) count++
        if (isQuickActionTileEnabled(KEY_QA_BARCODE) && AutomationModeStore.isBarcodeAllowed(this)) count++
        return count
    }

    private fun setQuickActionTileEnabled(key: String, enabled: Boolean) {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        sp.edit { putBoolean(key, enabled) }
    }

    private fun showQuickActionsCustomizeDialog() {
        val items = buildList {
            add(Triple(KEY_QA_APPS, getString(R.string.dashboard_tile_apps), isQuickActionTileEnabled(KEY_QA_APPS)))
            add(Triple(KEY_QA_PROFILES, getString(R.string.dashboard_tile_profiles), isQuickActionTileEnabled(KEY_QA_PROFILES)))
            add(Triple(KEY_QA_WEBSITES, getString(R.string.dashboard_tile_blocked_websites), isQuickActionTileEnabled(KEY_QA_WEBSITES)))
            add(Triple(KEY_QA_INAPP, getString(R.string.dashboard_tile_in_app), isQuickActionTileEnabled(KEY_QA_INAPP)))
            add(Triple(KEY_QA_USAGE, getString(R.string.dashboard_tile_usage), isQuickActionTileEnabled(KEY_QA_USAGE)))
            add(Triple(KEY_QA_NFC_WRITE, getString(R.string.dashboard_tile_write_nfc), isQuickActionTileEnabled(KEY_QA_NFC_WRITE, defaultValue = false)))
            add(Triple(KEY_QA_BLOCKED_NOTIFICATIONS, getString(R.string.dashboard_tile_blocked_notifications), isQuickActionTileEnabled(KEY_QA_BLOCKED_NOTIFICATIONS, defaultValue = false)))
            if (AutomationModeStore.isQrAllowed(this@MainActivity)) {
                add(Triple(KEY_QA_QR, getString(R.string.dashboard_tile_qr), isQuickActionTileEnabled(KEY_QA_QR)))
            }
            if (AutomationModeStore.isBarcodeAllowed(this@MainActivity)) {
                add(Triple(KEY_QA_BARCODE, getString(R.string.dashboard_tile_barcode), isQuickActionTileEnabled(KEY_QA_BARCODE)))
            }
        }

        val accent = AccentColor.getAccentColorInt(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val boxes = mutableListOf<Pair<String, MaterialCheckBox>>()
        items.forEach { (key, label, checked) ->
            val box = MaterialCheckBox(this).apply {
                text = label
                isChecked = checked
                buttonTintList = ColorStateList.valueOf(accent)
                setUseMaterialThemeColors(false)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            container.addView(box)
            boxes += key to box
        }

        val scroll = android.widget.ScrollView(this).apply { addView(container) }

        Dialogs.builder(this)
            .setTitle(R.string.dashboard_quick_actions_customize)
            .setView(scroll)
            .setPositiveButton(R.string.ok) { _, _ ->
                boxes.forEach { (key, box) -> setQuickActionTileEnabled(key, box.isChecked) }
                syncScanQuickActions()
                updateQuickActionsVisibility()
            }
            .setNegativeButton(R.string.cancel, null)
            .showAccented()
    }

    private fun toggleQuickActionsExpanded() {
        if (!areQuickActionsEnabled()) return
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val expanded = !areQuickActionsExpanded()
        sp.edit { putBoolean(KEY_QUICK_ACTIONS_EXPANDED, expanded) }
        ivQuickActionsChevron.animate().rotation(if (expanded) 180f else 0f).setDuration(180L).start()
        if (expanded) {
            gridQuickActions.showFade()
        } else {
            gridQuickActions.hideFade()
        }
        tvQuickActionsTitle.text = if (expanded || getVisibleQuickActionsCount() <= 0) {
            getString(R.string.dashboard_quick_actions)
        } else {
            resources.getQuantityString(R.plurals.dashboard_quick_actions_count, getVisibleQuickActionsCount(), getVisibleQuickActionsCount())
        }
        ivQuickActionsChevron.contentDescription = getString(
            if (expanded) R.string.dashboard_quick_actions_collapse else R.string.dashboard_quick_actions_expand
        )
    }

    private fun syncScanQuickActions() {
        if (!::tileQr.isInitialized) return

        val appsVisible = isQuickActionTileEnabled(KEY_QA_APPS)
        val profilesVisible = isQuickActionTileEnabled(KEY_QA_PROFILES)
        val websitesVisible = isQuickActionTileEnabled(KEY_QA_WEBSITES)
        val inAppVisible = isQuickActionTileEnabled(KEY_QA_INAPP)
        val usageVisible = isQuickActionTileEnabled(KEY_QA_USAGE)
        val nfcWriteVisible = isQuickActionTileEnabled(KEY_QA_NFC_WRITE, defaultValue = false)
        val blockedNotificationsVisible = isQuickActionTileEnabled(KEY_QA_BLOCKED_NOTIFICATIONS, defaultValue = false)
        val qrVisible = isQuickActionTileEnabled(KEY_QA_QR) && AutomationModeStore.isQrAllowed(this)
        val barcodeVisible = isQuickActionTileEnabled(KEY_QA_BARCODE) && AutomationModeStore.isBarcodeAllowed(this)

        val orderedTiles = listOf(
            tileManageApps to appsVisible,
            tileWriteNfc to websitesVisible,
            tileToggleOptions to inAppVisible,
            tileProfiles to profilesVisible,
            tilePermissions to usageVisible,
            tileNfcWrite to nfcWriteVisible,
            tileBlockedNotifications to blockedNotificationsVisible,
            tileQr to qrVisible,
            tileBarcode to barcodeVisible,
        )

        listOf(tileManageApps, tileProfiles, tileWriteNfc, tileToggleOptions, tilePermissions, tileNfcWrite, tileBlockedNotifications, tileQr, tileBarcode).forEach { tile ->
            (tile.parent as? LinearLayout)?.removeView(tile)
            tile.visibility = View.GONE
        }

        listOf(rowManageShortcuts, rowUtilityShortcuts, rowToolsShortcuts, rowScanShortcuts).forEach { row ->
            row.removeAllViews()
            row.visibility = View.GONE
        }

        val visibleTiles = orderedTiles.filter { it.second }.map { it.first }
        val rows = listOf(rowManageShortcuts, rowUtilityShortcuts, rowToolsShortcuts, rowScanShortcuts)
        visibleTiles.chunked(2).forEachIndexed { index, chunk ->
            val row = rows.getOrNull(index) ?: return@forEachIndexed
            row.visibility = View.VISIBLE
            chunk.forEachIndexed { tileIndex, tile ->
                tile.visibility = View.VISIBLE
                tile.layoutParams = quickActionTileLayoutParams(single = chunk.size == 1)
                row.addView(tile)
            }
        }
    }

    private fun quickActionTileLayoutParams(single: Boolean): LinearLayout.LayoutParams {
        val density = resources.displayMetrics.density
        val margin = (6 * density).toInt()
        val tileHeight = (164 * density).toInt()
        return if (single) {
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                tileHeight
            ).apply {
                setMargins(margin, margin, margin, margin)
            }
        } else {
            LinearLayout.LayoutParams(0, tileHeight, 1f).apply {
                setMargins(margin, margin, margin, margin)
            }
        }
    }

    private fun activeControlLabels(): String {
        val labels = mutableListOf<String>()
        if (AutomationModeStore.isButtonAllowed(this)) labels += getString(R.string.control_manual)
        if (AutomationModeStore.isNfcAllowed(this)) labels += getString(R.string.control_nfc)
        if (AutomationModeStore.isScheduleAllowed(this)) labels += getString(R.string.control_schedules)
        if (AutomationModeStore.isQrAllowed(this)) labels += getString(R.string.control_qr)
        if (AutomationModeStore.isBarcodeAllowed(this)) labels += getString(R.string.control_barcode)
        return labels.joinToString(", ")
    }

    private fun updateBlockedNowCard() {
        // Dashboard section has been removed (card stays GONE). Keep logic as a no-op.
        if (!cardBlockedNow.isVisible) return

        val enabled = SwitchModeStore.isEnabled(this)
        val emergency = EmergencyBypassStore.isActive(this)

        fun showEmpty(@StringRes msg: Int, @DrawableRes icon: Int) {
            tvBlockedNowEmpty.text = getString(msg)
            ivBlockedNowEmptyIcon.setImageResource(icon)
            layoutBlockedNowEmpty.showFade()
            rvBlockedNow.hideFade()
            tvBlockedNowMore.hideFade()
        }

        // Default: nothing blocked (or user explicitly bypasses)
        if (!enabled) {
            showEmpty(R.string.dashboard_blocked_now_off, R.drawable.lock_open_24)
            return
        }

        if (emergency) {
            showEmpty(R.string.dashboard_blocked_now_emergency, R.drawable.info_24)
            return
        }

        val profile = ProfileStore.getCurrent(this)
        if (profile.isNullOrBlank()) {
            showEmpty(R.string.dashboard_blocked_now_all_allowed, R.drawable.toggle_on_24)
            return
        }

        val blocked = cachedBlockedApps
        if (blocked.isEmpty()) {
            showEmpty(R.string.dashboard_blocked_now_all_allowed, R.drawable.toggle_on_24)
            return
        }

        // Build list:
        // 1) Limit reached apps (most "alive")
        // 2) Hard-block apps (no limit set -> always blocked)
        val limitReached = mutableListOf<BlockedNowItem>()
        val hardBlocked = mutableListOf<BlockedNowItem>()

        for (app in blocked) {
            val limitMin = UsageLimitStore.getLimitMinutes(this, profile, app.pkg)
            val sessionLimitMin = SessionLimitStore.getLimitMinutes(this, profile, app.pkg)
            val attemptLimit = AttemptLimitStore.getLimitAttempts(this, profile, app.pkg)
            val opensAtOrOver = attemptLimit > 0 && OpenCountStore.getToday(this, profile, app.pkg) >= attemptLimit
            val hasAnyLimit = limitMin > 0 || sessionLimitMin > 0 || attemptLimit > 0

            if (limitMin > 0) {
                if (LimitReachedStore.isReachedToday(this, app.pkg)) {
                    limitReached.add(
                        BlockedNowItem(
                            label = app.label,
                            pkg = app.pkg,
                            icon = app.icon,
                            reason = getString(R.string.dashboard_blocked_now_reason_limit),
                            isAvailable = app.isAvailable
                        )
                    )
                }
            } else if (attemptLimit > 0) {
                if (opensAtOrOver) {
                    limitReached.add(
                        BlockedNowItem(
                            label = app.label,
                            pkg = app.pkg,
                            icon = app.icon,
                            reason = getString(R.string.dashboard_blocked_now_reason_attempts),
                            isAvailable = app.isAvailable
                        )
                    )
                }
            } else if (!hasAnyLimit) {
                hardBlocked.add(
                    BlockedNowItem(
                        label = app.label,
                        pkg = app.pkg,
                        icon = app.icon,
                        reason = getString(R.string.dashboard_blocked_now_reason_profile),
                        isAvailable = app.isAvailable
                    )
                )
            }
        }

        val all = (limitReached + hardBlocked)
            .sortedBy { it.label.lowercase() }

        if (all.isEmpty()) {
            showEmpty(R.string.dashboard_blocked_now_all_allowed, R.drawable.toggle_on_24)
            return
        }

        // Only show the top 3, plus a small "+X more" hint (feels cleaner on the dashboard)
        val display = all.take(3)
        val moreCount = (all.size - display.size).coerceAtLeast(0)

        layoutBlockedNowEmpty.hideFade()
        rvBlockedNow.showFade()
        blockedNowAdapter.submitList(display)

        if (moreCount > 0) {
            tvBlockedNowMore.text = resources.getQuantityString(R.plurals.dashboard_blocked_now_more, moreCount, moreCount)
            tvBlockedNowMore.showFade()
        } else {
            tvBlockedNowMore.hideFade()
        }
    }

    /**
     * Updates:
     * - Switchly enabled/disabled header (with temp disable/enable + emergency info)
     * - icon + primary enable/disable button
     * - active profile label
     * - NFC lock UI
     */
    private fun updateSwitchState() {
        SwitchModeStore.finishTemporaryDisableIfExpired(this)
        SwitchModeStore.finishTemporaryEnableIfExpired(this)

        val enabled = SwitchModeStore.isEnabled(this)
        val baseEnabled = SwitchModeStore.isBaseEnabled(this)

        // Subtle icon animation when state changes (only on change, not every refresh)
        val prev = lastEnabledUi
        if (prev != null && prev != enabled) {
            ivStatusIcon.animate().cancel()
            ivStatusIcon.scaleX = 0.9f
            ivStatusIcon.scaleY = 0.9f
            ivStatusIcon.alpha = 0.6f
            ivStatusIcon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(180)
                .start()
        }
        lastEnabledUi = enabled

        val tempDisableRemaining = SwitchModeStore.getTemporaryRemainingMillis(this)
        val tempEnableRemaining = SwitchModeStore.getTemporaryEnableRemainingMillis(this)

        val emergencyActive = EmergencyBypassStore.isActive(this)
        val emergencyPaused = EmergencyBypassStore.isPaused(this)
        val emergencyRemMinutes = EmergencyBypassStore.minutesRemaining(this)

        // Icon + button
        ivStatusIcon.setImageResource(if (enabled) R.drawable.lock_24 else R.drawable.lock_open_24)
        btnToggle.text = if (enabled) getString(R.string.dashboard_toggle_disable) else getString(R.string.dashboard_toggle_enable)

        val locked = enabled && SwitchModeStore.isNfcRequiredForDisable(this)
        tvNfcLockedHint.visibility = if (locked) View.VISIBLE else View.GONE
        btnToggle.isEnabled = !locked

        // Apply lock to profile/app editing controls
        applyLockedUi(locked)

        // Profile label
        val current = ProfileStore.getCurrent(this)
        tvActiveProfile.text = if (current.isNullOrBlank()) {
            getString(R.string.dashboard_active_profile_unknown)
        } else {
            getString(R.string.dashboard_active_profile_fmt, current)
        }

        // Status line
        val stateWord = if (enabled) getString(R.string.state_enabled) else getString(R.string.state_disabled)
        val sb = StringBuilder()
        sb.append(getString(R.string.switch_mode_label, stateWord))

        // Temp-mode status now reuses the hint row below the main button instead of showing a separate chip.
        chipTemp.visibility = View.GONE

        when {
            emergencyActive -> {
                sb.append("  •  ")
                sb.append(getString(R.string.emergency_enabled_inline, emergencyRemMinutes))
            }
            emergencyPaused -> {
                sb.append("  •  ")
                sb.append(getString(R.string.emergency_paused_inline, emergencyRemMinutes))
            }
        }

        val full = sb.toString()
        val span = SpannableString(full)
        val start = full.indexOf(stateWord)
        if (start >= 0) {
            val color =
                if (enabled) AccentColor.getAccentColorInt(this)
                else ContextCompat.getColor(this, R.color.status_error)

            span.setSpan(
                ForegroundColorSpan(color),
                start,
                start + stateWord.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        tvSwitchMode.text = span

        // Keep quick-entry text in sync with current state
        updateEmergencyHintVisibility()
        updateTempHintVisibility()

        // Live section
        updateBlockedNowCard()

        // Refresh "Blocked now" chip visibility in the blocked-apps list immediately when
        // state changes (previously it only updated after re-opening the app).
        val chipKey = "$enabled|$emergencyActive"
        if (lastBlockedChipKey != chipKey) {
            lastBlockedChipKey = chipKey
            // Re-bind rows so chips update; keep it lightweight.
            notifyBlockedChipsChanged()
        }

        val editEnabled = ensureCanRemoveBlockedApp(showToast = false)
        if (lastBlockedEditEnabled != editEnabled) {
            lastBlockedEditEnabled = editEnabled
            notifyBlockedEditStateChanged()
        }

        // Cleanup stale timers
        if (tempDisableRemaining == 0L && enabled && baseEnabled) {
            SwitchModeStore.clearTemporary(this)
        }
        if (tempEnableRemaining == 0L && !baseEnabled) {
            SwitchModeStore.clearTemporaryEnable(this)
        }
    }

    private fun uiHintsPrefs() = getSharedPreferences(PREFS_UI_HINTS, MODE_PRIVATE)

    private fun hasDiscoveredTempMode(): Boolean {
        return uiHintsPrefs().getBoolean(KEY_TEMP_MODE_DISCOVERED, false)
    }

    private fun updateTempHintVisibility() {
        tvTempHint.isVisible = true

        val lockedByNfc = isNfcLocked()
        val tempDisableRemaining = SwitchModeStore.getTemporaryRemainingMillis(this)
        val tempEnableRemaining = SwitchModeStore.getTemporaryEnableRemainingMillis(this)

        tvTempHint.text = when {
            tempDisableRemaining > 0L -> getString(
                R.string.dashboard_temp_status_disabled,
                formatRemainingShort(tempDisableRemaining)
            )
            tempEnableRemaining > 0L -> getString(
                R.string.dashboard_temp_status_enabled,
                formatRemainingShort(tempEnableRemaining)
            )
            lockedByNfc -> getString(R.string.dashboard_temp_hint_locked_nfc)
            else -> getString(R.string.dashboard_temp_hint)
        }

        tvTempHint.alpha = when {
            tempDisableRemaining > 0L || tempEnableRemaining > 0L -> 0.96f
            lockedByNfc -> 0.95f
            else -> 0.88f
        }
    }

    private fun updateEmergencyHintVisibility() {
        tvEmergencyHint.isVisible = true

        val featureEnabled = EmergencyBypassStore.isFeatureEnabled(this)
        val active = EmergencyBypassStore.isActive(this)
        val paused = EmergencyBypassStore.isPaused(this)
        val usedToday = EmergencyBypassStore.hasUsedToday(this)
        val rem = EmergencyBypassStore.minutesRemaining(this)

        tvEmergencyHint.text = when {
            active -> getString(R.string.dashboard_emergency_hint_active, rem)
            paused -> getString(R.string.dashboard_emergency_hint_paused, rem)
            !featureEnabled -> getString(R.string.dashboard_emergency_hint_disabled)
            usedToday -> getString(R.string.dashboard_emergency_hint_used_today)
            else -> getString(R.string.dashboard_emergency_hint_ready)
        }
    }

    private fun showEmergencyQuickSheet() {
        val featureEnabled = EmergencyBypassStore.isFeatureEnabled(this)
        if (!featureEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pref_emergency_title)
                .setMessage(R.string.emergency_disabled_message_controls)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.emergency_open_controls_action) { _, _ ->
                    startActivity(Intent(this, ToggleOptionsActivity::class.java))
                }
                .showAccented()
            return
        }

        val active = EmergencyBypassStore.isActive(this)
        val paused = EmergencyBypassStore.isPaused(this)
        val usedToday = EmergencyBypassStore.hasUsedToday(this)
        val remaining = EmergencyBypassStore.minutesRemaining(this)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        fun addAction(label: String, action: () -> Unit) {
            labels += label
            actions += action
        }

        if (active) {
            addAction(getString(R.string.emergency_action_pause)) {
                if (EmergencyBypassStore.pause(this)) {
                    SwitchModeStore.clearTemporary(this)
                    Toast.makeText(this, getString(R.string.emergency_paused_toast), Toast.LENGTH_SHORT).show()
                    BlockingRuntime.ensureRunning(this)
                    updateSwitchState()
                }
            }
            addAction(getString(R.string.emergency_action_end)) {
                EmergencyBypassStore.cancel(this)
                SwitchModeStore.clearTemporary(this)
                Toast.makeText(this, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                BlockingRuntime.ensureRunning(this)
                updateSwitchState()
            }
        } else if (paused) {
            addAction(getString(R.string.emergency_action_resume)) {
                if (EmergencyBypassStore.resume(this)) {
                    val remainingMinutes = EmergencyBypassStore.minutesRemaining(this).coerceAtLeast(1)
                    SwitchModeStore.setTemporarilyDisabled(this, remainingMinutes * 60_000L)
                    Toast.makeText(this, getString(R.string.emergency_resumed_toast), Toast.LENGTH_SHORT).show()
                    BlockingRuntime.ensureRunning(this)
                    updateSwitchState()
                }
            }
            addAction(getString(R.string.emergency_action_end)) {
                EmergencyBypassStore.cancel(this)
                SwitchModeStore.clearTemporary(this)
                Toast.makeText(this, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                BlockingRuntime.ensureRunning(this)
                updateSwitchState()
            }
        } else if (!usedToday) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.pref_emergency_title))
                .setMessage(getString(R.string.emergency_action_start_15))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val ok = EmergencyBypassStore.enableIfAllowed(this, 15)
                    if (ok) {
                        SwitchModeStore.setTemporarilyDisabled(this, 15 * 60_000L)
                        Toast.makeText(this, getString(R.string.emergency_enabled_toast, 15), Toast.LENGTH_SHORT).show()
                        BlockingRuntime.ensureRunning(this)
                        updateSwitchState()
                    } else {
                        Toast.makeText(this, getString(R.string.emergency_used_today), Toast.LENGTH_SHORT).show()
                    }
                }
                .showAccented()
            return
        }

        val title = when {
            active -> getString(R.string.emergency_manage_title_active, remaining)
            paused -> getString(R.string.emergency_manage_title_paused, remaining)
            else -> getString(R.string.pref_emergency_title)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setNegativeButton(R.string.cancel, null)

        if (labels.isNotEmpty()) {
            builder.setItems(labels.toTypedArray()) { _, which ->
                runCatching { actions[which].invoke() }
                updateEmergencyHintVisibility()
            }
        }

        if (!active && !paused && usedToday) {
            builder.setMessage(R.string.emergency_used_today)
        }

        builder.showAccented()
    }

    private fun markTempModeDiscovered(showSnack: Boolean) {
        if (!hasDiscoveredTempMode()) {
            uiHintsPrefs().edit { putBoolean(KEY_TEMP_MODE_DISCOVERED, true) }
            updateTempHintVisibility()
        }

        if (showSnack) {
            val snack = Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.dashboard_temp_discovery_done),
                Snackbar.LENGTH_SHORT
            )
            runCatching {
                val anchor = findViewById<View>(R.id.bottomNav)
                if (anchor != null) snack.setAnchorView(anchor)
            }
            snack.show()
        }
    }

    private fun trackPrimaryToggleTapForTempNudge() {
        if (hasDiscoveredTempMode()) return

        val prefs = uiHintsPrefs()
        val taps = prefs.getInt(KEY_PRIMARY_TOGGLE_TAP_COUNT, 0) + 1
        prefs.edit { putInt(KEY_PRIMARY_TOGGLE_TAP_COUNT, taps) }

        if (taps == 5 || taps == 12) {
            val snack = Snackbar.make(
                findViewById(android.R.id.content),
                getString(R.string.dashboard_temp_nudge),
                Snackbar.LENGTH_SHORT
            )
            runCatching {
                val anchor = findViewById<View>(R.id.bottomNav)
                if (anchor != null) snack.setAnchorView(anchor)
            }
            snack.show()
        }
    }

    private fun applyLockedUi(locked: Boolean) {
        val profileLocked = locked || isProfileSwitchLockedWhileEnabled()
        val appPickingLocked = locked || isAppPickingLockedWhileEnabled()
        val websitesLocked = EditingLockGuard.isLocked(this)
        val inAppLocked = SwitchModeStore.isBaseEnabled(this) || EditingLockGuard.isLocked(this)
        val nfcWriteLocked = isNfcTagWritingLocked()
        val blockedNotificationsLocked = isProtectionActivelyEnforced()

        // Keep locked profile controls clickable so they can show the explanatory dialog instead of silently disabling the dropdown/end icon.
        profileDropdown.isEnabled = true
        rowActiveProfile.isEnabled = true
        btnPickApps.isEnabled = !appPickingLocked

        val profileAlpha = if (profileLocked) 0.5f else 1f
        val appPickingAlpha = if (appPickingLocked) 0.5f else 1f

        // Quick actions (Permissions stays available so users can fix setup)
        // Keep locked tiles clickable so they can show the explanatory lock dialog.
        tileManageApps.isEnabled = true
        tileProfiles.isEnabled = true
        tileWriteNfc.isEnabled = true
        tileToggleOptions.isEnabled = true
        tilePermissions.isEnabled = true
        tileNfcWrite.isEnabled = true
        tileBlockedNotifications.isEnabled = true
        tileQr.isEnabled = true
        tileBarcode.isEnabled = true

        tileManageApps.alpha = appPickingAlpha
        tileProfiles.alpha = profileAlpha
        tileWriteNfc.alpha = if (websitesLocked) lockedTileAlpha() else 1f
        tileToggleOptions.alpha = if (inAppLocked) lockedTileAlpha() else 1f
        tilePermissions.alpha = 1f
        tileNfcWrite.alpha = if (nfcWriteLocked) lockedTileAlpha() else 1f
        tileBlockedNotifications.alpha = if (blockedNotificationsLocked) lockedTileAlpha() else 1f
        tileQr.alpha = 1f
        tileBarcode.alpha = 1f
        rowActiveProfile.alpha = profileAlpha
        btnPickApps.alpha = appPickingAlpha
    }

    private fun lockedTileAlpha(): Float = LockedUi.cardAlpha(this)

    private fun isNfcTagWritingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this) &&
            !AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(this)
    }

    private fun refreshProfilesUi() {
        val profiles: List<String> = ProfileStore.getProfiles(this).toList().sorted()
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, profiles)
        profileDropdown.setAdapter(adapter)

        // Keep dropdown width aligned with the field (prevents full-screen-wide popup on some OEMs).
        profileDropdown.post {
            profileDropdown.dropDownWidth = profileDropdown.width
        }

        val current = ProfileStore.getCurrent(this)
        if (current.isNullOrEmpty() && profiles.isNotEmpty()) {
            ProfileStore.setCurrent(this, profiles.first())
            profileDropdown.setText(profiles.first(), false)
        } else {
            profileDropdown.setText(current ?: "", false)
        }

        profileDropdown.setOnItemClickListener { _, _, pos, _ ->
            val selected = adapter.getItem(pos) ?: return@setOnItemClickListener

            if (!ensureCanSwitchProfiles(showToast = true)) {
                val cur = ProfileStore.getCurrent(this)
                profileDropdown.setText(cur ?: "", false)
                return@setOnItemClickListener
            }

            ProfileStore.setCurrent(this, selected)
            refreshBlockedList()
            updateSwitchState()
        }
    }

    private fun snackRoot(): View {
        return findViewById(android.R.id.content) ?: window.decorView
    }

    private fun showAddProfileDialog() {
        // Safety guard: even if called directly elsewhere, don't allow when switching is locked.
        if (!ensureCanSwitchProfiles(showToast = true)) return

        val sheet = BottomSheetDialog(this)
        val parent = findViewById<ViewGroup>(android.R.id.content)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_add_profile, parent, false)
        sheet.setContentView(view)

        val icon = view.findViewById<ImageView>(R.id.icon)
        val til = view.findViewById<TextInputLayout>(R.id.tilProfile)
        val et = view.findViewById<TextInputEditText>(R.id.etProfile)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        val btnCreate = view.findViewById<MaterialButton>(R.id.btnCreate)

        val accent = AccentColor.getAccentColorInt(this)
        val tint = AccentColor.getActiveColor(this)
        val onAccent = if (ColorUtils.calculateLuminance(accent) > 0.52) Color.BLACK else Color.WHITE

        icon?.imageTintList = tint
        til.setStartIconTintList(tint)
        btnCancel.backgroundTintList = tint
        btnCreate.backgroundTintList = tint
        btnCancel.setTextColor(onAccent)
        btnCreate.setTextColor(onAccent)

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
                updateSwitchState()
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

    private fun showBlockedAppQuickActions(item: AppDisplay) {
        if (!item.isAvailable) {
            if (!ensureCanRemoveBlockedApp(showToast = true)) return
            confirmRemoveBlockedApp(item)
            return
        }

        val options = arrayOf(
            getString(R.string.dashboard_blocked_app_action_edit_limits),
            getString(R.string.dashboard_blocked_app_action_remove)
        )

        AlertDialog.Builder(this)
            .setTitle(item.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showBlockedAppLimitActions(item)
                    1 -> confirmRemoveBlockedApp(item)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun showBlockedAppLimitActions(item: AppDisplay) {
        val options = arrayOf(
            getString(R.string.dashboard_blocked_app_action_time_limit),
            getString(R.string.dashboard_blocked_app_action_open_limit)
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.dashboard_blocked_app_limits_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> QuickLimitDialogs.showForApp(
                        activity = this,
                        pkg = item.pkg,
                        label = item.label,
                        startOnAttempts = false,
                        onChanged = { refreshBlockedList() }
                    )
                    1 -> QuickLimitDialogs.showForApp(
                        activity = this,
                        pkg = item.pkg,
                        label = item.label,
                        startOnAttempts = true,
                        onChanged = { refreshBlockedList() }
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .showAccented()
    }

    private fun confirmRemoveBlockedApp(item: AppDisplay) {
        if (!ensureCanRemoveBlockedApp(showToast = true)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.dashboard_blocked_app_remove_title)
            .setMessage(getString(R.string.dashboard_blocked_app_remove_message, item.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                removeBlockedApp(item)
            }
            .showAccented()
    }

    private fun removeBlockedApp(item: AppDisplay) {
        val profile = ProfileStore.getCurrent(this)
        if (profile.isNullOrBlank()) return

        val blocked = ProfileStore.getBlockedForProfile(this, profile).toMutableSet()
        blocked.remove(item.pkg)
        ProfileStore.setBlockedForProfile(this, profile, blocked)

        UsageLimitStore.setLimitMinutes(this, profile, item.pkg, 0)
        SessionLimitStore.setLimitMinutes(this, profile, item.pkg, 0)
        AttemptLimitStore.setLimitAttempts(this, profile, item.pkg, 0)
        OpenCountStore.setToday(this, profile, item.pkg, 0)
        LimitReachedStore.clearToday(this, item.pkg)

        BlockingRuntime.ensureRunning(this)
        refreshBlockedList()

        Snackbar.make(
            snackRoot(),
            getString(R.string.dashboard_blocked_app_removed, item.label),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun refreshBlockedList() {
        val current = ProfileStore.getCurrent(this)
        val pkgs: List<String> = loadBlockedPkgsFor(current)
        val appContext = applicationContext
        val requestSeq = ++blockedListRefreshSeq

        blockedListRefreshJob?.cancel()
        blockedListRefreshJob = lifecycleScope.launch {
            val items: List<AppDisplay> = withContext(Dispatchers.IO) {
                val ioContext = currentCoroutineContext()
                pkgs
                    .asSequence()
                    .mapNotNull { pkg ->
                        if (!ioContext.isActive) return@mapNotNull null
                        resolveAppDisplay(appContext, pkg)
                    }
                    .sortedBy { app -> app.label.lowercase() }
                    .toList()
            }

            if (requestSeq != blockedListRefreshSeq || !currentCoroutineContext().isActive) return@launch

            val isEmpty = items.isEmpty()
            if (isEmpty) {
                layoutBlockedAppsEmpty.showFade()
                rvBlocked.hideFade()
            } else {
                layoutBlockedAppsEmpty.hideFade()
                rvBlocked.showFade()
            }

            blockedAdapter.submitList(items) {
                // The managed-app rows include live status chips (e.g. "Limit reached") that are derived from runtime state rather than DiffUtil item content.
                // When the list contents themselves have not changed, returning to Home after a limit is hit would otherwise keep the old chip text until some unrelated state change forced a rebind.
                notifyBlockedChipsChanged()
            }

            cachedBlockedApps = items
            updateBlockedNowCard()
        }
    }

    private fun loadBlockedPkgsFor(profile: String?): List<String> {
        if (profile.isNullOrEmpty()) return emptyList()

        val sp = getSharedPreferences("switchly_prefs", MODE_PRIVATE)
        val key = "blocked_apps_$profile"

        // If the stored type is wrong, don't crash — drop the value.
        val explicitlyBlocked = try {
            sp.getStringSet(key, emptySet())?.toSet() ?: emptySet()
        } catch (_: ClassCastException) {
            sp.edit { remove(key) }
            emptySet()
        }

        // Limited apps are also managed by the profile and should appear together with the other selected apps, even if the stored blocked-app set was not updated.
        val limited = buildSet {
            addAll(UsageLimitStore.getAllLimitedPackages(this@MainActivity, profile))
            addAll(SessionLimitStore.getAllLimitedPackages(this@MainActivity, profile))
            addAll(AttemptLimitStore.getAllLimitedPackages(this@MainActivity, profile))
        }

        return (explicitlyBlocked + limited)
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun resolveAppDisplay(context: Context, pkg: String): AppDisplay {
        val pm = context.packageManager
        val fallbackIcon = ContextCompat.getDrawable(context, android.R.drawable.sym_def_app_icon)!!
        return try {
            val ai = pm.getApplicationInfo(pkg, 0)
            val label = runCatching { pm.getApplicationLabel(ai)?.toString() }.getOrNull() ?: pkg
            val icon = runCatching { pm.getApplicationIcon(pkg).toSafeListIcon(context) }.getOrDefault(fallbackIcon)
            AppDisplay(label, pkg, icon, isAvailable = true)
        } catch (_: PackageManager.NameNotFoundException) {
            AppDisplay(
                label = pkg,
                pkg = pkg,
                icon = fallbackIcon,
                isAvailable = false
            )
        }
    }

    private fun Drawable.toSafeListIcon(context: Context): Drawable {
        val size = (48f * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val oldBounds = copyBounds()
        setBounds(0, 0, size, size)
        draw(canvas)
        setBounds(oldBounds)
        return BitmapDrawable(context.resources, bitmap)
    }

    data class AppDisplay(
        val label: String,
        val pkg: String,
        val icon: Drawable,
        val isAvailable: Boolean
    )

    data class BlockedNowItem(
        val label: String,
        val pkg: String,
        val icon: Drawable,
        val reason: String,
        val isAvailable: Boolean
    )

    private class BlockedNowAdapter :
        ListAdapter<BlockedNowItem, BlockedNowAdapter.VH>(DIFF) {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val rowRoot: View = v.findViewById(R.id.rowRoot)
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val name: TextView = v.findViewById(R.id.appName)
            val pkg: TextView = v.findViewById(R.id.appPkg)
            val reason: TextView = v.findViewById(R.id.appReason)
            val unavailableChip: TextView = v.findViewById(R.id.tvUnavailableChip)
            val unavailableHint: TextView = v.findViewById(R.id.tvUnavailableHint)

            private fun dp(value: Float): Int =
                (value * itemView.resources.displayMetrics.density).toInt()

            fun bind(item: BlockedNowItem) {
                val ctx = itemView.context
                icon.setImageDrawable(item.icon)
                pkg.text = item.pkg

                if (item.isAvailable) {
                    name.text = item.label
                    reason.text = item.reason
                    reason.visibility = View.VISIBLE

                    rowRoot.background = null
                    unavailableChip.visibility = View.GONE
                    unavailableHint.visibility = View.GONE
                } else {
                    name.text = ctx.getString(R.string.unavailable_app_label)
                    reason.visibility = View.GONE

                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(12f).toFloat()
                        setColor(ContextCompat.getColor(ctx, R.color.unavailable_row_bg))
                        setStroke(dp(1f), ContextCompat.getColor(ctx, R.color.unavailable_row_stroke))
                    }
                    rowRoot.background = bg

                    val chipBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(999f).toFloat()
                        setColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_bg))
                    }
                    unavailableChip.background = chipBg
                    unavailableChip.setTextColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_text))
                    unavailableChip.visibility = View.VISIBLE
                    unavailableHint.visibility = View.GONE
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.row_blocked_now, parent, false)
            return VH(v)
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(getItem(position))
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<BlockedNowItem>() {
                override fun areItemsTheSame(oldItem: BlockedNowItem, newItem: BlockedNowItem): Boolean {
                    return oldItem.pkg == newItem.pkg && oldItem.reason == newItem.reason
                }

                override fun areContentsTheSame(oldItem: BlockedNowItem, newItem: BlockedNowItem): Boolean {
                    return oldItem.pkg == newItem.pkg &&
                        oldItem.label == newItem.label &&
                        oldItem.reason == newItem.reason &&
                        oldItem.isAvailable == newItem.isAvailable
                }
            }
        }
    }

    private class BlockedAppsAdapter(
        private val onAppClick: (AppDisplay) -> Unit,
        private val onEditClick: (AppDisplay) -> Unit,
        private val canEdit: () -> Boolean
    ) : ListAdapter<AppDisplay, BlockedAppsAdapter.VH>(DIFF) {

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cardRoot: MaterialCardView = v.findViewById(R.id.cardRoot)
            val icon: ImageView = v.findViewById(R.id.appIcon)
            val name: TextView = v.findViewById(R.id.appName)
            val pkg: TextView = v.findViewById(R.id.appPkg)
            val unavailableChip: TextView = v.findViewById(R.id.tvUnavailableChip)
            val blockedNowChip: TextView = v.findViewById(R.id.tvBlockedNowChip)
            val rule: TextView = v.findViewById(R.id.tvRule)
            val unavailableHint: TextView = v.findViewById(R.id.tvUnavailableHint)
            val quickEditButton: View = v.findViewById(R.id.btnQuickEdit)

            private fun dp(value: Float): Int =
                (value * itemView.resources.displayMetrics.density).toInt()

            fun updateBlockedNowChipOnly(item: AppDisplay) {
                val ctx = itemView.context

                // Uninstalled apps should only show the "Not installed" chip on the main list.
                if (!item.isAvailable) {
                    blockedNowChip.visibility = View.GONE
                    return
                }

                val enabled = SwitchModeStore.isEnabled(ctx)
                val emergency = EmergencyBypassStore.isActive(ctx)
                val profile = ProfileStore.getCurrent(ctx)

                val limitMin = if (!profile.isNullOrBlank()) {
                    UsageLimitStore.getLimitMinutes(ctx, profile, item.pkg)
                } else 0

                val sessionLimitMin = if (!profile.isNullOrBlank()) {
                    SessionLimitStore.getLimitMinutes(ctx, profile, item.pkg)
                } else 0

                val attemptLimit = if (!profile.isNullOrBlank()) {
                    AttemptLimitStore.getLimitAttempts(ctx, profile, item.pkg)
                } else 0

                val opensAtOrOver = if (attemptLimit > 0) {
                    OpenCountStore.getToday(ctx, profile ?: "default", item.pkg) >= attemptLimit
                } else false

                val limitReached = if (limitMin > 0) {
                    val limitMs = limitMin.toLong() * 60_000L
                    val usedMs = AppUsageToday.getUsageMsToday(ctx, item.pkg)
                    LimitReachedStore.isReachedToday(ctx, item.pkg) || usedMs >= limitMs
                } else false

                val hasAnyLimit = limitMin > 0 || sessionLimitMin > 0 || attemptLimit > 0
                val isActive = enabled && !emergency && !profile.isNullOrBlank()
                val reasonRes = when {
                    !isActive -> null
                    (limitMin > 0 && limitReached) -> R.string.dashboard_blocked_now_reason_limit
                    (attemptLimit > 0 && opensAtOrOver) -> R.string.dashboard_blocked_now_reason_attempts
                    !hasAnyLimit -> R.string.dashboard_blocked_now_reason_profile
                    else -> null
                }

                if (reasonRes == null) {
                    blockedNowChip.visibility = View.GONE
                } else {
                    blockedNowChip.text = ctx.getString(reasonRes)
                    blockedNowChip.visibility = View.VISIBLE
                }
            }

            private fun buildRuleText(ctx: Context, pkgName: String): String {
                val profile = ProfileStore.getCurrent(ctx)
                val limitMin = if (!profile.isNullOrBlank()) {
                    UsageLimitStore.getLimitMinutes(ctx, profile, pkgName)
                } else 0
                val sessionLimitMin = if (!profile.isNullOrBlank()) {
                    SessionLimitStore.getLimitMinutes(ctx, profile, pkgName)
                } else 0
                val attemptLimit = if (!profile.isNullOrBlank()) {
                    AttemptLimitStore.getLimitAttempts(ctx, profile, pkgName)
                } else 0

                val lines = mutableListOf<String>()
                if (limitMin > 0) {
                    lines += ctx.getString(R.string.daily_limit_value_format, limitMin)
                }
                if (sessionLimitMin > 0) {
                    lines += ctx.getString(R.string.session_limit_label, sessionLimitMin)
                }
                if (attemptLimit > 0) {
                    lines += ctx.resources.getQuantityString(
                        R.plurals.daily_attempt_limit_value_format,
                        attemptLimit,
                        attemptLimit
                    )
                }

                return if (lines.isNotEmpty()) {
                    when (lines.size) {
                        1 -> lines.first()
                        2 -> lines.joinToString(separator = "  –  ")
                        else -> lines.joinToString(separator = "  •  ")
                    }
                } else {
                    ctx.getString(R.string.in_app_surface_always_block)
                }
            }

            fun bind(item: AppDisplay, editEnabled: Boolean) {
                val ctx = itemView.context
                icon.setImageDrawable(item.icon)
                pkg.text = item.pkg

                updateBlockedNowChipOnly(item)
                updateQuickEditState(item, editEnabled)

                if (item.isAvailable) {
                    name.text = item.label
                    rule.text = buildRuleText(ctx, item.pkg)
                    rule.visibility = View.VISIBLE
                    cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.switchly_card_bg))
                    cardRoot.strokeColor = ContextCompat.getColor(ctx, R.color.switchly_card_stroke)
                    unavailableChip.visibility = View.GONE
                    unavailableHint.visibility = View.GONE
                } else {
                    rule.visibility = View.GONE
                    name.text = ctx.getString(R.string.unavailable_app_label)
                    cardRoot.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.unavailable_row_bg))
                    cardRoot.strokeColor = ContextCompat.getColor(ctx, R.color.unavailable_row_stroke)
                    blockedNowChip.visibility = View.GONE

                    val chipBg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(999f).toFloat()
                        setColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_bg))
                    }
                    unavailableChip.background = chipBg
                    unavailableChip.setTextColor(ContextCompat.getColor(ctx, R.color.unavailable_chip_text))
                    unavailableChip.visibility = View.VISIBLE
                    unavailableHint.visibility = View.GONE
                }
            }

            fun updateQuickEditState(item: AppDisplay, editEnabled: Boolean) {
                val enabled = editEnabled
                quickEditButton.visibility = View.VISIBLE
                quickEditButton.isEnabled = enabled
                quickEditButton.isClickable = enabled
                quickEditButton.alpha = when {
                    enabled -> if (item.isAvailable) 1f else 0.72f
                    else -> 0.38f
                }

                cardRoot.isClickable = enabled
                cardRoot.isFocusable = enabled
            }
        }

        private fun bindActions(holder: VH, item: AppDisplay, editEnabled: Boolean) {
            holder.cardRoot.setOnClickListener {
                if (editEnabled) onAppClick(item)
            }
            holder.quickEditButton.setOnClickListener {
                if (editEnabled) onEditClick(item)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.row_blocked_app, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            val item = getItem(position)
            var handled = false

            if (payloads.contains(PAYLOAD_BLOCKED_CHIPS)) {
                holder.updateBlockedNowChipOnly(item)
                handled = true
            }
            if (payloads.contains(PAYLOAD_BLOCKED_EDIT_STATE)) {
                val editEnabled = canEdit()
                holder.updateQuickEditState(item, editEnabled)
                bindActions(holder, item, editEnabled)
                handled = true
            }
            if (handled) return
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            val editEnabled = canEdit()
            holder.bind(item, editEnabled)
            bindActions(holder, item, editEnabled)
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<AppDisplay>() {
                override fun areItemsTheSame(oldItem: AppDisplay, newItem: AppDisplay): Boolean {
                    return oldItem.pkg == newItem.pkg
                }

                override fun areContentsTheSame(oldItem: AppDisplay, newItem: AppDisplay): Boolean {
                    return oldItem.pkg == newItem.pkg &&
                        oldItem.label == newItem.label &&
                        oldItem.isAvailable == newItem.isAvailable
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
        val quickActionsEnabled = areQuickActionsEnabled()
        val qrAllowed = AutomationModeStore.isQrAllowed(this)
        val barcodeAllowed = AutomationModeStore.isBarcodeAllowed(this)
        val qrQuickTileShown = quickActionsEnabled && isQuickActionTileEnabled(KEY_QA_QR) && qrAllowed
        val barcodeQuickTileShown = quickActionsEnabled && isQuickActionTileEnabled(KEY_QA_BARCODE) && barcodeAllowed

        menu.findItem(R.id.action_qr_header)?.isVisible = qrAllowed && !qrQuickTileShown
        menu.findItem(R.id.action_barcode_header)?.isVisible = barcodeAllowed && !barcodeQuickTileShown
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_qr_header -> {
                startActivity(Intent(this, QrScanActivity::class.java)
                    .putExtra(QrScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true))
                true
            }
            R.id.action_barcode_header -> {
                startActivity(Intent(this, BarcodeScanActivity::class.java)
                    .putExtra(BarcodeScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true))
                true
            }
            R.id.action_info -> {
                showDevelopmentInfoDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDevelopmentInfoDialog() {
        val downloadsUrl = getString(R.string.about_downloads_url)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.main_info_title))
            .setMessage(getString(R.string.main_development_info_message))
            .setPositiveButton(getString(R.string.main_info_contact_action)) { _, _ ->
                startActivity(Intent(this, SupportActivity::class.java))
            }
            .setNeutralButton(getString(R.string.main_info_older_versions_action)) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, downloadsUrl.toUri())) }
            }
            .setNegativeButton(getString(R.string.close), null)
            .showAccented()
    }

    private fun openQrScannerDirectly() {
        startActivity(
            Intent(this, QrScanActivity::class.java)
                .putExtra(QrScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true)
        )
    }

    private fun openBarcodeScannerDirectly() {
        startActivity(
            Intent(this, BarcodeScanActivity::class.java)
                .putExtra(BarcodeScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true)
        )
    }

    private fun showBarcodeChoiceDialog() {
        val items = arrayOf(getString(R.string.barcode_scan_title), getString(R.string.manage_barcodes_title))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dashboard_tile_barcode))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openBarcodeScannerDirectly()
                    1 -> {
                        if (EditingLockGuard.isLocked(this)) {
                            EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_barcodes)
                        } else {
                            startActivity(Intent(this, ManageBarcodesActivity::class.java))
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showAccented()
    }

    private fun showQrChoiceDialog() {
        val items = arrayOf(getString(R.string.qr_scan_title), getString(R.string.qr_generate_title))
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.qr_title))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openQrScannerDirectly()
                    1 -> startActivity(Intent(this, QrGenerateActivity::class.java))
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .showAccented()
    }
}
