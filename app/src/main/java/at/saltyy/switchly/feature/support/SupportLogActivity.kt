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

package at.saltyy.switchly.feature.support

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLogStore
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class SupportLogActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logText: TextView
    private lateinit var copyButton: MaterialButton
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
        setContentView(R.layout.activity_support_logs)

        setupToolbar()

        logText = findViewById(R.id.tvSupportLogs)
        copyButton = findViewById(R.id.btnSupportLogsCopy)
        findViewById<MaterialButton>(R.id.btnSupportLogsRefresh).setOnClickListener {
            refreshLogs()
        }
        copyButton.setOnClickListener { copyLogs() }
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

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarSupportLogs)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        val foreground = toolbarForegroundColor()
        toolbar.navigationIcon?.mutate()?.setTint(foreground)
        toolbar.setTitleTextColor(foreground)
    }

    private fun refreshLogs() {
        val logs = AppLogStore.latestPlainText(this, MAX_VISIBLE_LINES).trim()
        logText.text = logs.ifBlank { getString(R.string.support_logs_view_empty) }
        copyButton.isEnabled = logs.isNotBlank()
    }

    private fun copyLogs() {
        val text = AppLogStore.latestPlainText(this, MAX_VISIBLE_LINES).trim()
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.support_logs_view_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Switchly logs", text))
        Toast.makeText(this, getString(R.string.support_logs_copied), Toast.LENGTH_SHORT).show()
    }

    private fun toolbarForegroundColor(): Int {
        val toolbarColor = AccentColor.getToolbarColor(this)
        return if (ColorUtils.calculateLuminance(toolbarColor) > 0.5) Color.BLACK else Color.WHITE
    }

    private companion object {
        private const val MAX_VISIBLE_LINES = 250
        private const val LIVE_REFRESH_MS = 1_500L
    }
}
