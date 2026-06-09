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

package at.saltyy.switchly.feature.about

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AdvancedModeStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar

class DeveloperModeActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_developer_mode)

        setupToolbar()
        setupCards()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarDeveloperMode)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.developer_mode_title)
        toolbar.title = getString(R.string.developer_mode_title)
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.arrow_back_ios_24)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
    }

    private fun setupCards() {
        findViewById<View>(R.id.cardDeveloperModeAdb).setOnClickListener {
            startActivity(Intent(this, AdvancedModeActivity::class.java))
        }
        findViewById<View>(R.id.cardDeveloperModeLogging).setOnClickListener {
            startActivity(Intent(this, DeveloperLogActivity::class.java))
        }
        findViewById<View>(R.id.cardDeveloperModeDisable).setOnClickListener {
            AdvancedModeStore.setEnabled(this, false)
            Toast.makeText(this, getString(R.string.developer_mode_disabled_toast), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
