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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar

class DeveloperLogActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logText: TextView
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshLogs()
            handler.postDelayed(this, LIVE_REFRESH_MS)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_developer_logs)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarDeveloperLogs)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.developer_logging_title)
        toolbar.title = getString(R.string.developer_logging_title)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        logText = findViewById(R.id.tvDeveloperLogs)
        findViewById<Button>(R.id.btnDeveloperLogsRefresh).setOnClickListener { refreshLogs() }
        findViewById<Button>(R.id.btnDeveloperLogsCopy).setOnClickListener { copyLogs() }
        refreshLogs()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private fun refreshLogs() {
        logText.text = AppLogStore.latestPlainText(this, MAX_VISIBLE_LINES)
    }

    private fun copyLogs() {
        val text = AppLogStore.latestPlainText(this, MAX_VISIBLE_LINES)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Switchly logs", text))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    private companion object {
        private const val MAX_VISIBLE_LINES = 250
        private const val LIVE_REFRESH_MS = 1_500L
    }
}
