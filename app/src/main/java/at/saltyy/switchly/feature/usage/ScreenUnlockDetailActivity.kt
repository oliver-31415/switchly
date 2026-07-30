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

class ScreenUnlockDetailActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private var startMs: Long = 0L
    private var endMs: Long = 0L

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)
        startMs = intent.getLongExtra(EXTRA_START, 0L)
        endMs = intent.getLongExtra(EXTRA_END, 0L)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = getString(R.string.screen_unlocks_detail_title)
            setNavigationIcon(R.drawable.keyboard_arrow_left_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundColor(AccentColor.getToolbarColor(this@ScreenUnlockDetailActivity))
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
        if (!UsageStatsRepo.hasUsageAccess(this)) {
            content.addView(messageCard(getString(R.string.screen_unlocks_permission_needed)).apply {
                setOnClickListener { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            })
            return
        }
        content.addView(messageCard(getString(R.string.usage_timeline_loading)))
        val ctx = applicationContext
        val from = startMs
        val to = endMs.coerceAtLeast(from)
        Thread {
            val sessions = UsageTimelineRepo.combinedUsageSessions(ctx, from, to, limit = 120)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                render(sessions)
            }
        }.start()
    }

    private fun render(sessions: List<UsageTimelineRepo.UsageTimelineSession>) {
        content.removeAllViews()
        val duration = (endMs - startMs).coerceAtLeast(0L)
        content.addView(headerCard(duration, sessions.map { it.source.name + ":" + it.id }.distinct().size))
        content.addView(sectionTitle(getString(R.string.usage_timeline_title)))
        if (sessions.isEmpty()) {
            content.addView(messageCard(getString(R.string.screen_unlocks_no_apps)))
            return
        }
        sessions.forEachIndexed { index, session ->
            content.addView(timelineRow(session, first = index == 0, last = index == sessions.lastIndex))
        }
    }

    private fun headerCard(durationMs: Long, appCount: Int): MaterialCardView {
        val card = baseCard()
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@ScreenUnlockDetailActivity).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startMs))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 16f
            })
            addView(TextView(this@ScreenUnlockDetailActivity).apply {
                text = getString(
                    R.string.screen_unlocks_detail_summary,
                    StatsFormat.prettyMsWithSeconds(durationMs),
                    resources.getQuantityString(R.plurals.screen_unlocks_apps_used, appCount, appCount)
                )
                alpha = 0.76f
                textSize = 13f
                setPadding(0, dp(4), 0, 0)
            })
        })
        return card
    }

    private fun timelineRow(session: UsageTimelineRepo.UsageTimelineSession, first: Boolean, last: Boolean): View {
        val label = if (session.source == UsageTimelineRepo.TimelineSource.WEBSITE) {
            session.label
        } else {
            appLabel(session.id)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(timelineMarker(first, last))
            addView(MaterialCardView(this@ScreenUnlockDetailActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    bottomMargin = dp(8)
                }
                radius = dp(22).toFloat()
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(this@ScreenUnlockDetailActivity, R.color.switchly_card_stroke)
                setCardBackgroundColor(ContextCompat.getColor(this@ScreenUnlockDetailActivity, R.color.switchly_card_bg))
                addView(LinearLayout(this@ScreenUnlockDetailActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addView(ImageView(this@ScreenUnlockDetailActivity).apply {
                        setImageDrawable(timelineIcon(session))
                        layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                    })
                    addView(LinearLayout(this@ScreenUnlockDetailActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(12)
                        }
                        addView(TextView(this@ScreenUnlockDetailActivity).apply {
                            text = label
                            textSize = 15f
                            setTypeface(typeface, Typeface.BOLD)
                        })
                        addView(TextView(this@ScreenUnlockDetailActivity).apply {
                            text = getString(R.string.usage_timeline_row, label, timeText(session.startMs), timeText(session.endMs), StatsFormat.prettyMsWithSeconds(session.durationMs))
                            alpha = 0.72f
                            textSize = 12.5f
                        })
                    })
                    addView(TextView(this@ScreenUnlockDetailActivity).apply {
                        text = StatsFormat.prettyMsWithSeconds(session.durationMs)
                        textSize = 13f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(AccentColor.getAccentColorInt(this@ScreenUnlockDetailActivity))
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
            addView(View(this@ScreenUnlockDetailActivity).apply {
                setBackgroundColor(if (first) android.graphics.Color.TRANSPARENT else lineColor)
            }, LinearLayout.LayoutParams(dp(1), dp(12)))
            addView(View(this@ScreenUnlockDetailActivity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(AccentColor.getAccentColorInt(this@ScreenUnlockDetailActivity))
                }
            }, LinearLayout.LayoutParams(dp(10), dp(10)))
            addView(View(this@ScreenUnlockDetailActivity).apply {
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

    private fun baseCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            radius = dp(22).toFloat()
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(this@ScreenUnlockDetailActivity, R.color.switchly_card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(this@ScreenUnlockDetailActivity, R.color.switchly_card_bg))
        }
    }

    private fun appIcon(packageName: String) =
        runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull()
            ?: ContextCompat.getDrawable(this, R.drawable.apps_24)

    private fun timelineIcon(session: UsageTimelineRepo.UsageTimelineSession) =
        if (session.source == UsageTimelineRepo.TimelineSource.WEBSITE) {
            ContextCompat.getDrawable(this, R.drawable.language_24)
        } else {
            appIcon(session.id)
        }

    private fun appLabel(packageName: String): String =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)

    private fun timeText(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    private fun actionBarSize(): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)
        return android.util.TypedValue.complexToDimensionPixelSize(typedValue.data, resources.displayMetrics)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val EXTRA_START = "start"
        private const val EXTRA_END = "end"

        fun intent(context: Context, startMs: Long, endMs: Long): Intent {
            return Intent(context, ScreenUnlockDetailActivity::class.java)
                .putExtra(EXTRA_START, startMs)
                .putExtra(EXTRA_END, endMs)
        }
    }
}
