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
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import at.saltyy.switchly.R
import at.saltyy.switchly.feature.usage.ActiveTimeStatsActivity
import at.saltyy.switchly.feature.usage.ActivityHistoryActivity
import at.saltyy.switchly.feature.usage.AppLaunchesActivity
import at.saltyy.switchly.feature.usage.ScreenUnlocksActivity
import at.saltyy.switchly.feature.usage.ScreenTimeDashboardActivity
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

class ManageInsightsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = getString(R.string.insights_menu_title)
            setNavigationIcon(R.drawable.arrow_back_ios_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundColor(AccentColor.getToolbarColor(this@ManageInsightsActivity))
        }
        root.addView(AppBarLayout(this).apply {
            fitsSystemWindows = true
            addView(
                toolbar,
                AppBarLayout.LayoutParams(
                    AppBarLayout.LayoutParams.MATCH_PARENT,
                    AppBarLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        content.addView(TextView(this).apply {
            text = getString(R.string.insights_menu_summary)
            textSize = 14.5f
            alpha = 0.86f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(resolveAttrColor(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, dp(8))
        })

        content.addView(
            menuCard(
                title = getString(R.string.insights_active_time),
                summary = getString(R.string.insights_active_time_summary),
                iconRes = R.drawable.schedule_24
            ) {
                startActivity(ActiveTimeStatsActivity.intent(this))
            }
        )

        content.addView(
            menuCard(
                title = getString(R.string.insights_usage_statistics),
                summary = getString(R.string.insights_usage_statistics_summary),
                iconRes = R.drawable.bar_chart_24
            ) {
                startActivity(ScreenTimeDashboardActivity.intent(this))
            }
        )

        content.addView(
            menuCard(
                title = getString(R.string.insights_app_launches),
                summary = getString(R.string.insights_app_launches_summary),
                iconRes = R.drawable.apps_24
            ) {
                startActivity(AppLaunchesActivity.intent(this))
            }
        )

        content.addView(
            menuCard(
                title = getString(R.string.insights_screen_unlocks),
                summary = getString(R.string.insights_screen_unlocks_summary),
                iconRes = R.drawable.lock_open_24
            ) {
                startActivity(ScreenUnlocksActivity.intent(this))
            }
        )

        content.addView(
            menuCard(
                title = getString(R.string.insights_activity_history),
                summary = getString(R.string.insights_activity_history_summary),
                iconRes = R.drawable.layers_24
            ) {
                startActivity(ActivityHistoryActivity.intent(this))
            }
        )

        setContentView(root)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
    }

    private fun menuCard(
        title: String,
        summary: String,
        iconRes: Int,
        onClick: () -> Unit
    ): MaterialCardView {
        val parent = LinearLayout(this)
        val card = LayoutInflater.from(this)
            .inflate(R.layout.item_pref_card, parent, false) as MaterialCardView
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        }

        card.findViewById<TextView>(android.R.id.title).text = title
        card.findViewById<TextView>(android.R.id.summary).text = summary
        card.findViewById<ImageView>(android.R.id.icon).apply {
            setImageResource(iconRes)
            contentDescription = null
            imageTintList = android.content.res.ColorStateList.valueOf(AccentColor.getAccentColorInt(this@ManageInsightsActivity))
        }
        card.setOnClickListener { onClick() }
        return card
    }

    private fun resolveAttrColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    private fun actionBarSize(): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
        return android.util.TypedValue.complexToDimensionPixelSize(
            typedValue.data,
            resources.displayMetrics
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
