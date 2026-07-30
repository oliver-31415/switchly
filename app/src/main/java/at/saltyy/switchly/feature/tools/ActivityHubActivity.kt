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
import at.saltyy.switchly.feature.inbox.BlockedInboxActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.feature.usage.ActiveTimeActivity
import at.saltyy.switchly.feature.usage.ActivityHistoryActivity
import at.saltyy.switchly.feature.usage.AppLaunchesActivity
import at.saltyy.switchly.feature.usage.AppWebsiteUsageActivity
import at.saltyy.switchly.feature.usage.ScreenUnlocksActivity
import at.saltyy.switchly.feature.usage.SwitchlyOverviewActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.ActivityTransitionCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class ActivityHubActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)

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
        toolbar.title = getString(R.string.nav_activity)
        supportActionBar?.title = getString(R.string.nav_activity)
    }

    private fun tintActivityIcons() {
        val iconTint = ColorStateList.valueOf(AccentColor.getAccentColorInt(this))

        listOf(
            R.id.ivSwitchlyOverviewIcon,
            R.id.ivActiveTimeIcon,
            R.id.ivAppWebsiteUsageIcon,
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
        findViewById<View>(R.id.cardSwitchlyOverview).setOnClickListener {
            startActivity(SwitchlyOverviewActivity.intent(this))
        }
        findViewById<View>(R.id.cardActiveTime).setOnClickListener {
            startActivity(ActiveTimeActivity.intent(this))
        }
        findViewById<View>(R.id.cardAppWebsiteUsage).setOnClickListener {
            startActivity(AppWebsiteUsageActivity.intent(this))
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
            startActivity(Intent(this, BlockedInboxActivity::class.java))
        }
    }

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_activity
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    ActivityTransitionCompat.switchWithoutAnimation(
                        activity = this,
                        intent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        },
                        finishCurrent = true,
                    )
                    true
                }
                R.id.nav_rules -> {
                    RulesHubActivity.openWithAccessCheck(
                        source = this,
                        finishSourceAfterOpen = true
                    )
                }
                R.id.nav_activity -> true
                R.id.nav_settings -> {
                    SettingsActivity.openWithAccessCheck(
                        source = this,
                        finishSourceAfterOpen = true
                    )
                }
                else -> false
            }
        }
    }

}
