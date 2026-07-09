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

package at.saltyy.switchly.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.premium.PremiumInfoActivity
import at.saltyy.switchly.feature.tools.BlockingHubActivity
import at.saltyy.switchly.feature.tools.ToolsHubActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var rootScroll: View
    private lateinit var container: View

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        setContentView(R.layout.activity_settings)

        setupViews()
        setupToolbar()
        setupToolbarTitleSync()
        setupBottomNav()
        setupRootCards()
        restoreScreenState(savedInstanceState)
        updateTitleFromFragment()
    }

    override fun onResume() {
        super.onResume()
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
    }

    override fun onSupportNavigateUp(): Boolean {
        return if (supportFragmentManager.backStackEntryCount > 0 || container.isVisible) {
            onBackPressedDispatcher.onBackPressed()
            true
        } else {
            false
        }
    }

    fun setToolbarTitle(title: String) {
        supportActionBar?.title = title
        toolbar.title = title
    }

    private fun setupViews() {
        toolbar = findViewById(R.id.toolbar)
        bottomNav = findViewById(R.id.bottomNav)
        rootScroll = findViewById(R.id.settingsRootScroll)
        container = findViewById(R.id.container)
    }

    private fun setupToolbar() {
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = bottomNav)
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
    }

    private fun setupToolbarTitleSync() {
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                showRootSettings()
            } else {
                showNestedSettingsContainer()
            }
            val canGoBack = container.isVisible
            supportActionBar?.setDisplayHomeAsUpEnabled(canGoBack)
            toolbar.navigationIcon = if (canGoBack) {
                ContextCompat.getDrawable(this, R.drawable.arrow_back_ios_24)
            } else {
                null
            }
            updateTitleFromFragment()
        }
    }

    private fun setupBottomNav() {
        bottomNav.selectedItemId = R.id.nav_settings

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                    )
                    finish()
                    true
                }
                R.id.nav_blocking -> {
                    startActivity(Intent(this, BlockingHubActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_tools -> {
                    startActivity(Intent(this, ToolsHubActivity::class.java))
                    finish()
                    true
                }
                R.id.nav_settings -> true
                else -> false
            }
        }

        bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_settings) {
                resetSettingsToTop()
            }
        }
    }

    private fun setupRootCards() {
        findViewById<View>(R.id.cardSettingsBlockingModes).setOnClickListener {
            startActivity(Intent(this, BlockingModesActivity::class.java))
        }
        findViewById<View>(R.id.cardSettingsBlockingFeatures).setOnClickListener {
            startActivity(Intent(this, BlockingFeaturesActivity::class.java))
        }
        findViewById<View>(R.id.cardSettingsAppearance).setOnClickListener {
            showNestedSettingsScreen("screen_appearance")
        }
        findViewById<View>(R.id.cardSettingsDisplayShortcuts).setOnClickListener {
            startActivity(Intent(this, ToggleOptionsActivity::class.java).apply {
                putExtra(ToggleOptionsActivity.EXTRA_VIEW_SECTION, ToggleOptionsActivity.SECTION_DISPLAY)
            })
        }
        findViewById<View>(R.id.cardSettingsPermissions).setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
        findViewById<View>(R.id.cardSettingsAppLock).setOnClickListener {
            startActivity(Intent(this, AppLockSettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardSettingsAccountData).setOnClickListener {
            showNestedSettingsScreen("screen_account")
        }
        findViewById<View>(R.id.cardSettingsPremium).setOnClickListener {
            startActivity(Intent(this, PremiumInfoActivity::class.java))
        }
        findViewById<View>(R.id.cardSettingsHelpAbout).setOnClickListener {
            showNestedSettingsScreen("screen_help_about")
        }
    }

    private fun restoreScreenState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            showRootSettings()
            return
        }
        if (supportFragmentManager.findFragmentById(R.id.container) != null) {
            showNestedSettingsContainer()
        } else {
            showRootSettings()
        }
    }

    private fun showNestedSettingsScreen(screenKey: String) {
        showNestedSettingsContainer()
        val fragment = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString("androidx.preference.PreferenceFragmentCompat.PREFERENCE_ROOT", screenKey)
            }
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(screenKey)
            .commit()
    }

    private fun showRootSettings() {
        rootScroll.visibility = View.VISIBLE
        container.visibility = View.GONE
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.navigationIcon = null
        setToolbarTitle(getString(R.string.settings))
    }

    private fun showNestedSettingsContainer() {
        rootScroll.visibility = View.GONE
        container.visibility = View.VISIBLE
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.arrow_back_ios_24)
    }

    private fun updateTitleFromFragment() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.container)
        val title = if (container.isVisible && currentFragment is SettingsFragment) {
            currentFragment.currentScreenTitle()
        } else {
            getString(R.string.settings)
        }
        setToolbarTitle(title)
    }

    private fun resetSettingsToTop() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.executePendingTransactions()
        showRootSettings()
        rootScroll.post { runCatching { rootScroll.scrollTo(0, 0) } }
    }
}
