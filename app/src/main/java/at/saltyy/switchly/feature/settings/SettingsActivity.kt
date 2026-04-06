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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import at.saltyy.switchly.feature.tools.ToolsHubActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView

class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var bottomNav: BottomNavigationView

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
        showRootFragment(savedInstanceState)
        updateTitleFromFragment()
    }

    override fun onResume() {
        super.onResume()
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
    }

    override fun onSupportNavigateUp(): Boolean {
        return if (supportFragmentManager.backStackEntryCount > 0) {
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
            val canGoBack = supportFragmentManager.backStackEntryCount > 0
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

    private fun showRootFragment(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .commit()
        }
    }

    private fun updateTitleFromFragment() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.container)
        val title = if (currentFragment is SettingsFragment) {
            currentFragment.currentScreenTitle()
        } else {
            getString(R.string.settings)
        }
        setToolbarTitle(title)
    }

    private fun resetSettingsToTop() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentById(R.id.container) as? SettingsFragment)?.scrollToTop()
    }
}
