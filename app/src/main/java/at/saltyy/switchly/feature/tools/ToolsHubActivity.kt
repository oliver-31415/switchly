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

package at.saltyy.switchly.feature.tools

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.blocking.BlockingRuntime
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.ManagePairedTagsActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.feature.usage.ScreenTimeDashboardActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.LockedUi
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import at.saltyy.switchly.util.SystemBarColorCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class ToolsHubActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools_hub)

        setupViews()
        setupToolbar()
        tintToolIcons()
        setupToolCardActions()
        setupBottomNav()
        syncOptionalFeatureVisibility()
    }

    override fun onResume() {
        super.onResume()
        syncOptionalFeatureVisibility()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)
    }

    private fun setupToolbar() {
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = bottomNav)
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)
        SystemBarColorCompat.setStatusBarColor(window, ContextCompat.getColor(this, android.R.color.black))
        SystemBarColorCompat.setNavigationBarColor(window, ContextCompat.getColor(this, android.R.color.black))
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.navigationIcon = null
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
    }

    private fun tintToolIcons() {
        val iconTint = AccentColor.getActiveColor(this)

        listOf(
            R.id.ivSchedulesIcon,
            R.id.ivProfilesIcon,
            R.id.ivPairedTagsIcon,
            R.id.ivWriteNfcIcon,
            R.id.ivManageQrIcon,
            R.id.ivManageBarcodesIcon,
            R.id.ivBlockedNotificationsIcon,
            R.id.ivEmergencyIcon,
            R.id.ivInsightsIcon,
        ).forEach { iconId ->
            findViewById<ImageView>(iconId).imageTintList = iconTint
        }
    }

    private fun setupToolCardActions() {
        findViewById<View>(R.id.cardSchedules).setOnClickListener {
            startActivity(Intent(this, SchedulesActivity::class.java))
        }

        findViewById<View>(R.id.cardProfiles).setOnClickListener {
            if (isProfileManagementLocked()) {
                val messageRes = if (isNfcLockedForProtectedEdits()) {
                    R.string.toast_cannot_change_profile_while_locked
                } else {
                    R.string.edit_locked_manage_profiles
                }
                EditingLockGuard.showLockedDialog(this, messageRes)
            } else {
                startActivity(Intent(this, ManageProfilesActivity::class.java))
            }
        }

        findViewById<View>(R.id.cardBlockedNotifications).setOnClickListener {
            if (isProtectionActivelyEnforced()) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_blocked_notifications)
            } else {
                startActivity(Intent(this, BlockedInboxActivity::class.java))
            }
        }

        findViewById<View>(R.id.cardEmergency).setOnClickListener {
            showEmergencyQuickSheet()
        }

        findViewById<View>(R.id.cardPairedTags).setOnClickListener {
            val locked = EditingLockGuard.isLocked(this)
            AppLogStore.append(this, "NFC", "Manage Paired Tags clicked from Tools locked=$locked")
            if (locked) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_paired_tags)
            } else {
                runCatching {
                    startActivity(Intent(this, ManagePairedTagsActivity::class.java))
                }.onFailure { error ->
                    AppLogStore.append(this, "NFC", "Failed to open Manage Paired Tags from Tools", error)
                    Toast.makeText(this, R.string.error_open_manage_paired_tags, Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<View>(R.id.cardWriteNfc).setOnClickListener {
            startActivity(Intent(this, ManageKeysActivity::class.java))
        }

        findViewById<View>(R.id.cardManageQr).setOnClickListener {
            startActivity(Intent(this, ManageKeysActivity::class.java))
        }

        findViewById<View>(R.id.cardManageBarcodes).setOnClickListener {
            startActivity(Intent(this, ManageKeysActivity::class.java))
        }

        findViewById<View>(R.id.cardInsights).setOnClickListener {
            startActivity(ScreenTimeDashboardActivity.intent(this))
        }
    }

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_tools
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    finish()
                    true
                }
                R.id.nav_blocking -> {
                    startActivity(Intent(this, BlockingHubActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_tools -> true
                R.id.nav_settings -> {
                    if (SwitchlyAppAccessGuard.isLocked(this)) {
                        SwitchlyAppAccessGuard.showLockedToast(this)
                        false
                    } else {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                        true
                    }
                }
                else -> false
            }
        }
    }

    private fun syncOptionalFeatureVisibility() {
        val cardPairedTags = findViewById<View>(R.id.cardPairedTags)
        val cardManageQr = findViewById<View>(R.id.cardManageQr)
        val cardManageBarcodes = findViewById<View>(R.id.cardManageBarcodes)
        val cardBlockedNotifications = findViewById<View>(R.id.cardBlockedNotifications)
        val cardProfiles = findViewById<View>(R.id.cardProfiles)
        val cardManageKeys = findViewById<View>(R.id.cardWriteNfc)

        // NFC, paired tags, QR, and barcode management live behind one normal
        // "Manage keys" subpage instead of separate cards or a popup.
        cardProfiles.visibility = View.GONE
        cardPairedTags.visibility = View.GONE
        cardManageQr.visibility = View.GONE
        cardManageBarcodes.visibility = View.GONE
        cardBlockedNotifications.visibility = View.VISIBLE

        applyLockedCardState(cardProfiles, false)
        applyLockedCardState(cardManageKeys, isNfcTagWritingLocked())
        applyLockedCardState(cardBlockedNotifications, isProtectionActivelyEnforced())
    }

    private fun applyLockedCardState(view: View, locked: Boolean) {
        view.alpha = if (locked) lockedCardAlpha() else 1f
        view.isEnabled = true
    }

    private fun lockedCardAlpha(): Float = LockedUi.cardAlpha(this)

    private fun isProtectionActivelyEnforced(): Boolean {
        return SwitchModeStore.isEnabled(this) && !EmergencyBypassStore.isActive(this)
    }

    private fun showEmergencyQuickSheet() {
        if (!EmergencyBypassStore.isFeatureEnabled(this)) {
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

        val isActive = EmergencyBypassStore.isActive(this)
        val isPaused = EmergencyBypassStore.isPaused(this)
        val hasUsedToday = EmergencyBypassStore.hasUsedToday(this)
        val remainingMinutes = EmergencyBypassStore.minutesRemaining(this)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        fun addAction(label: String, action: () -> Unit) {
            labels += label
            actions += action
        }

        if (isActive) {
            addAction(getString(R.string.emergency_action_pause)) {
                if (EmergencyBypassStore.pause(this)) {
                    SwitchModeStore.clearTemporary(this)
                    AppLogStore.append(this, "Emergency", "Emergency mode paused from Tools")
                    Toast.makeText(this, getString(R.string.emergency_paused_toast), Toast.LENGTH_SHORT).show()
                    BlockingRuntime.ensureRunning(this)
                }
            }
            addAction(getString(R.string.emergency_action_end)) {
                AppLogStore.append(this, "Emergency", "Emergency mode ended from Tools")
                EmergencyBypassStore.cancel(this)
                SwitchModeStore.clearTemporary(this)
                Toast.makeText(this, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                BlockingRuntime.ensureRunning(this)
            }
        } else if (isPaused) {
            addAction(getString(R.string.emergency_action_resume)) {
                if (EmergencyBypassStore.resume(this)) {
                    val minutes = EmergencyBypassStore.minutesRemaining(this).coerceAtLeast(1)
                    SwitchModeStore.setTemporarilyDisabled(this, minutes * 60_000L)
                    AppLogStore.append(this, "Emergency", "Emergency mode resumed from Tools with ${minutes}m remaining")
                    Toast.makeText(this, getString(R.string.emergency_resumed_toast), Toast.LENGTH_SHORT).show()
                    BlockingRuntime.ensureRunning(this)
                }
            }
            addAction(getString(R.string.emergency_action_end)) {
                EmergencyBypassStore.cancel(this)
                SwitchModeStore.clearTemporary(this)
                Toast.makeText(this, getString(R.string.emergency_ended_toast), Toast.LENGTH_SHORT).show()
                BlockingRuntime.ensureRunning(this)
            }
        } else if (!hasUsedToday) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.pref_emergency_title))
                .setMessage(getString(R.string.emergency_action_start_15))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    if (EmergencyBypassStore.enableIfAllowed(this, 15)) {
                        AppLogStore.append(this, "Emergency", "Emergency mode started from Tools for 15m")
                        SwitchModeStore.setTemporarilyDisabled(this, 15 * 60_000L)
                        Toast.makeText(this, getString(R.string.emergency_enabled_toast, 15), Toast.LENGTH_SHORT).show()
                        BlockingRuntime.ensureRunning(this)
                    } else {
                        Toast.makeText(this, getString(R.string.emergency_used_today), Toast.LENGTH_SHORT).show()
                    }
                }
                .showAccented()
            return
        }

        val title = when {
            isActive -> getString(R.string.emergency_manage_title_active, remainingMinutes)
            isPaused -> getString(R.string.emergency_manage_title_paused, remainingMinutes)
            else -> getString(R.string.pref_emergency_title)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setNegativeButton(R.string.cancel, null)

        if (labels.isNotEmpty()) {
            builder.setItems(labels.toTypedArray()) { _, which ->
                runCatching { actions[which].invoke() }
            }
        }

        if (!isActive && !isPaused && hasUsedToday) {
            builder.setMessage(R.string.emergency_used_today)
        }

        builder.showAccented()
    }

    private fun isNfcLockedForProtectedEdits(): Boolean {
        return SwitchModeStore.isEnabled(this) && SwitchModeStore.isNfcRequiredForDisable(this)
    }

    private fun isProfileManagementLocked(): Boolean {
        val enabled = SwitchModeStore.isEnabled(this)
        val emergencyActive = EmergencyBypassStore.isActive(this)
        val emergencyPaused = EmergencyBypassStore.isPaused(this)
        if (!enabled && !emergencyActive) return false

        val requireNfc = SwitchModeStore.isNfcRequiredForDisable(this)
        if (requireNfc || emergencyActive || (enabled && emergencyPaused)) return true

        return !AutomationModeStore.isProfileSwitchingAllowedWhileEnabled(this)
    }

    private fun isNfcTagWritingLocked(): Boolean {
        return SwitchModeStore.isEnabled(this) &&
            !AutomationModeStore.isNfcTagWritingAllowedWhileEnabled(this)
    }
}
