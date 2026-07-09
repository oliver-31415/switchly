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
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.setPadding
import androidx.core.view.isEmpty
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import at.saltyy.switchly.R
import at.saltyy.switchly.data.prefs.ActiveDurationStore
import at.saltyy.switchly.data.prefs.SwitchModeStore
import at.saltyy.switchly.feature.stats.StatsFormat
import at.saltyy.switchly.theme.AccentColor
import at.saltyy.switchly.ui.EdgeToEdgeUtils
import at.saltyy.switchly.ui.ThemeUtils
import at.saltyy.switchly.ui.dialog.showAccented
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class ActiveTimeStatsActivity : AppCompatActivity() {

    private enum class Range { TODAY, WEEK, MONTH, YEAR, OVERALL }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var totalValue: TextView
    private lateinit var totalSubtitle: TextView
    private lateinit var bucketContainer: LinearLayout
    private lateinit var emptyText: TextView

    private lateinit var chipToday: Chip
    private lateinit var chipWeek: Chip
    private lateinit var chipMonth: Chip
    private lateinit var chipYear: Chip
    private lateinit var chipOverall: Chip

    private var range: Range = Range.TODAY

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(at.saltyy.switchly.util.LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyAccentTheme(this)
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resolveColor(android.R.attr.colorBackground))
        }

        toolbar = MaterialToolbar(this).apply {
            minimumHeight = actionBarSize()
            title = getString(R.string.active_time_title)
            setNavigationIcon(R.drawable.arrow_back_ios_24)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            menu.add(R.string.active_time_info_title).apply {
                setIcon(R.drawable.info_24)
                icon?.mutate()?.setTint(toolbarIconColor())
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                setOnMenuItemClickListener {
                    showInfo()
                    true
                }
            }
            setBackgroundColor(AccentColor.getToolbarColor(this@ActiveTimeStatsActivity))
            navigationIcon?.mutate()?.setTint(toolbarIconColor())
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
            setPadding(dp(16))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val chipGroup = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingHorizontal = dp(8)
        }
        chipToday = rangeChip(R.string.active_time_today, Range.TODAY)
        chipWeek = rangeChip(R.string.active_time_week, Range.WEEK)
        chipMonth = rangeChip(R.string.active_time_month, Range.MONTH)
        chipYear = rangeChip(R.string.active_time_year, Range.YEAR)
        chipOverall = rangeChip(R.string.active_time_overall, Range.OVERALL)
        listOf(chipToday, chipWeek, chipMonth, chipYear, chipOverall).forEach(chipGroup::addView)
        content.addView(chipGroup)

        chipGroup.check(chipToday.id)
        syncRangeChipUi(chipToday.id)

        val totalCard = MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = dp(2).toFloat()
            useCompatPadding = true
            applySwitchlyCardColors()
        }
        val totalContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16))
        }
        totalContent.addView(TextView(this).apply {
            text = getString(R.string.active_time_total_label)
            textSize = 14f
            alpha = 0.74f
        })
        totalValue = TextView(this).apply {
            textSize = 30f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(AccentColor.getAccentColorInt(this@ActiveTimeStatsActivity))
        }
        totalContent.addView(totalValue)
        totalSubtitle = TextView(this).apply {
            textSize = 13f
            alpha = 0.78f
        }
        totalContent.addView(totalSubtitle)
        totalCard.addView(totalContent)
        content.addView(totalCard)

        emptyText = TextView(this).apply {
            text = getString(R.string.active_time_no_data)
            textSize = 14f
            alpha = 0.72f
            visibility = View.GONE
            setPadding(dp(12))
        }
        content.addView(emptyText)

        bucketContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(bucketContainer)

        setContentView(root)

        EdgeToEdgeUtils.setupClassic(activity = this, toolbar = toolbar)

        refresh()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(30_000L)
                }
            }
        }
    }

    private fun rangeChip(labelRes: Int, target: Range): Chip {
        return Chip(this).apply {
            id = View.generateViewId()
            text = getString(labelRes)
            isCheckable = true
            isCheckedIconVisible = false
            checkedIcon = null
            setOnClickListener {
                setRange(target, id)
            }
        }
    }

    private fun setRange(target: Range, chipId: Int) {
        range = target
        syncRangeChipUi(chipId)
        refresh()
    }

    private fun syncRangeChipUi(activeChipId: Int) {
        val activeBg = AccentColor.getAccentColorInt(this)
        val activeText = if (MaterialColors.isColorLight(activeBg)) Color.BLACK else Color.WHITE
        val inactiveBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0)
        val inactiveText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, inactiveText)

        listOf(chipToday, chipWeek, chipMonth, chipYear, chipOverall).forEach { chip ->
            val active = chip.id == activeChipId
            chip.isChecked = active
            chip.isCheckable = true
            chip.isCheckedIconVisible = false
            chip.checkedIcon = null
            chip.isClickable = true
            chip.isActivated = active
            chip.chipBackgroundColor = ColorStateList.valueOf(if (active) activeBg else inactiveBg)
            chip.setTextColor(if (active) activeText else inactiveText)
            chip.chipStrokeColor = ColorStateList.valueOf(if (active) activeBg else outline)
            chip.chipStrokeWidth = resources.displayMetrics.density
            chip.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(activeBg, 0x35))
            chip.jumpDrawablesToCurrentState()
            chip.refreshDrawableState()
        }
    }

    private fun toolbarIconColor(): Int {
        return if (MaterialColors.isColorLight(AccentColor.getToolbarColor(this))) Color.BLACK else Color.WHITE
    }

    private fun showInfo() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.active_time_info_title)
            .setMessage(R.string.active_time_info_body)
            .setPositiveButton(android.R.string.ok, null)
            .showAccented()
    }

    private fun refresh() {
        SwitchModeStore.getActiveDurationMillis(this)

        val totalMs = when (range) {
            Range.TODAY -> ActiveDurationStore.todayMs(this)
            Range.WEEK -> ActiveDurationStore.lastNDaysMs(this, 7)
            Range.MONTH -> ActiveDurationStore.thisMonthMs(this)
            Range.YEAR -> ActiveDurationStore.thisYearMs(this)
            Range.OVERALL -> ActiveDurationStore.overallMs(this)
        }

        totalValue.text = StatsFormat.prettyMs(totalMs)

        val currentSessionMs = SwitchModeStore.getActiveDurationMillis(this)
        val subtitleParts = mutableListOf(
            getString(R.string.active_time_current_session, StatsFormat.prettyMs(currentSessionMs))
        )

        val averageDays = when (range) {
            Range.TODAY -> 1
            Range.WEEK -> 7
            Range.MONTH -> daysElapsedThisMonth()
            Range.YEAR -> daysElapsedThisYear()
            Range.OVERALL -> 0
        }
        if (averageDays > 1) {
            subtitleParts += getString(
                R.string.active_time_average_day,
                StatsFormat.prettyMs(totalMs / averageDays)
            )
        }
        totalSubtitle.text = subtitleParts.joinToString(separator = " · ")

        renderBuckets(bucketsForRange())

        emptyText.visibility = if (totalMs <= 0L && bucketContainer.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun bucketsForRange(): List<ActiveDurationStore.Bucket> {
        return when (range) {
            Range.TODAY -> ActiveDurationStore.dailyBuckets(this, 1)
            Range.WEEK -> ActiveDurationStore.dailyBuckets(this, 7)
            Range.MONTH -> ActiveDurationStore.dailyBuckets(this, daysElapsedThisMonth())
            Range.YEAR -> ActiveDurationStore.monthlyBucketsThisYear(this)
            Range.OVERALL -> ActiveDurationStore.monthlyBucketsOverall(this)
        }
    }

    private fun renderBuckets(buckets: List<ActiveDurationStore.Bucket>) {
        bucketContainer.removeAllViews()

        val max = buckets.maxOfOrNull { it.valueMs }?.coerceAtLeast(1L) ?: 1L
        buckets.forEach { bucket ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = dp(1).toFloat()
                useCompatPadding = true
                applySwitchlyCardColors()
                isClickable = true
                isFocusable = true
                foreground = selectableItemBackground()
                setOnClickListener {
                    startActivity(ActiveTimeDetailActivity.intent(
                        context = this@ActiveTimeStatsActivity,
                        label = bucket.label,
                        timeMillis = bucket.timeMillis,
                        isMonth = range == Range.YEAR || range == Range.OVERALL
                    ))
                }
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }

            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            line.addView(TextView(this).apply {
                text = bucket.label
                textSize = 14f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(TextView(this).apply {
                text = StatsFormat.prettyMs(bucket.valueMs)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            row.addView(line)

            val barBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(resolveColor(com.google.android.material.R.attr.colorSurfaceVariant))
            }
            val bar = android.widget.FrameLayout(this).apply {
                background = barBg
            }
            val fill = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(AccentColor.getAccentColorInt(this@ActiveTimeStatsActivity))
                }
            }
            val fraction = (bucket.valueMs.toFloat() / max.toFloat()).coerceIn(0.04f, 1f)
            bar.addView(fill, android.widget.FrameLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                width = (resources.displayMetrics.widthPixels * 0.72f * fraction).toInt().coerceAtLeast(dp(6))
            })
            row.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)).apply {
                topMargin = dp(6)
            })

            card.addView(row)
            bucketContainer.addView(card)
        }
    }

    private fun daysElapsedThisMonth(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
    }

    private fun daysElapsedThisYear(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.DAY_OF_YEAR).coerceAtLeast(1)
    }

    private fun selectableItemBackground(): android.graphics.drawable.Drawable? {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        return ContextCompat.getDrawable(this, typedValue.resourceId)
    }

    private fun resolveColor(attr: Int): Int {
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

    private fun MaterialCardView.applySwitchlyCardColors() {
        setCardBackgroundColor(ContextCompat.getColor(this@ActiveTimeStatsActivity, R.color.switchly_card_bg))
        strokeColor = ContextCompat.getColor(this@ActiveTimeStatsActivity, R.color.switchly_card_stroke)
        strokeWidth = dp(1)
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, ActiveTimeStatsActivity::class.java)
    }
}
