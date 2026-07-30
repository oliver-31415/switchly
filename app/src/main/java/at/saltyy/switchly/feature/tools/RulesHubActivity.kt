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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.picker.AppPickerActivity
import at.saltyy.switchly.feature.profiles.ManageProfilesActivity
import at.saltyy.switchly.feature.schedule.SchedulesActivity
import at.saltyy.switchly.feature.settings.InAppRulesActivity
import at.saltyy.switchly.feature.settings.ManageBlockedWebsitesActivity
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.ActivityTransitionCompat
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class RulesHubActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules_hub)

        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)

        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = bottomNav)
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        setupCards()
        setupBottomNav()
    }

    override fun onResume() {
        super.onResume()
        bottomNav.selectedItemId = R.id.nav_rules
    }

    private fun card(@IdRes id: Int): View = findViewById(id)

    private fun setupCards() {
        card(R.id.cardManageProfiles).setOnClickListener {
            startActivity(Intent(this, ManageProfilesActivity::class.java))
        }

        card(R.id.cardManageApps).setOnClickListener {
            startActivity(Intent(this, AppPickerActivity::class.java))
        }

        card(R.id.cardManageWebsites).setOnClickListener {
            startActivity(Intent(this, ManageBlockedWebsitesActivity::class.java))
        }

        card(R.id.cardInAppBlocking).setOnClickListener {
            startActivity(Intent(this, InAppRulesActivity::class.java))
        }

        card(R.id.cardSchedules).setOnClickListener {
            startActivity(Intent(this, SchedulesActivity::class.java))
        }
    }

    companion object {
        fun openWithAccessCheck(
            source: AppCompatActivity,
            finishSourceAfterOpen: Boolean = false,
        ): Boolean {
            // Rules are always reviewable.
            // Individual editors enforce read-only mode while protection or a temporary override is active.
            ActivityTransitionCompat.switchWithoutAnimation(
                activity = source,
                intent = Intent(source, RulesHubActivity::class.java),
                finishCurrent = finishSourceAfterOpen,
            )
            return true
        }
    }

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_rules
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

                R.id.nav_rules -> true

                R.id.nav_activity -> {
                    ActivityTransitionCompat.switchWithoutAnimation(
                        activity = this,
                        intent = Intent(this, ActivityHubActivity::class.java),
                        finishCurrent = true,
                    )
                    true
                }

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
