/*
 * Switchly
 * Copyright (C) 2025-2026 Saltyy
 * Copyright (C) 2026 Switchly Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package at.saltyy.switchly.feature.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.barcode.BarcodeScanActivity
import at.saltyy.switchly.feature.qr.QrScanActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.settings.InAppBlockingActivity
import at.saltyy.switchly.feature.settings.ManageBlockedWebsitesActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.feature.settings.ToggleOptionsActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.LockedUi
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class BlockingHubActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        setContentView(R.layout.activity_blocking_hub)

        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)

        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = bottomNav)
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        setupCards()
        setupBottomNav()
        syncLockedCardState()
    }

    override fun onResume() {
        super.onResume()
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        bottomNav.selectedItemId = R.id.nav_blocking
        syncLockedCardState()
    }

    private fun card(@IdRes id: Int): View = findViewById(id)

    private fun setupCards() {
        card(R.id.cardManageProfiles).setOnClickListener {
            if (EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_profiles)
            } else {
                startActivity(Intent(this, ManageProfilesActivity::class.java))
            }
        }

        card(R.id.cardBlockingModes).setOnClickListener {
            startActivity(Intent(this, ToggleOptionsActivity::class.java).apply {
                putExtra(ToggleOptionsActivity.EXTRA_VIEW_SECTION, ToggleOptionsActivity.SECTION_BLOCKING)
            })
        }

        card(R.id.cardBlockingFeatures).setOnClickListener {
            startActivity(Intent(this, ToggleOptionsActivity::class.java).apply {
                putExtra(ToggleOptionsActivity.EXTRA_VIEW_SECTION, ToggleOptionsActivity.SECTION_OTHER)
            })
        }

        card(R.id.cardScanQr).setOnClickListener {
            startActivity(Intent(this, QrScanActivity::class.java)
                .putExtra(QrScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true))
        }

        card(R.id.cardScanBarcode).setOnClickListener {
            startActivity(Intent(this, BarcodeScanActivity::class.java)
                .putExtra(BarcodeScanActivity.EXTRA_ALLOW_DIRECT_OPEN, true))
        }

        card(R.id.cardManageApps).setOnClickListener {
            if (SwitchModeStore.isBaseEnabled(this) || EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_apps)
            } else {
                startActivity(Intent(this, AppPickerActivity::class.java))
            }
        }

        card(R.id.cardManageWebsites).setOnClickListener {
            if (EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_websites)
            } else {
                startActivity(Intent(this, ManageBlockedWebsitesActivity::class.java))
            }
        }

        card(R.id.cardInAppBlocking).setOnClickListener {
            if (SwitchModeStore.isBaseEnabled(this) || EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_inapp)
            } else {
                startActivity(Intent(this, InAppBlockingActivity::class.java))
            }
        }
    }

    private fun syncLockedCardState() {
        val profilesLocked = EditingLockGuard.isLocked(this)
        val appsLocked = SwitchModeStore.isBaseEnabled(this) || EditingLockGuard.isLocked(this)
        val websitesLocked = EditingLockGuard.isLocked(this)
        val inAppLocked = SwitchModeStore.isBaseEnabled(this) || EditingLockGuard.isLocked(this)

        applyLockedCardState(card(R.id.cardManageProfiles), profilesLocked)
        applyLockedCardState(card(R.id.cardManageApps), appsLocked)
        applyLockedCardState(card(R.id.cardManageWebsites), websitesLocked)
        applyLockedCardState(card(R.id.cardInAppBlocking), inAppLocked)
        applyLockedCardState(card(R.id.cardBlockingModes), false)
        applyLockedCardState(card(R.id.cardBlockingFeatures), false)
        syncScanSectionVisibility()
    }

    private fun syncScanSectionVisibility() {
        val qrVisible = AutomationModeStore.isQrAllowed(this)
        val barcodeVisible = AutomationModeStore.isBarcodeAllowed(this)
        findViewById<View>(R.id.sectionScan).visibility = if (qrVisible || barcodeVisible) View.VISIBLE else View.GONE
        card(R.id.cardScanQr).visibility = if (qrVisible) View.VISIBLE else View.GONE
        card(R.id.cardScanBarcode).visibility = if (barcodeVisible) View.VISIBLE else View.GONE
    }

    private fun applyLockedCardState(view: View, locked: Boolean) {
        view.alpha = if (locked) lockedCardAlpha() else 1f
        // Keep clicks enabled so users still get the explanatory locked dialog.
        view.isEnabled = true
    }

    private fun lockedCardAlpha(): Float = LockedUi.cardAlpha(this)

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_blocking
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    })
                    finish()
                    true
                }

                R.id.nav_blocking -> true

                R.id.nav_tools -> {
                    startActivity(Intent(this, ToolsHubActivity::class.java))
                    finish()
                    true
                }

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
}