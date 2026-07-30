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
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

class ActiveTimeActivity : AppCompatActivity() {

    private enum class Range { TODAY, WEEK, MONTH, YEAR, CUSTOM }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var totalValue: TextView
    private lateinit var totalSubtitle: TextView
    private lateinit var bucketContainer: LinearLayout
    private lateinit var emptyText: TextView

    private lateinit var chipToday: MaterialButton
    private lateinit var chipWeek: MaterialButton
    private lateinit var chipMonth: MaterialButton
    private lateinit var chipYear: MaterialButton
    private lateinit var chipCustom: MaterialButton
    private lateinit var customRangeSummary: LinearLayout
    private lateinit var customRangeValue: TextView

    private var range: Range = Range.TODAY
    private var customRangeStartMillis: Long? = null
    private var customRangeEndMillis: Long? = null
    private var customRangePickerShowing = false

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
            setNavigationIcon(R.drawable.keyboard_arrow_left_24)
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
            setBackgroundColor(AccentColor.getToolbarColor(this@ActiveTimeActivity))
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

        val chipGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        chipToday = rangeButton(R.string.active_time_today, Range.TODAY)
        chipWeek = rangeButton(R.string.active_time_week, Range.WEEK)
        chipMonth = rangeButton(R.string.active_time_month, Range.MONTH)
        chipYear = rangeButton(R.string.active_time_year, Range.YEAR)
        chipCustom = calendarRangeButton(Range.CUSTOM)
        listOf(chipToday, chipWeek, chipMonth, chipYear, chipCustom).forEach(chipGroup::addView)
        content.addView(chipGroup)
        customRangeSummary = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        customRangeValue = TextView(this).apply {
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(MaterialColors.getColor(this@ActiveTimeActivity, com.google.android.material.R.attr.colorOnSurface, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val clearCustomRange = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.stats_range_clear)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(36)
            minimumHeight = dp(36)
            insetTop = 0
            insetBottom = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            setOnClickListener {
                customRangeStartMillis = null
                customRangeEndMillis = null
                setRange(Range.TODAY, chipToday.id)
            }
        }
        customRangeSummary.addView(customRangeValue)
        customRangeSummary.addView(clearCustomRange)
        content.addView(customRangeSummary)

        chipGroup.check(chipToday.id)
        syncRangeChipUi(chipToday.id)

        val totalCard = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
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
            setTextColor(AccentColor.getAccentColorInt(this@ActiveTimeActivity))
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

    private fun rangeButton(labelRes: Int, target: Range): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = getString(labelRes)
            isCheckable = true
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            minHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(4)
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f)
            setOnClickListener {
                setRange(target, id)
            }
        }
    }

    private fun calendarRangeButton(target: Range): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId()
            text = ""
            contentDescription = getString(R.string.activity_history_range_custom)
            icon = ContextCompat.getDrawable(this@ActiveTimeActivity, R.drawable.calendar_month_24)
            iconTint = ColorStateList.valueOf(AccentColor.getAccentColorInt(this@ActiveTimeActivity))
            iconPadding = 0
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            isCheckable = true
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(8), 0, dp(8), 0)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(4)
            setAllCaps(false)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(40))
            setOnClickListener {
                if (target == Range.CUSTOM) showCustomRangePicker()
            }
        }
    }

    private fun setRange(target: Range, chipId: Int) {
        if (!ensureRangeAllowed(target)) {
            syncRangeChipUi(chipIdForRange(range))
            return
        }
        if (target == Range.CUSTOM) {
            showCustomRangePicker()
            return
        }
        range = target
        syncRangeChipUi(chipId)
        refresh()
    }

    private fun ensureRangeAllowed(target: Range): Boolean {
        if (target == Range.TODAY || StatsPremiumGate.canUseExtendedStats(this)) {
            return true
        }
        StatsPremiumGate.show(this)
        return false
    }

    private fun chipIdForRange(target: Range): Int {
        return when (target) {
            Range.TODAY -> chipToday.id
            Range.WEEK -> chipWeek.id
            Range.MONTH -> chipMonth.id
            Range.YEAR -> chipYear.id
            Range.CUSTOM -> chipCustom.id
        }
    }

    private fun syncRangeChipUi(activeChipId: Int) {
        val activeBg = AccentColor.getAccentColorInt(this)
        val activeText = if (MaterialColors.isColorLight(activeBg)) Color.BLACK else Color.WHITE
        val inactiveBg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, 0)
        val inactiveText = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, 0)
        val outline = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline, inactiveText)

        listOf(chipToday, chipWeek, chipMonth, chipYear, chipCustom).forEach { button ->
            val active = button.id == activeChipId
            button.isChecked = active
            button.isCheckable = true
            button.isActivated = active
            button.backgroundTintList = ColorStateList.valueOf(if (active) activeBg else inactiveBg)
            button.setTextColor(if (active) activeText else inactiveText)
            button.iconTint = ColorStateList.valueOf(if (active) activeText else inactiveText)
            button.strokeColor = ColorStateList.valueOf(if (active) activeBg else outline)
            button.strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            button.rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(activeBg, 0x35))
            button.jumpDrawablesToCurrentState()
            button.refreshDrawableState()
        }
        updateCustomRangeSummary()
    }

    private fun updateCustomRangeSummary() {
        val start = customRangeStartMillis
        val end = customRangeEndMillis
        if (range != Range.CUSTOM || start == null || end == null) {
            customRangeSummary.visibility = View.GONE
            return
        }
        customRangeSummary.visibility = View.VISIBLE
        val fmt = DateFormat.getDateInstance(DateFormat.SHORT)
        customRangeValue.text = getString(
            R.string.activity_history_range_custom_value,
            fmt.format(Date(start)),
            fmt.format(Date(end))
        )
    }

    private fun showCustomRangePicker() {
        if (!ensureRangeAllowed(Range.CUSTOM)) {
            syncRangeChipUi(chipIdForRange(range))
            return
        }
        if (customRangePickerShowing || supportFragmentManager.isStateSaved) {
            return
        }
        customRangePickerShowing = true
        val now = System.currentTimeMillis()
        val currentStart = customRangeStartMillis ?: startOfTodayMillis()
        val currentEnd = customRangeEndMillis ?: now
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTheme(com.google.android.material.R.style.ThemeOverlay_MaterialComponents_MaterialCalendar)
            .setTitleText(R.string.activity_history_range_custom)
            .setSelection(androidx.core.util.Pair(localDayToDatePickerUtcMillis(currentStart), localDayToDatePickerUtcMillis(currentEnd)))
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val startUtc = selection.first ?: return@addOnPositiveButtonClickListener
            val endUtc = selection.second ?: startUtc
            customRangeStartMillis = datePickerUtcMillisToLocalDayStart(minOf(startUtc, endUtc))
            customRangeEndMillis = datePickerUtcMillisToLocalDayEnd(maxOf(startUtc, endUtc))
            range = Range.CUSTOM
            syncRangeChipUi(chipCustom.id)
            refresh()
        }
        picker.addOnDismissListener { customRangePickerShowing = false }
        runCatching { picker.show(supportFragmentManager, "active_time_custom_range") }
            .onSuccess { UsageDatePickerAccentTint.apply(this, picker) }
            .onFailure { customRangePickerShowing = false }
    }

    private fun toolbarIconColor(): Int {
        return if (MaterialColors.isColorLight(AccentColor.getToolbarColor(this))) {
            Color.BLACK
        } else {
            Color.WHITE
        }
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
            Range.CUSTOM -> customRangeStartMillis?.let { start ->
                customRangeEndMillis?.let { end -> ActiveDurationStore.rangeMs(this, start, end) }
            } ?: 0L
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
            Range.CUSTOM -> customRangeDays()
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
            Range.CUSTOM -> customRangeStartMillis?.let { start ->
                customRangeEndMillis?.let { end -> ActiveDurationStore.dailyBucketsForRange(this, start, end) }
            } ?: emptyList()
        }
    }

    private fun renderBuckets(buckets: List<ActiveDurationStore.Bucket>) {
        bucketContainer.removeAllViews()

        if (range == Range.TODAY) {
            renderTodayDetails()
            return
        }

        val max = buckets.maxOfOrNull { it.valueMs }?.coerceAtLeast(1L) ?: 1L
        buckets.forEach { bucket ->
            val card = MaterialCardView(this).apply {
                radius = dp(26).toFloat()
                cardElevation = dp(1).toFloat()
                useCompatPadding = true
                applySwitchlyCardColors()
                isClickable = true
                isFocusable = true
                foreground = selectableItemBackground()
                setOnClickListener {
                    startActivity(ActiveTimeDetailActivity.intent(
                        context = this@ActiveTimeActivity,
                        label = bucket.label,
                        timeMillis = bucket.timeMillis,
                        isMonth = range == Range.YEAR
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
                    setColor(AccentColor.getAccentColorInt(this@ActiveTimeActivity))
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

    private fun renderTodayDetails() {
        val now = System.currentTimeMillis()
        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        val sessions = ActiveDurationStore.daySessions(this, now)

        bucketContainer.addView(todaySectionHeader(getString(R.string.active_time_sessions_title)))
        if (sessions.isEmpty()) {
            bucketContainer.addView(todayEmptyText(getString(R.string.active_time_no_sessions)))
        } else {
            sessions.forEachIndexed { index, session ->
                bucketContainer.addView(todayDetailCard(
                    title = getString(R.string.active_time_session_title, index + 1),
                    summary = "${timeFormat.format(Date(session.startMs))} – ${timeFormat.format(Date(session.endMs))}",
                    value = StatsFormat.prettyMs(session.durationMs),
                    iconRes = R.drawable.schedule_24
                ))
            }
        }

        bucketContainer.addView(todaySectionHeader(getString(R.string.active_time_activity_history_title)))
        val historyEntries = ActivityHistoryRepository.entriesForDay(this, now, limit = 40)
            .sortedByDescending { it.timeMillis }
        if (historyEntries.isEmpty()) {
            bucketContainer.addView(todayEmptyText(getString(R.string.active_time_activity_history_empty)))
        } else {
            historyEntries.forEach { entry ->
                bucketContainer.addView(todayDetailCard(
                    title = entry.title,
                    summary = entry.summary,
                    value = timeFormat.format(Date(entry.timeMillis)),
                    iconRes = entry.iconRes
                ))
            }
        }
    }

    private fun todaySectionHeader(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        alpha = 0.82f
        setPadding(dp(4), dp(18), dp(4), dp(4))
    }

    private fun todayEmptyText(message: String): TextView = TextView(this).apply {
        text = message
        textSize = 13f
        alpha = 0.72f
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun todayDetailCard(
        title: String,
        summary: String,
        value: String,
        iconRes: Int,
    ): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(26).toFloat()
            cardElevation = dp(1).toFloat()
            useCompatPadding = true
            applySwitchlyCardColors()
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        row.addView(ImageView(this).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(AccentColor.getAccentColorInt(this@ActiveTimeActivity))
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        row.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textColumn.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        if (summary.isNotBlank()) {
            textColumn.addView(TextView(this).apply {
                text = summary
                textSize = 13f
                alpha = 0.72f
            })
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = value
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setPadding(dp(8), 0, 0, 0)
        })

        card.addView(row)
        return card
    }

    private fun daysElapsedThisMonth(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.DAY_OF_MONTH).coerceAtLeast(1)
    }

    private fun daysElapsedThisYear(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.DAY_OF_YEAR).coerceAtLeast(1)
    }

    private fun customRangeDays(): Int {
        val start = customRangeStartMillis ?: return 0
        val end = customRangeEndMillis ?: return 0
        return (((end - start).coerceAtLeast(0L) / DAY_MILLIS) + 1L).toInt().coerceAtLeast(1)
    }

    private fun startOfTodayMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun localDayToDatePickerUtcMillis(localMillis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = localMillis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    private fun datePickerUtcMillisToLocalDayStart(utcMillis: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }

    private fun datePickerUtcMillisToLocalDayEnd(utcMillis: Long): Long {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = utcMillis }
        return Calendar.getInstance().apply {
            clear()
            set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 23, 59, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
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
        setCardBackgroundColor(ContextCompat.getColor(this@ActiveTimeActivity, R.color.switchly_card_bg))
        strokeColor = ContextCompat.getColor(this@ActiveTimeActivity, R.color.switchly_card_stroke)
        strokeWidth = dp(1)
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        fun intent(context: Context): Intent =
            Intent(context, ActiveTimeActivity::class.java)
    }
}
