package at.saltyy.switchly.feature.stats

import android.content.res.ColorStateList
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.databinding.ActivityStatisticsHubBinding
import at.saltyy.switchly.feature.settings.SettingsActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.MainActivity
import at.saltyy.switchly.util.SwitchlyAppAccessGuard
import at.saltyy.switchly.ui.ThemeUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import at.saltyy.switchly.feature.usage.ScreenTimeDashboardActivity
import android.graphics.Color

class StatisticsHubActivity : AppCompatActivity() {

    private lateinit var b: ActivityStatisticsHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        b = ActivityStatisticsHubBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Keep status bar neutral (same as other screens)
        window.statusBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        // Match other screens: keep navigation bar dark so gesture/nav area reads as spacing
        window.navigationBarColor = ContextCompat.getColor(this, android.R.color.black)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = false

        // Match edge-to-edge behavior across the app (Home/Schedules/Stats).
        EdgeToEdgeUtils.setupClassic(
            activity = this,
            toolbar = b.toolbar,
            bottomNav = b.bottomNav
        )
        EdgeToEdgeUtils.applyBottomNavGestureInset(b.bottomNav)

        val toolbar: MaterialToolbar = b.toolbar
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.setBackgroundColor(AccentColor.getToolbarColor(this))
        // Keep back arrow readable on accent toolbar.
        val bg = AccentColor.getToolbarColor(this)
        val navTint = if (MaterialColors.isColorLight(bg)) Color.BLACK else Color.WHITE
        toolbar.navigationIcon?.mutate()?.setTint(navTint)

        setupBottomNav(b.bottomNav)

        b.cardUsage.setOnClickListener {
            startActivity(ScreenTimeDashboardActivity.intent(this))
        }
        b.cardBlocking.setOnClickListener {
            startActivity(StatsActivity.intent(this, "blocking"))
        }
        b.cardOther.setOnClickListener {
            startActivity(StatsActivity.intent(this, "other"))
        }

        applyAccentUi()
    }

    override fun onResume() {
        super.onResume()
        applyAccentUi()
    }

    private fun applyAccentUi() {
        val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
        val tint = ColorStateList.valueOf(onSurface)
        b.ivUsageIcon.imageTintList = tint
        b.ivBlockingIcon.imageTintList = tint
        b.ivOtherIcon.imageTintList = tint
    }

    private fun setupBottomNav(bottomNav: BottomNavigationView) {
        bottomNav.selectedItemId = R.id.nav_stats

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

                R.id.nav_stats -> true

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
}