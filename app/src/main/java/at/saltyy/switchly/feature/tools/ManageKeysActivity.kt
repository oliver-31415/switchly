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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AutomationModeStore
import at.saltyy.switchly.feature.qr.QrGenerateActivity
import at.saltyy.switchly.feature.settings.ManageBarcodesActivity
import at.saltyy.switchly.feature.settings.ManagePairedTagsActivity
import at.saltyy.switchly.nfc.NfcWriterActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.LockedUi
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SystemBarColorCompat
import com.google.android.material.appbar.MaterialToolbar

class ManageKeysActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manage_keys)

        toolbar = findViewById(R.id.toolbar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = null)
        SystemBarColorCompat.setStatusBarColor(window, androidx.core.content.ContextCompat.getColor(this, android.R.color.black))
        SystemBarColorCompat.setNavigationBarColor(window, androidx.core.content.ContextCompat.getColor(this, android.R.color.black))
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        setupCards()
        syncLockedCardState()
    }

    override fun onResume() {
        super.onResume()
        syncLockedCardState()
    }

    private fun setupCards() {
        findViewById<View>(R.id.cardWriteNfc).setOnClickListener {
            if (isNfcTagWritingLocked()) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_write_nfc_tags)
            } else {
                startActivity(Intent(this, NfcWriterActivity::class.java))
            }
        }

        findViewById<View>(R.id.cardPairedTags).setOnClickListener {
            if (EditingLockGuard.isLocked(this)) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_paired_tags)
            } else {
                startActivity(Intent(this, ManagePairedTagsActivity::class.java))
            }
        }

        findViewById<View>(R.id.cardGenerateQr).setOnClickListener {
            startActivity(Intent(this, QrGenerateActivity::class.java))
        }

        findViewById<View>(R.id.cardManageBarcodes).setOnClickListener {
            val allowBarcodeSetupFallback = AutomationModeStore.isBarcodeSetupMissing(this)
            if (EditingLockGuard.isLocked(this) && !allowBarcodeSetupFallback) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_barcodes)
            } else {
                startActivity(Intent(this, ManageBarcodesActivity::class.java))
            }
        }
    }

    private fun syncLockedCardState() {
        val editLocked = EditingLockGuard.isLocked(this)
        val barcodeSetupFallback = AutomationModeStore.isBarcodeSetupMissing(this)
        applyLockedCardState(findViewById(R.id.cardWriteNfc), isNfcTagWritingLocked())
        applyLockedCardState(findViewById(R.id.cardPairedTags), editLocked)
        applyLockedCardState(findViewById(R.id.cardManageBarcodes), editLocked && !barcodeSetupFallback)
    }

    private fun applyLockedCardState(view: View, locked: Boolean) {
        view.alpha = if (locked) lockedCardAlpha() else 1f
        // Keep clicks enabled so locked cards can explain why they cannot be opened.
        view.isEnabled = true
    }

    private fun lockedCardAlpha(): Float = LockedUi.cardAlpha(this)

    private fun isNfcTagWritingLocked(): Boolean {
        return EditingLockGuard.isLocked(this)
    }
}
