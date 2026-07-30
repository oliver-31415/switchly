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

package at.saltyy.switchly.feature.usage

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.AppLaunchCountStore
import at.saltyy.switchly.data.prefs.UsageStore
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import java.text.DateFormat
import java.util.Date

class AppLaunchDetailActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var packageName: String
    private lateinit var label: String
    private lateinit var rangeName: String
    private var customStartMs: Long = 0L
    private var customEndMs: Long = 0L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { packageName }
        rangeName = intent.getStringExtra(EXTRA_RANGE).orEmpty().ifBlank { "week" }
        customStartMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        customEndMs = intent.getLongExtra(EXTRA_END_MS, 0L)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = label
            setNavigationIcon(R.drawable.keyboard_arrow_left_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundColor(AccentColor.getToolbarColor(this@AppLaunchDetailActivity))
        }
        root.addView(AppBarLayout(this).apply {
            fitsSystemWindows = true
            addView(toolbar, AppBarLayout.LayoutParams(
                AppBarLayout.LayoutParams.MATCH_PARENT,
                AppBarLayout.LayoutParams.WRAP_CONTENT
            ))
        })

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        setContentView(root)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
        load()
    }

    private fun load() {
        content.removeAllViews()
        content.addView(messageCard(getString(R.string.usage_timeline_loading)))
        val ctx = applicationContext
        val pkg = packageName
        val range = rangeName
        val hasUsageAccess = UsageStatsRepo.hasUsageAccess(this)
        Thread {
            val (from, to) = if (range == "custom" && customEndMs > customStartMs) {
                customStartMs to customEndMs
            } else {
                UsageTimelineRepo.windowForRange(range)
            }
            if (hasUsageAccess) {
                runCatching { StatsArchiveSync.sync(ctx) }
            }
            val sessions = UsageTimelineRepo.appSessions(ctx, pkg, from, to, limit = 0)
            val storedLaunches = AppLaunchCountStore.getForDateRange(ctx, pkg, from, to)
            val storedUsage = UsageStore.getUsageMsSeriesForDateRange(ctx, pkg, from, to).sum()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(sessions, storedLaunches, storedUsage, hasUsageAccess)
            }
        }.start()
    }

    private fun render(
        sessions: List<UsageTimelineRepo.AppSession>,
        launchCount: Int,
        totalMs: Long,
        hasUsageAccess: Boolean,
    ) {
        content.removeAllViews()
        val effectiveLaunches = launchCount.takeIf { it > 0 } ?: sessions.size
        val effectiveTotal = totalMs.takeIf { it > 0L } ?: sessions.sumOf { it.durationMs }
        if (effectiveLaunches <= 0 && effectiveTotal <= 0L) {
            val message = if (hasUsageAccess) R.string.usage_timeline_empty else R.string.usage_timeline_permission_needed
            content.addView(messageCard(getString(message)).apply {
                if (!hasUsageAccess) setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            })
            return
        }
        content.addView(headerCard(effectiveLaunches, effectiveTotal))
        if (sessions.isEmpty()) {
            content.addView(messageCard(getString(R.string.usage_timeline_recent_details_unavailable)))
            return
        }
        content.addView(sectionTitle(getString(R.string.usage_timeline_title)))

        val grouped = sessions.asReversed().groupBy { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.startMs)) }
        grouped.forEach { (date, daySessions) ->
            content.addView(dayTitle(date))
            daySessions.forEachIndexed { index, session ->
                content.addView(timelineRow(session, first = index == 0, last = index == daySessions.lastIndex))
            }
        }
    }

    private fun headerCard(launches: Int, totalMs: Long): MaterialCardView {
        val card = baseCard()
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(appIcon(packageName))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(14)
            }
            addView(TextView(this@AppLaunchDetailActivity).apply {
                text = label
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@AppLaunchDetailActivity).apply {
                text = getString(
                    R.string.app_launch_detail_summary,
                    resources.getQuantityString(R.plurals.app_launches_count, launches, launches),
                    StatsFormat.prettyMsWithSeconds(totalMs)
                )
                alpha = 0.75f
                textSize = 13f
            })
        })
        card.addView(row)
        return card
    }

    private fun timelineRow(session: UsageTimelineRepo.AppSession, first: Boolean, last: Boolean): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(timelineMarker(first, last))
            addView(MaterialCardView(this@AppLaunchDetailActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    bottomMargin = dp(8)
                }
                radius = dp(22).toFloat()
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(this@AppLaunchDetailActivity, R.color.switchly_card_stroke)
                setCardBackgroundColor(ContextCompat.getColor(this@AppLaunchDetailActivity, R.color.switchly_card_bg))
                addView(LinearLayout(this@AppLaunchDetailActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addView(LinearLayout(this@AppLaunchDetailActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        addView(TextView(this@AppLaunchDetailActivity).apply {
                            text = timeText(session.startMs)
                            textSize = 15f
                            setTypeface(typeface, Typeface.BOLD)
                        })
                        addView(TextView(this@AppLaunchDetailActivity).apply {
                            text = getString(R.string.usage_timeline_row, label, timeText(session.startMs), timeText(session.endMs), StatsFormat.prettyMsWithSeconds(session.durationMs))
                            alpha = 0.72f
                            textSize = 12.5f
                        })
                    })
                    addView(TextView(this@AppLaunchDetailActivity).apply {
                        text = StatsFormat.prettyMsWithSeconds(session.durationMs)
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(AccentColor.getAccentColorInt(this@AppLaunchDetailActivity))
                    })
                })
            })
        }
    }

    private fun timelineMarker(first: Boolean, last: Boolean): LinearLayout {
        val lineColor = ContextCompat.getColor(this, R.color.switchly_card_stroke)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.MATCH_PARENT)
            addView(View(this@AppLaunchDetailActivity).apply {
                setBackgroundColor(if (first) android.graphics.Color.TRANSPARENT else lineColor)
            }, LinearLayout.LayoutParams(dp(1), dp(12)))
            addView(View(this@AppLaunchDetailActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AccentColor.getAccentColorInt(this@AppLaunchDetailActivity))
                }
            }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(View(this@AppLaunchDetailActivity).apply {
                setBackgroundColor(if (last) android.graphics.Color.TRANSPARENT else lineColor)
            }, LinearLayout.LayoutParams(dp(1), 0, 1f))
        }
    }

    private fun messageCard(message: String): MaterialCardView {
        val card = baseCard()
        card.addView(TextView(this).apply {
            text = message
            textSize = 14f
            alpha = 0.82f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        })
        return card
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun dayTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(28), dp(10), 0, dp(6))
    }

    private fun baseCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(22).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@AppLaunchDetailActivity, R.color.switchly_card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(this@AppLaunchDetailActivity, R.color.switchly_card_bg))
        }
    }

    private fun appIcon(packageName: String) =
        runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: ContextCompat.getDrawable(this, R.drawable.apps_24)

    private fun timeText(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    private fun actionBarSize(): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
        return android.util.TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_RANGE = "range"
        private const val EXTRA_START_MS = "start_ms"
        private const val EXTRA_END_MS = "end_ms"

        fun intent(
            context: Context,
            packageName: String,
            label: String,
            rangeName: String = "week",
            startMs: Long = 0L,
            endMs: Long = 0L
        ): Intent {
            return Intent(context, AppLaunchDetailActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_RANGE, rangeName)
                .putExtra(EXTRA_START_MS, startMs)
                .putExtra(EXTRA_END_MS, endMs)
        }
    }
}
