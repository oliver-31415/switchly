package at.saltyy.switchly.feature.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import at.saltyy.switchly.R
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import at.saltyy.switchly.feature.stats.StatisticsHubActivity
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.util.SwitchlyAppAccessGuard

class SettingsActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
        setContentView(R.layout.activity_settings)

        toolbar = findViewById(R.id.toolbar)
        val bottomNav = findViewById<BottomNavigationView>(R.id.bottomNav)
        // Match NFC writer: classic system insets (toolbar below status bar)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar, bottomNav = bottomNav)
        EdgeToEdgeUtils.applyBottomNavGestureInset(bottomNav)
        setSupportActionBar(toolbar)
        // Settings is a main tab now -> root has no back arrow.
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))

        // Show a back arrow when navigating into nested PreferenceScreens.
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

        setupBottomNav(bottomNav)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, SettingsFragment())
                .commit()
        }

        // Ensure correct title (root or nested)
        updateTitleFromFragment()
    }

    fun setToolbarTitle(title: String) {
        supportActionBar?.title = title
        toolbar.title = title
    }

    private fun updateTitleFromFragment() {
        val f = supportFragmentManager.findFragmentById(R.id.container)
        val t = if (f is SettingsFragment) f.currentScreenTitle() else getString(R.string.settings)
        setToolbarTitle(t)
    }

    override fun onSupportNavigateUp(): Boolean {
        return if (supportFragmentManager.backStackEntryCount > 0) {
            onBackPressedDispatcher.onBackPressed()
            true
        } else {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (SwitchlyAppAccessGuard.blockIfLocked(this)) return
    }

    private fun setupBottomNav(bottomNav: BottomNavigationView) {
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

                R.id.nav_stats -> {
                    startActivity(Intent(this, StatisticsHubActivity::class.java))
                    finish()
                    true
                }

                R.id.nav_settings -> true

                else -> false
            }
        }

        // If user taps Settings again while already here, jump back to top of settings.
        bottomNav.setOnItemReselectedListener { item ->
            if (item.itemId == R.id.nav_settings) {
                resetSettingsToTop()
            }
        }
    }

    private fun resetSettingsToTop() {
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.executePendingTransactions()
        (supportFragmentManager.findFragmentById(R.id.container) as? SettingsFragment)?.scrollToTop()
    }
}
