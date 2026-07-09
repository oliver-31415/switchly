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
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.EmergencyBypassStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.feature.usage.ActiveTimeStatsActivity
import at.saltyy.switchly.feature.usage.ActivityHistoryActivity
import at.saltyy.switchly.feature.usage.AppLaunchesActivity
import at.saltyy.switchly.feature.usage.ScreenTimeDashboardActivity
import at.saltyy.switchly.feature.usage.ScreenUnlocksActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.EditingLockGuard
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * Bottom-tab activity hub.
 * Class name stays ToolsHubActivity for compatibility with existing intents/manifest entries, but the user-facing tab is Activity and contains direct activity/statistics entries.
 */
class ToolsHubActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools_hub)

        setupViews()
        setupToolbar()
        tintActivityIcons()
        setupActivityCardActions()
        setupBottomNav()
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)
    }

    private fun setupToolbar() {
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = bottomNav)
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.navigationIcon = null
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        toolbar.title = getString(R.string.nav_tools)
        supportActionBar?.title = getString(R.string.nav_tools)
    }

    private fun tintActivityIcons() {
        val iconTint = ColorStateList.valueOf(AccentColor.getAccentColorInt(this))

        listOf(
            R.id.ivActiveTimeIcon,
            R.id.ivUsageStatsIcon,
            R.id.ivAppLaunchesIcon,
            R.id.ivScreenUnlocksIcon,
            R.id.ivActivityHistoryIcon,
            R.id.ivBlockedNotificationsIcon,
        ).forEach { iconId ->
            findViewById<ImageView>(iconId)?.let { icon ->
                icon.imageTintList = iconTint
                icon.setColorFilter(iconTint.defaultColor)
                icon.isEnabled = true
                icon.alpha = 1f
            }
        }
    }

    private fun setupActivityCardActions() {
        findViewById<View>(R.id.cardActiveTime).setOnClickListener {
            startActivity(ActiveTimeStatsActivity.intent(this))
        }
        findViewById<View>(R.id.cardUsageStats).setOnClickListener {
            startActivity(ScreenTimeDashboardActivity.intent(this))
        }
        findViewById<View>(R.id.cardAppLaunches).setOnClickListener {
            startActivity(AppLaunchesActivity.intent(this))
        }
        findViewById<View>(R.id.cardScreenUnlocks).setOnClickListener {
            startActivity(ScreenUnlocksActivity.intent(this))
        }
        findViewById<View>(R.id.cardActivityHistory).setOnClickListener {
            startActivity(ActivityHistoryActivity.intent(this))
        }
        findViewById<View>(R.id.cardBlockedNotifications).setOnClickListener {
            if (isProtectionActivelyEnforced()) {
                EditingLockGuard.showLockedDialog(this, R.string.edit_locked_manage_blocked_notifications)
            } else {
                startActivity(Intent(this, BlockedInboxActivity::class.java))
            }
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

    private fun isProtectionActivelyEnforced(): Boolean {
        return SwitchModeStore.isEnabled(this) && !EmergencyBypassStore.isActive(this)
    }
}
