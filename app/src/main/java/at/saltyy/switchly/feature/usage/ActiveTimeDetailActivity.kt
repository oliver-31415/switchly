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
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ActiveDurationStore
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.util.LocaleHelper
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.text.DateFormat

class ActiveTimeDetailActivity : AppCompatActivity() {

    private data class GraphEntry(
        val label: String,
        val valueMs: Long,
        val subtitle: String
    )

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val timeMillis = intent.getLongExtra(EXTRA_TIME_MILLIS, 0L)
        val isMonth = intent.getBooleanExtra(EXTRA_IS_MONTH, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = label.ifBlank { getString(R.string.active_time_title) }
            setNavigationIcon(R.drawable.keyboard_arrow_left_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            setBackgroundColor(AccentColor.getToolbarColor(this@ActiveTimeDetailActivity))
        }
        UsageInfoAction.attach(this, toolbar, R.string.active_time_info_title, R.string.active_time_info_body)

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
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        if (isMonth) {
            renderMonthDetails(content, timeMillis)
        } else {
            renderDayDetails(content, timeMillis)
        }

        setContentView(root)
        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)
    }

    private fun renderDayDetails(content: LinearLayout, timeMillis: Long) {
        val sessions = ActiveDurationStore.daySessions(this, timeMillis)
        val total = sessions.sumOf { it.durationMs }
        val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)

        content.addView(totalCard(
            title = getString(R.string.active_time_details_total_label),
            value = StatsFormat.prettyMs(total)
        ))

        content.addView(sectionHeader(getString(R.string.active_time_sessions_title)))
        if (sessions.isEmpty()) {
            content.addView(emptyText(getString(R.string.active_time_no_sessions)))
        } else {
            sessions.forEachIndexed { index, session ->
                content.addView(rowCard(
                    title = getString(R.string.active_time_session_title, index + 1),
                    summary = "${timeFmt.format(java.util.Date(session.startMs))} – ${timeFmt.format(java.util.Date(session.endMs))}",
                    value = StatsFormat.prettyMs(session.durationMs),
                    iconRes = R.drawable.schedule_24
                ))
            }
        }

        content.addView(sectionHeader(getString(R.string.active_time_activity_history_title)))
        val historyEntries = ActivityHistoryRepository.entriesForDay(this, timeMillis)
        if (historyEntries.isEmpty()) {
            content.addView(emptyText(getString(R.string.active_time_activity_history_empty)))
        } else {
            historyEntries.forEach { entry ->
                content.addView(rowCard(
                    title = entry.title,
                    summary = entry.summary,
                    value = timeFmt.format(java.util.Date(entry.timeMillis)),
                    iconRes = entry.iconRes
                ))
            }
        }
    }

    private fun renderMonthDetails(content: LinearLayout, monthStartMs: Long) {
        val days = ActiveDurationStore.dailyBucketsForMonth(this, monthStartMs)
            .filter { it.valueMs > 0L }
        val total = days.sumOf { it.valueMs }

        content.addView(totalCard(
            title = getString(R.string.active_time_details_total_label),
            value = StatsFormat.prettyMs(total)
        ))

        if (days.isEmpty()) {
            content.addView(emptyText(getString(R.string.active_time_no_sessions)))
            return
        }

        content.addView(graphCard(
            title = getString(R.string.active_time_graph_days),
            entries = days.map { day ->
                GraphEntry(
                    label = day.label,
                    valueMs = day.valueMs,
                    subtitle = StatsFormat.prettyMs(day.valueMs)
                )
            }
        ))

        days.forEach { day ->
            content.addView(rowCard(
                title = day.label,
                summary = getString(R.string.active_time_day_sessions_hint),
                value = StatsFormat.prettyMs(day.valueMs),
                iconRes = R.drawable.bar_chart_24,
                onClick = {
                    startActivity(intent(this, day.label, day.timeMillis, isMonth = false))
                }
            ))
        }
    }

    private fun sectionHeader(title: String): TextView =
        TextView(this).apply {
            text = title
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            alpha = 0.82f
            setPadding(dp(4), dp(18), dp(4), dp(4))
        }

    private fun totalCard(title: String, value: String): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = dp(2).toFloat()
            useCompatPadding = true
            applySwitchlyCardColors()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            alpha = 0.74f
        })
        box.addView(TextView(this).apply {
            text = value
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(AccentColor.getAccentColorInt(this@ActiveTimeDetailActivity))
        })
        card.addView(box)
        return card
    }

    private fun graphCard(title: String, entries: List<GraphEntry>): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = dp(2).toFloat()
            useCompatPadding = true
            applySwitchlyCardColors()
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })

        val max = entries.maxOfOrNull { it.valueMs }?.coerceAtLeast(1L) ?: 1L
        entries.forEach { entry ->
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            labelRow.addView(TextView(this).apply {
                text = entry.label
                textSize = 13f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            labelRow.addView(TextView(this).apply {
                text = StatsFormat.prettyMs(entry.valueMs)
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(AccentColor.getAccentColorInt(this@ActiveTimeDetailActivity))
            })
            box.addView(labelRow)

            if (entry.subtitle.isNotBlank()) {
                box.addView(TextView(this).apply {
                    text = entry.subtitle
                    textSize = 12f
                    alpha = 0.68f
                })
            }

            val bar = FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(resolveAttrColor(com.google.android.material.R.attr.colorSurfaceVariant))
                }
            }
            val fill = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(AccentColor.getAccentColorInt(this@ActiveTimeDetailActivity))
                }
            }
            val fraction = (entry.valueMs.toFloat() / max.toFloat()).coerceIn(0.04f, 1f)
            bar.addView(fill, FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.70f * fraction).toInt().coerceAtLeast(dp(6)),
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            box.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            ).apply {
                topMargin = dp(6)
            })
        }

        card.addView(box)
        return card
    }

    private fun rowCard(
        title: String,
        summary: String,
        value: String,
        iconRes: Int,
        onClick: (() -> Unit)? = null
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = dp(1).toFloat()
            useCompatPadding = true
            applySwitchlyCardColors()
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                foreground = selectableItemBackground()
                setOnClickListener { onClick() }
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(AccentColor.getAccentColorInt(this@ActiveTimeDetailActivity))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(24), dp(24)))

        row.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        texts.addView(TextView(this).apply {
            text = summary
            textSize = 13f
            alpha = 0.72f
        })
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = value
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(AccentColor.getAccentColorInt(this@ActiveTimeDetailActivity))
        })

        card.addView(row)
        return card
    }

    private fun emptyText(textValue: String): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 14f
            alpha = 0.72f
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return ContextCompat.getDrawable(this, typedValue.resourceId)
    }

    private fun MaterialCardView.applySwitchlyCardColors() {
        setCardBackgroundColor(ContextCompat.getColor(this@ActiveTimeDetailActivity, R.color.switchly_card_bg))
        strokeColor = ContextCompat.getColor(this@ActiveTimeDetailActivity, R.color.switchly_card_stroke)
        strokeWidth = dp(1)
    }

    private fun resolveAttrColor(attr: Int): Int =
        MaterialColors.getColor(this, attr, Color.TRANSPARENT)

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

    companion object {
        private const val EXTRA_LABEL = "extra_label"
        private const val EXTRA_TIME_MILLIS = "extra_time_millis"
        private const val EXTRA_IS_MONTH = "extra_is_month"

        fun intent(context: Context, label: String, timeMillis: Long, isMonth: Boolean): Intent =
            Intent(context, ActiveTimeDetailActivity::class.java)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_TIME_MILLIS, timeMillis)
                .putExtra(EXTRA_IS_MONTH, isMonth)
    }
}
